from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from app.config import Settings
from app.formatters import (
    mobile_traffic_amount_bytes,
    mobile_traffic_config,
    mobile_traffic_enabled,
    mobile_traffic_price_rub,
    payment_method,
    payment_method_amount,
    payment_method_request_amount,
    payment_methods,
    period_months,
    period_title,
    period_total,
    referral_discount_amount,
    slot_amount,
    slot_price_rub,
    slot_traffic_delta_bytes,
)
from app.orders import OrderStore
from app.tariffs import find_tariff, tariffs


class CommerceError(RuntimeError):
    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,100}$")
MAX_SAFE_INTEGER = 2**53 - 1


def _catalog_identifier(value: object) -> str | None:
    candidate = str(value or "")
    return candidate if IDENTIFIER_RE.fullmatch(candidate) else None


def _catalog_text(value: object, *, max_length: int) -> str | None:
    candidate = " ".join(str(value or "").split())
    return candidate if 1 <= len(candidate) <= max_length else None


def _catalog_int(
    value: object,
    *,
    minimum: int,
    maximum: int,
) -> int | None:
    if isinstance(value, bool):
        return None
    try:
        parsed = int(value)
    except (TypeError, ValueError, OverflowError):
        return None
    return parsed if minimum <= parsed <= maximum else None


@dataclass(frozen=True)
class CheckoutQuote:
    tariff: dict[str, Any]
    period: dict[str, Any]
    method: dict[str, Any]
    provider_method: int
    base_amount_rub: int
    amount_rub: int
    pay_amount_rub: int
    provider_request_amount_rub: int | float
    discount_percent: int
    discount_rub: int
    referrer_telegram_id: int | None


def purchase_periods(settings: Settings) -> list[dict[str, Any]]:
    raw = settings.data.get("purchase_periods")
    periods = [item for item in raw if isinstance(item, dict)] if isinstance(raw, list) else []
    return periods or [{"months": 1, "title": "1 месяц"}]


def find_period(settings: Settings, months: int) -> dict[str, Any] | None:
    return next(
        (period for period in purchase_periods(settings) if period_months(period) == months),
        None,
    )


def trial_allows_extended_purchase(order_store: OrderStore, telegram_id: int) -> bool:
    trial = order_store.get_trial_access(telegram_id)
    return bool(
        trial
        and str(trial.get("status") or "") == "completed"
        and trial.get("first_traffic_at")
    )


def _tariff_purchase_months(tariff: dict[str, Any]) -> set[int] | None:
    raw = tariff.get("purchase_period_months")
    if not isinstance(raw, list) or not raw:
        return None
    result: set[int] = set()
    for value in raw:
        try:
            months = int(value)
        except (TypeError, ValueError):
            continue
        if months > 0:
            result.add(months)
    return result or None


def purchase_period_allowed(
    tariff: dict[str, Any],
    months: int,
    *,
    allow_extended: bool,
) -> bool:
    if allow_extended:
        return True
    allowed = _tariff_purchase_months(tariff)
    return allowed is None or months in allowed


