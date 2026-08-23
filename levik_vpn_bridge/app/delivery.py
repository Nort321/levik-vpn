from __future__ import annotations

import calendar
import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

from app import wdtt
from app.config import Settings
from app.formatters import (
    GB,
    MOBILE_PLAN,
    esc,
    format_date,
    key_text,
    mobile_traffic_amount_bytes,
    mobile_traffic_success_text,
    multi_slot_success_text,
    parse_dt,
    plan_name,
    slot_amount,
    slot_success_text,
    user_title,
)
from app.orders import OrderStore
from app.remnawave import RemnawaveClient
from app.tariffs import subscription_tariff_id_for_user
from app.multi_subscription import MULTI_TARIFF_ID, decorate_user


PERMANENT_ADDON_EXPIRES_AT = "9999-12-31T23:59:59+00:00"


def referral_reward_multiplier(months: int) -> tuple[int, int]:
    if months >= 6:
        return 2, 1
    if months >= 3:
        return 3, 2
    return 1, 1


def scaled_referral_reward(base_amount: int, months: int) -> int:
    numerator, denominator = referral_reward_multiplier(months)
    return max(0, base_amount) * numerator // denominator


@dataclass(frozen=True)
class DeliveryResult:
    success: bool
    user_text: str | None
    user_uuid: str | None = None
    subscription_url: str | None = None
    offer_happ_routing: bool = False
    referral_telegram_id: int | None = None
    referral_text: str | None = None


def payment_payload(order_id: int, telegram_id: int) -> str:
    return f"pay:{order_id}:{telegram_id}"


def parse_payment_payload(payload: str) -> tuple[int, int] | None:
    parts = payload.split(":")
    if len(parts) != 3 or parts[0] != "pay":
        return None
    try:
        return int(parts[1]), int(parts[2])
    except ValueError:
        return None


async def deliver_paid_order(
    *,
    telegram_id: int,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    order: dict[str, object],
) -> DeliveryResult:
    order_id = int(order.get("id") or 0)
    if str(order.get("status")) == "delivered":
        return DeliveryResult(success=True, user_text=None)
    if not order_store.claim_delivery(order_id):
        return DeliveryResult(
            success=True,
            user_text="Оплата уже обрабатывается. Если доступ не появится, напишите в поддержку.",
        )

    try:
        kind = str(order.get("kind") or "")
        if kind == "slot":
            text = await _deliver_slot_payment(telegram_id, settings, remnawave, order_store, order)
            return DeliveryResult(success=True, user_text=text)
        if kind == "traffic":
            text = await _deliver_traffic_payment(telegram_id, settings, remnawave, order_store, order)
            return DeliveryResult(success=True, user_text=text)

        if kind in {"access_purchase", "access_renewal", "admin_grant"}:
            text, user_uuid, subscription_url, referral_telegram_id, referral_text = await _deliver_access_payment(
                telegram_id,
                settings,
                remnawave,
                order_store,
                order,
            )
            return DeliveryResult(
                success=True,
                user_text=text,
                user_uuid=user_uuid,
                subscription_url=subscription_url,
                offer_happ_routing=kind in {"access_purchase", "admin_grant"},
                referral_telegram_id=referral_telegram_id,
                referral_text=referral_text,
            )

        raise RuntimeError("unsupported payment kind")
    except Exception as exc:
        order_store.mark_delivery_failed(order_id, exc.__class__.__name__)
        return DeliveryResult(
            success=False,
            user_text="Оплата прошла, но доступ не удалось выдать автоматически. Напишите в поддержку, оплата сохранена.",
        )


async def _deliver_slot_payment(
    telegram_id: int,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    order: dict[str, object],
) -> str:
    order_id = int(order.get("id") or 0)
    user_uuid = str(order.get("target_user_uuid") or "")
    wdtt_access = order_store.get_wdtt_access_by_user_uuid(user_uuid) if user_uuid else None
    user = await remnawave.get_user_by_uuid(user_uuid) if _is_remnawave_uuid(user_uuid) else None
    if wdtt_access is not None:
        if int(wdtt_access.get("telegram_id") or 0) != telegram_id:
            raise RuntimeError("WDTT slot target user mismatch")
        if user is None or int(user.get("telegramId") or 0) != telegram_id:
            migration = order_store.get_wdtt_remnawave_migration(user_uuid)
            if migration is None:
                raise RuntimeError("WDTT slot target has no Remnawave migration")
            user_uuid = str(migration.get("remnawave_user_uuid") or "")
            user = await remnawave.get_user_by_uuid(user_uuid) if user_uuid else None

    if user is None or int(user.get("telegramId") or 0) != telegram_id:
        raise RuntimeError("slot target user not found")
    multi = order_store.get_multi_subscription_by_user_uuid(user_uuid)
    if multi is not None:
        return await _deliver_multi_slot_payment(
            telegram_id=telegram_id,
            settings=settings,
            remnawave=remnawave,
            order_store=order_store,
            order=order,
            record=multi,
        )
    effect = order_store.get_delivery_effect(order_id)
    if effect is None:
        old_limit = _device_limit(user)
        if old_limit <= 0:
            raise RuntimeError("slot target user has unlimited device limit")
        slots_delta = max(1, int(order.get("slots_delta") or slot_amount(settings.data)))
        traffic_delta = max(0, _order_int(order, "traffic_delta_bytes", 0))
        is_mobile = plan_name(user) == MOBILE_PLAN
        if not is_mobile:
            traffic_delta = 0
        old_traffic = _traffic_limit_bytes(user)
        if traffic_delta > 0 and old_traffic <= 0:
            raise RuntimeError("slot target user has unlimited traffic")
        expires_at = _addon_expires_at(user) if is_mobile else None
        if is_mobile and expires_at is None:
            raise RuntimeError("mobile slot target has no finite paid period")
        effect = order_store.prepare_delivery_effect(
            order_id=order_id,
            telegram_id=telegram_id,
            kind="slot",
            effect={
                "version": 1,
                "operation": "slot",
                "user_uuid": user_uuid,
                "base_device_limit": old_limit,
                "target_device_limit": old_limit + slots_delta,
                "base_traffic_limit_bytes": old_traffic,
                "target_traffic_limit_bytes": old_traffic + traffic_delta,
                "slots_delta": slots_delta,
                "traffic_delta_bytes": traffic_delta,
                "addon_expires_at": _utc_iso(expires_at) if expires_at is not None else None,
            },
        )
    _validate_delivery_effect(effect, operation="slot", user_uuid=user_uuid)
    old_limit = _effect_int(effect, "base_device_limit", minimum=1)
    new_limit = _effect_int(effect, "target_device_limit", minimum=old_limit)
    old_traffic = _effect_int(effect, "base_traffic_limit_bytes", minimum=0)
    new_traffic = _effect_int(effect, "target_traffic_limit_bytes", minimum=old_traffic)
    slots_delta = _effect_int(effect, "slots_delta", minimum=1)
    traffic_delta = _effect_int(effect, "traffic_delta_bytes", minimum=0)
    addon_expires_at = effect.get("addon_expires_at")
    if addon_expires_at is not None and not isinstance(addon_expires_at, str):
        raise RuntimeError("stored slot delivery effect is invalid")
    order_store.create_subscription_addon(
        telegram_id=telegram_id,
        user_uuid=user_uuid,
        order_id=order_id,
        kind="slot",
        slots_delta=slots_delta,
        slots_persistent=True,
        traffic_delta_bytes=traffic_delta,
        expires_at=addon_expires_at or PERMANENT_ADDON_EXPIRES_AT,
    )
    current_limit = _device_limit(user)
    current_traffic = _traffic_limit_bytes(user)
    body: dict[str, Any] = {"uuid": user_uuid}
    if 0 < current_limit < new_limit:
        body["hwidDeviceLimit"] = new_limit
    if traffic_delta > 0 and 0 < current_traffic < new_traffic:
        body["trafficLimitBytes"] = new_traffic
    updated = user
    if len(body) > 1:
        updated = _extract_user(await remnawave.update_user(body)) or user
    order_store.mark_delivered(order_id)
    return slot_success_text(
        settings.data,
        updated,
        old_limit,
        max(current_limit, new_limit),
        old_traffic_bytes=old_traffic if traffic_delta > 0 else None,
        new_traffic_bytes=max(current_traffic, new_traffic) if traffic_delta > 0 else None,
        expires_at=addon_expires_at,
    )


