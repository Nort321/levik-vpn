from __future__ import annotations

import argparse
import asyncio
import logging
import re
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from aiogram import Bot
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.exceptions import TelegramAPIError

from app.config import Settings, load_settings
from app.formatters import key_text, parse_dt
from app.orders import OrderStore
from app.remnawave import RemnawaveClient


logger = logging.getLogger(__name__)
MOBILE_TARIFF_ID = "lte"
NO_EXPIRE_AT = datetime(2099, 1, 1, tzinfo=timezone.utc)
REMNAWAVE_UUID_RE = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)


@dataclass(frozen=True)
class MigrationResult:
    source_user_uuid: str
    telegram_id: int
    remnawave_user: dict[str, Any]
    announcement_sent_at: str | None


def _find_tariff(settings: Settings, tariff_id: str) -> dict[str, Any]:
    tariffs = settings.data.get("tariffs")
    items = [tariff for tariff in tariffs if isinstance(tariff, dict)] if isinstance(tariffs, list) else []
    for tariff in items:
        if str(tariff.get("id") or "") == tariff_id:
            return tariff
    raise RuntimeError(f"tariff not found: {tariff_id}")


def _tariff_int(tariff: dict[str, Any], key: str, default: int) -> int:
    try:
        return int(tariff.get(key) if tariff.get(key) is not None else default)
    except (TypeError, ValueError):
        return default


def _tariff_strategy(tariff: dict[str, Any]) -> str:
    return str(tariff.get("traffic_limit_strategy") or "MONTH_ROLLING")


def _lte_external_squad_uuid(tariff: dict[str, Any]) -> str | None:
    names = tariff.get("internal_squads") if isinstance(tariff.get("internal_squads"), list) else []
    if [str(name) for name in names if name] != ["LTE"]:
        return None

    external_squad_uuid = str(tariff.get("external_squad_uuid") or "").strip()
    if not _is_remnawave_uuid(external_squad_uuid):
        raise RuntimeError("LTE tariff external squad UUID is missing or invalid")
    return external_squad_uuid


async def _internal_squad_uuids(remnawave: RemnawaveClient, tariff: dict[str, Any]) -> list[str]:
    names = tariff.get("internal_squads") if isinstance(tariff.get("internal_squads"), list) else []
    wanted = [str(name) for name in names if name]
    squads = await remnawave.get_internal_squads()
    by_name = {str(squad.get("name")): str(squad.get("uuid")) for squad in squads if squad.get("name") and squad.get("uuid")}
    missing = [name for name in wanted if name not in by_name]
    if missing:
        raise RuntimeError("missing internal squads: " + ", ".join(missing))
    return [by_name[name] for name in wanted]


