from __future__ import annotations

import secrets
import string
from datetime import datetime, timezone
from typing import Any
from urllib.parse import quote, urlencode

import aiohttp

from app.config import Settings
from app.formatters import esc, format_date, plural_ru


PASSWORD_ALPHABET = string.ascii_letters + string.digits
ANDROID_CLIENT_URL = "https://github.com/SpaceNeuroX/proxy-turn-vk-android/releases/latest"
IOS_TESTFLIGHT_URL = "https://testflight.apple.com/join/ANm6cmDv"
IOS_RELEASES_URL = "https://github.com/anton48/vk-turn-proxy-ios/releases/latest"


def config(settings: Settings) -> dict[str, Any]:
    value = settings.data.get("wdtt")
    return value if isinstance(value, dict) else {}


def enabled_for_tariff(settings: Settings, tariff_id: str) -> bool:
    cfg = config(settings)
    if not cfg.get("enabled", False):
        return False
    tariff_ids = cfg.get("tariff_ids")
    if not isinstance(tariff_ids, list):
        return True
    return tariff_id in {str(item) for item in tariff_ids}


def hashes(settings: Settings) -> str:
    raw = config(settings).get("hashes") or config(settings).get("vk_hashes")
    if isinstance(raw, list):
        return ",".join(str(item).strip() for item in raw if str(item).strip())
    return str(raw or "").strip()


def peer(settings: Settings) -> str:
    cfg = config(settings)
    value = str(cfg.get("peer") or "").strip()
    if value:
        return value
    host = str(cfg.get("server_host") or "94.156.114.70").strip()
    return f"{host}:{dtls_port(settings)}"


def peer_host(settings: Settings) -> str:
    value = peer(settings)
    return value.rsplit(":", 1)[0] if ":" in value else value


def dtls_port(settings: Settings) -> int:
    return _int_config(settings, "dtls_port", 56000)


def wg_port(settings: Settings) -> int:
    return _int_config(settings, "wg_port", 56001)


def local_port(settings: Settings) -> int:
    return _int_config(settings, "local_port", 9000)


def workers(settings: Settings) -> int:
    return _int_config(settings, "workers", 9)


def max_devices(settings: Settings, fallback: int) -> int:
    cfg = config(settings)
    if "max_devices" in cfg:
        try:
            return max(0, int(cfg.get("max_devices") or 0))
        except (TypeError, ValueError):
            return 0
    return max(0, fallback)


def ports(settings: Settings) -> str:
    return f"{dtls_port(settings)},{wg_port(settings)},{local_port(settings)}"


def subscription_base_url(settings: Settings) -> str:
    return str(config(settings).get("subscription_base_url") or "").rstrip("/")


def display_name(settings: Settings) -> str:
    return str(config(settings).get("display_name") or settings.data.get("brand") or "Levik VPN")


def generate_password(length: int = 24) -> str:
    return "".join(secrets.choice(PASSWORD_ALPHABET) for _ in range(length))


def generate_token() -> str:
    return secrets.token_urlsafe(32)


def qwdtt_link(settings: Settings, access: dict[str, object]) -> str:
    query = urlencode(
        {
            "name": str(access.get("label") or display_name(settings)),
            "peer": str(access.get("peer") or peer(settings)),
            "hashes": str(access.get("hashes") or hashes(settings)),
            "workers": int(access.get("workers") or workers(settings)),
            "port": int(access.get("port") or local_port(settings)),
            "pass": str(access.get("password") or ""),
        }
    )
    return f"qwdtt://config?{query}"


def wdtt_link(settings: Settings, access: dict[str, object]) -> str:
    all_hashes = str(access.get("hashes") or hashes(settings))
    first_hash = all_hashes.split(",", 1)[0].strip()
    return (
        f"wdtt://{peer_host(settings)}:{dtls_port(settings)}:{wg_port(settings)}:"
        f"{int(access.get('port') or local_port(settings))}:{access.get('password')}:{first_hash}"
    )


def subscription_url(settings: Settings, access: dict[str, object]) -> str:
    base = subscription_base_url(settings)
    token = str(access.get("token") or "")
    return f"{base}/{token}.json" if base and token else ""


def subscription_payload(settings: Settings, access: dict[str, object]) -> dict[str, object]:
    expire_at = int(access.get("expires_at") or 0)
    description = "Подписка WDTT"
    if expire_at > 0:
        dt = datetime.fromtimestamp(expire_at, tz=timezone.utc).isoformat()
        description = f"Подписка WDTT · до {dt[:10]}"
    return {
        "subscriptionName": display_name(settings),
        "description": description,
        "updatedAt": datetime.now(timezone.utc).date().isoformat(),
        "version": 1,
        "profiles": [
            {
                "name": str(access.get("label") or display_name(settings)),
                "peer": str(access.get("peer") or peer(settings)),
                "hashes": str(access.get("hashes") or hashes(settings)),
                "workers": int(access.get("workers") or workers(settings)),
                "port": int(access.get("port") or local_port(settings)),
                "password": str(access.get("password") or ""),
            }
        ],
    }