async def _deliver_traffic_payment(
    telegram_id: int,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    order: dict[str, object],
) -> str:
    order_id = int(order.get("id") or 0)
    user_uuid = str(order.get("target_user_uuid") or "")
    multi = order_store.get_multi_subscription_by_user_uuid(user_uuid)
    target_reference = str(multi.get("mobile_user_uuid") or "") if multi is not None else user_uuid
    user = await remnawave.get_user_by_uuid(target_reference) if _is_remnawave_uuid(target_reference) else None
    if user is None or int(user.get("telegramId") or 0) != telegram_id:
        raise RuntimeError("traffic target user not found")
    if plan_name(user) != MOBILE_PLAN:
        raise RuntimeError("traffic target is not mobile")
    old_traffic = _traffic_limit_bytes(user)
    effect = order_store.get_delivery_effect(order_id)
    if effect is None:
        if old_traffic <= 0:
            raise RuntimeError("traffic target has unlimited traffic")
        traffic_delta = max(1, _order_int(order, "traffic_delta_bytes", mobile_traffic_amount_bytes(settings.data)))
        expires_at = _addon_expires_at(user)
        if expires_at is None:
            raise RuntimeError("mobile traffic target has no finite paid period")
        effect = order_store.prepare_delivery_effect(
            order_id=order_id,
            telegram_id=telegram_id,
            kind="traffic",
            effect={
                "version": 1,
                "operation": "traffic",
                "user_uuid": user_uuid,
                "target_user_uuid": target_reference,
                "base_traffic_limit_bytes": old_traffic,
                "target_traffic_limit_bytes": old_traffic + traffic_delta,
                "traffic_delta_bytes": traffic_delta,
                "addon_expires_at": _utc_iso(expires_at),
            },
        )
    _validate_delivery_effect(effect, operation="traffic", user_uuid=user_uuid)
    old_traffic = _effect_int(effect, "base_traffic_limit_bytes", minimum=1)
    new_traffic = _effect_int(effect, "target_traffic_limit_bytes", minimum=old_traffic)
    traffic_delta = _effect_int(effect, "traffic_delta_bytes", minimum=1)
    addon_expires_at = effect.get("addon_expires_at")
    if not isinstance(addon_expires_at, str):
        raise TypeError("stored traffic delivery effect is invalid")
    order_store.create_subscription_addon(
        telegram_id=telegram_id,
        user_uuid=user_uuid,
        order_id=order_id,
        kind="traffic",
        slots_delta=0,
        slots_persistent=False,
        traffic_delta_bytes=traffic_delta,
        expires_at=addon_expires_at,
    )
    current_traffic = _traffic_limit_bytes(user)
    updated = user
    if 0 < current_traffic < new_traffic:
        updated = _extract_user(
            await remnawave.update_user(
                {
                    "uuid": target_reference,
                    "trafficLimitBytes": new_traffic,
                }
            )
        ) or user
    order_store.mark_delivered(order_id)
    if multi is not None:
        primary = await remnawave.get_user_by_uuid(str(multi.get("primary_user_uuid") or ""))
        if primary is not None:
            updated = decorate_user(settings, multi, primary, updated)
    return mobile_traffic_success_text(updated, old_traffic, max(current_traffic, new_traffic))


async def _deliver_multi_slot_payment(
    *,
    telegram_id: int,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    order: dict[str, object],
    record: dict[str, object],
) -> str:
    order_id = int(order.get("id") or 0)
    primary_reference = str(record.get("primary_user_uuid") or "")
    mobile_reference = str(record.get("mobile_user_uuid") or "")
    primary = await remnawave.get_user_by_uuid(primary_reference)
    mobile = await remnawave.get_user_by_uuid(mobile_reference)
    if (
        primary is None
        or mobile is None
        or int(primary.get("telegramId") or 0) != telegram_id
        or int(mobile.get("telegramId") or 0) != telegram_id
    ):
        raise RuntimeError("multi slot target users not found")

    effect = order_store.get_delivery_effect(order_id)
    if effect is None:
        slots_delta = max(1, int(order.get("slots_delta") or slot_amount(settings.data)))
        traffic_delta = max(0, _order_int(order, "traffic_delta_bytes", 0))
        old_regular_limit = _device_limit(primary)
        old_mobile_limit = _device_limit(mobile)
        old_traffic = _traffic_limit_bytes(mobile)
        expires_at = _addon_expires_at(primary)
        if old_regular_limit <= 0 or old_mobile_limit <= 0 or old_traffic <= 0 or expires_at is None:
            raise RuntimeError("multi slot target limits are invalid")
        effect = order_store.prepare_delivery_effect(
            order_id=order_id,
            telegram_id=telegram_id,
            kind="slot",
            effect={
                "version": 1,
                "operation": "multi_slot",
                "user_uuid": primary_reference,
                "mobile_user_uuid": mobile_reference,
                "base_regular_device_limit": old_regular_limit,
                "target_regular_device_limit": old_regular_limit + slots_delta,
                "base_mobile_device_limit": old_mobile_limit,
                "target_mobile_device_limit": old_mobile_limit + slots_delta,
                "base_traffic_limit_bytes": old_traffic,
                "target_traffic_limit_bytes": old_traffic + traffic_delta,
                "slots_delta": slots_delta,
                "traffic_delta_bytes": traffic_delta,
                "addon_expires_at": _utc_iso(expires_at),
            },
        )
    _validate_delivery_effect(effect, operation="multi_slot", user_uuid=primary_reference)
    if str(effect.get("mobile_user_uuid") or "") != mobile_reference:
        raise RuntimeError("stored multi slot companion is invalid")
    old_regular_limit = _effect_int(effect, "base_regular_device_limit", minimum=1)
    new_regular_limit = _effect_int(effect, "target_regular_device_limit", minimum=old_regular_limit)
    old_mobile_limit = _effect_int(effect, "base_mobile_device_limit", minimum=1)
    new_mobile_limit = _effect_int(effect, "target_mobile_device_limit", minimum=old_mobile_limit)
    old_traffic = _effect_int(effect, "base_traffic_limit_bytes", minimum=1)
    new_traffic = _effect_int(effect, "target_traffic_limit_bytes", minimum=old_traffic)
    slots_delta = _effect_int(effect, "slots_delta", minimum=1)
    traffic_delta = _effect_int(effect, "traffic_delta_bytes", minimum=0)
    expires_at = effect.get("addon_expires_at")
    if not isinstance(expires_at, str):
        raise RuntimeError("stored multi slot expiration is invalid")
    order_store.create_subscription_addon(
        telegram_id=telegram_id,
        user_uuid=primary_reference,
        order_id=order_id,
        kind="slot",
        slots_delta=slots_delta,
        slots_persistent=True,
        traffic_delta_bytes=traffic_delta,
        expires_at=expires_at,
    )
    if _device_limit(primary) < new_regular_limit:
        primary = _extract_user(
            await remnawave.update_user({"uuid": primary_reference, "hwidDeviceLimit": new_regular_limit})
        ) or primary
    mobile_body: dict[str, Any] = {"uuid": mobile_reference}
    if _device_limit(mobile) < new_mobile_limit:
        mobile_body["hwidDeviceLimit"] = new_mobile_limit
    if traffic_delta > 0 and _traffic_limit_bytes(mobile) < new_traffic:
        mobile_body["trafficLimitBytes"] = new_traffic
    if len(mobile_body) > 1:
        mobile = _extract_user(await remnawave.update_user(mobile_body)) or mobile
    order_store.mark_delivered(order_id)
    decorated = decorate_user(settings, record, primary, mobile)
    return multi_slot_success_text(
        settings.data,
        decorated,
        old_regular_limit=old_regular_limit,
        new_regular_limit=max(_device_limit(primary), new_regular_limit),
        old_mobile_limit=old_mobile_limit,
        new_mobile_limit=max(_device_limit(mobile), new_mobile_limit),
        old_traffic_bytes=old_traffic,
        new_traffic_bytes=max(_traffic_limit_bytes(mobile), new_traffic),
    )


