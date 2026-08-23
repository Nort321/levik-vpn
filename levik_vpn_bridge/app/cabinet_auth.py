from __future__ import annotations

import hashlib
import hmac
import json
import re
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any
from uuid import UUID

from aiohttp import web

from app.config import Settings, decode_cabinet_secret
from app.orders import OrderStore


NONCE_RE = re.compile(r"^[0-9a-f]{32}$")
SIGNATURE_RE = re.compile(r"^v1=([0-9a-f]{64})$")
MAX_BODY_BYTES = 64 * 1024
SECURITY_HEADER_NAMES = (
    "X-Cabinet-Key-Id",
    "X-Cabinet-Timestamp",
    "X-Cabinet-Nonce",
    "X-Cabinet-Signature",
    "X-Cabinet-Grant",
    "Idempotency-Key",
)


class CabinetAuthError(RuntimeError):
    def __init__(self, status: int, code: str) -> None:
        self.status = status
        self.code = code
        super().__init__(code)


@dataclass(frozen=True)
class CabinetRequestContext:
    telegram_id: int | None
    grant_hash: str
    idempotency_key: str
    body_hash: str
    payload: dict[str, Any]


def opaque_token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def _reject_json_constant(_: str) -> None:
    raise ValueError("invalid JSON number")


def canonical_request(
    *,
    method: str,
    raw_path: str,
    timestamp: str,
    nonce: str,
    idempotency_key: str,
    grant_hash: str,
    body_hash: str,
) -> bytes:
    return "\n".join(
        (
            method.upper(),
            raw_path,
            timestamp,
            nonce,
            idempotency_key,
            grant_hash,
            body_hash,
        )
    ).encode("utf-8")


def cabinet_signature(secret: str, canonical: bytes) -> str:
    digest = hmac.new(decode_cabinet_secret(secret), canonical, hashlib.sha256).hexdigest()
    return f"v1={digest}"


def _validated_idempotency_key(value: str, *, required: bool) -> str:
    if not value:
        if required:
            raise CabinetAuthError(400, "idempotency_key_required")
        return ""
    try:
        parsed = UUID(value)
    except ValueError as exc:
        raise CabinetAuthError(400, "invalid_idempotency_key") from exc
    if parsed.version != 4 or str(parsed) != value:
        raise CabinetAuthError(400, "invalid_idempotency_key")
    return str(parsed)


def _single_header(request: web.Request, name: str) -> str:
    getall = getattr(request.headers, "getall", None)
    if callable(getall):
        values = list(getall(name, []))
    else:
        value = request.headers.get(name)
        values = [] if value is None else [value]
    if len(values) > 1:
        raise CabinetAuthError(400, "duplicate_security_header")
    return str(values[0]) if values else ""


async def authenticate_cabinet_request(
    request: web.Request,
    *,
    require_grant: bool,
    require_idempotency: bool = False,
    now: int | None = None,
) -> CabinetRequestContext:
    settings: Settings = request.app["settings"]
    order_store: OrderStore = request.app["order_store"]
    if not settings.cabinet_bridge_secret:
        raise CabinetAuthError(404, "cabinet_bridge_disabled")

    headers = {
        name: _single_header(request, name)
        for name in SECURITY_HEADER_NAMES
    }
    try:
        body = await request.read()
    except web.HTTPRequestEntityTooLarge as exc:
        raise CabinetAuthError(413, "request_too_large") from exc
    if len(body) > MAX_BODY_BYTES:
        raise CabinetAuthError(413, "request_too_large")

    key_id = headers["X-Cabinet-Key-Id"]
    timestamp = headers["X-Cabinet-Timestamp"]
    nonce = headers["X-Cabinet-Nonce"]
    signature = headers["X-Cabinet-Signature"]
    grant = headers["X-Cabinet-Grant"]
    idempotency_key = _validated_idempotency_key(
        headers["Idempotency-Key"],
        required=require_idempotency,
    )

    if not hmac.compare_digest(key_id, settings.cabinet_bridge_key_id):
        raise CabinetAuthError(401, "invalid_service_key")
    if len(grant) > 256:
        raise CabinetAuthError(401, "invalid_grant")
    try:
        request_timestamp = int(timestamp)
    except ValueError as exc:
        raise CabinetAuthError(401, "invalid_timestamp") from exc
    current_timestamp = int(time.time()) if now is None else now
    if str(request_timestamp) != timestamp or abs(
        current_timestamp - request_timestamp
    ) > settings.cabinet_hmac_clock_skew_seconds:
        raise CabinetAuthError(401, "stale_request")
    if not NONCE_RE.fullmatch(nonce):
        raise CabinetAuthError(401, "invalid_nonce")
    if require_grant and not grant:
        raise CabinetAuthError(401, "grant_required")

    grant_hash = opaque_token_hash(grant)
    body_hash = hashlib.sha256(body).hexdigest()
    canonical = canonical_request(
        method=request.method,
        raw_path=request.raw_path,
        timestamp=timestamp,
        nonce=nonce,
        idempotency_key=idempotency_key,
        grant_hash=grant_hash,
        body_hash=body_hash,
    )
    expected = cabinet_signature(settings.cabinet_bridge_secret, canonical)
    match = SIGNATURE_RE.fullmatch(signature)
    if match is None or not hmac.compare_digest(signature, expected):
        raise CabinetAuthError(401, "invalid_signature")

    nonce_expires_at = datetime.fromtimestamp(
        current_timestamp,
        tz=timezone.utc,
    ) + timedelta(seconds=settings.cabinet_hmac_clock_skew_seconds * 2)
    if not order_store.claim_cabinet_nonce(
        key_id=key_id,
        nonce=nonce,
        expires_at=nonce_expires_at.isoformat(),
    ):
        raise CabinetAuthError(409, "replay_detected")

    telegram_id: int | None = None
    if require_grant:
        telegram_id = order_store.resolve_cabinet_grant(grant_hash=grant_hash)
        if telegram_id is None:
            raise CabinetAuthError(401, "invalid_or_expired_grant")

    try:
        payload = (
            json.loads(
                body.decode("utf-8"),
                object_pairs_hook=_unique_object,
                parse_constant=_reject_json_constant,
            )
            if body
            else {}
        )
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise CabinetAuthError(400, "invalid_json") from exc
    if not isinstance(payload, dict):
        raise CabinetAuthError(400, "invalid_json")

    return CabinetRequestContext(
        telegram_id=telegram_id,
        grant_hash=grant_hash,
        idempotency_key=idempotency_key,
        body_hash=body_hash,
        payload=payload,
    )
