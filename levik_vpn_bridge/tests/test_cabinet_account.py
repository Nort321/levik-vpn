from __future__ import annotations

import base64
import hashlib
import json
import secrets
import tempfile
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from uuid import uuid4

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from app.cabinet_api import (
    BASE_PATH,
    _account_grant_token,
    register_cabinet_routes,
)
from app.cabinet_auth import (
    cabinet_signature,
    canonical_request,
    opaque_token_hash,
)
from app.cabinet_service import cabinet_user_key
from app.delivery import payment_payload
from app.orders import CabinetAccountConflict, OrderStore
from app.webhook import handle_platega_callback


BRIDGE_SECRET = base64.urlsafe_b64encode(b"b" * 32).decode("ascii").rstrip("=")
SUBJECT_SECRET = base64.urlsafe_b64encode(b"s" * 32).decode("ascii").rstrip("=")


def _make_test_settings(data_dir: Path) -> SimpleNamespace:
    return SimpleNamespace(
        data_dir=data_dir,
        data={
            "timezone": "Europe/Moscow",
            "platega": {"enabled": True},
            "payments": {
                "methods": [
                    {
                        "id": "sbp",
                        "title": "SBP",
                        "enabled": True,
                        "platega_method": 2,
                    }
                ]
            },
            "purchase_periods": [{"months": 1, "title": "1 month"}],
            "tariffs": [
                {
                    "id": "regular",
                    "title": "Levik VPN",
                    "description": "Regular VPN access",
                    "base_price_rub": 100,
                    "traffic_limit_bytes": 0,
                    "traffic_limit_strategy": "NO_RESET",
                    "hwid_device_limit": 5,
                    "internal_squads": [],
                    "purchase_enabled": True,
                }
            ],
            "referrals": {
                "enabled": False,
                "bot_username": "levikvpnbot",
                "discount_percent": 0,
                "reward_days": 0,
            },
            "slots": {"enabled": False},
            "mobile_traffic": {"enabled": False},
            "trial": {"enabled": False},
        },
        cabinet_bridge_secret=BRIDGE_SECRET,
        cabinet_subject_secret=SUBJECT_SECRET,
        cabinet_bridge_key_id="cabinet-v1",
        cabinet_hmac_clock_skew_seconds=60,
        cabinet_device_code_ttl_seconds=300,
        cabinet_grant_ttl_seconds=3600,
        cabinet_payment_return_url="https://leviknet.com/dashboard",
        cabinet_payment_failed_url="https://leviknet.com/dashboard",
        cabinet_payment_redirect_hosts=("pay.platega.io",),
        platega_merchant_id="merchant-test",
        platega_api_key="provider-test-secret",
        wdtt_api_token="",
        mtproto_provisioner_url="",
        mtproto_provisioner_token="",
        request_timeout=1.0,
    )


class FakeRemnawave:
    def __init__(self) -> None:
        self.users: list[dict[str, object]] = []
        self.create_calls: list[dict[str, object]] = []

    async def get_users_by_telegram_id(self, actor_id: int) -> list[dict[str, object]]:
        return [
            dict(user)
            for user in self.users
            if int(user.get("telegramId") or 0) == actor_id
        ]

    async def get_internal_squads(self) -> list[dict[str, object]]:
        return []

    async def create_user(self, body: dict[str, object]) -> dict[str, object]:
        self.create_calls.append(dict(body))
        user = {
            **body,
            "id": len(self.users) + 1,
            "uuid": str(uuid4()),
            "status": "ACTIVE",
            "subscriptionUrl": f"https://sub.leviknet.com:2096/{len(self.users) + 1}",
            "activeInternalSquads": [],
            "userTraffic": {"usedTrafficBytes": 0},
        }
        self.users.append(user)
        return dict(user)

    async def get_user_devices(self, _: str) -> list[dict[str, object]]:
        return []


