from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from app.config import Settings
from app.delivery import provision_trial_user
from app.formatters import status_label
from app.orders import OrderStore
from app.remnawave import RemnawaveClient
from app.tariffs import find_tariff


DEFAULT_ADMIN_TELEGRAM_ID = 351358714


class TrialActivationError(RuntimeError):
    def __init__(self, code: str, *, retryable: bool = False) -> None:
        self.code = code
        self.retryable = retryable
        super().__init__(code)


@dataclass(frozen=True)
class TrialClaim:
    telegram_id: int
    component: str
    tariff: dict[str, Any]
    config: dict[str, int | str | bool]
    trial: dict[str, object]


@dataclass(frozen=True)
class TrialActivation:
    subscription_uuid: str
    component: str
    tariff: dict[str, Any]
    config: dict[str, int | str | bool]
    trial: dict[str, object]
    traffic_limit_bytes: int


def trial_config(settings: Settings) -> dict[str, int | str | bool]:
    raw = settings.data.get("trial") if isinstance(settings.data.get("trial"), dict) else {}

    def positive_int(key: str, default: int) -> int:
        try:
            value = int(raw.get(key) if raw.get(key) is not None else default)
        except (TypeError, ValueError):
            return default
        return value if value > 0 else default

    return {
        "enabled": bool(raw.get("enabled", False)),
        "duration_days": positive_int("duration_days", 3),
        "regular_tariff_id": str(raw.get("regular_tariff_id") or "regular"),
        "mobile_tariff_id": str(raw.get("mobile_tariff_id") or "lte_solo"),
        "mobile_traffic_limit_bytes": positive_int(
            "mobile_traffic_limit_bytes",
            1_000_000_000,
        ),
        "admin_telegram_id": positive_int(
            "admin_telegram_id",
            DEFAULT_ADMIN_TELEGRAM_ID,
        ),
    }


def trial_available(
    users: list[dict[str, Any]],
    trial: dict[str, object] | None,
    telegram_id: int,
) -> bool:
    current_status = str(trial.get("status") or "") if trial else ""
    if current_status in {"completed", "provisioning"}:
        return False

    active_users = [user for user in users if status_label(user) == "активна"]
    if not active_users:
        return trial is None or current_status == "failed"
    if current_status != "failed":
        return False

    component_uuids = {
        str(trial.get("regular_user_uuid") or ""),
        str(trial.get("mobile_user_uuid") or ""),
    }
    component_uuids.discard("")
    username_prefix = f"tg{telegram_id}_trial_"
    return all(
        str(user.get("uuid") or "") in component_uuids
        or str(user.get("username") or "").startswith(username_prefix)
        for user in active_users
    )


def claim_trial(
    *,
    telegram_id: int,
    telegram_username: str | None,
    first_name: str | None,
    component: str,
    users: list[dict[str, Any]],
    settings: Settings,
    order_store: OrderStore,
) -> TrialClaim:
    config = trial_config(settings)
    if not bool(config["enabled"]):
        raise TrialActivationError("trial_disabled")
    if component not in {"regular", "mobile"}:
        raise TrialActivationError("trial_component_invalid")
    tariff_id = str(
        config["mobile_tariff_id"]
        if component == "mobile"
        else config["regular_tariff_id"]
    )
    tariff = find_tariff(settings.data, tariff_id)
    if tariff is None:
        raise TrialActivationError("trial_tariff_unavailable", retryable=True)

    existing = order_store.get_trial_access(telegram_id)
    existing_status = str(existing.get("status") or "") if existing else ""
    if existing_status == "completed":
        raise TrialActivationError("trial_already_used")
    if existing_status != "provisioning" and not trial_available(
        users,
        existing,
        telegram_id,
    ):
        raise TrialActivationError("trial_not_eligible")

    start_status, trial = order_store.start_trial_access(
        telegram_id=telegram_id,
        telegram_username=telegram_username,
        first_name=first_name,
        duration_days=int(config["duration_days"]),
    )
    if start_status == "completed":
        raise TrialActivationError("trial_already_used")
    if start_status == "in_progress":
        raise TrialActivationError("trial_in_progress", retryable=True)
    if start_status == "support_required":
        raise TrialActivationError("trial_support_required")

    order_store.set_trial_selection(
        telegram_id,
        tariff_id=tariff_id,
        component=component,
    )
    order_store.record_event(
        telegram_id=telegram_id,
        event_name="trial_requested",
        properties={"tariff_id": tariff_id, "component": component},
    )
    return TrialClaim(
        telegram_id=telegram_id,
        component=component,
        tariff=tariff,
        config=config,
        trial=trial,
    )


async def provision_trial(
    *,
    claim: TrialClaim,
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> TrialActivation:
    traffic_limit = (
        int(claim.config["mobile_traffic_limit_bytes"])
        if claim.component == "mobile"
        else 0
    )
    try:
        existing_uuid = (
            str(claim.trial.get(f"{claim.component}_user_uuid") or "") or None
        )
        user = await provision_trial_user(
            telegram_id=claim.telegram_id,
            settings=settings,
            remnawave=remnawave,
            tariff_id=str(claim.tariff.get("id") or ""),
            component=claim.component,
            expires_at=str(claim.trial["expires_at"]),
            traffic_limit_bytes=traffic_limit,
            existing_user_uuid=existing_uuid,
        )
        user_uuid = str(user.get("uuid") or "")
        if not user_uuid:
            raise RuntimeError("trial user UUID is missing")
        order_store.mark_trial_component(
            claim.telegram_id,
            claim.component,
            user_uuid,
        )
        trial = order_store.mark_trial_completed(claim.telegram_id)
    except Exception as exc:
        order_store.mark_trial_failed(claim.telegram_id, exc.__class__.__name__)
        raise TrialActivationError(
            "trial_provisioning_failed",
            retryable=True,
        ) from exc

    order_store.record_event(
        telegram_id=claim.telegram_id,
        event_name="trial_created",
        properties={"tariff_id": str(claim.tariff.get("id") or "")},
    )
    return TrialActivation(
        subscription_uuid=user_uuid,
        component=claim.component,
        tariff=claim.tariff,
        config=claim.config,
        trial=trial,
        traffic_limit_bytes=traffic_limit,
    )


async def activate_trial(
    *,
    telegram_id: int,
    telegram_username: str | None,
    first_name: str | None,
    component: str,
    users: list[dict[str, Any]],
    settings: Settings,
    remnawave: RemnawaveClient,
    order_store: OrderStore,
) -> TrialActivation:
    claim = claim_trial(
        telegram_id=telegram_id,
        telegram_username=telegram_username,
        first_name=first_name,
        component=component,
        users=users,
        settings=settings,
        order_store=order_store,
    )
    return await provision_trial(
        claim=claim,
        settings=settings,
        remnawave=remnawave,
        order_store=order_store,
    )
