from __future__ import annotations

import logging
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from aiogram import F, Router
from aiogram.exceptions import TelegramAPIError, TelegramBadRequest
from aiogram.filters import Command, CommandObject, CommandStart
from aiogram.types import CallbackQuery, ErrorEvent, FSInputFile, LabeledPrice, Message, PreCheckoutQuery

from app.config import Settings
from app.happ_routing import is_enabled as happ_routing_enabled
from app.formatters import (
    GB,
    MOBILE_PLAN,
    MULTI_PLAN,
    account_text,
    access_invoice_description,
    access_invoice_title,
    device_name,
    devices_text,
    esc,
    format_date,
    happ_routing_disable_confirm_text,
    happ_routing_enable_confirm_text,
    happ_routing_offer_text,
    happ_routing_ready_text,
    instructions_text,
    key_text,
    legal_text,
    mobile_traffic_amount_bytes,
    mobile_traffic_config,
    mobile_traffic_enabled,
    mobile_traffic_invoice_description,
    mobile_traffic_invoice_title,
    mobile_traffic_platega_amount,
    mobile_traffic_price_rub,
    mobile_traffic_purchase_text,
    mobile_traffic_stars,
    no_access_text,
    connection_check_text,
    connection_guide_text,
    platform_setup_text,
    scenario_text,
    trial_single_success_text,
    used_traffic_bytes,
    payment_link_text,
    payment_method,
    payment_method_amount,
    payment_method_request_amount,
    payment_method_title,
    period_months,
    period_title,
    period_total,
    period_total_stars,
    plan_name,
    profile_text,
    purchase_confirm_text,
    purchase_period_text,
    referral_discount_amount,
    referral_text,
    rub,
    service_text,
    slot_amount,
    slot_invoice_description,
    slot_invoice_title,
    slot_platega_amount,
    slot_price_rub,
    slot_purchase_text,
    slot_stars,
    slot_traffic_delta_bytes,
    slot_unlimited_text,
    status_label,
    subscription_text,
    subscriptions_text,
    tariffs_text,
    telegram_stars_enabled,
    traffic_line,
    trial_admin_notification_text,
    trial_success_text,
)
from app.keyboards import (
    access_success_keyboard,
    admin_keyboard,
    back_home_keyboard,
    cabinet_auth_confirm_keyboard,
    device_delete_all_confirm_keyboard,
    device_delete_confirm_keyboard,
    devices_keyboard,
    happ_routing_confirm_keyboard,
    happ_routing_disable_confirm_keyboard,
    happ_routing_manage_keyboard,
    happ_routing_open_keyboard,
    happ_import_url,
    home_keyboard,
    key_keyboard,
    mobile_traffic_payment_keyboard,
    more_keyboard,
    no_access_keyboard,
    connection_result_keyboard,
    connection_guide_keyboard,
    platform_keyboard,
    scenario_keyboard,
    setup_keyboard,
    support_diagnostics_keyboard,
    payment_link_keyboard,
    payment_cancel_reason_keyboard,
    periods_keyboard,
    purchase_confirm_keyboard,
    refresh_confirm_keyboard,
    referral_keyboard,
    renewal_confirm_keyboard,
    renewal_periods_keyboard,
    renewal_subscriptions_keyboard,
    service_keyboard,
    shield_manage_keyboard,
    slot_payment_keyboard,
    subscription_keyboard,
    subscriptions_keyboard,
    tariffs_keyboard,
    trial_retry_keyboard,
    trial_success_keyboard,
)
from app.delivery import deliver_paid_order, parse_payment_payload, payment_payload
from app.orders import OrderAlreadyInProgress, OrderStore
from app.cabinet_auth import opaque_token_hash
from app.platega import PlategaApiError, PlategaClient
from app.remnawave import RemnawaveApiError, RemnawaveClient
from app.tariffs import subscription_tariff_id_for_user
from app.multi_subscription import decorate_user, merge_users
from app.trials import (
    TrialActivationError,
    claim_trial,
    provision_trial,
    trial_available,
    trial_config,
)
from app import wdtt
from app import mtproto

router = Router()
logger = logging.getLogger(__name__)
ADMIN_TELEGRAM_ID = 351358714
WDTT_LEGACY_PAYMENT_MESSAGE = (
    "Это старый WDTT-ключ. Он продолжит работать до конца срока, "
    "а продление и докупка слотов доступны только для нового ключа Мобильного VPN в Happ."
)
ORDER_ALREADY_IN_PROGRESS_MESSAGE = (
    "Для этой подписки уже создан незавершённый заказ. "
    "Завершите его оплату или дождитесь обновления статуса."
)
REMNAWAVE_UUID_RE = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)


@router.errors()
async def on_error(event: ErrorEvent) -> bool:
    logger.exception("telegram handler failed", exc_info=event.exception)
    message = "Сервис временно недоступен. Попробуйте позже."
    update = event.update
    if update.callback_query is not None:
        await update.callback_query.answer(message, show_alert=True)
    elif update.message is not None:
        await update.message.answer(message)
    return True


def _telegram_id(message_or_callback: Message | CallbackQuery) -> int:
    user = message_or_callback.from_user
    if user is None:
        raise RuntimeError("Telegram user is missing")
    return user.id


def _first_name(message_or_callback: Message | CallbackQuery) -> str:
    user = message_or_callback.from_user
    return user.first_name if user and user.first_name else "друг"


def _telegram_username(message_or_callback: Message | CallbackQuery) -> str | None:
    user = message_or_callback.from_user
    return user.username if user and user.username else None


def _support_url(settings: Settings) -> str | None:
    support = settings.data.get("support") if isinstance(settings.data.get("support"), dict) else {}
    url = str(support.get("url") or "").strip()
    return url or None


def _sort_users(users: list[dict[str, Any]]) -> list[dict[str, Any]]:
    def key(user: dict[str, Any]) -> tuple[int, str]:
        active = 0 if str(user.get("status") or "").upper() == "ACTIVE" else 1
        return active, str(user.get("username") or user.get("email") or "")

    return sorted(users, key=key)


def _wdtt_user(access: dict[str, object]) -> dict[str, Any]:
    expires_at = int(access.get("expires_at") or 0)
    expire_at = ""
    if expires_at > 0:
        expire_at = datetime.fromtimestamp(expires_at, tz=timezone.utc).isoformat().replace("+00:00", "Z")
    status = "ACTIVE" if expires_at <= 0 or expires_at > int(datetime.now(timezone.utc).timestamp()) else "EXPIRED"
    try:
        device_limit = int(access.get("max_devices") or 0)
    except (TypeError, ValueError):
        device_limit = 0
    label = str(access.get("label") or "Levik VPN")
    return {
        "uuid": str(access.get("user_uuid") or ""),
        "username": f"WDTT · {label}",
        "telegramId": int(access.get("telegram_id") or 0),
        "status": status,
        "expireAt": expire_at,
        "hwidDeviceLimit": device_limit,
        "activeInternalSquads": [{"name": "LTE"}],
        "_wdtt_access": access,
    }


async def _load_users(remnawave: RemnawaveClient, telegram_id: int, order_store: OrderStore | None = None) -> list[dict[str, Any]]:
    wdtt_users: list[dict[str, Any]] = []
    if order_store is not None:
        for access in order_store.get_wdtt_accesses_by_telegram_id(telegram_id):
            user = _wdtt_user(access)
            wdtt_users.append(user)

        try:
            remnawave_users = await remnawave.get_users_by_telegram_id(telegram_id)
        except RemnawaveApiError:
            logger.warning("failed to load Remnawave users, showing WDTT accesses only", exc_info=True)
            remnawave_users = []

        remnawave_users = merge_users(
            {},
            remnawave_users,
            order_store.list_multi_subscriptions_by_telegram_id(telegram_id),
        )
        return _sort_users(wdtt_users + remnawave_users)

    return _sort_users(await remnawave.get_users_by_telegram_id(telegram_id))


async def _load_hosts(remnawave: RemnawaveClient) -> list[dict[str, Any]]:
    try:
        return await remnawave.get_hosts()
    except RemnawaveApiError:
        logger.warning("failed to load Remnawave hosts, using default host list", exc_info=True)
        return []


def _wdtt_access_for_user(order_store: OrderStore, user: dict[str, Any]) -> dict[str, object] | None:
    access = user.get("_wdtt_access")
    if isinstance(access, dict):
        return access
    return None


async def _wdtt_devices_for_user(
    settings: Settings,
    order_store: OrderStore,
    user: dict[str, Any],
) -> list[dict[str, Any]]:
    access = _wdtt_access_for_user(order_store, user)
    if access is None:
        raise RuntimeError("WDTT access not found")
    payload = await wdtt.remote_devices(settings, access)
    try:
        user["hwidDeviceLimit"] = int(payload.get("max_devices") or user.get("hwidDeviceLimit") or 0)
    except (TypeError, ValueError):
        pass
    devices = payload.get("devices")
    return [item for item in devices if isinstance(item, dict)] if isinstance(devices, list) else []


def _is_wdtt_user(user: dict[str, Any]) -> bool:
    return isinstance(user.get("_wdtt_access"), dict)


def _renewable_users(users: list[dict[str, Any]]) -> list[tuple[int, dict[str, Any]]]:
    return [
        (index, user)
        for index, user in enumerate(users)
        if not _is_wdtt_user(user) and status_label(user) == "активна"
    ]


def _is_remnawave_uuid(value: str) -> bool:
    return bool(REMNAWAVE_UUID_RE.fullmatch(value) or re.fullmatch(r"[0-9]+", value))


def _happ_import_url(subscription_url: str) -> str | None:
    return happ_import_url(subscription_url)


def _slots_enabled(settings: Settings) -> bool:
    slots = settings.data.get("slots") if isinstance(settings.data.get("slots"), dict) else {}
    return bool(slots.get("enabled", True))


def _mobile_traffic_enabled_for_user(settings: Settings, user: dict[str, Any]) -> bool:
    return (
        mobile_traffic_enabled(settings.data)
        and not _is_wdtt_user(user)
        and status_label(user) == "активна"
        and plan_name(user) in {MOBILE_PLAN, MULTI_PLAN}
        and _traffic_limit(user) > 0
    )


def _subscription_keyboard(settings: Settings, index: int, user: dict[str, Any]):
    return subscription_keyboard(
        index,
        str(user.get("subscriptionUrl") or ""),
        user_uuid=str(user.get("uuid") or "") or None,
        is_wdtt=_is_wdtt_user(user),
        slots_enabled=_slots_enabled(settings),
        traffic_enabled=_mobile_traffic_enabled_for_user(settings, user),
    )


def _get_user(users: list[dict[str, Any]], index: int) -> dict[str, Any] | None:
    if 0 <= index < len(users):
        return users[index]
    return users[0] if users else None


def _tariffs(settings: Settings) -> list[dict[str, Any]]:
    tariffs = settings.data.get("tariffs")
    return [tariff for tariff in tariffs if isinstance(tariff, dict)] if isinstance(tariffs, list) else []


def _periods(settings: Settings) -> list[dict[str, Any]]:
    periods = settings.data.get("purchase_periods")
    valid = [period for period in periods if isinstance(period, dict)] if isinstance(periods, list) else []
    return valid or [{"months": 1, "title": "1 месяц"}]


def _purchase_period_months(tariff: dict[str, Any]) -> set[int] | None:
    raw_months = tariff.get("purchase_period_months")
    if not isinstance(raw_months, list) or not raw_months:
        return None

    months: set[int] = set()
    for raw_month in raw_months:
        try:
            month = int(raw_month)
        except (TypeError, ValueError):
            continue
        if month > 0:
            months.add(month)
    return months or None


def _trial_allows_extended_purchase(order_store: OrderStore, telegram_id: int) -> bool:
    trial = order_store.get_trial_access(telegram_id)
    return bool(
        trial
        and str(trial.get("status") or "") == "completed"
        and trial.get("first_traffic_at")
    )


def _purchase_periods(
    settings: Settings,
    tariff: dict[str, Any],
    *,
    allow_extended: bool = False,
) -> list[dict[str, Any]]:
    periods = _periods(settings)
    if allow_extended:
        return periods
    allowed_months = _purchase_period_months(tariff)
    if allowed_months is None:
        return periods

    filtered = [period for period in periods if period_months(period) in allowed_months]
    return filtered or periods[:1]


def _purchase_period_allowed(
    tariff: dict[str, Any],
    months: int,
    *,
    allow_extended: bool = False,
) -> bool:
    if allow_extended:
        return True
    allowed_months = _purchase_period_months(tariff)
    return allowed_months is None or months in allowed_months


def _find_tariff(settings: Settings, tariff_id: str) -> dict[str, Any] | None:
    for tariff in _tariffs(settings):
        if str(tariff.get("id") or "") == tariff_id:
            return tariff
    return None


def _tariff_purchase_enabled(tariff: dict[str, Any]) -> bool:
    return bool(tariff.get("purchase_enabled", True))


def _tariff_purchase_unavailable_text(tariff: dict[str, Any]) -> str:
    return str(tariff.get("purchase_unavailable_text") or "Тариф временно недоступен для покупки.")


def _find_period(settings: Settings, months: int) -> dict[str, Any] | None:
    for period in _periods(settings):
        if period_months(period) == months:
            return period
    return None


def _tariff_id_for_user(settings: Settings, user: dict[str, Any]) -> str:
    return subscription_tariff_id_for_user(settings.data, user)


def _device_limit(user: dict[str, Any]) -> int:
    try:
        return int(user.get("hwidDeviceLimit") or 0)
    except (TypeError, ValueError):
        return 0


def _traffic_limit(user: dict[str, Any]) -> int:
    try:
        return int(user.get("trafficLimitBytes") or 0)
    except (TypeError, ValueError):
        return 0


async def _device_count(remnawave: RemnawaveClient, user: dict[str, Any]) -> int | None:
    user_uuid = str(user.get("uuid") or "")
    if not user_uuid:
        return None
    try:
        return len(await remnawave.get_user_devices(user_uuid))
    except RemnawaveApiError:
        logger.warning("failed to load device count", exc_info=True)
        return None


async def _multi_devices_for_user(
    remnawave: RemnawaveClient,
    user: dict[str, Any],
) -> list[dict[str, Any]]:
    record = user.get("_multi_subscription")
    if not isinstance(record, dict):
        return await remnawave.get_user_devices(str(user.get("uuid") or ""))
    devices: list[dict[str, Any]] = []
    for component, key in (("regular", "primary_user_uuid"), ("mobile", "mobile_user_uuid")):
        reference = str(record.get(key) or "")
        if not reference:
            continue
        for raw_device in await remnawave.get_user_devices(reference):
            device = dict(raw_device)
            device["_multi_component"] = component
            device["_multi_user_uuid"] = reference
            devices.append(device)
    user["_multi_devices"] = devices
    return devices


def _payment_payload(order_id: int, telegram_id: int) -> str:
    return payment_payload(order_id, telegram_id)


def _parse_payment_payload(payload: str) -> tuple[int, int] | None:
    return parse_payment_payload(payload)


def _slot_method(settings: Settings, method_id: str) -> dict[str, Any] | None:
    slots = settings.data.get("slots") if isinstance(settings.data.get("slots"), dict) else {}
    methods = slots.get("methods") if isinstance(slots.get("methods"), list) else []
    for method in methods:
        if isinstance(method, dict) and str(method.get("id") or "") == method_id:
            return method
    return None


def _traffic_method(settings: Settings, method_id: str) -> dict[str, Any] | None:
    traffic = mobile_traffic_config(settings.data)
    methods = traffic.get("methods") if isinstance(traffic.get("methods"), list) else []
    for method in methods:
        if isinstance(method, dict) and str(method.get("id") or "") == method_id:
            return method
    return None


def _referral_discount(
    *,
    settings: Settings,
    order_store: OrderStore,
    telegram_id: int,
    total_rub: int,
    monthly_price_rub: int,
) -> tuple[int, int, int | None]:
    referrals = settings.data.get("referrals") if isinstance(settings.data.get("referrals"), dict) else {}
    if not referrals.get("enabled", True):
        return 0, 0, None
    try:
        configured_percent = int(referrals.get("discount_percent") or 20)
    except (TypeError, ValueError):
        configured_percent = 20
    discount_percent, referrer_telegram_id = order_store.referral_discount(telegram_id, configured_percent)
    discount_rub = referral_discount_amount(total_rub, discount_percent)
    return discount_percent, discount_rub, referrer_telegram_id


