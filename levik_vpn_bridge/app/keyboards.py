from __future__ import annotations

import re
from typing import Any
from urllib.parse import quote, urlsplit

from aiogram.types import InlineKeyboardMarkup
from aiogram.utils.keyboard import InlineKeyboardBuilder

from app.formatters import (
    format_date,
    mobile_traffic_config,
    mobile_traffic_stars,
    payment_method_amount,
    payment_method_title,
    payment_methods,
    period_months,
    period_title,
    period_total,
    period_savings,
    period_total_stars,
    plan_name,
    plain_user_title,
    rub,
    slot_stars,
    telegram_stars_enabled,
)

HAPP_SUBSCRIPTION_TOKEN_RE = re.compile(r"^[A-Za-z0-9_-]{6,80}$")
HAPP_SUBSCRIPTION_HOSTS = {"sub.leviknet.com", "levik.levafart.store"}
HAPP_IMPORT_BASE_URL = "https://sub.leviknet.com:2095/levik-vpn-bot/happ-import"


def happ_import_url(subscription_url: str) -> str | None:
    try:
        parsed = urlsplit(subscription_url)
        port = parsed.port
    except ValueError:
        return None
    path = parsed.path.removeprefix("/")
    is_multi = path.startswith("multi/")
    token = path.removeprefix("multi/") if is_multi else path
    if (
        parsed.scheme != "https"
        or parsed.hostname not in HAPP_SUBSCRIPTION_HOSTS
        or port != 2096
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or "/" in token
        or not HAPP_SUBSCRIPTION_TOKEN_RE.fullmatch(token)
    ):
        return None
    return f"{HAPP_IMPORT_BASE_URL}/multi/{token}" if is_multi else f"{HAPP_IMPORT_BASE_URL}/{token}"


def home_keyboard(
    *,
    support_enabled: bool,
    support_url: str | None = None,
    trial_available: bool = False,
    renewal_available: bool = False,
) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    if trial_available:
        builder.button(text="🎁 Получить пробный доступ", callback_data="scenario:auto")
    builder.button(text="📦 Мои подписки", callback_data="subs")
    if renewal_available:
        builder.button(text="💎 Продлить", callback_data="renew_select")
    builder.button(text="💳 Купить доступ", callback_data="tariffs")
    builder.button(text="\U0001f91d \u041f\u0440\u0438\u0433\u043b\u0430\u0441\u0438\u0442\u044c \u0434\u0440\u0443\u0433\u0430", callback_data="referrals")
    builder.button(text="➕ Подключить устройство", callback_data="subs")
    if support_enabled:
        if support_url:
            builder.button(text="🛠 Исправить проблему", url=support_url)
        else:
            builder.button(text="🛠 Исправить проблему", callback_data="support")
    builder.button(text="Ещё", callback_data="more")
    builder.adjust(1, 2, 1, 1, 1, 1)
    return builder.as_markup()


def more_keyboard(*, support_url: str | None = None) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.button(text="Бесплатный Telegram-прокси", callback_data="free_proxy")
    builder.button(text="🤝 Пригласить друга", callback_data="referrals")
    builder.button(text="🛡️ О сервисе", callback_data="service")
    builder.button(text="📄 Политика", callback_data="policy")
    builder.button(text="📄 Условия", callback_data="terms")
    if support_url:
        builder.button(text="🆘 Поддержка", url=support_url)
    builder.button(text="← Главное меню", callback_data="home")
    builder.adjust(1, 1, 1, 2, 1, 1)
    return builder.as_markup()


def no_access_keyboard(
    *,
    support_enabled: bool,
    support_url: str | None = None,
    trial_available: bool = False,
) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    if trial_available:
        builder.button(text="📱 Для мобильного интернета", callback_data="scenario:mobile")
        builder.button(text="🌐 Для Wi-Fi и обычного интернета", callback_data="scenario:regular")
        builder.button(text="✨ Помочь выбрать", callback_data="scenario:auto")
    else:
        builder.button(text="💳 Выбрать тариф", callback_data="tariffs")
    builder.button(text="🔑 Уже есть подписка", callback_data="subs")
    if support_enabled:
        if support_url:
            builder.button(text="💬 Задать вопрос", url=support_url)
        else:
            builder.button(text="💬 Задать вопрос", callback_data="support")
    builder.button(text="Ещё", callback_data="service")
    builder.adjust(1)
    return builder.as_markup()


