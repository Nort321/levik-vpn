from __future__ import annotations

import unittest

from app.main import _account_alias_event_is_authentication


class AccountAliasGuardTests(unittest.TestCase):
    def test_only_device_authorization_actions_bypass_the_guard(self) -> None:
        self.assertTrue(
            _account_alias_event_is_authentication(
                "message",
                "/start web_0123456789abcdefghijklmnop",
            )
        )
        self.assertTrue(
            _account_alias_event_is_authentication(
                "callback_query",
                "cabinet_auth:confirm:0123456789abcdef0123456789abcdef",
            )
        )
        self.assertFalse(
            _account_alias_event_is_authentication("message", "/start")
        )
        self.assertFalse(
            _account_alias_event_is_authentication(
                "callback_query",
                "purchase:regular",
            )
        )
        self.assertFalse(
            _account_alias_event_is_authentication(
                "pre_checkout_query",
                "cabinet_auth:confirm:anything",
            )
        )


if __name__ == "__main__":
    unittest.main()