def _wdtt_device_limit(access: dict[str, object]) -> int:
    try:
        return int(access.get("max_devices") or 0)
    except (TypeError, ValueError):
        return 0


def _wdtt_slot_user(access: dict[str, object]) -> dict[str, Any]:
    return {
        "uuid": str(access.get("user_uuid") or ""),
        "username": str(access.get("label") or "Levik VPN"),
        "telegramId": int(access.get("telegram_id") or 0),
        "hwidDeviceLimit": _wdtt_device_limit(access),
        "activeInternalSquads": [{"name": "LTE"}],
        "_wdtt_access": access,
    }


async def _deliver_access_payment(
    telegram_id: int,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    order: dict[str, object],
) -> tuple[str, str | None, str | None, int | None, str | None]:
    order_id = int(order.get("id") or 0)
    kind = str(order.get("kind") or "")
    tariff_id = str(order.get("tariff_id") or "")
    order = await _remap_legacy_wdtt_renewal(
        telegram_id=telegram_id,
        remnawave=remnawave,
        order_store=order_store,
        order=order,
    )
    tariff = _find_tariff(settings, tariff_id)
    if tariff is None:
        raise RuntimeError("tariff not found")
    months = max(1, int(order.get("period_months") or 1))

    if wdtt.enabled_for_tariff(settings, tariff_id):
        wdtt_access = await _deliver_wdtt_access(
            telegram_id=telegram_id,
            settings=settings,
            remnawave=remnawave,
            order_store=order_store,
            order=order,
            tariff=tariff,
            tariff_id=tariff_id,
            months=months,
        )
        order_store.mark_delivered(order_id)
        referral_telegram_id: int | None = None
        referral_text_value: str | None = None
        if kind == "access_purchase":
            referral_telegram_id, referral_text_value = await _grant_wdtt_referral_reward(
                invitee_telegram_id=telegram_id,
                settings=settings,
                order_store=order_store,
                order_id=order_id,
                months=months,
            )
        if kind == "admin_grant":
            action = "Доступ выдан администратором"
        else:
            action = "Доступ выдан автоматически" if kind == "access_purchase" else "Подписка продлена автоматически"
        text = f"✅ <b>{action}</b>\n\n" + wdtt.access_text(
            settings,
            wdtt_access,
            str(settings.data.get("timezone") or "Europe/Moscow"),
        )
        bonus_days = _order_int(order, "bonus_days", 0)
        if bonus_days > 0:
            text += f"\n\n🎁 Добавлено бонусных дней: <b>+{bonus_days}</b>."
        return text, str(wdtt_access.get("user_uuid") or "") or None, None, referral_telegram_id, referral_text_value

    if kind in {"access_purchase", "admin_grant"}:
        delivered_user = await _create_paid_access(
            telegram_id,
            settings,
            remnawave,
            order_store,
            order,
            tariff,
            months,
        )
        wdtt_access = await _sync_wdtt_access(
            telegram_id=telegram_id,
            settings=settings,
            order_store=order_store,
            order=order,
            user=delivered_user,
            tariff_id=tariff_id,
        )
        order_store.mark_delivered(order_id)
        referral_telegram_id: int | None = None
        referral_text_value: str | None = None
        if kind == "access_purchase":
            referral_telegram_id, referral_text_value = await _grant_referral_reward(
                invitee_telegram_id=telegram_id,
                settings=settings,
                remnawave=remnawave,
                order_store=order_store,
                order_id=order_id,
                months=months,
            )
        action = "Доступ выдан администратором" if kind == "admin_grant" else "Доступ выдан автоматически"
        text = f"✅ <b>{action}</b>\n\n" + key_text(
            delivered_user,
            str(settings.data.get("timezone") or "Europe/Moscow"),
        )
        bonus_days = _order_int(order, "bonus_days", 0)
        if bonus_days > 0:
            text += f"\n\n🎁 Добавлено бонусных дней: <b>+{bonus_days}</b>."
        if wdtt_access is not None:
            text += "\n\n" + wdtt.access_text(settings, wdtt_access, str(settings.data.get("timezone") or "Europe/Moscow"))
        return text, _user_uuid(delivered_user), _subscription_url(delivered_user), referral_telegram_id, referral_text_value

    if kind == "access_renewal":
        delivered_user = await _extend_paid_access(telegram_id, settings, remnawave, order_store, order, tariff, months)
        wdtt_access = await _sync_wdtt_access(
            telegram_id=telegram_id,
            settings=settings,
            order_store=order_store,
            order=order,
            user=delivered_user,
            tariff_id=tariff_id,
        )
        order_store.mark_delivered(order_id)
        text = "✅ <b>Подписка продлена автоматически</b>\n\n" + key_text(
            delivered_user,
            str(settings.data.get("timezone") or "Europe/Moscow"),
        )
        bonus_days = _order_int(order, "bonus_days", 0)
        if bonus_days > 0:
            text += f"\n\n🎁 Добавлено бонусных дней: <b>+{bonus_days}</b>."
        if wdtt_access is not None:
            text += "\n\n" + wdtt.access_text(settings, wdtt_access, str(settings.data.get("timezone") or "Europe/Moscow"))
        return text, _user_uuid(delivered_user), _subscription_url(delivered_user), None, None

    raise RuntimeError("unsupported access payment kind")