def access_text(settings: Settings, access: dict[str, object], timezone_name: str) -> str:
    sub_url = subscription_url(settings, access)
    q_link = qwdtt_link(settings, access)
    legacy_link = wdtt_link(settings, access)
    expire_at = int(access.get("expires_at") or 0)
    expire_text = "без срока"
    if expire_at > 0:
        expire_text = format_date(datetime.fromtimestamp(expire_at, tz=timezone.utc).isoformat(), timezone_name)
    try:
        device_limit = int(access.get("max_devices") or max_devices(settings, 0))
    except (TypeError, ValueError):
        device_limit = 0

    lines = [
        "📡 <b>Мобильный доступ WDTT</b>",
        "",
        f"Действует до: <b>{esc(expire_text)}</b>",
    ]
    if device_limit > 0:
        lines.append(f"Устройства: <b>до {device_limit} {plural_ru(device_limit, 'устройства', 'устройств', 'устройств')}</b>")
    lines.extend([
        "",
        "<b>Android</b>",
        f"1. <a href=\"{ANDROID_CLIENT_URL}\">Скачайте qWDTT для Android</a>.",
        "2. Если Android спросит разрешение, включите установку APK из браузера/Telegram.",
        "3. Установите приложение, откройте qWDTT и импортируйте строку:",
        f"<code>{esc(q_link)}</code>",
        "4. Нажмите «Подключить» и разрешите создание VPN-профиля.",
        "",
        "Если не проходит капча: в настройках qWDTT включите ручную капчу, метод обхода капчи WBV и режим обхода АВТ.",
    ])
    if sub_url:
        lines.extend(
            [
                "",
                "Дополнительно для qWDTT: можно добавить HTTPS-подписку через Профили → + → Подписка:",
                f"<code>{esc(sub_url)}</code>",
            ]
        )

    lines.extend(
        [
            "",
            "<b>iOS</b>",
            f"1. Установите <a href=\"{IOS_TESTFLIGHT_URL}\">VK TURN Proxy через TestFlight</a>.",
            f"Если TestFlight недоступен, используйте <a href=\"{IOS_RELEASES_URL}\">IPA из GitHub Releases</a> и подпишите его своим способом.",
            "2. Откройте VK TURN Proxy → Settings → Import from connection link.",
            "3. Вставьте строку:",
            f"<code>{esc(legacy_link)}</code>",
            "4. Сохраните профиль, нажмите подключение и разрешите VPN-профиль iOS.",
        ]
    )
    return "\n".join(lines)


def _admin_url(settings: Settings, path: str) -> str:
    api_url = str(config(settings).get("api_url") or "").rstrip("/")
    if not api_url:
        raise RuntimeError("WDTT api_url is not configured")
    return f"{api_url}{path}"


def _admin_headers(settings: Settings) -> dict[str, str]:
    if not settings.wdtt_api_token:
        raise RuntimeError("WDTT_API_TOKEN is not configured")
    return {"Authorization": f"Bearer {settings.wdtt_api_token}"}


async def _admin_request(
    settings: Settings,
    method: str,
    path: str,
    *,
    payload: dict[str, object] | None = None,
) -> dict[str, Any]:
    timeout = aiohttp.ClientTimeout(total=10)
    async with aiohttp.ClientSession(timeout=timeout) as session:
        async with session.request(
            method,
            _admin_url(settings, path),
            json=payload,
            headers=_admin_headers(settings),
        ) as response:
            if response.status >= 400:
                raise RuntimeError(f"WDTT admin API failed: {response.status}")
            data = await response.json(content_type=None)
    if not isinstance(data, dict):
        raise RuntimeError("WDTT admin API returned invalid payload")
    return data


async def remote_devices(settings: Settings, access: dict[str, object]) -> dict[str, Any]:
    password = quote(str(access.get("password") or ""), safe="")
    if not password:
        raise RuntimeError("WDTT password is missing")
    return await _admin_request(settings, "GET", f"/access/devices?password={password}")


async def delete_remote_device(settings: Settings, access: dict[str, object], device_id: str) -> dict[str, Any]:
    return await _admin_request(
        settings,
        "POST",
        "/access/device/delete",
        payload={"password": str(access.get("password") or ""), "device_id": device_id},
    )


async def delete_all_remote_devices(settings: Settings, access: dict[str, object]) -> dict[str, Any]:
    return await _admin_request(
        settings,
        "POST",
        "/access/devices/delete-all",
        payload={"password": str(access.get("password") or "")},
    )


async def sync_remote_access(settings: Settings, access: dict[str, object]) -> None:
    payload = {
        "password": str(access["password"]),
        "label": str(access["label"]),
        "expires_at": int(access["expires_at"]),
        "max_devices": int(access["max_devices"]),
        "vk_hash": str(access["hashes"]),
        "ports": ports(settings),
    }
    await _admin_request(settings, "POST", "/access", payload=payload)


def build_access_record(
    *,
    settings: Settings,
    telegram_id: int,
    user_uuid: str,
    order_id: int,
    tariff_id: str,
    expires_at: int,
    max_devices_value: int,
    existing: dict[str, object] | None = None,
) -> dict[str, object]:
    current_hashes = hashes(settings)
    if not current_hashes:
        raise RuntimeError("WDTT hashes are not configured")

    password = str(existing.get("password")) if existing and existing.get("password") else generate_password()
    token = str(existing.get("token")) if existing and existing.get("token") else generate_token()
    label = str(existing.get("label")) if existing and existing.get("label") else display_name(settings)
    return {
        "telegram_id": telegram_id,
        "user_uuid": user_uuid,
        "order_id": order_id,
        "password": password,
        "token": token,
        "label": label,
        "peer": peer(settings),
        "hashes": current_hashes,
        "workers": workers(settings),
        "port": local_port(settings),
        "expires_at": expires_at,
        "max_devices": max_devices_value,
    }


def _int_config(settings: Settings, key: str, default: int) -> int:
    try:
        return int(config(settings).get(key) if config(settings).get(key) is not None else default)
    except (TypeError, ValueError):
        return default
