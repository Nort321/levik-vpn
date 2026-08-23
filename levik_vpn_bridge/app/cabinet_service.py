from __future__ import annotations

import base64
import hashlib
import hmac
import ipaddress
import re
from datetime import datetime, timezone
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit
from uuid import NAMESPACE_URL, UUID, uuid5

from app import mtproto, wdtt
from app.commerce import CommerceError, catalog as commerce_catalog, checkout_quote
from app.config import Settings, decode_cabinet_secret
from app.delivery import payment_payload
from app.formatters import (
    MOBILE_PLAN,
    MULTI_PLAN,
    device_name,
    mobile_traffic_amount_bytes,
    mobile_traffic_config,
    mobile_traffic_enabled,
    mobile_traffic_price_rub,
    payment_method_amount,
    payment_method_request_amount,
    payment_method_title,
    plan_name,
    slot_amount,
    slot_price_rub,
    slot_traffic_delta_bytes,
    status_label,
    used_traffic_bytes,
)
from app.orders import OrderAlreadyInProgress, OrderStore
from app.platega import PlategaApiError, PlategaClient
from app.remnawave import RemnawaveApiError, RemnawaveClient
from app.tariffs import subscription_tariff_id_for_user
from app.multi_subscription import decorate_user, is_multi_user, merge_users, mobile_user
from app.trials import (
    TrialActivationError,
    activate_trial as activate_trial_access,
    trial_available,
)


class CabinetServiceError(RuntimeError):
    def __init__(self, status: int, code: str, *, retryable: bool = False) -> None:
        self.status = status
        self.code = code
        self.retryable = retryable
        super().__init__(code)


TELEGRAM_BOT_USERNAME_RE = re.compile(r"^[A-Za-z0-9_]{5,32}$")
MTPROTO_SECRET_RE = re.compile(r"^(?:[0-9a-fA-F]{32}|(?:dd|ee)[0-9a-fA-F]{32,})$")
HOST_LABEL_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,100}$")
MAX_SAFE_INTEGER = 2**53 - 1
FALLBACK_DATETIME = "1970-01-01T00:00:00Z"
WDTT_PUBLIC_UUID_NAMESPACE = uuid5(
    NAMESPACE_URL,
    "https://leviknet.com/cabinet/wdtt",
)
REMNAWAVE_PUBLIC_UUID_NAMESPACE = uuid5(
    NAMESPACE_URL,
    "https://leviknet.com/cabinet/remnawave",
)


def _as_int(value: object, default: int = 0) -> int:
    if value is None or value == "":
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _bounded_int(
    value: object,
    *,
    minimum: int,
    maximum: int,
    default: int,
) -> int:
    parsed = _as_int(value, default)
    return min(maximum, max(minimum, parsed))


def _display_text(
    value: object,
    *,
    max_length: int,
    fallback: str,
) -> str:
    candidate = " ".join(str(value or "").split())
    if not candidate:
        candidate = fallback
    return candidate[:max_length]


def _identifier(value: object, *, fallback: str) -> str:
    candidate = str(value or "")
    return candidate if IDENTIFIER_RE.fullmatch(candidate) else fallback


def _optional_identifier(value: object) -> str | None:
    candidate = str(value or "")
    return candidate if IDENTIFIER_RE.fullmatch(candidate) else None


def _uuid_string(value: object) -> str | None:
    try:
        return str(UUID(str(value)))
    except (TypeError, ValueError, AttributeError):
        return None


def _wdtt_public_uuid(internal_user_uuid: object) -> str | None:
    internal_value = str(internal_user_uuid or "")
    if not internal_value:
        return None
    return str(uuid5(WDTT_PUBLIC_UUID_NAMESPACE, internal_value))


def _remnawave_public_uuid(internal_user_uuid: object) -> str | None:
    internal_value = str(internal_user_uuid or "")
    if not internal_value:
        return None
    direct = _uuid_string(internal_value)
    return direct or str(uuid5(REMNAWAVE_PUBLIC_UUID_NAMESPACE, internal_value))


def _public_user_uuid(user: dict[str, Any]) -> str | None:
    if isinstance(user.get("_wdtt_access"), dict):
        return _uuid_string(user.get("uuid"))
    return _remnawave_public_uuid(user.get("uuid"))


def _datetime_string(value: object, *, fallback: str | None = None) -> str | None:
    if not isinstance(value, str) or not value.strip():
        return fallback
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        return fallback
    if parsed.tzinfo is None:
        return fallback
    return parsed.isoformat().replace("+00:00", "Z")


def _subscription_url(value: object) -> str | None:
    candidate = str(value or "")
    if not candidate or len(candidate) > 4096:
        return None
    try:
        parsed = urlsplit(candidate)
        port = parsed.port
    except ValueError:
        return None
    hostname = parsed.hostname or ""
    try:
        ipaddress.ip_address(hostname)
        valid_hostname = True
    except ValueError:
        valid_hostname = bool(
            hostname
            and len(hostname) <= 253
            and all(
                HOST_LABEL_RE.fullmatch(label)
                for label in hostname.split(".")
            )
        )
    if (
        parsed.scheme != "https"
        or not valid_hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
        or (port is not None and not 1 <= port <= 65535)
        or parsed.netloc.endswith(":")
        or not parsed.netloc
    ):
        return None
    return candidate


