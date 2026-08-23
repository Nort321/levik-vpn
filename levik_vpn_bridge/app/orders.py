from __future__ import annotations

import json
import secrets
import sqlite3
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path


@dataclass(frozen=True)
class PurchaseOrder:
    id: int
    created_at: str
    telegram_id: int
    tariff_id: str
    tariff_title: str
    period_months: int
    price_rub: int
    kind: str
    status: str


@dataclass(frozen=True)
class SubscriptionReminder:
    id: int
    telegram_id: int
    user_uuid: str
    expire_at: str
    last_sent_date: str | None
    declined_at: str | None


@dataclass(frozen=True)
class CabinetAccountGrantClaim:
    actor_id: int
    user_key: str
    grant_seed: str
    grant_hash: str
    grant_expires_at: str
    grant_expires_in: int
    fresh: bool


class CabinetIdempotencyConflict(RuntimeError):
    pass


class CabinetAccountConflict(RuntimeError):
    pass


class CabinetAccountMergeRequired(RuntimeError):
    pass


class CabinetAccountGrantUnavailable(RuntimeError):
    pass


class CabinetGrantCollision(RuntimeError):
    pass


class ProviderPaymentConflict(RuntimeError):
    pass


class DeliveryEffectConflict(RuntimeError):
    pass


class OrderAlreadyInProgress(RuntimeError):
    pass


