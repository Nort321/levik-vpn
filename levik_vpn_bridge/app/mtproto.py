from __future__ import annotations

import html
import re
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import aiohttp

from app.config import Settings


SECRET_RE = re.compile(r"^[0-9a-fA-F]{32}$")


class MtprotoProvisionerError(RuntimeError):
    pass


def is_enabled(settings: Settings) -> bool:
    return bool(settings.mtproto_provisioner_url and settings.mtproto_provisioner_token)


def _as_int(value: Any, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


async def get_or_create_free_proxy(
    settings: Settings,
    *,
    telegram_id: int,
) -> dict[str, object]:
    if not is_enabled(settings):
        raise MtprotoProvisionerError("MTProto provisioner is not configured")

    url = f"{settings.mtproto_provisioner_url.rstrip('/')}/v1/free-proxy"
    timeout = aiohttp.ClientTimeout(total=settings.request_timeout)
    headers = {
        "Authorization": f"Bearer {settings.mtproto_provisioner_token}",
        "Content-Type": "application/json",
    }
    payload = {"telegram_id": telegram_id}

    try:
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with session.post(
                url,
                json=payload,
                headers=headers,
                allow_redirects=False,
            ) as response:
                body = await response.text()
                if response.status != 200:
                    raise MtprotoProvisionerError(
                        f"provisioner returned HTTP {response.status}: {body[:300]}"
                    )
                try:
                    data = await response.json()
                except Exception as exc:  # noqa: BLE001 - aiohttp raises content/JSON variants here.
                    raise MtprotoProvisionerError("provisioner returned invalid JSON") from exc
    except aiohttp.ClientError as exc:
        raise MtprotoProvisionerError(f"provisioner request failed: {exc}") from exc

    label = str(data.get("label") or "").strip()
    link = str(data.get("link") or "").strip()
    if not label or not link.startswith("https://t.me/proxy?"):
        raise MtprotoProvisionerError("provisioner response does not contain a proxy link")

    return {
        "mtproxy_label": label,
        "proxy_link": link,
        "rate_limit_mbps": _as_int(data.get("rate_limit_mbps"), 15),
        "device_limit": _as_int(data.get("device_limit"), 1),
        "max_tcp_connections": _as_int(data.get("max_tcp_connections"), 5),
        "max_unique_ips": _as_int(data.get("max_unique_ips"), 1),
    }


def _replace_proxy_secret(link: str, secret: str) -> str | None:
    parts = urlsplit(link)
    query = parse_qsl(parts.query, keep_blank_values=True)
    if not query:
        return None

    replaced = False
    updated: list[tuple[str, str]] = []
    for key, value in query:
        if key == "secret":
            updated.append((key, secret))
            replaced = True
        else:
            updated.append((key, value))
    if not replaced:
        return None

    return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(updated), parts.fragment))


def classic_proxy_link(link: str) -> str | None:
    parts = urlsplit(link)
    params = dict(parse_qsl(parts.query, keep_blank_values=True))
    secret = params.get("secret", "").strip()
    if SECRET_RE.fullmatch(secret):
        return None
    if len(secret) >= 34 and secret[:2].lower() in {"dd", "ee"}:
        raw_secret = secret[2:34]
        if SECRET_RE.fullmatch(raw_secret):
            return _replace_proxy_secret(link, raw_secret)
    return None


def free_proxy_text(proxy: dict[str, object]) -> str:
    proxy_link = str(proxy.get("proxy_link") or "")
    display_link = classic_proxy_link(proxy_link) or proxy_link
    link = html.escape(display_link, quote=True)
    rate_limit = _as_int(proxy.get("rate_limit_mbps"), 15)
    device_limit = _as_int(proxy.get("device_limit"), 1)
    return (
        "<b>Бесплатный Telegram-прокси</b>\n\n"
        "Твой персональный MTProto-прокси:\n"
        f'<a href="{link}">Подключить Telegram-прокси</a>\n\n'
        "Ограничения:\n"
        f"- устройств: <b>{device_limit}</b>\n"
        f"- скорость: до <b>{rate_limit} Мбит/с</b>\n"
        "- срок действия: <b>без ограничения</b>\n\n"
        "Ссылка закреплена за твоим Telegram ID. При повторном нажатии бот вернет этот же прокси."
    )
