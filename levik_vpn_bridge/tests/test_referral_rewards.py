from __future__ import annotations

import unittest

from app.delivery import referral_reward_multiplier, scaled_referral_reward
from app.formatters import GB, referral_discount_amount, referral_text


class ReferralRewardsTest(unittest.TestCase):
    def test_discount_applies_to_the_full_first_purchase(self) -> None:
        self.assertEqual(referral_discount_amount(1_000, 20), 200)

    def test_reward_multiplier_by_paid_period(self) -> None:
        self.assertEqual(referral_reward_multiplier(1), (1, 1))
        self.assertEqual(referral_reward_multiplier(3), (3, 2))
        self.assertEqual(referral_reward_multiplier(6), (2, 1))
        self.assertEqual(referral_reward_multiplier(12), (2, 1))

    def test_days_and_traffic_are_scaled_together(self) -> None:
        self.assertEqual(scaled_referral_reward(14, 1), 14)
        self.assertEqual(scaled_referral_reward(14, 3), 21)
        self.assertEqual(scaled_referral_reward(14, 6), 28)
        self.assertEqual(scaled_referral_reward(10 * GB, 3), 15 * GB)
        self.assertEqual(scaled_referral_reward(10 * GB, 6), 20 * GB)

    def test_referral_screen_describes_new_terms(self) -> None:
        text = referral_text(
            {
                "referrals": {
                    "bot_username": "levikvpnbot",
                    "discount_percent": 20,
                    "reward_days": 14,
                    "mobile_traffic_reward_bytes": 10 * GB,
                }
            },
            123,
            {"total": 0, "rewarded": 0},
        )

        self.assertIn("20% скидку на всю первую покупку", text)
        self.assertIn("+14 дней", text)
        self.assertIn("+10 ГБ", text)
        self.assertIn("×1,5", text)
        self.assertIn("×2", text)


if __name__ == "__main__":
    unittest.main()
