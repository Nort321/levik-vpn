from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import re
import secrets
import sqlite3
from collections.abc import Awaitable, Callable
from datetime import datetime, timedelta, timezone
from uuid import UUID

from aiohttp import web

from app.cabinet_auth import (
    CabinetAuthError,
    CabinetRequestContext,
    authenticate_cabinet_request,
    opaque_token_hash,
)
from app.cabinet_service import (
    TELEGRAM_BOT_USERNAME_RE,
    CabinetService,
    CabinetServiceError,
    cabinet_user,
    cabinet_user_key,
)
from app.config import decode_cabinet_secret
from app.orders import (
    CabinetAccountConflict,
    CabinetAccountGrantUnavailable,
    CabinetAccountMergeRequired,
    CabinetGrantCollision,
    CabinetIdempotencyConflict,
    OrderStore,
)


BASE_PATH = "/levik-vpn-bot/internal/cabinet/v1"
USER_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
CABINET_USER_KEY_RE = re.compile(r"^usr_[A-Za-z0-9_-]{24}$")
FORBIDDEN_IDENTITY_FIELDS = frozenset(
    {"telegramid", "userid", "actortelegramid"}
)
logger = logging.getLogger(__name__)


def _response(payload: dict[str, object], *, status: int = 200) -> web.Response:
    return web.json_response(
        payload,
        status=status,
        headers={
            "Cache-Control": "private, no-store",
            "Pragma": "no-cache",
            "Referrer-Policy": "no-referrer",
            "X-Content-Type-Options": "nosniff",
        },
    )


def _error(
    status: int,
    code: str,
    *,
    retryable: bool = False,
) -> web.Response:
    messages = {
        "authorization_pending": "Waiting for confirmation in Telegram.",
        "access_denied": "Authorization was declined.",
        "expired_token": "Authorization request has expired.",
        "subscription_not_found": "Subscription was not found.",
        "shield_not_supported": "Shield is unavailable for this subscription.",
        "order_not_found": "Order was not found.",
        "device_not_found": "Device was not found.",
        "vpn_service_unavailable": "VPN service is temporarily unavailable.",
        "payment_provider_unavailable": "Payment service is temporarily unavailable.",
        "proxy_service_unavailable": "Proxy service is temporarily unavailable.",
        "trial_already_used": "Trial access has already been used.",
        "trial_in_progress": "Trial access is already being created.",
        "trial_not_eligible": "Trial access is unavailable for this account.",
        "trial_provisioning_failed": "Trial access could not be created.",
        "replay_detected": "Request has already been processed.",
        "idempotency_conflict": "Idempotency key was reused for another request.",
        "account_identity_conflict": "Account identity could not be linked.",
        "account_merge_requires_support": "Both identities contain VPN state and require support review.",
        "idempotency_expired": "The original authorization grant is no longer available.",
    }
    return _response(
        {
            "ok": False,
            "error": {
                "code": code,
                "message": messages.get(code, "Request could not be completed."),
                "retryable": retryable,
            },
        },
        status=status,
    )


def _service(request: web.Request) -> CabinetService:
    return CabinetService(
        settings=request.app["settings"],
        remnawave=request.app["remnawave"],
        order_store=request.app["order_store"],
    )


def _reject_identity_fields(context: CabinetRequestContext) -> None:
    pending: list[object] = [context.payload]
    while pending:
        value = pending.pop()
        if isinstance(value, dict):
            for key, child in value.items():
                normalized = "".join(character for character in key.lower() if character.isalnum())
                if normalized in FORBIDDEN_IDENTITY_FIELDS:
                    raise CabinetAuthError(400, "identity_field_forbidden")
                pending.append(child)
        elif isinstance(value, list):
            pending.extend(value)


def _require_fields(
    context: CabinetRequestContext,
    *,
    required: frozenset[str] = frozenset(),
    optional: frozenset[str] = frozenset(),
) -> None:
    present = frozenset(context.payload)
    if not required.issubset(present) or not present.issubset(required | optional):
        raise CabinetAuthError(400, "invalid_request")


def _string_field(
    context: CabinetRequestContext,
    key: str,
    *,
    max_length: int,
) -> str:
    value = context.payload.get(key)
    if not isinstance(value, str) or not value or len(value) > max_length:
        raise CabinetAuthError(400, "invalid_request")
    return value