class FakePlategaClient:
    transactions: dict[str, dict[str, object]] = {}

    def __init__(self, settings: SimpleNamespace) -> None:
        self.settings = settings

    @classmethod
    def reset(cls) -> None:
        cls.transactions = {}

    async def __aenter__(self) -> FakePlategaClient:
        return self

    async def __aexit__(self, *_: object) -> None:
        return None

    async def create_transaction(
        self,
        *,
        payment_method: int,
        amount_rub: int | float,
        description: str,
        return_url: str,
        failed_url: str,
        payload: str,
        telegram_id: int,
        username: str | None,
    ) -> dict[str, object]:
        transaction_id = f"tx-{len(self.transactions) + 1}"
        transaction = {
            "id": transaction_id,
            "status": "PENDING",
            "redirect": f"https://pay.platega.io/{transaction_id}",
            "merchantId": self.settings.platega_merchant_id,
            "paymentMethod": payment_method,
            "paymentDetails": {"amount": amount_rub, "currency": "RUB"},
            "payload": payload,
            "description": description,
            "return": return_url,
            "failedUrl": failed_url,
            "metadata": {
                "userId": str(telegram_id),
                "userName": username or str(telegram_id),
            },
        }
        self.transactions[transaction_id] = transaction
        return dict(transaction)

    async def get_transaction(self, transaction_id: str) -> dict[str, object] | None:
        transaction = self.transactions.get(transaction_id)
        return dict(transaction) if transaction is not None else None

    @classmethod
    def confirm(cls, transaction_id: str) -> dict[str, object]:
        transaction = cls.transactions[transaction_id]
        transaction["status"] = "CONFIRMED"
        transaction["comission"] = 0
        return dict(transaction)


class CabinetApiTestCase(unittest.IsolatedAsyncioTestCase):
    include_payment_callback = False

    async def asyncSetUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.data_dir = Path(self.temporary_directory.name)
        self.settings = _make_test_settings(self.data_dir)
        self.store = OrderStore(self.data_dir)
        self.remnawave = FakeRemnawave()
        self.bot = AsyncMock()
        app = web.Application()
        app["settings"] = self.settings
        app["order_store"] = self.store
        app["remnawave"] = self.remnawave
        app["bot"] = self.bot
        register_cabinet_routes(app)
        if self.include_payment_callback:
            app.router.add_post(
                "/levik-vpn-bot/platega/callback",
                handle_platega_callback,
            )
        self.client = TestClient(TestServer(app))
        await self.client.start_server()

    async def asyncTearDown(self) -> None:
        await self.client.close()
        self.temporary_directory.cleanup()

    async def signed_post(
        self,
        endpoint: str,
        payload: dict[str, object],
        *,
        grant: str = "",
        idempotency_key: str = "",
        nonce: str | None = None,
        timestamp: str | None = None,
    ) -> tuple[int, dict[str, object]]:
        path = f"{BASE_PATH}{endpoint}"
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
        request_timestamp = timestamp or str(int(time.time()))
        request_nonce = nonce or secrets.token_hex(16)
        body_hash = hashlib.sha256(body).hexdigest()
        canonical = canonical_request(
            method="POST",
            raw_path=path,
            timestamp=request_timestamp,
            nonce=request_nonce,
            idempotency_key=idempotency_key,
            grant_hash=opaque_token_hash(grant),
            body_hash=body_hash,
        )
        headers = {
            "Content-Type": "application/json",
            "X-Cabinet-Key-Id": self.settings.cabinet_bridge_key_id,
            "X-Cabinet-Timestamp": request_timestamp,
            "X-Cabinet-Nonce": request_nonce,
            "X-Cabinet-Signature": cabinet_signature(BRIDGE_SECRET, canonical),
        }
        if grant:
            headers["X-Cabinet-Grant"] = grant
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        response = await self.client.post(path, data=body, headers=headers)
        return response.status, await response.json()

    async def issue_account_grant(
        self,
        account_id: str,
        legacy_user_key: str | None = None,
    ) -> dict[str, object]:
        status, payload = await self.signed_post(
            "/auth/account/grant",
            {"accountId": account_id, "legacyUserKey": legacy_user_key},
            idempotency_key=str(uuid4()),
        )
        self.assertEqual(status, 200, payload)
        return payload