def _transaction_data(payload: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return {}
    for key in ("transaction", "data", "result"):
        value = payload.get(key)
        if isinstance(value, dict):
            return value
    return payload


def _provider_method(method: dict[str, Any] | None) -> int:
    return _as_int(method.get("platega_method")) if isinstance(method, dict) else 0


def _configured_method(config: dict[str, Any], method_id: str) -> dict[str, Any] | None:
    methods = config.get("methods") if isinstance(config.get("methods"), list) else []
    for method in methods:
        if (
            isinstance(method, dict)
            and method.get("enabled", True)
            and str(method.get("id") or "") == method_id
        ):
            return method
    return None


def _wdtt_user(access: dict[str, object]) -> dict[str, Any]:
    expires_at = _as_int(access.get("expires_at"))
    expire_at = ""
    if expires_at > 0:
        expire_at = datetime.fromtimestamp(expires_at, tz=timezone.utc).isoformat().replace("+00:00", "Z")
    return {
        "uuid": _wdtt_public_uuid(access.get("user_uuid")) or "",
        "username": f"WDTT · {str(access.get('label') or 'Levik VPN')}",
        "status": "ACTIVE"
        if expires_at <= 0 or expires_at > int(datetime.now(timezone.utc).timestamp())
        else "EXPIRED",
        "expireAt": expire_at,
        "hwidDeviceLimit": _as_int(access.get("max_devices")),
        "activeInternalSquads": [{"name": "LTE"}],
        "_wdtt_access": access,
    }


def _sort_users(users: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return sorted(
        users,
        key=lambda user: (
            0 if str(user.get("status") or "").upper() == "ACTIVE" else 1,
            str(user.get("username") or user.get("email") or ""),
        ),
    )


def cabinet_user_key(settings: Settings, actor_id: int) -> str:
    digest = hmac.new(
        decode_cabinet_secret(settings.cabinet_subject_secret),
        str(actor_id).encode("ascii"),
        hashlib.sha256,
    ).digest()
    user_key = base64.urlsafe_b64encode(digest[:18]).decode("ascii").rstrip("=")
    return f"usr_{user_key}"


def cabinet_user(settings: Settings, order_store: OrderStore, actor_id: int) -> dict[str, str]:
    user_key = cabinet_user_key(settings, actor_id)
    actor = order_store.get_cabinet_actor(actor_id)
    if actor_id > 0:
        order_store.remember_cabinet_telegram_actor(
            actor_id=actor_id,
            user_key=user_key,
        )
    profile = order_store.get_bot_user(actor_id) or {}
    first_name = _display_text(
        profile.get("first_name"),
        max_length=160,
        fallback="",
    )
    username = _display_text(
        str(profile.get("username") or "").lstrip("@"),
        max_length=159,
        fallback="",
    )
    is_account = actor is not None and str(actor.get("actor_kind") or "") == "account"
    label = first_name or (
        f"@{username}" if username else "Levik Account" if is_account else "Telegram user"
    )
    result = {"userKey": user_key, "userLabel": label}
    if username and TELEGRAM_BOT_USERNAME_RE.fullmatch(username):
        result["telegramUsername"] = f"@{username}"
    return result


def validate_payment_url(url: str, allowed_hosts: tuple[str, ...]) -> str:
    if not url or len(url) > 2048:
        raise CabinetServiceError(502, "invalid_payment_redirect")
    try:
        parsed = urlsplit(url)
        port = parsed.port
    except ValueError as exc:
        raise CabinetServiceError(502, "invalid_payment_redirect") from exc
    hostname = (parsed.hostname or "").lower().rstrip(".")
    if (
        parsed.scheme != "https"
        or not hostname
        or hostname not in allowed_hosts
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
        or port not in {None, 443}
        or not parsed.netloc
    ):
        raise CabinetServiceError(502, "invalid_payment_redirect")
    return url


def validate_cabinet_return_url(url: str) -> str:
    try:
        parsed = urlsplit(url)
        port = parsed.port
    except ValueError as exc:
        raise CabinetServiceError(503, "cabinet_payment_urls_not_configured") from exc
    if (
        parsed.scheme != "https"
        or (parsed.hostname or "").lower().rstrip(".") != "leviknet.com"
        or parsed.username is not None
        or parsed.password is not None
        or port not in {None, 443}
        or not parsed.path.startswith("/")
    ):
        raise CabinetServiceError(503, "cabinet_payment_urls_not_configured")
    return url


def canonical_proxy_url(url: str) -> str:
    try:
        parsed = urlsplit(url)
        query_items = parse_qsl(parsed.query, keep_blank_values=True)
    except ValueError as exc:
        raise CabinetServiceError(502, "invalid_proxy_response") from exc
    is_tme = (
        parsed.scheme == "https"
        and (parsed.hostname or "").lower().rstrip(".") == "t.me"
        and parsed.path == "/proxy"
        and parsed.port in {None, 443}
        and parsed.username is None
        and parsed.password is None
    )
    is_tg = (
        parsed.scheme == "tg"
        and parsed.netloc == "proxy"
        and parsed.path in {"", "/"}
    )
    if not (is_tme or is_tg) or parsed.fragment:
        raise CabinetServiceError(502, "invalid_proxy_response")
    if len(query_items) != 3 or {key for key, _ in query_items} != {"server", "port", "secret"}:
        raise CabinetServiceError(502, "invalid_proxy_response")
    values = dict(query_items)
    server = values.get("server", "").strip()
    secret = values.get("secret", "").strip()
    try:
        port = int(values.get("port", ""))
    except ValueError as exc:
        raise CabinetServiceError(502, "invalid_proxy_response") from exc
    try:
        ipaddress.ip_address(server)
        valid_server = True
    except ValueError:
        normalized_server = server.rstrip(".")
        valid_server = bool(
            normalized_server
            and len(normalized_server) <= 253
            and all(HOST_LABEL_RE.fullmatch(label) for label in normalized_server.split("."))
        )
        server = normalized_server
    if (
        not valid_server
        or not 1 <= port <= 65535
        or not MTPROTO_SECRET_RE.fullmatch(secret)
    ):
        raise CabinetServiceError(502, "invalid_proxy_response")
    return f"tg://proxy?{urlencode({'server': server, 'port': str(port), 'secret': secret})}"


class CabinetService:
    def __init__(
        self,
        *,
        settings: Settings,
        remnawave: RemnawaveClient,
        order_store: OrderStore,
    ) -> None:
        self.settings = settings
        self.remnawave = remnawave
        self.order_store = order_store

    async def load_users(self, telegram_id: int) -> list[dict[str, Any]]:
        wdtt_users = [
            _wdtt_user(access)
            for access in self.order_store.get_wdtt_accesses_by_telegram_id(telegram_id)
        ]
        try:
            remnawave_users = await self.remnawave.get_users_by_telegram_id(telegram_id)
        except RemnawaveApiError as exc:
            if not wdtt_users:
                raise CabinetServiceError(502, "vpn_service_unavailable", retryable=True) from exc
            remnawave_users = []
        safe_remnawave_users = (
            [user for user in remnawave_users if isinstance(user, dict)]
            if isinstance(remnawave_users, list)
            else []
        )
        safe_remnawave_users = merge_users(
            self.settings,
            safe_remnawave_users,
            self.order_store.list_multi_subscriptions_by_telegram_id(telegram_id),
        )
        return _sort_users(wdtt_users + safe_remnawave_users)

    async def owned_user(self, telegram_id: int, user_uuid: str) -> dict[str, Any]:
        normalized_uuid = _uuid_string(user_uuid)
        if normalized_uuid is None:
            raise CabinetServiceError(404, "subscription_not_found")
        for user in await self.load_users(telegram_id):
            candidate_uuid = _public_user_uuid(user)
            if candidate_uuid and hmac.compare_digest(candidate_uuid, normalized_uuid):
                return user
        raise CabinetServiceError(404, "subscription_not_found")

    def catalog(self, telegram_id: int) -> dict[str, object]:
        return {
            "ok": True,
            **commerce_catalog(self.settings, self.order_store, telegram_id),
        }

    async def snapshot(self, telegram_id: int) -> dict[str, object]:
        is_account_actor = self.order_store.is_cabinet_account_actor(telegram_id)
        users = await self.load_users(telegram_id)
        subscriptions: list[dict[str, object]] = []
        for user in users:
            user_uuid = _public_user_uuid(user)
            if user_uuid is None:
                continue
            wdtt_access = user.get("_wdtt_access")
            is_wdtt = isinstance(wdtt_access, dict)
            try:
                if is_wdtt:
                    raw_devices = await wdtt.remote_devices(self.settings, wdtt_access)
                    raw_items = raw_devices.get("devices")
                    devices = (
                        [item for item in raw_items if isinstance(item, dict)]
                        if isinstance(raw_items, list)
                        else []
                    )
                elif is_multi_user(user):
                    devices = []
                    record = user["_multi_subscription"]
                    for component, key in (("regular", "primary_user_uuid"), ("mobile", "mobile_user_uuid")):
                        reference = str(record.get(key) or "")
                        for raw_device in await self.remnawave.get_user_devices(reference):
                            device = dict(raw_device)
                            device["_multi_component"] = component
                            device["_multi_user_uuid"] = reference
                            devices.append(device)
                else:
                    raw_devices = await self.remnawave.get_user_devices(str(user.get("uuid") or ""))
                    devices = [item for item in raw_devices if isinstance(item, dict)] if isinstance(raw_devices, list) else []
            except (RemnawaveApiError, RuntimeError):
                devices = []

            device_items: list[dict[str, str]] = []
            regular_device_items: list[dict[str, str]] = []
            mobile_device_items: list[dict[str, str]] = []
            for device in devices:
                raw_device_id = device.get("device_id") or device.get("hwid")
                if not isinstance(raw_device_id, str) or not 1 <= len(raw_device_id) <= 200:
                    continue
                component = str(device.get("_multi_component") or "")
                public_device_id = f"{component}:{raw_device_id}" if component in {"regular", "mobile"} else raw_device_id
                try:
                    raw_label = device_name(device)
                except (TypeError, ValueError, OverflowError):
                    raw_label = ""
                device_item = {
                        "id": public_device_id,
                        "label": _display_text(
                            raw_label,
                            max_length=160,
                            fallback="Устройство",
                        ),
                    }
                device_items.append(device_item)
                if component == "regular":
                    regular_device_items.append(device_item)
                elif component == "mobile":
                    mobile_device_items.append(device_item)
                if len(device_items) == 100:
                    break

            tariff_id = _identifier(
                subscription_tariff_id_for_user(self.settings.data, user),
                fallback="other",
            )
            traffic_limit = _bounded_int(
                user.get("trafficLimitBytes"),
                minimum=0,
                maximum=MAX_SAFE_INTEGER,
                default=0,
            )
            localized_status = status_label(user)
            active = localized_status == "активна"
            public_status = {
                "активна": "active",
                "истекла": "expired",
                "отключена": "disabled",
            }.get(localized_status, "disabled")
            raw_device_limit = _as_int(user.get("hwidDeviceLimit"))
            mobile_component = mobile_user(user) or {}
            if is_multi_user(user):
                raw_device_limit += _as_int(mobile_component.get("hwidDeviceLimit"))
            device_limit = (
                raw_device_limit
                if 1 <= raw_device_limit <= 100
                else 100
            )
            slots = self.settings.data.get("slots") if isinstance(self.settings.data.get("slots"), dict) else {}
            traffic_addon = (
                mobile_traffic_enabled(self.settings.data)
                and not is_wdtt
                and active
                and plan_name(user) in {MOBILE_PLAN, MULTI_PLAN}
                and traffic_limit > 0
            )
            raw_subscription_url = user.get("subscriptionUrl")
            if not raw_subscription_url and is_wdtt:
                try:
                    raw_subscription_url = wdtt.subscription_url(self.settings, wdtt_access)
                except (TypeError, ValueError):
                    raw_subscription_url = None
            subscription_item: dict[str, object] = {
                    "uuid": user_uuid,
                    "tariffId": tariff_id,
                    "title": _display_text(
                        plan_name(user),
                        max_length=160,
                        fallback="VPN",
                    ),
                    "status": public_status,
                    "expireAt": _datetime_string(user.get("expireAt")),
                    "subscriptionUrl": _subscription_url(raw_subscription_url),
                    "traffic": {
                        "usedBytes": _bounded_int(
                            used_traffic_bytes(user),
                            minimum=0,
                            maximum=MAX_SAFE_INTEGER,
                            default=0,
                        ),
                        "limitBytes": traffic_limit,
                    },
                    "devices": {
                        "used": len(device_items),
                        "limit": device_limit,
                        "items": device_items,
                    },
                    "shield": {
                        "supported": not is_wdtt and _as_int(user.get("id")) > 0,
                        "enabled": (
                            self.order_store.get_shield_enabled(_as_int(user.get("id")))
                            if not is_wdtt and _as_int(user.get("id")) > 0
                            else False
                        ),
                    },
                    "actions": {
                        "renew": active and not is_wdtt,
                        "rotateKey": active and not is_wdtt,
                        "revokeDevice": active,
                        "slotAddon": (
                            active
                            and raw_device_limit > 0
                            and bool(slots.get("enabled", True))
                        ),
                        "trafficAddon": traffic_addon,
                    },
                }
            if is_multi_user(user):
                subscription_item["components"] = {
                    "regular": {
                        "traffic": {"usedBytes": 0, "limitBytes": 0},
                        "devices": {
                            "used": len(regular_device_items),
                            "limit": max(1, _as_int(user.get("hwidDeviceLimit"), 5)),
                            "items": regular_device_items,
                        },
                    },
                    "mobile": {
                        "traffic": {
                            "usedBytes": _bounded_int(used_traffic_bytes(user), minimum=0, maximum=MAX_SAFE_INTEGER, default=0),
                            "limitBytes": traffic_limit,
                        },
                        "devices": {
                            "used": len(mobile_device_items),
                            "limit": max(1, _as_int(mobile_component.get("hwidDeviceLimit"), 1)),
                            "items": mobile_device_items,
                        },
                    },
                }
            subscriptions.append(subscription_item)
            if len(subscriptions) == 100:
                break

        trial = (
            None
            if is_account_actor
            else self.order_store.get_trial_access(telegram_id)
        )
        trial_status = _identifier(
            trial.get("status") if trial else "available",
            fallback="unknown",
        )
        referral_stats = (
            None
            if is_account_actor
            else self.order_store.referral_stats(telegram_id)
        )
        referrals = (
            self.settings.data.get("referrals")
            if isinstance(self.settings.data.get("referrals"), dict)
            else {}
        )
        bot_username = str(referrals.get("bot_username") or "levikvpnbot").strip().lstrip("@")
        if not TELEGRAM_BOT_USERNAME_RE.fullmatch(bot_username):
            bot_username = "levikvpnbot"
        free_proxy = (
            None
            if is_account_actor
            else self.order_store.get_free_mtproto_proxy(telegram_id)
        )
        orders: list[dict[str, object]] = []
        for order in self.order_store.list_user_orders(telegram_id, limit=20):
            try:
                orders.append(self.order_public(order))
            except CabinetServiceError:
                continue
        return {
            "ok": True,
            "user": cabinet_user(self.settings, self.order_store, telegram_id),
            "trial": {
                "eligible": (
                    False
                    if is_account_actor
                    else trial_available(users, trial, telegram_id)
                ),
                "status": "unavailable" if is_account_actor else trial_status,
                "expiresAt": _datetime_string(trial.get("expires_at")) if trial else None,
            },
            "referrals": None if referral_stats is None else {
                "invited": _bounded_int(
                    referral_stats["total"],
                    minimum=0,
                    maximum=MAX_SAFE_INTEGER,
                    default=0,
                ),
                "rewarded": _bounded_int(
                    referral_stats["rewarded"],
                    minimum=0,
                    maximum=MAX_SAFE_INTEGER,
                    default=0,
                ),
                "discountPercent": _bounded_int(
                    referrals.get("discount_percent"),
                    minimum=0,
                    maximum=100,
                    default=10,
                ),
                "rewardDays": _bounded_int(
                    referrals.get("reward_days"),
                    minimum=0,
                    maximum=3650,
                    default=5,
                ),
                "referralLink": f"https://t.me/{bot_username}?start=ref_{telegram_id}",
            },
            "subscriptions": subscriptions,
            "orders": orders,
            "freeProxy": {
                "available": (
                    False
                    if is_account_actor
                    else mtproto.is_enabled(self.settings)
                ),
                "active": not is_account_actor and free_proxy is not None,
            },
        }

    async def activate_trial(self, telegram_id: int) -> dict[str, object]:
        if self.order_store.is_cabinet_account_actor(telegram_id):
            raise CabinetServiceError(422, "trial_not_eligible")
        users = await self.load_users(telegram_id)
        profile = self.order_store.get_bot_user(telegram_id) or {}
        try:
            result = await activate_trial_access(
                telegram_id=telegram_id,
                telegram_username=str(profile.get("username") or "").strip() or None,
                first_name=str(profile.get("first_name") or "").strip() or None,
                component="regular",
                users=users,
                settings=self.settings,
                remnawave=self.remnawave,
                order_store=self.order_store,
            )
        except TrialActivationError as exc:
            statuses = {
                "trial_already_used": 409,
                "trial_in_progress": 409,
                "trial_provisioning_failed": 502,
                "trial_tariff_unavailable": 503,
            }
            raise CabinetServiceError(
                statuses.get(exc.code, 422),
                exc.code,
                retryable=exc.retryable,
            ) from exc
        subscription_uuid = _uuid_string(result.subscription_uuid)
        if subscription_uuid is None:
            raise CabinetServiceError(
                502,
                "trial_provisioning_failed",
                retryable=True,
            )
        return {"ok": True, "subscriptionUuid": subscription_uuid}

    def order_public(self, order: dict[str, object]) -> dict[str, object]:
        raw_payment_url = str(order.get("payment_url") or "")
        payment_url: str | None = None
        if raw_payment_url:
            try:
                payment_url = validate_payment_url(
                    raw_payment_url,
                    self.settings.cabinet_payment_redirect_hosts,
                )
            except CabinetServiceError:
                payment_url = None
        public_kind = {
            "slot": "slot_addon",
            "traffic": "traffic_addon",
        }.get(str(order.get("kind") or ""), str(order.get("kind") or ""))
        order_id = _as_int(order.get("id"))
        if not 1 <= order_id <= MAX_SAFE_INTEGER:
            raise CabinetServiceError(500, "invalid_order_record")
        raw_payment_method = str(order.get("payment_method") or "").removeprefix(
            "platega_"
        )
        return {
            "id": order_id,
            "kind": _identifier(public_kind, fallback="other"),
            "status": _identifier(order.get("status"), fallback="unknown"),
            "tariffId": _optional_identifier(order.get("tariff_id")),
            "months": _bounded_int(
                order.get("period_months"),
                minimum=0,
                maximum=36,
                default=0,
            ),
            "amountRub": _bounded_int(
                order.get("pay_amount_rub") or order.get("price_rub"),
                minimum=0,
                maximum=1_000_000,
                default=0,
            ),
            "paymentMethodId": _identifier(
                raw_payment_method,
                fallback="other",
            ),
            "createdAt": _datetime_string(
                order.get("created_at"),
                fallback=FALLBACK_DATETIME,
            ),
            "paymentUrl": payment_url,
        }

    def order_status(self, telegram_id: int, order_id: int) -> dict[str, object]:
        order = self.order_store.get(order_id)
        if order is None or _as_int(order.get("telegram_id")) != telegram_id:
            raise CabinetServiceError(404, "order_not_found")
        return {"ok": True, "order": self.order_public(order)}

    async def create_order(self, telegram_id: int, payload: dict[str, Any]) -> dict[str, object]:
        platega = (
            self.settings.data.get("platega")
            if isinstance(self.settings.data.get("platega"), dict)
            else {}
        )
        if not bool(platega.get("enabled", False)):
            raise CabinetServiceError(503, "payments_disabled", retryable=True)
        return_url = validate_cabinet_return_url(self.settings.cabinet_payment_return_url)
        failed_url = validate_cabinet_return_url(self.settings.cabinet_payment_failed_url)
        raw_kind = payload.get("kind")
        if not isinstance(raw_kind, str):
            raise CabinetServiceError(400, "invalid_order")
        kind = raw_kind
        required_fields = {
            "access_purchase": {"kind", "tariffId", "months", "paymentMethodId"},
            "access_renewal": {
                "kind",
                "subscriptionUuid",
                "months",
                "paymentMethodId",
            },
            "slot_addon": {"kind", "subscriptionUuid", "paymentMethodId"},
            "traffic_addon": {"kind", "subscriptionUuid", "paymentMethodId"},
        }.get(kind)
        optional_fields = {"tariffId"} if kind == "access_renewal" else set()
        if (
            required_fields is None
            or not required_fields.issubset(payload)
            or not set(payload).issubset(required_fields | optional_fields)
        ):
            raise CabinetServiceError(400, "invalid_order")
        method_value = payload.get("paymentMethodId")
        if not isinstance(method_value, str) or not 1 <= len(method_value) <= 64:
            raise CabinetServiceError(400, "invalid_order")
        method_id = method_value
        profile = self.order_store.get_bot_user(telegram_id) or {}
        username = str(profile.get("username") or "").strip() or None
        first_name = str(profile.get("first_name") or "").strip() or None
        target_user: dict[str, Any] | None = None

        if kind in {"access_purchase", "access_renewal"}:
            tariff_value = payload.get("tariffId")
            tariff_id = tariff_value if isinstance(tariff_value, str) else ""
            months_value = payload.get("months")
            if (
                isinstance(months_value, bool)
                or not isinstance(months_value, int)
                or months_value <= 0
            ):
                raise CabinetServiceError(400, "invalid_order")
            months = months_value
            if kind == "access_renewal":
                subscription_value = payload.get("subscriptionUuid")
                if not isinstance(subscription_value, str):
                    raise CabinetServiceError(400, "invalid_order")
                target_user = await self.owned_user(
                    telegram_id,
                    subscription_value,
                )
                if isinstance(target_user.get("_wdtt_access"), dict):
                    raise CabinetServiceError(422, "renewal_unavailable")
                if status_label(target_user) != "активна":
                    raise CabinetServiceError(422, "renewal_unavailable")
                actual_tariff_id = subscription_tariff_id_for_user(self.settings.data, target_user)
                if tariff_id and tariff_id != actual_tariff_id:
                    raise CabinetServiceError(422, "tariff_mismatch")
                tariff_id = actual_tariff_id
            try:
                quote = checkout_quote(
                    settings=self.settings,
                    order_store=self.order_store,
                    telegram_id=telegram_id,
                    tariff_id=tariff_id,
                    months=months,
                    method_id=method_id,
                    renewal=kind == "access_renewal",
                )
            except CommerceError as exc:
                raise CabinetServiceError(422, exc.code) from exc
            try:
                order = self.order_store.create_access_payment(
                    telegram_id=telegram_id,
                    telegram_username=username,
                    first_name=first_name,
                    tariff_id=str(quote.tariff.get("id") or ""),
                    tariff_title=str(quote.tariff.get("title") or "Тариф"),
                    period_months=months,
                    price_rub=quote.amount_rub,
                    stars_amount=None,
                    kind=kind,
                    target_user_uuid=str(target_user.get("uuid") or "") if target_user else None,
                    target_user_name=str(target_user.get("username") or target_user.get("email") or "")
                    if target_user
                    else None,
                    payment_method=f"platega_{method_id}",
                    pay_amount_rub=quote.pay_amount_rub,
                    base_price_rub=quote.base_amount_rub,
                    discount_percent=quote.discount_percent,
                    discount_rub=quote.discount_rub,
                    referrer_telegram_id=quote.referrer_telegram_id,
                    platega_payment_method=quote.provider_method,
                )
            except OrderAlreadyInProgress as exc:
                raise CabinetServiceError(409, "order_already_in_progress") from exc
            provider_method = quote.provider_method
            request_amount = quote.provider_request_amount_rub
            description = f"Levik VPN: {quote.tariff.get('title') or 'Тариф'}"
        elif kind in {"slot_addon", "traffic_addon"}:
            subscription_value = payload.get("subscriptionUuid")
            if not isinstance(subscription_value, str):
                raise CabinetServiceError(400, "invalid_order")
            target_user = await self.owned_user(
                telegram_id,
                subscription_value,
            )
            if status_label(target_user) != "активна":
                raise CabinetServiceError(422, "subscription_inactive")
            if kind == "traffic_addon" and isinstance(target_user.get("_wdtt_access"), dict):
                raise CabinetServiceError(422, "traffic_addon_unavailable")
            addon_config = (
                self.settings.data.get("slots")
                if kind == "slot_addon"
                else mobile_traffic_config(self.settings.data)
            )
            if not isinstance(addon_config, dict) or not bool(addon_config.get("enabled", True)):
                raise CabinetServiceError(422, "addon_unavailable")
            method = _configured_method(addon_config, method_id)
            provider_method = _provider_method(method)
            if method is None or provider_method <= 0:
                raise CabinetServiceError(422, "payment_method_unavailable")
            base_amount = (
                slot_price_rub(self.settings.data)
                if kind == "slot_addon"
                else mobile_traffic_price_rub(self.settings.data)
            )
            pay_amount = payment_method_amount(base_amount, method)
            request_amount = payment_method_request_amount(pay_amount, method)
            wdtt_access = target_user.get("_wdtt_access")
            target_uuid = (
                str(wdtt_access.get("user_uuid") or "")
                if isinstance(wdtt_access, dict)
                else str(target_user.get("uuid") or "")
            )
            if not target_uuid:
                raise CabinetServiceError(404, "subscription_not_found")
            target_name = str(target_user.get("username") or target_user.get("email") or "")
            if kind == "slot_addon":
                if _as_int(target_user.get("hwidDeviceLimit")) <= 0:
                    raise CabinetServiceError(422, "slot_addon_unavailable")
                traffic_delta = (
                    slot_traffic_delta_bytes(self.settings.data)
                    if plan_name(target_user) in {MOBILE_PLAN, MULTI_PLAN}
                    else 0
                )
                try:
                    order = self.order_store.create_slot_payment(
                        telegram_id=telegram_id,
                        telegram_username=username,
                        first_name=first_name,
                        target_user_uuid=target_uuid,
                        target_user_name=target_name,
                        price_rub=base_amount,
                        stars_amount=None,
                        slots_delta=slot_amount(self.settings.data),
                        payment_method=f"platega_{method_id}",
                        traffic_delta_bytes=traffic_delta,
                        pay_amount_rub=pay_amount,
                        platega_payment_method=provider_method,
                    )
                except OrderAlreadyInProgress as exc:
                    raise CabinetServiceError(409, "order_already_in_progress") from exc
                description = f"Levik VPN: +{slot_amount(self.settings.data)} устройство"
            else:
                if (
                    not mobile_traffic_enabled(self.settings.data)
                    or plan_name(target_user) not in {MOBILE_PLAN, MULTI_PLAN}
                    or _as_int(target_user.get("trafficLimitBytes")) <= 0
                ):
                    raise CabinetServiceError(422, "traffic_addon_unavailable")
                try:
                    order = self.order_store.create_traffic_payment(
                        telegram_id=telegram_id,
                        telegram_username=username,
                        first_name=first_name,
                        target_user_uuid=target_uuid,
                        target_user_name=target_name,
                        price_rub=base_amount,
                        stars_amount=None,
                        traffic_delta_bytes=mobile_traffic_amount_bytes(self.settings.data),
                        payment_method=f"platega_{method_id}",
                        pay_amount_rub=pay_amount,
                        platega_payment_method=provider_method,
                    )
                except OrderAlreadyInProgress as exc:
                    raise CabinetServiceError(409, "order_already_in_progress") from exc
                description = f"Levik VPN: дополнительный трафик · {payment_method_title(method)}"
        else:
            raise CabinetServiceError(400, "invalid_order_kind")

        try:
            async with PlategaClient(self.settings) as client:
                raw_transaction = await client.create_transaction(
                    payment_method=provider_method,
                    amount_rub=request_amount,
                    description=description,
                    return_url=return_url,
                    failed_url=failed_url,
                    payload=payment_payload(order.id, telegram_id),
                    telegram_id=telegram_id,
                    username=username,
                )
        except PlategaApiError as exc:
            self.order_store.mark_payment_canceled(order.id, "payment_creation_failed")
            raise CabinetServiceError(502, "payment_provider_unavailable", retryable=True) from exc
        try:
            transaction = _transaction_data(raw_transaction)
            payment_url = validate_payment_url(
                str(transaction.get("redirect") or transaction.get("paymentUrl") or ""),
                self.settings.cabinet_payment_redirect_hosts,
            )
            transaction_id = str(transaction.get("transactionId") or transaction.get("id") or "")
            merchant_id = str(transaction.get("merchantId") or "")
            if (
                not transaction_id
                or len(transaction_id) > 256
                or str(transaction.get("status") or "").upper() != "PENDING"
                or not hmac.compare_digest(merchant_id, self.settings.platega_merchant_id)
            ):
                raise CabinetServiceError(502, "invalid_payment_response")
        except CabinetServiceError:
            self.order_store.mark_payment_canceled(order.id, "payment_creation_failed")
            raise
        self.order_store.set_provider_payment(
            order_id=order.id,
            transaction_id=transaction_id,
            payment_url=payment_url,
            provider_amount_rub=request_amount,
        )
        saved = self.order_store.get(order.id)
        if saved is None:
            raise CabinetServiceError(500, "order_not_saved")
        return {"ok": True, "order": self.order_public(saved)}

    async def revoke_device(
        self,
        telegram_id: int,
        *,
        user_uuid: str,
        device_id: str,
    ) -> dict[str, object]:
        if not device_id or len(device_id) > 200:
            raise CabinetServiceError(400, "invalid_device")
        user = await self.owned_user(telegram_id, user_uuid)
        public_user_uuid = _public_user_uuid(user)
        if public_user_uuid is None:
            raise CabinetServiceError(404, "subscription_not_found")
        access = user.get("_wdtt_access")
        try:
            if isinstance(access, dict):
                await wdtt.delete_remote_device(self.settings, access, device_id)
            else:
                target_reference = str(user.get("uuid") or "")
                target_device_id = device_id
                record = user.get("_multi_subscription")
                if isinstance(record, dict):
                    component, separator, raw_device_id = device_id.partition(":")
                    if not separator or component not in {"regular", "mobile"} or not raw_device_id:
                        raise CabinetServiceError(400, "invalid_device")
                    target_reference = str(
                        record.get("primary_user_uuid" if component == "regular" else "mobile_user_uuid") or ""
                    )
                    target_device_id = raw_device_id
                devices = await self.remnawave.get_user_devices(target_reference)
                if not any(
                    hmac.compare_digest(str(item.get("hwid") or ""), target_device_id)
                    for item in devices
                ):
                    raise CabinetServiceError(404, "device_not_found")
                await self.remnawave.delete_user_device(target_reference, target_device_id)
        except (RemnawaveApiError, RuntimeError) as exc:
            raise CabinetServiceError(502, "vpn_service_unavailable", retryable=True) from exc
        return {
            "ok": True,
            "subscriptionUuid": public_user_uuid,
            "deviceId": device_id,
        }

    async def rotate_key(self, telegram_id: int, *, user_uuid: str) -> dict[str, object]:
        user = await self.owned_user(telegram_id, user_uuid)
        public_user_uuid = _public_user_uuid(user)
        if public_user_uuid is None:
            raise CabinetServiceError(404, "subscription_not_found")
        if isinstance(user.get("_wdtt_access"), dict):
            raise CabinetServiceError(422, "key_rotation_unavailable")
        try:
            record = user.get("_multi_subscription")
            if isinstance(record, dict):
                primary_reference = str(record.get("primary_user_uuid") or "")
                mobile_reference = str(record.get("mobile_user_uuid") or "")
                primary = await self.remnawave.revoke_subscription(primary_reference)
                mobile = await self.remnawave.revoke_subscription(mobile_reference)
                rotated = self.order_store.rotate_multi_subscription_token(primary_reference)
                updated = decorate_user(self.settings, rotated, primary or user, mobile) if rotated is not None else None
            else:
                updated = await self.remnawave.revoke_subscription(str(user.get("uuid") or ""))
        except RemnawaveApiError as exc:
            raise CabinetServiceError(502, "vpn_service_unavailable", retryable=True) from exc
        if not isinstance(updated, dict):
            raise CabinetServiceError(502, "vpn_service_unavailable", retryable=True)
        return {
            "ok": True,
            "subscriptionUuid": public_user_uuid,
            "subscriptionUrl": _subscription_url(updated.get("subscriptionUrl")),
        }

    async def set_shield(
        self,
        telegram_id: int,
        *,
        user_uuid: str,
        enabled: bool,
    ) -> dict[str, object]:
        user = await self.owned_user(telegram_id, user_uuid)
        public_user_uuid = _public_user_uuid(user)
        remnawave_user_id = _as_int(user.get("id"))
        if (
            public_user_uuid is None
            or isinstance(user.get("_wdtt_access"), dict)
            or remnawave_user_id <= 0
        ):
            raise CabinetServiceError(422, "shield_not_supported")
        self.order_store.set_shield_enabled(
            remnawave_user_id=remnawave_user_id,
            telegram_id=telegram_id,
            user_uuid=str(user.get("uuid") or ""),
            enabled=enabled,
        )
        return {
            "ok": True,
            "subscriptionUuid": public_user_uuid,
            "shieldEnabled": enabled,
        }

    async def free_proxy(self, telegram_id: int) -> dict[str, object]:
        if self.order_store.is_cabinet_account_actor(telegram_id):
            raise CabinetServiceError(422, "proxy_not_available")
        proxy = self.order_store.get_free_mtproto_proxy(telegram_id)
        if proxy is None:
            try:
                provisioned = await mtproto.get_or_create_free_proxy(
                    self.settings,
                    telegram_id=telegram_id,
                )
            except mtproto.MtprotoProvisionerError as exc:
                raise CabinetServiceError(502, "proxy_service_unavailable", retryable=True) from exc
            canonical_proxy_url(str(provisioned["proxy_link"]))
            proxy = self.order_store.upsert_free_mtproto_proxy(
                telegram_id=telegram_id,
                mtproxy_label=str(provisioned["mtproxy_label"]),
                proxy_link=str(provisioned["proxy_link"]),
                rate_limit_mbps=_as_int(provisioned["rate_limit_mbps"], 15),
                device_limit=_as_int(provisioned["device_limit"], 1),
                max_tcp_connections=_as_int(provisioned["max_tcp_connections"], 5),
                max_unique_ips=_as_int(provisioned["max_unique_ips"], 1),
            )
        return {
            "ok": True,
            "proxy": {
                "label": _display_text(
                    proxy.get("mtproxy_label"),
                    max_length=160,
                    fallback="Levik VPN Proxy",
                ),
                "url": canonical_proxy_url(str(proxy.get("proxy_link") or "")),
                "rateLimitMbps": _bounded_int(
                    proxy.get("rate_limit_mbps"),
                    minimum=1,
                    maximum=100_000,
                    default=15,
                ),
                "deviceLimit": _bounded_int(
                    proxy.get("device_limit"),
                    minimum=1,
                    maximum=100,
                    default=1,
                ),
            },
        }