async def _context(
    request: web.Request,
    *,
    require_grant: bool,
    require_idempotency: bool = False,
) -> CabinetRequestContext:
    if request.content_type != "application/json":
        raise CabinetAuthError(415, "json_content_type_required")
    context = await authenticate_cabinet_request(
        request,
        require_grant=require_grant,
        require_idempotency=require_idempotency,
    )
    if require_grant:
        _reject_identity_fields(context)
    return context


async def _run(
    request: web.Request,
    operation: Callable[
        [CabinetRequestContext, CabinetService],
        Awaitable[dict[str, object]] | dict[str, object],
    ],
    *,
    require_grant: bool = True,
    require_idempotency: bool = False,
    automatic_idempotency: bool = True,
) -> web.Response:
    manage_idempotency = require_idempotency and automatic_idempotency
    try:
        context = await _context(
            request,
            require_grant=require_grant,
            require_idempotency=require_idempotency,
        )
        service = _service(request)
        idempotency_claimed = False
        if manage_idempotency:
            if context.telegram_id is None:
                raise CabinetAuthError(401, "grant_required")
            store: OrderStore = request.app["order_store"]
            try:
                previous, fresh = store.claim_cabinet_idempotency(
                    idempotency_key=context.idempotency_key,
                    operation=request.path,
                    telegram_id=context.telegram_id,
                    request_hash=context.body_hash,
                )
            except CabinetIdempotencyConflict as exc:
                raise CabinetAuthError(409, "idempotency_conflict") from exc
            if not fresh:
                if str(previous.get("status") or "") == "completed":
                    try:
                        cached = json.loads(str(previous.get("response_json") or ""))
                    except json.JSONDecodeError as exc:
                        raise CabinetAuthError(500, "invalid_idempotency_record") from exc
                    if isinstance(cached, dict):
                        return _response(cached)
                if str(previous.get("status") or "") == "processing":
                    return _error(409, "request_in_progress", retryable=True)
                return _error(409, "previous_attempt_failed", retryable=True)
            idempotency_claimed = True

        result = operation(context, service)
        payload = await result if hasattr(result, "__await__") else result
        if manage_idempotency and idempotency_claimed:
            request.app["order_store"].complete_cabinet_idempotency(
                idempotency_key=context.idempotency_key,
                response=payload,
                order_id=(
                    int(payload["order"]["id"])
                    if isinstance(payload.get("order"), dict)
                    and int(payload["order"].get("id") or 0) > 0
                    else None
                ),
            )
        return _response(payload)
    except CabinetAuthError as exc:
        return _error(exc.status, exc.code)
    except CabinetServiceError as exc:
        if manage_idempotency:
            idempotency_key = request.headers.get("Idempotency-Key", "")
            if idempotency_key:
                request.app["order_store"].fail_cabinet_idempotency(
                    idempotency_key=idempotency_key,
                    error_code=exc.code,
                )
        return _error(exc.status, exc.code, retryable=exc.retryable)
    except Exception:
        if manage_idempotency:
            idempotency_key = request.headers.get("Idempotency-Key", "")
            if idempotency_key:
                request.app["order_store"].fail_cabinet_idempotency(
                    idempotency_key=idempotency_key,
                    error_code="internal_error",
                )
        logger.exception("cabinet request failed path=%s", request.path)
        return _error(500, "internal_error", retryable=True)


def _bot_username(request: web.Request) -> str:
    settings = request.app["settings"]
    referrals = (
        settings.data.get("referrals")
        if isinstance(settings.data.get("referrals"), dict)
        else {}
    )
    username = str(referrals.get("bot_username") or "levikvpnbot").strip().lstrip("@")
    return username if TELEGRAM_BOT_USERNAME_RE.fullmatch(username) else "levikvpnbot"


def _new_user_code() -> str:
    return "".join(secrets.choice(USER_CODE_ALPHABET) for _ in range(8))


def _canonical_account_id(context: CabinetRequestContext) -> str:
    value = context.payload.get("accountId")
    if not isinstance(value, str):
        raise CabinetAuthError(400, "invalid_request")
    try:
        parsed = UUID(value)
    except ValueError as exc:
        raise CabinetAuthError(400, "invalid_request") from exc
    if parsed.version != 4 or str(parsed) != value:
        raise CabinetAuthError(400, "invalid_request")
    return value


def _account_grant_token(subject_secret: str, seed: str) -> str:
    digest = hmac.new(
        decode_cabinet_secret(subject_secret),
        b"levik-cabinet-account-grant-v1\x00" + seed.encode("ascii"),
        hashlib.sha256,
    ).digest()
    return base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


