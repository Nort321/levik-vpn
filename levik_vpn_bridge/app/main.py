from __future__ import annotations

import asyncio
import logging
import re
import signal
from collections.abc import Awaitable, Callable
from typing import Any

from aiogram import BaseMiddleware, Bot, Dispatcher
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.types import BotCommand, CallbackQuery, Message, PreCheckoutQuery, TelegramObject

from app.config import ConfigError, load_settings
from app.handlers import router
from app.orders import OrderStore
from app.reminders import reminder_loop
from app.remnawave import RemnawaveClient
from app.webhook import start_payment_webhook


class ActivityMiddleware(BaseMiddleware):
    async def __call__(
        self,
        handler: Callable[[TelegramObject, dict[str, Any]], Awaitable[Any]],
        event: TelegramObject,
        data: dict[str, Any],
    ) -> Any:
        order_store = data.get("order_store")
        if isinstance(order_store, OrderStore):
            user = getattr(event, "from_user", None)
            if user is not None:
                if isinstance(event, Message):
                    event_type = "message"
                elif isinstance(event, CallbackQuery):
                    event_type = "callback_query"
                elif isinstance(event, PreCheckoutQuery):
                    event_type = "pre_checkout_query"
                else:
                    event_type = "update"
                order_store.record_user_activity(
                    telegram_id=user.id,
                    username=user.username,
                    first_name=user.first_name,
                    event_type=event_type,
                )
                if isinstance(event, CallbackQuery):
                    callback_group = str(event.data or "unknown").split(":", 1)[0][:32]
                    order_store.record_event(
                        telegram_id=user.id,
                        event_name="callback_clicked",
                        properties={"group": callback_group},
                    )
                elif isinstance(event, PreCheckoutQuery):
                    order_store.record_event(telegram_id=user.id, event_name="pre_checkout")
        return await handler(event, data)


def _account_alias_event_is_authentication(event_kind: str, value: str) -> bool:
    if event_kind == "message":
        return bool(
            re.fullmatch(
                r"/start(?:@[A-Za-z0-9_]+)?\s+web_[A-Za-z0-9_-]{16,128}",
                value.strip(),
            )
        )
    if event_kind == "callback_query":
        return value.startswith("cabinet_auth:")
    return False


class AccountAliasGuardMiddleware(BaseMiddleware):
    """Keep a late-linked Telegram identity authentication-only."""

    async def __call__(
        self,
        handler: Callable[[TelegramObject, dict[str, Any]], Awaitable[Any]],
        event: TelegramObject,
        data: dict[str, Any],
    ) -> Any:
        order_store = data.get("order_store")
        user = getattr(event, "from_user", None)
        if (
            not isinstance(order_store, OrderStore)
            or user is None
            or order_store.canonical_cabinet_actor(user.id) == user.id
        ):
            return await handler(event, data)

        if isinstance(event, Message):
            if _account_alias_event_is_authentication(
                "message",
                str(event.text or ""),
            ):
                return await handler(event, data)
            await event.answer(
                "Покупки и управление подпиской для этого Levik Account "
                "доступны в личном кабинете: https://leviknet.com/dashboard"
            )
            return None
        if isinstance(event, CallbackQuery):
            if _account_alias_event_is_authentication(
                "callback_query",
                str(event.data or ""),
            ):
                return await handler(event, data)
            await event.answer(
                "Откройте личный кабинет Levik Account для этого действия.",
                show_alert=True,
            )
            return None
        if isinstance(event, PreCheckoutQuery):
            await event.answer(
                ok=False,
                error_message=(
                    "Оплата для этого Levik Account доступна в личном кабинете."
                ),
            )
        return None


async def set_commands(bot: Bot) -> None:
    await bot.set_my_commands(
        [
            BotCommand(command="start", description="Главное меню"),
            BotCommand(command="profile", description="Профиль"),
            BotCommand(command="keys", description="Мои ключи"),
            BotCommand(command="devices", description="Мои устройства"),
            BotCommand(command="tariffs", description="Тарифы"),
            BotCommand(command="referrals", description="Рефералка"),
            BotCommand(command="paysupport", description="Поддержка по оплатам"),
        ]
    )


async def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    settings = load_settings()
    order_store = OrderStore(settings.data_dir)
    order_store.init()
    bot = Bot(
        token=settings.bot_token,
        default=DefaultBotProperties(parse_mode=ParseMode.HTML),
    )
    stop_event = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop_event.set)

    async with RemnawaveClient(settings) as remnawave:
        dispatcher = Dispatcher(settings=settings, remnawave=remnawave, order_store=order_store)
        activity_middleware = ActivityMiddleware()
        alias_guard_middleware = AccountAliasGuardMiddleware()
        dispatcher.message.middleware(activity_middleware)
        dispatcher.message.middleware(alias_guard_middleware)
        dispatcher.callback_query.middleware(activity_middleware)
        dispatcher.callback_query.middleware(alias_guard_middleware)
        dispatcher.pre_checkout_query.middleware(activity_middleware)
        dispatcher.pre_checkout_query.middleware(alias_guard_middleware)
        dispatcher.include_router(router)
        await set_commands(bot)
        await bot.delete_webhook(drop_pending_updates=True)
        webhook_runner = await start_payment_webhook(
            bot=bot,
            settings=settings,
            remnawave=remnawave,
            order_store=order_store,
        )
        polling_task = asyncio.create_task(dispatcher.start_polling(bot))
        reminder_task = asyncio.create_task(
            reminder_loop(
                bot=bot,
                settings=settings,
                remnawave=remnawave,
                order_store=order_store,
                stop_event=stop_event,
            )
        )
        stop_task = asyncio.create_task(stop_event.wait())
        done, pending = await asyncio.wait(
            {polling_task, reminder_task, stop_task},
            return_when=asyncio.FIRST_COMPLETED,
        )
        for task in pending:
            task.cancel()
        for task in done:
            task.result()
        if webhook_runner is not None:
            await webhook_runner.cleanup()
    await bot.session.close()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except ConfigError as exc:
        logging.basicConfig(level=logging.ERROR, format="%(asctime)s %(levelname)s %(name)s %(message)s")
        logging.error("configuration error: %s", exc)
        raise SystemExit(1) from exc
