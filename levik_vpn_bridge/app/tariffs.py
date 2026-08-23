from __future__ import annotations

from typing import Any

from app.formatters import GB, MOBILE_PLAN, plan_name
from app.multi_subscription import MULTI_PLAN, MULTI_TARIFF_ID, is_multi_user


def tariffs(config: dict[str, Any]) -> list[dict[str, Any]]:
    items = config.get("tariffs")
    return [tariff for tariff in items if isinstance(tariff, dict)] if isinstance(items, list) else []


def find_tariff(config: dict[str, Any], tariff_id: str) -> dict[str, Any] | None:
    for tariff in tariffs(config):
        if str(tariff.get("id") or "") == tariff_id:
            return tariff
    return None


def tariff_int(tariff: dict[str, Any], key: str, default: int) -> int:
    try:
        return int(tariff.get(key) if tariff.get(key) is not None else default)
    except (TypeError, ValueError):
        return default


def tariff_strategy(tariff: dict[str, Any]) -> str:
    return str(tariff.get("traffic_limit_strategy") or "MONTH_ROLLING")


def subscription_tariff_id_for_user(config: dict[str, Any], user: dict[str, Any]) -> str:
    available = {str(tariff.get("id") or "") for tariff in tariffs(config)}
    marker = " ".join(str(user.get(key) or "") for key in ("username", "tag", "description", "email")).lower()
    if (is_multi_user(user) or plan_name(user) == MULTI_PLAN or "[multi:primary]" in marker) and MULTI_TARIFF_ID in available:
        return MULTI_TARIFF_ID
    if plan_name(user) != MOBILE_PLAN:
        return "regular"

    if "lte_solo" in marker or "mobile_solo" in marker or " solo" in f" {marker} ":
        if "lte_solo" in available:
            return "lte_solo"
    if "lte" in marker or "wdtt" in marker or "plus" in marker:
        if "lte" in available:
            return "lte"

    device_limit = _user_int(user, "hwidDeviceLimit", 0)
    traffic_limit = _user_int(user, "trafficLimitBytes", 0)
    if "lte_solo" in available and device_limit <= 1 and 0 < traffic_limit <= 60 * GB:
        return "lte_solo"
    if "lte" in available:
        return "lte"
    if "lte_solo" in available:
        return "lte_solo"
    return "regular"


def _user_int(user: dict[str, Any], key: str, default: int) -> int:
    try:
        return int(user.get(key) if user.get(key) is not None else default)
    except (TypeError, ValueError):
        return default