def _extract_user(payload: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise RuntimeError("Remnawave API did not return a user")
    user = payload.get("user")
    if isinstance(user, dict):
        return user
    return payload


def _utc_iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _is_active_wdtt_access(access: dict[str, object]) -> bool:
    try:
        expires_at = int(access.get("expires_at") or 0)
    except (TypeError, ValueError):
        expires_at = 0
    return expires_at <= 0 or expires_at > int(datetime.now(timezone.utc).timestamp())


def _access_expire_at(access: dict[str, object]) -> datetime:
    try:
        expires_at = int(access.get("expires_at") or 0)
    except (TypeError, ValueError):
        expires_at = 0
    if expires_at <= 0:
        return NO_EXPIRE_AT
    return datetime.fromtimestamp(expires_at, tz=timezone.utc)


def _existing_expire_at(user: dict[str, Any] | None) -> datetime | None:
    if user is None:
        return None
    expire_at = parse_dt(user.get("expireAt"))
    if expire_at is None:
        return None
    if expire_at.tzinfo is None:
        expire_at = expire_at.replace(tzinfo=timezone.utc)
    return expire_at.astimezone(timezone.utc)


def _migration_username(access: dict[str, object]) -> str:
    telegram_id = int(access.get("telegram_id") or 0)
    access_id = int(access.get("id") or access.get("order_id") or 0)
    raw = f"tg{telegram_id}_lte_wdtt_{access_id}"
    return re.sub(r"[^a-zA-Z0-9_-]", "_", raw)[:36]


def _migration_marker(source_user_uuid: str) -> str:
    return f"WDTT source: {source_user_uuid}"


def _telegram_id_matches(user: dict[str, Any], telegram_id: int) -> bool:
    try:
        existing_telegram_id = int(user.get("telegramId") or 0)
    except (TypeError, ValueError):
        existing_telegram_id = 0
    return existing_telegram_id in {0, telegram_id}


def _is_remnawave_uuid(value: str) -> bool:
    return bool(REMNAWAVE_UUID_RE.fullmatch(value))


async def _find_existing_user(
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    access: dict[str, object],
) -> dict[str, Any] | None:
    telegram_id = int(access.get("telegram_id") or 0)
    source_user_uuid = str(access.get("user_uuid") or "")
    migration = order_store.get_wdtt_remnawave_migration(source_user_uuid)
    if migration is not None:
        user_uuid = str(migration.get("remnawave_user_uuid") or "")
        user = await remnawave.get_user_by_uuid(user_uuid) if user_uuid else None
        if user is not None and _telegram_id_matches(user, telegram_id):
            return user

    direct_user = await remnawave.get_user_by_uuid(source_user_uuid) if _is_remnawave_uuid(source_user_uuid) else None
    if direct_user is not None and _telegram_id_matches(direct_user, telegram_id):
        return direct_user

    username = _migration_username(access)
    marker = _migration_marker(source_user_uuid)
    for user in await remnawave.get_users_by_telegram_id(telegram_id):
        description = str(user.get("description") or "")
        if str(user.get("username") or "") == username or marker in description:
            return user
    return None


async def _ensure_remnawave_mobile_access(
    *,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
    access: dict[str, object],
    tariff: dict[str, Any],
    squad_uuids: list[str],
    dry_run: bool,
) -> MigrationResult | None:
    telegram_id = int(access.get("telegram_id") or 0)
    source_user_uuid = str(access.get("user_uuid") or "")
    if telegram_id <= 0 or not source_user_uuid or not _is_active_wdtt_access(access):
        return None

    existing_user = await _find_existing_user(remnawave, order_store, access)
    target_expire_at = _access_expire_at(access)
    existing_expire_at = _existing_expire_at(existing_user)
    if existing_expire_at is not None and existing_expire_at > target_expire_at:
        target_expire_at = existing_expire_at

    description = (
        f"Mobile VPN access migrated from WDTT. "
        f"{_migration_marker(source_user_uuid)}; order #{access.get('order_id') or 'unknown'}"
    )
    body: dict[str, Any] = {
        "status": "ACTIVE",
        "trafficLimitBytes": _tariff_int(tariff, "traffic_limit_bytes", 45 * 1024**3),
        "trafficLimitStrategy": _tariff_strategy(tariff),
        "expireAt": _utc_iso(target_expire_at),
        "telegramId": telegram_id,
        "description": description,
        "hwidDeviceLimit": _tariff_int(tariff, "hwid_device_limit", 2),
        "activeInternalSquads": squad_uuids,
    }
    external_squad_uuid = _lte_external_squad_uuid(tariff)
    if external_squad_uuid is not None:
        body["externalSquadUuid"] = external_squad_uuid

    if dry_run:
        logger.info(
            "dry-run: %s WDTT %s -> Remnawave %s until %s",
            "update" if existing_user else "create",
            source_user_uuid,
            existing_user.get("uuid") if existing_user else _migration_username(access),
            body["expireAt"],
        )
        return None

    if existing_user is not None:
        body["uuid"] = str(existing_user["uuid"])
        remnawave_user = _extract_user(await remnawave.update_user(body))
    else:
        body["username"] = _migration_username(access)
        remnawave_user = _extract_user(await remnawave.create_user(body))

    remnawave_user_uuid = str(remnawave_user.get("uuid") or "")
    if not remnawave_user_uuid:
        raise RuntimeError("migrated Remnawave user has no uuid")
    remnawave_user = await remnawave.get_user_by_uuid(remnawave_user_uuid) or remnawave_user

    migration = order_store.upsert_wdtt_remnawave_migration(
        source_user_uuid=source_user_uuid,
        telegram_id=telegram_id,
        remnawave_user_uuid=remnawave_user_uuid,
    )
    return MigrationResult(
        source_user_uuid=source_user_uuid,
        telegram_id=telegram_id,
        remnawave_user=remnawave_user,
        announcement_sent_at=str(migration.get("announcement_sent_at") or "") or None,
    )


def _announcement_text() -> str:
    return (
        "✅ <b>Мобильный VPN обновлён</b>\n\n"
        "Теперь вам доступен новый ключ для подключения через привычный клиент Happ.\n\n"
        "Старый WDTT-ключ продолжит работать до конца оплаченного срока. "
        "Все новые покупки и продления будут идти через новый ключ Мобильного VPN.\n\n"
        "Важно: Мобильный VPN стабильно работает только в Happ. "
        "За работу в других клиентах отвечать не можем.\n\n"
        "Ниже отправляю новый ключ."
    )


def _key_message(user: dict[str, Any], timezone_name: str) -> str:
    return key_text(user, timezone_name)


async def _announce_results(
    *,
    bot: Bot,
    order_store: OrderStore,
    results: list[MigrationResult],
    timezone_name: str,
) -> int:
    grouped: dict[int, list[MigrationResult]] = defaultdict(list)
    for result in results:
        if result.announcement_sent_at is None:
            grouped[result.telegram_id].append(result)

    sent_count = 0
    for telegram_id, user_results in grouped.items():
        try:
            await bot.send_message(telegram_id, _announcement_text())
            for result in user_results:
                await bot.send_message(telegram_id, _key_message(result.remnawave_user, timezone_name))
                order_store.mark_wdtt_remnawave_announcement_sent(result.source_user_uuid)
                sent_count += 1
        except TelegramAPIError:
            logger.warning("failed to announce WDTT migration to telegram_id=%s", telegram_id, exc_info=True)
    return sent_count


async def run(*, announce: bool, dry_run: bool) -> None:
    settings = load_settings()
    order_store = OrderStore(settings.data_dir)
    order_store.init()
    tariff = _find_tariff(settings, MOBILE_TARIFF_ID)
    timezone_name = str(settings.data.get("timezone") or "Europe/Moscow")

    async with RemnawaveClient(settings) as remnawave:
        squad_uuids = await _internal_squad_uuids(remnawave, tariff)
        accesses = order_store.get_active_wdtt_accesses()
        results: list[MigrationResult] = []
        for access in accesses:
            result = await _ensure_remnawave_mobile_access(
                remnawave=remnawave,
                order_store=order_store,
                access=access,
                tariff=tariff,
                squad_uuids=squad_uuids,
                dry_run=dry_run,
            )
            if result is not None:
                results.append(result)

        logger.info("WDTT migration prepared: %s active accesses", len(results))
        if announce and not dry_run:
            bot = Bot(settings.bot_token, default=DefaultBotProperties(parse_mode=ParseMode.HTML))
            try:
                sent_count = await _announce_results(
                    bot=bot,
                    order_store=order_store,
                    results=results,
                    timezone_name=timezone_name,
                )
            finally:
                await bot.session.close()
            logger.info("WDTT migration announcements sent: %s", sent_count)


def main() -> None:
    parser = argparse.ArgumentParser(description="Migrate legacy WDTT mobile accesses to Remnawave LTE accesses.")
    parser.add_argument("--announce", action="store_true", help="send Telegram announcements with new keys")
    parser.add_argument("--dry-run", action="store_true", help="show planned changes without writing or sending")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    asyncio.run(run(announce=args.announce, dry_run=args.dry_run))


if __name__ == "__main__":
    main()
