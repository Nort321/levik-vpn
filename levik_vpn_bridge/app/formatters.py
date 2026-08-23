from __future__ import annotations

import html
import math
from datetime import datetime, timezone
from typing import Any
from zoneinfo import ZoneInfo

from app.multi_subscription import MULTI_PLAN, is_multi_user, mobile_user


GB = 1024**3


COUNTRIES = {
    "🇷🇺": "Россия",
    "🇩🇪": "Германия",
    "🇸🇪": "Швеция",
    "🇫🇮": "Финляндия",
    "🇬🇧": "Великобритания",
    "🇳🇱": "Нидерланды",
    "🇫🇷": "Франция",
    "🇵🇱": "Польша",
}

REGULAR_SQUADS = {"Levik Runtime Xray", "Levik Runtime Hysteria"}
MOBILE_SQUAD = "\u004c\u0054\u0045"
MOBILE_PLAN = "Мобильный VPN"


def esc(value: object) -> str:
    return html.escape(str(value), quote=False)


def plural_ru(count: int, one: str, few: str, many: str) -> str:
    value = abs(count)
    if value % 10 == 1 and value % 100 != 11:
        return one
    if 2 <= value % 10 <= 4 and not 12 <= value % 100 <= 14:
        return few
    return many


def rub(amount: int) -> str:
    return f"{amount:,}".replace(",", " ") + " ₽"


def rub_to_stars(config: dict[str, Any], amount_rub: int) -> int:
    payments = config.get("payments") if isinstance(config.get("payments"), dict) else {}
    pricing = payments.get("stars_pricing") if isinstance(payments.get("stars_pricing"), dict) else {}
    try:
        rub_per_star = float(pricing.get("rub_per_star") or 1.6)
    except (TypeError, ValueError):
        rub_per_star = 1.6
    try:
        markup_percent = float(pricing.get("markup_percent") or 15)
    except (TypeError, ValueError):
        markup_percent = 15
    rub_per_star = rub_per_star if rub_per_star > 0 else 1.6
    return max(1, math.ceil(amount_rub * (1 + markup_percent / 100) / rub_per_star))


def telegram_stars_enabled(config: dict[str, Any]) -> bool:
    payments = config.get("payments") if isinstance(config.get("payments"), dict) else {}
    return bool(payments.get("telegram_stars_enabled", True))


def payment_methods(config: dict[str, Any]) -> list[dict[str, Any]]:
    payments = config.get("payments") if isinstance(config.get("payments"), dict) else {}
    methods = payments.get("methods") if isinstance(payments.get("methods"), list) else []
    return [method for method in methods if isinstance(method, dict) and method.get("enabled", True)]


def payment_method(config: dict[str, Any], method_id: str) -> dict[str, Any] | None:
    for method in payment_methods(config):
        if str(method.get("id") or "") == method_id:
            return method
    return None


def payment_method_fee_percent(method: dict[str, Any]) -> int:
    try:
        return max(0, int(method.get("fee_percent") or 0))
    except (TypeError, ValueError):
        return 0


def payment_method_provider_fee_percent(method: dict[str, Any]) -> float:
    try:
        return max(0.0, float(method.get("provider_fee_percent") or 0))
    except (TypeError, ValueError):
        return 0.0


def payment_method_amount(amount_rub: int, method: dict[str, Any]) -> int:
    return max(1, math.ceil(amount_rub * (1 + payment_method_fee_percent(method) / 100)))


def payment_method_request_amount(amount_rub: int, method: dict[str, Any]) -> int | float:
    if not method.get("absorb_provider_fee", False):
        return payment_method_amount(amount_rub, method)

    fee_percent = payment_method_provider_fee_percent(method)
    if fee_percent <= 0:
        return amount_rub

    net_amount = amount_rub / (1 + fee_percent / 100)
    rounded = round(max(1.0, net_amount), 2)
    return int(rounded) if rounded.is_integer() else rounded


def payment_method_title(method: dict[str, Any]) -> str:
    return str(method.get("title") or "Оплата")


def referral_config(config: dict[str, Any]) -> dict[str, Any]:
    referrals = config.get("referrals")
    return referrals if isinstance(referrals, dict) else {}


def referral_discount_amount(total_rub: int, discount_percent: int) -> int:
    return max(0, math.floor(total_rub * max(0, discount_percent) / 100))


def parse_dt(value: object) -> datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def format_date(value: object, timezone_name: str) -> str:
    dt = parse_dt(value)
    if dt is None:
        return "без срока"
    local = dt.astimezone(ZoneInfo(timezone_name))
    if local.year >= 2099:
        return "без срока"
    return local.strftime("%d.%m.%Y")


def days_left(value: object) -> int | None:
    dt = parse_dt(value)
    if dt is None or dt.year >= 2099:
        return None
    now = datetime.now(timezone.utc)
    return max(0, (dt.astimezone(timezone.utc) - now).days)


def format_bytes(value: object) -> str:
    try:
        amount = float(value or 0)
    except (TypeError, ValueError):
        amount = 0

    if amount <= 0:
        return "0 МБ"
    if amount >= GB:
        return f"{amount / GB:.1f}".rstrip("0").rstrip(".") + " ГБ"
    return f"{amount / 1024**2:.0f} МБ"


def format_limit(value: object) -> str:
    try:
        amount = int(value or 0)
    except (TypeError, ValueError):
        amount = 0
    return "без лимита" if amount <= 0 else format_bytes(amount)


def status_label(user: dict[str, Any]) -> str:
    status = str(user.get("status") or "").upper()
    expire_at = parse_dt(user.get("expireAt"))
    expired = expire_at is not None and expire_at.year < 2099 and expire_at.astimezone(timezone.utc) <= datetime.now(timezone.utc)
    if status == "ACTIVE" and not expired:
        return "активна"
    if expired:
        return "истекла"
    if status == "DISABLED":
        return "отключена"
    return status.lower() or "неизвестно"


def active_squad_names(user: dict[str, Any]) -> set[str]:
    squads = user.get("activeInternalSquads")
    if not isinstance(squads, list):
        return set()

    names: set[str] = set()
    for squad in squads:
        if isinstance(squad, dict):
            name = squad.get("name")
            if name:
                names.add(str(name))
        elif squad:
            names.add(str(squad))
    return names