class OrderStore:
    def __init__(self, data_dir: Path) -> None:
        self._db_path = data_dir / "bot.sqlite3"

    def init(self) -> None:
        self._db_path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as connection:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS purchase_orders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at TEXT NOT NULL,
                    telegram_id INTEGER NOT NULL,
                    telegram_username TEXT,
                    first_name TEXT,
                    tariff_id TEXT NOT NULL,
                    tariff_title TEXT NOT NULL,
                    period_months INTEGER NOT NULL,
                    price_rub INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    target_user_uuid TEXT,
                    target_user_name TEXT,
                    status TEXT NOT NULL
                )
                """
            )
            self._ensure_column(connection, "purchase_orders", "slots_delta", "INTEGER NOT NULL DEFAULT 0")
            self._ensure_column(connection, "purchase_orders", "traffic_delta_bytes", "INTEGER NOT NULL DEFAULT 0")
            self._ensure_column(connection, "purchase_orders", "payment_method", "TEXT")
            self._ensure_column(connection, "purchase_orders", "stars_amount", "INTEGER")
            self._ensure_column(connection, "purchase_orders", "paid_at", "TEXT")
            self._ensure_column(connection, "purchase_orders", "telegram_payment_charge_id", "TEXT")
            self._ensure_column(connection, "purchase_orders", "provider_payment_charge_id", "TEXT")
            self._ensure_column(connection, "purchase_orders", "delivered_at", "TEXT")
            self._ensure_column(connection, "purchase_orders", "delivery_error", "TEXT")
            self._ensure_column(connection, "purchase_orders", "delivery_started_at", "TEXT")
            self._ensure_column(connection, "purchase_orders", "base_price_rub", "INTEGER")
            self._ensure_column(connection, "purchase_orders", "pay_amount_rub", "INTEGER")
            self._ensure_column(connection, "purchase_orders", "discount_percent", "INTEGER NOT NULL DEFAULT 0")
            self._ensure_column(connection, "purchase_orders", "discount_rub", "INTEGER NOT NULL DEFAULT 0")
            self._ensure_column(connection, "purchase_orders", "bonus_days", "INTEGER NOT NULL DEFAULT 0")
            self._ensure_column(connection, "purchase_orders", "referrer_telegram_id", "INTEGER")
            self._ensure_column(connection, "purchase_orders", "platega_payment_method", "INTEGER")
            self._ensure_column(connection, "purchase_orders", "provider_amount_rub", "TEXT")
            self._ensure_column(connection, "purchase_orders", "payment_url", "TEXT")
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_purchase_orders_telegram_status
                ON purchase_orders (telegram_id, status, created_at)
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_purchase_orders_provider_payment
                ON purchase_orders (provider_payment_charge_id)
                """
            )
            connection.execute(
                """
                CREATE TRIGGER IF NOT EXISTS prevent_parallel_access_renewal
                BEFORE INSERT ON purchase_orders
                WHEN NEW.kind = 'access_renewal'
                  AND NEW.target_user_uuid IS NOT NULL
                  AND NEW.target_user_uuid <> ''
                  AND NEW.status IN (
                    'pending_payment',
                    'paid',
                    'delivering',
                    'paid_delivery_failed'
                  )
                  AND EXISTS (
                    SELECT 1
                    FROM purchase_orders
                    WHERE kind = 'access_renewal'
                      AND target_user_uuid IS NOT NULL
                      AND lower(target_user_uuid) = lower(NEW.target_user_uuid)
                      AND status IN (
                        'pending_payment',
                        'paid',
                        'delivering',
                        'paid_delivery_failed'
                      )
                  )
                BEGIN
                    SELECT RAISE(ABORT, 'order_already_in_progress');
                END
                """
            )
            connection.execute(
                """
                CREATE TRIGGER IF NOT EXISTS prevent_parallel_subscription_addon
                BEFORE INSERT ON purchase_orders
                WHEN NEW.kind IN ('slot', 'traffic')
                  AND NEW.target_user_uuid IS NOT NULL
                  AND NEW.target_user_uuid <> ''
                  AND NEW.status IN (
                    'pending_payment',
                    'paid',
                    'delivering',
                    'paid_delivery_failed'
                  )
                  AND EXISTS (
                    SELECT 1
                    FROM purchase_orders
                    WHERE kind IN ('slot', 'traffic')
                      AND target_user_uuid IS NOT NULL
                      AND lower(target_user_uuid) = lower(NEW.target_user_uuid)
                      AND status IN (
                        'pending_payment',
                        'paid',
                        'delivering',
                        'paid_delivery_failed'
                      )
                  )
                BEGIN
                    SELECT RAISE(ABORT, 'order_already_in_progress');
                END
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS referrals (
                    invitee_telegram_id INTEGER PRIMARY KEY,
                    referrer_telegram_id INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    first_order_id INTEGER,
                    reward_granted_at TEXT
                )
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_referrals_referrer
                ON referrals (referrer_telegram_id, created_at)
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS bot_users (
                    telegram_id INTEGER PRIMARY KEY,
                    username TEXT,
                    username_normalized TEXT,
                    first_name TEXT,
                    first_seen_at TEXT NOT NULL,
                    last_seen_at TEXT NOT NULL,
                    message_count INTEGER NOT NULL DEFAULT 0,
                    callback_count INTEGER NOT NULL DEFAULT 0,
                    first_started_at TEXT,
                    last_started_at TEXT,
                    start_count INTEGER NOT NULL DEFAULT 0
                )
                """
            )
            self._ensure_column(connection, "bot_users", "first_started_at", "TEXT")
            self._ensure_column(connection, "bot_users", "last_started_at", "TEXT")
            self._ensure_column(connection, "bot_users", "start_count", "INTEGER NOT NULL DEFAULT 0")
            self._ensure_column(connection, "bot_users", "first_source_code", "TEXT")
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_bot_users_username
                ON bot_users (username_normalized, last_seen_at)
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_bot_users_first_source
                ON bot_users (first_source_code)
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS traffic_sources (
                    code TEXT PRIMARY KEY,
                    label TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    is_active INTEGER NOT NULL DEFAULT 1
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS free_mtproto_proxies (
                    telegram_id INTEGER PRIMARY KEY,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    mtproxy_label TEXT NOT NULL UNIQUE,
                    proxy_link TEXT NOT NULL,
                    rate_limit_mbps INTEGER NOT NULL,
                    device_limit INTEGER NOT NULL,
                    max_tcp_connections INTEGER NOT NULL,
                    max_unique_ips INTEGER NOT NULL,
                    status TEXT NOT NULL
                )
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_free_mtproto_proxies_status
                ON free_mtproto_proxies (status, updated_at)
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS subscription_reminders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    telegram_id INTEGER NOT NULL,
                    user_uuid TEXT NOT NULL,
                    expire_at TEXT NOT NULL,
                    last_sent_date TEXT,
                    declined_at TEXT,
                    UNIQUE(user_uuid, expire_at)
                )
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_subscription_reminders_due
                ON subscription_reminders (telegram_id, user_uuid, expire_at)
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS wdtt_accesses (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    telegram_id INTEGER NOT NULL,
                    user_uuid TEXT NOT NULL UNIQUE,
                    order_id INTEGER NOT NULL,
                    password TEXT NOT NULL,
                    token TEXT NOT NULL UNIQUE,
                    label TEXT NOT NULL,
                    peer TEXT NOT NULL,
                    hashes TEXT NOT NULL,
                    workers INTEGER NOT NULL,
                    port INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    max_devices INTEGER NOT NULL,
                    status TEXT NOT NULL
                )
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_wdtt_accesses_telegram
                ON wdtt_accesses (telegram_id, status)
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS wdtt_remnawave_migrations (
                    source_user_uuid TEXT PRIMARY KEY,
                    telegram_id INTEGER NOT NULL,
                    remnawave_user_uuid TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    announcement_sent_at TEXT
                )
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_wdtt_remnawave_migrations_telegram
                ON wdtt_remnawave_migrations (telegram_id, announcement_sent_at)
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS subscription_addons (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    telegram_id INTEGER NOT NULL,
                    user_uuid TEXT NOT NULL,
                    order_id INTEGER NOT NULL UNIQUE,
                    kind TEXT NOT NULL,
                    slots_delta INTEGER NOT NULL DEFAULT 0,
                    slots_persistent INTEGER NOT NULL DEFAULT 0,
                    traffic_delta_bytes INTEGER NOT NULL DEFAULT 0,
                    expires_at TEXT NOT NULL,
                    status TEXT NOT NULL
                )
                """
            )
            self._ensure_column(
                connection,
                "subscription_addons",
                "slots_persistent",
                "INTEGER NOT NULL DEFAULT 0",
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_subscription_addons_user_status
                ON subscription_addons (user_uuid, status, expires_at)
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS multi_subscriptions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    telegram_id INTEGER NOT NULL,
                    primary_user_uuid TEXT NOT NULL UNIQUE,
                    mobile_user_uuid TEXT NOT NULL UNIQUE,
                    token TEXT NOT NULL UNIQUE,
                    status TEXT NOT NULL
                )
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_multi_subscriptions_telegram
                ON multi_subscriptions (telegram_id, status)
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS subscription_shield_settings (
                    remnawave_user_id INTEGER PRIMARY KEY,
                    telegram_id INTEGER NOT NULL,
                    user_uuid TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 0 CHECK(enabled IN (0, 1)),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_subscription_shield_telegram
                ON subscription_shield_settings (telegram_id)
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS order_delivery_effects (
                    order_id INTEGER PRIMARY KEY,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    telegram_id INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    effect_json TEXT NOT NULL,
                    applied_at TEXT,
                    FOREIGN KEY(order_id) REFERENCES purchase_orders(id)
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS trial_accesses (
                    telegram_id INTEGER PRIMARY KEY,
                    telegram_username TEXT,
                    first_name TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    status TEXT NOT NULL,
                    regular_user_uuid TEXT,
                    mobile_user_uuid TEXT,
                    completed_at TEXT,
                    delivery_error TEXT,
                    admin_notified_at TEXT
                )
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_trial_accesses_notification
                ON trial_accesses (status, admin_notified_at, completed_at)
                """
            )
            self._ensure_column(connection, "trial_accesses", "selected_tariff_id", "TEXT")
            self._ensure_column(connection, "trial_accesses", "selected_component", "TEXT")
            self._ensure_column(connection, "trial_accesses", "platform", "TEXT")
            self._ensure_column(connection, "trial_accesses", "first_traffic_at", "TEXT")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS bot_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at TEXT NOT NULL,
                    telegram_id INTEGER NOT NULL,
                    event_name TEXT NOT NULL,
                    properties_json TEXT NOT NULL DEFAULT '{}'
                )
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_bot_events_funnel
                ON bot_events (event_name, created_at, telegram_id)
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS lifecycle_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at TEXT NOT NULL,
                    telegram_id INTEGER NOT NULL,
                    message_kind TEXT NOT NULL,
                    reference_key TEXT NOT NULL,
                    status TEXT NOT NULL,
                    sent_at TEXT,
                    error_code TEXT,
                    UNIQUE(telegram_id, message_kind, reference_key)
                )
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_lifecycle_messages_status
                ON lifecycle_messages (status, message_kind, created_at)
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS cabinet_device_challenges (
                    challenge_id TEXT PRIMARY KEY,
                    device_code_hash TEXT NOT NULL UNIQUE,
                    verification_token_hash TEXT NOT NULL UNIQUE,
                    user_code TEXT NOT NULL UNIQUE,
                    status TEXT NOT NULL,
                    telegram_id INTEGER,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    bound_at TEXT,
                    confirmed_at TEXT,
                    consumed_at TEXT
                )
                """
            )
            self._ensure_column(connection, "cabinet_device_challenges", "user_code", "TEXT")
            connection.execute(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS idx_cabinet_device_challenges_user_code
                ON cabinet_device_challenges (user_code)
                WHERE user_code IS NOT NULL
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_cabinet_device_challenges_expiry
                ON cabinet_device_challenges (status, expires_at)
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS cabinet_grants (
                    grant_hash TEXT PRIMARY KEY,
                    telegram_id INTEGER NOT NULL,
                    challenge_id TEXT NOT NULL UNIQUE,
                    issued_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    last_used_at TEXT,
                    revoked_at TEXT
                )
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_cabinet_grants_expiry
                ON cabinet_grants (expires_at, revoked_at)
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS cabinet_replay_nonces (
                    key_id TEXT NOT NULL,
                    nonce TEXT NOT NULL,
                    seen_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    PRIMARY KEY (key_id, nonce)
                )
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_cabinet_replay_nonces_expiry
                ON cabinet_replay_nonces (expires_at)
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS cabinet_idempotency (
                    idempotency_key TEXT PRIMARY KEY,
                    operation TEXT NOT NULL,
                    telegram_id INTEGER NOT NULL,
                    request_hash TEXT NOT NULL,
                    status TEXT NOT NULL,
                    order_id INTEGER,
                    response_json TEXT,
                    error_code TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_cabinet_idempotency_actor
                ON cabinet_idempotency (telegram_id, operation, created_at)
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS cabinet_actors (
                    actor_id INTEGER PRIMARY KEY,
                    user_key TEXT NOT NULL UNIQUE,
                    actor_kind TEXT NOT NULL CHECK(actor_kind IN ('telegram', 'account')),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    CHECK(actor_id != 0),
                    CHECK(
                        (actor_kind = 'telegram' AND actor_id > 0)
                        OR (actor_kind = 'account' AND actor_id <= -4503599627370496)
                    )
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS cabinet_account_principals (
                    account_id TEXT PRIMARY KEY,
                    actor_id INTEGER NOT NULL UNIQUE,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY(actor_id) REFERENCES cabinet_actors(actor_id)
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS cabinet_account_legacy_aliases (
                    account_id TEXT PRIMARY KEY,
                    legacy_actor_id INTEGER NOT NULL UNIQUE,
                    legacy_user_key TEXT NOT NULL UNIQUE,
                    canonical_actor_id INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY(account_id) REFERENCES cabinet_account_principals(account_id),
                    FOREIGN KEY(legacy_actor_id) REFERENCES cabinet_actors(actor_id),
                    FOREIGN KEY(canonical_actor_id) REFERENCES cabinet_actors(actor_id),
                    CHECK(legacy_actor_id > 0),
                    CHECK(canonical_actor_id <= -4503599627370496)
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS cabinet_account_grant_requests (
                    idempotency_key TEXT PRIMARY KEY,
                    account_id TEXT NOT NULL,
                    legacy_user_key TEXT,
                    request_hash TEXT NOT NULL,
                    actor_id INTEGER NOT NULL,
                    grant_seed TEXT NOT NULL,
                    grant_hash TEXT NOT NULL UNIQUE,
                    grant_expires_at TEXT NOT NULL,
                    grant_expires_in INTEGER NOT NULL CHECK(grant_expires_in > 0),
                    created_at TEXT NOT NULL,
                    FOREIGN KEY(account_id) REFERENCES cabinet_account_principals(account_id),
                    FOREIGN KEY(actor_id) REFERENCES cabinet_actors(actor_id),
                    FOREIGN KEY(grant_hash) REFERENCES cabinet_grants(grant_hash)
                )
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_cabinet_account_grants_actor
                ON cabinet_account_grant_requests (actor_id, created_at)
                """
            )

    def create_cabinet_device_challenge(
        self,
        *,
        challenge_id: str,
        device_code_hash: str,
        verification_token_hash: str,
        user_code: str,
        expires_at: str,
    ) -> dict[str, object]:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            connection.execute(
                """
                INSERT INTO cabinet_device_challenges (
                    challenge_id,
                    device_code_hash,
                    verification_token_hash,
                    user_code,
                    status,
                    created_at,
                    updated_at,
                    expires_at
                )
                VALUES (?, ?, ?, ?, 'pending', ?, ?, ?)
                """,
                (
                    challenge_id,
                    device_code_hash,
                    verification_token_hash,
                    user_code,
                    now,
                    now,
                    expires_at,
                ),
            )
            row = connection.execute(
                "SELECT * FROM cabinet_device_challenges WHERE challenge_id = ?",
                (challenge_id,),
            ).fetchone()
            if row is None:
                raise RuntimeError("cabinet device challenge was not saved")
            return dict(row)

    def bind_cabinet_device_challenge(
        self,
        *,
        verification_token_hash: str,
        telegram_id: int,
    ) -> dict[str, object] | None:
        if telegram_id <= 0:
            return None
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            connection.execute(
                """
                UPDATE cabinet_device_challenges
                SET telegram_id = ?,
                    status = 'awaiting_confirmation',
                    bound_at = COALESCE(bound_at, ?),
                    updated_at = ?
                WHERE verification_token_hash = ?
                  AND expires_at > ?
                  AND status IN ('pending', 'awaiting_confirmation')
                  AND (telegram_id IS NULL OR telegram_id = ?)
                """,
                (
                    telegram_id,
                    now,
                    now,
                    verification_token_hash,
                    now,
                    telegram_id,
                ),
            )
            row = connection.execute(
                """
                SELECT *
                FROM cabinet_device_challenges
                WHERE verification_token_hash = ?
                  AND telegram_id = ?
                  AND status = 'awaiting_confirmation'
                  AND expires_at > ?
                """,
                (verification_token_hash, telegram_id, now),
            ).fetchone()
            return dict(row) if row else None

    def confirm_cabinet_device_challenge(
        self,
        *,
        challenge_id: str,
        telegram_id: int,
    ) -> bool:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            cursor = connection.execute(
                """
                UPDATE cabinet_device_challenges
                SET status = 'confirmed',
                    confirmed_at = ?,
                    updated_at = ?
                WHERE challenge_id = ?
                  AND telegram_id = ?
                  AND status = 'awaiting_confirmation'
                  AND expires_at > ?
                """,
                (now, now, challenge_id, telegram_id, now),
            )
            return cursor.rowcount == 1

    def deny_cabinet_device_challenge(
        self,
        *,
        challenge_id: str,
        telegram_id: int,
    ) -> bool:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            cursor = connection.execute(
                """
                UPDATE cabinet_device_challenges
                SET status = 'denied',
                    updated_at = ?
                WHERE challenge_id = ?
                  AND telegram_id = ?
                  AND status = 'awaiting_confirmation'
                  AND expires_at > ?
                """,
                (now, challenge_id, telegram_id, now),
            )
            return cursor.rowcount == 1

    def exchange_cabinet_device_challenge(
        self,
        *,
        device_code_hash: str,
        grant_hash: str,
        grant_expires_at: str,
    ) -> tuple[str, int | None]:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute(
                """
                SELECT *
                FROM cabinet_device_challenges
                WHERE device_code_hash = ?
                """,
                (device_code_hash,),
            ).fetchone()
            if row is None:
                return "invalid", None
            if str(row["expires_at"]) <= now:
                connection.execute(
                    """
                    UPDATE cabinet_device_challenges
                    SET status = 'expired', updated_at = ?
                    WHERE challenge_id = ? AND status NOT IN ('consumed', 'denied')
                    """,
                    (now, str(row["challenge_id"])),
                )
                return "expired", None

            status = str(row["status"])
            telegram_id = int(row["telegram_id"] or 0)
            if status != "confirmed" or telegram_id <= 0:
                return status, None

            cursor = connection.execute(
                """
                UPDATE cabinet_device_challenges
                SET status = 'consumed',
                    consumed_at = ?,
                    updated_at = ?
                WHERE challenge_id = ? AND status = 'confirmed'
                """,
                (now, now, str(row["challenge_id"])),
            )
            if cursor.rowcount != 1:
                return "consumed", None
            connection.execute(
                """
                INSERT INTO cabinet_grants (
                    grant_hash,
                    telegram_id,
                    challenge_id,
                    issued_at,
                    expires_at
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                (
                    grant_hash,
                    telegram_id,
                    str(row["challenge_id"]),
                    now,
                    grant_expires_at,
                ),
            )
            return "authorized", telegram_id

    def resolve_cabinet_grant(self, *, grant_hash: str) -> int | None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT COALESCE(alias.canonical_actor_id, grant.telegram_id)
                FROM cabinet_grants AS grant
                LEFT JOIN cabinet_account_legacy_aliases AS alias
                    ON alias.legacy_actor_id = grant.telegram_id
                WHERE grant.grant_hash = ?
                  AND grant.revoked_at IS NULL
                  AND grant.expires_at > ?
                """,
                (grant_hash, now),
            ).fetchone()
            if row is None:
                return None
            connection.execute(
                """
                UPDATE cabinet_grants
                SET last_used_at = ?
                WHERE grant_hash = ?
                """,
                (now, grant_hash),
            )
            return int(row[0])

    def revoke_cabinet_grant(self, *, grant_hash: str) -> bool:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            cursor = connection.execute(
                """
                UPDATE cabinet_grants
                SET revoked_at = ?,
                    last_used_at = ?
                WHERE grant_hash = ?
                  AND revoked_at IS NULL
                  AND expires_at > ?
                """,
                (now, now, grant_hash, now),
            )
            return cursor.rowcount == 1

    def get_bot_user(self, telegram_id: int) -> dict[str, object] | None:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute(
                "SELECT * FROM bot_users WHERE telegram_id = ?",
                (telegram_id,),
            ).fetchone()
            return dict(row) if row else None

    def remember_cabinet_telegram_actor(self, *, actor_id: int, user_key: str) -> None:
        if actor_id <= 0 or not user_key:
            raise ValueError("cabinet Telegram actor is invalid")
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            connection.execute("BEGIN IMMEDIATE")
            actor = connection.execute(
                "SELECT * FROM cabinet_actors WHERE actor_id = ?",
                (actor_id,),
            ).fetchone()
            key_owner = connection.execute(
                "SELECT actor_id FROM cabinet_actors WHERE user_key = ?",
                (user_key,),
            ).fetchone()
            if (
                actor is not None
                and (
                    str(actor["user_key"]) != user_key
                    or str(actor["actor_kind"]) != "telegram"
                )
            ) or (key_owner is not None and int(key_owner["actor_id"]) != actor_id):
                raise CabinetAccountConflict("cabinet user key belongs to another actor")
            connection.execute(
                """
                INSERT INTO cabinet_actors (
                    actor_id,
                    user_key,
                    actor_kind,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, 'telegram', ?, ?)
                ON CONFLICT(actor_id) DO UPDATE SET updated_at = excluded.updated_at
                """,
                (actor_id, user_key, now, now),
            )

    def resolve_cabinet_actor_user_key(self, *, user_key: str) -> int | None:
        self.init()
        with self._connect() as connection:
            row = connection.execute(
                "SELECT actor_id FROM cabinet_actors WHERE user_key = ?",
                (user_key,),
            ).fetchone()
            # Identity binding needs the actor named by the supplied user key.
            # Commerce grants are canonicalized separately in
            # resolve_cabinet_grant so an authentication-only Telegram alias
            # never masquerades as the synthetic account identity here.
            return int(row[0]) if row is not None else None

    def get_cabinet_actor(self, actor_id: int) -> dict[str, object] | None:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute(
                "SELECT * FROM cabinet_actors WHERE actor_id = ?",
                (actor_id,),
            ).fetchone()
            return dict(row) if row else None

    def is_cabinet_account_actor(self, actor_id: int) -> bool:
        actor = self.get_cabinet_actor(actor_id)
        return actor is not None and str(actor.get("actor_kind") or "") == "account"

    def canonical_cabinet_actor(self, actor_id: int) -> int:
        if actor_id <= 0:
            return actor_id
        self.init()
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT canonical_actor_id
                FROM cabinet_account_legacy_aliases
                WHERE legacy_actor_id = ?
                """,
                (actor_id,),
            ).fetchone()
            return int(row[0]) if row is not None else actor_id

    def get_cabinet_account_binding(self, account_id: str) -> dict[str, object] | None:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute(
                """
                SELECT
                    principal.actor_id,
                    actor.user_key,
                    actor.actor_kind,
                    alias.legacy_actor_id,
                    alias.legacy_user_key
                FROM cabinet_account_principals AS principal
                INNER JOIN cabinet_actors AS actor
                    ON actor.actor_id = principal.actor_id
                LEFT JOIN cabinet_account_legacy_aliases AS alias
                    ON alias.account_id = principal.account_id
                WHERE principal.account_id = ?
                """,
                (account_id,),
            ).fetchone()
            return dict(row) if row else None

    @staticmethod
    def _cabinet_actor_has_local_state(
        connection: sqlite3.Connection,
        actor_id: int,
    ) -> bool:
        row = connection.execute(
            """
            SELECT (
                EXISTS(SELECT 1 FROM purchase_orders WHERE telegram_id = ?)
                OR EXISTS(SELECT 1 FROM wdtt_accesses WHERE telegram_id = ?)
                OR EXISTS(SELECT 1 FROM multi_subscriptions WHERE telegram_id = ?)
                OR EXISTS(SELECT 1 FROM trial_accesses WHERE telegram_id = ?)
                OR EXISTS(SELECT 1 FROM free_mtproto_proxies WHERE telegram_id = ?)
                OR EXISTS(
                    SELECT 1 FROM referrals
                    WHERE invitee_telegram_id = ? OR referrer_telegram_id = ?
                )
            )
            """,
            (actor_id, actor_id, actor_id, actor_id, actor_id, actor_id, actor_id),
        ).fetchone()
        return bool(row and row[0])

    def claim_cabinet_account_grant(
        self,
        *,
        account_id: str,
        legacy_user_key: str | None,
        legacy_actor_id: int | None,
        idempotency_key: str,
        request_hash: str,
        grant_seed: str,
        grant_hash: str,
        grant_expires_at: str,
        grant_expires_in: int,
        user_key_factory: Callable[[int], str],
        principal_has_external_state: bool = False,
        legacy_has_external_state: bool = False,
    ) -> CabinetAccountGrantClaim:
        if (legacy_user_key is None) != (legacy_actor_id is None):
            raise CabinetAccountConflict("legacy actor binding is incomplete")
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            connection.execute("BEGIN IMMEDIATE")
            previous = connection.execute(
                """
                SELECT
                    request.*,
                    actor.user_key,
                    grant.revoked_at
                FROM cabinet_account_grant_requests AS request
                INNER JOIN cabinet_actors AS actor
                    ON actor.actor_id = request.actor_id
                INNER JOIN cabinet_grants AS grant
                    ON grant.grant_hash = request.grant_hash
                WHERE request.idempotency_key = ?
                """,
                (idempotency_key,),
            ).fetchone()
            if previous is not None:
                stored_legacy_key = (
                    str(previous["legacy_user_key"])
                    if previous["legacy_user_key"] is not None
                    else None
                )
                if (
                    str(previous["account_id"]) != account_id
                    or stored_legacy_key != legacy_user_key
                    or str(previous["request_hash"]) != request_hash
                ):
                    raise CabinetAccountConflict(
                        "idempotency key was reused with another account request"
                    )
                if (
                    previous["revoked_at"] is not None
                    or str(previous["grant_expires_at"]) <= now
                ):
                    raise CabinetAccountGrantUnavailable(
                        "idempotent account grant is no longer available"
                    )
                return CabinetAccountGrantClaim(
                    actor_id=int(previous["actor_id"]),
                    user_key=str(previous["user_key"]),
                    grant_seed=str(previous["grant_seed"]),
                    grant_hash=str(previous["grant_hash"]),
                    grant_expires_at=str(previous["grant_expires_at"]),
                    grant_expires_in=int(previous["grant_expires_in"]),
                    fresh=False,
                )

            principal = connection.execute(
                """
                SELECT principal.account_id, principal.actor_id, actor.user_key
                FROM cabinet_account_principals AS principal
                INNER JOIN cabinet_actors AS actor
                    ON actor.actor_id = principal.actor_id
                WHERE principal.account_id = ?
                """,
                (account_id,),
            ).fetchone()
            if principal is not None:
                actor_id = int(principal["actor_id"])
                user_key = str(principal["user_key"])
                if legacy_user_key is not None:
                    if legacy_actor_id is None or legacy_actor_id <= 0:
                        raise CabinetAccountConflict("legacy cabinet actor is invalid")
                    actor_kind_row = connection.execute(
                        "SELECT actor_kind FROM cabinet_actors WHERE actor_id = ?",
                        (actor_id,),
                    ).fetchone()
                    actor_kind = str(actor_kind_row[0]) if actor_kind_row else ""
                    if actor_kind == "telegram":
                        if legacy_user_key != user_key or legacy_actor_id != actor_id:
                            raise CabinetAccountConflict(
                                "account is already bound to another cabinet actor"
                            )
                    elif actor_kind == "account":
                        alias = connection.execute(
                            """
                            SELECT legacy_actor_id, legacy_user_key
                            FROM cabinet_account_legacy_aliases
                            WHERE account_id = ?
                            """,
                            (account_id,),
                        ).fetchone()
                        if alias is not None:
                            if (
                                int(alias["legacy_actor_id"]) != legacy_actor_id
                                or str(alias["legacy_user_key"]) != legacy_user_key
                            ):
                                raise CabinetAccountConflict(
                                    "account already has another legacy identity"
                                )
                        else:
                            legacy_owner = connection.execute(
                                """
                                SELECT account_id FROM cabinet_account_principals
                                WHERE actor_id = ? AND account_id <> ?
                                UNION ALL
                                SELECT account_id FROM cabinet_account_legacy_aliases
                                WHERE legacy_actor_id = ? AND account_id <> ?
                                LIMIT 1
                                """,
                                (legacy_actor_id, account_id, legacy_actor_id, account_id),
                            ).fetchone()
                            if legacy_owner is not None:
                                raise CabinetAccountConflict(
                                    "legacy cabinet actor belongs to another account"
                                )
                            principal_has_state = (
                                principal_has_external_state
                                or self._cabinet_actor_has_local_state(connection, actor_id)
                            )
                            legacy_has_state = (
                                legacy_has_external_state
                                or self._cabinet_actor_has_local_state(
                                    connection,
                                    legacy_actor_id,
                                )
                            )
                            if principal_has_state and legacy_has_state:
                                raise CabinetAccountMergeRequired(
                                    "both cabinet actors contain commerce state"
                                )
                            if not principal_has_state:
                                # Prefer the real Telegram actor when the synthetic actor
                                # has no state. This also deterministically handles the
                                # neither-has-state case.
                                connection.execute(
                                    """
                                    UPDATE cabinet_account_principals
                                    SET actor_id = ?, updated_at = ?
                                    WHERE account_id = ? AND actor_id = ?
                                    """,
                                    (legacy_actor_id, now, account_id, actor_id),
                                )
                                connection.execute(
                                    """
                                    UPDATE cabinet_grants
                                    SET revoked_at = COALESCE(revoked_at, ?)
                                    WHERE telegram_id = ?
                                    """,
                                    (now, actor_id),
                                )
                                actor_id = legacy_actor_id
                                user_key = legacy_user_key
                            else:
                                # The synthetic actor already owns commerce state while
                                # the Telegram actor does not. Keep commerce canonical and
                                # remember Telegram solely as an authentication alias.
                                connection.execute(
                                    """
                                    INSERT INTO cabinet_account_legacy_aliases (
                                        account_id,
                                        legacy_actor_id,
                                        legacy_user_key,
                                        canonical_actor_id,
                                        created_at,
                                        updated_at
                                    )
                                    VALUES (?, ?, ?, ?, ?, ?)
                                    """,
                                    (
                                        account_id,
                                        legacy_actor_id,
                                        legacy_user_key,
                                        actor_id,
                                        now,
                                        now,
                                    ),
                                )
                    else:
                        raise CabinetAccountConflict("account principal is invalid")
            elif legacy_user_key is not None and legacy_actor_id is not None:
                actor = connection.execute(
                    """
                    SELECT actor_id, user_key
                    FROM cabinet_actors
                    WHERE actor_id = ? AND user_key = ?
                    """,
                    (legacy_actor_id, legacy_user_key),
                ).fetchone()
                if actor is None:
                    raise CabinetAccountConflict("legacy cabinet actor was not found")
                actor_principal = connection.execute(
                    """
                    SELECT account_id
                    FROM cabinet_account_principals
                    WHERE actor_id = ?
                    """,
                    (legacy_actor_id,),
                ).fetchone()
                if (
                    actor_principal is not None
                    and str(actor_principal["account_id"]) != account_id
                ):
                    raise CabinetAccountConflict(
                        "cabinet actor is already bound to another account"
                    )
                actor_id = legacy_actor_id
                user_key = legacy_user_key
                connection.execute(
                    """
                    INSERT INTO cabinet_account_principals (
                        account_id,
                        actor_id,
                        created_at,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?)
                    """,
                    (account_id, actor_id, now, now),
                )
            else:
                minimum_row = connection.execute(
                    """
                    SELECT MIN(actor_id)
                    FROM cabinet_actors
                    WHERE actor_kind = 'account'
                    """
                ).fetchone()
                current_minimum = (
                    int(minimum_row[0])
                    if minimum_row is not None and minimum_row[0] is not None
                    else -4503599627370495
                )
                actor_id = current_minimum - 1
                while actor_id >= -9007199254740991:
                    user_key = user_key_factory(actor_id)
                    collision = connection.execute(
                        "SELECT 1 FROM cabinet_actors WHERE user_key = ?",
                        (user_key,),
                    ).fetchone()
                    if collision is None:
                        break
                    actor_id -= 1
                else:
                    raise RuntimeError("cabinet account actor range is exhausted")
                connection.execute(
                    """
                    INSERT INTO cabinet_actors (
                        actor_id,
                        user_key,
                        actor_kind,
                        created_at,
                        updated_at
                    )
                    VALUES (?, ?, 'account', ?, ?)
                    """,
                    (actor_id, user_key, now, now),
                )
                connection.execute(
                    """
                    INSERT INTO cabinet_account_principals (
                        account_id,
                        actor_id,
                        created_at,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?)
                    """,
                    (account_id, actor_id, now, now),
                )

            if connection.execute(
                "SELECT 1 FROM cabinet_grants WHERE grant_hash = ?",
                (grant_hash,),
            ).fetchone() is not None:
                raise CabinetGrantCollision("cabinet grant token collided")
            challenge_id = f"account:{idempotency_key}"
            if connection.execute(
                "SELECT 1 FROM cabinet_grants WHERE challenge_id = ?",
                (challenge_id,),
            ).fetchone() is not None:
                raise RuntimeError("orphaned cabinet account grant exists")
            connection.execute(
                """
                INSERT INTO cabinet_grants (
                    grant_hash,
                    telegram_id,
                    challenge_id,
                    issued_at,
                    expires_at
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                (grant_hash, actor_id, challenge_id, now, grant_expires_at),
            )
            connection.execute(
                """
                INSERT INTO cabinet_account_grant_requests (
                    idempotency_key,
                    account_id,
                    legacy_user_key,
                    request_hash,
                    actor_id,
                    grant_seed,
                    grant_hash,
                    grant_expires_at,
                    grant_expires_in,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    idempotency_key,
                    account_id,
                    legacy_user_key,
                    request_hash,
                    actor_id,
                    grant_seed,
                    grant_hash,
                    grant_expires_at,
                    grant_expires_in,
                    now,
                ),
            )
            return CabinetAccountGrantClaim(
                actor_id=actor_id,
                user_key=user_key,
                grant_seed=grant_seed,
                grant_hash=grant_hash,
                grant_expires_at=grant_expires_at,
                grant_expires_in=grant_expires_in,
                fresh=True,
            )

    def claim_cabinet_nonce(
        self,
        *,
        key_id: str,
        nonce: str,
        expires_at: str,
    ) -> bool:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.execute(
                "DELETE FROM cabinet_replay_nonces WHERE expires_at <= ?",
                (now,),
            )
            cursor = connection.execute(
                """
                INSERT OR IGNORE INTO cabinet_replay_nonces (
                    key_id,
                    nonce,
                    seen_at,
                    expires_at
                )
                VALUES (?, ?, ?, ?)
                """,
                (key_id, nonce, now, expires_at),
            )
            return cursor.rowcount == 1

    def claim_cabinet_idempotency(
        self,
        *,
        idempotency_key: str,
        operation: str,
        telegram_id: int,
        request_hash: str,
    ) -> tuple[dict[str, object], bool]:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            cursor = connection.execute(
                """
                INSERT OR IGNORE INTO cabinet_idempotency (
                    idempotency_key,
                    operation,
                    telegram_id,
                    request_hash,
                    status,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, 'processing', ?, ?)
                """,
                (
                    idempotency_key,
                    operation,
                    telegram_id,
                    request_hash,
                    now,
                    now,
                ),
            )
            row = connection.execute(
                """
                SELECT *
                FROM cabinet_idempotency
                WHERE idempotency_key = ?
                """,
                (idempotency_key,),
            ).fetchone()
            if row is None:
                raise RuntimeError("cabinet idempotency record was not saved")
            result = dict(row)
            if (
                str(result["operation"]) != operation
                or int(result["telegram_id"]) != telegram_id
                or str(result["request_hash"]) != request_hash
            ):
                raise CabinetIdempotencyConflict("idempotency key was reused with a different request")
            return result, cursor.rowcount == 1

    def set_cabinet_idempotency_order(self, *, idempotency_key: str, order_id: int) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE cabinet_idempotency
                SET order_id = ?,
                    updated_at = ?
                WHERE idempotency_key = ? AND status = 'processing'
                """,
                (order_id, now, idempotency_key),
            )

    def complete_cabinet_idempotency(
        self,
        *,
        idempotency_key: str,
        response: dict[str, object],
        order_id: int | None = None,
    ) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        payload = json.dumps(response, ensure_ascii=False, separators=(",", ":"))
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE cabinet_idempotency
                SET status = 'completed',
                    order_id = COALESCE(?, order_id),
                    response_json = ?,
                    error_code = NULL,
                    updated_at = ?
                WHERE idempotency_key = ?
                """,
                (order_id, payload, now, idempotency_key),
            )

    def fail_cabinet_idempotency(self, *, idempotency_key: str, error_code: str) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE cabinet_idempotency
                SET status = 'failed',
                    error_code = ?,
                    updated_at = ?
                WHERE idempotency_key = ? AND status = 'processing'
                """,
                (error_code[:64], now, idempotency_key),
            )

    def create_slot_payment(
        self,
        *,
        telegram_id: int,
        telegram_username: str | None,
        first_name: str | None,
        target_user_uuid: str,
        target_user_name: str | None,
        price_rub: int,
        stars_amount: int | None,
        slots_delta: int,
        payment_method: str,
        traffic_delta_bytes: int = 0,
        pay_amount_rub: int | None = None,
        platega_payment_method: int | None = None,
    ) -> PurchaseOrder:
        self.init()
        created_at = datetime.now(timezone.utc).isoformat()
        status = "pending_payment"
        with self._order_creation_connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            cursor = connection.execute(
                """
                INSERT INTO purchase_orders (
                    created_at,
                    telegram_id,
                    telegram_username,
                    first_name,
                    tariff_id,
                    tariff_title,
                    period_months,
                    price_rub,
                    kind,
                    target_user_uuid,
                    target_user_name,
                    status,
                    slots_delta,
                    traffic_delta_bytes,
                    payment_method,
                    stars_amount,
                    base_price_rub,
                    pay_amount_rub,
                    platega_payment_method
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    created_at,
                    telegram_id,
                    telegram_username,
                    first_name,
                    "slot",
                    "+1 слот",
                    0,
                    price_rub,
                    "slot",
                    target_user_uuid,
                    target_user_name,
                    status,
                    slots_delta,
                    max(0, traffic_delta_bytes),
                    payment_method,
                    stars_amount,
                    price_rub,
                    pay_amount_rub if pay_amount_rub is not None else price_rub,
                    platega_payment_method,
                ),
            )
            order_id = int(cursor.lastrowid)

        return PurchaseOrder(
            id=order_id,
            created_at=created_at,
            telegram_id=telegram_id,
            tariff_id="slot",
            tariff_title="+1 слот",
            period_months=0,
            price_rub=price_rub,
            kind="slot",
            status=status,
        )

    def create_traffic_payment(
        self,
        *,
        telegram_id: int,
        telegram_username: str | None,
        first_name: str | None,
        target_user_uuid: str,
        target_user_name: str | None,
        price_rub: int,
        stars_amount: int | None,
        traffic_delta_bytes: int,
        payment_method: str,
        pay_amount_rub: int | None = None,
        platega_payment_method: int | None = None,
    ) -> PurchaseOrder:
        self.init()
        created_at = datetime.now(timezone.utc).isoformat()
        status = "pending_payment"
        with self._order_creation_connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            cursor = connection.execute(
                """
                INSERT INTO purchase_orders (
                    created_at,
                    telegram_id,
                    telegram_username,
                    first_name,
                    tariff_id,
                    tariff_title,
                    period_months,
                    price_rub,
                    kind,
                    target_user_uuid,
                    target_user_name,
                    status,
                    slots_delta,
                    traffic_delta_bytes,
                    payment_method,
                    stars_amount,
                    base_price_rub,
                    pay_amount_rub,
                    platega_payment_method
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    created_at,
                    telegram_id,
                    telegram_username,
                    first_name,
                    "mobile_traffic",
                    "+трафик",
                    0,
                    price_rub,
                    "traffic",
                    target_user_uuid,
                    target_user_name,
                    status,
                    0,
                    max(1, traffic_delta_bytes),
                    payment_method,
                    stars_amount,
                    price_rub,
                    pay_amount_rub if pay_amount_rub is not None else price_rub,
                    platega_payment_method,
                ),
            )
            order_id = int(cursor.lastrowid)

        return PurchaseOrder(
            id=order_id,
            created_at=created_at,
            telegram_id=telegram_id,
            tariff_id="mobile_traffic",
            tariff_title="+трафик",
            period_months=0,
            price_rub=price_rub,
            kind="traffic",
            status=status,
        )

    def create_admin_access_grant(
        self,
        *,
        telegram_id: int,
        telegram_username: str | None,
        tariff_id: str,
        tariff_title: str,
        period_months: int,
    ) -> PurchaseOrder:
        self.init()
        created_at = datetime.now(timezone.utc).isoformat()
        status = "paid"
        with self._connect() as connection:
            cursor = connection.execute(
                """
                INSERT INTO purchase_orders (
                    created_at,
                    telegram_id,
                    telegram_username,
                    first_name,
                    tariff_id,
                    tariff_title,
                    period_months,
                    price_rub,
                    kind,
                    status,
                    payment_method,
                    stars_amount,
                    paid_at,
                    base_price_rub,
                    pay_amount_rub
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    created_at,
                    telegram_id,
                    telegram_username,
                    None,
                    tariff_id,
                    tariff_title,
                    period_months,
                    0,
                    "admin_grant",
                    status,
                    "admin_grant",
                    None,
                    created_at,
                    0,
                    0,
                ),
            )
            order_id = int(cursor.lastrowid)

        return PurchaseOrder(
            id=order_id,
            created_at=created_at,
            telegram_id=telegram_id,
            tariff_id=tariff_id,
            tariff_title=tariff_title,
            period_months=period_months,
            price_rub=0,
            kind="admin_grant",
            status=status,
        )

    def create_access_payment(
        self,
        *,
        telegram_id: int,
        telegram_username: str | None,
        first_name: str | None,
        tariff_id: str,
        tariff_title: str,
        period_months: int,
        price_rub: int,
        stars_amount: int | None,
        kind: str,
        target_user_uuid: str | None = None,
        target_user_name: str | None = None,
        payment_method: str = "telegram_stars",
        pay_amount_rub: int | None = None,
        base_price_rub: int | None = None,
        discount_percent: int = 0,
        discount_rub: int = 0,
        referrer_telegram_id: int | None = None,
        platega_payment_method: int | None = None,
    ) -> PurchaseOrder:
        self.init()
        created_at = datetime.now(timezone.utc).isoformat()
        status = "pending_payment"
        with self._order_creation_connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            cursor = connection.execute(
                """
                INSERT INTO purchase_orders (
                    created_at,
                    telegram_id,
                    telegram_username,
                    first_name,
                    tariff_id,
                    tariff_title,
                    period_months,
                    price_rub,
                    kind,
                    target_user_uuid,
                    target_user_name,
                    status,
                    payment_method,
                    stars_amount,
                    base_price_rub,
                    pay_amount_rub,
                    discount_percent,
                    discount_rub,
                    referrer_telegram_id,
                    platega_payment_method
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    created_at,
                    telegram_id,
                    telegram_username,
                    first_name,
                    tariff_id,
                    tariff_title,
                    period_months,
                    price_rub,
                    kind,
                    target_user_uuid,
                    target_user_name,
                    status,
                    payment_method,
                    stars_amount,
                    base_price_rub if base_price_rub is not None else price_rub,
                    pay_amount_rub if pay_amount_rub is not None else price_rub,
                    discount_percent,
                    discount_rub,
                    referrer_telegram_id,
                    platega_payment_method,
                ),
            )
            order_id = int(cursor.lastrowid)
            bonus_cutoff = (datetime.now(timezone.utc) - timedelta(hours=48)).isoformat()
            bonus = connection.execute(
                """
                SELECT 1 FROM lifecycle_messages
                WHERE telegram_id = ?
                  AND message_kind IN ('trial_winback_7d', 'paid_winback_7d')
                  AND status = 'sent'
                  AND sent_at >= ?
                LIMIT 1
                """,
                (telegram_id, bonus_cutoff),
            ).fetchone()
            if bonus is not None:
                connection.execute("UPDATE purchase_orders SET bonus_days = 3 WHERE id = ?", (order_id,))
            connection.execute(
                "INSERT INTO bot_events (created_at, telegram_id, event_name, properties_json) VALUES (?, ?, ?, ?)",
                (
                    created_at,
                    telegram_id,
                    "checkout_started",
                    json.dumps(
                        {"order_id": order_id, "tariff_id": tariff_id, "months": period_months, "method": payment_method},
                        ensure_ascii=False,
                        separators=(",", ":"),
                    ),
                ),
            )

        return PurchaseOrder(
            id=order_id,
            created_at=created_at,
            telegram_id=telegram_id,
            tariff_id=tariff_id,
            tariff_title=tariff_title,
            period_months=period_months,
            price_rub=price_rub,
            kind=kind,
            status=status,
        )

    def set_provider_payment(
        self,
        *,
        order_id: int,
        transaction_id: str,
        payment_url: str | None,
        provider_amount_rub: int | float | None = None,
    ) -> None:
        self.init()
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            collision = connection.execute(
                """
                SELECT id
                FROM purchase_orders
                WHERE provider_payment_charge_id = ?
                  AND id != ?
                LIMIT 1
                """,
                (transaction_id, order_id),
            ).fetchone()
            if collision is not None:
                raise ProviderPaymentConflict("provider transaction is already assigned")
            connection.execute(
                """
                UPDATE purchase_orders
                SET provider_payment_charge_id = ?,
                    payment_url = ?,
                    provider_amount_rub = ?
                WHERE id = ?
                """,
                (
                    transaction_id,
                    payment_url,
                    str(provider_amount_rub) if provider_amount_rub is not None else None,
                    order_id,
                ),
            )

    def get(self, order_id: int) -> dict[str, object] | None:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute("SELECT * FROM purchase_orders WHERE id = ?", (order_id,)).fetchone()
            return dict(row) if row else None

    def list_user_orders(self, telegram_id: int, *, limit: int = 20) -> list[dict[str, object]]:
        self.init()
        safe_limit = max(1, min(int(limit), 100))
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            rows = connection.execute(
                """
                SELECT *
                FROM purchase_orders
                WHERE telegram_id = ?
                ORDER BY id DESC
                LIMIT ?
                """,
                (telegram_id, safe_limit),
            ).fetchall()
            return [dict(row) for row in rows]

    def get_by_provider_payment(self, transaction_id: str) -> dict[str, object] | None:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            rows = connection.execute(
                "SELECT * FROM purchase_orders WHERE provider_payment_charge_id = ?",
                (transaction_id,),
            ).fetchmany(2)
            return dict(rows[0]) if len(rows) == 1 else None

    def mark_paid(
        self,
        *,
        order_id: int,
        telegram_payment_charge_id: str,
        provider_payment_charge_id: str | None,
    ) -> dict[str, object] | None:
        self.init()
        paid_at = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute("SELECT * FROM purchase_orders WHERE id = ?", (order_id,)).fetchone()
            if row is None:
                return None
            current_status = str(row["status"])
            if current_status in {"pending_payment", "paid_delivery_failed"}:
                connection.execute(
                    """
                    UPDATE purchase_orders
                    SET status = 'paid',
                        paid_at = ?,
                        telegram_payment_charge_id = ?,
                        provider_payment_charge_id = ?,
                        delivery_started_at = NULL
                    WHERE id = ? AND status IN ('pending_payment', 'paid_delivery_failed')
                    """,
                    (paid_at, telegram_payment_charge_id, provider_payment_charge_id, order_id),
                )
                row = connection.execute("SELECT * FROM purchase_orders WHERE id = ?", (order_id,)).fetchone()
            return dict(row) if row else None

    def claim_delivery(self, order_id: int) -> bool:
        self.init()
        now = datetime.now(timezone.utc)
        started_at = now.isoformat()
        stale_before = (now - timedelta(minutes=10)).isoformat()
        with self._connect() as connection:
            cursor = connection.execute(
                """
                UPDATE purchase_orders
                SET status = 'delivering',
                    delivery_started_at = ?,
                    delivery_error = NULL
                WHERE id = ?
                  AND (
                    status IN ('paid', 'paid_delivery_failed')
                    OR (
                        status = 'delivering'
                        AND (
                            delivery_started_at IS NULL
                            OR delivery_started_at <= ?
                        )
                    )
                  )
                """,
                (started_at, order_id, stale_before),
            )
            return cursor.rowcount == 1

    def get_delivery_effect(self, order_id: int) -> dict[str, object] | None:
        self.init()
        with self._connect() as connection:
            row = connection.execute(
                "SELECT effect_json FROM order_delivery_effects WHERE order_id = ?",
                (order_id,),
            ).fetchone()
        if row is None:
            return None
        return self._decode_delivery_effect(str(row[0]))

    def prepare_delivery_effect(
        self,
        *,
        order_id: int,
        telegram_id: int,
        kind: str,
        effect: dict[str, object],
    ) -> dict[str, object]:
        self.init()
        serialized = json.dumps(
            effect,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        if len(serialized.encode("utf-8")) > 32_768:
            raise ValueError("delivery effect is too large")
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            order = connection.execute(
                "SELECT telegram_id, kind, status FROM purchase_orders WHERE id = ?",
                (order_id,),
            ).fetchone()
            if order is None:
                raise DeliveryEffectConflict("delivery order does not exist")
            if int(order[0]) != telegram_id or str(order[1]) != kind:
                raise DeliveryEffectConflict("delivery effect owner or kind mismatch")
            if str(order[2]) != "delivering":
                raise DeliveryEffectConflict("delivery order is not claimed")
            connection.execute(
                """
                INSERT OR IGNORE INTO order_delivery_effects (
                    order_id,
                    created_at,
                    updated_at,
                    telegram_id,
                    kind,
                    effect_json
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (order_id, now, now, telegram_id, kind, serialized),
            )
            row = connection.execute(
                """
                SELECT telegram_id, kind, effect_json
                FROM order_delivery_effects
                WHERE order_id = ?
                """,
                (order_id,),
            ).fetchone()
            if row is None:
                raise RuntimeError("delivery effect was not saved")
            if int(row[0]) != telegram_id or str(row[1]) != kind:
                raise DeliveryEffectConflict("stored delivery effect owner or kind mismatch")
            return self._decode_delivery_effect(str(row[2]))

    def mark_delivered(self, order_id: int) -> None:
        self.init()
        delivered_at = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute("SELECT * FROM purchase_orders WHERE id = ?", (order_id,)).fetchone()
            cursor = connection.execute(
                """
                UPDATE purchase_orders
                SET status = 'delivered',
                    delivered_at = ?,
                    delivery_started_at = NULL,
                    delivery_error = NULL
                WHERE id = ? AND status = 'delivering'
                """,
                (delivered_at, order_id),
            )
            if cursor.rowcount == 1:
                connection.execute(
                    """
                    UPDATE order_delivery_effects
                    SET applied_at = COALESCE(applied_at, ?),
                        updated_at = ?
                    WHERE order_id = ?
                    """,
                    (delivered_at, delivered_at, order_id),
                )
            if row is not None and cursor.rowcount == 1:
                connection.execute(
                    "INSERT INTO bot_events (created_at, telegram_id, event_name, properties_json) VALUES (?, ?, ?, ?)",
                    (
                        delivered_at,
                        int(row["telegram_id"]),
                        "payment_delivered",
                        json.dumps(
                            {"order_id": order_id, "kind": str(row["kind"]), "tariff_id": str(row["tariff_id"])},
                            ensure_ascii=False,
                            separators=(",", ":"),
                        ),
                    ),
                )


    def mark_delivery_failed(self, order_id: int, error: str) -> None:
        self.init()
        safe_error = error[:500]
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE purchase_orders
                SET status = 'paid_delivery_failed',
                    delivery_started_at = NULL,
                    delivery_error = ?
                WHERE id = ? AND status = 'delivering'
                """,
                (safe_error, order_id),
            )

    def mark_payment_canceled(self, order_id: int, status: str) -> None:
        self.init()
        safe_status = status[:32]
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE purchase_orders
                SET status = ?
                WHERE id = ? AND status = 'pending_payment'
                """,
                (safe_status, order_id),
            )

    def register_referral(self, *, invitee_telegram_id: int, referrer_telegram_id: int) -> bool:
        if invitee_telegram_id <= 0 or referrer_telegram_id <= 0 or invitee_telegram_id == referrer_telegram_id:
            return False
        self.init()
        created_at = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            cursor = connection.execute(
                """
                INSERT OR IGNORE INTO referrals (
                    invitee_telegram_id,
                    referrer_telegram_id,
                    created_at
                )
                VALUES (?, ?, ?)
                """,
                (invitee_telegram_id, referrer_telegram_id, created_at),
            )
            return cursor.rowcount == 1

    def get_referral_for_invitee(self, invitee_telegram_id: int) -> dict[str, object] | None:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute(
                "SELECT * FROM referrals WHERE invitee_telegram_id = ?",
                (invitee_telegram_id,),
            ).fetchone()
            return dict(row) if row else None

    def has_delivered_access_purchase(self, telegram_id: int) -> bool:
        self.init()
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT 1
                FROM purchase_orders
                WHERE telegram_id = ?
                  AND kind = 'access_purchase'
                  AND status = 'delivered'
                LIMIT 1
                """,
                (telegram_id,),
            ).fetchone()
            return row is not None

    def referral_discount(self, telegram_id: int, percent: int) -> tuple[int, int | None]:
        if percent <= 0 or self.has_delivered_access_purchase(telegram_id):
            return 0, None
        referral = self.get_referral_for_invitee(telegram_id)
        if referral is None or referral.get("reward_granted_at"):
            return 0, None
        referrer = int(referral.get("referrer_telegram_id") or 0)
        return percent, referrer if referrer > 0 else None

    def mark_referral_reward_granted(self, *, invitee_telegram_id: int, order_id: int) -> dict[str, object] | None:
        self.init()
        granted_at = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            connection.execute(
                """
                UPDATE referrals
                SET first_order_id = ?,
                    reward_granted_at = ?
                WHERE invitee_telegram_id = ?
                  AND reward_granted_at IS NULL
                """,
                (order_id, granted_at, invitee_telegram_id),
            )
            row = connection.execute(
                "SELECT * FROM referrals WHERE invitee_telegram_id = ?",
                (invitee_telegram_id,),
            ).fetchone()
            return dict(row) if row else None

    def referral_stats(self, referrer_telegram_id: int) -> dict[str, int]:
        self.init()
        with self._connect() as connection:
            total = connection.execute(
                "SELECT COUNT(*) FROM referrals WHERE referrer_telegram_id = ?",
                (referrer_telegram_id,),
            ).fetchone()[0]
            rewarded = connection.execute(
                """
                SELECT COUNT(*)
                FROM referrals
                WHERE referrer_telegram_id = ?
                  AND reward_granted_at IS NOT NULL
                """,
                (referrer_telegram_id,),
            ).fetchone()[0]
        return {"total": int(total or 0), "rewarded": int(rewarded or 0)}

    def create_traffic_source(self, *, label: str) -> dict[str, object] | None:
        safe_label = label.strip()[:64]
        if not safe_label:
            return None
        self.init()
        created_at = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            for _ in range(8):
                code = secrets.token_hex(4)
                try:
                    connection.execute(
                        """
                        INSERT INTO traffic_sources (code, label, created_at, is_active)
                        VALUES (?, ?, ?, 1)
                        """,
                        (code, safe_label, created_at),
                    )
                except sqlite3.IntegrityError:
                    continue
                return {"code": code, "label": safe_label, "created_at": created_at, "is_active": 1}
        return None

    def get_traffic_source(self, code: str) -> dict[str, object] | None:
        safe_code = code.strip().lower()[:32]
        if not safe_code:
            return None
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute(
                "SELECT * FROM traffic_sources WHERE code = ?",
                (safe_code,),
            ).fetchone()
            return dict(row) if row else None

    def list_traffic_sources(self) -> list[dict[str, object]]:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            rows = connection.execute(
                "SELECT * FROM traffic_sources ORDER BY created_at DESC"
            ).fetchall()
            return [dict(row) for row in rows]

    def set_traffic_source_active(self, code: str, *, active: bool) -> dict[str, object] | None:
        source = self.get_traffic_source(code)
        if source is None:
            return None
        self.init()
        with self._connect() as connection:
            connection.execute(
                "UPDATE traffic_sources SET is_active = ? WHERE code = ?",
                (1 if active else 0, str(source.get("code") or "")),
            )
        source["is_active"] = 1 if active else 0
        return source

    def traffic_source_overview(self) -> list[dict[str, object]]:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            rows = connection.execute(
                """
                SELECT
                    s.code,
                    s.label,
                    s.created_at,
                    s.is_active,
                    (SELECT COUNT(*) FROM bot_users u WHERE u.first_source_code = s.code) AS users_total,
                    (SELECT COUNT(DISTINCT e.telegram_id) FROM bot_events e
                     WHERE e.event_name = 'trial_created'
                       AND e.telegram_id IN (SELECT u.telegram_id FROM bot_users u WHERE u.first_source_code = s.code)
                    ) AS trial_users,
                    (SELECT COUNT(DISTINCT o.telegram_id) FROM purchase_orders o
                     WHERE o.status = 'delivered' AND o.kind != 'admin_grant'
                       AND o.telegram_id IN (SELECT u.telegram_id FROM bot_users u WHERE u.first_source_code = s.code)
                    ) AS paid_users,
                    (SELECT COALESCE(SUM(o2.price_rub), 0) FROM purchase_orders o2
                     WHERE o2.status = 'delivered' AND o2.kind != 'admin_grant'
                       AND o2.telegram_id IN (SELECT u.telegram_id FROM bot_users u WHERE u.first_source_code = s.code)
                    ) AS revenue_rub
                FROM traffic_sources s
                ORDER BY s.created_at DESC
                """
            ).fetchall()
            return [dict(row) for row in rows]

    def traffic_source_stats(self, code: str) -> dict[str, object] | None:
        source = self.get_traffic_source(code)
        if source is None:
            return None
        safe_code = str(source.get("code") or "")
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            user_counts = connection.execute(
                """
                SELECT COUNT(*) AS users_total,
                       COALESCE(SUM(CASE WHEN last_seen_at >= ? THEN 1 ELSE 0 END), 0) AS active_7d
                FROM bot_users
                WHERE first_source_code = ?
                """,
                ((datetime.now(timezone.utc) - timedelta(days=7)).isoformat(), safe_code),
            ).fetchone()
            events = {
                str(row[0]): int(row[1] or 0)
                for row in connection.execute(
                    """
                    SELECT event_name, COUNT(DISTINCT telegram_id)
                    FROM bot_events
                    WHERE telegram_id IN (SELECT telegram_id FROM bot_users WHERE first_source_code = ?)
                    GROUP BY event_name
                    """,
                    (safe_code,),
                ).fetchall()
            }
            orders = connection.execute(
                """
                SELECT COUNT(*) AS orders_total,
                       COUNT(DISTINCT telegram_id) AS paid_users,
                       COALESCE(SUM(price_rub), 0) AS revenue_rub
                FROM purchase_orders
                WHERE status = 'delivered' AND kind != 'admin_grant'
                  AND telegram_id IN (SELECT telegram_id FROM bot_users WHERE first_source_code = ?)
                """,
                (safe_code,),
            ).fetchone()
        return {
            "source": source,
            "users_total": int(user_counts["users_total"] or 0),
            "active_7d": int(user_counts["active_7d"] or 0),
            "events": events,
            "orders_total": int(orders["orders_total"] or 0),
            "paid_users": int(orders["paid_users"] or 0),
            "revenue_rub": int(orders["revenue_rub"] or 0),
        }

    def record_user_activity(
        self,
        *,
        telegram_id: int,
        username: str | None,
        first_name: str | None,
        event_type: str,
    ) -> None:
        if telegram_id <= 0:
            return
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        safe_username = username.strip().lstrip("@")[:64] if username else None
        normalized_username = safe_username.lower() if safe_username else None
        safe_first_name = first_name.strip()[:128] if first_name else None
        message_delta = 1 if event_type == "message" else 0
        callback_delta = 1 if event_type == "callback_query" else 0
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO bot_users (
                    telegram_id,
                    username,
                    username_normalized,
                    first_name,
                    first_seen_at,
                    last_seen_at,
                    message_count,
                    callback_count
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(telegram_id) DO UPDATE SET
                    username = COALESCE(excluded.username, bot_users.username),
                    username_normalized = COALESCE(excluded.username_normalized, bot_users.username_normalized),
                    first_name = COALESCE(excluded.first_name, bot_users.first_name),
                    last_seen_at = excluded.last_seen_at,
                    message_count = bot_users.message_count + excluded.message_count,
                    callback_count = bot_users.callback_count + excluded.callback_count
                """,
                (
                    telegram_id,
                    safe_username,
                    normalized_username,
                    safe_first_name,
                    now,
                    now,
                    message_delta,
                    callback_delta,
                ),
            )

    def get_trial_access(self, telegram_id: int) -> dict[str, object] | None:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute(
                "SELECT * FROM trial_accesses WHERE telegram_id = ?",
                (telegram_id,),
            ).fetchone()
            return dict(row) if row else None

    def start_trial_access(
        self,
        *,
        telegram_id: int,
        telegram_username: str | None,
        first_name: str | None,
        duration_days: int,
    ) -> tuple[str, dict[str, object]]:
        if telegram_id <= 0:
            raise ValueError("telegram_id must be positive")
        self.init()
        now_dt = datetime.now(timezone.utc)
        now = now_dt.isoformat()
        expires_at = (now_dt + timedelta(days=max(1, duration_days))).isoformat()
        stale_before = (now_dt - timedelta(minutes=5)).isoformat()
        safe_username = telegram_username.strip().lstrip("@")[:64] if telegram_username else None
        safe_first_name = first_name.strip()[:128] if first_name else None

        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            cursor = connection.execute(
                """
                INSERT OR IGNORE INTO trial_accesses (
                    telegram_id,
                    telegram_username,
                    first_name,
                    created_at,
                    updated_at,
                    expires_at,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, 'provisioning')
                """,
                (telegram_id, safe_username, safe_first_name, now, now, expires_at),
            )
            if cursor.rowcount == 1:
                row = connection.execute(
                    "SELECT * FROM trial_accesses WHERE telegram_id = ?",
                    (telegram_id,),
                ).fetchone()
                if row is None:
                    raise RuntimeError("trial access was not created")
                return "started", dict(row)

            row = connection.execute(
                "SELECT * FROM trial_accesses WHERE telegram_id = ?",
                (telegram_id,),
            ).fetchone()
            if row is None:
                raise RuntimeError("trial access was not found")
            current = dict(row)
            status = str(current.get("status") or "")
            if status == "completed":
                return "completed", current
            if status == "provisioning" and str(current.get("updated_at") or "") > stale_before:
                return "in_progress", current

            has_partial_delivery = bool(current.get("regular_user_uuid") or current.get("mobile_user_uuid"))
            if has_partial_delivery and str(current.get("expires_at") or "") <= now:
                return "support_required", current
            if not has_partial_delivery:
                current["expires_at"] = expires_at

            cursor = connection.execute(
                """
                UPDATE trial_accesses
                SET telegram_username = COALESCE(?, telegram_username),
                    first_name = COALESCE(?, first_name),
                    updated_at = ?,
                    expires_at = ?,
                    status = 'provisioning',
                    delivery_error = NULL
                WHERE telegram_id = ?
                  AND status != 'completed'
                  AND (status != 'provisioning' OR updated_at <= ?)
                """,
                (
                    safe_username,
                    safe_first_name,
                    now,
                    str(current["expires_at"]),
                    telegram_id,
                    stale_before,
                ),
            )
            row = connection.execute(
                "SELECT * FROM trial_accesses WHERE telegram_id = ?",
                (telegram_id,),
            ).fetchone()
            if row is None:
                raise RuntimeError("trial access disappeared")
            return ("started" if cursor.rowcount == 1 else "in_progress"), dict(row)

    def mark_trial_component(self, telegram_id: int, component: str, user_uuid: str) -> dict[str, object]:
        columns = {
            "regular": "regular_user_uuid",
            "mobile": "mobile_user_uuid",
        }
        column = columns.get(component)
        if column is None:
            raise ValueError("unknown trial component")
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            connection.execute(
                f"UPDATE trial_accesses SET {column} = ?, updated_at = ? WHERE telegram_id = ?",
                (user_uuid, now, telegram_id),
            )
            row = connection.execute(
                "SELECT * FROM trial_accesses WHERE telegram_id = ?",
                (telegram_id,),
            ).fetchone()
            if row is None:
                raise RuntimeError("trial access was not found")
            return dict(row)

    def mark_trial_completed(self, telegram_id: int) -> dict[str, object]:
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            cursor = connection.execute(
                """
                UPDATE trial_accesses
                SET status = 'completed',
                    updated_at = ?,
                    completed_at = ?,
                    delivery_error = NULL
                WHERE telegram_id = ?
                  AND status = 'provisioning'
                  AND (regular_user_uuid IS NOT NULL OR mobile_user_uuid IS NOT NULL)
                """,
                (now, now, telegram_id),
            )
            if cursor.rowcount != 1:
                raise RuntimeError("trial access is incomplete")
            row = connection.execute(
                "SELECT * FROM trial_accesses WHERE telegram_id = ?",
                (telegram_id,),
            ).fetchone()
            if row is None:
                raise RuntimeError("trial access was not found")
            return dict(row)

    def mark_trial_failed(self, telegram_id: int, error: str) -> None:
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE trial_accesses
                SET status = 'failed', updated_at = ?, delivery_error = ?
                WHERE telegram_id = ? AND status = 'provisioning'
                """,
                (now, error[:500], telegram_id),
            )

    def pending_trial_admin_notifications(self, limit: int = 20) -> list[dict[str, object]]:
        self.init()
        safe_limit = max(1, min(limit, 100))
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            rows = connection.execute(
                """
                SELECT *
                FROM trial_accesses
                WHERE status = 'completed' AND admin_notified_at IS NULL
                ORDER BY completed_at ASC
                LIMIT ?
                """,
                (safe_limit,),
            ).fetchall()
            return [dict(row) for row in rows]

    def mark_trial_admin_notified(self, telegram_id: int) -> None:
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE trial_accesses
                SET admin_notified_at = COALESCE(admin_notified_at, ?), updated_at = ?
                WHERE telegram_id = ? AND status = 'completed'
                """,
                (now, now, telegram_id),
            )

    def record_user_start(
        self,
        *,
        telegram_id: int,
        username: str | None,
        first_name: str | None,
        source_code: str | None = None,
    ) -> None:
        if telegram_id <= 0:
            return
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        safe_username = username.strip().lstrip("@")[:64] if username else None
        normalized_username = safe_username.lower() if safe_username else None
        safe_first_name = first_name.strip()[:128] if first_name else None
        safe_source_code = source_code.strip().lower()[:32] if source_code else None
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO bot_users (
                    telegram_id,
                    username,
                    username_normalized,
                    first_name,
                    first_seen_at,
                    last_seen_at,
                    first_started_at,
                    last_started_at,
                    start_count,
                    first_source_code
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                ON CONFLICT(telegram_id) DO UPDATE SET
                    username = COALESCE(excluded.username, bot_users.username),
                    username_normalized = COALESCE(excluded.username_normalized, bot_users.username_normalized),
                    first_name = COALESCE(excluded.first_name, bot_users.first_name),
                    last_seen_at = excluded.last_seen_at,
                    first_started_at = COALESCE(bot_users.first_started_at, excluded.first_started_at),
                    last_started_at = excluded.last_started_at,
                    start_count = bot_users.start_count + 1,
                    first_source_code = COALESCE(bot_users.first_source_code, excluded.first_source_code)
                """,
                (
                    telegram_id,
                    safe_username,
                    normalized_username,
                    safe_first_name,
                    now,
                    now,
                    now,
                    now,
                    safe_source_code,
                ),
            )

    def resolve_telegram_id(self, identifier: str) -> int | None:
        value = identifier.strip()
        if not value:
            return None
        if value.startswith("@"):
            value = value[1:]
        try:
            telegram_id = int(value)
        except ValueError:
            telegram_id = 0
        if telegram_id > 0:
            return telegram_id

        normalized = value.lower()
        self.init()
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT telegram_id
                FROM bot_users
                WHERE username_normalized = ?
                ORDER BY last_seen_at DESC
                LIMIT 1
                """,
                (normalized,),
            ).fetchone()
            if row is not None:
                return int(row[0])

            row = connection.execute(
                """
                SELECT telegram_id
                FROM purchase_orders
                WHERE LOWER(COALESCE(telegram_username, '')) = ?
                ORDER BY created_at DESC
                LIMIT 1
                """,
                (normalized,),
            ).fetchone()
            return int(row[0]) if row is not None else None

    def known_telegram_ids(self) -> set[int]:
        self.init()
        values: set[int] = set()
        with self._connect() as connection:
            queries = [
                "SELECT telegram_id FROM bot_users",
                "SELECT telegram_id FROM purchase_orders",
                "SELECT telegram_id FROM wdtt_accesses",
                "SELECT telegram_id FROM free_mtproto_proxies",
                "SELECT telegram_id FROM trial_accesses",
                "SELECT invitee_telegram_id FROM referrals",
                "SELECT referrer_telegram_id FROM referrals",
                "SELECT telegram_id FROM cabinet_grants WHERE telegram_id > 0",
                "SELECT actor_id FROM cabinet_actors WHERE actor_kind = 'telegram'",
            ]
            for query in queries:
                for row in connection.execute(query).fetchall():
                    try:
                        telegram_id = int(row[0] or 0)
                    except (TypeError, ValueError):
                        telegram_id = 0
                    if telegram_id > 0:
                        values.add(telegram_id)
        return values

    def activity_stats(self) -> dict[str, int]:
        self.init()
        now = datetime.now(timezone.utc)
        cutoffs = {
            "active_24h": now - timedelta(days=1),
            "active_7d": now - timedelta(days=7),
            "active_30d": now - timedelta(days=30),
        }
        with self._connect() as connection:
            total = int(connection.execute("SELECT COUNT(*) FROM bot_users").fetchone()[0] or 0)
            started = connection.execute(
                """
                SELECT COUNT(*), COALESCE(SUM(start_count), 0)
                FROM bot_users
                WHERE start_count > 0
                """
            ).fetchone()
            result = {
                "tracked_users": total,
                "started_users": int(started[0] or 0),
                "start_events": int(started[1] or 0),
            }
            for key, cutoff in cutoffs.items():
                row = connection.execute(
                    "SELECT COUNT(*) FROM bot_users WHERE last_seen_at >= ?",
                    (cutoff.isoformat(),),
                ).fetchone()
                result[key] = int(row[0] or 0)
        return result

    def order_stats(self) -> dict[str, int]:
        self.init()
        cutoff_30d = (datetime.now(timezone.utc) - timedelta(days=30)).isoformat()
        with self._connect() as connection:
            delivered = connection.execute(
                "SELECT COUNT(*), COALESCE(SUM(price_rub), 0) FROM purchase_orders WHERE status = 'delivered'",
            ).fetchone()
            delivered_30d = connection.execute(
                """
                SELECT COUNT(*), COALESCE(SUM(price_rub), 0)
                FROM purchase_orders
                WHERE status = 'delivered' AND delivered_at >= ?
                """,
                (cutoff_30d,),
            ).fetchone()
            admin_grants = connection.execute(
                "SELECT COUNT(*) FROM purchase_orders WHERE kind = 'admin_grant' AND status = 'delivered'",
            ).fetchone()
        return {
            "delivered": int(delivered[0] or 0),
            "revenue_rub": int(delivered[1] or 0),
            "delivered_30d": int(delivered_30d[0] or 0),
            "revenue_30d_rub": int(delivered_30d[1] or 0),
            "admin_grants": int(admin_grants[0] or 0),
        }

    def funnel_stats(self) -> dict[str, int]:
        self.init()
        with self._connect() as connection:
            result = {
                str(row[0]): int(row[1] or 0)
                for row in connection.execute(
                    "SELECT event_name, COUNT(DISTINCT telegram_id) FROM bot_events GROUP BY event_name"
                ).fetchall()
            }
            result["trials_completed"] = int(
                connection.execute("SELECT COUNT(*) FROM trial_accesses WHERE status = 'completed'").fetchone()[0] or 0
            )
            result["referrals_created"] = int(connection.execute("SELECT COUNT(*) FROM referrals").fetchone()[0] or 0)
            result["referrals_converted"] = int(
                connection.execute("SELECT COUNT(*) FROM referrals WHERE first_order_id IS NOT NULL").fetchone()[0] or 0
            )
            result["online_paid_users"] = int(
                connection.execute(
                    """
                    SELECT COUNT(DISTINCT telegram_id) FROM purchase_orders
                    WHERE status = 'delivered' AND kind IN ('access_purchase', 'access_renewal')
                      AND payment_method LIKE 'platega_%'
                    """
                ).fetchone()[0] or 0
            )
        return result

    def recent_orders(self, limit: int = 5) -> list[dict[str, object]]:
        self.init()
        safe_limit = max(1, min(limit, 20))
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            rows = connection.execute(
                """
                SELECT id, created_at, telegram_id, telegram_username, tariff_title,
                       period_months, kind, status, price_rub
                FROM purchase_orders
                ORDER BY id DESC
                LIMIT ?
                """,
                (safe_limit,),
            ).fetchall()
            return [dict(row) for row in rows]

    def ensure_subscription_reminder(self, *, telegram_id: int, user_uuid: str, expire_at: str) -> dict[str, object]:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            connection.execute(
                """
                INSERT OR IGNORE INTO subscription_reminders (
                    created_at,
                    updated_at,
                    telegram_id,
                    user_uuid,
                    expire_at
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                (now, now, telegram_id, user_uuid, expire_at),
            )
            connection.execute(
                """
                UPDATE subscription_reminders
                SET telegram_id = ?, updated_at = ?
                WHERE user_uuid = ? AND expire_at = ?
                """,
                (telegram_id, now, user_uuid, expire_at),
            )
            row = connection.execute(
                """
                SELECT *
                FROM subscription_reminders
                WHERE user_uuid = ? AND expire_at = ?
                """,
                (user_uuid, expire_at),
            ).fetchone()
            if row is None:
                raise RuntimeError("subscription reminder was not created")
            return dict(row)

    def get_subscription_reminder(self, reminder_id: int) -> dict[str, object] | None:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute("SELECT * FROM subscription_reminders WHERE id = ?", (reminder_id,)).fetchone()
            return dict(row) if row else None

    def mark_subscription_reminder_sent(self, reminder_id: int, sent_date: str) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE subscription_reminders
                SET last_sent_date = ?, updated_at = ?
                WHERE id = ?
                """,
                (sent_date, now, reminder_id),
            )

    def decline_subscription_reminder(self, reminder_id: int) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE subscription_reminders
                SET declined_at = ?, updated_at = ?
                WHERE id = ?
                """,
                (now, now, reminder_id),
            )

    def create_subscription_addon(
        self,
        *,
        telegram_id: int,
        user_uuid: str,
        order_id: int,
        kind: str,
        slots_delta: int,
        slots_persistent: bool,
        traffic_delta_bytes: int,
        expires_at: str,
    ) -> dict[str, object]:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            connection.execute(
                """
                INSERT INTO subscription_addons (
                    created_at,
                    updated_at,
                    telegram_id,
                    user_uuid,
                    order_id,
                    kind,
                    slots_delta,
                    slots_persistent,
                    traffic_delta_bytes,
                    expires_at,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active')
                ON CONFLICT(order_id) DO UPDATE SET
                    updated_at = excluded.updated_at,
                    telegram_id = excluded.telegram_id,
                    user_uuid = excluded.user_uuid,
                    kind = excluded.kind,
                    slots_delta = excluded.slots_delta,
                    slots_persistent = excluded.slots_persistent,
                    traffic_delta_bytes = excluded.traffic_delta_bytes,
                    expires_at = excluded.expires_at,
                    status = 'active'
                """,
                (
                    now,
                    now,
                    telegram_id,
                    user_uuid,
                    order_id,
                    kind[:32],
                    max(0, slots_delta),
                    1 if slots_persistent else 0,
                    max(0, traffic_delta_bytes),
                    expires_at,
                ),
            )
            row = connection.execute("SELECT * FROM subscription_addons WHERE order_id = ?", (order_id,)).fetchone()
            if row is None:
                raise RuntimeError("subscription addon was not saved")
            return dict(row)

    def upsert_multi_subscription(
        self,
        *,
        telegram_id: int,
        primary_user_uuid: str,
        mobile_user_uuid: str,
    ) -> dict[str, object]:
        self.init()
        if not primary_user_uuid or not mobile_user_uuid or primary_user_uuid == mobile_user_uuid:
            raise ValueError("multi subscription references are invalid")
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            existing = connection.execute(
                "SELECT * FROM multi_subscriptions WHERE primary_user_uuid = ?",
                (primary_user_uuid,),
            ).fetchone()
            token = str(existing["token"]) if existing is not None else secrets.token_urlsafe(32)
            connection.execute(
                """
                INSERT INTO multi_subscriptions (
                    created_at,
                    updated_at,
                    telegram_id,
                    primary_user_uuid,
                    mobile_user_uuid,
                    token,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, 'active')
                ON CONFLICT(primary_user_uuid) DO UPDATE SET
                    updated_at = excluded.updated_at,
                    telegram_id = excluded.telegram_id,
                    mobile_user_uuid = excluded.mobile_user_uuid,
                    status = 'active'
                """,
                (now, now, telegram_id, primary_user_uuid, mobile_user_uuid, token),
            )
            row = connection.execute(
                "SELECT * FROM multi_subscriptions WHERE primary_user_uuid = ?",
                (primary_user_uuid,),
            ).fetchone()
            if row is None:
                raise RuntimeError("multi subscription was not saved")
            return dict(row)

    def get_multi_subscription_by_user_uuid(self, user_uuid: str) -> dict[str, object] | None:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute(
                """
                SELECT *
                FROM multi_subscriptions
                WHERE status = 'active'
                  AND (primary_user_uuid = ? OR mobile_user_uuid = ?)
                """,
                (user_uuid, user_uuid),
            ).fetchone()
            return dict(row) if row else None

    def get_multi_subscription_by_token(self, token: str) -> dict[str, object] | None:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute(
                "SELECT * FROM multi_subscriptions WHERE token = ? AND status = 'active'",
                (token,),
            ).fetchone()
            return dict(row) if row else None

    def list_multi_subscriptions_by_telegram_id(self, telegram_id: int) -> list[dict[str, object]]:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            rows = connection.execute(
                """
                SELECT *
                FROM multi_subscriptions
                WHERE telegram_id = ? AND status = 'active'
                ORDER BY id ASC
                """,
                (telegram_id,),
            ).fetchall()
            return [dict(row) for row in rows]

    def list_active_multi_subscriptions(self) -> list[dict[str, object]]:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            rows = connection.execute(
                """
                SELECT *
                FROM multi_subscriptions
                WHERE status = 'active'
                ORDER BY id ASC
                """
            ).fetchall()
            return [dict(row) for row in rows]

    def rotate_multi_subscription_token(self, primary_user_uuid: str) -> dict[str, object] | None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        token = secrets.token_urlsafe(32)
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            connection.execute(
                """
                UPDATE multi_subscriptions
                SET token = ?, updated_at = ?
                WHERE primary_user_uuid = ? AND status = 'active'
                """,
                (token, now, primary_user_uuid),
            )
            row = connection.execute(
                "SELECT * FROM multi_subscriptions WHERE primary_user_uuid = ? AND status = 'active'",
                (primary_user_uuid,),
            ).fetchone()
            return dict(row) if row else None

    def get_shield_enabled(self, remnawave_user_id: int) -> bool:
        self.init()
        if remnawave_user_id <= 0:
            return False
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT enabled
                FROM subscription_shield_settings
                WHERE remnawave_user_id = ?
                """,
                (remnawave_user_id,),
            ).fetchone()
        return bool(row and int(row[0]) == 1)

    def set_shield_enabled(
        self,
        *,
        remnawave_user_id: int,
        telegram_id: int,
        user_uuid: str,
        enabled: bool,
    ) -> None:
        self.init()
        if remnawave_user_id <= 0 or telegram_id <= 0 or not user_uuid:
            raise ValueError("invalid subscription Shield owner")
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO subscription_shield_settings (
                    remnawave_user_id,
                    telegram_id,
                    user_uuid,
                    enabled,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(remnawave_user_id) DO UPDATE SET
                    telegram_id = excluded.telegram_id,
                    user_uuid = excluded.user_uuid,
                    enabled = excluded.enabled,
                    updated_at = excluded.updated_at
                """,
                (
                    remnawave_user_id,
                    telegram_id,
                    user_uuid,
                    1 if enabled else 0,
                    now,
                    now,
                ),
            )

    def active_subscription_addon_totals(self, user_uuid: str, *, now: str | None = None) -> dict[str, int]:
        self.init()
        now_value = now or datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT
                    COALESCE(SUM(
                        CASE
                            WHEN slots_persistent = 1
                              OR (status = 'active' AND expires_at > ?)
                            THEN slots_delta
                            ELSE 0
                        END
                    ), 0),
                    COALESCE(SUM(
                        CASE
                            WHEN status = 'active' AND expires_at > ?
                            THEN traffic_delta_bytes
                            ELSE 0
                        END
                    ), 0)
                FROM subscription_addons
                WHERE user_uuid = ?
                """,
                (now_value, now_value, user_uuid),
            ).fetchone()
        return {
            "slots_delta": int(row[0] or 0) if row else 0,
            "traffic_delta_bytes": int(row[1] or 0) if row else 0,
        }

    def expired_subscription_addons(self, *, now: str | None = None, limit: int = 200) -> list[dict[str, object]]:
        self.init()
        safe_limit = max(1, min(limit, 1000))
        now_value = now or datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            rows = connection.execute(
                """
                SELECT *
                FROM subscription_addons
                WHERE status = 'active'
                  AND expires_at <= ?
                ORDER BY expires_at ASC, id ASC
                LIMIT ?
                """,
                (now_value, safe_limit),
            ).fetchall()
            return [dict(row) for row in rows]

    def mark_subscription_addons_expired(self, addon_ids: list[int]) -> None:
        ids = [int(addon_id) for addon_id in addon_ids if int(addon_id) > 0]
        if not ids:
            return
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        placeholders = ", ".join("?" for _ in ids)
        with self._connect() as connection:
            connection.execute(
                f"""
                UPDATE subscription_addons
                SET status = 'expired',
                    updated_at = ?
                WHERE id IN ({placeholders})
                """,
                (now, *ids),
            )

    def get_free_mtproto_proxy(self, telegram_id: int) -> dict[str, object] | None:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute(
                """
                SELECT *
                FROM free_mtproto_proxies
                WHERE telegram_id = ?
                  AND status = 'active'
                """,
                (telegram_id,),
            ).fetchone()
            return dict(row) if row else None

    def upsert_free_mtproto_proxy(
        self,
        *,
        telegram_id: int,
        mtproxy_label: str,
        proxy_link: str,
        rate_limit_mbps: int,
        device_limit: int,
        max_tcp_connections: int,
        max_unique_ips: int,
    ) -> dict[str, object]:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        safe_label = mtproxy_label.strip()[:32]
        safe_link = proxy_link.strip()[:2048]
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            connection.execute(
                """
                INSERT INTO free_mtproto_proxies (
                    telegram_id,
                    created_at,
                    updated_at,
                    mtproxy_label,
                    proxy_link,
                    rate_limit_mbps,
                    device_limit,
                    max_tcp_connections,
                    max_unique_ips,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'active')
                ON CONFLICT(telegram_id) DO UPDATE SET
                    updated_at = excluded.updated_at,
                    mtproxy_label = excluded.mtproxy_label,
                    proxy_link = excluded.proxy_link,
                    rate_limit_mbps = excluded.rate_limit_mbps,
                    device_limit = excluded.device_limit,
                    max_tcp_connections = excluded.max_tcp_connections,
                    max_unique_ips = excluded.max_unique_ips,
                    status = 'active'
                """,
                (
                    telegram_id,
                    now,
                    now,
                    safe_label,
                    safe_link,
                    max(1, rate_limit_mbps),
                    max(1, device_limit),
                    max(1, max_tcp_connections),
                    max(1, max_unique_ips),
                ),
            )
            row = connection.execute(
                "SELECT * FROM free_mtproto_proxies WHERE telegram_id = ?",
                (telegram_id,),
            ).fetchone()
            if row is None:
                raise RuntimeError("free MTProto proxy was not saved")
            return dict(row)

    def get_wdtt_access_by_user_uuid(self, user_uuid: str) -> dict[str, object] | None:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute(
                "SELECT * FROM wdtt_accesses WHERE user_uuid = ? AND status = 'active'",
                (user_uuid,),
            ).fetchone()
            return dict(row) if row else None

    def get_wdtt_access_by_token(self, token: str) -> dict[str, object] | None:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute(
                "SELECT * FROM wdtt_accesses WHERE token = ? AND status = 'active'",
                (token,),
            ).fetchone()
            return dict(row) if row else None

    def get_wdtt_accesses_by_telegram_id(self, telegram_id: int) -> list[dict[str, object]]:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            rows = connection.execute(
                """
                SELECT *
                FROM wdtt_accesses
                WHERE telegram_id = ?
                  AND status = 'active'
                ORDER BY expires_at DESC, updated_at DESC
                """,
                (telegram_id,),
            ).fetchall()
            return [dict(row) for row in rows]

    def get_active_wdtt_accesses(self) -> list[dict[str, object]]:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            rows = connection.execute(
                """
                SELECT *
                FROM wdtt_accesses
                WHERE status = 'active'
                ORDER BY updated_at DESC
                """
            ).fetchall()
            return [dict(row) for row in rows]

    def update_wdtt_max_devices(self, user_uuid: str, max_devices: int) -> dict[str, object] | None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            connection.execute(
                """
                UPDATE wdtt_accesses
                SET max_devices = ?,
                    updated_at = ?
                WHERE user_uuid = ?
                  AND status = 'active'
                """,
                (max_devices, now, user_uuid),
            )
            row = connection.execute(
                "SELECT * FROM wdtt_accesses WHERE user_uuid = ? AND status = 'active'",
                (user_uuid,),
            ).fetchone()
            return dict(row) if row else None

    def upsert_wdtt_access(
        self,
        *,
        telegram_id: int,
        user_uuid: str,
        order_id: int,
        password: str,
        token: str,
        label: str,
        peer: str,
        hashes: str,
        workers: int,
        port: int,
        expires_at: int,
        max_devices: int,
    ) -> dict[str, object]:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            existing = connection.execute(
                "SELECT * FROM wdtt_accesses WHERE user_uuid = ?",
                (user_uuid,),
            ).fetchone()
            if existing is None:
                connection.execute(
                    """
                    INSERT INTO wdtt_accesses (
                        created_at, updated_at, telegram_id, user_uuid, order_id,
                        password, token, label, peer, hashes, workers, port,
                        expires_at, max_devices, status
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active')
                    """,
                    (
                        now,
                        now,
                        telegram_id,
                        user_uuid,
                        order_id,
                        password,
                        token,
                        label,
                        peer,
                        hashes,
                        workers,
                        port,
                        expires_at,
                        max_devices,
                    ),
                )
            else:
                connection.execute(
                    """
                    UPDATE wdtt_accesses
                    SET updated_at = ?,
                        telegram_id = ?,
                        order_id = ?,
                        password = ?,
                        token = ?,
                        label = ?,
                        peer = ?,
                        hashes = ?,
                        workers = ?,
                        port = ?,
                        expires_at = ?,
                        max_devices = ?,
                        status = 'active'
                    WHERE user_uuid = ?
                    """,
                    (
                        now,
                        telegram_id,
                        order_id,
                        password,
                        token,
                        label,
                        peer,
                        hashes,
                        workers,
                        port,
                        expires_at,
                        max_devices,
                        user_uuid,
                    ),
                )
            row = connection.execute(
                "SELECT * FROM wdtt_accesses WHERE user_uuid = ?",
                (user_uuid,),
            ).fetchone()
            if row is None:
                raise RuntimeError("wdtt access was not saved")
            return dict(row)

    def get_wdtt_remnawave_migration(self, source_user_uuid: str) -> dict[str, object] | None:
        self.init()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute(
                "SELECT * FROM wdtt_remnawave_migrations WHERE source_user_uuid = ?",
                (source_user_uuid,),
            ).fetchone()
            return dict(row) if row else None

    def upsert_wdtt_remnawave_migration(
        self,
        *,
        source_user_uuid: str,
        telegram_id: int,
        remnawave_user_uuid: str,
    ) -> dict[str, object]:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            connection.execute(
                """
                INSERT INTO wdtt_remnawave_migrations (
                    source_user_uuid,
                    telegram_id,
                    remnawave_user_uuid,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(source_user_uuid) DO UPDATE SET
                    telegram_id = excluded.telegram_id,
                    remnawave_user_uuid = excluded.remnawave_user_uuid,
                    updated_at = excluded.updated_at
                """,
                (source_user_uuid, telegram_id, remnawave_user_uuid, now, now),
            )
            row = connection.execute(
                "SELECT * FROM wdtt_remnawave_migrations WHERE source_user_uuid = ?",
                (source_user_uuid,),
            ).fetchone()
            if row is None:
                raise RuntimeError("wdtt remnawave migration was not saved")
            return dict(row)

    def mark_wdtt_remnawave_announcement_sent(self, source_user_uuid: str) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE wdtt_remnawave_migrations
                SET announcement_sent_at = COALESCE(announcement_sent_at, ?),
                    updated_at = ?
                WHERE source_user_uuid = ?
                """,
                (now, now, source_user_uuid),
            )

    def record_event(
        self,
        *,
        telegram_id: int,
        event_name: str,
        properties: dict[str, object] | None = None,
    ) -> None:
        if telegram_id <= 0:
            return
        safe_name = event_name.strip()[:64]
        if not safe_name:
            return
        payload = json.dumps(properties or {}, ensure_ascii=False, separators=(",", ":"))[:2000]
        self.init()
        with self._connect() as connection:
            connection.execute(
                "INSERT INTO bot_events (created_at, telegram_id, event_name, properties_json) VALUES (?, ?, ?, ?)",
                (datetime.now(timezone.utc).isoformat(), telegram_id, safe_name, payload),
            )

    def set_trial_selection(self, telegram_id: int, *, tariff_id: str, component: str) -> None:
        if component not in {"regular", "mobile"}:
            raise ValueError("unknown trial component")
        self.init()
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE trial_accesses
                SET selected_tariff_id = ?, selected_component = ?, updated_at = ?
                WHERE telegram_id = ? AND status != 'completed'
                """,
                (tariff_id[:32], component, datetime.now(timezone.utc).isoformat(), telegram_id),
            )

    def set_trial_platform(self, telegram_id: int, platform: str) -> None:
        self.init()
        with self._connect() as connection:
            connection.execute(
                "UPDATE trial_accesses SET platform = ?, updated_at = ? WHERE telegram_id = ?",
                (platform[:32], datetime.now(timezone.utc).isoformat(), telegram_id),
            )

    def mark_trial_first_traffic(self, telegram_id: int) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE trial_accesses
                SET first_traffic_at = COALESCE(first_traffic_at, ?), updated_at = ?
                WHERE telegram_id = ? AND status = 'completed'
                """,
                (now, now, telegram_id),
            )

    def completed_trials(self, limit: int = 1000) -> list[dict[str, object]]:
        self.init()
        safe_limit = max(1, min(limit, 1000))
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            rows = connection.execute(
                "SELECT * FROM trial_accesses WHERE status = 'completed' ORDER BY completed_at DESC LIMIT ?",
                (safe_limit,),
            ).fetchall()
            return [dict(row) for row in rows]

    def pending_checkout_orders(
        self,
        *,
        older_than_minutes: int,
        newer_than_hours: int = 48,
        limit: int = 100,
    ) -> list[dict[str, object]]:
        self.init()
        now = datetime.now(timezone.utc)
        older = (now - timedelta(minutes=max(1, older_than_minutes))).isoformat()
        newer = (now - timedelta(hours=max(1, newer_than_hours))).isoformat()
        safe_limit = max(1, min(limit, 500))
        with self._connect() as connection:
            connection.row_factory = sqlite3.Row
            rows = connection.execute(
                """
                SELECT * FROM purchase_orders
                WHERE status = 'pending_payment'
                  AND payment_url IS NOT NULL
                  AND created_at <= ?
                  AND created_at >= ?
                ORDER BY created_at ASC
                LIMIT ?
                """,
                (older, newer, safe_limit),
            ).fetchall()
            return [dict(row) for row in rows]

    def lifecycle_message_sent(self, telegram_id: int, message_kind: str, reference_key: str) -> bool:
        self.init()
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT 1 FROM lifecycle_messages
                WHERE telegram_id = ? AND message_kind = ? AND reference_key = ? AND status = 'sent'
                LIMIT 1
                """,
                (telegram_id, message_kind[:64], reference_key[:128]),
            ).fetchone()
            return row is not None

    def lifecycle_message_sent_at(self, telegram_id: int, message_kind: str, reference_key: str) -> str | None:
        self.init()
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT sent_at FROM lifecycle_messages
                WHERE telegram_id = ? AND message_kind = ? AND reference_key = ? AND status = 'sent'
                LIMIT 1
                """,
                (telegram_id, message_kind[:64], reference_key[:128]),
            ).fetchone()
            return str(row[0]) if row and row[0] else None

    def mark_lifecycle_message(
        self,
        *,
        telegram_id: int,
        message_kind: str,
        reference_key: str,
        sent: bool,
        error_code: str | None = None,
    ) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        status = "sent" if sent else "failed"
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO lifecycle_messages (
                    created_at, telegram_id, message_kind, reference_key, status, sent_at, error_code
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(telegram_id, message_kind, reference_key) DO UPDATE SET
                    status = excluded.status,
                    sent_at = excluded.sent_at,
                    error_code = excluded.error_code
                """,
                (
                    now,
                    telegram_id,
                    message_kind[:64],
                    reference_key[:128],
                    status,
                    now if sent else None,
                    (error_code or "")[:64] or None,
                ),
            )

    def _ensure_column(self, connection: sqlite3.Connection, table: str, column: str, definition: str) -> None:
        rows = connection.execute(f"PRAGMA table_info({table})").fetchall()
        existing = {str(row[1]) for row in rows}
        if column not in existing:
            connection.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")

    @staticmethod
    def _decode_delivery_effect(serialized: str) -> dict[str, object]:
        try:
            payload = json.loads(serialized)
        except json.JSONDecodeError as exc:
            raise RuntimeError("stored delivery effect is invalid") from exc
        if not isinstance(payload, dict):
            raise TypeError("stored delivery effect is invalid")
        return payload

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self._db_path)
        try:
            connection.execute("PRAGMA busy_timeout=5000")
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("PRAGMA foreign_keys=ON")
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    @contextmanager
    def _order_creation_connection(self) -> Iterator[sqlite3.Connection]:
        try:
            with self._connect() as connection:
                yield connection
        except sqlite3.IntegrityError as exc:
            if "order_already_in_progress" in str(exc):
                raise OrderAlreadyInProgress("order_already_in_progress") from exc
            raise