class CabinetAccountGrantApiTests(CabinetApiTestCase):
    async def test_new_account_is_stable_and_idempotent(self) -> None:
        account_id = str(uuid4())
        idempotency_key = str(uuid4())
        request = {"accountId": account_id, "legacyUserKey": None}

        first_status, first = await self.signed_post(
            "/auth/account/grant",
            request,
            idempotency_key=idempotency_key,
        )
        second_status, second = await self.signed_post(
            "/auth/account/grant",
            request,
            idempotency_key=idempotency_key,
        )

        self.assertEqual(first_status, 200)
        self.assertEqual(second_status, 200)
        self.assertEqual(first, second)
        self.assertEqual(first["ok"], True)
        self.assertEqual(first["grantExpiresIn"], 3600)
        user = first["user"]
        self.assertIsInstance(user, dict)
        self.assertEqual(user["userLabel"], "Levik Account")
        self.assertRegex(str(user["userKey"]), r"^usr_[A-Za-z0-9_-]{24}$")
        actor_id = self.store.resolve_cabinet_grant(
            grant_hash=opaque_token_hash(str(first["grant"]))
        )
        self.assertIsNotNone(actor_id)
        self.assertLessEqual(int(actor_id or 0), -(2**52))
        self.assertTrue(self.store.is_cabinet_account_actor(int(actor_id or 0)))

        next_grant = await self.issue_account_grant(
            account_id,
            None,
        )
        next_actor = self.store.resolve_cabinet_grant(
            grant_hash=opaque_token_hash(str(next_grant["grant"]))
        )
        self.assertEqual(next_actor, actor_id)
        self.assertEqual(next_grant["user"], user)
        self.assertNotEqual(next_grant["grant"], first["grant"])

    async def test_late_legacy_merge_uses_explicit_state_policy(self) -> None:
        async def synthetic_principal() -> tuple[str, int, str, str]:
            account_id = str(uuid4())
            authorization = await self.issue_account_grant(account_id)
            actor_id = int(
                self.store.resolve_cabinet_grant(
                    grant_hash=opaque_token_hash(str(authorization["grant"]))
                )
                or 0
            )
            return (
                account_id,
                actor_id,
                str(authorization["user"]["userKey"]),
                str(authorization["grant"]),
            )

        def legacy_actor(actor_id: int) -> str:
            self.store.record_user_start(
                telegram_id=actor_id,
                username=f"legacy_{actor_id}",
                first_name="Legacy",
            )
            return cabinet_user_key(self.settings, actor_id)

        # Legacy owns state, synthetic does not: rebind to the real actor.
        account_id, synthetic_id, _, synthetic_grant = await synthetic_principal()
        legacy_id = 123450001
        legacy_key = legacy_actor(legacy_id)
        self.remnawave.users.append({"telegramId": legacy_id, "uuid": str(uuid4())})
        rebound = await self.issue_account_grant(account_id, legacy_key)
        self.assertEqual(rebound["user"]["userKey"], legacy_key)
        self.assertEqual(
            self.store.get_cabinet_account_binding(account_id)["actor_id"],
            legacy_id,
        )
        self.assertIsNone(
            self.store.resolve_cabinet_grant(
                grant_hash=opaque_token_hash(synthetic_grant)
            )
        )
        self.assertLessEqual(synthetic_id, -(2**52))

        # Synthetic owns state, legacy does not: keep synthetic and remember
        # Telegram only as an authentication alias.
        account_id, synthetic_id, synthetic_key, _ = await synthetic_principal()
        self.remnawave.users.append(
            {"telegramId": synthetic_id, "uuid": str(uuid4())}
        )
        legacy_id = 123450002
        legacy_key = legacy_actor(legacy_id)
        kept = await self.issue_account_grant(account_id, legacy_key)
        self.assertEqual(kept["user"]["userKey"], synthetic_key)
        kept_binding = self.store.get_cabinet_account_binding(account_id)
        self.assertEqual(kept_binding["actor_id"], synthetic_id)
        self.assertEqual(kept_binding["legacy_actor_id"], legacy_id)
        self.assertEqual(
            self.store.canonical_cabinet_actor(legacy_id),
            synthetic_id,
        )

        # Both actors own state: never merge silently.
        account_id, synthetic_id, _, _ = await synthetic_principal()
        legacy_id = 123450003
        legacy_key = legacy_actor(legacy_id)
        self.remnawave.users.extend(
            [
                {"telegramId": synthetic_id, "uuid": str(uuid4())},
                {"telegramId": legacy_id, "uuid": str(uuid4())},
            ]
        )
        status, conflict = await self.signed_post(
            "/auth/account/grant",
            {"accountId": account_id, "legacyUserKey": legacy_key},
            idempotency_key=str(uuid4()),
        )
        self.assertEqual(status, 409, conflict)
        self.assertEqual(
            conflict["error"]["code"],
            "account_merge_requires_support",
        )

        # Neither actor owns state: deterministically prefer the real actor.
        account_id, _, _, _ = await synthetic_principal()
        legacy_id = 123450004
        legacy_key = legacy_actor(legacy_id)
        empty_rebind = await self.issue_account_grant(account_id, legacy_key)
        self.assertEqual(empty_rebind["user"]["userKey"], legacy_key)

    async def test_legacy_actor_is_bound_without_identity_split(self) -> None:
        actor_id = 123456789
        self.store.record_user_start(
            telegram_id=actor_id,
            username="legacy_user",
            first_name="Legacy",
        )
        legacy_key = cabinet_user_key(self.settings, actor_id)

        linked = await self.issue_account_grant(str(uuid4()), legacy_key)

        self.assertEqual(linked["user"]["userKey"], legacy_key)
        self.assertEqual(linked["user"]["userLabel"], "Legacy")
        self.assertEqual(linked["user"]["telegramUsername"], "@legacy_user")
        self.assertEqual(
            self.store.resolve_cabinet_grant(
                grant_hash=opaque_token_hash(str(linked["grant"]))
            ),
            actor_id,
        )

        conflict_status, conflict = await self.signed_post(
            "/auth/account/grant",
            {"accountId": str(uuid4()), "legacyUserKey": legacy_key},
            idempotency_key=str(uuid4()),
        )
        self.assertEqual(conflict_status, 409)
        self.assertEqual(conflict["error"]["code"], "account_identity_conflict")

    async def test_strict_validation_conflicts_and_replay(self) -> None:
        account_id = str(uuid4())
        idempotency_key = str(uuid4())
        request = {"accountId": account_id, "legacyUserKey": None}
        first_status, _ = await self.signed_post(
            "/auth/account/grant",
            request,
            idempotency_key=idempotency_key,
        )
        self.assertEqual(first_status, 200)

        conflict_status, conflict = await self.signed_post(
            "/auth/account/grant",
            {"accountId": str(uuid4()), "legacyUserKey": None},
            idempotency_key=idempotency_key,
        )
        self.assertEqual(conflict_status, 409)
        self.assertEqual(conflict["error"]["code"], "account_identity_conflict")

        invalid_cases = (
            ({"accountId": account_id.upper(), "legacyUserKey": None}, str(uuid4())),
            ({"accountId": account_id, "legacyUserKey": "usr_short"}, str(uuid4())),
            ({"accountId": account_id, "legacyUserKey": None, "extra": True}, str(uuid4())),
            ({"accountId": account_id}, str(uuid4())),
            (request, str(uuid4()).upper()),
        )
        for payload, key in invalid_cases:
            status, response = await self.signed_post(
                "/auth/account/grant",
                payload,
                idempotency_key=key,
            )
            self.assertEqual(status, 400, response)

        nonce = secrets.token_hex(16)
        replay_key = str(uuid4())
        replay_request = {"accountId": str(uuid4()), "legacyUserKey": None}
        accepted, _ = await self.signed_post(
            "/auth/account/grant",
            replay_request,
            idempotency_key=replay_key,
            nonce=nonce,
        )
        replayed, replay_response = await self.signed_post(
            "/auth/account/grant",
            replay_request,
            idempotency_key=replay_key,
            nonce=nonce,
        )
        self.assertEqual(accepted, 200)
        self.assertEqual(replayed, 409)
        self.assertEqual(replay_response["error"]["code"], "replay_detected")