def plan_name(user: dict[str, Any]) -> str:
    if is_multi_user(user):
        return MULTI_PLAN
    marker_lower = " ".join(
        str(user.get(key) or "")
        for key in ("username", "tag", "description", "email")
    ).lower()
    if "[multi:primary]" in marker_lower:
        return MULTI_PLAN
    squads = active_squad_names(user)
    if MOBILE_SQUAD in squads and not REGULAR_SQUADS.issubset(squads):
        return MOBILE_PLAN
    if REGULAR_SQUADS.issubset(squads):
        return "Обычный VPN"
    if MOBILE_SQUAD in squads:
        return MOBILE_PLAN

    marker = " ".join(
        str(user.get(key) or "")
        for key in ("username", "tag", "description", "email")
    ).upper()
    try:
        limit = int(user.get("trafficLimitBytes") or 0)
    except (TypeError, ValueError):
        limit = 0
    if MOBILE_SQUAD in marker or (0 < limit <= 60 * GB):
        return MOBILE_PLAN
    return "Обычный VPN"


def plain_user_title(user: dict[str, Any], fallback: str = "ключ") -> str:
    return str(user.get("username") or user.get("email") or fallback)


def user_title(user: dict[str, Any], fallback: str = "ключ") -> str:
    return esc(plain_user_title(user, fallback))


def subscription_line(user: dict[str, Any], index: int, timezone_name: str) -> str:
    return (
        f"{index}. <b>{esc(plan_name(user))}</b> · {esc(plain_user_title(user, fallback=f'подписка {index}'))} · "
        f"{esc(status_label(user))}, до {esc(format_date(user.get('expireAt'), timezone_name))}"
    )


def country_summary(hosts: list[dict[str, Any]], *, include_mobile: bool = True) -> str:
    found: list[str] = []
    for host in hosts:
        if host.get("isDisabled"):
            continue
        remark = str(host.get("remark") or "")
        for flag, name in COUNTRIES.items():
            if not include_mobile and name == "Россия":
                continue
            if flag in remark and name not in found:
                found.append(name)
    if not found:
        found = ["Россия", "Германия", "Швеция", "Финляндия"]
    preferred = ["Россия", "Германия", "Швеция", "Финляндия", "Великобритания", "Нидерланды", "Франция", "Польша"]
    ordered = [name for name in preferred if name in found]
    ordered.extend(name for name in found if name not in ordered)
    return ", ".join(ordered)


def user_country_summary(user: dict[str, Any], hosts: list[dict[str, Any]]) -> str:
    plan = plan_name(user)
    if plan == MOBILE_PLAN:
        return "Россия"
    if plan == MULTI_PLAN:
        return country_summary(hosts)
    return country_summary(hosts, include_mobile=False)


def traffic_line(user: dict[str, Any]) -> str:
    traffic = user.get("userTraffic") if isinstance(user.get("userTraffic"), dict) else {}
    used = format_bytes(traffic.get("usedTrafficBytes"))
    limit = format_limit(user.get("trafficLimitBytes"))
    return f"{used} / {limit}"


def device_line(user: dict[str, Any], used_devices: int | None = None) -> str:
    limit = user.get("hwidDeviceLimit")
    try:
        limit_int = int(limit) if limit is not None else 0
    except (TypeError, ValueError):
        limit_int = 0
    used = "—" if used_devices is None else str(used_devices)
    if limit_int <= 0:
        return f"{used} / без лимита"
    return f"{used} / {limit_int}"


def device_limit(user: dict[str, Any]) -> int:
    try:
        return int(user.get("hwidDeviceLimit") or 0)
    except (TypeError, ValueError):
        return 0


def is_wdtt_user(user: dict[str, Any]) -> bool:
    return isinstance(user.get("_wdtt_access"), dict)


def home_text(user: dict[str, Any], hosts: list[dict[str, Any]], first_name: str, brand: str) -> str:
    plan = plan_name(user)
    status = status_label(user)
    countries = user_country_summary(user, hosts)
    speed = "до 250 Мбит/с"
    return (
        f"🛡️ <b>{esc(brand)}</b> — добро пожаловать, {esc(first_name)}!\n\n"
        f"💎 Подписка: <b>{esc(plan)}</b>, {esc(status)}.\n\n"
        f"🌍 Серверы: {esc(countries)}.\n"
        f"⚡ Скорость: <b>{speed}</b>"
    )


def account_text(
    users: list[dict[str, Any]],
    hosts: list[dict[str, Any]],
    first_name: str,
    brand: str,
    timezone_name: str,
) -> str:
    count = len(users)
    active_count = sum(1 for user in users if status_label(user) == "активна")
    subscription_word = plural_ru(count, "подписка", "подписки", "подписок")
    active_word = plural_ru(active_count, "активная", "активные", "активных")

    lines = [
        f"🛡️ <b>{esc(brand)}</b> — добро пожаловать, {esc(first_name)}!",
        "",
        f"У вас <b>{count}</b> {subscription_word}, из них <b>{active_count}</b> {active_word}.",
        "Откройте нужную подписку, чтобы посмотреть ключ, профиль или инструкцию.",
        "",
    ]
    for index, user in enumerate(users, start=1):
        lines.append(subscription_line(user, index, timezone_name))

    lines.extend(["", f"🌍 Доступные направления: <b>{esc(country_summary(hosts))}</b>"])
    return "\n".join(lines)


def no_access_text(telegram_id: int, brand: str, first_name: str = "") -> str:
    greeting = f", {esc(first_name)}" if first_name else ""
    return (
        f"🛡️ <b>{esc(brand)}</b>{greeting}\n\n"
        "Поможем подобрать и подключить VPN за несколько минут. "
        "Сначала выберите, где вам нужно стабильное подключение.\n\n"
        "Пробный период — <b>3 дня</b>, карта не нужна."
    )


def scenario_text(scenario: str) -> str:
    if scenario == "mobile":
        return (
            "📱 <b>Мобильный VPN Solo</b>\n\n"
            "Для обхода белых списков и глушения VPN в мобильных сетях.\n\n"
            "• 1 устройство\n"
            "• 50 ГБ в месяц\n"
            "• стабильная работа в Happ\n"
            "• 149 ₽ в месяц\n\n"
            "Пробный период: 3 дня и 1 ГБ, карта не нужна."
        )
    if scenario == "regular":
        return (
            "🌐 <b>Обычный VPN</b>\n\n"
            "Обычный VPN для Wi-Fi, домашнего и мобильного интернета. Подходит для мобильной сети, "
            "если оператор не применяет белые списки и глушение VPN.\n\n"
            "• до 5 устройств\n"
            "• безлимитный трафик\n"
            "• несколько серверных профилей\n"
            "• 100 ₽ в месяц\n\n"
            "Пробный период: 3 дня, карта не нужна."
        )
    return (
        "✨ <b>Какой VPN выбрать?</b>\n\n"
        "Если мобильный оператор ограничивает интернет белыми списками или глушит обычные VPN — "
        "выберите Мобильный VPN. Если таких ограничений нет, Обычный VPN подойдёт для Wi-Fi, "
        "домашнего и мобильного интернета."
    )