def _net_period_total(tariff: dict[str, Any], period: dict[str, Any], discount_rub: int = 0) -> int:
    return max(1, period_total(tariff, period) - max(0, discount_rub))


def _platega_method(settings: Settings, method_id: str) -> tuple[dict[str, Any], int] | None:
    method = payment_method(settings.data, method_id)
    if method is None:
        return None
    try:
        method_code = int(method.get("platega_method") or 0)
    except (TypeError, ValueError):
        method_code = 0
    return (method, method_code) if method_code > 0 else None


def _platega_url(settings: Settings, key: str, default: str) -> str:
    platega = settings.data.get("platega") if isinstance(settings.data.get("platega"), dict) else {}
    return str(platega.get(key) or default)


def _platega_transaction_data(payload: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return {}
    for key in ("transaction", "data", "result"):
        value = payload.get(key)
        if isinstance(value, dict):
            return value
    return payload


def _platega_transaction_status(transaction: dict[str, Any]) -> str:
    return str(transaction.get("status") or transaction.get("paymentStatus") or "").upper()


def _platega_transaction_id(transaction: dict[str, Any], fallback: str) -> str:
    return str(transaction.get("id") or transaction.get("transactionId") or fallback)


def _platega_amount_matches(transaction: dict[str, Any], order: dict[str, object]) -> bool:
    expected = int(order.get("pay_amount_rub") or order.get("price_rub") or 0)
    details = transaction.get("paymentDetails") if isinstance(transaction.get("paymentDetails"), dict) else {}
    raw_amount = transaction.get("amount") if transaction.get("amount") is not None else details.get("amount")
    raw_currency = transaction.get("currency") if transaction.get("currency") is not None else details.get("currency")
    try:
        actual = int(round(float(raw_amount or 0)))
    except (TypeError, ValueError):
        return False
    return expected > 0 and actual >= expected and str(raw_currency or "").upper() == "RUB"


def _referrer_from_start_args(args: str | None) -> int | None:
    if not args:
        return None
    value = args.strip()
    if not value.startswith("ref_"):
        return None
    try:
        referrer_id = int(value.removeprefix("ref_"))
    except ValueError:
        return None
    return referrer_id if referrer_id > 0 else None


def _traffic_source_from_start_args(args: str | None, order_store: OrderStore) -> str | None:
    value = (args or "").strip()
    if not value.startswith("src_"):
        return None
    code = value.removeprefix("src_").strip().lower()
    if not re.fullmatch(r"[a-z0-9]{4,32}", code):
        return None
    source = order_store.get_traffic_source(code)
    if source is None:
        return None
    try:
        is_active = int(source.get("is_active") or 0) == 1
    except (TypeError, ValueError):
        is_active = False
    return code if is_active else None


def _cabinet_verification_token(args: str | None) -> str | None:
    value = (args or "").strip()
    if not value.startswith("web_"):
        return None
    token = value.removeprefix("web_")
    if not re.fullmatch(r"[A-Za-z0-9_-]{20,96}", token):
        return None
    return token


async def _edit_or_send(message: Message, text: str, **kwargs: Any) -> None:
    try:
        await message.edit_text(text, **kwargs)
    except TelegramBadRequest:
        await message.answer(text, **kwargs)


async def _show_no_access(target: Message | CallbackQuery, settings: Settings) -> None:
    text = no_access_text(_telegram_id(target), str(settings.data.get("brand") or "Levik VPN"))
    support = settings.data.get("support") if isinstance(settings.data.get("support"), dict) else {}
    markup = no_access_keyboard(support_enabled=bool(support.get("enabled")), support_url=_support_url(settings))
    if isinstance(target, CallbackQuery):
        await target.answer()
        if target.message:
            await _edit_or_send(target.message, text, reply_markup=markup)
    else:
        await target.answer(text, reply_markup=markup)


def _find_user_index_by_uuid(users: list[dict[str, Any]], user_uuid: str) -> int | None:
    for index, user in enumerate(users):
        if str(user.get("uuid") or "") == user_uuid:
            return index
    return None


def _local_today(settings: Settings) -> str:
    timezone_name = str(settings.data.get("timezone") or "Europe/Moscow")
    try:
        tz = ZoneInfo(timezone_name)
    except ZoneInfoNotFoundError:
        tz = timezone.utc
    return datetime.now(timezone.utc).astimezone(tz).date().isoformat()


async def _show_renewal_options(
    callback: CallbackQuery,
    settings: Settings,
    user: dict[str, Any],
    index: int,
) -> None:
    if _is_wdtt_user(user):
        await callback.answer(WDTT_LEGACY_PAYMENT_MESSAGE, show_alert=True)
        return
    tariff_id = _tariff_id_for_user(settings, user)
    tariff = _find_tariff(settings, tariff_id)
    if tariff is None:
        await callback.answer("Тариф для продления не найден.", show_alert=True)
        return
    await callback.answer()
    if callback.message:
        await _edit_or_send(
            callback.message,
            purchase_period_text(tariff, kind="renewal", user=user, config=settings.data),
            reply_markup=renewal_periods_keyboard(index, tariff, _periods(settings)),
        )


async def _show_subscription_details(
    message: Message,
    *,
    settings: Settings,
    remnawave: RemnawaveClient,
    user: dict[str, Any],
    index: int,
    edit: bool = False,
) -> None:
    hosts = await _load_hosts(remnawave)
    if _is_wdtt_user(user):
        used_devices = None
    elif plan_name(user) == MULTI_PLAN:
        try:
            used_devices = len(await _multi_devices_for_user(remnawave, user))
        except RemnawaveApiError:
            logger.warning("failed to load multi device count", exc_info=True)
            used_devices = None
    else:
        used_devices = await _device_count(remnawave, user)
    text = subscription_text(user, hosts, str(settings.data.get("timezone") or "Europe/Moscow"), used_devices)
    markup = _subscription_keyboard(settings, index, user)
    if edit:
        await _edit_or_send(message, text, reply_markup=markup)
    else:
        await message.answer(text, reply_markup=markup)


async def _show_home(
    message: Message,
    *,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    first_name: str,
    telegram_id: int | None = None,
    edit: bool = False,
) -> None:
    resolved_telegram_id = telegram_id if telegram_id is not None else _telegram_id(message)
    users = await _load_users(remnawave, resolved_telegram_id, order_store)
    trial_settings = trial_config(settings)
    trial = order_store.get_trial_access(resolved_telegram_id)
    trial_is_available = bool(trial_settings["enabled"]) and trial_available(
        users,
        trial,
        resolved_telegram_id,
    )
    if not users:
        text = no_access_text(
            resolved_telegram_id,
            str(settings.data.get("brand") or "Levik VPN"),
            first_name,
        )
        support = settings.data.get("support") if isinstance(settings.data.get("support"), dict) else {}
        markup = no_access_keyboard(
            support_enabled=bool(support.get("enabled")),
            support_url=_support_url(settings),
            trial_available=trial_is_available,
        )
        if edit:
            await _edit_or_send(message, text, reply_markup=markup)
        else:
            await message.answer(text, reply_markup=markup)
        return

    hosts = await _load_hosts(remnawave)
    text = account_text(
        users,
        hosts,
        first_name,
        str(settings.data.get("brand") or "Levik VPN"),
        str(settings.data.get("timezone") or "Europe/Moscow"),
    )
    support = settings.data.get("support") if isinstance(settings.data.get("support"), dict) else {}
    markup = home_keyboard(
        support_enabled=bool(support.get("enabled")),
        support_url=_support_url(settings),
        trial_available=trial_is_available,
        renewal_available=bool(_renewable_users(users)),
    )
    if edit:
        await _edit_or_send(message, text, reply_markup=markup)
    else:
        banner = Path(settings.banner_path)
        if banner.exists():
            await message.answer_photo(FSInputFile(banner))
        await message.answer(text, reply_markup=markup)


def _is_admin_id(telegram_id: int) -> bool:
    return telegram_id == ADMIN_TELEGRAM_ID


def _admin_menu_text(settings: Settings) -> str:
    tariff_ids = ", ".join(str(tariff.get("id")) for tariff in _tariffs(settings) if tariff.get("id"))
    return (
        "🛠️ <b>Админ-панель</b>\n\n"
        f"Доступные тарифы: <code>{esc(tariff_ids or 'regular')}</code>\n\n"
        "Выдача доступа:\n"
        "<code>/admin give 123456789 regular 1</code>\n"
        "<code>/admin give @username solo 1</code>\n"
        "<code>/admin give @username plus 1</code>\n\n"
        "Ссылки-источники (откуда пришли пользователи):\n"
        "<code>/admin links</code> — список ссылок со статистикой\n"
        "<code>/admin link create Группа А</code> — создать ссылку\n"
        "<code>/admin link stats ab12cd34</code> — воронка по источнику\n"
        "<code>/admin link off ab12cd34</code> / <code>on</code> — выключить/включить\n\n"
        "Мобильные тарифы: Solo — 1 устройство и 50 ГБ, Plus — 2 устройства и 80 ГБ.\n"
        "Докупки в боте: +1 слот и +10 ГБ — 69 ₽, +20 ГБ — 59 ₽.\n\n"
        "Username сработает, если пользователь уже открывал бота или оплачивал доступ."
    )


def _admin_help_text(settings: Settings) -> str:
    return _admin_menu_text(settings)


def _normalize_admin_tariff_id(settings: Settings, value: str) -> str:
    normalized = value.strip().lower()
    aliases = {
        "обычный": "regular",
        "regular": "regular",
        "base": "regular",
        "мобильный": "lte",
        "mobile": "lte",
        "wdtt": "lte",
        "lte": "lte",
        "plus": "lte",
        "lte_plus": "lte",
        "мобильный+": "lte",
        "мобильный_plus": "lte",
        "solo": "lte_solo",
        "lte_solo": "lte_solo",
        "мобильный_solo": "lte_solo",
        "мобильный соло": "lte_solo",
    }
    candidate = aliases.get(normalized, normalized)
    available = {str(tariff.get("id") or "") for tariff in _tariffs(settings)}
    return candidate if candidate in available else ""


def _username_from_identifier(identifier: str) -> str | None:
    value = identifier.strip()
    if not value.startswith("@"):
        return None
    username = value[1:].strip()
    return username[:64] if username else None


def _is_active_wdtt_access(access: dict[str, object]) -> bool:
    try:
        expires_at = int(access.get("expires_at") or 0)
    except (TypeError, ValueError):
        expires_at = 0
    return expires_at <= 0 or expires_at > int(datetime.now(timezone.utc).timestamp())


def _admin_order_line(order: dict[str, object], timezone_name: str) -> str:
    username = str(order.get("telegram_username") or "").strip()
    user_label = f"@{username}" if username else f"id {order.get('telegram_id') or '—'}"
    tariff = str(order.get("tariff_title") or order.get("tariff_id") or "доступ")
    try:
        months = int(order.get("period_months") or 0)
    except (TypeError, ValueError):
        months = 0
    period = f"{months} мес." if months > 0 else "—"
    price = rub(int(order.get("price_rub") or 0))
    created = format_date(order.get("created_at"), timezone_name)
    return (
        f"#{order.get('id')} · {esc(created)} · {esc(user_label)} · "
        f"{esc(tariff)} · {esc(period)} · {esc(order.get('status') or '—')} · {esc(price)}"
    )


async def _admin_stats_text(
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> str:
    timezone_name = str(settings.data.get("timezone") or "Europe/Moscow")
    remnawave_users: list[dict[str, Any]] = []
    api_warning = ""
    try:
        remnawave_users = await remnawave.get_users()
    except RemnawaveApiError:
        logger.exception("failed to load admin VPN stats")
        api_warning = "\n\n⚠️ VPN-сервер сейчас не отдал список пользователей, показана локальная часть статистики."

    wdtt_accesses = order_store.get_active_wdtt_accesses()
    known_ids = order_store.known_telegram_ids()
    for user in remnawave_users:
        try:
            telegram_id = int(user.get("telegramId") or 0)
        except (TypeError, ValueError):
            telegram_id = 0
        if telegram_id > 0:
            known_ids.add(telegram_id)
    for access in wdtt_accesses:
        try:
            telegram_id = int(access.get("telegram_id") or 0)
        except (TypeError, ValueError):
            telegram_id = 0
        if telegram_id > 0:
            known_ids.add(telegram_id)

    active_remnawave = sum(1 for user in remnawave_users if status_label(user) == "активна")
    active_wdtt = sum(1 for access in wdtt_accesses if _is_active_wdtt_access(access))
    mobile_remnawave = sum(1 for user in remnawave_users if plan_name(user) == MOBILE_PLAN)
    mobile_solo = sum(1 for user in remnawave_users if _tariff_id_for_user(settings, user) == "lte_solo")
    mobile_plus = sum(1 for user in remnawave_users if _tariff_id_for_user(settings, user) == "lte")
    regular_remnawave = max(0, len(remnawave_users) - mobile_remnawave)
    activity = order_store.activity_stats()
    orders = order_store.order_stats()
    funnel = order_store.funnel_stats()

    return (
        "📊 <b>Статистика Levik VPN</b>\n\n"
        f"Telegram-пользователей всего: <b>{len(known_ids)}</b>\n"
        f"Бот видел напрямую: <b>{activity.get('tracked_users', 0)}</b>\n\n"
        f"Запускали /start: <b>{activity.get('started_users', 0)}</b>\n"
        f"Всего команд /start: <b>{activity.get('start_events', 0)}</b>\n\n"
        f"Подписок всего: <b>{len(remnawave_users) + len(wdtt_accesses)}</b>\n"
        f"Активных подписок: <b>{active_remnawave + active_wdtt}</b>\n"
        f"Обычный VPN: <b>{regular_remnawave}</b>\n"
        f"Мобильный VPN: <b>{mobile_remnawave + len(wdtt_accesses)}</b>\n"
        f"Solo: <b>{mobile_solo}</b>\n"
        f"Plus: <b>{mobile_plus}</b>\n"
        f"Старые мобильные ключи: <b>{len(wdtt_accesses)}</b>\n\n"
        "Активность в боте:\n"
        f"за 24 часа: <b>{activity.get('active_24h', 0)}</b>\n"
        f"за 7 дней: <b>{activity.get('active_7d', 0)}</b>\n"
        f"за 30 дней: <b>{activity.get('active_30d', 0)}</b>\n\n"
        "Воронка (с момента обновления):\n"
        f"выбрали сценарий: <b>{funnel.get('scenario_selected', 0)}</b>\n"
        f"запросили пробник: <b>{funnel.get('trial_requested', 0)}</b>\n"
        f"получили пробник всего: <b>{funnel.get('trials_completed', 0)}</b>\n"
        f"передали первый трафик: <b>{funnel.get('first_traffic', 0)}</b>\n"
        f"начали оплату: <b>{funnel.get('checkout_started', 0)}</b>\n"
        f"онлайн-плательщиков: <b>{funnel.get('online_paid_users', 0)}</b>\n"
        f"рефералов: <b>{funnel.get('referrals_created', 0)}</b>, оплатили: <b>{funnel.get('referrals_converted', 0)}</b>\n\n"
        "Операции:\n"
        f"выдано всего: <b>{orders.get('delivered', 0)}</b>\n"
        f"выдано за 30 дней: <b>{orders.get('delivered_30d', 0)}</b>\n"
        f"админ-выдач: <b>{orders.get('admin_grants', 0)}</b>\n"
        f"оплачено всего: <b>{esc(rub(orders.get('revenue_rub', 0)))}</b>\n"
        f"оплачено за 30 дней: <b>{esc(rub(orders.get('revenue_30d_rub', 0)))}</b>"
        f"{api_warning}"
    )


def _admin_recent_text(settings: Settings, order_store: OrderStore) -> str:
    timezone_name = str(settings.data.get("timezone") or "Europe/Moscow")
    orders = order_store.recent_orders(7)
    if not orders:
        return "🧾 <b>Последние операции</b>\n\nОпераций пока нет."
    lines = ["🧾 <b>Последние операции</b>", ""]
    lines.extend(_admin_order_line(order, timezone_name) for order in orders)
    return "\n".join(lines)


async def _send_admin_menu(message: Message, settings: Settings) -> None:
    await message.answer(_admin_menu_text(settings), reply_markup=admin_keyboard())


async def _handle_admin_command(
    message: Message,
    args: str,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    parts = args.split()
    action = parts[0].lower() if parts else "help"
    if action in {"help", "помощь"}:
        await message.answer(_admin_help_text(settings), reply_markup=admin_keyboard())
        return
    if action in {"stats", "stat", "статистика"}:
        await message.answer(await _admin_stats_text(settings, remnawave, order_store), reply_markup=admin_keyboard())
        return
    if action in {"recent", "orders", "последние"}:
        await message.answer(_admin_recent_text(settings, order_store), reply_markup=admin_keyboard())
        return
    if action in {"links", "link", "источники", "источник"}:
        await _handle_admin_links_command(message, parts, settings, order_store)
        return
    if action in {"give", "grant", "выдать"}:
        if len(parts) != 4:
            await message.answer(_admin_help_text(settings), reply_markup=admin_keyboard())
            return
        await _admin_grant_access(
            message=message,
            settings=settings,
            remnawave=remnawave,
            order_store=order_store,
            target_identifier=parts[1],
            tariff_value=parts[2],
            months_value=parts[3],
        )
        return
    await message.answer(_admin_help_text(settings), reply_markup=admin_keyboard())


def _bot_username(settings: Settings) -> str:
    referrals = settings.data.get("referrals") if isinstance(settings.data.get("referrals"), dict) else {}
    return str(referrals.get("bot_username") or "levikvpnbot").strip().lstrip("@")


def _traffic_source_link(settings: Settings, code: str) -> str:
    return f"https://t.me/{_bot_username(settings)}?start=src_{code}"


async def _handle_admin_links_command(
    message: Message,
    parts: list[str],
    settings: Settings,
    order_store: OrderStore,
) -> None:
    sub = parts[1].lower() if len(parts) > 1 else ""
    if sub in {"create", "создать", "new"}:
        label = " ".join(parts[2:]).strip()
        if not label:
            await message.answer(
                "Укажите название ссылки, например: <code>/admin link create Группа А</code>",
                reply_markup=admin_keyboard(),
            )
            return
        source = order_store.create_traffic_source(label=label)
        if source is None:
            await message.answer("Не удалось создать ссылку, попробуйте ещё раз.", reply_markup=admin_keyboard())
            return
        code = str(source.get("code") or "")
        await message.answer(
            "🔗 <b>Ссылка-источник создана</b>\n\n"
            f"Название: <b>{esc(label)}</b>\n"
            f"Код: <code>{esc(code)}</code>\n\n"
            f"{_traffic_source_link(settings, code)}\n\n"
            "Все, кто перейдёт по ней и нажмёт «Начать», будут числиться за этим источником.\n"
            f"Статистика: <code>/admin link stats {esc(code)}</code>",
            reply_markup=admin_keyboard(),
        )
        return
    if sub in {"stats", "стата", "статистика"}:
        code = parts[2].strip().lower() if len(parts) > 2 else ""
        if not code:
            await message.answer(
                "Укажите код ссылки: <code>/admin link stats ab12cd34</code>",
                reply_markup=admin_keyboard(),
            )
            return
        text = _admin_link_stats_text(settings, order_store, code)
        await message.answer(
            text if text is not None else f"Источник <code>{esc(code)}</code> не найден.",
            reply_markup=admin_keyboard(),
        )
        return
    if sub in {"off", "disable", "выкл"}:
        code = parts[2].strip().lower() if len(parts) > 2 else ""
        source = order_store.set_traffic_source_active(code, active=False) if code else None
        await message.answer(
            f"⛔ Ссылка <code>{esc(code)}</code> выключена: новые переходы по ней не засчитываются."
            if source is not None
            else f"Источник <code>{esc(code or '—')}</code> не найден.",
            reply_markup=admin_keyboard(),
        )
        return
    if sub in {"on", "enable", "вкл"}:
        code = parts[2].strip().lower() if len(parts) > 2 else ""
        source = order_store.set_traffic_source_active(code, active=True) if code else None
        await message.answer(
            f"✅ Ссылка <code>{esc(code)}</code> снова активна."
            if source is not None
            else f"Источник <code>{esc(code or '—')}</code> не найден.",
            reply_markup=admin_keyboard(),
        )
        return
    await message.answer(
        _admin_links_text(settings, order_store),
        reply_markup=admin_keyboard(),
    )


def _admin_links_text(settings: Settings, order_store: OrderStore) -> str:
    sources = order_store.traffic_source_overview()
    if not sources:
        return (
            "🔗 <b>Источники трафика</b>\n\n"
            "Ссылок пока нет. Создайте первую:\n"
            "<code>/admin link create Группа А</code>\n\n"
            "Каждому, кто перейдёт по ссылке и нажмёт «Начать», автоматически "
            "запишется источник — потом видно, кто откуда пришёл и кто оплатил."
        )
    lines = [
        "🔗 <b>Источники трафика</b>\n",
        f"Создать: <code>/admin link create &lt;название&gt;</code>\n",
    ]
    for source in sources:
        code = str(source.get("code") or "")
        try:
            active = int(source.get("is_active") or 0) == 1
        except (TypeError, ValueError):
            active = False
        status = "" if active else " · ⛔ выключена"
        lines.append(
            f"<b>{esc(source.get('label') or code)}</b> · <code>{esc(code)}</code>{status}\n"
            f"зашло {int(source.get('users_total') or 0)} · "
            f"пробник {int(source.get('trial_users') or 0)} · "
            f"оплатило {int(source.get('paid_users') or 0)} · "
            f"выручка {rub(int(source.get('revenue_rub') or 0))}"
        )
    lines.append(
        "\nПодробная воронка: <code>/admin link stats &lt;код&gt;</code>\n"
        "Выключить/включить: <code>/admin link off|on &lt;код&gt;</code>"
    )
    return "\n".join(lines)


def _admin_link_stats_text(settings: Settings, order_store: OrderStore, code: str) -> str | None:
    stats = order_store.traffic_source_stats(code)
    if stats is None:
        return None
    source = dict(stats.get("source") or {})
    try:
        active = int(source.get("is_active") or 0) == 1
    except (TypeError, ValueError):
        active = False
    events = dict(stats.get("events") or {})
    status = "активна" if active else "⛔ выключена"
    return (
        f"🔗 <b>{esc(source.get('label') or code)}</b> · <code>{esc(code)}</code> · {status}\n\n"
        f"{_traffic_source_link(settings, code)}\n\n"
        f"Зашли по ссылке: <b>{int(stats.get('users_total') or 0)}</b>\n"
        f"Активны за 7 дней: <b>{int(stats.get('active_7d') or 0)}</b>\n"
        f"Выбрали сценарий: <b>{int(events.get('scenario_selected') or 0)}</b>\n"
        f"Запросили пробник: <b>{int(events.get('trial_requested') or 0)}</b>\n"
        f"Получили пробник: <b>{int(events.get('trial_created') or 0)}</b>\n"
        f"Передали первый трафик: <b>{int(events.get('first_traffic') or 0)}</b>\n"
        f"Начали оплату: <b>{int(events.get('checkout_started') or 0)}</b>\n\n"
        "Операции:\n"
        f"оплат: <b>{int(stats.get('orders_total') or 0)}</b>\n"
        f"плательщиков: <b>{int(stats.get('paid_users') or 0)}</b>\n"
        f"выручка: <b>{esc(rub(int(stats.get('revenue_rub') or 0)))}</b>"
    )


async def _admin_grant_access(
    *,
    message: Message,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    target_identifier: str,
    tariff_value: str,
    months_value: str,
) -> None:
    target_telegram_id = order_store.resolve_telegram_id(target_identifier)
    if target_telegram_id is None:
        await message.answer(
            "Не нашёл пользователя по username. Укажите Telegram ID или username пользователя, который уже открывал бота.",
            reply_markup=admin_keyboard(),
        )
        return

    tariff_id = _normalize_admin_tariff_id(settings, tariff_value)
    tariff = _find_tariff(settings, tariff_id) if tariff_id else None
    if tariff is None:
        await message.answer("Тариф не найден. Проверьте id тарифа в /admin.", reply_markup=admin_keyboard())
        return

    try:
        months = int(months_value)
    except ValueError:
        months = 0
    if months < 1 or months > 36:
        await message.answer("Срок должен быть числом от 1 до 36 месяцев.", reply_markup=admin_keyboard())
        return

    order = order_store.create_admin_access_grant(
        telegram_id=target_telegram_id,
        telegram_username=_username_from_identifier(target_identifier),
        tariff_id=tariff_id,
        tariff_title=str(tariff.get("title") or tariff_id),
        period_months=months,
    )
    stored_order = order_store.get(order.id)
    if stored_order is None:
        await message.answer("Не удалось создать админ-выдачу.", reply_markup=admin_keyboard())
        return

    result = await deliver_paid_order(
        telegram_id=target_telegram_id,
        settings=settings,
        remnawave=remnawave,
        order_store=order_store,
        order=stored_order,
    )
    if not result.success:
        await message.answer(
            f"Оплата не нужна, но доступ выдать не удалось. Заказ: <code>#{order.id}</code>.",
            reply_markup=admin_keyboard(),
        )
        return

    user_notified = False
    if result.user_text:
        try:
            await message.bot.send_message(
                target_telegram_id,
                result.user_text,
                reply_markup=access_success_keyboard(
                    result.user_uuid,
                    subscription_url=result.subscription_url,
                    offer_happ_routing=result.offer_happ_routing,
                ),
            )
            user_notified = True
        except TelegramAPIError:
            logger.warning("failed to notify admin-granted user telegram_id=%s", target_telegram_id, exc_info=True)

    notify_text = "пользователь получил сообщение" if user_notified else "ключ создан, но пользователю не удалось отправить сообщение"
    await message.answer(
        "✅ <b>Доступ выдан</b>\n\n"
        f"Пользователь: <code>{target_telegram_id}</code>\n"
        f"Тариф: <b>{esc(tariff.get('title') or tariff_id)}</b>\n"
        f"Срок: <b>{months} мес.</b>\n"
        f"Заказ: <code>#{order.id}</code>\n"
        f"Статус: {esc(notify_text)}.",
        reply_markup=admin_keyboard(),
    )


@router.message(Command("admin"))
async def cmd_admin(
    message: Message,
    command: CommandObject,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    if not _is_admin_id(_telegram_id(message)):
        return
    args = (command.args or "").strip()
    if args:
        await _handle_admin_command(message, args, settings, remnawave, order_store)
        return
    await _send_admin_menu(message, settings)


@router.message(CommandStart())
async def cmd_start(
    message: Message,
    command: CommandObject,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    try:
        source_code = _traffic_source_from_start_args(command.args, order_store)
        order_store.record_user_start(
            telegram_id=_telegram_id(message),
            username=_telegram_username(message),
            first_name=_first_name(message),
            source_code=source_code,
        )
        cabinet_token = _cabinet_verification_token(command.args)
        if cabinet_token is not None:
            challenge = order_store.bind_cabinet_device_challenge(
                verification_token_hash=opaque_token_hash(cabinet_token),
                telegram_id=_telegram_id(message),
            )
            if challenge is None:
                await message.answer(
                    "Ссылка для входа недействительна или уже истекла. "
                    "Вернитесь на сайт и запросите новую."
                )
                return
            order_store.record_event(
                telegram_id=_telegram_id(message),
                event_name="cabinet_auth_opened",
            )
            await message.answer(
                "🔐 <b>Вход в личный кабинет Levik VPN</b>\n\n"
                f"Код на сайте: <code>{esc(challenge.get('user_code') or '')}</code>\n\n"
                "Подтвердите вход только если такой же код сейчас показан на вашем устройстве. "
                "Мы никогда не просим пересылать этот код другому человеку.",
                reply_markup=cabinet_auth_confirm_keyboard(
                    str(challenge.get("challenge_id") or "")
                ),
            )
            return
        referrer_id = _referrer_from_start_args(command.args)
        start_source = f"src:{source_code}" if source_code else ("referral" if referrer_id is not None else "direct")
        order_store.record_event(
            telegram_id=_telegram_id(message),
            event_name="start",
            properties={"source": start_source},
        )
        if referrer_id is not None:
            registered = order_store.register_referral(
                invitee_telegram_id=_telegram_id(message),
                referrer_telegram_id=referrer_id,
            )
            if registered:
                order_store.record_event(telegram_id=_telegram_id(message), event_name="referral_registered")
                await message.answer(
                    "🎁 <b>Вы пришли по приглашению</b>\n\n"
                    "Получите пробный доступ без карты. При первой покупке скидка 20% применяется ко всей сумме заказа."
                )
        await _show_home(message, settings=settings, remnawave=remnawave, order_store=order_store, first_name=_first_name(message))
    except RemnawaveApiError:
        logger.exception("failed to load start screen")
        await message.answer("Сервис временно недоступен. Попробуйте позже.")


@router.callback_query(F.data.startswith("cabinet_auth:"))
async def cb_cabinet_auth(callback: CallbackQuery, order_store: OrderStore) -> None:
    parts = str(callback.data or "").split(":", 2)
    if len(parts) != 3 or parts[1] not in {"confirm", "deny"}:
        await callback.answer("Запрос недействителен.", show_alert=True)
        return
    action, challenge_id = parts[1], parts[2]
    if not re.fullmatch(r"[0-9a-f]{32}", challenge_id):
        await callback.answer("Запрос недействителен.", show_alert=True)
        return
    telegram_id = _telegram_id(callback)
    if action == "confirm":
        changed = order_store.confirm_cabinet_device_challenge(
            challenge_id=challenge_id,
            telegram_id=telegram_id,
        )
        text = (
            "✅ <b>Вход подтверждён</b>\n\n"
            "Вернитесь на сайт — личный кабинет откроется автоматически."
        )
        event_name = "cabinet_auth_confirmed"
    else:
        changed = order_store.deny_cabinet_device_challenge(
            challenge_id=challenge_id,
            telegram_id=telegram_id,
        )
        text = (
            "❌ <b>Вход отклонён</b>\n\n"
            "Если это были не вы, никаких дополнительных действий не требуется."
        )
        event_name = "cabinet_auth_denied"
    if not changed:
        await callback.answer(
            "Запрос уже обработан или истёк. Запросите новый вход на сайте.",
            show_alert=True,
        )
        return
    order_store.record_event(telegram_id=telegram_id, event_name=event_name)
    await callback.answer()
    if callback.message:
        await _edit_or_send(callback.message, text)


@router.message(Command("profile"))
async def cmd_profile(message: Message, settings: Settings, remnawave: RemnawaveClient, order_store: OrderStore) -> None:
    await show_profile_message(message, settings, remnawave, order_store, 0)


@router.message(Command("keys"))
async def cmd_keys(message: Message, settings: Settings, remnawave: RemnawaveClient, order_store: OrderStore) -> None:
    await show_keys_message(message, settings, remnawave, order_store)


@router.message(Command("devices"))
async def cmd_devices(message: Message, settings: Settings, remnawave: RemnawaveClient, order_store: OrderStore) -> None:
    await show_devices_message(message, settings, remnawave, order_store, 0)


@router.message(Command("tariffs"))
async def cmd_tariffs(message: Message, settings: Settings) -> None:
    await message.answer(tariffs_text(settings.data), reply_markup=tariffs_keyboard(_tariffs(settings)))


@router.message(Command("referrals"))
async def cmd_referrals(message: Message, settings: Settings, order_store: OrderStore) -> None:
    await message.answer(
        referral_text(settings.data, _telegram_id(message), order_store.referral_stats(_telegram_id(message))),
        reply_markup=referral_keyboard(
            _telegram_id(message),
            str((settings.data.get("referrals") if isinstance(settings.data.get("referrals"), dict) else {}).get("bot_username") or "levikvpnbot"),
        ),
    )


@router.message(Command("paysupport"))
async def cmd_pay_support(message: Message, settings: Settings) -> None:
    support = settings.data.get("support") if isinstance(settings.data.get("support"), dict) else {}
    await message.answer(
        f"🆘 <b>Поддержка по оплатам</b>\n\n{esc(support.get('text') or 'Свяжитесь с администратором сервиса.')}",
        reply_markup=back_home_keyboard(),
    )


@router.pre_checkout_query()
async def pre_checkout(
    pre_checkout_query: PreCheckoutQuery,
    settings: Settings,
    order_store: OrderStore,
    remnawave: RemnawaveClient,
) -> None:
    if not telegram_stars_enabled(settings.data):
        await pre_checkout_query.answer(
            ok=False,
            error_message="Оплата Telegram Stars отключена. Выберите другой способ оплаты в боте.",
        )
        return
    parsed = _parse_payment_payload(pre_checkout_query.invoice_payload)
    if parsed is None:
        await pre_checkout_query.answer(ok=False, error_message="Счёт не найден. Создайте оплату заново.")
        return
    order_id, telegram_id = parsed
    if telegram_id != pre_checkout_query.from_user.id:
        await pre_checkout_query.answer(ok=False, error_message="Этот счёт создан для другого Telegram аккаунта.")
        return
    order = order_store.get(order_id)
    if order is None or str(order.get("status")) != "pending_payment":
        await pre_checkout_query.answer(ok=False, error_message="Счёт уже использован или устарел.")
        return
    if str(order.get("payment_method")) != "telegram_stars":
        await pre_checkout_query.answer(ok=False, error_message="Неверный способ оплаты.")
        return
    if pre_checkout_query.currency != "XTR" or int(order.get("stars_amount") or 0) != pre_checkout_query.total_amount:
        await pre_checkout_query.answer(ok=False, error_message="Сумма счёта изменилась. Создайте оплату заново.")
        return
    if str(order.get("kind")) == "slot":
        user_uuid = str(order.get("target_user_uuid") or "")
        wdtt_access = order_store.get_wdtt_access_by_user_uuid(user_uuid) if user_uuid else None
        if wdtt_access is not None:
            user = await remnawave.get_user_by_uuid(user_uuid) if _is_remnawave_uuid(user_uuid) else None
            if (
                user is None
                or int(user.get("telegramId") or 0) != telegram_id
                or _device_limit(user) <= 0
                or (plan_name(user) == MOBILE_PLAN and status_label(user) != "активна")
            ):
                await pre_checkout_query.answer(ok=False, error_message=WDTT_LEGACY_PAYMENT_MESSAGE)
                return
        else:
            user = await remnawave.get_user_by_uuid(user_uuid) if _is_remnawave_uuid(user_uuid) else None
            if (
                user is None
                or int(user.get("telegramId") or 0) != telegram_id
                or _device_limit(user) <= 0
                or (plan_name(user) == MOBILE_PLAN and status_label(user) != "активна")
            ):
                await pre_checkout_query.answer(ok=False, error_message="Подписка для докупки слота не найдена.")
                return
    if str(order.get("kind")) == "traffic":
        user_uuid = str(order.get("target_user_uuid") or "")
        multi = order_store.get_multi_subscription_by_user_uuid(user_uuid)
        target_reference = str(multi.get("mobile_user_uuid") or "") if multi is not None else user_uuid
        user = await remnawave.get_user_by_uuid(target_reference) if _is_remnawave_uuid(target_reference) else None
        if (
            user is None
            or int(user.get("telegramId") or 0) != telegram_id
            or plan_name(user) != MOBILE_PLAN
            or status_label(user) != "активна"
            or _traffic_limit(user) <= 0
        ):
            await pre_checkout_query.answer(ok=False, error_message="Подписка для докупки трафика не найдена.")
            return
    if str(order.get("kind")) == "access_renewal":
        user_uuid = str(order.get("target_user_uuid") or "")
        wdtt_access = order_store.get_wdtt_access_by_user_uuid(user_uuid) if user_uuid else None
        if wdtt_access is not None:
            user = await remnawave.get_user_by_uuid(user_uuid) if _is_remnawave_uuid(user_uuid) else None
            if user is None or int(user.get("telegramId") or 0) != telegram_id:
                await pre_checkout_query.answer(ok=False, error_message=WDTT_LEGACY_PAYMENT_MESSAGE)
                return
        else:
            user = await remnawave.get_user_by_uuid(user_uuid) if _is_remnawave_uuid(user_uuid) else None
            if user is None or int(user.get("telegramId") or 0) != telegram_id:
                await pre_checkout_query.answer(ok=False, error_message="Подписка для продления не найдена.")
                return
    await pre_checkout_query.answer(ok=True)


@router.message(F.successful_payment)
async def msg_successful_payment(
    message: Message,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    payment = message.successful_payment
    if payment is None:
        return
    parsed = _parse_payment_payload(payment.invoice_payload)
    if parsed is None:
        await message.answer("Оплата получена, но заказ не найден. Напишите в поддержку.", reply_markup=back_home_keyboard())
        return
    order_id, telegram_id = parsed
    if telegram_id != _telegram_id(message):
        await message.answer("Оплата получена, но Telegram ID не совпал. Напишите в поддержку.", reply_markup=back_home_keyboard())
        return
    order = order_store.mark_paid(
        order_id=order_id,
        telegram_payment_charge_id=payment.telegram_payment_charge_id,
        provider_payment_charge_id=payment.provider_payment_charge_id,
    )
    if order is None:
        await message.answer("Оплата получена, но заказ не найден. Напишите в поддержку.", reply_markup=back_home_keyboard())
        return
    result = await deliver_paid_order(
        telegram_id=_telegram_id(message),
        settings=settings,
        remnawave=remnawave,
        order_store=order_store,
        order=order,
    )
    if result.user_text:
        await message.answer(
            result.user_text,
            reply_markup=access_success_keyboard(
                result.user_uuid,
                subscription_url=result.subscription_url,
                offer_happ_routing=result.offer_happ_routing,
            ),
        )
    if result.referral_telegram_id and result.referral_text:
        await message.bot.send_message(result.referral_telegram_id, result.referral_text, reply_markup=back_home_keyboard())


async def show_keys_message(message: Message, settings: Settings, remnawave: RemnawaveClient, order_store: OrderStore) -> None:
    users = await _load_users(remnawave, _telegram_id(message), order_store)
    if not users:
        await _show_no_access(message, settings)
        return
    if len(users) == 1:
        await _show_subscription_details(message, settings=settings, remnawave=remnawave, user=users[0], index=0)
        return
    await message.answer(
        subscriptions_text(users, str(settings.data.get("timezone") or "Europe/Moscow")),
        reply_markup=subscriptions_keyboard(users, str(settings.data.get("timezone") or "Europe/Moscow")),
    )


async def show_profile_message(message: Message, settings: Settings, remnawave: RemnawaveClient, order_store: OrderStore, index: int) -> None:
    users = await _load_users(remnawave, _telegram_id(message), order_store)
    user = _get_user(users, index)
    if user is None:
        await _show_no_access(message, settings)
        return
    hosts = await _load_hosts(remnawave)
    await message.answer(
        profile_text(user, hosts, str(settings.data.get("timezone") or "Europe/Moscow")),
        reply_markup=_subscription_keyboard(settings, index, user),
    )


async def show_devices_message(message: Message, settings: Settings, remnawave: RemnawaveClient, order_store: OrderStore, index: int) -> None:
    users = await _load_users(remnawave, _telegram_id(message), order_store)
    user = _get_user(users, index)
    if user is not None and _is_wdtt_user(user):
        regular_index = next((idx for idx, item in enumerate(users) if not _is_wdtt_user(item)), None)
        user = _get_user(users, regular_index) if regular_index is not None else None
        index = regular_index if regular_index is not None else index
    if user is None:
        await _show_no_access(message, settings)
        return
    devices = await _multi_devices_for_user(remnawave, user) if plan_name(user) == MULTI_PLAN else await remnawave.get_user_devices(str(user["uuid"]))
    await message.answer(devices_text(user, devices), reply_markup=devices_keyboard(index, devices))


@router.callback_query(F.data.startswith("admin:"))
async def cb_admin(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    if not _is_admin_id(_telegram_id(callback)):
        await callback.answer("Недоступно.", show_alert=True)
        return
    action = _parse_value(callback.data, "admin:")
    await callback.answer()
    if callback.message is None:
        return
    if action == "stats":
        await _edit_or_send(
            callback.message,
            await _admin_stats_text(settings, remnawave, order_store),
            reply_markup=admin_keyboard(),
        )
        return
    if action == "recent":
        await _edit_or_send(callback.message, _admin_recent_text(settings, order_store), reply_markup=admin_keyboard())
        return
    if action == "links":
        await _edit_or_send(
            callback.message,
            _admin_links_text(settings, order_store),
            reply_markup=admin_keyboard(),
        )
        return
    await _edit_or_send(callback.message, _admin_help_text(settings), reply_markup=admin_keyboard())


@router.callback_query(F.data == "home")
async def cb_home(callback: CallbackQuery, settings: Settings, remnawave: RemnawaveClient, order_store: OrderStore) -> None:
    await callback.answer()
    if callback.message:
        await _show_home(
            callback.message,
            settings=settings,
            remnawave=remnawave,
            order_store=order_store,
            first_name=_first_name(callback),
            telegram_id=_telegram_id(callback),
            edit=True,
        )


@router.callback_query(F.data.startswith("scenario:"))
async def cb_scenario(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    scenario = _parse_value(callback.data, "scenario:")
    if scenario not in {"regular", "mobile", "auto"}:
        await callback.answer("Сценарий не найден.", show_alert=True)
        return
    telegram_id = _telegram_id(callback)
    users = await _load_users(remnawave, telegram_id, order_store)
    trial = order_store.get_trial_access(telegram_id)
    available = bool(trial_config(settings)["enabled"]) and trial_available(
        users,
        trial,
        telegram_id,
    )
    order_store.record_event(
        telegram_id=telegram_id,
        event_name="scenario_selected",
        properties={"scenario": scenario},
    )
    await callback.answer()
    if callback.message:
        await _edit_or_send(
            callback.message,
            scenario_text(scenario),
            reply_markup=scenario_keyboard(scenario, trial_available=available),
        )


@router.callback_query(F.data.startswith("trial:claim"))
async def cb_trial_claim(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    raw_component = str(callback.data or "").removeprefix("trial:claim:")
    component = raw_component if raw_component in {"regular", "mobile"} else "regular"
    telegram_id = _telegram_id(callback)
    users = await _load_users(remnawave, telegram_id, order_store)
    from_user = callback.from_user
    try:
        claim = claim_trial(
            telegram_id=telegram_id,
            telegram_username=from_user.username if from_user else None,
            first_name=from_user.first_name if from_user else None,
            component=component,
            users=users,
            settings=settings,
            order_store=order_store,
        )
    except TrialActivationError as exc:
        messages = {
            "trial_disabled": "Пробный доступ сейчас недоступен.",
            "trial_tariff_unavailable": "Пробный тариф временно недоступен.",
            "trial_already_used": "Вы уже использовали пробный доступ на этом Telegram-аккаунте.",
            "trial_not_eligible": "Пробный доступ доступен только без активных подписок.",
            "trial_in_progress": "Пробный доступ уже создаётся. Подождите немного.",
            "trial_support_required": "Для завершения выдачи напишите в поддержку.",
        }
        await callback.answer(
            messages.get(exc.code, "Не удалось создать пробный доступ."),
            show_alert=True,
        )
        return

    await callback.answer("Создаём пробную подписку…")
    try:
        result = await provision_trial(
            claim=claim,
            settings=settings,
            remnawave=remnawave,
            order_store=order_store,
        )
    except TrialActivationError:
        logger.exception("failed to provision trial access telegram_id=%s", telegram_id)
        if callback.message:
            await _edit_or_send(
                callback.message,
                "Не удалось полностью выдать пробный доступ. Повторите попытку — уже созданный ключ не продублируется.",
                reply_markup=trial_retry_keyboard(component),
            )
        return

    user_uuid = result.subscription_uuid
    if callback.message:
        await _edit_or_send(
            callback.message,
            trial_single_success_text(
                str(result.tariff.get("title") or "VPN"),
                int(result.config["duration_days"]),
                result.traffic_limit_bytes,
            ),
            reply_markup=connection_guide_keyboard(1, user_uuid),
        )

    try:
        await callback.bot.send_message(
            int(result.config["admin_telegram_id"]),
            trial_admin_notification_text(result.trial),
        )
    except TelegramAPIError:
        logger.warning("failed to send trial notification to admin", exc_info=True)
    else:
        order_store.mark_trial_admin_notified(telegram_id)


@router.callback_query(F.data.startswith("platform_select:"))
async def cb_platform_select(
    callback: CallbackQuery,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    user_uuid = _parse_value(callback.data, "platform_select:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = next((item for item in users if str(item.get("uuid") or "") == user_uuid), None)
    if user is None or _is_wdtt_user(user):
        await callback.answer("Подписка не найдена.", show_alert=True)
        return
    await callback.answer()
    if callback.message:
        await _edit_or_send(
            callback.message,
            connection_guide_text(1, user),
            reply_markup=connection_guide_keyboard(1, user_uuid),
        )


@router.callback_query(F.data.startswith("guide:"))
async def cb_connection_guide(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    raw = str(callback.data or "").removeprefix("guide:")
    step_value, separator, user_uuid = raw.partition(":")
    if not separator or step_value not in {"1", "2", "3", "back"}:
        await callback.answer("Шаг инструкции не найден.", show_alert=True)
        return
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    index = _find_user_index_by_uuid(users, user_uuid)
    if index is not None:
        user = users[index]
    if index is None:
        await callback.answer("Подписка не найдена.", show_alert=True)
        return
    user = users[index]
    if _is_wdtt_user(user):
        await callback.answer("Для этого ключа используется отдельная инструкция.", show_alert=True)
        return
    if step_value == "back":
        await callback.answer()
        if callback.message:
            await _show_subscription_details(
                callback.message,
                settings=settings,
                remnawave=remnawave,
                user=user,
                index=index,
                edit=True,
            )
        return

    step = int(step_value)
    subscription_url = str(user.get("subscriptionUrl") or "")
    if step == 2 and not subscription_url:
        await callback.answer("Ключ подписки временно недоступен.", show_alert=True)
        return
    await callback.answer()
    if callback.message:
        await _edit_or_send(
            callback.message,
            connection_guide_text(step, user),
            reply_markup=connection_guide_keyboard(
                step,
                user_uuid,
                happ_import_url=_happ_import_url(subscription_url) if step == 2 else None,
            ),
        )


@router.callback_query(F.data.startswith("platform:"))
async def cb_platform(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    raw = str(callback.data or "").removeprefix("platform:")
    platform, separator, user_uuid = raw.partition(":")
    if not separator or platform not in {"android", "ios", "windows", "macos"}:
        await callback.answer("Устройство не найдено.", show_alert=True)
        return
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = next((item for item in users if str(item.get("uuid") or "") == user_uuid), None)
    if user is None:
        await callback.answer("Подписка не найдена.", show_alert=True)
        return
    order_store.set_trial_platform(_telegram_id(callback), platform)
    order_store.record_event(
        telegram_id=_telegram_id(callback),
        event_name="platform_selected",
        properties={"platform": platform},
    )
    mobile_app = settings.data.get("mobile_app") if isinstance(settings.data.get("mobile_app"), dict) else {}
    download_url = str(mobile_app.get("download_url") or "") if platform == "android" else ""
    await callback.answer()
    if callback.message:
        await _edit_or_send(
            callback.message,
            platform_setup_text(platform, user),
            reply_markup=setup_keyboard(
                user_uuid,
                str(user.get("subscriptionUrl") or "") or None,
                download_url=download_url or None,
            ),
        )


@router.callback_query(F.data.startswith("connect_check:"))
async def cb_connect_check(
    callback: CallbackQuery,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    user_uuid = _parse_value(callback.data, "connect_check:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = next((item for item in users if str(item.get("uuid") or "") == user_uuid), None)
    if user is None:
        await callback.answer("Подписка не найдена.", show_alert=True)
        return
    connected = used_traffic_bytes(user) > 0
    if connected:
        order_store.mark_trial_first_traffic(_telegram_id(callback))
        order_store.record_event(telegram_id=_telegram_id(callback), event_name="first_traffic")
    else:
        order_store.record_event(telegram_id=_telegram_id(callback), event_name="connection_check_no_traffic")
    await callback.answer("Подключение подтверждено." if connected else "Трафик пока не появился.")
    if callback.message:
        await _edit_or_send(
            callback.message,
            connection_check_text(user, connected=connected),
            reply_markup=connection_result_keyboard(user_uuid, success=connected),
        )


@router.callback_query(F.data.startswith("connect_help:"))
async def cb_connect_help(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    user_uuid = _parse_value(callback.data, "connect_help:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = next((item for item in users if str(item.get("uuid") or "") == user_uuid), None)
    if user is None:
        await callback.answer("Подписка не найдена.", show_alert=True)
        return
    order_store.record_event(
        telegram_id=_telegram_id(callback),
        event_name="connection_help_requested",
        properties={"plan": plan_name(user), "has_traffic": used_traffic_bytes(user) > 0},
    )
    support = settings.data.get("support") if isinstance(settings.data.get("support"), dict) else {}
    await callback.answer()
    if callback.message:
        await _edit_or_send(
            callback.message,
            "🆘 <b>Помощь с подключением</b>\n\n"
            f"Тариф: <b>{esc(plan_name(user))}</b>\n"
            f"Статус: <b>{esc(status_label(user))}</b>\n"
            f"Трафик: <b>{esc(traffic_line(user))}</b>\n\n"
            f"{esc(support.get('text') or 'Напишите в поддержку — поможем закончить настройку.')}",
            reply_markup=support_diagnostics_keyboard(user_uuid, _support_url(settings)),
        )


@router.callback_query(F.data == "free_proxy")
async def cb_free_proxy(callback: CallbackQuery, settings: Settings, order_store: OrderStore) -> None:
    telegram_id = _telegram_id(callback)
    cached_proxy = order_store.get_free_mtproto_proxy(telegram_id)
    try:
        provisioned = await mtproto.get_or_create_free_proxy(settings, telegram_id=telegram_id)
    except mtproto.MtprotoProvisionerError:
        if cached_proxy is None:
            logger.exception("failed to provision free MTProto proxy")
            await callback.answer("Прокси временно недоступен. Попробуйте позже.", show_alert=True)
            return
        logger.warning("using cached free MTProto proxy after provisioner failure", exc_info=True)
        proxy = cached_proxy
    else:
        proxy = order_store.upsert_free_mtproto_proxy(
            telegram_id=telegram_id,
            mtproxy_label=str(provisioned["mtproxy_label"]),
            proxy_link=str(provisioned["proxy_link"]),
            rate_limit_mbps=int(provisioned["rate_limit_mbps"]),
            device_limit=int(provisioned["device_limit"]),
            max_tcp_connections=int(provisioned["max_tcp_connections"]),
            max_unique_ips=int(provisioned["max_unique_ips"]),
        )
    await callback.answer()
    if callback.message:
        await _edit_or_send(
            callback.message,
            mtproto.free_proxy_text(proxy),
            reply_markup=back_home_keyboard(),
        )


@router.callback_query(F.data == "subs")
@router.callback_query(F.data == "keys")
async def cb_keys(callback: CallbackQuery, settings: Settings, remnawave: RemnawaveClient, order_store: OrderStore) -> None:
    await callback.answer()
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    if not users:
        await _show_no_access(callback, settings)
        return
    if callback.message:
        if len(users) == 1:
            await _show_subscription_details(
                callback.message,
                settings=settings,
                remnawave=remnawave,
                user=users[0],
                index=0,
                edit=True,
            )
            return
        await _edit_or_send(
            callback.message,
            subscriptions_text(users, str(settings.data.get("timezone") or "Europe/Moscow")),
            reply_markup=subscriptions_keyboard(users, str(settings.data.get("timezone") or "Europe/Moscow")),
        )


@router.callback_query(F.data.startswith("sub:"))
async def cb_subscription(callback: CallbackQuery, settings: Settings, remnawave: RemnawaveClient, order_store: OrderStore) -> None:
    await callback.answer()
    index = _parse_index(callback.data, "sub:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, index)
    if user is None:
        await _show_no_access(callback, settings)
        return
    if callback.message:
        await _show_subscription_details(
            callback.message,
            settings=settings,
            remnawave=remnawave,
            user=user,
            index=index,
            edit=True,
        )


@router.callback_query(F.data.startswith("profile:"))
async def cb_profile(callback: CallbackQuery, settings: Settings, remnawave: RemnawaveClient, order_store: OrderStore) -> None:
    await callback.answer()
    index = _parse_index(callback.data, "profile:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, index)
    if user is None:
        await _show_no_access(callback, settings)
        return
    hosts = await _load_hosts(remnawave)
    if callback.message:
        await _edit_or_send(
            callback.message,
            profile_text(user, hosts, str(settings.data.get("timezone") or "Europe/Moscow")),
            reply_markup=_subscription_keyboard(settings, index, user),
        )


@router.callback_query(F.data.startswith("key:"))
async def cb_key(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    await callback.answer()
    index = _parse_index(callback.data, "key:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, index)
    if user is None:
        await _show_no_access(callback, settings)
        return
    if callback.message:
        wdtt_access = _wdtt_access_for_user(order_store, user)
        if wdtt_access is not None:
            text = wdtt.access_text(settings, wdtt_access, str(settings.data.get("timezone") or "Europe/Moscow"))
        else:
            text = key_text(user, str(settings.data.get("timezone") or "Europe/Moscow"))
        await _edit_or_send(
            callback.message,
            text,
            reply_markup=key_keyboard(index, str(user.get("subscriptionUrl") or ""), is_wdtt=_is_wdtt_user(user)),
        )


def _shield_user_id(user: dict[str, Any]) -> int:
    try:
        return int(user.get("id") or 0)
    except (TypeError, ValueError):
        return 0


def _shield_text(enabled: bool) -> str:
    status = "🟢 <b>Включён</b>" if enabled else "⚪️ <b>Выключен</b>"
    return (
        "🛡 <b>Levik Shield</b>\n\n"
        "Блокирует соединения с рекламными и отслеживающими доменами.\n\n"
        f"Статус: {status}\n\n"
        "Работает только в <b>Happ</b> и действует на всех устройствах этой подписки. "
        "После изменения обновите подписку в Happ и переподключитесь."
    )


@router.callback_query(F.data.startswith("shield:set:"))
async def cb_shield_set(
    callback: CallbackQuery,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    index, raw_enabled = _parse_index_and_value(callback.data, "shield:set:")
    if raw_enabled not in {"0", "1"}:
        await callback.answer("Некорректная настройка.", show_alert=True)
        return
    telegram_id = _telegram_id(callback)
    users = await _load_users(remnawave, telegram_id, order_store)
    user = _get_user(users, index)
    remnawave_user_id = _shield_user_id(user or {})
    if user is None or _is_wdtt_user(user) or remnawave_user_id <= 0:
        await callback.answer("Shield недоступен для этой подписки.", show_alert=True)
        return
    enabled = raw_enabled == "1"
    order_store.set_shield_enabled(
        remnawave_user_id=remnawave_user_id,
        telegram_id=telegram_id,
        user_uuid=str(user.get("uuid") or ""),
        enabled=enabled,
    )
    order_store.record_event(
        telegram_id=telegram_id,
        event_name="subscription_shield_changed",
        properties={"enabled": enabled},
    )
    await callback.answer("Shield включён." if enabled else "Shield выключен.")
    if callback.message:
        await _edit_or_send(
            callback.message,
            _shield_text(enabled),
            reply_markup=shield_manage_keyboard(index, enabled=enabled),
        )


@router.callback_query(F.data.startswith("shield:"))
async def cb_shield(
    callback: CallbackQuery,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    index = _parse_index(callback.data, "shield:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, index)
    remnawave_user_id = _shield_user_id(user or {})
    if user is None or _is_wdtt_user(user) or remnawave_user_id <= 0:
        await callback.answer("Shield недоступен для этой подписки.", show_alert=True)
        return
    enabled = order_store.get_shield_enabled(remnawave_user_id)
    await callback.answer()
    if callback.message:
        await _edit_or_send(
            callback.message,
            _shield_text(enabled),
            reply_markup=shield_manage_keyboard(index, enabled=enabled),
        )


@router.callback_query(F.data == "happ_routing:offer")
async def cb_happ_routing_offer(callback: CallbackQuery, settings: Settings) -> None:
    if not happ_routing_enabled(settings.data):
        await callback.answer("Маршрутизация временно недоступна.", show_alert=True)
        return
    await callback.answer()
    if callback.message:
        await _edit_or_send(
            callback.message,
            happ_routing_offer_text(settings.data),
            reply_markup=happ_routing_manage_keyboard(),
        )


@router.callback_query(F.data == "happ_routing:enable")
async def cb_happ_routing_enable(callback: CallbackQuery, settings: Settings) -> None:
    if not happ_routing_enabled(settings.data):
        await callback.answer("Маршрутизация временно недоступна.", show_alert=True)
        return
    await callback.answer()
    if callback.message:
        await _edit_or_send(
            callback.message,
            happ_routing_enable_confirm_text(settings.data),
            reply_markup=happ_routing_confirm_keyboard(),
        )


@router.callback_query(F.data == "happ_routing:disable")
async def cb_happ_routing_disable(callback: CallbackQuery, settings: Settings) -> None:
    if not happ_routing_enabled(settings.data):
        await callback.answer("Маршрутизация временно недоступна.", show_alert=True)
        return
    await callback.answer()
    if callback.message:
        await _edit_or_send(
            callback.message,
            happ_routing_disable_confirm_text(),
            reply_markup=happ_routing_disable_confirm_keyboard(),
        )


@router.callback_query(F.data == "happ_routing:confirm")
async def cb_happ_routing_confirm(callback: CallbackQuery, settings: Settings) -> None:
    if not happ_routing_enabled(settings.data):
        await callback.answer("Маршрутизация временно недоступна.", show_alert=True)
        return
    await callback.answer("Профиль готов к установке.")
    if callback.message:
        await _edit_or_send(
            callback.message,
            happ_routing_ready_text(enable=True),
            reply_markup=happ_routing_open_keyboard(settings.data, enable=True),
        )


@router.callback_query(F.data == "happ_routing:disable_confirm")
async def cb_happ_routing_disable_confirm(callback: CallbackQuery, settings: Settings) -> None:
    if not happ_routing_enabled(settings.data):
        await callback.answer("Маршрутизация временно недоступна.", show_alert=True)
        return
    await callback.answer("Отключение routing готово.")
    if callback.message:
        await _edit_or_send(
            callback.message,
            happ_routing_ready_text(enable=False),
            reply_markup=happ_routing_open_keyboard(settings.data, enable=False),
        )


@router.callback_query(F.data.startswith("instructions:"))
async def cb_instructions(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    await callback.answer()
    index = _parse_index(callback.data, "instructions:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, index)
    if callback.message:
        wdtt_access = _wdtt_access_for_user(order_store, user) if user else None
        if user is not None and wdtt_access is None:
            await _edit_or_send(
                callback.message,
                connection_guide_text(1, user),
                reply_markup=connection_guide_keyboard(1, str(user.get("uuid") or "")),
            )
            return
        text = (
            wdtt.access_text(settings, wdtt_access, str(settings.data.get("timezone") or "Europe/Moscow"))
            if wdtt_access is not None
            else instructions_text(settings.data, user)
        )
        await _edit_or_send(
            callback.message,
            text,
            reply_markup=_subscription_keyboard(settings, index, user) if user else back_home_keyboard(),
        )


@router.callback_query(F.data.startswith("instr_uuid:"))
async def cb_instructions_by_uuid(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    user_uuid = _parse_value(callback.data, "instr_uuid:")
    if not user_uuid:
        await callback.answer("Подписка не найдена.", show_alert=True)
        return
    wdtt_access = order_store.get_wdtt_access_by_user_uuid(user_uuid)
    if wdtt_access is not None:
        if int(wdtt_access.get("telegram_id") or 0) != _telegram_id(callback):
            await callback.answer("Эта подписка не найдена для вашего Telegram ID.", show_alert=True)
            return
        users = await _load_users(remnawave, _telegram_id(callback), order_store)
        index = _find_user_index_by_uuid(users, user_uuid)
        await callback.answer()
        if callback.message:
            await _edit_or_send(
                callback.message,
                wdtt.access_text(settings, wdtt_access, str(settings.data.get("timezone") or "Europe/Moscow")),
                reply_markup=_subscription_keyboard(settings, index, users[index]) if index is not None else back_home_keyboard(),
            )
        return
    if not _is_remnawave_uuid(user_uuid):
        await callback.answer("Эта подписка не найдена для вашего Telegram ID.", show_alert=True)
        return
    user = await remnawave.get_user_by_uuid(user_uuid)
    if user is None or int(user.get("telegramId") or 0) != _telegram_id(callback):
        await callback.answer("Эта подписка не найдена для вашего Telegram ID.", show_alert=True)
        return
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    index = _find_user_index_by_uuid(users, user_uuid)
    await callback.answer()
    if callback.message:
        visible_user = users[index] if index is not None else user
        await _edit_or_send(
            callback.message,
            connection_guide_text(1, visible_user),
            reply_markup=connection_guide_keyboard(1, user_uuid),
        )


@router.callback_query(F.data == "help_instructions")
async def cb_help_instructions(callback: CallbackQuery, settings: Settings) -> None:
    await callback.answer()
    if callback.message:
        await _edit_or_send(callback.message, instructions_text(settings.data), reply_markup=service_keyboard())


@router.callback_query(F.data.startswith("devices:"))
async def cb_devices(callback: CallbackQuery, settings: Settings, remnawave: RemnawaveClient, order_store: OrderStore) -> None:
    await callback.answer()
    index = _parse_index(callback.data, "devices:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, index)
    if user is None:
        await _show_no_access(callback, settings)
        return
    if _is_wdtt_user(user):
        try:
            devices = await _wdtt_devices_for_user(settings, order_store, user)
        except Exception:
            logger.exception("failed to load WDTT devices")
            await callback.answer("Не удалось загрузить устройства WDTT. Попробуйте позже.", show_alert=True)
            return
    else:
        devices = await _multi_devices_for_user(remnawave, user) if plan_name(user) == MULTI_PLAN else await remnawave.get_user_devices(str(user["uuid"]))
    if callback.message:
        await _edit_or_send(callback.message, devices_text(user, devices), reply_markup=devices_keyboard(index, devices))


@router.callback_query(F.data.startswith("slot:"))
async def cb_slot(callback: CallbackQuery, settings: Settings, remnawave: RemnawaveClient, order_store: OrderStore) -> None:
    await callback.answer()
    index = _parse_index(callback.data, "slot:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, index)
    if user is None:
        await _show_no_access(callback, settings)
        return
    if not _slots_enabled(settings):
        await callback.answer("Докупка слотов временно недоступна.", show_alert=True)
        return
    if _is_wdtt_user(user):
        await callback.answer(WDTT_LEGACY_PAYMENT_MESSAGE, show_alert=True)
        return
    if plan_name(user) in {MOBILE_PLAN, MULTI_PLAN} and status_label(user) != "активна":
        await callback.answer("Сначала продлите мобильную подписку, потом можно докупить слот или трафик.", show_alert=True)
        return
    if _device_limit(user) <= 0:
        if callback.message:
            await _edit_or_send(
                callback.message,
                slot_unlimited_text(user),
                reply_markup=_subscription_keyboard(settings, index, user),
            )
        return
    if callback.message:
        await _edit_or_send(
            callback.message,
            slot_purchase_text(settings.data, user),
            reply_markup=slot_payment_keyboard(index, settings.data),
        )


@router.callback_query(F.data.startswith("slot_pay:"))
async def cb_slot_fiat(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    index, method_id = _parse_index_and_value(callback.data, "slot_pay:")
    method = _slot_method(settings, method_id)
    platega = settings.data.get("platega") if isinstance(settings.data.get("platega"), dict) else {}
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, index)
    if user is None:
        await _show_no_access(callback, settings)
        return
    if _is_wdtt_user(user):
        await callback.answer(WDTT_LEGACY_PAYMENT_MESSAGE, show_alert=True)
        return
    if plan_name(user) in {MOBILE_PLAN, MULTI_PLAN} and status_label(user) != "активна":
        await callback.answer("Сначала продлите мобильную подписку, потом можно докупить слот или трафик.", show_alert=True)
        return
    if method is None:
        await callback.answer("Способ оплаты не найден.", show_alert=True)
        return
    if not method.get("enabled") or not platega.get("enabled"):
        await callback.answer(
            str(platega.get("unavailable_text") or "Этот способ оплаты пока подключается."),
            show_alert=True,
        )
        return
    try:
        method_code = int(method.get("platega_method") or 0)
    except (TypeError, ValueError):
        method_code = 0
    if method_code <= 0:
        await callback.answer("Способ оплаты не настроен.", show_alert=True)
        return
    limit = _device_limit(user)
    if limit <= 0:
        await callback.answer("На этой подписке нет ограничения по устройствам.", show_alert=True)
        return

    base_amount = slot_price_rub(settings.data)
    traffic_delta = slot_traffic_delta_bytes(settings.data) if plan_name(user) in {MOBILE_PLAN, MULTI_PLAN} else 0
    pay_amount_rub = int(method.get("amount_rub") or slot_platega_amount(settings.data, method))
    request_amount_rub = payment_method_request_amount(pay_amount_rub, method)
    try:
        order = order_store.create_slot_payment(
            telegram_id=_telegram_id(callback),
            telegram_username=_telegram_username(callback),
            first_name=_first_name(callback),
            target_user_uuid=str(user.get("uuid") or ""),
            target_user_name=str(user.get("username") or user.get("email") or "") or None,
            price_rub=base_amount,
            stars_amount=None,
            slots_delta=slot_amount(settings.data),
            payment_method=f"platega_{method_id}",
            traffic_delta_bytes=traffic_delta,
            pay_amount_rub=pay_amount_rub,
            platega_payment_method=method_code,
        )
    except OrderAlreadyInProgress:
        await callback.answer(ORDER_ALREADY_IN_PROGRESS_MESSAGE, show_alert=True)
        return
    await callback.answer("Создаю ссылку на оплату...")
    description_suffix = f" и +{traffic_delta // GB} ГБ" if traffic_delta > 0 else ""
    try:
        async with PlategaClient(settings) as platega_client:
            transaction = await platega_client.create_transaction(
                payment_method=method_code,
                amount_rub=request_amount_rub,
                description=f"Levik VPN: +{slot_amount(settings.data)} слот{description_suffix}",
                return_url=_platega_url(settings, "return_url", "https://t.me/levikvpnbot"),
                failed_url=_platega_url(settings, "failed_url", "https://t.me/levikvpnbot"),
                payload=_payment_payload(order.id, _telegram_id(callback)),
                telegram_id=_telegram_id(callback),
                username=_telegram_username(callback),
            )
    except PlategaApiError:
        logger.exception("failed to create Platega slot payment")
        order_store.mark_payment_canceled(order.id, "payment_creation_failed")
        await callback.answer("Не удалось создать ссылку на оплату. Попробуйте позже.", show_alert=True)
        return

    payment_url = str(transaction.get("redirect") or "")
    transaction_id = str(transaction.get("transactionId") or "")
    if not payment_url or not transaction_id:
        order_store.mark_payment_canceled(order.id, "payment_creation_failed")
        await callback.answer("Платёжная система не вернула ссылку. Попробуйте позже.", show_alert=True)
        return
    order_store.set_provider_payment(
        order_id=order.id,
        transaction_id=transaction_id,
        payment_url=payment_url,
        provider_amount_rub=request_amount_rub,
    )
    if callback.message:
        await _edit_or_send(
            callback.message,
            payment_link_text(order.id, str(method.get("title") or "Оплата"), pay_amount_rub),
            reply_markup=payment_link_keyboard(payment_url, order.id, back_callback=f"slot:{index}"),
        )


@router.callback_query(F.data.startswith("slot_stars:"))
async def cb_slot_stars(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    if not telegram_stars_enabled(settings.data):
        await callback.answer("Оплата Telegram Stars отключена. Выберите другой способ оплаты.", show_alert=True)
        return
    index = _parse_index(callback.data, "slot_stars:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, index)
    if user is None:
        await _show_no_access(callback, settings)
        return
    if _is_wdtt_user(user):
        await callback.answer(WDTT_LEGACY_PAYMENT_MESSAGE, show_alert=True)
        return
    if plan_name(user) in {MOBILE_PLAN, MULTI_PLAN} and status_label(user) != "активна":
        await callback.answer("Сначала продлите мобильную подписку, потом можно докупить слот или трафик.", show_alert=True)
        return
    limit = _device_limit(user)
    if limit <= 0:
        await callback.answer("На этой подписке нет ограничения по устройствам.", show_alert=True)
        return
    traffic_delta = slot_traffic_delta_bytes(settings.data) if plan_name(user) in {MOBILE_PLAN, MULTI_PLAN} else 0
    try:
        order = order_store.create_slot_payment(
            telegram_id=_telegram_id(callback),
            telegram_username=_telegram_username(callback),
            first_name=_first_name(callback),
            target_user_uuid=str(user.get("uuid") or ""),
            target_user_name=str(user.get("username") or user.get("email") or "") or None,
            price_rub=slot_price_rub(settings.data),
            stars_amount=slot_stars(settings.data),
            slots_delta=slot_amount(settings.data),
            payment_method="telegram_stars",
            traffic_delta_bytes=traffic_delta,
        )
    except OrderAlreadyInProgress:
        await callback.answer(ORDER_ALREADY_IN_PROGRESS_MESSAGE, show_alert=True)
        return
    await callback.answer("Создаю счёт в Telegram Stars...")
    if callback.message:
        await callback.message.answer_invoice(
            title=slot_invoice_title(settings.data),
            description=slot_invoice_description(settings.data, user),
            payload=_payment_payload(order.id, _telegram_id(callback)),
            provider_token="",
            currency="XTR",
            prices=[LabeledPrice(label=slot_invoice_title(settings.data), amount=slot_stars(settings.data))],
        )


@router.callback_query(F.data.startswith("traffic:"))
async def cb_traffic(callback: CallbackQuery, settings: Settings, remnawave: RemnawaveClient, order_store: OrderStore) -> None:
    await callback.answer()
    index = _parse_index(callback.data, "traffic:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, index)
    if user is None:
        await _show_no_access(callback, settings)
        return
    if _is_wdtt_user(user):
        await callback.answer(WDTT_LEGACY_PAYMENT_MESSAGE, show_alert=True)
        return
    if not _mobile_traffic_enabled_for_user(settings, user):
        await callback.answer("Докупка трафика доступна только для активной мобильной подписки с лимитом трафика.", show_alert=True)
        return
    if callback.message:
        await _edit_or_send(
            callback.message,
            mobile_traffic_purchase_text(settings.data, user),
            reply_markup=mobile_traffic_payment_keyboard(index, settings.data),
        )


@router.callback_query(F.data.startswith("traffic_pay:"))
async def cb_traffic_fiat(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    index, method_id = _parse_index_and_value(callback.data, "traffic_pay:")
    method = _traffic_method(settings, method_id)
    platega = settings.data.get("platega") if isinstance(settings.data.get("platega"), dict) else {}
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, index)
    if user is None:
        await _show_no_access(callback, settings)
        return
    if _is_wdtt_user(user):
        await callback.answer(WDTT_LEGACY_PAYMENT_MESSAGE, show_alert=True)
        return
    if not _mobile_traffic_enabled_for_user(settings, user):
        await callback.answer("Докупка трафика доступна только для мобильной подписки.", show_alert=True)
        return
    if method is None:
        await callback.answer("Способ оплаты не найден.", show_alert=True)
        return
    if not method.get("enabled") or not platega.get("enabled"):
        await callback.answer(
            str(platega.get("unavailable_text") or "Этот способ оплаты пока подключается."),
            show_alert=True,
        )
        return
    try:
        method_code = int(method.get("platega_method") or 0)
    except (TypeError, ValueError):
        method_code = 0
    if method_code <= 0:
        await callback.answer("Способ оплаты не настроен.", show_alert=True)
        return

    base_amount = mobile_traffic_price_rub(settings.data)
    traffic_delta = mobile_traffic_amount_bytes(settings.data)
    pay_amount_rub = int(method.get("amount_rub") or mobile_traffic_platega_amount(settings.data, method))
    request_amount_rub = payment_method_request_amount(pay_amount_rub, method)
    try:
        order = order_store.create_traffic_payment(
            telegram_id=_telegram_id(callback),
            telegram_username=_telegram_username(callback),
            first_name=_first_name(callback),
            target_user_uuid=str(user.get("uuid") or ""),
            target_user_name=str(user.get("username") or user.get("email") or "") or None,
            price_rub=base_amount,
            stars_amount=None,
            traffic_delta_bytes=traffic_delta,
            payment_method=f"platega_{method_id}",
            pay_amount_rub=pay_amount_rub,
            platega_payment_method=method_code,
        )
    except OrderAlreadyInProgress:
        await callback.answer(ORDER_ALREADY_IN_PROGRESS_MESSAGE, show_alert=True)
        return
    await callback.answer("Создаю ссылку на оплату...")
    try:
        async with PlategaClient(settings) as platega_client:
            transaction = await platega_client.create_transaction(
                payment_method=method_code,
                amount_rub=request_amount_rub,
                description=f"Levik VPN: +{traffic_delta // GB} ГБ трафика",
                return_url=_platega_url(settings, "return_url", "https://t.me/levikvpnbot"),
                failed_url=_platega_url(settings, "failed_url", "https://t.me/levikvpnbot"),
                payload=_payment_payload(order.id, _telegram_id(callback)),
                telegram_id=_telegram_id(callback),
                username=_telegram_username(callback),
            )
    except PlategaApiError:
        logger.exception("failed to create Platega traffic payment")
        order_store.mark_payment_canceled(order.id, "payment_creation_failed")
        await callback.answer("Не удалось создать ссылку на оплату. Попробуйте позже.", show_alert=True)
        return

    payment_url = str(transaction.get("redirect") or "")
    transaction_id = str(transaction.get("transactionId") or "")
    if not payment_url or not transaction_id:
        order_store.mark_payment_canceled(order.id, "payment_creation_failed")
        await callback.answer("Платёжная система не вернула ссылку. Попробуйте позже.", show_alert=True)
        return
    order_store.set_provider_payment(
        order_id=order.id,
        transaction_id=transaction_id,
        payment_url=payment_url,
        provider_amount_rub=request_amount_rub,
    )
    if callback.message:
        await _edit_or_send(
            callback.message,
            payment_link_text(order.id, str(method.get("title") or "Оплата"), pay_amount_rub),
            reply_markup=payment_link_keyboard(payment_url, order.id, back_callback=f"traffic:{index}"),
        )


@router.callback_query(F.data.startswith("traffic_stars:"))
async def cb_traffic_stars(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    if not telegram_stars_enabled(settings.data):
        await callback.answer("Оплата Telegram Stars отключена. Выберите другой способ оплаты.", show_alert=True)
        return
    index = _parse_index(callback.data, "traffic_stars:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, index)
    if user is None:
        await _show_no_access(callback, settings)
        return
    if _is_wdtt_user(user):
        await callback.answer(WDTT_LEGACY_PAYMENT_MESSAGE, show_alert=True)
        return
    if not _mobile_traffic_enabled_for_user(settings, user):
        await callback.answer("Докупка трафика доступна только для мобильной подписки.", show_alert=True)
        return
    try:
        order = order_store.create_traffic_payment(
            telegram_id=_telegram_id(callback),
            telegram_username=_telegram_username(callback),
            first_name=_first_name(callback),
            target_user_uuid=str(user.get("uuid") or ""),
            target_user_name=str(user.get("username") or user.get("email") or "") or None,
            price_rub=mobile_traffic_price_rub(settings.data),
            stars_amount=mobile_traffic_stars(settings.data),
            traffic_delta_bytes=mobile_traffic_amount_bytes(settings.data),
            payment_method="telegram_stars",
        )
    except OrderAlreadyInProgress:
        await callback.answer(ORDER_ALREADY_IN_PROGRESS_MESSAGE, show_alert=True)
        return
    await callback.answer("Создаю счёт в Telegram Stars...")
    if callback.message:
        await callback.message.answer_invoice(
            title=mobile_traffic_invoice_title(settings.data),
            description=mobile_traffic_invoice_description(settings.data, user),
            payload=_payment_payload(order.id, _telegram_id(callback)),
            provider_token="",
            currency="XTR",
            prices=[
                LabeledPrice(
                    label=mobile_traffic_invoice_title(settings.data),
                    amount=mobile_traffic_stars(settings.data),
                )
            ],
        )


@router.callback_query(F.data.startswith("refresh:"))
async def cb_refresh(callback: CallbackQuery) -> None:
    await callback.answer()
    index = _parse_index(callback.data, "refresh:")
    if callback.message:
        await _edit_or_send(
            callback.message,
            "🔄 <b>Обновить ключ?</b>\n\nПосле обновления старая ссылка подписки может перестать работать. На устройствах нужно будет добавить новый ключ.",
            reply_markup=refresh_confirm_keyboard(index),
        )


@router.callback_query(F.data.startswith("refresh_yes:"))
async def cb_refresh_yes(callback: CallbackQuery, settings: Settings, remnawave: RemnawaveClient, order_store: OrderStore) -> None:
    await callback.answer("Обновляю ключ...")
    index = _parse_index(callback.data, "refresh_yes:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, index)
    if user is None:
        await _show_no_access(callback, settings)
        return
    if _is_wdtt_user(user):
        await callback.answer("Обновление ключа недоступно для WDTT-подписки.", show_alert=True)
        return
    record = user.get("_multi_subscription")
    if isinstance(record, dict):
        primary_reference = str(record.get("primary_user_uuid") or "")
        mobile_reference = str(record.get("mobile_user_uuid") or "")
        primary = await remnawave.revoke_subscription(primary_reference) or user
        mobile = await remnawave.revoke_subscription(mobile_reference)
        rotated = order_store.rotate_multi_subscription_token(primary_reference)
        if rotated is None:
            raise RuntimeError("multi subscription token rotation failed")
        updated = decorate_user(settings, rotated, primary, mobile)
    else:
        updated = await remnawave.revoke_subscription(str(user["uuid"])) or user
    if callback.message:
        await _edit_or_send(
            callback.message,
            "✅ Ключ обновлён.\n\n" + key_text(updated, str(settings.data.get("timezone") or "Europe/Moscow")),
            reply_markup=key_keyboard(index, str(updated.get("subscriptionUrl") or ""), is_wdtt=False),
        )


@router.callback_query(F.data.startswith("devdel:"))
async def cb_device_delete(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    await callback.answer()
    user_index, device_index = _parse_two_indexes(callback.data, "devdel:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, user_index)
    if user is None or callback.message is None:
        return
    if _is_wdtt_user(user):
        try:
            devices = await _wdtt_devices_for_user(settings, order_store, user)
        except Exception:
            logger.exception("failed to load WDTT devices")
            await callback.answer("Не удалось загрузить устройства WDTT. Попробуйте позже.", show_alert=True)
            return
    else:
        devices = await _multi_devices_for_user(remnawave, user) if plan_name(user) == MULTI_PLAN else await remnawave.get_user_devices(str(user["uuid"]))
    if not (0 <= device_index < len(devices)):
        await _edit_or_send(callback.message, "Устройство уже не найдено.", reply_markup=devices_keyboard(user_index, devices))
        return
    await _edit_or_send(
        callback.message,
        f"Отвязать устройство?\n\n<b>{esc(device_name(devices[device_index]))}</b>",
        reply_markup=device_delete_confirm_keyboard(user_index, device_index),
    )


@router.callback_query(F.data.startswith("devdel_yes:"))
async def cb_device_delete_yes(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    await callback.answer("Отвязываю устройство...")
    user_index, device_index = _parse_two_indexes(callback.data, "devdel_yes:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, user_index)
    if user is None or callback.message is None:
        return
    if _is_wdtt_user(user):
        try:
            devices = await _wdtt_devices_for_user(settings, order_store, user)
            if 0 <= device_index < len(devices):
                access = _wdtt_access_for_user(order_store, user)
                device_id = str(devices[device_index].get("device_id") or "")
                if access is not None and device_id:
                    payload = await wdtt.delete_remote_device(settings, access, device_id)
                    devices = [item for item in payload.get("devices", []) if isinstance(item, dict)]
        except Exception:
            logger.exception("failed to delete WDTT device")
            await callback.answer("Не удалось отвязать устройство WDTT. Попробуйте позже.", show_alert=True)
            return
    else:
        devices = await _multi_devices_for_user(remnawave, user) if plan_name(user) == MULTI_PLAN else await remnawave.get_user_devices(str(user["uuid"]))
        if 0 <= device_index < len(devices):
            hwid = str(devices[device_index].get("hwid") or "")
            if hwid:
                target_reference = str(devices[device_index].get("_multi_user_uuid") or user.get("uuid") or "")
                await remnawave.delete_user_device(target_reference, hwid)
                devices = await _multi_devices_for_user(remnawave, user) if plan_name(user) == MULTI_PLAN else await remnawave.get_user_devices(str(user["uuid"]))
    await _edit_or_send(callback.message, devices_text(user, devices), reply_markup=devices_keyboard(user_index, devices))


@router.callback_query(F.data.startswith("devdelall:"))
async def cb_device_delete_all(callback: CallbackQuery) -> None:
    await callback.answer()
    user_index = _parse_index(callback.data, "devdelall:")
    if callback.message:
        await _edit_or_send(
            callback.message,
            "Отвязать все устройства от этого ключа?",
            reply_markup=device_delete_all_confirm_keyboard(user_index),
        )


@router.callback_query(F.data.startswith("devdelall_yes:"))
async def cb_device_delete_all_yes(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    await callback.answer("Отвязываю устройства...")
    user_index = _parse_index(callback.data, "devdelall_yes:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, user_index)
    if user is None or callback.message is None:
        return
    if _is_wdtt_user(user):
        access = _wdtt_access_for_user(order_store, user)
        if access is None:
            return
        try:
            payload = await wdtt.delete_all_remote_devices(settings, access)
            devices = [item for item in payload.get("devices", []) if isinstance(item, dict)]
        except Exception:
            logger.exception("failed to delete all WDTT devices")
            await callback.answer("Не удалось отвязать устройства WDTT. Попробуйте позже.", show_alert=True)
            return
    else:
        record = user.get("_multi_subscription")
        if isinstance(record, dict):
            await remnawave.delete_all_user_devices(str(record.get("primary_user_uuid") or ""))
            await remnawave.delete_all_user_devices(str(record.get("mobile_user_uuid") or ""))
            devices = []
        else:
            devices = await remnawave.delete_all_user_devices(str(user["uuid"]))
    await _edit_or_send(callback.message, devices_text(user, devices), reply_markup=devices_keyboard(user_index, devices))


@router.callback_query(F.data == "tariffs")
async def cb_tariffs(callback: CallbackQuery, settings: Settings) -> None:
    await callback.answer()
    if callback.message:
        await _edit_or_send(callback.message, tariffs_text(settings.data), reply_markup=tariffs_keyboard(_tariffs(settings)))


@router.callback_query(F.data == "referrals")
async def cb_referrals(callback: CallbackQuery, settings: Settings, order_store: OrderStore) -> None:
    await callback.answer()
    if callback.message:
        await _edit_or_send(
            callback.message,
            referral_text(settings.data, _telegram_id(callback), order_store.referral_stats(_telegram_id(callback))),
            reply_markup=referral_keyboard(
                _telegram_id(callback),
                str((settings.data.get("referrals") if isinstance(settings.data.get("referrals"), dict) else {}).get("bot_username") or "levikvpnbot"),
            ),
        )


@router.callback_query(F.data.startswith("buy:"))
async def cb_buy(callback: CallbackQuery, settings: Settings, order_store: OrderStore) -> None:
    tariff_id = _parse_value(callback.data, "buy:")
    tariff = _find_tariff(settings, tariff_id)
    if tariff is None:
        await callback.answer("Тариф не найден.", show_alert=True)
        return
    if not _tariff_purchase_enabled(tariff):
        await callback.answer(_tariff_purchase_unavailable_text(tariff), show_alert=True)
        return
    await callback.answer()
    if callback.message:
        await _edit_or_send(
            callback.message,
            purchase_period_text(tariff, config=settings.data),
            reply_markup=periods_keyboard(
                tariff_id,
                tariff,
                _purchase_periods(
                    settings,
                    tariff,
                    allow_extended=_trial_allows_extended_purchase(order_store, _telegram_id(callback)),
                ),
            ),
        )


@router.callback_query(F.data.startswith("period:"))
async def cb_period(callback: CallbackQuery, settings: Settings, order_store: OrderStore) -> None:
    tariff_id, months = _parse_value_and_int(callback.data, "period:")
    tariff = _find_tariff(settings, tariff_id)
    period = _find_period(settings, months)
    if tariff is None or period is None:
        await callback.answer("Вариант покупки не найден.", show_alert=True)
        return
    if not _tariff_purchase_enabled(tariff):
        await callback.answer(_tariff_purchase_unavailable_text(tariff), show_alert=True)
        return
    if not _purchase_period_allowed(
        tariff,
        months,
        allow_extended=_trial_allows_extended_purchase(order_store, _telegram_id(callback)),
    ):
        await callback.answer("Для первой покупки доступен один месяц. После успешного пробника откроются все периоды.", show_alert=True)
        return
    total = period_total(tariff, period)
    discount_percent, discount_rub, _referrer = _referral_discount(
        settings=settings,
        order_store=order_store,
        telegram_id=_telegram_id(callback),
        total_rub=total,
        monthly_price_rub=int(tariff.get("base_price_rub") or total),
    )
    amount_rub = _net_period_total(tariff, period, discount_rub)
    await callback.answer()
    if callback.message:
        await _edit_or_send(
            callback.message,
            purchase_confirm_text(
                tariff,
                period,
                settings.data,
                discount_percent=discount_percent,
                discount_rub=discount_rub,
            ),
            reply_markup=purchase_confirm_keyboard(
                tariff_id,
                months,
                settings.data,
                tariff,
                period,
                amount_rub,
                discount_rub,
            ),
        )


@router.callback_query(F.data.startswith("order:"))
async def cb_order(callback: CallbackQuery) -> None:
    await callback.answer("Эта кнопка устарела. Выберите способ оплаты заново.", show_alert=True)


@router.callback_query(F.data.startswith("checkpay:"))
async def cb_check_platega_payment(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    order_id = _parse_index(callback.data, "checkpay:")
    order = order_store.get(order_id)
    if order is None or int(order.get("telegram_id") or 0) != _telegram_id(callback):
        await callback.answer("Заказ не найден для вашего Telegram ID.", show_alert=True)
        return

    status = str(order.get("status") or "")
    if status == "delivered":
        await callback.answer("Этот заказ уже выдан.", show_alert=True)
        return
    if status in {"delivering", "paid"}:
        await callback.answer("Оплата уже обрабатывается. Проверьте чат через несколько секунд.", show_alert=True)
        return
    if status in {"canceled", "cancelled", "chargebacked"}:
        await callback.answer("Платёж по этому заказу отменён. Создайте новый счёт.", show_alert=True)
        return

    payment_method_value = str(order.get("payment_method") or "")
    transaction_id = str(order.get("provider_payment_charge_id") or "")
    if not payment_method_value.startswith("platega_") or not transaction_id:
        await callback.answer("Для этого заказа нет платежа Platega.", show_alert=True)
        return

    try:
        async with PlategaClient(settings) as platega_client:
            raw_transaction = await platega_client.get_transaction(transaction_id)
    except PlategaApiError:
        logger.exception("failed to check Platega payment order_id=%s", order_id)
        await callback.answer("Не удалось проверить оплату. Попробуйте ещё раз через минуту.", show_alert=True)
        return

    transaction = _platega_transaction_data(raw_transaction)
    payment_status = _platega_transaction_status(transaction)
    if payment_status in {"CANCELED", "CANCELLED", "CHARGEBACKED"}:
        order_store.mark_payment_canceled(order_id, payment_status.lower())
        order_store.record_event(
            telegram_id=_telegram_id(callback),
            event_name="payment_canceled",
            properties={"order_id": order_id},
        )
        await callback.answer("Платёж отменён.")
        if callback.message:
            await _edit_or_send(
                callback.message,
                "Платёж отменён. Подскажите, что помешало завершить оплату?",
                reply_markup=payment_cancel_reason_keyboard(order_id),
            )
        return
    if payment_status != "CONFIRMED":
        await callback.answer("Платёж пока не подтверждён. Если вы только что оплатили, попробуйте ещё раз через минуту.", show_alert=True)
        return
    if not _platega_amount_matches(transaction, order):
        logger.error("Platega amount mismatch order_id=%s", order_id)
        await callback.answer("Оплата найдена, но сумма не совпала с заказом. Напишите в поддержку.", show_alert=True)
        return

    paid_order = order_store.mark_paid(
        order_id=order_id,
        telegram_payment_charge_id="platega_manual_check",
        provider_payment_charge_id=_platega_transaction_id(transaction, transaction_id),
    )
    if paid_order is None:
        await callback.answer("Заказ не найден. Напишите в поддержку.", show_alert=True)
        return

    result = await deliver_paid_order(
        telegram_id=_telegram_id(callback),
        settings=settings,
        remnawave=remnawave,
        order_store=order_store,
        order=paid_order,
    )
    if result.user_text:
        if callback.message:
            await _edit_or_send(
                callback.message,
                result.user_text,
                reply_markup=access_success_keyboard(
                    result.user_uuid,
                    subscription_url=result.subscription_url,
                    offer_happ_routing=result.offer_happ_routing,
                ),
            )
        else:
            await callback.bot.send_message(
                _telegram_id(callback),
                result.user_text,
                reply_markup=access_success_keyboard(
                    result.user_uuid,
                    subscription_url=result.subscription_url,
                    offer_happ_routing=result.offer_happ_routing,
                ),
            )
    if result.referral_telegram_id and result.referral_text:
        await callback.bot.send_message(result.referral_telegram_id, result.referral_text, reply_markup=back_home_keyboard())
    if result.success:
        await callback.answer("Оплата подтверждена, доступ выдан.")
    else:
        await callback.answer("Оплата подтверждена, но доступ не удалось выдать автоматически. Напишите в поддержку.", show_alert=True)


@router.callback_query(F.data.startswith("payreason:"))
async def cb_payment_reason(callback: CallbackQuery, order_store: OrderStore) -> None:
    raw = str(callback.data or "").removeprefix("payreason:")
    order_id_text, separator, reason = raw.partition(":")
    try:
        order_id = int(order_id_text)
    except ValueError:
        order_id = 0
    order = order_store.get(order_id) if order_id > 0 else None
    allowed = {"price", "error", "tariff", "later"}
    if order is None or int(order.get("telegram_id") or 0) != _telegram_id(callback) or reason not in allowed:
        await callback.answer("Заказ не найден.", show_alert=True)
        return
    order_store.record_event(
        telegram_id=_telegram_id(callback),
        event_name="payment_cancel_reason",
        properties={"order_id": order_id, "reason": reason},
    )
    await callback.answer("Спасибо, ответ сохранён.")
    if callback.message:
        await _edit_or_send(
            callback.message,
            "Спасибо. Мы учтём причину отказа. Вернуться к тарифам можно в любой момент.",
            reply_markup=back_home_keyboard(),
        )


@router.callback_query(F.data.startswith("astar:"))
async def cb_access_stars(callback: CallbackQuery, settings: Settings, order_store: OrderStore) -> None:
    if not telegram_stars_enabled(settings.data):
        await callback.answer("Оплата Telegram Stars отключена. Выберите другой способ оплаты.", show_alert=True)
        return
    tariff_id, months = _parse_value_and_int(callback.data, "astar:")
    tariff = _find_tariff(settings, tariff_id)
    period = _find_period(settings, months)
    if tariff is None or period is None:
        await callback.answer("Вариант покупки не найден.", show_alert=True)
        return
    if not _tariff_purchase_enabled(tariff):
        await callback.answer(_tariff_purchase_unavailable_text(tariff), show_alert=True)
        return
    if not _purchase_period_allowed(
        tariff,
        months,
        allow_extended=_trial_allows_extended_purchase(order_store, _telegram_id(callback)),
    ):
        await callback.answer("Для первой покупки доступен один месяц. После успешного пробника откроются все периоды.", show_alert=True)
        return
    total = period_total(tariff, period)
    discount_percent, discount_rub, referrer_telegram_id = _referral_discount(
        settings=settings,
        order_store=order_store,
        telegram_id=_telegram_id(callback),
        total_rub=total,
        monthly_price_rub=int(tariff.get("base_price_rub") or total),
    )
    amount_rub = _net_period_total(tariff, period, discount_rub)
    stars_amount = period_total_stars(settings.data, tariff, period, discount_rub)
    await callback.answer("Создаю счёт в Telegram Stars...")
    order = order_store.create_access_payment(
        telegram_id=_telegram_id(callback),
        telegram_username=_telegram_username(callback),
        first_name=_first_name(callback),
        tariff_id=tariff_id,
        tariff_title=str(tariff.get("title") or "Тариф"),
        period_months=period_months(period),
        price_rub=amount_rub,
        stars_amount=stars_amount,
        kind="access_purchase",
        base_price_rub=total,
        discount_percent=discount_percent,
        discount_rub=discount_rub,
        referrer_telegram_id=referrer_telegram_id,
    )
    if callback.message:
        await callback.message.answer_invoice(
            title=access_invoice_title(tariff, period),
            description=access_invoice_description(tariff, period),
            payload=_payment_payload(order.id, _telegram_id(callback)),
            provider_token="",
            currency="XTR",
            prices=[
                LabeledPrice(
                    label=access_invoice_title(tariff, period),
                    amount=stars_amount,
                )
            ],
        )


@router.callback_query(F.data.startswith("aplat:"))
async def cb_access_platega(callback: CallbackQuery, settings: Settings, order_store: OrderStore) -> None:
    tariff_id, months, method_id = _parse_value_int_value(callback.data, "aplat:")
    tariff = _find_tariff(settings, tariff_id)
    period = _find_period(settings, months)
    method_pair = _platega_method(settings, method_id)
    platega = settings.data.get("platega") if isinstance(settings.data.get("platega"), dict) else {}
    if tariff is None or period is None or method_pair is None:
        await callback.answer("Вариант оплаты не найден.", show_alert=True)
        return
    if not _tariff_purchase_enabled(tariff):
        await callback.answer(_tariff_purchase_unavailable_text(tariff), show_alert=True)
        return
    if not _purchase_period_allowed(
        tariff,
        months,
        allow_extended=_trial_allows_extended_purchase(order_store, _telegram_id(callback)),
    ):
        await callback.answer("Для первой покупки доступен один месяц. После успешного пробника откроются все периоды.", show_alert=True)
        return
    if not platega.get("enabled"):
        await callback.answer(str(platega.get("unavailable_text") or "Этот способ оплаты временно недоступен."), show_alert=True)
        return

    method, method_code = method_pair
    total = period_total(tariff, period)
    discount_percent, discount_rub, referrer_telegram_id = _referral_discount(
        settings=settings,
        order_store=order_store,
        telegram_id=_telegram_id(callback),
        total_rub=total,
        monthly_price_rub=int(tariff.get("base_price_rub") or total),
    )
    amount_rub = _net_period_total(tariff, period, discount_rub)
    pay_amount_rub = payment_method_amount(amount_rub, method)
    request_amount_rub = payment_method_request_amount(pay_amount_rub, method)
    await callback.answer("Создаю ссылку на оплату...")
    order = order_store.create_access_payment(
        telegram_id=_telegram_id(callback),
        telegram_username=_telegram_username(callback),
        first_name=_first_name(callback),
        tariff_id=tariff_id,
        tariff_title=str(tariff.get("title") or "Тариф"),
        period_months=period_months(period),
        price_rub=amount_rub,
        stars_amount=None,
        kind="access_purchase",
        payment_method=f"platega_{method_id}",
        pay_amount_rub=pay_amount_rub,
        base_price_rub=total,
        discount_percent=discount_percent,
        discount_rub=discount_rub,
        referrer_telegram_id=referrer_telegram_id,
        platega_payment_method=method_code,
    )
    try:
        async with PlategaClient(settings) as platega_client:
            transaction = await platega_client.create_transaction(
                payment_method=method_code,
                amount_rub=request_amount_rub,
                description=f"Levik VPN: {tariff.get('title') or 'Тариф'} {period_title(period)}",
                return_url=_platega_url(settings, "return_url", "https://t.me/levikvpnbot"),
                failed_url=_platega_url(settings, "failed_url", "https://t.me/levikvpnbot"),
                payload=_payment_payload(order.id, _telegram_id(callback)),
                telegram_id=_telegram_id(callback),
                username=_telegram_username(callback),
            )
    except PlategaApiError:
        logger.exception("failed to create Platega access payment")
        await callback.answer("Не удалось создать ссылку на оплату. Попробуйте позже.", show_alert=True)
        return

    payment_url = str(transaction.get("redirect") or "")
    transaction_id = str(transaction.get("transactionId") or "")
    if not payment_url or not transaction_id:
        await callback.answer("Платёжная система не вернула ссылку. Попробуйте позже.", show_alert=True)
        return
    order_store.set_provider_payment(
        order_id=order.id,
        transaction_id=transaction_id,
        payment_url=payment_url,
        provider_amount_rub=request_amount_rub,
    )
    if callback.message:
        await _edit_or_send(
            callback.message,
            payment_link_text(order.id, payment_method_title(method), pay_amount_rub),
            reply_markup=payment_link_keyboard(payment_url, order.id, back_callback=f"period:{tariff_id[:24]}:{months}"),
        )


@router.callback_query(F.data == "renew_select")
async def cb_renew_select(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    renewable_users = _renewable_users(await _load_users(remnawave, _telegram_id(callback), order_store))
    if not renewable_users:
        await callback.answer("Нет активных подписок, доступных для продления.", show_alert=True)
        return
    await callback.answer()
    if callback.message:
        await _edit_or_send(
            callback.message,
            "💎 <b>Продление подписки</b>\n\nВыберите подписку для продления.",
            reply_markup=renewal_subscriptions_keyboard(renewable_users),
        )


@router.callback_query(F.data.startswith("renew:"))
async def cb_renew(callback: CallbackQuery, settings: Settings, remnawave: RemnawaveClient, order_store: OrderStore) -> None:
    index = _parse_index(callback.data, "renew:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, index)
    if user is None:
        await _show_no_access(callback, settings)
        return
    await _show_renewal_options(callback, settings, user, index)


@router.callback_query(F.data.startswith("rem_renew:"))
async def cb_reminder_renew(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    reminder_id = _parse_index(callback.data, "rem_renew:")
    reminder = order_store.get_subscription_reminder(reminder_id)
    if reminder is None:
        await callback.answer("Напоминание уже устарело.", show_alert=True)
        return
    if int(reminder.get("telegram_id") or 0) != _telegram_id(callback):
        await callback.answer("Это напоминание создано для другого аккаунта.", show_alert=True)
        return

    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    index = _find_user_index_by_uuid(users, str(reminder.get("user_uuid") or ""))
    if index is None:
        await callback.answer()
        if callback.message:
            await _edit_or_send(
                callback.message,
                "Подписка уже изменилась или была удалена. Откройте главное меню, чтобы посмотреть актуальные данные.",
                reply_markup=back_home_keyboard(),
            )
        return

    await _show_renewal_options(callback, settings, users[index], index)


@router.callback_query(F.data.startswith("rem_snooze:"))
async def cb_reminder_snooze(callback: CallbackQuery, settings: Settings, order_store: OrderStore) -> None:
    reminder_id = _parse_index(callback.data, "rem_snooze:")
    reminder = order_store.get_subscription_reminder(reminder_id)
    if reminder is None:
        await callback.answer("Напоминание уже устарело.", show_alert=True)
        return
    if int(reminder.get("telegram_id") or 0) != _telegram_id(callback):
        await callback.answer("Это напоминание создано для другого аккаунта.", show_alert=True)
        return

    order_store.mark_subscription_reminder_sent(reminder_id, _local_today(settings))
    await callback.answer("Хорошо, напомню завтра.")
    if callback.message:
        await _edit_or_send(
            callback.message,
            "⏰ Напоминание отложено.\n\nЗавтра пришлю его снова, если срок подписки всё ещё будет подходить.",
            reply_markup=back_home_keyboard(),
        )


@router.callback_query(F.data.startswith("rem_stop:"))
async def cb_reminder_stop(callback: CallbackQuery, order_store: OrderStore) -> None:
    reminder_id = _parse_index(callback.data, "rem_stop:")
    reminder = order_store.get_subscription_reminder(reminder_id)
    if reminder is None:
        await callback.answer("Напоминание уже устарело.", show_alert=True)
        return
    if int(reminder.get("telegram_id") or 0) != _telegram_id(callback):
        await callback.answer("Это напоминание создано для другого аккаунта.", show_alert=True)
        return

    order_store.decline_subscription_reminder(reminder_id)
    await callback.answer("Больше не напомню по этому сроку.")
    if callback.message:
        await _edit_or_send(
            callback.message,
            "Готово. По этой подписке больше не буду напоминать до окончания текущего срока.",
            reply_markup=back_home_keyboard(),
        )


@router.callback_query(F.data.startswith("rperiod:"))
async def cb_renewal_period(callback: CallbackQuery, settings: Settings, remnawave: RemnawaveClient, order_store: OrderStore) -> None:
    index, months = _parse_two_indexes(callback.data, "rperiod:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, index)
    if user is None:
        await _show_no_access(callback, settings)
        return
    if _is_wdtt_user(user):
        await callback.answer(WDTT_LEGACY_PAYMENT_MESSAGE, show_alert=True)
        return
    tariff = _find_tariff(settings, _tariff_id_for_user(settings, user))
    period = _find_period(settings, months)
    if tariff is None or period is None:
        await callback.answer("Вариант продления не найден.", show_alert=True)
        return
    await callback.answer()
    if callback.message:
        await _edit_or_send(
            callback.message,
            purchase_confirm_text(tariff, period, settings.data, kind="renewal", user=user),
            reply_markup=renewal_confirm_keyboard(index, months, settings.data, tariff, period),
        )


@router.callback_query(F.data.startswith("rorder:"))
async def cb_renewal_order(callback: CallbackQuery) -> None:
    await callback.answer("Эта кнопка устарела. Выберите способ оплаты заново.", show_alert=True)


@router.callback_query(F.data.startswith("rastar:"))
async def cb_renewal_stars(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    if not telegram_stars_enabled(settings.data):
        await callback.answer("Оплата Telegram Stars отключена. Выберите другой способ оплаты.", show_alert=True)
        return
    index, months = _parse_two_indexes(callback.data, "rastar:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, index)
    if user is None:
        await _show_no_access(callback, settings)
        return
    if _is_wdtt_user(user):
        await callback.answer(WDTT_LEGACY_PAYMENT_MESSAGE, show_alert=True)
        return
    tariff_id = _tariff_id_for_user(settings, user)
    tariff = _find_tariff(settings, tariff_id)
    period = _find_period(settings, months)
    if tariff is None or period is None:
        await callback.answer("Вариант продления не найден.", show_alert=True)
        return
    try:
        order = order_store.create_access_payment(
            telegram_id=_telegram_id(callback),
            telegram_username=_telegram_username(callback),
            first_name=_first_name(callback),
            tariff_id=tariff_id,
            tariff_title=str(tariff.get("title") or "Тариф"),
            period_months=period_months(period),
            price_rub=period_total(tariff, period),
            stars_amount=period_total_stars(settings.data, tariff, period),
            kind="access_renewal",
            target_user_uuid=str(user.get("uuid") or "") or None,
            target_user_name=str(user.get("username") or user.get("email") or "") or None,
        )
    except OrderAlreadyInProgress:
        await callback.answer(ORDER_ALREADY_IN_PROGRESS_MESSAGE, show_alert=True)
        return
    await callback.answer("Создаю счёт в Telegram Stars...")
    if callback.message:
        await callback.message.answer_invoice(
            title=access_invoice_title(tariff, period),
            description=access_invoice_description(tariff, period, renewal=True),
            payload=_payment_payload(order.id, _telegram_id(callback)),
            provider_token="",
            currency="XTR",
            prices=[
                LabeledPrice(
                    label=access_invoice_title(tariff, period),
                    amount=period_total_stars(settings.data, tariff, period),
                )
            ],
        )


@router.callback_query(F.data.startswith("rplat:"))
async def cb_renewal_platega(
    callback: CallbackQuery,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> None:
    index, months, method_id = _parse_int_int_value(callback.data, "rplat:")
    users = await _load_users(remnawave, _telegram_id(callback), order_store)
    user = _get_user(users, index)
    if user is None:
        await _show_no_access(callback, settings)
        return
    if _is_wdtt_user(user):
        await callback.answer(WDTT_LEGACY_PAYMENT_MESSAGE, show_alert=True)
        return
    tariff_id = _tariff_id_for_user(settings, user)
    tariff = _find_tariff(settings, tariff_id)
    period = _find_period(settings, months)
    method_pair = _platega_method(settings, method_id)
    platega = settings.data.get("platega") if isinstance(settings.data.get("platega"), dict) else {}
    if tariff is None or period is None or method_pair is None:
        await callback.answer("Вариант продления не найден.", show_alert=True)
        return
    if not platega.get("enabled"):
        await callback.answer(str(platega.get("unavailable_text") or "Этот способ оплаты временно недоступен."), show_alert=True)
        return

    method, method_code = method_pair
    amount_rub = period_total(tariff, period)
    pay_amount_rub = payment_method_amount(amount_rub, method)
    request_amount_rub = payment_method_request_amount(pay_amount_rub, method)
    try:
        order = order_store.create_access_payment(
            telegram_id=_telegram_id(callback),
            telegram_username=_telegram_username(callback),
            first_name=_first_name(callback),
            tariff_id=tariff_id,
            tariff_title=str(tariff.get("title") or "Тариф"),
            period_months=period_months(period),
            price_rub=amount_rub,
            stars_amount=None,
            kind="access_renewal",
            target_user_uuid=str(user.get("uuid") or "") or None,
            target_user_name=str(user.get("username") or user.get("email") or "") or None,
            payment_method=f"platega_{method_id}",
            pay_amount_rub=pay_amount_rub,
            base_price_rub=amount_rub,
            platega_payment_method=method_code,
        )
    except OrderAlreadyInProgress:
        await callback.answer(ORDER_ALREADY_IN_PROGRESS_MESSAGE, show_alert=True)
        return
    await callback.answer("Создаю ссылку на оплату...")
    try:
        async with PlategaClient(settings) as platega_client:
            transaction = await platega_client.create_transaction(
                payment_method=method_code,
                amount_rub=request_amount_rub,
                description=f"Levik VPN: продление {tariff.get('title') or 'Тариф'} {period_title(period)}",
                return_url=_platega_url(settings, "return_url", "https://t.me/levikvpnbot"),
                failed_url=_platega_url(settings, "failed_url", "https://t.me/levikvpnbot"),
                payload=_payment_payload(order.id, _telegram_id(callback)),
                telegram_id=_telegram_id(callback),
                username=_telegram_username(callback),
            )
    except PlategaApiError:
        logger.exception("failed to create Platega renewal payment")
        order_store.mark_payment_canceled(order.id, "payment_creation_failed")
        await callback.answer("Не удалось создать ссылку на оплату. Попробуйте позже.", show_alert=True)
        return

    payment_url = str(transaction.get("redirect") or "")
    transaction_id = str(transaction.get("transactionId") or "")
    if not payment_url or not transaction_id:
        order_store.mark_payment_canceled(order.id, "payment_creation_failed")
        await callback.answer("Платёжная система не вернула ссылку. Попробуйте позже.", show_alert=True)
        return
    order_store.set_provider_payment(
        order_id=order.id,
        transaction_id=transaction_id,
        payment_url=payment_url,
        provider_amount_rub=request_amount_rub,
    )
    if callback.message:
        await _edit_or_send(
            callback.message,
            payment_link_text(order.id, payment_method_title(method), pay_amount_rub),
            reply_markup=payment_link_keyboard(payment_url, order.id, back_callback=f"rperiod:{index}:{months}"),
        )


@router.callback_query(F.data.startswith("pay:"))
async def cb_payment_unavailable(callback: CallbackQuery, settings: Settings) -> None:
    payments = settings.data.get("payments") if isinstance(settings.data.get("payments"), dict) else {}
    await callback.answer(str(payments.get("unavailable_text") or "Оплата временно недоступна."), show_alert=True)


@router.callback_query(F.data == "more")
async def cb_more(callback: CallbackQuery, settings: Settings) -> None:
    await callback.answer()
    if callback.message:
        await _edit_or_send(
            callback.message,
            "<b>Дополнительные возможности</b>",
            reply_markup=more_keyboard(support_url=_support_url(settings)),
        )


@router.callback_query(F.data == "service")
async def cb_service(callback: CallbackQuery, settings: Settings, remnawave: RemnawaveClient) -> None:
    await callback.answer()
    hosts = await _load_hosts(remnawave)
    if callback.message:
        await _edit_or_send(callback.message, service_text(settings.data, hosts), reply_markup=service_keyboard())


@router.callback_query(F.data == "support")
async def cb_support(callback: CallbackQuery, settings: Settings) -> None:
    await callback.answer()
    support = settings.data.get("support") if isinstance(settings.data.get("support"), dict) else {}
    if callback.message:
        await _edit_or_send(
            callback.message,
            f"🆘 <b>Поддержка</b>\n\n{esc(support.get('text') or 'Свяжитесь с администратором сервиса.')}",
            reply_markup=back_home_keyboard(),
        )


@router.callback_query(F.data == "policy")
async def cb_policy(callback: CallbackQuery, settings: Settings) -> None:
    await callback.answer()
    if callback.message:
        await _edit_or_send(callback.message, legal_text(settings.data, "policy"), reply_markup=service_keyboard())


@router.callback_query(F.data == "terms")
async def cb_terms(callback: CallbackQuery, settings: Settings) -> None:
    await callback.answer()
    if callback.message:
        await _edit_or_send(callback.message, legal_text(settings.data, "terms"), reply_markup=service_keyboard())


def _parse_index(data: str | None, prefix: str) -> int:
    if not data or not data.startswith(prefix):
        return 0
    try:
        return int(data.removeprefix(prefix))
    except ValueError:
        return 0


def _parse_value(data: str | None, prefix: str) -> str:
    if not data or not data.startswith(prefix):
        return ""
    return data.removeprefix(prefix)


def _parse_value_and_int(data: str | None, prefix: str) -> tuple[str, int]:
    if not data or not data.startswith(prefix):
        return "", 0
    value, _, raw_number = data.removeprefix(prefix).partition(":")
    try:
        return value, int(raw_number)
    except ValueError:
        return value, 0


def _parse_two_indexes(data: str | None, prefix: str) -> tuple[int, int]:
    if not data or not data.startswith(prefix):
        return 0, 0
    parts = data.removeprefix(prefix).split(":", 1)
    try:
        return int(parts[0]), int(parts[1])
    except (IndexError, ValueError):
        return 0, 0


def _parse_index_and_value(data: str | None, prefix: str) -> tuple[int, str]:
    if not data or not data.startswith(prefix):
        return 0, ""
    raw_index, _, value = data.removeprefix(prefix).partition(":")
    try:
        return int(raw_index), value
    except ValueError:
        return 0, value


def _parse_value_int_value(data: str | None, prefix: str) -> tuple[str, int, str]:
    if not data or not data.startswith(prefix):
        return "", 0, ""
    first, _, rest = data.removeprefix(prefix).partition(":")
    raw_number, _, second = rest.partition(":")
    try:
        return first, int(raw_number), second
    except ValueError:
        return first, 0, second


def _parse_int_int_value(data: str | None, prefix: str) -> tuple[int, int, str]:
    if not data or not data.startswith(prefix):
        return 0, 0, ""
    raw_first, _, rest = data.removeprefix(prefix).partition(":")
    raw_second, _, value = rest.partition(":")
    try:
        return int(raw_first), int(raw_second), value
    except ValueError:
        return 0, 0, value