def _resolve_cabinet_user_key(
    request: web.Request,
    user_key: str,
) -> int | None:
    settings = request.app["settings"]
    store: OrderStore = request.app["order_store"]
    stored_actor = store.resolve_cabinet_actor_user_key(user_key=user_key)
    if stored_actor is not None:
        return stored_actor
    for actor_id in sorted(store.known_telegram_ids()):
        candidate = cabinet_user_key(settings, actor_id)
        if hmac.compare_digest(candidate, user_key):
            store.remember_cabinet_telegram_actor(
                actor_id=actor_id,
                user_key=user_key,
            )
            return actor_id
    return None


async def handle_device_create(request: web.Request) -> web.Response:
    async def create(
        context: CabinetRequestContext,
        __: CabinetService,
    ) -> dict[str, object]:
        _require_fields(context)
        settings = request.app["settings"]
        store: OrderStore = request.app["order_store"]
        expires_at = datetime.now(timezone.utc) + timedelta(
            seconds=settings.cabinet_device_code_ttl_seconds
        )
        for _attempt in range(8):
            challenge_id = secrets.token_hex(16)
            device_code = secrets.token_urlsafe(32)
            verification_token = secrets.token_urlsafe(24)
            user_code = _new_user_code()
            try:
                store.create_cabinet_device_challenge(
                    challenge_id=challenge_id,
                    device_code_hash=opaque_token_hash(device_code),
                    verification_token_hash=opaque_token_hash(verification_token),
                    user_code=user_code,
                    expires_at=expires_at.isoformat(),
                )
                break
            except sqlite3.IntegrityError:
                continue
        else:
            raise CabinetServiceError(503, "authorization_service_unavailable", retryable=True)

        verification_uri = f"https://t.me/{_bot_username(request)}"
        return {
            "ok": True,
            "deviceCode": device_code,
            "userCode": user_code,
            "verificationUri": verification_uri,
            "verificationUriComplete": f"{verification_uri}?start=web_{verification_token}",
            "expiresIn": settings.cabinet_device_code_ttl_seconds,
            "interval": 2,
        }

    return await _run(request, create, require_grant=False)


async def handle_device_status(request: web.Request) -> web.Response:
    async def status(
        context: CabinetRequestContext,
        _: CabinetService,
    ) -> dict[str, object]:
        _require_fields(context, required=frozenset({"deviceCode"}))
        device_code = _string_field(context, "deviceCode", max_length=128)
        if len(device_code) < 32 or not all(
            character.isalnum() or character in "_-"
            for character in device_code
        ):
            raise CabinetAuthError(400, "invalid_device_code")
        settings = request.app["settings"]
        store: OrderStore = request.app["order_store"]
        grant = secrets.token_urlsafe(32)
        grant_expires_at = datetime.now(timezone.utc) + timedelta(
            seconds=settings.cabinet_grant_ttl_seconds
        )
        challenge_status, telegram_id = store.exchange_cabinet_device_challenge(
            device_code_hash=opaque_token_hash(device_code),
            grant_hash=opaque_token_hash(grant),
            grant_expires_at=grant_expires_at.isoformat(),
        )
        if challenge_status in {"pending", "awaiting_confirmation"}:
            return {"ok": True, "status": "authorization_pending", "interval": 2}
        if challenge_status == "denied":
            raise CabinetAuthError(403, "access_denied")
        if challenge_status != "authorized" or telegram_id is None:
            raise CabinetAuthError(410, "expired_token")
        telegram_id = request.app["order_store"].canonical_cabinet_actor(
            telegram_id
        )
        return {
            "ok": True,
            "status": "authorized",
            "grant": grant,
            "grantExpiresIn": settings.cabinet_grant_ttl_seconds,
            "user": cabinet_user(settings, store, telegram_id),
        }

    return await _run(request, status, require_grant=False)


