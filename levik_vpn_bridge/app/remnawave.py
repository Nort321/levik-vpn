from __future__ import annotations

import asyncio
import json
from collections.abc import Mapping
from pathlib import Path
from typing import Any
from urllib.parse import quote

import aiohttp

from app.config import Settings


class RemnawaveApiError(RuntimeError):
    def __init__(self, endpoint: str, status: int | None = None) -> None:
        self.endpoint = endpoint
        self.status = status
        message = f"Remnawave API request failed: endpoint={endpoint}"
        if status is not None:
            message += f" status={status}"
        super().__init__(message)


class RemnawaveClient:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._session: aiohttp.ClientSession | None = None
        self._api_major: int | None = None
        self._api_major_lock = asyncio.Lock()
        self._user_map_path = settings.data_dir / "remnawave-user-id-map.json"
        self._legacy_uuid_by_id = self._load_user_map(self._user_map_path)
        self._user_id_by_legacy_uuid = {
            legacy_uuid: user_id
            for user_id, legacy_uuid in self._legacy_uuid_by_id.items()
        }

    async def __aenter__(self) -> "RemnawaveClient":
        timeout = aiohttp.ClientTimeout(total=self._settings.request_timeout)
        self._session = aiohttp.ClientSession(timeout=timeout)
        return self

    async def __aexit__(self, *_: object) -> None:
        if self._session is not None:
            await self._session.close()

    @property
    def session(self) -> aiohttp.ClientSession:
        if self._session is None:
            raise RuntimeError("RemnawaveClient is not started")
        return self._session

    async def _request(
        self,
        method: str,
        path: str,
        *,
        body: Mapping[str, Any] | None = None,
        endpoint: str,
    ) -> Any:
        url = f"{self._settings.remnawave_base_url}{path}"
        headers = {
            "Authorization": f"Bearer {self._settings.remnawave_api_token}",
            "Content-Type": "application/json",
        }
        try:
            request_ssl = None if self._settings.remnawave_tls_verify else False
            async with self.session.request(method, url, headers=headers, json=body, ssl=request_ssl) as response:
                if response.status == 404:
                    return None
                if response.status >= 400:
                    raise RemnawaveApiError(endpoint, response.status)
                if response.content_length == 0:
                    return None
                payload = await response.json(content_type=None)
        except TimeoutError as exc:
            raise RemnawaveApiError(endpoint) from exc
        except asyncio.TimeoutError as exc:
            raise RemnawaveApiError(endpoint) from exc
        except aiohttp.ClientError as exc:
            raise RemnawaveApiError(endpoint) from exc

        if isinstance(payload, dict) and "response" in payload:
            return payload["response"]
        return payload

    @staticmethod
    def _load_user_map(path: Path) -> dict[int, str]:
        if not path.exists():
            return {}
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {}
        raw_users = payload.get("legacyUuidById") if isinstance(payload, dict) else None
        if not isinstance(raw_users, dict):
            return {}
        result: dict[int, str] = {}
        for raw_user_id, raw_legacy_uuid in raw_users.items():
            try:
                user_id = int(raw_user_id)
            except (TypeError, ValueError):
                continue
            legacy_uuid = str(raw_legacy_uuid or "").strip()
            if user_id > 0 and legacy_uuid:
                result[user_id] = legacy_uuid
        return result

    def _save_user_map(self) -> None:
        self._settings.data_dir.mkdir(parents=True, exist_ok=True)
        payload = {
            "version": 1,
            "legacyUuidById": {
                str(user_id): legacy_uuid
                for user_id, legacy_uuid in sorted(self._legacy_uuid_by_id.items())
            },
        }
        temporary_path = self._user_map_path.with_suffix(".tmp")
        temporary_path.write_text(
            json.dumps(payload, ensure_ascii=True, indent=2) + "\n",
            encoding="utf-8",
        )
        temporary_path.chmod(0o600)
        temporary_path.replace(self._user_map_path)

    @staticmethod
    def _coerce_user_id(value: object) -> int | None:
        try:
            user_id = int(value)
        except (TypeError, ValueError):
            return None
        return user_id if user_id > 0 else None

    def _remember_legacy_user(self, user: Mapping[str, Any]) -> bool:
        user_id = self._coerce_user_id(user.get("id"))
        legacy_uuid = str(user.get("uuid") or "").strip()
        if user_id is None or not legacy_uuid:
            return False
        if self._legacy_uuid_by_id.get(user_id) == legacy_uuid:
            return False
        self._legacy_uuid_by_id[user_id] = legacy_uuid
        self._user_id_by_legacy_uuid[legacy_uuid] = user_id
        return True

    def _normalize_user(self, user: Mapping[str, Any]) -> dict[str, Any]:
        normalized = dict(user)
        if self._api_major == 2:
            if self._remember_legacy_user(normalized):
                self._save_user_map()
            return normalized

        user_id = self._coerce_user_id(normalized.get("id"))
        if user_id is not None:
            normalized["uuid"] = self._legacy_uuid_by_id.get(user_id, str(user_id))
        return normalized

    def _normalize_users(self, users: list[dict[str, Any]]) -> list[dict[str, Any]]:
        changed = False
        normalized: list[dict[str, Any]] = []
        for user in users:
            item = dict(user)
            if self._api_major == 2:
                changed = self._remember_legacy_user(item) or changed
            else:
                user_id = self._coerce_user_id(item.get("id"))
                if user_id is not None:
                    item["uuid"] = self._legacy_uuid_by_id.get(user_id, str(user_id))
            normalized.append(item)
        if changed:
            self._save_user_map()
        return normalized

    @staticmethod
    def _users_from_payload(payload: Any) -> list[dict[str, Any]]:
        if isinstance(payload, list):
            return [item for item in payload if isinstance(item, dict)]
        if isinstance(payload, dict):
            raw_users = payload.get("users")
            if isinstance(raw_users, list):
                return [item for item in raw_users if isinstance(item, dict)]
        return []

    async def _ensure_api_major(self) -> int:
        if self._api_major is not None:
            return self._api_major
        async with self._api_major_lock:
            if self._api_major is not None:
                return self._api_major
            payload = await self._request(
                "GET",
                "/api/users?start=0&size=1",
                endpoint="users.detect_api_version",
            )
            users = self._users_from_payload(payload)
            if not users:
                raise RemnawaveApiError("users.detect_api_version")
            self._api_major = 2 if "uuid" in users[0] else 3
            if self._api_major == 2 and self._remember_legacy_user(users[0]):
                self._save_user_map()
            return self._api_major

    def _resolve_user_id(self, user_reference: str) -> int:
        normalized_reference = user_reference.strip()
        direct_id = self._coerce_user_id(normalized_reference)
        if direct_id is not None:
            return direct_id
        mapped_id = self._user_id_by_legacy_uuid.get(normalized_reference)
        if mapped_id is None:
            raise RemnawaveApiError("users.resolve_legacy_uuid")
        return mapped_id

    async def get_users_by_telegram_id(self, telegram_id: int) -> list[dict[str, Any]]:
        api_major = await self._ensure_api_major()
        if api_major >= 3:
            expected_telegram_id = str(telegram_id)
            return [
                user
                for user in await self.get_users()
                if str(user.get("telegramId") or "") == expected_telegram_id
            ]

        encoded = quote(str(telegram_id), safe="")
        payload = await self._request(
            "GET",
            f"/api/users/by-telegram-id/{encoded}",
            endpoint="users.by_telegram_id",
        )
        return self._normalize_users(self._users_from_payload(payload))

    async def get_users(self, *, page_size: int = 200) -> list[dict[str, Any]]:
        await self._ensure_api_major()
        users: list[dict[str, Any]] = []
        start = 0
        size = max(1, min(page_size, 1000))

        while True:
            payload = await self._request(
                "GET",
                f"/api/users?start={start}&size={size}",
                endpoint="users.list",
            )
            if isinstance(payload, list):
                batch = [item for item in payload if isinstance(item, dict)]
                total = None
            elif isinstance(payload, dict):
                raw_users = payload.get("users")
                batch = [item for item in raw_users if isinstance(item, dict)] if isinstance(raw_users, list) else []
                total = payload.get("total")
            else:
                batch = []
                total = None

            if not batch:
                break
            users.extend(batch)

            try:
                total_int = int(total) if total is not None else 0
            except (TypeError, ValueError):
                total_int = 0

            if total_int > 0 and len(users) >= total_int:
                break
            if len(batch) < size:
                break
            start += len(batch)

        return self._normalize_users(users)

    async def get_user_by_uuid(self, user_uuid: str) -> dict[str, Any] | None:
        api_major = await self._ensure_api_major()
        user_reference = (
            str(self._resolve_user_id(user_uuid))
            if api_major >= 3
            else user_uuid
        )
        encoded = quote(user_reference, safe="")
        payload = await self._request("GET", f"/api/users/{encoded}", endpoint="users.by_uuid")
        return self._normalize_user(payload) if isinstance(payload, dict) else None

    async def create_user(self, body: Mapping[str, Any]) -> dict[str, Any] | None:
        await self._ensure_api_major()
        payload = await self._request("POST", "/api/users", body=body, endpoint="users.create")
        return self._normalize_user(payload) if isinstance(payload, dict) else None

    async def update_user(self, body: Mapping[str, Any]) -> dict[str, Any] | None:
        api_major = await self._ensure_api_major()
        request_body = dict(body)
        if api_major >= 3:
            legacy_reference = request_body.pop("uuid", None)
            if "id" not in request_body and legacy_reference is not None:
                request_body["id"] = self._resolve_user_id(str(legacy_reference))
        payload = await self._request(
            "PATCH",
            "/api/users",
            body=request_body,
            endpoint="users.update",
        )
        return self._normalize_user(payload) if isinstance(payload, dict) else None

    async def update_user_hwid_device_limit(self, user_uuid: str, limit: int) -> dict[str, Any] | None:
        return await self.update_user({"uuid": user_uuid, "hwidDeviceLimit": limit})

    async def get_internal_squads(self) -> list[dict[str, Any]]:
        payload = await self._request("GET", "/api/internal-squads", endpoint="internal_squads.list")
        if isinstance(payload, list):
            return [item for item in payload if isinstance(item, dict)]
        if isinstance(payload, dict):
            squads = payload.get("internalSquads") or payload.get("squads")
            if isinstance(squads, list):
                return [item for item in squads if isinstance(item, dict)]
        return []

    async def get_hosts(self) -> list[dict[str, Any]]:
        payload = await self._request("GET", "/api/hosts", endpoint="hosts.list")
        if isinstance(payload, list):
            return [item for item in payload if isinstance(item, dict)]
        if isinstance(payload, dict):
            hosts = payload.get("hosts")
            if isinstance(hosts, list):
                return [item for item in hosts if isinstance(item, dict)]
        return []

    async def get_user_devices(self, user_uuid: str) -> list[dict[str, Any]]:
        api_major = await self._ensure_api_major()
        user_reference = (
            str(self._resolve_user_id(user_uuid))
            if api_major >= 3
            else user_uuid
        )
        encoded = quote(user_reference, safe="")
        payload = await self._request("GET", f"/api/hwid/devices/{encoded}", endpoint="hwid.user_devices")
        if isinstance(payload, dict) and isinstance(payload.get("devices"), list):
            return [item for item in payload["devices"] if isinstance(item, dict)]
        return []

    async def delete_user_device(self, user_uuid: str, hwid: str) -> list[dict[str, Any]]:
        api_major = await self._ensure_api_major()
        body = (
            {"userId": self._resolve_user_id(user_uuid), "hwid": hwid}
            if api_major >= 3
            else {"userUuid": user_uuid, "hwid": hwid}
        )
        payload = await self._request(
            "POST",
            "/api/hwid/devices/delete",
            body=body,
            endpoint="hwid.delete_device",
        )
        if isinstance(payload, dict) and isinstance(payload.get("devices"), list):
            return [item for item in payload["devices"] if isinstance(item, dict)]
        return []

    async def delete_all_user_devices(self, user_uuid: str) -> list[dict[str, Any]]:
        api_major = await self._ensure_api_major()
        body = (
            {"userId": self._resolve_user_id(user_uuid)}
            if api_major >= 3
            else {"userUuid": user_uuid}
        )
        payload = await self._request(
            "POST",
            "/api/hwid/devices/delete-all",
            body=body,
            endpoint="hwid.delete_all_devices",
        )
        if isinstance(payload, dict) and isinstance(payload.get("devices"), list):
            return [item for item in payload["devices"] if isinstance(item, dict)]
        return []

    async def revoke_subscription(self, user_uuid: str) -> dict[str, Any] | None:
        api_major = await self._ensure_api_major()
        user_reference = (
            str(self._resolve_user_id(user_uuid))
            if api_major >= 3
            else user_uuid
        )
        encoded = quote(user_reference, safe="")
        payload = await self._request(
            "POST",
            f"/api/users/{encoded}/actions/revoke",
            body={"revokeOnlyPasswords": False},
            endpoint="users.revoke_subscription",
        )
        return self._normalize_user(payload) if isinstance(payload, dict) else None

    async def fetch_subscription(
        self,
        url: str,
        *,
        headers: Mapping[str, str],
        max_bytes: int = 2 * 1024 * 1024,
    ) -> tuple[int, bytes, dict[str, str]]:
        try:
            async with self.session.get(
                url,
                headers=dict(headers),
                allow_redirects=False,
            ) as response:
                if response.content_length is not None and response.content_length > max_bytes:
                    raise RemnawaveApiError("subscriptions.fetch_too_large", response.status)
                body = await response.read()
                if len(body) > max_bytes:
                    raise RemnawaveApiError("subscriptions.fetch_too_large", response.status)
                return response.status, body, dict(response.headers)
        except (TimeoutError, asyncio.TimeoutError) as exc:
            raise RemnawaveApiError("subscriptions.fetch") from exc
        except aiohttp.ClientError as exc:
            raise RemnawaveApiError("subscriptions.fetch") from exc
