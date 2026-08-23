from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone
from typing import Any

from aiogram import Bot
from aiogram.exceptions import TelegramAPIError, TelegramBadRequest, TelegramForbiddenError

from app.config import Settings
from app.formatters import format_date, parse_dt, plan_name, traffic_line, used_traffic_bytes
from app.keyboards import back_home_keyboard, payment_link_keyboard, platform_keyboard, reminder_keyboard, tariffs_keyboard
from app.orders import OrderStore
from app.remnawave import RemnawaveClient
from app.multi_subscription import merge_users

logger = logging.getLogger(__name__)


async def send_lifecycle_messages(
    *,
    bot: Bot,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> int:
    config = settings.data.get("lifecycle") if isinstance(settings.data.get("lifecycle"), dict) else {}
    if not bool(config.get("enabled", True)):
        return 0

    sent = 0
    try:
        users = merge_users(
            settings,
            await remnawave.get_users(),
            order_store.list_active_multi_subscriptions(),
        )
    except Exception:
        logger.warning("lifecycle user scan failed", exc_info=True)
        return 0
    by_uuid = {str(user.get("uuid") or ""): user for user in users if isinstance(user, dict)}
    now = datetime.now(timezone.utc)
    active_user_uuids_by_telegram_id: dict[int, set[str]] = {}
    for user in users:
        telegram_id = _positive_int(user.get("telegramId"))
        user_uuid = str(user.get("uuid") or "")
        expires_at = parse_dt(user.get("expireAt"))
        if (
            telegram_id is None
            or not user_uuid
            or expires_at is None
            or str(user.get("status") or "").upper() != "ACTIVE"
        ):
            continue
        if expires_at.tzinfo is None:
            expires_at = expires_at.replace(tzinfo=timezone.utc)
        if expires_at.astimezone(timezone.utc) > now:
            active_user_uuids_by_telegram_id.setdefault(telegram_id, set()).add(user_uuid)

    for trial in order_store.completed_trials():
        telegram_id = _positive_int(trial.get("telegram_id"))
        completed_at = parse_dt(trial.get("completed_at"))
        expires_at = parse_dt(trial.get("expires_at"))
        if telegram_id is None or completed_at is None or expires_at is None:
            continue
        component = str(trial.get("selected_component") or "regular")
        user_uuid = str(trial.get("mobile_user_uuid") if component == "mobile" else trial.get("regular_user_uuid") or "")
        if any(
            active_user_uuid != user_uuid
            for active_user_uuid in active_user_uuids_by_telegram_id.get(telegram_id, set())
        ):
            continue
        user = by_uuid.get(user_uuid)
        has_traffic = bool(user and used_traffic_bytes(user) > 0)
        reference = str(trial.get("completed_at") or trial.get("created_at") or user_uuid)

        if has_traffic and not trial.get("first_traffic_at"):
            order_store.mark_trial_first_traffic(telegram_id)
            order_store.record_event(telegram_id=telegram_id, event_name="first_traffic", properties={"source": "lifecycle"})
            trial["first_traffic_at"] = now.isoformat()

        elapsed = now - completed_at.astimezone(timezone.utc)
        remaining = expires_at.astimezone(timezone.utc) - now
        no_traffic_verified = False
        if not has_traffic and user_uuid and timedelta(minutes=30) <= elapsed <= timedelta(hours=6):
            try:
                current_user = await remnawave.get_user_by_uuid(user_uuid)
            except Exception:
                logger.warning("trial traffic verification failed user_uuid=%s", user_uuid, exc_info=True)
            else:
                if current_user is not None:
                    user = current_user
                    has_traffic = used_traffic_bytes(user) > 0
                    no_traffic_verified = not has_traffic
                    if has_traffic and not trial.get("first_traffic_at"):
                        order_store.mark_trial_first_traffic(telegram_id)
                        order_store.record_event(
                            telegram_id=telegram_id,
                            event_name="first_traffic",
                            properties={"source": "lifecycle_detail"},
                        )
                        trial["first_traffic_at"] = now.isoformat()
        if no_traffic_verified:
            sent += await _send_once(
                bot=bot,
                order_store=order_store,
                telegram_id=telegram_id,
                kind="trial_no_traffic_30m",
                reference=reference,
                text=(
                    "📲 <b>Давайте закончим подключение</b>\n\n"
                    "Пробная подписка создана, но трафик пока не появился. "
                    "Выберите устройство — бот снова покажет инструкцию."
                ),
                reply_markup=platform_keyboard(user_uuid),
            )
        if has_traffic and timedelta(hours=20) <= elapsed <= timedelta(hours=36) and user is not None:
            sent += await _send_once(
                bot=bot,
                order_store=order_store,
                telegram_id=telegram_id,
                kind="trial_day_two",
                reference=reference,
                text=(
                    "✅ <b>Пробный VPN работает</b>\n\n"
                    f"Тариф: <b>{plan_name(user)}</b>\n"
                    f"Трафик: <b>{traffic_line(user)}</b>\n"
                    f"Доступ до: <b>{format_date(user.get('expireAt'), str(settings.data.get('timezone') or 'Europe/Moscow'))}</b>"
                ),
                reply_markup=tariffs_keyboard(_tariffs(settings)),
            )
        if timedelta(0) < remaining <= timedelta(hours=12):
            sent += await _send_once(
                bot=bot,
                order_store=order_store,
                telegram_id=telegram_id,
                kind="trial_expiring_12h",
                reference=reference,
                text=(
                    "⏳ <b>Пробный доступ заканчивается сегодня</b>\n\n"
                    "После оплаты текущий ключ и настройки сохранятся — подключать устройство заново не придётся."
                ),
                reply_markup=tariffs_keyboard(_tariffs(settings)),
            )
        if -timedelta(hours=24) <= remaining <= timedelta(0):
            sent += await _send_once(
                bot=bot,
                order_store=order_store,
                telegram_id=telegram_id,
                kind="trial_expired",
                reference=reference,
                text=(
                    "⏸️ <b>Пробный доступ завершён</b>\n\n"
                    "Ключ и настройки сохранены. Выберите тариф, чтобы восстановить подключение."
                ),
                reply_markup=tariffs_keyboard(_tariffs(settings)),
            )
        if -timedelta(days=8) <= remaining <= -timedelta(days=7) and has_traffic and not order_store.has_delivered_access_purchase(telegram_id):
            sent += await _send_once(
                bot=bot,
                order_store=order_store,
                telegram_id=telegram_id,
                kind="trial_winback_7d",
                reference=reference,
                text=(
                    "🎁 <b>Вернитесь в Levik VPN</b>\n\n"
                    "При покупке в течение 48 часов добавим <b>3 бонусных дня</b>. "
                    "Настраивать VPN заново не потребуется."
                ),
                reply_markup=tariffs_keyboard(_tariffs(settings)),
            )

    for order in order_store.pending_checkout_orders(older_than_minutes=15):
        telegram_id = _positive_int(order.get("telegram_id"))
        order_id = _positive_int(order.get("id"))
        payment_url = str(order.get("payment_url") or "")
        if telegram_id is None or order_id is None or not payment_url:
            continue
        sent += await _send_once(
            bot=bot,
            order_store=order_store,
            telegram_id=telegram_id,
            kind="checkout_recovery_15m",
            reference=str(order_id),
            text=(
                "💳 <b>Оплата не завершена</b>\n\n"
                f"Заказ №{order_id}: <b>{order.get('tariff_title') or 'Levik VPN'}</b>.\n"
                "Счёт ещё можно оплатить или создать новый, если ссылка больше не открывается."
            ),
            reply_markup=payment_link_keyboard(payment_url, order_id),
        )
        created_at = parse_dt(order.get("created_at"))
        first_sent_at = parse_dt(order_store.lifecycle_message_sent_at(telegram_id, "checkout_recovery_15m", str(order_id)))
        if (
            created_at is not None
            and first_sent_at is not None
            and now - created_at.astimezone(timezone.utc) >= timedelta(hours=6)
            and now - first_sent_at.astimezone(timezone.utc) >= timedelta(hours=5)
        ):
            sent += await _send_once(
                bot=bot,
                order_store=order_store,
                telegram_id=telegram_id,
                kind="checkout_recovery_6h",
                reference=str(order_id),
                text=(
                    "Нужна помощь с оплатой? Можно повторить СБП, выбрать другой способ "
                    "или обратиться в поддержку. Стоимость заказа не изменилась."
                ),
                reply_markup=payment_link_keyboard(payment_url, order_id),
            )

    for user in users:
        telegram_id = _positive_int(user.get("telegramId"))
        user_uuid = str(user.get("uuid") or "")
        expires_at = parse_dt(user.get("expireAt"))
        if telegram_id is None or not user_uuid or expires_at is None:
            continue
        if any(
            active_user_uuid != user_uuid
            for active_user_uuid in active_user_uuids_by_telegram_id.get(telegram_id, set())
        ):
            continue
        try:
            traffic_limit = int(user.get("trafficLimitBytes") or 0)
        except (TypeError, ValueError):
            traffic_limit = 0
        used_traffic = used_traffic_bytes(user)
        if (
            str(user.get("status") or "").upper() == "ACTIVE"
            and traffic_limit > 0
            and used_traffic >= int(traffic_limit * 0.8)
            and used_traffic < traffic_limit
        ):
            sent += await _send_once(
                bot=bot,
                order_store=order_store,
                telegram_id=telegram_id,
                kind="traffic_80_percent",
                reference=expires_at.astimezone(timezone.utc).isoformat(),
                text=(
                    "📊 <b>Заканчивается трафик</b>\n\n"
                    f"Использовано: <b>{traffic_line(user)}</b>. "
                    "Откройте подписку, чтобы докупить трафик или выбрать другой тариф."
                ),
                reply_markup=back_home_keyboard(),
            )
        if not order_store.has_delivered_access_purchase(telegram_id):
            continue
        expired_for = now - expires_at.astimezone(timezone.utc)
        if expired_for < timedelta(0):
            continue
        reminder = order_store.ensure_subscription_reminder(
            telegram_id=telegram_id,
            user_uuid=user_uuid,
            expire_at=expires_at.astimezone(timezone.utc).isoformat(),
        )
        reminder_id = int(reminder["id"])
        reference = expires_at.astimezone(timezone.utc).isoformat()
        if expired_for <= timedelta(hours=24):
            sent += await _send_once(
                bot=bot,
                order_store=order_store,
                telegram_id=telegram_id,
                kind="paid_expired",
                reference=reference,
                text=(
                    "⏸️ <b>Подписка закончилась</b>\n\n"
                    f"{plan_name(user)} приостановлен, но ключ и настройки сохранены. "
                    "После продления подключение восстановится без повторной настройки."
                ),
                reply_markup=reminder_keyboard(reminder_id),
            )
        if timedelta(days=7) <= expired_for <= timedelta(days=8):
            sent += await _send_once(
                bot=bot,
                order_store=order_store,
                telegram_id=telegram_id,
                kind="paid_winback_7d",
                reference=reference,
                text=(
                    "🎁 <b>Восстановите Levik VPN</b>\n\n"
                    "При продлении в течение 48 часов добавим <b>3 бонусных дня</b>."
                ),
                reply_markup=reminder_keyboard(reminder_id),
            )
    return sent


def _tariffs(settings: Settings) -> list[dict[str, Any]]:
    tariffs = settings.data.get("tariffs")
    return [item for item in tariffs if isinstance(item, dict)] if isinstance(tariffs, list) else []


def _positive_int(value: object) -> int | None:
    try:
        parsed = int(value or 0)
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None


async def _send_once(
    *,
    bot: Bot,
    order_store: OrderStore,
    telegram_id: int,
    kind: str,
    reference: str,
    text: str,
    reply_markup: Any,
) -> int:
    if order_store.lifecycle_message_sent(telegram_id, kind, reference):
        return 0
    try:
        await bot.send_message(telegram_id, text, reply_markup=reply_markup)
    except (TelegramForbiddenError, TelegramBadRequest) as exc:
        order_store.mark_lifecycle_message(
            telegram_id=telegram_id,
            message_kind=kind,
            reference_key=reference,
            sent=False,
            error_code=exc.__class__.__name__,
        )
        return 0
    except TelegramAPIError as exc:
        logger.warning("lifecycle message failed kind=%s", kind, exc_info=True)
        order_store.mark_lifecycle_message(
            telegram_id=telegram_id,
            message_kind=kind,
            reference_key=reference,
            sent=False,
            error_code=exc.__class__.__name__,
        )
        return 0
    order_store.mark_lifecycle_message(
        telegram_id=telegram_id,
        message_kind=kind,
        reference_key=reference,
        sent=True,
    )
    order_store.record_event(
        telegram_id=telegram_id,
        event_name="lifecycle_message_sent",
        properties={"kind": kind},
    )
    return 1