def scenario_keyboard(scenario: str, *, trial_available: bool) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    if scenario == "regular":
        tariff_id = "regular"
        component = "regular"
    elif scenario == "mobile":
        tariff_id = "lte_solo"
        component = "mobile"
    else:
        builder.button(text="📱 Нужен мобильный VPN", callback_data="scenario:mobile")
        builder.button(text="🌐 Нужен обычный VPN", callback_data="scenario:regular")
        builder.button(text="← Главное меню", callback_data="home")
        builder.adjust(1)
        return builder.as_markup()
    if trial_available:
        builder.button(text="🎁 Попробовать бесплатно", callback_data=f"trial:claim:{component}")
    builder.button(text="💳 Купить без пробника", callback_data=f"buy:{tariff_id}")
    builder.button(text="← Назад", callback_data="home")
    builder.adjust(1)
    return builder.as_markup()


def platform_keyboard(user_uuid: str) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    for platform, title in (("android", "Android"), ("ios", "iPhone / iPad"), ("windows", "Windows"), ("macos", "macOS")):
        builder.button(text=title, callback_data=f"platform:{platform}:{user_uuid}")
    builder.button(text="← Мои подписки", callback_data="subs")
    builder.adjust(2, 2, 1)
    return builder.as_markup()


def setup_keyboard(
    user_uuid: str,
    subscription_url: str | None,
    *,
    download_url: str | None = None,
) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    if download_url:
        builder.button(text="⬇️ Установить Happ из Google Play", url=download_url)
    if subscription_url:
        builder.button(text="🚀 Открыть подписку", url=subscription_url)
    builder.button(text="✅ Я подключил VPN", callback_data=f"connect_check:{user_uuid}")
    builder.button(text="🆘 Не получается подключить", callback_data=f"connect_help:{user_uuid}")
    builder.button(text="← Выбрать устройство", callback_data=f"platform_select:{user_uuid}")
    builder.adjust(1)
    return builder.as_markup()


def connection_guide_keyboard(
    step: int,
    user_uuid: str,
    *,
    happ_import_url: str | None = None,
) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    if step == 1:
        builder.button(
            text="📱 iOS (App Store)",
            url="https://apps.apple.com/us/app/happ-proxy-utility/id6504287215?l=ru",
        )
        builder.button(
            text="🤖 Android (Google Play)",
            url="https://play.google.com/store/apps/details?id=com.happproxy",
        )
        builder.button(text="Далее →", callback_data=f"guide:2:{user_uuid}")
        builder.button(text="← К подписке", callback_data=f"guide:back:{user_uuid}")
    elif step == 2:
        if happ_import_url:
            builder.button(text="🚀 Добавить подписку в Happ", url=happ_import_url)
        builder.button(text="← Назад", callback_data=f"guide:1:{user_uuid}")
        builder.button(text="Далее →", callback_data=f"guide:3:{user_uuid}")
    else:
        builder.button(text="❌ Не работает", callback_data=f"connect_help:{user_uuid}")
        builder.button(text="← Назад", callback_data=f"guide:2:{user_uuid}")
        builder.button(text="⌂ К началу", callback_data="home")
    builder.adjust(1)
    return builder.as_markup()


def support_diagnostics_keyboard(user_uuid: str, support_url: str | None) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    if support_url:
        builder.button(text="💬 Открыть поддержку", url=support_url)
    builder.button(text="🔄 Проверить подключение", callback_data=f"connect_check:{user_uuid}")
    builder.button(text="← Главное меню", callback_data="home")
    builder.adjust(1)
    return builder.as_markup()


def connection_result_keyboard(user_uuid: str, *, success: bool) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    if success:
        builder.button(text="💳 Сохранить доступ после пробника", callback_data="tariffs")
        builder.button(text="📦 Моя подписка", callback_data="subs")
    else:
        builder.button(text="🔄 Проверить ещё раз", callback_data=f"connect_check:{user_uuid}")
        builder.button(text="🆘 Нужна помощь", callback_data=f"connect_help:{user_uuid}")
    builder.button(text="← Главное меню", callback_data="home")
    builder.adjust(1)
    return builder.as_markup()


