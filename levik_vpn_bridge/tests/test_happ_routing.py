from __future__ import annotations

import unittest

from app.happ_routing import (
    GEOIP_PUBLIC_URL,
    GEOSITE_PUBLIC_URL,
    routing_profile,
)
from app.webhook import _multi_announcement


class HappRoutingTest(unittest.TestCase):
    def test_routing_profile_uses_first_party_geo_assets(self) -> None:
        profile = routing_profile({})

        self.assertEqual(profile["Geoipurl"], GEOIP_PUBLIC_URL)
        self.assertEqual(profile["Geositeurl"], GEOSITE_PUBLIC_URL)
        self.assertGreater(int(str(profile["LastUpdated"])), 0)

    def test_multi_announcement_describes_both_traffic_pools(self) -> None:
        gigabyte = 1_073_741_824

        announcement = _multi_announcement(7 * gigabyte, 50 * gigabyte)

        self.assertEqual(
            announcement.splitlines(),
            [
                "Перестало работать? Обнови подписку!",
                "🌍 Обычные серверы: трафик безлимит!",
                "📱 Мобильный трафик: осталось 43 ГБ из 50 ГБ",
            ],
        )


if __name__ == "__main__":
    unittest.main()
