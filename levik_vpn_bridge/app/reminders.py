from __future__ import annotations

import asyncio
import logging
import math
from datetime import datetime, timezone
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from aiogram import Bot
from aiogram.exceptions import TelegramAPIError, TelegramBadRequest, TelegramForbiddenError

from app.config import Settings
from app.delivery import reconcile_expired_subscription_addons
from app.formatters import parse_dt, subscription_reminder_text, trial_admin_notification_text
from app.keyboards import reminder_keyboard
from app.lifecycle import send_lifecycle_messages
from app.orders import OrderStore
from app.remnawave import RemnawaveClient
from app.multi_subscription import merge_users

logger = logging.getLogger(__name__)

DEFAULT_DAYS_BEFORE = 3
DEFAULT_CHECK_INTERVAL_SECONDS = 60 * 60


async def reminder_loop(
    *,
    bot: Bot,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    stop_event: asyncio.Event,
) -> None:
    config = _reminder_config(settings.data)
    if not config["enabled"]:
        logger.info("subscription reminders are disabled")

    interval = max(60, int(config["check_interval_seconds"]))
    while not stop_event.is_set():
        try:
            await send_pending_trial_admin_notifications(
                bot=bot,
                settings=settings,
                order_store=order_store,
            )
            await send_lifecycle_messages(
                bot=bot,
                settings=settings,
                remnawave=remnawave,
                order_store=order_store,
            )
            if config["enabled"]:
                await reconcile_expired_subscription_addons(
                    settings=settings,
                    remnawave=remnawave,
                    order_store=order_store,
                )
                await send_due_subscription_reminders(
                    bot=bot,
                    settings=settings,
                    remnawave=remnawave,
                    order_store=order_store,
                    days_before=max(1, int(config["days_before"])),
                )
        except Exception:
            logger.exception("subscription reminder scan failed")

        try:
            await asyncio.wait_for(stop_event.wait(), timeout=interval)
        except TimeoutError:
            pass
        except asyncio.TimeoutError:
            pass


async def send_due_subscription_reminders(
    *,
    bot: Bot,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    days_before: int = DEFAULT_DAYS_BEFORE,
) -> int:
    users = merge_users(
        settings,
        await remnawave.get_users(),
        order_store.list_active_multi_subscriptions(),
    )
    timezone_name = str(settings.data.get("timezone") or "Europe/Moscow")
    now = datetime.now(timezone.utc)
    today = _local_date(now, timezone_name)
    sent_count = 0
    trial_subscription_keys = _trial_subscription_keys(order_store)

    for user in users:
        telegram_id = _telegram_id(user.get("telegramId"))
        user_uuid = str(user.get("uuid") or "")
        expire_at = _expire_key(user.get("expireAt"))
        if telegram_id is None or not user_uuid or expire_at is None:
            continue
        if (user_uuid, expire_at) in trial_subscription_keys:
            continue
        remaining_days = _remaining_days(user.get("expireAt"), now, days_before)
        if remaining_days is None:
            continue
        if str(user.get("status") or "").upper() != "ACTIVE":
            continue

        reminder = order_store.ensure_subscription_reminder(
            telegram_id=telegram_id,
            user_uuid=user_uuid,
            expire_at=expire_at,
        )
        if reminder.get("declined_at"):
            continue
        if str(reminder.get("last_sent_date") or "") == today:
            continue

        reminder_id = int(reminder["id"])
        try:
            await bot.send_message(
                telegram_id,
                subscription_reminder_text(user, timezone_name, remaining_days),
                reply_markup=reminder_keyboard(reminder_id),
            )
        except (TelegramForbiddenError, TelegramBadRequest):
            order_store.decline_subscription_reminder(reminder_id)
            logger.warning("subscription reminder recipient is unavailable")
            continue
        except TelegramAPIError:
            logger.warning("subscription reminder send failed", exc_info=True)
            continue

        order_store.mark_subscription_reminder_sent(reminder_id, today)
        sent_count += 1

    if sent_count:
        logger.info("subscription reminders sent: %s", sent_count)
    return sent_count


def _trial_subscription_keys(order_store: OrderStore) -> set[tuple[str, str]]:
    keys: set[tuple[str, str]] = set()
    for trial in order_store.completed_trials():
        component = str(trial.get("selected_component") or "regular")
        user_uuid = str(
            trial.get("mobile_user_uuid") if component == "mobile" else trial.get("regular_user_uuid") or ""
        )
        expire_at = _expire_key(trial.get("expires_at"))
        if user_uuid and expire_at:
            keys.add((user_uuid, expire_at))
    return keys


async def send_pending_trial_admin_notifications(
    *,
    bot: Bot,
    settings: Settings,
    order_store: OrderStore,
) -> int:
    trial = settings.data.get("trial") if isinstance(settings.data.get("trial"), dict) else {}
    try:
        admin_telegram_id = int(trial.get("admin_telegram_id") or 0)
    except (TypeError, ValueError):
        admin_telegram_id = 0
    if admin_telegram_id <= 0:
        return 0

    sent_count = 0
    for access in order_store.pending_trial_admin_notifications():
        telegram_id = _telegram_id(access.get("telegram_id"))
        if telegram_id is None:
            continue
        try:
            await bot.send_message(admin_telegram_id, trial_admin_notification_text(access))
        except TelegramAPIError:
            logger.warning("trial admin notification send failed", exc_info=True)
            continue
        order_store.mark_trial_admin_notified(telegram_id)
        sent_count += 1

    if sent_count:
        logger.info("trial admin notifications sent: %s", sent_count)
    return sent_count


def _reminder_config(config: dict[str, Any]) -> dict[str, int | bool]:
    reminders = config.get("reminders") if isinstance(config.get("reminders"), dict) else {}
    return {
        "enabled": bool(reminders.get("enabled", True)),
        "days_before": _positive_int(reminders.get("days_before"), DEFAULT_DAYS_BEFORE),
        "check_interval_seconds": _positive_int(
            reminders.get("check_interval_seconds"),
            DEFAULT_CHECK_INTERVAL_SECONDS,
        ),
    }


def _positive_int(value: object, default: int) -> int:
    try:
        parsed = int(value) if value is not None else default
    except (TypeError, ValueError):
        return default
    return parsed if parsed > 0 else default


def _telegram_id(value: object) -> int | None:
    try:
        telegram_id = int(value or 0)
    except (TypeError, ValueError):
        return None
    return telegram_id if telegram_id > 0 else None


def _expire_key(value: object) -> str | None:
    expire_at = parse_dt(value)
    if expire_at is None or expire_at.year >= 2099:
        return None
    if expire_at.tzinfo is None:
        expire_at = expire_at.replace(tzinfo=timezone.utc)
    return expire_at.astimezone(timezone.utc).isoformat()


def _remaining_days(value: object, now: datetime, days_before: int) -> int | None:
    expire_at = parse_dt(value)
    if expire_at is None or expire_at.year >= 2099:
        return None
    if expire_at.tzinfo is None:
        expire_at = expire_at.replace(tzinfo=timezone.utc)
    delta_seconds = (expire_at.astimezone(timezone.utc) - now).total_seconds()
    if delta_seconds <= 0 or delta_seconds > days_before * 24 * 60 * 60:
        return None
    return max(0, math.ceil(delta_seconds / (24 * 60 * 60)))


def _local_date(value: datetime, timezone_name: str) -> str:
    try:
        tz = ZoneInfo(timezone_name)
    except ZoneInfoNotFoundError:
        tz = timezone.utc
    return value.astimezone(tz).date().isoformat()