async def handle_account_grant(request: web.Request) -> web.Response:
    async def issue(
        context: CabinetRequestContext,
        service: CabinetService,
    ) -> dict[str, object]:
        _require_fields(
            context,
            required=frozenset({"accountId", "legacyUserKey"}),
        )
        account_id = _canonical_account_id(context)
        legacy_value = context.payload.get("legacyUserKey")
        if legacy_value is not None and (
            not isinstance(legacy_value, str)
            or CABINET_USER_KEY_RE.fullmatch(legacy_value) is None
        ):
            raise CabinetAuthError(400, "invalid_request")
        legacy_user_key = legacy_value if isinstance(legacy_value, str) else None
        legacy_actor_id = (
            _resolve_cabinet_user_key(request, legacy_user_key)
            if legacy_user_key is not None
            else None
        )
        if legacy_user_key is not None and (
            legacy_actor_id is None or legacy_actor_id <= 0
        ):
            raise CabinetAuthError(409, "account_identity_conflict")

        principal_has_external_state = False
        legacy_has_external_state = False
        binding = request.app["order_store"].get_cabinet_account_binding(account_id)
        if (
            binding is not None
            and str(binding.get("actor_kind") or "") == "account"
            and legacy_actor_id is not None
            and binding.get("legacy_actor_id") is None
        ):
            principal_actor_id = int(binding.get("actor_id") or 0)
            try:
                principal_has_external_state = bool(
                    await service.load_users(principal_actor_id)
                )
                legacy_has_external_state = bool(
                    await service.load_users(legacy_actor_id)
                )
            except CabinetServiceError as exc:
                raise CabinetServiceError(
                    503,
                    "account_merge_state_unavailable",
                    retryable=True,
                ) from exc

        settings = request.app["settings"]
        store: OrderStore = request.app["order_store"]
        expires_at = datetime.now(timezone.utc) + timedelta(
            seconds=settings.cabinet_grant_ttl_seconds
        )
        for _attempt in range(8):
            grant_seed = secrets.token_urlsafe(32)
            grant = _account_grant_token(
                settings.cabinet_subject_secret,
                grant_seed,
            )
            try:
                claim = store.claim_cabinet_account_grant(
                    account_id=account_id,
                    legacy_user_key=legacy_user_key,
                    legacy_actor_id=legacy_actor_id,
                    idempotency_key=context.idempotency_key,
                    request_hash=context.body_hash,
                    grant_seed=grant_seed,
                    grant_hash=opaque_token_hash(grant),
                    grant_expires_at=expires_at.isoformat(),
                    grant_expires_in=settings.cabinet_grant_ttl_seconds,
                    user_key_factory=lambda actor_id: cabinet_user_key(
                        settings,
                        actor_id,
                    ),
                    principal_has_external_state=principal_has_external_state,
                    legacy_has_external_state=legacy_has_external_state,
                )
            except CabinetGrantCollision:
                continue
            except CabinetAccountConflict as exc:
                raise CabinetAuthError(409, "account_identity_conflict") from exc
            except CabinetAccountMergeRequired as exc:
                raise CabinetAuthError(
                    409,
                    "account_merge_requires_support",
                ) from exc
            except CabinetAccountGrantUnavailable as exc:
                raise CabinetAuthError(409, "idempotency_expired") from exc

            grant = _account_grant_token(
                settings.cabinet_subject_secret,
                claim.grant_seed,
            )
            if not hmac.compare_digest(opaque_token_hash(grant), claim.grant_hash):
                raise CabinetAuthError(500, "invalid_account_grant_record")
            user = cabinet_user(settings, store, claim.actor_id)
            if not hmac.compare_digest(user["userKey"], claim.user_key):
                raise CabinetAuthError(500, "invalid_account_principal")
            return {
                "ok": True,
                "grant": grant,
                "grantExpiresIn": claim.grant_expires_in,
                "user": user,
            }
        raise CabinetServiceError(
            503,
            "authorization_service_unavailable",
            retryable=True,
        )

    return await _run(
        request,
        issue,
        require_grant=False,
        require_idempotency=True,
        automatic_idempotency=False,
    )


async def handle_grant_revoke(request: web.Request) -> web.Response:
    async def revoke(context: CabinetRequestContext, _: CabinetService) -> dict[str, object]:
        _require_fields(context)
        request.app["order_store"].revoke_cabinet_grant(grant_hash=context.grant_hash)
        return {"ok": True}

    return await _run(request, revoke, require_idempotency=True)


async def handle_catalog(request: web.Request) -> web.Response:
    async def get_catalog(context: CabinetRequestContext, service: CabinetService) -> dict[str, object]:
        _require_fields(context)
        return service.catalog(int(context.telegram_id or 0))

    return await _run(request, get_catalog)


async def handle_snapshot(request: web.Request) -> web.Response:
    async def get_snapshot(context: CabinetRequestContext, service: CabinetService) -> dict[str, object]:
        _require_fields(context)
        return await service.snapshot(int(context.telegram_id or 0))

    return await _run(request, get_snapshot)