def referral_keyboard(telegram_id: int, bot_username: str) -> InlineKeyboardMarkup:
    username = bot_username.strip().lstrip("@") or "levikvpnbot"
    link = f"https://t.me/{username}?start=ref_{telegram_id}"
    share_text = "Попробуй Levik VPN бесплатно — бот поможет подключиться."
    share_url = f"https://t.me/share/url?url={quote(link, safe='')}&text={quote(share_text, safe='')}"
    builder = InlineKeyboardBuilder()
    builder.button(text="📤 Поделиться приглашением", url=share_url)
    builder.button(text="← Главное меню", callback_data="home")
    builder.adjust(1)
    return builder.as_markup()


def back_home_keyboard() -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.button(text="← Главное меню", callback_data="home")
    return builder.as_markup()


def cabinet_auth_confirm_keyboard(challenge_id: str) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.button(
        text="✅ Подтвердить вход",
        callback_data=f"cabinet_auth:confirm:{challenge_id}",
    )
    builder.button(
        text="❌ Отклонить вход",
        callback_data=f"cabinet_auth:deny:{challenge_id}",
    )
    builder.adjust(1)
    return builder.as_markup()


def trial_retry_keyboard(component: str = "regular") -> InlineKeyboardMarkup:
    safe_component = component if component in {"regular", "mobile"} else "regular"
    builder = InlineKeyboardBuilder()
    builder.button(text="🔄 Повторить выдачу", callback_data=f"trial:claim:{safe_component}")
    builder.button(text="← Главное меню", callback_data="home")
    builder.adjust(1)
    return builder.as_markup()


def trial_success_keyboard() -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.button(text="📦 Открыть подписки", callback_data="subs")
    builder.button(text="← Главное меню", callback_data="home")
    builder.adjust(1)
    return builder.as_markup()


def access_success_keyboard(
    user_uuid: str | None,
    *,
    subscription_url: str | None = None,
    offer_happ_routing: bool = False,
) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    import_url = happ_import_url(subscription_url) if subscription_url else None
    if import_url:
        builder.button(text="🚀 Подключить в Happ", url=import_url)
    if user_uuid:
        builder.button(text="📖 Инструкция", callback_data=f"instr_uuid:{user_uuid}")
    if offer_happ_routing:
        builder.button(text="🧭 Маршрутизация Happ", callback_data="happ_routing:offer")
    builder.button(text="← Главное меню", callback_data="home")
    builder.adjust(1)
    return builder.as_markup()


def happ_routing_confirm_keyboard() -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.button(text="✅ Подтвердить включение", callback_data="happ_routing:confirm")
    builder.button(text="↩️ Отмена", callback_data="happ_routing:offer")
    builder.adjust(1)
    return builder.as_markup()


def happ_routing_manage_keyboard() -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.button(text="⌂ Главное меню", callback_data="home")
    builder.adjust(1)
    return builder.as_markup()


def happ_routing_disable_confirm_keyboard() -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.button(text="⏸️ Подтвердить отключение", callback_data="happ_routing:disable_confirm")
    builder.button(text="↩️ Отмена", callback_data="happ_routing:offer")
    builder.adjust(1)
    return builder.as_markup()


def happ_routing_open_keyboard(config: dict[str, Any], *, enable: bool) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.button(text="↩️ К настройкам", callback_data="happ_routing:offer")
    builder.adjust(1)
    return builder.as_markup()


def subscriptions_keyboard(users: list[dict[str, Any]], timezone_name: str) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    for index, user in enumerate(users):
        expires_at = format_date(user.get("expireAt"), timezone_name)
        expires_text = "без срока" if expires_at == "без срока" else f"до {expires_at}"
        builder.button(text=f"📦 {plan_name(user)} · {expires_text}", callback_data=f"sub:{index}")
    builder.button(text="← Главное меню", callback_data="home")
    builder.adjust(1)
    return builder.as_markup()