async def _deliver_wdtt_access(
    *,
    telegram_id: int,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    order: dict[str, object],
    tariff: dict[str, Any],
    tariff_id: str,
    months: int,
) -> dict[str, object]:
    order_id = int(order.get("id") or 0)
    kind = str(order.get("kind") or "")
    now = datetime.now(timezone.utc)

    if kind in {"access_purchase", "admin_grant"}:
        user_uuid = f"wdtt_{telegram_id}_{order_id}"
        existing = order_store.get_wdtt_access_by_user_uuid(user_uuid)
        proposed_expire_at = _add_months(now, months) + timedelta(
            days=max(0, _order_int(order, "bonus_days", 0))
        )
    elif kind == "access_renewal":
        user_uuid = str(order.get("target_user_uuid") or "")
        if not user_uuid:
            raise RuntimeError("WDTT renewal target is missing")
        existing = order_store.get_wdtt_access_by_user_uuid(user_uuid)
        if existing is not None:
            if int(existing.get("telegram_id") or 0) != telegram_id:
                raise RuntimeError("WDTT renewal target user mismatch")
            current_ts = int(existing.get("expires_at") or 0)
            current_expire = datetime.fromtimestamp(current_ts, tz=timezone.utc) if current_ts > 0 else None
        else:
            legacy_user = await remnawave.get_user_by_uuid(user_uuid)
            if legacy_user is None or int(legacy_user.get("telegramId") or 0) != telegram_id:
                raise RuntimeError("WDTT renewal target not found")
            current_expire = parse_dt(legacy_user.get("expireAt"))
            if current_expire is not None and current_expire.tzinfo is None:
                current_expire = current_expire.replace(tzinfo=timezone.utc)
        base = current_expire.astimezone(timezone.utc) if current_expire and current_expire > now else now
        proposed_expire_at = _add_months(base, months) + timedelta(
            days=max(0, _order_int(order, "bonus_days", 0))
        )
    else:
        raise RuntimeError("unsupported WDTT access kind")

    effect = order_store.get_delivery_effect(order_id)
    if effect is None:
        effect = order_store.prepare_delivery_effect(
            order_id=order_id,
            telegram_id=telegram_id,
            kind=kind,
            effect={
                "version": 1,
                "operation": "wdtt_access",
                "user_uuid": user_uuid,
                "target_expires_at": int(
                    proposed_expire_at.astimezone(timezone.utc).timestamp()
                ),
                "target_max_devices": wdtt.max_devices(
                    settings,
                    _tariff_int(tariff, "hwid_device_limit", 1),
                ),
            },
        )
    _validate_delivery_effect(effect, operation="wdtt_access", user_uuid=user_uuid)
    target_expires_at = _effect_int(effect, "target_expires_at", minimum=1)
    target_max_devices = _effect_int(effect, "target_max_devices", minimum=1)
    record = wdtt.build_access_record(
        settings=settings,
        telegram_id=telegram_id,
        user_uuid=user_uuid,
        order_id=order_id,
        tariff_id=tariff_id,
        expires_at=target_expires_at,
        max_devices_value=target_max_devices,
        existing=existing,
    )
    await wdtt.sync_remote_access(settings, record)
    return order_store.upsert_wdtt_access(**record)


async def _grant_wdtt_referral_reward(
    *,
    invitee_telegram_id: int,
    settings: Settings,
    order_store: OrderStore,
    order_id: int,
    months: int,
) -> tuple[int | None, str | None]:
    if invitee_telegram_id <= 0:
        return None, None
    referrals = settings.data.get("referrals") if isinstance(settings.data.get("referrals"), dict) else {}
    if not referrals.get("enabled", True):
        return None, None

    referral = order_store.get_referral_for_invitee(invitee_telegram_id)
    if referral is None or referral.get("reward_granted_at"):
        return None, None
    referrer_telegram_id = int(referral.get("referrer_telegram_id") or 0)
    if referrer_telegram_id <= 0:
        return None, None

    try:
        reward_days = max(1, int(referrals.get("reward_days") or 14))
    except (TypeError, ValueError):
        reward_days = 14
    reward_days = scaled_referral_reward(reward_days, months)

    now = datetime.now(timezone.utc)
    existing_accesses = order_store.get_wdtt_accesses_by_telegram_id(referrer_telegram_id)
    existing = existing_accesses[0] if existing_accesses else None
    user_uuid = str(existing.get("user_uuid")) if existing else f"wdtt_ref_{referrer_telegram_id}_{order_id}"
    current_ts = int(existing.get("expires_at") or 0) if existing else 0
    current_expire = datetime.fromtimestamp(current_ts, tz=timezone.utc) if current_ts > 0 else None
    base = current_expire if current_expire and current_expire > now else now

    record = wdtt.build_access_record(
        settings=settings,
        telegram_id=referrer_telegram_id,
        user_uuid=user_uuid,
        order_id=order_id,
        tariff_id="regular",
        expires_at=int((base + timedelta(days=reward_days)).timestamp()),
        max_devices_value=wdtt.max_devices(settings, 1),
        existing=existing,
    )
    await wdtt.sync_remote_access(settings, record)
    saved = order_store.upsert_wdtt_access(**record)
    order_store.mark_referral_reward_granted(invitee_telegram_id=invitee_telegram_id, order_id=order_id)
    expire_at = datetime.fromtimestamp(int(saved.get("expires_at") or 0), tz=timezone.utc).isoformat()
    text = (
        "🤝 <b>Реферальный бонус начислен</b>\n\n"
        f"Добавлено: <b>+{reward_days} дней</b> Levik VPN.\n"
        f"Действует до: <b>{esc(format_date(expire_at, str(settings.data.get('timezone') or 'Europe/Moscow')))}</b>"
    )
    return referrer_telegram_id, text


async def _grant_referral_reward(
    *,
    invitee_telegram_id: int,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    order_id: int,
    months: int,
) -> tuple[int | None, str | None]:
    if invitee_telegram_id <= 0:
        return None, None
    referrals = settings.data.get("referrals") if isinstance(settings.data.get("referrals"), dict) else {}
    if not referrals.get("enabled", True):
        return None, None

    referral = order_store.get_referral_for_invitee(invitee_telegram_id)
    if referral is None or referral.get("reward_granted_at"):
        return None, None
    referrer_telegram_id = int(referral.get("referrer_telegram_id") or 0)
    if referrer_telegram_id <= 0:
        return None, None

    try:
        reward_days = max(1, int(referrals.get("reward_days") or 14))
    except (TypeError, ValueError):
        reward_days = 14
    try:
        mobile_traffic_reward_bytes = max(0, int(referrals.get("mobile_traffic_reward_bytes") or 10 * GB))
    except (TypeError, ValueError):
        mobile_traffic_reward_bytes = 10 * GB
    reward_days = scaled_referral_reward(reward_days, months)
    mobile_traffic_reward_bytes = scaled_referral_reward(mobile_traffic_reward_bytes, months)
    regular_tariff = _find_tariff(settings, "regular")
    if regular_tariff is None:
        raise RuntimeError("regular tariff not found")

    referrer_users = await remnawave.get_users_by_telegram_id(referrer_telegram_id)
    regular_users = [user for user in referrer_users if _tariff_id_for_user(settings, user) == "regular"]
    if regular_users:
        target = sorted(regular_users, key=lambda user: str(user.get("expireAt") or ""), reverse=True)[0]
        updated_user = await _extend_user_days(remnawave, target, reward_days)
    else:
        updated_user = await _create_reward_access(referrer_telegram_id, settings, remnawave, regular_tariff, reward_days, order_id)

    mobile_users = [user for user in referrer_users if plan_name(user) == MOBILE_PLAN and _is_active_user(user)]
    updated_mobile_user: dict[str, Any] | None = None
    if mobile_users:
        target_mobile = sorted(mobile_users, key=lambda user: str(user.get("expireAt") or ""), reverse=True)[0]
        updated_mobile_user = await _extend_user_days(remnawave, target_mobile, reward_days)
        if mobile_traffic_reward_bytes > 0:
            updated_mobile_user = await _add_mobile_traffic(
                remnawave,
                target_mobile,
                mobile_traffic_reward_bytes,
            )

    order_store.mark_referral_reward_granted(invitee_telegram_id=invitee_telegram_id, order_id=order_id)
    mobile_line = ""
    if updated_mobile_user is not None:
        mobile_traffic_reward_gb = mobile_traffic_reward_bytes // GB
        mobile_line = (
            f"\n\u0422\u0430\u043a\u0436\u0435 \u0434\u043e\u0431\u0430\u0432\u043b\u0435\u043d\u043e: <b>+{reward_days} \u0434\u043d\u0435\u0439</b> \u0438 "
            f"<b>+{mobile_traffic_reward_gb} \u0413\u0411</b> \u041c\u043e\u0431\u0438\u043b\u044c\u043d\u043e\u0433\u043e VPN."
        )
    text = (
        "🤝 <b>Реферальный бонус начислен</b>\n\n"
        f"Добавлено: <b>+{reward_days} дней</b> Обычного VPN.{mobile_line}\n"
        f"Подписка: <b>{user_title(updated_user)}</b>"
    )
    return referrer_telegram_id, text