class CabinetAccountStoreConcurrencyTests(unittest.TestCase):
    def test_account_grant_claim_is_atomic_under_concurrency(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = OrderStore(Path(directory))
            store.init()
            settings = _make_test_settings(Path(directory))
            account_id = str(uuid4())
            idempotency_key = str(uuid4())
            expires_at = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()

            def claim() -> object:
                seed = secrets.token_urlsafe(32)
                grant = _account_grant_token(SUBJECT_SECRET, seed)
                return store.claim_cabinet_account_grant(
                    account_id=account_id,
                    legacy_user_key=None,
                    legacy_actor_id=None,
                    idempotency_key=idempotency_key,
                    request_hash="a" * 64,
                    grant_seed=seed,
                    grant_hash=opaque_token_hash(grant),
                    grant_expires_at=expires_at,
                    grant_expires_in=3600,
                    user_key_factory=lambda actor_id: cabinet_user_key(
                        settings,
                        actor_id,
                    ),
                )

            with ThreadPoolExecutor(max_workers=8) as executor:
                claims = list(executor.map(lambda _: claim(), range(8)))

            self.assertEqual(sum(1 for item in claims if item.fresh), 1)
            self.assertEqual(len({item.actor_id for item in claims}), 1)
            self.assertEqual(len({item.user_key for item in claims}), 1)
            self.assertEqual(len({item.grant_seed for item in claims}), 1)

    def test_concurrent_accounts_cannot_claim_one_legacy_actor(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = OrderStore(Path(directory))
            settings = _make_test_settings(Path(directory))
            actor_id = 123456789
            user_key = cabinet_user_key(settings, actor_id)
            store.remember_cabinet_telegram_actor(
                actor_id=actor_id,
                user_key=user_key,
            )

            def claim(account_id: str) -> object:
                seed = secrets.token_urlsafe(32)
                grant = _account_grant_token(SUBJECT_SECRET, seed)
                return store.claim_cabinet_account_grant(
                    account_id=account_id,
                    legacy_user_key=user_key,
                    legacy_actor_id=actor_id,
                    idempotency_key=str(uuid4()),
                    request_hash=hashlib.sha256(account_id.encode()).hexdigest(),
                    grant_seed=seed,
                    grant_hash=opaque_token_hash(grant),
                    grant_expires_at=(
                        datetime.now(timezone.utc) + timedelta(hours=1)
                    ).isoformat(),
                    grant_expires_in=3600,
                    user_key_factory=lambda value: cabinet_user_key(settings, value),
                )

            account_ids = [str(uuid4()), str(uuid4())]
            results: list[object] = []
            conflicts = 0
            with ThreadPoolExecutor(max_workers=2) as executor:
                futures = [executor.submit(claim, value) for value in account_ids]
                for future in futures:
                    try:
                        results.append(future.result())
                    except CabinetAccountConflict:
                        conflicts += 1

            self.assertEqual(len(results), 1)
            self.assertEqual(conflicts, 1)


class CabinetAccountPurchaseTests(CabinetApiTestCase):
    include_payment_callback = True

    async def asyncSetUp(self) -> None:
        FakePlategaClient.reset()
        self.service_patch = patch(
            "app.cabinet_service.PlategaClient",
            FakePlategaClient,
        )
        self.webhook_patch = patch(
            "app.webhook.PlategaClient",
            FakePlategaClient,
        )
        self.service_patch.start()
        self.webhook_patch.start()
        await super().asyncSetUp()

    async def asyncTearDown(self) -> None:
        await super().asyncTearDown()
        self.webhook_patch.stop()
        self.service_patch.stop()

    async def create_order(self, grant: str) -> dict[str, object]:
        status, response = await self.signed_post(
            "/orders/create",
            {
                "kind": "access_purchase",
                "tariffId": "regular",
                "months": 1,
                "paymentMethodId": "sbp",
            },
            grant=grant,
            idempotency_key=str(uuid4()),
        )
        self.assertEqual(status, 200, response)
        return response["order"]

    async def confirm_order(self, order: dict[str, object]) -> tuple[int, dict[str, object]]:
        transaction_id = str(self.store.get(int(order["id"]))["provider_payment_charge_id"])
        provider = FakePlategaClient.confirm(transaction_id)
        callback = {
            "id": transaction_id,
            "status": "CONFIRMED",
            "payload": provider["payload"],
            "paymentMethod": provider["paymentMethod"],
            "paymentDetails": provider["paymentDetails"],
        }
        response = await self.client.post(
            "/levik-vpn-bot/platega/callback",
            json=callback,
            headers={
                "X-MerchantId": self.settings.platega_merchant_id,
                "X-Secret": self.settings.platega_api_key,
            },
        )
        return response.status, await response.json()

    async def test_account_purchase_provisions_once_without_telegram_side_effects(self) -> None:
        account_id = str(uuid4())
        authorization = await self.issue_account_grant(account_id)
        grant = str(authorization["grant"])
        actor_id = int(
            self.store.resolve_cabinet_grant(
                grant_hash=opaque_token_hash(grant)
            )
            or 0
        )
        order = await self.create_order(grant)
        self.assertEqual(order["status"], "pending_payment")

        status, callback = await self.confirm_order(order)

        self.assertEqual(status, 200, callback)
        self.assertEqual(self.store.get(int(order["id"]))["status"], "delivered")
        self.assertEqual(len(self.remnawave.create_calls), 1)
        self.assertEqual(self.remnawave.create_calls[0]["telegramId"], actor_id)
        self.assertLessEqual(actor_id, -(2**52))
        self.bot.send_message.assert_not_awaited()

        duplicate_status, duplicate = await self.confirm_order(order)
        self.assertEqual(duplicate_status, 200, duplicate)
        self.assertEqual(len(self.remnawave.create_calls), 1)
        self.bot.send_message.assert_not_awaited()

        order_status, order_response = await self.signed_post(
            "/orders/status",
            {"orderId": int(order["id"])},
            grant=grant,
        )
        self.assertEqual(order_status, 200, order_response)
        self.assertEqual(order_response["order"]["status"], "delivered")

        snapshot_status, snapshot = await self.signed_post(
            "/account/snapshot",
            {},
            grant=grant,
        )
        self.assertEqual(snapshot_status, 200, snapshot)
        self.assertEqual(len(snapshot["subscriptions"]), 1)
        self.assertEqual(snapshot["user"], authorization["user"])
        self.assertIsNone(snapshot["referrals"])
        self.assertEqual(
            snapshot["trial"],
            {"eligible": False, "status": "unavailable", "expiresAt": None},
        )
        self.assertEqual(
            snapshot["freeProxy"],
            {"available": False, "active": False},
        )

        trial_status, trial = await self.signed_post(
            "/trial/activate",
            {},
            grant=grant,
            idempotency_key=str(uuid4()),
        )
        proxy_status, proxy = await self.signed_post(
            "/free-proxy",
            {},
            grant=grant,
            idempotency_key=str(uuid4()),
        )
        self.assertEqual(trial_status, 422, trial)
        self.assertEqual(trial["error"]["code"], "trial_not_eligible")
        self.assertEqual(proxy_status, 422, proxy)
        self.assertEqual(proxy["error"]["code"], "proxy_not_available")

        legacy_id = 765432100
        self.store.record_user_start(
            telegram_id=legacy_id,
            username="late_identity",
            first_name="Late identity",
        )
        linked = await self.issue_account_grant(
            account_id,
            cabinet_user_key(self.settings, legacy_id),
        )
        self.assertEqual(linked["user"], authorization["user"])
        refreshed_link = await self.issue_account_grant(
            account_id,
            cabinet_user_key(self.settings, legacy_id),
        )
        self.assertEqual(refreshed_link["user"], authorization["user"])

        device_status, device = await self.signed_post(
            "/auth/device/create",
            {},
        )
        self.assertEqual(device_status, 200, device)
        verification_token = str(device["verificationUriComplete"]).rsplit(
            "web_",
            1,
        )[1]
        challenge = self.store.bind_cabinet_device_challenge(
            verification_token_hash=opaque_token_hash(verification_token),
            telegram_id=legacy_id,
        )
        self.assertIsNotNone(challenge)
        self.assertTrue(
            self.store.confirm_cabinet_device_challenge(
                challenge_id=str((challenge or {})["challenge_id"]),
                telegram_id=legacy_id,
            )
        )
        device_status, device = await self.signed_post(
            "/auth/device/status",
            {"deviceCode": device["deviceCode"]},
        )
        self.assertEqual(device_status, 200, device)
        self.assertEqual(device["user"], authorization["user"])

        device_grant = str(device["grant"])
        snapshot_status, aliased_snapshot = await self.signed_post(
            "/account/snapshot",
            {},
            grant=device_grant,
        )
        self.assertEqual(snapshot_status, 200, aliased_snapshot)
        self.assertEqual(len(aliased_snapshot["subscriptions"]), 1)
        self.assertEqual(aliased_snapshot["user"], authorization["user"])

        linked_order = await self.create_order(device_grant)
        linked_status, linked_callback = await self.confirm_order(linked_order)
        self.assertEqual(linked_status, 200, linked_callback)
        self.assertEqual(len(self.remnawave.create_calls), 2)
        self.bot.send_message.assert_not_awaited()

    async def test_legacy_purchase_keeps_telegram_notification_behavior(self) -> None:
        actor_id = 987654321
        self.store.record_user_start(
            telegram_id=actor_id,
            username="legacy_buyer",
            first_name="Legacy Buyer",
        )
        authorization = await self.issue_account_grant(
            str(uuid4()),
            cabinet_user_key(self.settings, actor_id),
        )
        order = await self.create_order(str(authorization["grant"]))

        status, response = await self.confirm_order(order)

        self.assertEqual(status, 200, response)
        self.assertEqual(self.remnawave.create_calls[0]["telegramId"], actor_id)
        self.bot.send_message.assert_awaited_once()

        duplicate_status, _ = await self.confirm_order(order)
        self.assertEqual(duplicate_status, 200)
        self.assertEqual(len(self.remnawave.create_calls), 1)
        self.bot.send_message.assert_awaited_once()


if __name__ == "__main__":
    unittest.main()