def renewal_subscriptions_keyboard(users: list[tuple[int, dict[str, Any]]]) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    for index, user in users:
        title = plain_user_title(user, fallback=f"подписка {index + 1}")
        builder.button(text=f"💎 {plan_name(user)} · {title}", callback_data=f"renew:{index}")
    builder.button(text="← Главное меню", callback_data="home")
    builder.adjust(1)
    return builder.as_markup()


def keys_keyboard(users: list[dict[str, Any]], timezone_name: str) -> InlineKeyboardMarkup:
    return subscriptions_keyboard(users, timezone_name)


def subscription_keyboard(
    index: int,
    subscription_url: str | None,
    *,
    user_uuid: str | None = None,
    is_wdtt: bool = False,
    slots_enabled: bool = True,
    traffic_enabled: bool = False,
) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    if subscription_url and not is_wdtt:
        builder.button(text="🌐 Открыть подписку", url=subscription_url)
    if user_uuid and not is_wdtt:
        builder.button(text="➕ Подключить устройство", callback_data=f"platform_select:{user_uuid}")
    builder.button(text="🔑 Ключ", callback_data=f"key:{index}")
    builder.button(text="👤 Профиль", callback_data=f"profile:{index}")
    builder.button(text="📱 Устройства", callback_data=f"devices:{index}")
    builder.button(text="📖 Инструкция", callback_data=f"instructions:{index}")
    if not is_wdtt:
        builder.button(text="🛡 Levik Shield", callback_data=f"shield:{index}")
        builder.button(text="🧭 Маршрутизация Happ", callback_data="happ_routing:offer")
        builder.button(text="🔄 Обновить ключ", callback_data=f"refresh:{index}")
    if slots_enabled and not is_wdtt:
        builder.button(text="➕ Докупить слот", callback_data=f"slot:{index}")
    if traffic_enabled and not is_wdtt:
        builder.button(text="📊 Докупить трафик", callback_data=f"traffic:{index}")
    if not is_wdtt:
        builder.button(text="💎 Продлить", callback_data=f"renew:{index}")
    builder.button(text="← Мои подписки", callback_data="subs")
    builder.button(text="← Главное меню", callback_data="home")
    builder.adjust(1, 2, 2, 2, 1, 1)
    return builder.as_markup()


def shield_manage_keyboard(index: int, *, enabled: bool) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.button(
        text="Выключить Shield" if enabled else "Включить Shield",
        callback_data=f"shield:set:{index}:{0 if enabled else 1}",
    )
    builder.button(text="← К подписке", callback_data=f"sub:{index}")
    builder.adjust(1)
    return builder.as_markup()


def key_keyboard(index: int, subscription_url: str | None, *, is_wdtt: bool = False) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    if subscription_url and not is_wdtt:
        builder.button(text="🌐 Открыть подписку", url=subscription_url)
    builder.button(text="📖 Инструкция", callback_data=f"instructions:{index}")
    builder.button(text="📱 Устройства", callback_data=f"devices:{index}")
    if not is_wdtt:
        builder.button(text="🧭 Маршрутизация Happ", callback_data="happ_routing:offer")
        builder.button(text="🔄 Обновить ключ", callback_data=f"refresh:{index}")
    builder.button(text="← К подписке", callback_data=f"sub:{index}")
    builder.button(text="← Мои подписки", callback_data="subs")
    builder.button(text="← Главное меню", callback_data="home")
    builder.adjust(1)
    return builder.as_markup()


def refresh_confirm_keyboard(index: int) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.button(text="Да, обновить ключ", callback_data=f"refresh_yes:{index}")
    builder.button(text="Отмена", callback_data=f"sub:{index}")
    builder.adjust(1)
    return builder.as_markup()


def devices_keyboard(user_index: int, devices: list[dict[str, Any]]) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    for index, _device in enumerate(devices):
        builder.button(text=f"Отвязать устройство {index + 1}", callback_data=f"devdel:{user_index}:{index}")
    if devices:
        builder.button(text="Отвязать все устройства", callback_data=f"devdelall:{user_index}")
    builder.button(text="← К подписке", callback_data=f"sub:{user_index}")
    builder.button(text="← Главное меню", callback_data="home")
    builder.adjust(1)
    return builder.as_markup()