async def _create_paid_access(
    telegram_id: int,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    order: dict[str, object],
    tariff: dict[str, Any],
    months: int,
) -> dict[str, Any]:
    if str(tariff.get("id") or "") == MULTI_TARIFF_ID:
        return await _create_multi_paid_access(
            telegram_id,
            settings,
            remnawave,
            order_store,
            order,
            tariff,
            months,
        )
    order_id = int(order.get("id") or 0)
    kind = str(order.get("kind") or "")
    effect = order_store.get_delivery_effect(order_id)
    if effect is None:
        now = datetime.now(timezone.utc)
        expire_at = _add_months(now, months) + timedelta(days=max(0, _order_int(order, "bonus_days", 0)))
        squad_uuids = await _internal_squad_uuids(remnawave, tariff)
        external_squad_uuid = _lte_external_squad_uuid(tariff)
        body: dict[str, object] = {
            "username": _safe_username(telegram_id, str(order.get("tariff_id") or "vpn"), order_id),
            "status": "ACTIVE",
            "trafficLimitBytes": _tariff_int(tariff, "traffic_limit_bytes", 0),
            "trafficLimitStrategy": _tariff_strategy(tariff),
            "expireAt": _utc_iso(expire_at),
            "telegramId": telegram_id,
            "description": (
                f"{tariff.get('title') or 'VPN'} · order #{order_id} · "
                f"{order.get('payment_method') or 'online'}"
            ),
            "hwidDeviceLimit": _tariff_int(tariff, "hwid_device_limit", 5),
            "activeInternalSquads": squad_uuids,
        }
        if external_squad_uuid is not None:
            body["externalSquadUuid"] = external_squad_uuid
        effect = order_store.prepare_delivery_effect(
            order_id=order_id,
            telegram_id=telegram_id,
            kind=kind,
            effect={
                "version": 1,
                "operation": "create_access",
                "username": str(body["username"]),
                "body": body,
            },
        )
    _validate_delivery_effect(effect, operation="create_access")
    username = effect.get("username")
    body = effect.get("body")
    if not isinstance(username, str) or not username or not isinstance(body, dict):
        raise RuntimeError("stored access delivery effect is invalid")
    if body.get("username") != username or int(body.get("telegramId") or 0) != telegram_id:
        raise RuntimeError("stored access delivery effect owner is invalid")

    existing = await _find_order_created_user(
        remnawave=remnawave,
        telegram_id=telegram_id,
        username=username,
    )
    if existing is not None:
        return existing

    try:
        created = _extract_user(await remnawave.create_user(body))
    except Exception:
        existing = await _find_order_created_user(
            remnawave=remnawave,
            telegram_id=telegram_id,
            username=username,
        )
        if existing is not None:
            return existing
        raise
    if created is None:
        existing = await _find_order_created_user(
            remnawave=remnawave,
            telegram_id=telegram_id,
            username=username,
        )
        if existing is not None:
            return existing
        raise RuntimeError("created user missing")
    if int(created.get("telegramId") or 0) != telegram_id:
        raise RuntimeError("created user owner mismatch")
    created_username = str(created.get("username") or "")
    if created_username and created_username != username:
        raise RuntimeError("created user username mismatch")
    return created


async def _create_multi_paid_access(
    telegram_id: int,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    order: dict[str, object],
    tariff: dict[str, Any],
    months: int,
) -> dict[str, Any]:
    order_id = int(order.get("id") or 0)
    regular_tariff = _find_tariff(settings, str(tariff.get("regular_tariff_id") or "regular"))
    mobile_tariff = _find_tariff(settings, str(tariff.get("mobile_tariff_id") or "lte_solo"))
    if regular_tariff is None or mobile_tariff is None:
        raise RuntimeError("multi component tariff not found")
    effect = order_store.get_delivery_effect(order_id)
    if effect is None:
        expire_at = _add_months(datetime.now(timezone.utc), months) + timedelta(
            days=max(0, _order_int(order, "bonus_days", 0))
        )
        regular_squads = await _internal_squad_uuids(remnawave, regular_tariff)
        mobile_squads = await _internal_squad_uuids(remnawave, mobile_tariff)
        primary_username = _safe_username(telegram_id, "multi_regular", order_id)
        mobile_username = _safe_username(telegram_id, "multi_mobile", order_id)
        primary_body: dict[str, object] = {
            "username": primary_username,
            "status": "ACTIVE",
            "trafficLimitBytes": 0,
            "trafficLimitStrategy": _tariff_strategy(regular_tariff),
            "expireAt": _utc_iso(expire_at),
            "telegramId": telegram_id,
            "description": f"[multi:primary] order #{order_id} · {order.get('payment_method') or 'online'}",
            "hwidDeviceLimit": _tariff_int(regular_tariff, "hwid_device_limit", 5),
            "activeInternalSquads": regular_squads,
        }
        mobile_body: dict[str, object] = {
            "username": mobile_username,
            "status": "ACTIVE",
            "trafficLimitBytes": _tariff_int(mobile_tariff, "traffic_limit_bytes", 50 * GB),
            "trafficLimitStrategy": _tariff_strategy(mobile_tariff),
            "expireAt": _utc_iso(expire_at),
            "telegramId": telegram_id,
            "description": f"[multi:mobile] order #{order_id} · {order.get('payment_method') or 'online'}",
            "hwidDeviceLimit": _tariff_int(mobile_tariff, "hwid_device_limit", 1),
            "activeInternalSquads": mobile_squads,
        }
        external_squad_uuid = _lte_external_squad_uuid(mobile_tariff)
        if external_squad_uuid is not None:
            mobile_body["externalSquadUuid"] = external_squad_uuid
        effect = order_store.prepare_delivery_effect(
            order_id=order_id,
            telegram_id=telegram_id,
            kind=str(order.get("kind") or "access_purchase"),
            effect={
                "version": 1,
                "operation": "create_multi_access",
                "primary_username": primary_username,
                "mobile_username": mobile_username,
                "primary_body": primary_body,
                "mobile_body": mobile_body,
            },
        )
    _validate_delivery_effect(effect, operation="create_multi_access")
    primary_username = str(effect.get("primary_username") or "")
    mobile_username = str(effect.get("mobile_username") or "")
    primary_body = effect.get("primary_body")
    mobile_body = effect.get("mobile_body")
    if not primary_username or not mobile_username or not isinstance(primary_body, dict) or not isinstance(mobile_body, dict):
        raise RuntimeError("stored multi access effect is invalid")
    primary = await _find_order_created_user(remnawave=remnawave, telegram_id=telegram_id, username=primary_username)
    if primary is None:
        primary = _extract_user(await remnawave.create_user(primary_body))
    if primary is None:
        raise RuntimeError("multi primary user missing")
    mobile = await _find_order_created_user(remnawave=remnawave, telegram_id=telegram_id, username=mobile_username)
    if mobile is None:
        mobile = _extract_user(await remnawave.create_user(mobile_body))
    if mobile is None:
        raise RuntimeError("multi mobile user missing")
    if int(primary.get("telegramId") or 0) != telegram_id or int(mobile.get("telegramId") or 0) != telegram_id:
        raise RuntimeError("multi user owner mismatch")
    record = order_store.upsert_multi_subscription(
        telegram_id=telegram_id,
        primary_user_uuid=str(primary.get("uuid") or ""),
        mobile_user_uuid=str(mobile.get("uuid") or ""),
    )
    return decorate_user(settings, record, primary, mobile)