def trial_single_success_text(title: str, duration_days: int, traffic_limit_bytes: int) -> str:
    traffic = "безлимитный трафик" if traffic_limit_bytes <= 0 else f"{format_bytes(traffic_limit_bytes)} трафика"
    return (
        "🎁 <b>Пробный доступ активирован</b>\n\n"
        f"Тариф: <b>{esc(title)}</b>\n"
        f"Срок: <b>{duration_days} дня</b>\n"
        f"Лимит: <b>{esc(traffic)}</b>\n\n"
        "Теперь выберите устройство — бот покажет короткую инструкцию и поможет проверить подключение."
    )


def platform_setup_text(platform: str, user: dict[str, Any]) -> str:
    names = {"android": "Android", "ios": "iPhone / iPad", "windows": "Windows", "macos": "macOS"}
    name = names.get(platform, "устройство")
    mobile = plan_name(user) in {MOBILE_PLAN, MULTI_PLAN}
    client = "Happ" if mobile else "Happ или совместимый VPN-клиент"
    subscription_url = str(user.get("subscriptionUrl") or "")
    key_line = (
        f"\n\n🔐 Ссылка подписки (скопируйте её):\n<code>{esc(subscription_url)}</code>"
        if subscription_url
        else ""
    )


def connection_guide_text(step: int, user: dict[str, Any]) -> str:
    if step == 1:
        return (
            "📱 <b>Подключение VPN — шаг 1 из 3</b>\n"
            "━━━━━━━━━━━━\n\n"
            "Установите приложение <b>Happ</b>.\n\n"
            "Это бесплатный VPN-клиент. Выберите свою платформу ниже.\n\n"
            "<i>После установки приложения нажмите «Далее →».</i>"
        )

    if step == 2:
        subscription_url = str(user.get("subscriptionUrl") or "")
        return (
            "📋 <b>Подключение VPN — шаг 2 из 3</b>\n"
            "━━━━━━━━━━━━\n\n"
            "Скопируйте ключ и добавьте его в Happ.\n\n"
            f"👇 <b>Ваш ключ:</b>\n<code>{esc(subscription_url)}</code>\n\n"
            "<i>Нажмите на ключ выше — он скопируется автоматически, или нажмите кнопку ниже, "
            "чтобы сразу добавить подписку в Happ.</i>\n\n"
            "➡️ <b>Если добавляете вручную:</b>\n"
            "1. Откройте Happ.\n"
            "2. Нажмите «+» в правом верхнем углу.\n"
            "3. Выберите «Вставить из буфера обмена».\n"
            "4. Готово — подписка добавлена."
        )

    return (
        "🎉 <b>Готово! Шаг 3 из 3</b>\n"
        "━━━━━━━━━━━━\n\n"
        "VPN готов к подключению.\n\n"
        "Теперь в приложении Happ:\n"
        "• выберите любой сервер из списка;\n"
        "• нажмите кнопку подключения;\n"
        "• дождитесь зелёного индикатора.\n\n"
        "✨ Всё работает? Можно пользоваться интернетом через VPN.\n\n"
        "<i>Если что-то пошло не так — нажмите «Не работает» ниже.</i>"
    )
    return (
        f"📲 <b>Подключение на {esc(name)}</b>\n\n"
        f"1. Установите <b>{esc(client)}</b>.\n"
        "2. Нажмите «Открыть подписку» или импортируйте ссылку в приложение.\n"
        "3. Разрешите создание VPN-профиля и включите подключение.\n"
        "4. Вернитесь в бот и нажмите «Я подключил VPN».\n\n"
        + ("Мобильный VPN стабильно работает только в Happ." if mobile else "Если один профиль не подключается, обновите подписку в приложении.")
        + key_line
    )


def connection_check_text(user: dict[str, Any], *, connected: bool) -> str:
    if connected:
        return (
            "✅ <b>Всё работает</b>\n\n"
            f"Подписка <b>{esc(plan_name(user))}</b> уже передаёт трафик. "
            "Настройка завершена — повторно импортировать ключ не нужно."
        )
    return (
        "⏳ <b>Трафик пока не появился</b>\n\n"
        "Убедитесь, что VPN включён и в приложении выбран импортированный профиль. "
        "Иногда статистика обновляется в течение пары минут."
    )


def used_traffic_bytes(user: dict[str, Any]) -> int:
    traffic = user.get("userTraffic") if isinstance(user.get("userTraffic"), dict) else {}
    try:
        return max(0, int(traffic.get("usedTrafficBytes") or 0))
    except (TypeError, ValueError):
        return 0


def trial_success_text(duration_days: int, mobile_traffic_gb: int) -> str:
    return (
        "🎁 <b>Пробный доступ активирован</b>\n\n"
        f"Обычный VPN: <b>{duration_days} дня, безлимитный трафик</b>.\n"
        f"Мобильный VPN: <b>{duration_days} дня, {mobile_traffic_gb} ГБ трафика</b>.\n\n"
        "Обе подписки уже находятся в разделе «Мои подписки». "
        "Для проверки мобильного VPN используйте мобильную сеть в своём регионе."
    )


def trial_admin_notification_text(trial: dict[str, object]) -> str:
    username = str(trial.get("telegram_username") or "").strip().lstrip("@")
    first_name = str(trial.get("first_name") or "").strip()
    identity_parts = [f"@{username}" if username else "username отсутствует"]
    if first_name:
        identity_parts.append(first_name)
    identity = " · ".join(identity_parts)
    component = str(trial.get("selected_component") or "")
    title = "Мобильный VPN" if component == "mobile" else "Обычный VPN"
    user_uuid = trial.get("mobile_user_uuid") if component == "mobile" else trial.get("regular_user_uuid")
    return (
        "🎁 <b>Выдан пробный доступ</b>\n\n"
        f"Пользователь: <b>{esc(identity)}</b>\n"
        f"Telegram ID: <code>{esc(trial.get('telegram_id') or '—')}</code>\n"
        f"Тариф: <b>{title}</b>\n"
        f"Срок: <b>3 дня</b>\n\n"
        f"Подписка: <code>{esc(user_uuid or '—')}</code>"
    )