def device_delete_confirm_keyboard(user_index: int, device_index: int) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.button(text="Да, отвязать", callback_data=f"devdel_yes:{user_index}:{device_index}")
    builder.button(text="Отмена", callback_data=f"devices:{user_index}")
    builder.adjust(1)
    return builder.as_markup()


def device_delete_all_confirm_keyboard(user_index: int) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.button(text="Да, отвязать все", callback_data=f"devdelall_yes:{user_index}")
    builder.button(text="Отмена", callback_data=f"devices:{user_index}")
    builder.adjust(1)
    return builder.as_markup()


def tariffs_keyboard(tariffs: list[dict[str, Any]]) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    for tariff in tariffs:
        if isinstance(tariff, dict):
            title = str(tariff.get("title") or "Тариф")
            tariff_id = str(tariff.get("id") or "tariff")
            builder.button(text=f"Купить: {title}", callback_data=f"buy:{tariff_id[:24]}")
    builder.button(text="← Главное меню", callback_data="home")
    builder.adjust(1)
    return builder.as_markup()


def periods_keyboard(tariff_id: str, tariff: dict[str, Any], periods: list[dict[str, Any]]) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    for period in periods:
        if isinstance(period, dict):
            months = period_months(period)
            total = period_total(tariff, period)
            savings = period_savings(tariff, period)
            label = f"{period_title(period)} · {total:,} ₽".replace(",", " ")
            if savings > 0:
                label += f" · экономия {savings:,} ₽".replace(",", " ")
            builder.button(text=label, callback_data=f"period:{tariff_id[:24]}:{months}")
    builder.button(text="← Тарифы", callback_data="tariffs")
    builder.button(text="← Главное меню", callback_data="home")
    builder.adjust(1)
    return builder.as_markup()


def renewal_periods_keyboard(index: int, tariff: dict[str, Any], periods: list[dict[str, Any]]) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    for period in periods:
        if isinstance(period, dict):
            months = period_months(period)
            total = period_total(tariff, period)
            savings = period_savings(tariff, period)
            label = f"{period_title(period)} · {total:,} ₽".replace(",", " ")
            if savings > 0:
                label += f" · экономия {savings:,} ₽".replace(",", " ")
            builder.button(text=label, callback_data=f"rperiod:{index}:{months}")
    builder.button(text="← К подписке", callback_data=f"sub:{index}")
    builder.button(text="← Главное меню", callback_data="home")
    builder.adjust(1)
    return builder.as_markup()


def reminder_keyboard(reminder_id: int) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.button(text="💎 Продлить", callback_data=f"rem_renew:{reminder_id}")
    builder.button(text="⏰ Отложить", callback_data=f"rem_snooze:{reminder_id}")
    builder.button(text="Отказаться", callback_data=f"rem_stop:{reminder_id}")
    builder.adjust(1)
    return builder.as_markup()


def purchase_confirm_keyboard(
    tariff_id: str,
    months: int,
    config: dict[str, Any],
    tariff: dict[str, Any],
    period: dict[str, Any],
    amount_rub: int,
    discount_rub: int = 0,
) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    for method in payment_methods(config):
        method_id = str(method.get("id") or "")
        if method_id:
            builder.button(
                text=f"{payment_method_title(method)} — {rub(payment_method_amount(amount_rub, method))}",
                callback_data=f"aplat:{tariff_id[:24]}:{months}:{method_id[:16]}",
            )
    if telegram_stars_enabled(config):
        builder.button(text=f"⭐ Telegram Stars ({period_total_stars(config, tariff, period, discount_rub)} ⭐)", callback_data=f"astar:{tariff_id[:24]}:{months}")
    builder.button(text="← Выбрать период", callback_data=f"buy:{tariff_id[:24]}")
    builder.button(text="← Главное меню", callback_data="home")
    builder.adjust(2, 1, 1, 1)
    return builder.as_markup()