async def _extend_paid_access(
    telegram_id: int,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    order: dict[str, object],
    tariff: dict[str, Any],
    months: int,
) -> dict[str, Any]:
    user_uuid = str(order.get("target_user_uuid") or "")
    multi = order_store.get_multi_subscription_by_user_uuid(user_uuid)
    if multi is not None:
        return await _extend_multi_paid_access(
            telegram_id=telegram_id,
            settings=settings,
            remnawave=remnawave,
            order_store=order_store,
            order=order,
            tariff=tariff,
            months=months,
            record=multi,
        )
    user = await remnawave.get_user_by_uuid(user_uuid) if user_uuid else None
    if user is None or int(user.get("telegramId") or 0) != telegram_id:
        raise RuntimeError("renewal target user not found")
    now = datetime.now(timezone.utc)
    current_expire = parse_dt(user.get("expireAt"))
    if current_expire is not None and current_expire.tzinfo is None:
        current_expire = current_expire.replace(tzinfo=timezone.utc)
    effect = order_store.get_delivery_effect(int(order.get("id") or 0))
    if effect is None:
        base = current_expire.astimezone(timezone.utc) if current_expire and current_expire > now else now
        expire_at = _add_months(base, months) + timedelta(days=max(0, _order_int(order, "bonus_days", 0)))
        effect = order_store.prepare_delivery_effect(
            order_id=int(order.get("id") or 0),
            telegram_id=telegram_id,
            kind="access_renewal",
            effect={
                "version": 1,
                "operation": "renew_access",
                "user_uuid": user_uuid,
                "target_expire_at": _utc_iso(expire_at),
            },
        )
    _validate_delivery_effect(effect, operation="renew_access", user_uuid=user_uuid)
    target_expire_at = effect.get("target_expire_at")
    target_expire = parse_dt(target_expire_at)
    if target_expire is None:
        raise RuntimeError("stored renewal delivery effect is invalid")
    if target_expire.tzinfo is None:
        target_expire = target_expire.replace(tzinfo=timezone.utc)
    target_expire = target_expire.astimezone(timezone.utc)
    update_body: dict[str, Any] = {
        "uuid": user_uuid,
        "status": "ACTIVE",
    }
    if current_expire is None or current_expire.astimezone(timezone.utc) < target_expire:
        update_body["expireAt"] = _utc_iso(target_expire)
    if str(tariff.get("id") or "").startswith("lte"):
        addon_totals = order_store.active_subscription_addon_totals(user_uuid)
        update_body.update(_mobile_limit_body(tariff, addon_totals))
    updated = _extract_user(
        await remnawave.update_user(update_body)
    )
    if updated is None:
        raise RuntimeError("updated user missing")
    return updated


async def _extend_multi_paid_access(
    *,
    telegram_id: int,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    order: dict[str, object],
    tariff: dict[str, Any],
    months: int,
    record: dict[str, object],
) -> dict[str, Any]:
    primary_reference = str(record.get("primary_user_uuid") or "")
    mobile_reference = str(record.get("mobile_user_uuid") or "")
    primary = await remnawave.get_user_by_uuid(primary_reference)
    mobile = await remnawave.get_user_by_uuid(mobile_reference)
    if primary is None or mobile is None or int(primary.get("telegramId") or 0) != telegram_id or int(mobile.get("telegramId") or 0) != telegram_id:
        raise RuntimeError("multi renewal target users not found")
    regular_tariff = _find_tariff(settings, str(tariff.get("regular_tariff_id") or "regular"))
    mobile_tariff = _find_tariff(settings, str(tariff.get("mobile_tariff_id") or "lte_solo"))
    if regular_tariff is None or mobile_tariff is None:
        raise RuntimeError("multi component tariff not found")
    now = datetime.now(timezone.utc)
    current_expirations = [parse_dt(primary.get("expireAt")), parse_dt(mobile.get("expireAt"))]
    normalized = [value.replace(tzinfo=timezone.utc) if value is not None and value.tzinfo is None else value for value in current_expirations]
    active_expirations = [value.astimezone(timezone.utc) for value in normalized if value is not None and value > now]
    base = max(active_expirations) if active_expirations else now
    effect = order_store.get_delivery_effect(int(order.get("id") or 0))
    if effect is None:
        target = _add_months(base, months) + timedelta(days=max(0, _order_int(order, "bonus_days", 0)))
        effect = order_store.prepare_delivery_effect(
            order_id=int(order.get("id") or 0),
            telegram_id=telegram_id,
            kind="access_renewal",
            effect={
                "version": 1,
                "operation": "renew_multi_access",
                "user_uuid": primary_reference,
                "mobile_user_uuid": mobile_reference,
                "target_expire_at": _utc_iso(target),
            },
        )
    _validate_delivery_effect(effect, operation="renew_multi_access", user_uuid=primary_reference)
    if str(effect.get("mobile_user_uuid") or "") != mobile_reference:
        raise RuntimeError("stored multi renewal companion is invalid")
    target = parse_dt(effect.get("target_expire_at"))
    if target is None:
        raise RuntimeError("stored multi renewal expiration is invalid")
    if target.tzinfo is None:
        target = target.replace(tzinfo=timezone.utc)
    addons = order_store.active_subscription_addon_totals(primary_reference)
    regular_limit = _tariff_int(regular_tariff, "hwid_device_limit", 5) + max(0, int(addons.get("slots_delta") or 0))
    primary = _extract_user(
        await remnawave.update_user(
            {
                "uuid": primary_reference,
                "status": "ACTIVE",
                "expireAt": _utc_iso(target),
                "trafficLimitBytes": 0,
                "trafficLimitStrategy": _tariff_strategy(regular_tariff),
                "hwidDeviceLimit": regular_limit,
            }
        )
    ) or primary
    mobile = _extract_user(
        await remnawave.update_user(
            {
                "uuid": mobile_reference,
                "status": "ACTIVE",
                "expireAt": _utc_iso(target),
                **_mobile_limit_body(mobile_tariff, addons),
            }
        )
    ) or mobile
    return decorate_user(settings, record, primary, mobile)


async def reconcile_expired_subscription_addons(
    *,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    limit: int = 200,
) -> int:
    expired_addons = order_store.expired_subscription_addons(limit=limit)
    if not expired_addons:
        return 0

    by_user_uuid: dict[str, list[dict[str, object]]] = {}
    for addon in expired_addons:
        user_uuid = str(addon.get("user_uuid") or "")
        if user_uuid:
            by_user_uuid.setdefault(user_uuid, []).append(addon)

    reconciled = 0
    for user_uuid, addons in by_user_uuid.items():
        addon_ids = [int(addon.get("id") or 0) for addon in addons if int(addon.get("id") or 0) > 0]
        multi = order_store.get_multi_subscription_by_user_uuid(user_uuid)
        if multi is not None:
            primary_reference = str(multi.get("primary_user_uuid") or "")
            mobile_reference = str(multi.get("mobile_user_uuid") or "")
            regular_tariff = _find_tariff(settings, "regular")
            mobile_tariff = _find_tariff(settings, "lte_solo")
            if regular_tariff is not None and mobile_tariff is not None:
                addon_totals = order_store.active_subscription_addon_totals(primary_reference)
                await remnawave.update_user(
                    {
                        "uuid": primary_reference,
                        "hwidDeviceLimit": _tariff_int(regular_tariff, "hwid_device_limit", 5)
                        + max(0, int(addon_totals.get("slots_delta") or 0)),
                    }
                )
                await remnawave.update_user({"uuid": mobile_reference, **_mobile_limit_body(mobile_tariff, addon_totals)})
            order_store.mark_subscription_addons_expired(addon_ids)
            reconciled += len(addon_ids)
            continue
        user = await remnawave.get_user_by_uuid(user_uuid) if _is_remnawave_uuid(user_uuid) else None
        if user is not None and plan_name(user) == MOBILE_PLAN:
            tariff_id = _tariff_id_for_user(settings, user)
            tariff = _find_tariff(settings, tariff_id)
            if tariff is not None:
                addon_totals = order_store.active_subscription_addon_totals(user_uuid)
                await remnawave.update_user({"uuid": user_uuid, **_mobile_limit_body(tariff, addon_totals)})
        order_store.mark_subscription_addons_expired(addon_ids)
        reconciled += len(addon_ids)

    return reconciled


