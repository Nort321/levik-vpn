from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

from app.remnawave import RemnawaveClient


def settings(data_dir: Path) -> SimpleNamespace:
    return SimpleNamespace(
        data_dir=data_dir,
        remnawave_base_url="https://remnawave.invalid",
        remnawave_api_token="test-token",
        remnawave_tls_verify=True,
        request_timeout=1.0,
    )


class RemnawaveCompatibilityTests(unittest.IsolatedAsyncioTestCase):
    async def test_v2_user_list_persists_complete_legacy_mapping(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            data_dir = Path(temporary_directory)
            client = RemnawaveClient(settings(data_dir))  # type: ignore[arg-type]
            client._api_major = 2
            client._request = AsyncMock(
                return_value={
                    "users": [
                        {"id": 41, "uuid": "legacy-41", "telegramId": 10},
                        {"id": 42, "uuid": "legacy-42", "telegramId": 20},
                    ],
                    "total": 2,
                }
            )

            users = await client.get_users()

            self.assertEqual([user["uuid"] for user in users], ["legacy-41", "legacy-42"])
            payload = json.loads((data_dir / "remnawave-user-id-map.json").read_text())
            self.assertEqual(
                payload["legacyUuidById"],
                {"41": "legacy-41", "42": "legacy-42"},
            )

    async def test_v3_legacy_uuid_is_translated_to_numeric_user_id(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            data_dir = Path(temporary_directory)
            (data_dir / "remnawave-user-id-map.json").write_text(
                json.dumps({"version": 1, "legacyUuidById": {"42": "legacy-42"}}),
                encoding="utf-8",
            )
            client = RemnawaveClient(settings(data_dir))  # type: ignore[arg-type]
            client._api_major = 3
            client._request = AsyncMock(return_value={"id": 42, "username": "customer"})

            user = await client.get_user_by_uuid("legacy-42")

            self.assertEqual(user, {"id": 42, "username": "customer", "uuid": "legacy-42"})
            client._request.assert_awaited_once_with(
                "GET",
                "/api/users/42",
                endpoint="users.by_uuid",
            )

    async def test_v3_update_replaces_legacy_uuid_with_id(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            data_dir = Path(temporary_directory)
            (data_dir / "remnawave-user-id-map.json").write_text(
                json.dumps({"version": 1, "legacyUuidById": {"42": "legacy-42"}}),
                encoding="utf-8",
            )
            client = RemnawaveClient(settings(data_dir))  # type: ignore[arg-type]
            client._api_major = 3
            client._request = AsyncMock(
                return_value={"id": 42, "hwidDeviceLimit": 5}
            )

            user = await client.update_user(
                {"uuid": "legacy-42", "hwidDeviceLimit": 5}
            )

            self.assertEqual(user["uuid"], "legacy-42")
            client._request.assert_awaited_once_with(
                "PATCH",
                "/api/users",
                body={"id": 42, "hwidDeviceLimit": 5},
                endpoint="users.update",
            )

    async def test_v3_telegram_lookup_filters_official_user_list(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            client = RemnawaveClient(  # type: ignore[arg-type]
                settings(Path(temporary_directory))
            )
            client._api_major = 3
            client.get_users = AsyncMock(
                return_value=[
                    {"id": 1, "uuid": "1", "telegramId": 100},
                    {"id": 2, "uuid": "2", "telegramId": 200},
                ]
            )

            users = await client.get_users_by_telegram_id(200)

            self.assertEqual(users, [{"id": 2, "uuid": "2", "telegramId": 200}])

    async def test_v3_hwid_delete_uses_user_id_body(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            data_dir = Path(temporary_directory)
            (data_dir / "remnawave-user-id-map.json").write_text(
                json.dumps({"version": 1, "legacyUuidById": {"42": "legacy-42"}}),
                encoding="utf-8",
            )
            client = RemnawaveClient(settings(data_dir))  # type: ignore[arg-type]
            client._api_major = 3
            client._request = AsyncMock(return_value={"devices": []})

            devices = await client.delete_user_device("legacy-42", "device-hwid")

            self.assertEqual(devices, [])
            client._request.assert_awaited_once_with(
                "POST",
                "/api/hwid/devices/delete",
                body={"userId": 42, "hwid": "device-hwid"},
                endpoint="hwid.delete_device",
            )


if __name__ == "__main__":
    unittest.main()