def renewal_confirm_keyboard(index: int, months: int, config: dict[str, Any], tariff: dict[str, Any], period: dict[str, Any]) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    amount_rub = period_total(tariff, period)
    for method in payment_methods(config):
        method_id = str(method.get("id") or "")
        if method_id:
            builder.button(
                text=f"{payment_method_title(method)} — {rub(payment_method_amount(amount_rub, method))}",
                callback_data=f"rplat:{index}:{months}:{method_id[:16]}",
            )
    if telegram_stars_enabled(config):
        builder.button(text=f"⭐ Оплатить Stars ({period_total_stars(config, tariff, period)} ⭐)", callback_data=f"rastar:{index}:{months}")
    builder.button(text="← Выбрать период", callback_data=f"renew:{index}")
    builder.button(text="← К подписке", callback_data=f"sub:{index}")
    builder.adjust(2, 1, 1, 1)
    return builder.as_markup()


def slot_payment_keyboard(index: int, config: dict[str, Any]) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    slots = config.get("slots") if isinstance(config.get("slots"), dict) else {}
    methods = slots.get("methods") if isinstance(slots.get("methods"), list) else []
    for method in methods:
        if not isinstance(method, dict):
            continue
        method_id = str(method.get("id") or "")
        title = str(method.get("title") or "Оплата")
        try:
            amount_rub = int(method.get("amount_rub") or 0)
        except (TypeError, ValueError):
            amount_rub = 0
        if method_id:
            label = f"{title} — {rub(amount_rub)}" if amount_rub > 0 else title
            builder.button(text=label, callback_data=f"slot_pay:{index}:{method_id[:16]}")
    if telegram_stars_enabled(config):
        builder.button(text=f"⭐ Telegram Stars ({slot_stars(config)} ⭐)", callback_data=f"slot_stars:{index}")
    builder.button(text="← К подписке", callback_data=f"sub:{index}")
    builder.adjust(2, 1, 1)
    return builder.as_markup()


def mobile_traffic_payment_keyboard(index: int, config: dict[str, Any]) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    traffic = mobile_traffic_config(config)
    methods = traffic.get("methods") if isinstance(traffic.get("methods"), list) else []
    for method in methods:
        if not isinstance(method, dict):
            continue
        method_id = str(method.get("id") or "")
        title = str(method.get("title") or "Оплата")
        try:
            amount_rub = int(method.get("amount_rub") or 0)
        except (TypeError, ValueError):
            amount_rub = 0
        if method_id:
            label = f"{title} — {rub(amount_rub)}" if amount_rub > 0 else title
            builder.button(text=label, callback_data=f"traffic_pay:{index}:{method_id[:16]}")
    if telegram_stars_enabled(config):
        builder.button(text=f"⭐ Telegram Stars ({mobile_traffic_stars(config)} ⭐)", callback_data=f"traffic_stars:{index}")
    builder.button(text="← К подписке", callback_data=f"sub:{index}")
    builder.adjust(2, 1, 1)
    return builder.as_markup()


def payment_cancel_reason_keyboard(order_id: int) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    reasons = (("price", "Цена выше ожидаемой"), ("error", "Не сработала оплата"), ("tariff", "Выбрал другой тариф"), ("later", "Оплачу позже"))
    for code, title in reasons:
        builder.button(text=title, callback_data=f"payreason:{order_id}:{code}")
    builder.button(text="🆘 Нужна помощь", callback_data="support")
    builder.button(text="← Тарифы", callback_data="tariffs")
    builder.adjust(1)
    return builder.as_markup()


def payment_link_keyboard(payment_url: str, order_id: int, back_callback: str = "home") -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.button(text="💳 Перейти к оплате", url=payment_url)
    builder.button(text="✅ Я оплатил", callback_data=f"checkpay:{order_id}")
    builder.button(text="← Назад", callback_data=back_callback)
    builder.adjust(1)
    return builder.as_markup()


def service_keyboard() -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.button(text="📖 Инструкция", callback_data="help_instructions")
    builder.button(text="📄 Политика", callback_data="policy")
    builder.button(text="📄 Условия", callback_data="terms")
    builder.button(text="← Главное меню", callback_data="home")
    builder.adjust(1, 2, 1)
    return builder.as_markup()


def admin_keyboard() -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.button(text="📊 Статистика", callback_data="admin:stats")
    builder.button(text="🔗 Источники трафика", callback_data="admin:links")
    builder.button(text="🎁 Выдать доступ", callback_data="admin:grant_help")
    builder.button(text="🧾 Последние операции", callback_data="admin:recent")
    builder.adjust(1)
    return builder.as_markup()