def catalog(settings: Settings, order_store: OrderStore, telegram_id: int) -> dict[str, object]:
    extended = trial_allows_extended_purchase(order_store, telegram_id)
    periods = purchase_periods(settings)
    tariff_items: list[dict[str, object]] = []
    seen_tariff_ids: set[str] = set()
    for tariff in tariffs(settings.data):
        tariff_id = _catalog_identifier(tariff.get("id"))
        title = _catalog_text(tariff.get("title"), max_length=120)
        description = _catalog_text(tariff.get("description"), max_length=500)
        traffic_limit = _catalog_int(
            tariff.get("traffic_limit_bytes", 0),
            minimum=0,
            maximum=MAX_SAFE_INTEGER,
        )
        device_limit = _catalog_int(
            tariff.get("hwid_device_limit"),
            minimum=1,
            maximum=100,
        )
        if (
            tariff_id is None
            or tariff_id in seen_tariff_ids
            or title is None
            or description is None
            or traffic_limit is None
            or device_limit is None
        ):
            continue

        available_periods: list[dict[str, object]] = []
        seen_months: set[int] = set()
        for period in periods:
            months = _catalog_int(
                period.get("months", 1),
                minimum=1,
                maximum=36,
            )
            period_name = _catalog_text(period_title(period), max_length=80)
            try:
                amount_rub = _catalog_int(
                    period_total(tariff, period),
                    minimum=1,
                    maximum=1_000_000,
                )
            except (TypeError, ValueError, OverflowError):
                amount_rub = None
            if (
                months is None
                or months in seen_months
                or period_name is None
                or amount_rub is None
                or not purchase_period_allowed(
                    tariff,
                    months,
                    allow_extended=extended,
                )
            ):
                continue
            available_periods.append(
                {
                    "months": months,
                    "title": period_name,
                    "amountRub": amount_rub,
                }
            )
            seen_months.add(months)
            if len(available_periods) == 24:
                break

        tariff_items.append(
            {
                "id": tariff_id,
                "title": title,
                "description": description,
                "purchaseEnabled": bool(tariff.get("purchase_enabled", True)),
                "trafficLimitBytes": traffic_limit,
                "deviceLimit": device_limit,
                "periods": available_periods,
            }
        )
        seen_tariff_ids.add(tariff_id)
        if len(tariff_items) == 50:
            break

    method_items: list[dict[str, object]] = []
    seen_method_ids: set[str] = set()
    for method in payment_methods(settings.data):
        method_id = _catalog_identifier(method.get("id"))
        title = _catalog_text(method.get("title"), max_length=120)
        provider_method = _catalog_int(
            method.get("platega_method"),
            minimum=1,
            maximum=MAX_SAFE_INTEGER,
        )
        fee_percent = _catalog_int(
            method.get("fee_percent", 0),
            minimum=0,
            maximum=100,
        )
        if (
            method_id is None
            or method_id in seen_method_ids
            or title is None
            or provider_method is None
            or fee_percent is None
        ):
            continue
        method_items.append(
            {
                "id": method_id,
                "title": title,
                "feePercent": fee_percent,
            }
        )
        seen_method_ids.add(method_id)
        if len(method_items) == 20:
            break

    slots = settings.data.get("slots") if isinstance(settings.data.get("slots"), dict) else {}
    traffic = mobile_traffic_config(settings.data)
    addon_items: list[dict[str, object]] = []
    raw_addons = (
        (
            "slot_addon",
            slots.get("title") or "Дополнительное устройство",
            bool(slots.get("enabled", True)),
            slot_price_rub(settings.data),
            slot_amount(settings.data),
            slot_traffic_delta_bytes(settings.data),
        ),
        (
            "traffic_addon",
            traffic.get("title") or "Дополнительный трафик",
            mobile_traffic_enabled(settings.data),
            mobile_traffic_price_rub(settings.data),
            0,
            mobile_traffic_amount_bytes(settings.data),
        ),
    )
    for (
        addon_id,
        raw_title,
        enabled,
        raw_amount,
        raw_device_delta,
        raw_traffic_delta,
    ) in raw_addons:
        title = _catalog_text(raw_title, max_length=120)
        amount_rub = _catalog_int(raw_amount, minimum=1, maximum=1_000_000)
        device_delta = _catalog_int(raw_device_delta, minimum=0, maximum=100)
        traffic_delta = _catalog_int(
            raw_traffic_delta,
            minimum=0,
            maximum=MAX_SAFE_INTEGER,
        )
        if (
            title is None
            or amount_rub is None
            or device_delta is None
            or traffic_delta is None
        ):
            continue
        addon_items.append(
            {
                "id": addon_id,
                "title": title,
                "enabled": enabled,
                "amountRub": amount_rub,
                "deviceDelta": device_delta,
                "trafficDeltaBytes": traffic_delta,
            }
        )

    return {
        "tariffs": tariff_items,
        "paymentMethods": method_items,
        "addons": addon_items,
    }


def checkout_quote(
    *,
    settings: Settings,
    order_store: OrderStore,
    telegram_id: int,
    tariff_id: str,
    months: int,
    method_id: str,
    renewal: bool,
) -> CheckoutQuote:
    tariff = find_tariff(settings.data, tariff_id)
    period = find_period(settings, months)
    method = payment_method(settings.data, method_id)
    platega = settings.data.get("platega") if isinstance(settings.data.get("platega"), dict) else {}
    if tariff is None or period is None or method is None:
        raise CommerceError("checkout_option_not_found")
    if not renewal and not bool(tariff.get("purchase_enabled", True)):
        raise CommerceError("tariff_unavailable")
    if not renewal and not purchase_period_allowed(
        tariff,
        months,
        allow_extended=trial_allows_extended_purchase(order_store, telegram_id),
    ):
        raise CommerceError("purchase_period_not_allowed")
    if not platega.get("enabled", False):
        raise CommerceError("payments_disabled")
    try:
        provider_method = int(method.get("platega_method") or 0)
    except (TypeError, ValueError):
        provider_method = 0
    if provider_method <= 0:
        raise CommerceError("payment_method_unavailable")

    base_amount = period_total(tariff, period)
    discount_percent = 0
    discount_rub = 0
    referrer_telegram_id: int | None = None
    if not renewal:
        referrals = settings.data.get("referrals") if isinstance(settings.data.get("referrals"), dict) else {}
        if referrals.get("enabled", True):
            try:
                configured_percent = int(referrals.get("discount_percent") or 20)
            except (TypeError, ValueError):
                configured_percent = 20
            discount_percent, referrer_telegram_id = order_store.referral_discount(
                telegram_id,
                configured_percent,
            )
            discount_rub = referral_discount_amount(base_amount, discount_percent)

    amount_rub = max(1, base_amount - discount_rub)
    pay_amount_rub = payment_method_amount(amount_rub, method)
    return CheckoutQuote(
        tariff=tariff,
        period=period,
        method=method,
        provider_method=provider_method,
        base_amount_rub=base_amount,
        amount_rub=amount_rub,
        pay_amount_rub=pay_amount_rub,
        provider_request_amount_rub=payment_method_request_amount(pay_amount_rub, method),
        discount_percent=discount_percent,
        discount_rub=discount_rub,
        referrer_telegram_id=referrer_telegram_id,
    )
