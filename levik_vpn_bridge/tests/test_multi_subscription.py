from __future__ import annotations

import base64
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from app.multi_subscription import (
    MULTI_PLAN,
    combine_subscription_payloads,
    merge_users,
    public_url,
)
from app.orders import OrderStore


class MultiSubscriptionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.config = {
            "multi_subscription": {
                "enabled": True,
                "public_base_url": "https://sub.leviknet.com:2096/multi",
            }
        }

    def test_merge_hides_companion_and_exposes_one_public_key(self) -> None:
        primary = {
            "uuid": "primary",
            "username": "multi_regular",
            "description": "[multi:primary]",
            "trafficLimitBytes": 0,
            "hwidDeviceLimit": 5,
            "subscriptionUrl": "https://sub.leviknet.com:2096/primary-token",
        }
        mobile = {
            "uuid": "mobile",
            "username": "multi_mobile",
            "description": "[multi:mobile]",
            "trafficLimitBytes": 50 * 1_073_741_824,
            "hwidDeviceLimit": 1,
            "subscriptionUrl": "https://sub.leviknet.com:2096/mobile-token",
            "userTraffic": {"usedTrafficBytes": 7 * 1_073_741_824},
        }
        record = {
            "telegram_id": 42,
            "primary_user_uuid": "primary",
            "mobile_user_uuid": "mobile",
            "token": "public-multi-token",
            "status": "active",
        }

        result = merge_users(self.config, [primary, mobile], [record])

        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["subscriptionUrl"], public_url(self.config, "public-multi-token"))
        self.assertEqual(result[0]["trafficLimitBytes"], 50 * 1_073_741_824)
        self.assertEqual(result[0]["_multi_mobile_user"]["uuid"], "mobile")

    def test_combines_plain_and_base64_subscriptions_without_duplicates(self) -> None:
        regular = b"vless://regular\ntrojan://shared\n"
        mobile = base64.b64encode(b"trojan://shared\nhysteria2://mobile\n")

        combined = combine_subscription_payloads([regular, mobile])
        decoded = base64.b64decode(combined).decode("utf-8").splitlines()

        self.assertEqual(decoded, ["vless://regular", "trojan://shared", "hysteria2://mobile"])

    def test_store_creates_lookup_and_rotates_opaque_token(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = OrderStore(Path(directory))
            created = store.upsert_multi_subscription(
                telegram_id=42,
                primary_user_uuid="primary",
                mobile_user_uuid="mobile",
            )
            original_token = str(created["token"])

            self.assertGreaterEqual(len(original_token), 32)
            self.assertEqual(
                store.get_multi_subscription_by_user_uuid("mobile")["primary_user_uuid"],
                "primary",
            )
            self.assertEqual(
                store.get_multi_subscription_by_token(original_token)["telegram_id"],
                42,
            )
            self.assertEqual(len(store.list_multi_subscriptions_by_telegram_id(42)), 1)
            self.assertEqual(len(store.list_active_multi_subscriptions()), 1)

            rotated = store.rotate_multi_subscription_token("primary")
            self.assertIsNotNone(rotated)
            self.assertNotEqual(rotated["token"], original_token)
            self.assertIsNone(store.get_multi_subscription_by_token(original_token))

    def test_slot_persists_after_traffic_addon_expires(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = OrderStore(Path(directory))
            now = datetime.now(timezone.utc)
            expired_at = (now - timedelta(days=1)).isoformat()

            store.create_subscription_addon(
                telegram_id=42,
                user_uuid="subscription",
                order_id=1,
                kind="slot",
                slots_delta=1,
                slots_persistent=True,
                traffic_delta_bytes=10_000_000_000,
                expires_at=expired_at,
            )

            expired = store.expired_subscription_addons(now=now.isoformat())
            store.mark_subscription_addons_expired([int(expired[0]["id"])])
            totals = store.active_subscription_addon_totals(
                "subscription",
                now=now.isoformat(),
            )

            self.assertEqual(totals["slots_delta"], 1)
            self.assertEqual(totals["traffic_delta_bytes"], 0)

    def test_plan_label_is_stable(self) -> None:
        self.assertEqual(MULTI_PLAN, "Мультиподписка")


if __name__ == "__main__":
    unittest.main()
