from __future__ import annotations

import base64
import json
from typing import Any
from urllib.parse import urlsplit


DEFAULT_PROFILE_NAME = "Levik RU Direct"
DEFAULT_PUBLIC_URL = "https://sub.leviknet.com:2095/levik-vpn-bot/happ-routing"
DEFAULT_DISABLE_PUBLIC_URL = f"{DEFAULT_PUBLIC_URL}/off"
GEOIP_PUBLIC_URL = "https://sub.leviknet.com:2095/levik-vpn-bot/happ-import/geo/geoip.dat"
GEOSITE_PUBLIC_URL = "https://sub.leviknet.com:2095/levik-vpn-bot/happ-import/geo/geosite.dat"
GEO_ASSET_UPDATED_AT = "1786708786"
SHIELD_BLOCK_SITES = [
    "geosite:category-ads-all",
    "geosite:levik-shield-ru",
]


def routing_config(config: dict[str, Any]) -> dict[str, Any]:
    value = config.get("happ_routing")
    return value if isinstance(value, dict) else {}


def is_enabled(config: dict[str, Any]) -> bool:
    return bool(routing_config(config).get("enabled", True))


def public_url(config: dict[str, Any]) -> str:
    url = str(routing_config(config).get("public_url") or DEFAULT_PUBLIC_URL).strip()
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.netloc:
        raise ValueError("happ_routing.public_url must be an absolute HTTPS URL")
    return url


def disable_public_url(config: dict[str, Any]) -> str:
    url = str(routing_config(config).get("disable_public_url") or DEFAULT_DISABLE_PUBLIC_URL).strip()
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.netloc:
        raise ValueError("happ_routing.disable_public_url must be an absolute HTTPS URL")
    return url


def routing_profile(
    config: dict[str, Any],
    *,
    shield_enabled: bool = False,
) -> dict[str, object]:
    name = str(routing_config(config).get("profile_name") or DEFAULT_PROFILE_NAME).strip()
    if not name or len(name) > 25:
        raise ValueError("happ_routing.profile_name must contain 1 to 25 characters")

    return {
        "Name": name,
        "GlobalProxy": "true",
        "RouteOrder": "block-proxy-direct",
        "RemoteDNSType": "DoH",
        "RemoteDNSDomain": "https://cloudflare-dns.com/dns-query",
        "RemoteDNSIP": "1.1.1.1",
        "DomesticDNSType": "DoH",
        "DomesticDNSDomain": "https://dns.yandex.ru/dns-query",
        "DomesticDNSIP": "77.88.8.8",
        "Geoipurl": GEOIP_PUBLIC_URL,
        "Geositeurl": GEOSITE_PUBLIC_URL,
        "LastUpdated": GEO_ASSET_UPDATED_AT,
        "DnsHosts": {
            "cloudflare-dns.com": "1.1.1.1",
            "dns.yandex.ru": "77.88.8.8",
        },
        "DirectSites": ["geosite:category-ru", "geosite:private"],
        "DirectIp": ["geoip:ru", "geoip:private"],
        "ProxySites": [],
        "ProxyIp": [],
        "BlockSites": list(SHIELD_BLOCK_SITES) if shield_enabled else [],
        "BlockIp": [],
        "DomainStrategy": "IPIfNonMatch",
        "FakeDNS": "true" if shield_enabled else "false",
        "UseChunkFiles": "true",
    }


def routing_deeplink(
    config: dict[str, Any],
    *,
    shield_enabled: bool = False,
) -> str:
    payload = json.dumps(
        routing_profile(config, shield_enabled=shield_enabled),
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    return "happ://routing/add/" + base64.b64encode(payload).decode("ascii")


def routing_off_deeplink() -> str:
    return "happ://routing/off"


def landing_page(config: dict[str, Any], *, enable: bool = True) -> str:
    title = "Маршрутизация Happ обновлена"
    description = (
        "Обновите нужную подписку в Happ и переподключите VPN. Профиль Levik RU Direct "
        "теперь привязан к подписке, а управление routing находится в её панели внутри приложения."
        if enable
        else "Откройте панель нужной подписки в Happ и отключите routing там, затем переподключите VPN."
    )
    return f"""<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{title}</title>
  <style>
    :root {{ color-scheme: light dark; font-family: system-ui, sans-serif; }}
    body {{ margin: 0; min-height: 100vh; display: grid; place-items: center; background: #10141c; color: #f5f7fb; }}
    main {{ width: min(34rem, calc(100% - 2rem)); box-sizing: border-box; padding: 2rem; border-radius: 1.25rem; background: #1a2130; }}
    h1 {{ margin: 0 0 1rem; font-size: 1.6rem; }}
    p {{ margin: 0 0 1.5rem; color: #cbd4e5; line-height: 1.5; }}
  </style>
</head>
<body>
  <main>
    <h1>{title}</h1>
    <p>{description}</p>
  </main>
</body>
</html>
"""