async def _extend_user_days(remnawave: RemnawaveClient, user: dict[str, Any], days: int) -> dict[str, Any]:
    now = datetime.now(timezone.utc)
    current_expire = parse_dt(user.get("expireAt"))
    if current_expire is not None and current_expire.tzinfo is None:
        current_expire = current_expire.replace(tzinfo=timezone.utc)
    base = current_expire.astimezone(timezone.utc) if current_expire and current_expire > now else now
    updated = _extract_user(
        await remnawave.update_user(
            {
                "uuid": str(user["uuid"]),
                "status": "ACTIVE",
                "expireAt": _utc_iso(base + timedelta(days=days)),
            }
        )
    )
    if updated is None:
        raise RuntimeError("referral reward update missing")
    return updated


async def _add_mobile_traffic(
    remnawave: RemnawaveClient,
    user: dict[str, Any],
    traffic_bytes: int,
) -> dict[str, Any]:
    current_limit = _traffic_limit_bytes(user)
    if current_limit <= 0 or traffic_bytes <= 0:
        return user
    updated = _extract_user(
        await remnawave.update_user(
            {
                "uuid": str(user["uuid"]),
                "trafficLimitBytes": current_limit + traffic_bytes,
            }
        )
    )
    if updated is None:
        raise RuntimeError("referral mobile traffic reward update missing")
    return updated


async def _create_reward_access(
    telegram_id: int,
    settings: Settings,
    remnawave: RemnawaveClient,
    tariff: dict[str, Any],
    days: int,
    order_id: int,
) -> dict[str, Any]:
    expire_at = datetime.now(timezone.utc) + timedelta(days=days)
    squad_uuids = await _internal_squad_uuids(remnawave, tariff)
    external_squad_uuid = _lte_external_squad_uuid(tariff)
    body = {
        "username": _safe_username(telegram_id, "ref", order_id),
        "status": "ACTIVE",
        "trafficLimitBytes": _tariff_int(tariff, "traffic_limit_bytes", 0),
        "trafficLimitStrategy": _tariff_strategy(tariff),
        "expireAt": _utc_iso(expire_at),
        "telegramId": telegram_id,
        "description": f"Referral reward · order #{order_id}",
        "hwidDeviceLimit": _tariff_int(tariff, "hwid_device_limit", 5),
        "activeInternalSquads": squad_uuids,
    }
    if external_squad_uuid is not None:
        body["externalSquadUuid"] = external_squad_uuid
    created = _extract_user(await remnawave.create_user(body))
    if created is None:
        raise RuntimeError("referral reward user missing")
    return created


async def provision_trial_user(
    *,
    telegram_id: int,
    settings: Settings,
    remnawave: RemnawaveClient,
    tariff_id: str,
    component: str,
    expires_at: str,
    traffic_limit_bytes: int | None = None,
    existing_user_uuid: str | None = None,
) -> dict[str, Any]:
    if component not in {"regular", "mobile"}:
        raise ValueError("unknown trial component")
    tariff = _find_tariff(settings, tariff_id)
    if tariff is None:
        raise RuntimeError("trial tariff not found")

    expire_at = parse_dt(expires_at)
    if expire_at is None:
        raise RuntimeError("trial expiration is invalid")
    if expire_at.tzinfo is None:
        expire_at = expire_at.replace(tzinfo=timezone.utc)
    expire_at = expire_at.astimezone(timezone.utc)
    if expire_at <= datetime.now(timezone.utc):
        raise RuntimeError("trial expiration has passed")

    if existing_user_uuid:
        existing = await remnawave.get_user_by_uuid(existing_user_uuid)
        if existing is not None and int(existing.get("telegramId") or 0) == telegram_id:
            return existing

    username = _safe_trial_username(telegram_id, component)
    for existing in await remnawave.get_users_by_telegram_id(telegram_id):
        if str(existing.get("username") or "") == username:
            return existing

    squad_uuids = await _internal_squad_uuids(remnawave, tariff)
    external_squad_uuid = _lte_external_squad_uuid(tariff)
    limit = _tariff_int(tariff, "traffic_limit_bytes", 0) if traffic_limit_bytes is None else max(0, traffic_limit_bytes)
    body = {
        "username": username,
        "status": "ACTIVE",
        "trafficLimitBytes": limit,
        "trafficLimitStrategy": _tariff_strategy(tariff),
        "expireAt": _utc_iso(expire_at),
        "telegramId": telegram_id,
        "description": f"Trial access · {component}",
        "hwidDeviceLimit": _tariff_int(tariff, "hwid_device_limit", 5),
        "activeInternalSquads": squad_uuids,
    }
    if external_squad_uuid is not None:
        body["externalSquadUuid"] = external_squad_uuid
    created = _extract_user(await remnawave.create_user(body))
    if created is None:
        raise RuntimeError("trial user missing")
    return created


def _find_tariff(settings: Settings, tariff_id: str) -> dict[str, Any] | None:
    tariffs = settings.data.get("tariffs")
    items = [tariff for tariff in tariffs if isinstance(tariff, dict)] if isinstance(tariffs, list) else []
    for tariff in items:
        if str(tariff.get("id") or "") == tariff_id:
            return tariff
    return None


def _device_limit(user: dict[str, Any]) -> int:
    try:
        return int(user.get("hwidDeviceLimit") or 0)
    except (TypeError, ValueError):
        return 0


def _traffic_limit_bytes(user: dict[str, Any]) -> int:
    try:
        return int(user.get("trafficLimitBytes") or 0)
    except (TypeError, ValueError):
        return 0


def _order_int(order: dict[str, object], key: str, default: int) -> int:
    try:
        return int(order.get(key) if order.get(key) is not None else default)
    except (TypeError, ValueError):
        return default


def _addon_expires_at(user: dict[str, Any]) -> datetime | None:
    expire_at = parse_dt(user.get("expireAt"))
    if expire_at is None or expire_at.year >= 2099:
        return None
    if expire_at.tzinfo is None:
        expire_at = expire_at.replace(tzinfo=timezone.utc)
    expire_at = expire_at.astimezone(timezone.utc)
    return expire_at if expire_at > datetime.now(timezone.utc) else None


def _mobile_limit_body(tariff: dict[str, Any], addon_totals: dict[str, int]) -> dict[str, Any]:
    base_devices = max(0, _tariff_int(tariff, "hwid_device_limit", 1))
    base_traffic = max(0, _tariff_int(tariff, "traffic_limit_bytes", 0))
    slots_delta = max(0, int(addon_totals.get("slots_delta") or 0))
    traffic_delta = max(0, int(addon_totals.get("traffic_delta_bytes") or 0))
    return {
        "hwidDeviceLimit": base_devices + slots_delta if base_devices > 0 else 0,
        "trafficLimitBytes": base_traffic + traffic_delta if base_traffic > 0 else 0,
        "trafficLimitStrategy": _tariff_strategy(tariff),
    }