def referral_text(config: dict[str, Any], telegram_id: int, stats: dict[str, int]) -> str:
    referrals = referral_config(config)
    username = str(referrals.get("bot_username") or "levikvpnbot").lstrip("@")
    discount_percent = int(referrals.get("discount_percent") or 20)
    reward_days = int(referrals.get("reward_days") or 14)
    try:
        mobile_traffic_reward_gb = max(0, int(referrals.get("mobile_traffic_reward_bytes") or 10 * GB) // GB)
    except (TypeError, ValueError):
        mobile_traffic_reward_gb = 10
    link = f"https://t.me/{username}?start=ref_{telegram_id}"
    return (
        "🤝 <b>Реферальная программа</b>\n\n"
        f"Приглашённый друг получает <b>{discount_percent}% скидку на всю первую покупку</b>.\n"
        f"Вы получаете <b>+{reward_days} дней</b> Обычного VPN после его первой успешной оплаты.\n"
        f"Если у вас есть активный Мобильный VPN, он тоже получит <b>+{reward_days} дней</b>.\n\n"
        f"Для активного Мобильного VPN дополнительно начислим <b>+{mobile_traffic_reward_gb} ГБ</b> трафика.\n\n"
        "🔥 <b>Бонус растёт вместе со сроком покупки друга:</b>\n"
        "• 3 месяца — <b>21 день и 15 ГБ</b> (×1,5);\n"
        "• 6 месяцев и больше — <b>28 дней и 20 ГБ</b> (×2).\n\n"
        f"Приглашено: <b>{stats.get('total', 0)}</b>\n"
        f"Наград выдано: <b>{stats.get('rewarded', 0)}</b>\n\n"
        f"Ваша ссылка:\n<code>{esc(link)}</code>"
    )


def profile_text(user: dict[str, Any], hosts: list[dict[str, Any]], timezone_name: str) -> str:
    left = days_left(user.get("expireAt"))
    left_text = "без ограничения" if left is None else f"осталось {left} дн."
    countries = user_country_summary(user, hosts)
    lines = [
        "👤 <b>Профиль</b>\n\n"
        f"🆔 Telegram ID: <code>{esc(user.get('telegramId') or '—')}</code>\n"
        f"💎 Статус: <b>{esc(status_label(user))}</b>\n"
        f"📅 Действует до: <b>{esc(format_date(user.get('expireAt'), timezone_name))}</b> ({esc(left_text)})\n"
        f"🔑 Тариф: <b>{esc(plan_name(user))}</b>"
    ]
    if plan_name(user) == MULTI_PLAN:
        mobile = mobile_user(user) or {}
        lines.extend(
            [
                "",
                "🌐 <b>Обычный VPN</b>\nБезлимитный трафик · "
                f"до {esc(device_limit(user))} устройств",
                "",
                "📱 <b>Мобильный VPN</b>\n"
                f"{esc(traffic_line(user))} · до {esc(device_limit(mobile))} устройств",
            ]
        )
    elif not is_wdtt_user(user):
        lines.extend(["", f"📊 <b>Трафик</b>\n{esc(traffic_line(user))}"])
    lines.extend(["", f"🌍 <b>Доступные серверы</b>\n{esc(countries)}", "", "⚡ Скорость до 250 Мбит/с"])
    return "\n".join(lines)


def subscriptions_text(users: list[dict[str, Any]], timezone_name: str) -> str:
    lines = ["📦 <b>Мои подписки</b>", "", "Выберите подписку, с которой хотите работать:", ""]
    for index, user in enumerate(users, start=1):
        lines.append(subscription_line(user, index, timezone_name))
    return "\n".join(lines)


def subscription_text(
    user: dict[str, Any],
    hosts: list[dict[str, Any]],
    timezone_name: str,
    used_devices: int | None = None,
) -> str:
    left = days_left(user.get("expireAt"))
    left_text = "без ограничения" if left is None else f"осталось {left} дн."
    lines = [
        f"📦 <b>{esc(plan_name(user))}</b>\n\n"
        f"👤 Имя: <b>{user_title(user)}</b>\n"
        f"💎 Статус: <b>{esc(status_label(user))}</b>\n"
        f"📅 Действует до: <b>{esc(format_date(user.get('expireAt'), timezone_name))}</b> ({esc(left_text)})"
    ]
    if plan_name(user) == MULTI_PLAN:
        mobile = mobile_user(user) or {}
        regular_used = sum(1 for device in (user.get("_multi_devices") or []) if device.get("_multi_component") == "regular")
        mobile_used = sum(1 for device in (user.get("_multi_devices") or []) if device.get("_multi_component") == "mobile")
        lines.extend(
            [
                "🌐 Обычный VPN: <b>безлимит · "
                f"{regular_used if used_devices is not None else '—'} / {device_limit(user)} устройств</b>",
                "📱 Мобильный VPN: <b>"
                f"{esc(traffic_line(user))} · {mobile_used if used_devices is not None else '—'} / {device_limit(mobile)} устройств</b>",
            ]
        )
    elif is_wdtt_user(user):
        limit = device_limit(user)
        if limit > 0:
            lines.append(f"📱 Устройства: <b>до {limit} {plural_ru(limit, 'устройства', 'устройств', 'устройств')}</b>")
    else:
        lines.append(f"📊 Трафик: <b>{esc(traffic_line(user))}</b>")
        lines.append(f"📱 Устройства: <b>{esc(device_line(user, used_devices))}</b>")
    lines.extend(
        [
            f"🌍 Серверы: <b>{esc(user_country_summary(user, hosts))}</b>",
            "",
            "Выберите действие для этой подписки.",
        ]
    )
    return "\n".join(lines)


def subscription_reminder_text(user: dict[str, Any], timezone_name: str, remaining_days: int) -> str:
    day_word = plural_ru(remaining_days, "день", "дня", "дней")
    left_text = "сегодня" if remaining_days <= 0 else f"через {remaining_days} {day_word}"
    return (
        "⏳ <b>Подписка скоро закончится</b>\n\n"
        f"Подписка: <b>{esc(plan_name(user))}</b> · {user_title(user)}\n"
        f"Действует до: <b>{esc(format_date(user.get('expireAt'), timezone_name))}</b>\n"
        f"Окончание: <b>{esc(left_text)}</b>\n\n"
        "Продлите заранее, чтобы доступ не прерывался."
    )


def key_text(user: dict[str, Any], timezone_name: str) -> str:
    url = str(user.get("subscriptionUrl") or "")
    if plan_name(user) == MULTI_PLAN:
        mobile = mobile_user(user) or {}
        return (
            "🔑 <b>Единый ключ Мультиподписки</b>\n\n"
            "Один ключ добавляет в Happ обычные безлимитные и мобильные серверы.\n"
            f"📅 Действителен до: <b>{esc(format_date(user.get('expireAt'), timezone_name))}</b>\n"
            f"🌐 Обычный VPN: <b>безлимит · до {esc(device_limit(user))} устройств</b>\n"
            f"📱 Мобильный VPN: <b>{esc(traffic_line(user))} · до {esc(device_limit(mobile))} устройств</b>\n\n"
            f"🔐 Единый ключ для Happ:\n<code>{esc(url)}</code>"
        )
    if plan_name(user) == MOBILE_PLAN:
        return (
            "🔑 <b>Ключ для Мобильного VPN</b>\n\n"
            "⚠️ Мобильный VPN стабильно работает только в Happ. За работу в других клиентах отвечать не можем.\n\n"
            f"👤 Имя: <b>{user_title(user)}</b>\n"
            f"📅 Действителен до: <b>{esc(format_date(user.get('expireAt'), timezone_name))}</b>\n"
            f"📊 Трафик: <b>{esc(traffic_line(user))}</b>\n\n"
            f"🔐 Ключ для Happ:\n<code>{esc(url)}</code>"
        )
    return (
        "🔑 <b>Информация о ключе</b>\n\n"
        f"📱 Тариф: <b>{esc(plan_name(user))}</b>\n"
        f"👤 Имя: <b>{user_title(user)}</b>\n"
        f"📅 Действителен до: <b>{esc(format_date(user.get('expireAt'), timezone_name))}</b>\n"
        f"📊 Трафик: <b>{esc(traffic_line(user))}</b>\n\n"
        f"🔐 Ключ:\n<code>{esc(url)}</code>"
    )


def instructions_text(config: dict[str, Any], user: dict[str, Any] | None = None) -> str:
    data = config.get("instructions", {}) if isinstance(config.get("instructions"), dict) else {}
    if user is not None and is_wdtt_user(user):
        key = "wdtt_body"
    elif user is not None and plan_name(user) == MULTI_PLAN:
        key = "multi_body"
    elif user is not None and plan_name(user) == MOBILE_PLAN:
        key = "mobile_body"
    else:
        key = "regular_body"
    body = str(data.get(key) or data.get("body") or "")
    body_html = esc(body)
    if user and not is_wdtt_user(user) and user.get("subscriptionUrl"):
        label = "Ваш единый ключ для Happ" if plan_name(user) == MULTI_PLAN else (
            "Ваш ключ для Happ" if plan_name(user) == MOBILE_PLAN else "Ваша ссылка подписки"
        )
        body_html += f"\n\n{label}:\n<code>{esc(user['subscriptionUrl'])}</code>"
    return f"📱 <b>{esc(data.get('title') or 'Подключение VPN')}</b>\n\n{body_html}"


def happ_routing_offer_text(config: dict[str, Any]) -> str:
    data = config.get("happ_routing") if isinstance(config.get("happ_routing"), dict) else {}
    description = str(
        data.get("description")
        or (
            "Профиль направляет российские сервисы и локальную сеть напрямую, "
            "а остальные сайты — через VPN. Он подходит для обычного и Мобильного VPN."
        )
    )
    return (
        "🧭 <b>Маршрутизация Happ</b>\n\n"
        f"{esc(description)}\n\n"
        "В новых версиях Happ routing хранится отдельно для каждой подписки. "
        "Профиль <b>Levik RU Direct</b> автоматически добавляется или обновляется вместе с этой подпиской.\n\n"
        "Чтобы применить изменения, обновите подписку в Happ и переподключите VPN. "
        "Включать и отключать routing теперь нужно в панели управления выбранной подписки внутри Happ."
    )


def happ_routing_enable_confirm_text(config: dict[str, Any]) -> str:
    data = config.get("happ_routing") if isinstance(config.get("happ_routing"), dict) else {}
    profile_name = str(data.get("profile_name") or "Levik RU Direct")
    return (
        "✅ <b>Включить маршрутизацию?</b>\n\n"
        f"Профиль <b>{esc(profile_name)}</b> будет добавлен и сразу активирован в Happ. "
        "Одноимённый профиль обновится, остальные ваши профили не удаляются.\n\n"
        "Старый профиль <b>Levik LTE</b> можно удалить — он больше не появится автоматически."
    )


def happ_routing_disable_confirm_text() -> str:
    return (
        "⏸️ <b>Отключить routing в Happ?</b>\n\n"
        "Happ перестанет применять любые routing-профили. VPN-подписка, серверы и ключ останутся без изменений."
    )


def happ_routing_ready_text(*, enable: bool) -> str:
    return (
        "🧭 <b>Маршрутизация перенесена в подписку</b>\n\n"
        "Обновите нужную подписку в Happ и переподключите VPN. "
        "После обновления управляйте routing в панели этой подписки внутри приложения."
    )


def devices_text(user: dict[str, Any], devices: list[dict[str, Any]]) -> str:
    if plan_name(user) == MULTI_PLAN:
        mobile = mobile_user(user) or {}
        regular_used = sum(1 for device in devices if device.get("_multi_component") == "regular")
        mobile_used = sum(1 for device in devices if device.get("_multi_component") == "mobile")
        user["_multi_devices"] = devices
        lines = [
            "📱 <b>Устройства Мультиподписки</b>",
            "",
            f"🌐 Обычные серверы: <b>{regular_used} / {device_limit(user)}</b>",
            f"📱 Мобильные серверы: <b>{mobile_used} / {device_limit(mobile)}</b>",
            "",
            "Одно физическое устройство может отображаться в обеих частях подписки.",
        ]
        if not devices:
            return "\n".join(lines + ["", "Пока нет привязанных устройств."])
        lines.extend(["", "Перед отвязкой удалите подписку из Happ на этом устройстве.", ""])
        for index, device in enumerate(devices, start=1):
            lines.append(f"{index}. {esc(device_name(device))}")
        return "\n".join(lines)
    lines = [
        "📱 <b>Мои устройства</b>",
        "",
        f"📶 Слоты: <b>{esc(device_line(user, len(devices)))}</b>",
        "",
        "Здесь показаны устройства, на которых сейчас активен ваш ключ.",
    ]
    if not devices:
        lines.extend(["", "Пока нет привязанных устройств."])
        return "\n".join(lines)

    lines.extend(
        [
            "",
            "⚠️ Перед отвязкой сначала удалите подписку из VPN-клиента на этом устройстве. Иначе устройство может занять слот снова.",
            "",
        ]
    )
    for index, device in enumerate(devices, start=1):
        lines.append(f"{index}. {esc(device_name(device))}")
    return "\n".join(lines)


def device_name(device: dict[str, Any]) -> str:
    component = str(device.get("_multi_component") or "")
    component_prefix = "Обычный · " if component == "regular" else ("Мобильный · " if component == "mobile" else "")
    pieces = []
    for key in ("deviceModel", "model", "deviceName", "platform", "os", "osVersion"):
        value = device.get(key)
        if value and str(value) not in pieces:
            pieces.append(str(value))
    if pieces:
        return component_prefix + " · ".join(pieces)

    device_id = str(device.get("device_id") or "")
    if device_id:
        title = f"устройство {device_id[:6]}…{device_id[-4:]}" if len(device_id) > 12 else f"устройство {device_id}"
        ip = str(device.get("ip") or "").strip()
        traffic = format_bytes(int(device.get("up_bytes") or 0) + int(device.get("down_bytes") or 0))
        details = [title]
        if ip:
            details.append(f"IP {ip}")
        if traffic != "0 МБ":
            details.append(f"трафик {traffic}")
        return component_prefix + " · ".join(details)

    hwid = str(device.get("hwid") or "")
    if len(hwid) > 12:
        return component_prefix + f"устройство {hwid[:6]}…{hwid[-4:]}"
    return component_prefix + (hwid or "устройство")


def slots_config(config: dict[str, Any]) -> dict[str, Any]:
    slots = config.get("slots")
    return slots if isinstance(slots, dict) else {}


def slot_amount(config: dict[str, Any]) -> int:
    try:
        return max(1, int(slots_config(config).get("amount") or 1))
    except (TypeError, ValueError):
        return 1


def slot_price_rub(config: dict[str, Any]) -> int:
    try:
        return max(1, int(slots_config(config).get("price_rub") or 65))
    except (TypeError, ValueError):
        return 65


def slot_traffic_delta_bytes(config: dict[str, Any]) -> int:
    try:
        return max(0, int(slots_config(config).get("traffic_delta_bytes") or 0))
    except (TypeError, ValueError):
        return 0


def slot_stars(config: dict[str, Any]) -> int:
    return rub_to_stars(config, slot_price_rub(config))


def slot_purchase_text(config: dict[str, Any], user: dict[str, Any]) -> str:
    amount = slot_amount(config)
    limit = device_limit(user)
    next_limit = limit + amount if limit > 0 else amount
    slot_word = plural_ru(amount, "слот", "слота", "слотов")
    is_multi = plan_name(user) == MULTI_PLAN
    traffic_delta = slot_traffic_delta_bytes(config) if plan_name(user) in {MOBILE_PLAN, MULTI_PLAN} else 0
    traffic_note = f" и +{format_bytes(traffic_delta)} трафика" if traffic_delta > 0 else ""
    notice = str(slots_config(config).get("commission_notice") or "")
    lines = [
        f"📱 <b>+{amount} {slot_word}{esc(traffic_note)}</b>",
        f"Стоимость: <b>{esc(rub(slot_price_rub(config)))}</b>",
        "",
        f"Подписка: <b>{user_title(user)}</b>",
        (
            f"Лимиты после оплаты: <b>обычный {limit} → {next_limit}; "
            f"мобильный {device_limit(mobile_user(user) or {})} → {device_limit(mobile_user(user) or {}) + amount}</b>"
            if is_multi
            else f"Лимит после оплаты: <b>{next_limit}</b>"
        ),
        "Докупленные слоты сохраняются за этой подпиской без ограничения по сроку.",
    ]
    if traffic_delta > 0:
        lines.extend(
            [
                f"Трафик после оплаты увеличится на <b>{esc(format_bytes(traffic_delta))}</b>.",
                "Дополнительный трафик действует до конца текущего оплаченного периода подписки.",
            ]
        )
    lines.extend(
        [
            "",
            "💳 <b>Выбери способ оплаты:</b>",
        ]
    )
    if notice:
        lines.extend(["", f"<i>{esc(notice)}</i>"])
    return "\n".join(lines)


def slot_platega_amount(config: dict[str, Any], method: dict[str, Any]) -> int:
    return payment_method_amount(slot_price_rub(config), method)


def slot_unlimited_text(user: dict[str, Any]) -> str:
    return (
        "📱 <b>Слоты устройств</b>\n\n"
        f"Подписка: <b>{user_title(user)}</b>\n"
        "На этой подписке сейчас нет ограничения по устройствам, докупать слоты не нужно."
    )


def slot_invoice_title(config: dict[str, Any]) -> str:
    amount = slot_amount(config)
    return f"+{amount} {plural_ru(amount, 'слот', 'слота', 'слотов')} Levik VPN"


def slot_invoice_description(config: dict[str, Any], user: dict[str, Any]) -> str:
    amount = slot_amount(config)
    slot_word = plural_ru(amount, "слот", "слота", "слотов")
    traffic_delta = slot_traffic_delta_bytes(config) if plan_name(user) in {MOBILE_PLAN, MULTI_PLAN} else 0
    traffic_text = f" и +{format_bytes(traffic_delta)}" if traffic_delta > 0 else ""
    return f"Докупка +{amount} {slot_word}{traffic_text} для подписки {plain_user_title(user)}"


def slot_success_text(
    config: dict[str, Any],
    user: dict[str, Any],
    old_limit: int,
    new_limit: int,
    *,
    old_traffic_bytes: int | None = None,
    new_traffic_bytes: int | None = None,
    expires_at: object | None = None,
) -> str:
    amount = slot_amount(config)
    slot_word = plural_ru(amount, "слот", "слота", "слотов")
    lines = [
        "✅ <b>Оплата прошла успешно</b>",
        "",
        f"Подписка: <b>{user_title(user)}</b>",
        f"Добавлено: <b>+{amount} {slot_word}</b>",
        f"Устройства: <b>{old_limit} → {new_limit}</b>",
        "Докупленные слоты сохраняются за этой подпиской без ограничения по сроку.",
    ]
    if old_traffic_bytes is not None and new_traffic_bytes is not None and new_traffic_bytes > old_traffic_bytes:
        lines.append(f"Трафик: <b>{esc(format_bytes(old_traffic_bytes))} → {esc(format_bytes(new_traffic_bytes))}</b>")
    if expires_at is not None:
        lines.append("Дополнительный трафик действует до конца текущего оплаченного периода подписки.")
    return "\n".join(lines)


def multi_slot_success_text(
    config: dict[str, Any],
    user: dict[str, Any],
    *,
    old_regular_limit: int,
    new_regular_limit: int,
    old_mobile_limit: int,
    new_mobile_limit: int,
    old_traffic_bytes: int,
    new_traffic_bytes: int,
) -> str:
    amount = slot_amount(config)
    lines = [
        "✅ <b>Оплата прошла успешно</b>",
        "",
        f"Подписка: <b>{user_title(user)}</b>",
        f"Добавлено: <b>+{amount} {plural_ru(amount, 'слот', 'слота', 'слотов')} в обе части</b>",
        f"🌐 Обычный VPN: <b>{old_regular_limit} → {new_regular_limit} устройств</b>",
        f"📱 Мобильный VPN: <b>{old_mobile_limit} → {new_mobile_limit} устройств</b>",
        "Докупленные слоты сохраняются за этой подпиской без ограничения по сроку.",
    ]
    if new_traffic_bytes > old_traffic_bytes:
        lines.append(
            f"📊 Мобильный трафик: <b>{esc(format_bytes(old_traffic_bytes))} → "
            f"{esc(format_bytes(new_traffic_bytes))}</b>"
        )
        lines.append("Дополнительный трафик действует до конца текущего оплаченного периода подписки.")
    return "\n".join(lines)


def mobile_traffic_config(config: dict[str, Any]) -> dict[str, Any]:
    value = config.get("mobile_traffic")
    return value if isinstance(value, dict) else {}


def mobile_traffic_enabled(config: dict[str, Any]) -> bool:
    return bool(mobile_traffic_config(config).get("enabled", True))


def mobile_traffic_amount_bytes(config: dict[str, Any]) -> int:
    try:
        return max(1, int(mobile_traffic_config(config).get("amount_bytes") or 20 * GB))
    except (TypeError, ValueError):
        return 20 * GB


def mobile_traffic_price_rub(config: dict[str, Any]) -> int:
    try:
        return max(1, int(mobile_traffic_config(config).get("price_rub") or 59))
    except (TypeError, ValueError):
        return 59


def mobile_traffic_stars(config: dict[str, Any]) -> int:
    return rub_to_stars(config, mobile_traffic_price_rub(config))


def mobile_traffic_purchase_text(config: dict[str, Any], user: dict[str, Any]) -> str:
    amount = mobile_traffic_amount_bytes(config)
    notice = str(mobile_traffic_config(config).get("commission_notice") or "")
    lines = [
        f"📊 <b>+{esc(format_bytes(amount))} трафика</b>",
        f"Стоимость: <b>{esc(rub(mobile_traffic_price_rub(config)))}</b>",
        "",
        f"Подписка: <b>{user_title(user)}</b>",
        f"Текущий лимит: <b>{esc(format_limit(user.get('trafficLimitBytes')))}</b>",
        "Дополнительный трафик действует до конца текущего оплаченного периода подписки.",
        "",
        "💳 <b>Выбери способ оплаты:</b>",
    ]
    if notice:
        lines.extend(["", f"<i>{esc(notice)}</i>"])
    return "\n".join(lines)


def mobile_traffic_platega_amount(config: dict[str, Any], method: dict[str, Any]) -> int:
    return payment_method_amount(mobile_traffic_price_rub(config), method)


def mobile_traffic_invoice_title(config: dict[str, Any]) -> str:
    return f"+{format_bytes(mobile_traffic_amount_bytes(config))} Levik VPN"


def mobile_traffic_invoice_description(config: dict[str, Any], user: dict[str, Any]) -> str:
    return f"Докупка +{format_bytes(mobile_traffic_amount_bytes(config))} для подписки {plain_user_title(user)}"


def mobile_traffic_success_text(user: dict[str, Any], old_traffic_bytes: int, new_traffic_bytes: int) -> str:
    return (
        "✅ <b>Оплата прошла успешно</b>\n\n"
        f"Подписка: <b>{user_title(user)}</b>\n"
        f"Трафик: <b>{esc(format_bytes(old_traffic_bytes))} → {esc(format_bytes(new_traffic_bytes))}</b>\n"
        "Дополнительный трафик действует до конца текущего оплаченного периода подписки."
    )


def mobile_addon_summary_lines(config: dict[str, Any]) -> list[str]:
    lines: list[str] = []
    if slots_config(config).get("enabled", True):
        slot_traffic = slot_traffic_delta_bytes(config)
        slot_suffix = f" и +{format_bytes(slot_traffic)}" if slot_traffic > 0 else ""
        lines.append(f"• +1 слот{slot_suffix} до конца периода — {rub(slot_price_rub(config))}.")
    if mobile_traffic_enabled(config):
        lines.append(
            f"• +{format_bytes(mobile_traffic_amount_bytes(config))} трафика до конца периода — "
            f"{rub(mobile_traffic_price_rub(config))}."
        )
    return lines


def tariff_base_price(tariff: dict[str, Any]) -> int:
    try:
        return int(tariff.get("base_price_rub") or 0)
    except (TypeError, ValueError):
        return 0


def period_months(period: dict[str, Any]) -> int:
    try:
        return max(1, int(period.get("months") or 1))
    except (TypeError, ValueError):
        return 1


def period_title(period: dict[str, Any]) -> str:
    months = period_months(period)
    return str(period.get("title") or f"{months} мес.")


def period_total(tariff: dict[str, Any], period: dict[str, Any]) -> int:
    months = period_months(period)
    configured = tariff.get("period_prices_rub") if isinstance(tariff.get("period_prices_rub"), dict) else {}
    try:
        price = int(configured.get(str(months)) or 0)
    except (TypeError, ValueError):
        price = 0
    return price if price > 0 else tariff_base_price(tariff) * months


def period_savings(tariff: dict[str, Any], period: dict[str, Any]) -> int:
    full_price = tariff_base_price(tariff) * period_months(period)
    return max(0, full_price - period_total(tariff, period))


def period_total_stars(config: dict[str, Any], tariff: dict[str, Any], period: dict[str, Any], discount_rub: int = 0) -> int:
    return rub_to_stars(config, max(1, period_total(tariff, period) - max(0, discount_rub)))


def tariffs_text(config: dict[str, Any]) -> str:
    tariffs = config.get("tariffs") if isinstance(config.get("tariffs"), list) else []
    periods = config.get("purchase_periods") if isinstance(config.get("purchase_periods"), list) else []
    period_titles = [period_title(period) for period in periods if isinstance(period, dict)]
    lines = ["💳 <b>Купить доступ</b>", ""]
    for tariff in tariffs:
        if not isinstance(tariff, dict):
            continue
        lines.append(f"• <b>{esc(tariff.get('title'))}</b> — {esc(tariff.get('price'))}")
        lines.append(esc(tariff.get("description") or ""))
        lines.append("")
    addon_lines = mobile_addon_summary_lines(config)
    if addon_lines:
        lines.append("<b>Дополнительно для Мобильного VPN:</b>")
        lines.extend(esc(line) for line in addon_lines)
        lines.append("")
    if period_titles:
        lines.append("Доступные периоды: " + esc(", ".join(period_titles)) + ".")
        lines.append("Скидка за период: около 3% на 3 месяца, 5% на 6 месяцев и 8% на 12 месяцев.")
        lines.append("Для мобильных тарифов без пробника первая покупка доступна на 1 месяц. После успешного пробника открываются все периоды.")
        lines.append("Итоговая сумма показывается перед оплатой.")
        lines.append("")
    payments = config.get("payments") if isinstance(config.get("payments"), dict) else {}
    if not payments.get("enabled"):
        lines.append(esc(payments.get("unavailable_text") or "Оплата временно недоступна."))
    return "\n".join(lines).strip()


def purchase_period_text(
    tariff: dict[str, Any],
    *,
    kind: str = "purchase",
    user: dict[str, Any] | None = None,
    config: dict[str, Any] | None = None,
) -> str:
    action = "Продление подписки" if kind == "renewal" else "Покупка доступа"
    title = str(tariff.get("title") or "Тариф")
    lines = [
        f"💳 <b>{esc(action)}</b>",
        "",
        f"Тариф: <b>{esc(title)}</b>",
        esc(tariff.get("description") or ""),
    ]
    if tariff.get("purchase_period_months") == [1]:
        if lines and lines[-1] != "":
            lines.append("")
        lines.append("Без пробника первая покупка доступна на 1 месяц. После успешной проверки пробника открываются все периоды.")
    if config is not None and (str(tariff.get("id") or "").startswith("lte") or str(tariff.get("id") or "") == "multi"):
        addon_lines = mobile_addon_summary_lines(config)
        if addon_lines:
            lines.extend(["", "<b>Докупки после покупки:</b>"])
            lines.extend(esc(line) for line in addon_lines)
    if user is not None:
        lines.extend(["", f"Подписка: <b>{user_title(user)}</b>"])
    lines.extend(["", "Выберите период:"])
    return "\n".join(lines)


def purchase_confirm_text(
    tariff: dict[str, Any],
    period: dict[str, Any],
    config: dict[str, Any] | None = None,
    *,
    kind: str = "purchase",
    user: dict[str, Any] | None = None,
    discount_percent: int = 0,
    discount_rub: int = 0,
) -> str:
    action = "Продление" if kind == "renewal" else "Покупка"
    base_total = period_total(tariff, period)
    total = max(1, base_total - max(0, discount_rub))
    effective_config = config or {}
    lines = [
        f"🧾 <b>{esc(action)} доступа</b>",
        "",
        f"Тариф: <b>{esc(tariff.get('title') or 'Тариф')}</b>",
        f"Период: <b>{esc(period_title(period))}</b>",
    ]
    savings = period_savings(tariff, period)
    if savings > 0:
        lines.append(f"Скидка за период: <b>−{esc(rub(savings))}</b>")
    if discount_rub > 0:
        lines.append(f"Скидка по приглашению: <b>{discount_percent}% от всей покупки · −{esc(rub(discount_rub))}</b>")
    lines.extend(
        [
            f"Стоимость сервиса: <b>{esc(rub(total))}</b>",
            "",
            "💳 <b>Выберите способ оплаты:</b>",
            "",
            "<i>Комиссия зависит от способа оплаты. Точная итоговая сумма уже указана на каждой кнопке.</i>",
        ]
    )
    if telegram_stars_enabled(effective_config):
        lines.append(f"Telegram Stars: <b>{rub_to_stars(effective_config, total)} ⭐</b>")
    if user is not None:
        lines.append(f"Подписка: <b>{user_title(user)}</b>")
    if kind == "renewal":
        lines.extend(["", "После успешной оплаты бот автоматически продлит эту подписку."])
    else:
        lines.extend(["", "После успешной оплаты бот автоматически создаст доступ и отправит ключ в этот чат."])
    return "\n".join(lines)


def access_invoice_title(tariff: dict[str, Any], period: dict[str, Any]) -> str:
    return f"{tariff.get('title') or 'VPN'} · {period_title(period)}"


def access_invoice_description(tariff: dict[str, Any], period: dict[str, Any], *, renewal: bool = False) -> str:
    action = "Продление" if renewal else "Покупка"
    return f"{action} доступа Levik VPN: {tariff.get('title') or 'Тариф'} на {period_title(period)}"


def access_payment_received_text(order_id: int, tariff_title: str, period_months_value: int, *, renewal: bool) -> str:
    action = "продления" if renewal else "покупки"
    period_text = f"{period_months_value} мес."
    return (
        "✅ <b>Оплата прошла успешно</b>\n\n"
        f"Номер оплаты: <code>#{order_id}</code>\n"
        f"Тип: <b>{esc(action)}</b>\n"
        f"Тариф: <b>{esc(tariff_title)}</b>\n"
        f"Период: <b>{esc(period_text)}</b>\n\n"
        "Бот автоматически выдаст или продлит доступ после обработки оплаты."
    )


def payment_link_text(order_id: int, method_title: str, amount_rub: int) -> str:
    return (
        "✅ <b>Счёт создан</b>\n\n"
        f"Номер заказа: <code>#{order_id}</code>\n"
        f"Способ оплаты: <b>{esc(method_title)}</b>\n"
        f"К оплате: <b>{esc(rub(amount_rub))}</b>\n\n"
        "После оплаты нажмите «Я оплатил», если доступ не пришёл автоматически в течение минуты."
    )


def service_text(config: dict[str, Any], hosts: list[dict[str, Any]]) -> str:
    service = config.get("service") if isinstance(config.get("service"), dict) else {}
    lines = ["🛡️ <b>Levik VPN — о сервисе</b>", "", esc(service.get("about") or "")]
    features = service.get("features")
    if isinstance(features, list):
        lines.append("")
        for item in features:
            lines.append(f"• {esc(item)}")
    support = config.get("support") if isinstance(config.get("support"), dict) else {}
    support_text = str(support.get("text") or "").strip()
    if support_text:
        lines.extend(["", f"🆘 Поддержка: {esc(support_text)}"])
    lines.extend(["", f"🌍 Сейчас доступны: <b>{esc(country_summary(hosts))}</b>"])
    return "\n".join(lines)


def legal_text(config: dict[str, Any], key: str) -> str:
    legal = config.get("legal") if isinstance(config.get("legal"), dict) else {}
    text = str(legal.get(key) or "Документ пока не настроен.")
    if key == "terms" and legal.get("multi_terms"):
        text += "\n\n" + str(legal["multi_terms"])
    return esc(text)