async def handle_order_create(request: web.Request) -> web.Response:
    async def create_order(context: CabinetRequestContext, service: CabinetService) -> dict[str, object]:
        return await service.create_order(int(context.telegram_id or 0), context.payload)

    return await _run(request, create_order, require_idempotency=True)


async def handle_order_status(request: web.Request) -> web.Response:
    async def get_status(context: CabinetRequestContext, service: CabinetService) -> dict[str, object]:
        _require_fields(context, required=frozenset({"orderId"}))
        order_id = context.payload.get("orderId")
        if isinstance(order_id, bool) or not isinstance(order_id, int) or order_id <= 0:
            raise CabinetAuthError(400, "invalid_order")
        return service.order_status(int(context.telegram_id or 0), order_id)

    return await _run(request, get_status)


async def handle_device_revoke(request: web.Request) -> web.Response:
    async def revoke_device(context: CabinetRequestContext, service: CabinetService) -> dict[str, object]:
        _require_fields(
            context,
            required=frozenset({"subscriptionUuid", "deviceId"}),
        )
        return await service.revoke_device(
            int(context.telegram_id or 0),
            user_uuid=_string_field(context, "subscriptionUuid", max_length=128),
            device_id=_string_field(context, "deviceId", max_length=200),
        )

    return await _run(request, revoke_device, require_idempotency=True)


async def handle_rotate_key(request: web.Request) -> web.Response:
    async def rotate(context: CabinetRequestContext, service: CabinetService) -> dict[str, object]:
        _require_fields(context, required=frozenset({"subscriptionUuid"}))
        return await service.rotate_key(
            int(context.telegram_id or 0),
            user_uuid=_string_field(context, "subscriptionUuid", max_length=128),
        )

    return await _run(request, rotate, require_idempotency=True)


async def handle_set_shield(request: web.Request) -> web.Response:
    async def set_shield(context: CabinetRequestContext, service: CabinetService) -> dict[str, object]:
        _require_fields(
            context,
            required=frozenset({"subscriptionUuid", "enabled"}),
        )
        enabled = context.payload.get("enabled")
        if not isinstance(enabled, bool):
            raise CabinetAuthError(400, "invalid_request")
        return await service.set_shield(
            int(context.telegram_id or 0),
            user_uuid=_string_field(context, "subscriptionUuid", max_length=128),
            enabled=enabled,
        )

    return await _run(request, set_shield, require_idempotency=True)


async def handle_free_proxy(request: web.Request) -> web.Response:
    async def provision(context: CabinetRequestContext, service: CabinetService) -> dict[str, object]:
        _require_fields(context)
        return await service.free_proxy(int(context.telegram_id or 0))

    return await _run(request, provision, require_idempotency=True)


async def handle_trial_activate(request: web.Request) -> web.Response:
    async def activate(context: CabinetRequestContext, service: CabinetService) -> dict[str, object]:
        _require_fields(context)
        return await service.activate_trial(int(context.telegram_id or 0))

    return await _run(request, activate, require_idempotency=True)


def register_cabinet_routes(app: web.Application) -> None:
    app.router.add_post(f"{BASE_PATH}/auth/device/create", handle_device_create)
    app.router.add_post(f"{BASE_PATH}/auth/device/status", handle_device_status)
    app.router.add_post(f"{BASE_PATH}/auth/account/grant", handle_account_grant)
    app.router.add_post(f"{BASE_PATH}/auth/grant/revoke", handle_grant_revoke)
    app.router.add_post(f"{BASE_PATH}/catalog", handle_catalog)
    app.router.add_post(f"{BASE_PATH}/account/snapshot", handle_snapshot)
    app.router.add_post(f"{BASE_PATH}/orders/create", handle_order_create)
    app.router.add_post(f"{BASE_PATH}/orders/status", handle_order_status)
    app.router.add_post(f"{BASE_PATH}/devices/revoke", handle_device_revoke)
    app.router.add_post(f"{BASE_PATH}/subscriptions/rotate-key", handle_rotate_key)
    app.router.add_post(f"{BASE_PATH}/subscriptions/shield", handle_set_shield)
    app.router.add_post(f"{BASE_PATH}/free-proxy", handle_free_proxy)
    app.router.add_post(f"{BASE_PATH}/trial/activate", handle_trial_activate)