def _is_active_user(user: dict[str, Any]) -> bool:
    if str(user.get("status") or "").upper() != "ACTIVE":
        return False
    expire_at = parse_dt(user.get("expireAt"))
    if expire_at is None or expire_at.year >= 2099:
        return True
    if expire_at.tzinfo is None:
        expire_at = expire_at.replace(tzinfo=timezone.utc)
    return expire_at.astimezone(timezone.utc) > datetime.now(timezone.utc)


def _is_remnawave_uuid(value: str) -> bool:
    return bool(
        re.fullmatch(
            r"(?:[0-9]+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})",
            value,
        )
    )


def _tariff_id_for_user(settings: Settings, user: dict[str, Any]) -> str:
    return subscription_tariff_id_for_user(settings.data, user)


def _add_months(value: datetime, months: int) -> datetime:
    month_index = value.month - 1 + months
    year = value.year + month_index // 12
    month = month_index % 12 + 1
    day = min(value.day, calendar.monthrange(year, month)[1])
    return value.replace(year=year, month=month, day=day)


def _utc_iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _tariff_int(tariff: dict[str, Any], key: str, default: int) -> int:
    try:
        return int(tariff.get(key) if tariff.get(key) is not None else default)
    except (TypeError, ValueError):
        return default


def _tariff_strategy(tariff: dict[str, Any]) -> str:
    return str(tariff.get("traffic_limit_strategy") or "MONTH_ROLLING")


def _validate_delivery_effect(
    effect: dict[str, object],
    *,
    operation: str,
    user_uuid: str | None = None,
) -> None:
    if effect.get("version") != 1 or effect.get("operation") != operation:
        raise RuntimeError("stored delivery effect is incompatible")
    if user_uuid is not None and effect.get("user_uuid") != user_uuid:
        raise RuntimeError("stored delivery effect target mismatch")


def _effect_int(
    effect: dict[str, object],
    key: str,
    *,
    minimum: int,
) -> int:
    value = effect.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, str)):
        raise TypeError("stored delivery effect is invalid")
    try:
        parsed = int(value)
    except (TypeError, ValueError) as exc:
        raise RuntimeError("stored delivery effect is invalid") from exc
    if parsed < minimum:
        raise RuntimeError("stored delivery effect is invalid")
    return parsed


async def _find_order_created_user(
    *,
    remnawave: RemnawaveClient,
    telegram_id: int,
    username: str,
) -> dict[str, Any] | None:
    users = await remnawave.get_users_by_telegram_id(telegram_id)
    matches = [
        user
        for user in users
        if int(user.get("telegramId") or 0) == telegram_id
        and str(user.get("username") or "") == username
    ]
    if len(matches) > 1:
        raise RuntimeError("multiple users match paid order username")
    return matches[0] if matches else None


def _safe_username(telegram_id: int, tariff_id: str, order_id: int) -> str:
    prefix = re.sub(r"[^a-zA-Z0-9_-]", "_", f"tg{telegram_id}_{tariff_id}")
    suffix = re.sub(r"[^a-zA-Z0-9_-]", "_", f"_{order_id}")
    if len(suffix) >= 36:
        return suffix[-36:]
    available_prefix = 36 - len(suffix)
    return f"{prefix[:available_prefix]}{suffix}"


def _safe_trial_username(telegram_id: int, component: str) -> str:
    raw = f"tg{telegram_id}_trial_{component}"
    safe = re.sub(r"[^a-zA-Z0-9_-]", "_", raw)
    return safe[:36]


def _extract_user(payload: dict[str, Any] | None) -> dict[str, Any] | None:
    if not isinstance(payload, dict):
        return None
    user = payload.get("user")
    return user if isinstance(user, dict) else payload


def _user_uuid(user: dict[str, Any]) -> str | None:
    user_uuid = str(user.get("uuid") or "")
    return user_uuid or None


def _subscription_url(user: dict[str, Any]) -> str | None:
    subscription_url = str(user.get("subscriptionUrl") or "")
    return subscription_url or None


async def _sync_wdtt_access(
    *,
    telegram_id: int,
    settings: Settings,
    order_store: OrderStore,
    order: dict[str, object],
    user: dict[str, Any],
    tariff_id: str,
) -> dict[str, object] | None:
    if not wdtt.enabled_for_tariff(settings, tariff_id):
        return None

    user_uuid = _user_uuid(user)
    if user_uuid is None:
        raise RuntimeError("WDTT user uuid is missing")
    expire_at = parse_dt(user.get("expireAt"))
    expires_at = int(expire_at.astimezone(timezone.utc).timestamp()) if expire_at is not None else 0
    existing = order_store.get_wdtt_access_by_user_uuid(user_uuid)
    record = wdtt.build_access_record(
        settings=settings,
        telegram_id=telegram_id,
        user_uuid=user_uuid,
        order_id=int(order.get("id") or 0),
        tariff_id=tariff_id,
        expires_at=expires_at,
        max_devices_value=wdtt.max_devices(settings, _device_limit(user) or 1),
        existing=existing,
    )
    await wdtt.sync_remote_access(settings, record)
    return order_store.upsert_wdtt_access(**record)


async def _remap_legacy_wdtt_renewal(
    *,
    telegram_id: int,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    order: dict[str, object],
) -> dict[str, object]:
    if str(order.get("kind") or "") != "access_renewal":
        return order

    source_user_uuid = str(order.get("target_user_uuid") or "")
    wdtt_access = order_store.get_wdtt_access_by_user_uuid(source_user_uuid) if source_user_uuid else None
    if wdtt_access is None:
        return order
    if int(wdtt_access.get("telegram_id") or 0) != telegram_id:
        raise RuntimeError("legacy WDTT renewal target user mismatch")

    direct_user = await remnawave.get_user_by_uuid(source_user_uuid) if _is_remnawave_uuid(source_user_uuid) else None
    if direct_user is not None and int(direct_user.get("telegramId") or 0) == telegram_id:
        return order

    migration = order_store.get_wdtt_remnawave_migration(source_user_uuid)
    if migration is None:
        raise RuntimeError("legacy WDTT renewal has no Remnawave migration")

    remnawave_user_uuid = str(migration.get("remnawave_user_uuid") or "")
    user = await remnawave.get_user_by_uuid(remnawave_user_uuid) if remnawave_user_uuid else None
    if user is None or int(user.get("telegramId") or 0) != telegram_id:
        raise RuntimeError("legacy WDTT renewal Remnawave target not found")

    remapped = dict(order)
    remapped["target_user_uuid"] = remnawave_user_uuid
    remapped["target_user_name"] = str(user.get("username") or user.get("email") or remapped.get("target_user_name") or "")
    return remapped


async def _internal_squad_uuids(remnawave: RemnawaveClient, tariff: dict[str, Any]) -> list[str]:
    names = tariff.get("internal_squads") if isinstance(tariff.get("internal_squads"), list) else []
    wanted = [str(name) for name in names if name]
    squads = await remnawave.get_internal_squads()
    by_name = {str(squad.get("name")): str(squad.get("uuid")) for squad in squads if squad.get("name") and squad.get("uuid")}
    missing = [name for name in wanted if name not in by_name]
    if missing:
        raise RuntimeError("missing internal squads: " + ", ".join(missing))
    return [by_name[name] for name in wanted]


def _lte_external_squad_uuid(tariff: dict[str, Any]) -> str | None:
    names = tariff.get("internal_squads") if isinstance(tariff.get("internal_squads"), list) else []
    if [str(name) for name in names if name] != ["LTE"]:
        return None

    external_squad_uuid = str(tariff.get("external_squad_uuid") or "").strip()
    if not _is_remnawave_uuid(external_squad_uuid):
        raise RuntimeError("LTE tariff external squad UUID is missing or invalid")
    return external_squad_uuid
