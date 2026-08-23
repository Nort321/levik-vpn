from __future__ import annotations

import base64
import binascii
from typing import Any

from app.config import Settings


MULTI_TARIFF_ID = "multi"
MULTI_PLAN = "Мультиподписка"
DEFAULT_PUBLIC_BASE_URL = "https://sub.leviknet.com:2096/multi"
SUPPORTED_SCHEMES = (
    "vless://",
    "vmess://",
    "trojan://",
    "ss://",
    "hysteria2://",
    "hy2://",
    "tuic://",
    "wireguard://",
)


def config(settings: Settings | dict[str, Any]) -> dict[str, Any]:
    data = settings.data if isinstance(settings, Settings) else settings
    value = data.get("multi_subscription")
    return value if isinstance(value, dict) else {}


def public_base_url(settings: Settings | dict[str, Any]) -> str:
    return str(config(settings).get("public_base_url") or DEFAULT_PUBLIC_BASE_URL).rstrip("/")


def public_url(settings: Settings | dict[str, Any], token: str) -> str:
    return f"{public_base_url(settings)}/{token}"


def is_multi_user(user: dict[str, Any]) -> bool:
    return isinstance(user.get("_multi_subscription"), dict)


def mobile_user(user: dict[str, Any]) -> dict[str, Any] | None:
    value = user.get("_multi_mobile_user")
    return value if isinstance(value, dict) else None


def decorate_user(
    settings: Settings | dict[str, Any],
    record: dict[str, object],
    primary: dict[str, Any],
    mobile: dict[str, Any] | None,
) -> dict[str, Any]:
    result = dict(primary)
    mobile_value = dict(mobile) if isinstance(mobile, dict) else {}
    result["_multi_subscription"] = dict(record)
    result["_multi_mobile_user"] = mobile_value
    result["subscriptionUrl"] = public_url(settings, str(record.get("token") or ""))
    result["username"] = str(config(settings).get("display_name") or "Levik Multi")
    if mobile_value:
        result["trafficLimitBytes"] = mobile_value.get("trafficLimitBytes", 0)
        result["userTraffic"] = mobile_value.get("userTraffic", {})
    return result


def merge_users(
    settings: Settings | dict[str, Any],
    users: list[dict[str, Any]],
    records: list[dict[str, object]],
) -> list[dict[str, Any]]:
    by_reference = {str(user.get("uuid") or ""): user for user in users}
    hidden_mobile_references: set[str] = set()
    decorated_by_primary: dict[str, dict[str, Any]] = {}
    for record in records:
        if str(record.get("status") or "") != "active":
            continue
        primary_reference = str(record.get("primary_user_uuid") or "")
        mobile_reference = str(record.get("mobile_user_uuid") or "")
        primary = by_reference.get(primary_reference)
        if primary is None:
            continue
        decorated_by_primary[primary_reference] = decorate_user(
            settings,
            record,
            primary,
            by_reference.get(mobile_reference),
        )
        hidden_mobile_references.add(mobile_reference)

    result: list[dict[str, Any]] = []
    for user in users:
        reference = str(user.get("uuid") or "")
        if reference in hidden_mobile_references:
            continue
        result.append(decorated_by_primary.get(reference, user))
    return result


def decode_subscription_lines(payload: bytes) -> list[str]:
    if not payload:
        return []
    raw = payload.strip()
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        return []

    direct = _configuration_lines(text)
    if direct:
        return direct

    compact = "".join(text.split())
    if not compact:
        return []
    padding = "=" * (-len(compact) % 4)
    try:
        decoded = base64.b64decode(compact + padding, validate=True).decode("utf-8")
    except (binascii.Error, UnicodeDecodeError):
        return []
    return _configuration_lines(decoded)


def combine_subscription_payloads(payloads: list[bytes]) -> bytes:
    lines: list[str] = []
    seen: set[str] = set()
    for payload in payloads:
        for line in decode_subscription_lines(payload):
            if line not in seen:
                seen.add(line)
                lines.append(line)
    if not lines:
        return b""
    return base64.b64encode(("\n".join(lines) + "\n").encode("utf-8"))


def _configuration_lines(text: str) -> list[str]:
    result: list[str] = []
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if line.lower().startswith(SUPPORTED_SCHEMES):
            result.append(line)
    return result
