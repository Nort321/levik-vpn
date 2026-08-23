from __future__ import annotations

import unittest

from app.formatters import GB, connection_guide_text, format_bytes, key_text
from app.handlers import _happ_import_url, _load_users
from app.keyboards import access_success_keyboard, connection_guide_keyboard


class ConnectionGuideTest(unittest.TestCase):
    def test_load_users_without_multi_records_does_not_require_settings(self) -> None:
        class RemnawaveStub:
            async def get_users_by_telegram_id(self, telegram_id: int):
                self.asserted_telegram_id = telegram_id
                return []

        class OrderStoreStub:
            def get_wdtt_accesses_by_telegram_id(self, telegram_id: int):
                return []

            def list_multi_subscriptions_by_telegram_id(self, telegram_id: int):
                return []

        users = __import__("asyncio").run(
            _load_users(RemnawaveStub(), 42, OrderStoreStub())
        )
        self.assertEqual(users, [])

    def test_happ_import_url_accepts_current_and_legacy_hosts(self) -> None:
        token = "Abcdef_123-xyz"
        expected = f"https://sub.leviknet.com:2095/levik-vpn-bot/happ-import/{token}"
        self.assertEqual(_happ_import_url(f"https://sub.leviknet.com:2096/{token}"), expected)
        self.assertEqual(_happ_import_url(f"https://levik.levafart.store:2096/{token}"), expected)

        multi_token = "Abcdef_123-xyz-Abcdef_123-xyz-Abcdef_123"
        self.assertEqual(
            _happ_import_url(f"https://sub.leviknet.com:2096/multi/{multi_token}"),
            f"https://sub.leviknet.com:2095/levik-vpn-bot/happ-import/multi/{multi_token}",
        )

    def test_happ_import_url_rejects_untrusted_or_malformed_urls(self) -> None:
        invalid = (
            "http://sub.leviknet.com:2096/token123",
            "https://evil.example:2096/token123",
            "https://user:pass@sub.leviknet.com:2096/token123",
            "https://sub.leviknet.com:2096/short",
            "https://sub.leviknet.com:2096/token123?query=1",
            "https://sub.leviknet.com:2096/nested/token123",
        )
        for value in invalid:
            with self.subTest(value=value):
                self.assertIsNone(_happ_import_url(value))

    def test_requested_store_links_and_happ_button_are_present(self) -> None:
        first = connection_guide_keyboard(1, "user-uuid")
        first_buttons = [button for row in first.inline_keyboard for button in row]
        self.assertEqual(
            first_buttons[0].url,
            "https://apps.apple.com/us/app/happ-proxy-utility/id6504287215?l=ru",
        )
        self.assertEqual(
            first_buttons[1].url,
            "https://play.google.com/store/apps/details?id=com.happproxy",
        )

        import_url = "https://sub.leviknet.com:2095/levik-vpn-bot/happ-import/token123"
        second = connection_guide_keyboard(2, "user-uuid", happ_import_url=import_url)
        second_buttons = [button for row in second.inline_keyboard for button in row]
        self.assertEqual(second_buttons[0].text, "🚀 Добавить подписку в Happ")
        self.assertEqual(second_buttons[0].url, import_url)

    def test_purchase_success_has_direct_happ_button(self) -> None:
        token = "Abcdef_123-xyz"
        keyboard = access_success_keyboard(
            "user-uuid",
            subscription_url=f"https://sub.leviknet.com:2096/{token}",
        )
        buttons = [button for row in keyboard.inline_keyboard for button in row]
        self.assertEqual(buttons[0].text, "🚀 Подключить в Happ")
        self.assertEqual(
            buttons[0].url,
            f"https://sub.leviknet.com:2095/levik-vpn-bot/happ-import/{token}",
        )

    def test_key_text_separates_traffic_and_key(self) -> None:
        text = key_text(
            {
                "subscriptionUrl": "https://sub.leviknet.com:2096/Abcdef_123-xyz",
                "username": "test_user",
                "expireAt": "2026-09-16T00:00:00+00:00",
                "trafficLimitBytes": 0,
                "userTraffic": {"usedTrafficBytes": 0},
            },
            "Europe/Moscow",
        )
        self.assertIn("</b>\n\n🔐 Ключ:", text)

    def test_three_steps_include_requested_copy(self) -> None:
        user = {"subscriptionUrl": "https://sub.leviknet.com:2096/token123"}
        self.assertIn("шаг 1 из 3", connection_guide_text(1, user).lower())
        self.assertIn("или нажмите кнопку ниже", connection_guide_text(2, user))
        self.assertIn("Шаг 3 из 3", connection_guide_text(3, user))


    def test_binary_traffic_units_match_remnawave_display(self) -> None:
        self.assertEqual(GB, 1_073_741_824)
        self.assertEqual(format_bytes(GB), "1 ГБ")
        self.assertEqual(format_bytes(50 * GB), "50 ГБ")


if __name__ == "__main__":
    unittest.main()
