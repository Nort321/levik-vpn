from __future__ import annotations

import unittest

from app.formatters import purchase_confirm_text, telegram_stars_enabled
from app.keyboards import (
    mobile_traffic_payment_keyboard,
    purchase_confirm_keyboard,
    renewal_confirm_keyboard,
    slot_payment_keyboard,
)


def _callbacks(markup: object) -> set[str]:
    keyboard = getattr(markup, "inline_keyboard")
    return {
        str(button.callback_data)
        for row in keyboard
        for button in row
        if button.callback_data
    }


class TelegramStarsDisabledTest(unittest.TestCase):
    def setUp(self) -> None:
        self.config = {
            "payments": {
                "telegram_stars_enabled": False,
                "methods": [
                    {"id": "sbp", "title": "СБП", "enabled": True},
                ],
            },
            "slots": {
                "methods": [
                    {"id": "sbp", "title": "СБП", "amount_rub": 216},
                ],
            },
            "mobile_traffic": {
                "methods": [
                    {"id": "sbp", "title": "СБП", "amount_rub": 64},
                ],
            },
        }
        self.tariff = {"id": "multi", "base_price_rub": 200}
        self.period = {"months": 1, "price_rub": 200}

    def test_feature_flag_is_disabled(self) -> None:
        self.assertFalse(telegram_stars_enabled(self.config))

    def test_stars_callbacks_are_absent_from_all_payment_keyboards(self) -> None:
        markups = (
            purchase_confirm_keyboard("multi", 1, self.config, self.tariff, self.period, 200),
            renewal_confirm_keyboard(0, 1, self.config, self.tariff, self.period),
            slot_payment_keyboard(0, self.config),
            mobile_traffic_payment_keyboard(0, self.config),
        )

        callbacks = set().union(*(_callbacks(markup) for markup in markups))

        self.assertFalse(any("star" in callback for callback in callbacks))
        self.assertTrue(any(callback.startswith("aplat:") for callback in callbacks))
        self.assertTrue(any(callback.startswith("rplat:") for callback in callbacks))
        self.assertTrue(any(callback.startswith("slot_pay:") for callback in callbacks))
        self.assertTrue(any(callback.startswith("traffic_pay:") for callback in callbacks))

    def test_stars_are_absent_from_purchase_confirmation_text(self) -> None:
        text = purchase_confirm_text(
            self.tariff,
            self.period,
            self.config,
        )

        self.assertNotIn("Telegram Stars", text)
        self.assertNotIn("⭐", text)
        self.assertIn("Стоимость сервиса: <b>200 ₽</b>", text)


if __name__ == "__main__":
    unittest.main()
