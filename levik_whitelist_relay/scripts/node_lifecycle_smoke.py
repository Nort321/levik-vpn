#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Fail-closed synthetic lifecycle smoke test for a Levik relay node."""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import socket
import ssl
import stat
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Callable, Mapping, Sequence


HMAC_SCHEME = "levik-hmac-v1"
MAX_RESPONSE_BYTES = 16 << 10
MAX_SECRET_BYTES = 4096
MAX_TLS_FILE_BYTES = 1 << 20
DEFAULT_TIMEOUT_SECONDS = 15.0
DEFAULT_LEASE_SECONDS = 600
MIN_LEASE_SECONDS = 120
MAX_LEASE_SECONDS = 86_400

KEY_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
NONCE_RE = re.compile(r"^[A-Za-z0-9_-]{22,128}$")
IDEMPOTENCY_RE = re.compile(r"^[A-Za-z0-9._:-]{16,128}$")
HASH_RE = re.compile(r"^[0-9a-f]{64}$")
LEASE_REF_RE = re.compile(r"^[A-Za-z0-9_-]{43}$")
REQUEST_ID_RE = re.compile(r"^[0-9a-f]{24}$")
PASSWORD_CHARS = frozenset(
    "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
)
KNOWN_ERROR_CODES = frozenset(
    {
        "invalid_request",
        "unauthorized",
        "body_too_large",
        "rate_limited",
        "not_found",
        "stale_revision",
        "revision_gap",
        "state_conflict",
        "wdtt_rejected",
        "node_unavailable",
    }
)
KNOWN_STATES = frozenset({"active", "expired", "revoked", "absent", "unknown"})
OPERATIONS = frozenset({"apply", "status", "revoke"})


class SmokeError(Exception):
    """An intentionally non-sensitive operator-facing failure."""

    def __init__(
        self,
        code: str,
        *,
        step: str | None = None,
        http_status: int | None = None,
        node_code: str | None = None,
    ) -> None:
        super().__init__(code)
        self.code = code
        self.step = step if step in OPERATIONS else None
        self.http_status = http_status
        self.node_code = node_code if node_code in KNOWN_ERROR_CODES else None


class _DuplicateJSONKey(ValueError):
    pass


class SafeArgumentParser(argparse.ArgumentParser):
    """Do not reflect malformed argv, which may contain operator mistakes."""

    def error(self, message: str) -> None:
        del message
        self.exit(2, '{"error":"arguments_invalid","ok":false}\n')


@dataclass(frozen=True)
class _FileSnapshot:
    path: str
    device: int
    inode: int
    size: int
    modified_ns: int


@dataclass(frozen=True, repr=False)
class PreparedRequest:
    operation: str
    url: str = field(repr=False)
    request_uri: str = field(repr=False)
    body: bytes = field(repr=False)
    headers: Mapping[str, str] = field(repr=False)

    def __repr__(self) -> str:
        return f"PreparedRequest(operation={self.operation!r}, redacted=True)"


@dataclass(frozen=True, repr=False)
class HTTPResponse:
    status: int
    headers: Mapping[str, str] = field(repr=False)
    body: bytes = field(repr=False)

    def __repr__(self) -> str:
        return f"HTTPResponse(status={self.status}, body=redacted)"


@dataclass(frozen=True)
class ExpectedResponse:
    http_status: int
    state: str
    revision: int
    expires_at: int | None
    shape: str


@dataclass(frozen=True, repr=False)
class LeaseObservation:
    lease_ref: str
    state: str
    revision: int
    expires_at: int | None

    def __repr__(self) -> str:
        return (
            "LeaseObservation(lease_ref=redacted, "
            f"state={self.state!r}, revision={self.revision}, "
            f"expires_at={self.expires_at!r})"
        )


@dataclass(frozen=True, repr=False)
class LifecycleStep:
    operation: str
    revision: int
    request_expires_at: int
    idempotency_key: str = field(repr=False)
    expected: ExpectedResponse


@dataclass(frozen=True, repr=False)
class LifecyclePlan:
    subscription_hash: str = field(repr=False)
    device_hash: str = field(repr=False)
    lease_ref: str = field(repr=False)
    expires_at: int
    steps: tuple[LifecycleStep, ...] = field(repr=False)


Sender = Callable[[PreparedRequest, float], HTTPResponse]


def _fail(
    code: str,
    *,
    step: str | None = None,
    http_status: int | None = None,
    node_code: str | None = None,
) -> SmokeError:
    return SmokeError(
        code,
        step=step,
        http_status=http_status,
        node_code=node_code,
    )


def _checked_path(path_value: str, *, private: bool, max_bytes: int) -> _FileSnapshot:
    if not path_value or not os.path.isabs(path_value):
        raise _fail("configuration_invalid")
    normalized = os.path.normpath(path_value)
    if normalized != path_value or os.path.realpath(path_value) != path_value:
        raise _fail("configuration_invalid")
    try:
        info = os.stat(path_value, follow_symlinks=False)
    except (OSError, ValueError) as exc:
        raise _fail("configuration_invalid") from exc
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        raise _fail("configuration_invalid")
    if info.st_size <= 0 or info.st_size > max_bytes:
        raise _fail("configuration_invalid")
    permissions = stat.S_IMODE(info.st_mode)
    if private:
        if permissions & 0o077:
            raise _fail("configuration_invalid")
    elif permissions & 0o022:
        raise _fail("configuration_invalid")
    if info.st_uid not in {0, os.geteuid()}:
        raise _fail("configuration_invalid")
    return _FileSnapshot(
        path=path_value,
        device=info.st_dev,
        inode=info.st_ino,
        size=info.st_size,
        modified_ns=info.st_mtime_ns,
    )


def _same_file(before: _FileSnapshot, after: _FileSnapshot) -> bool:
    return (
        before.path == after.path
        and before.device == after.device
        and before.inode == after.inode
        and before.size == after.size
        and before.modified_ns == after.modified_ns
    )


def read_private_file(path_value: str, *, max_bytes: int = MAX_SECRET_BYTES) -> bytes:
    before = _checked_path(path_value, private=True, max_bytes=max_bytes)
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path_value, flags)
    except OSError as exc:
        raise _fail("configuration_invalid") from exc
    try:
        opened = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened.st_mode)
            or opened.st_dev != before.device
            or opened.st_ino != before.inode
            or opened.st_nlink != 1
        ):
            raise _fail("configuration_invalid")
        chunks: list[bytes] = []
        remaining = max_bytes + 1
        while remaining > 0:
            chunk = os.read(descriptor, min(remaining, 4096))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        value = b"".join(chunks)
    except OSError as exc:
        raise _fail("configuration_invalid") from exc
    finally:
        os.close(descriptor)
    if not value or len(value) > max_bytes:
        raise _fail("configuration_invalid")
    after = _checked_path(path_value, private=True, max_bytes=max_bytes)
    if not _same_file(before, after):
        raise _fail("configuration_invalid")
    return value


def _decode_padded_base64(value: str, *, urlsafe: bool) -> bytes:
    padded = value + ("=" * (-len(value) % 4))
    return base64.b64decode(
        padded.encode("ascii"),
        altchars=b"-_" if urlsafe else None,
        validate=True,
    )


def decode_control_key(encoded: bytes) -> bytes:
    try:
        text = encoded.decode("ascii").strip()
    except UnicodeDecodeError as exc:
        raise _fail("configuration_invalid") from exc
    if not text or any(character.isspace() for character in text):
        raise _fail("configuration_invalid")
    decoders: tuple[Callable[[str], bytes], ...] = (
        bytes.fromhex,
        lambda value: _decode_padded_base64(value, urlsafe=False),
        lambda value: _decode_padded_base64(value, urlsafe=True),
    )
    for decoder in decoders:
        try:
            decoded = decoder(text)
        except (ValueError, TypeError):
            continue
        if len(decoded) >= 32:
            return decoded
    raise _fail("configuration_invalid")


def validate_base_url(value: str) -> str:
    if not value or any(ord(character) < 0x21 for character in value):
        raise _fail("configuration_invalid")
    candidate = value[:-1] if value.endswith("/") else value
    try:
        parsed = urllib.parse.urlsplit(candidate)
        port = parsed.port
    except ValueError as exc:
        raise _fail("configuration_invalid") from exc
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or parsed.path != "/internal"
        or (port is not None and not 1 <= port <= 65_535)
        or "%" in parsed.hostname
    ):
        raise _fail("configuration_invalid")
    return urllib.parse.urlunsplit(("https", parsed.netloc, "/internal", "", ""))


def build_ssl_context(ca_file: str, client_cert_file: str, client_key_file: str) -> ssl.SSLContext:
    ca_before = _checked_path(ca_file, private=False, max_bytes=MAX_TLS_FILE_BYTES)
    cert_before = _checked_path(
        client_cert_file, private=False, max_bytes=MAX_TLS_FILE_BYTES
    )
    key_before = _checked_path(
        client_key_file, private=True, max_bytes=MAX_TLS_FILE_BYTES
    )
    key_material = read_private_file(client_key_file, max_bytes=MAX_TLS_FILE_BYTES)
    if b"PRIVATE KEY-----" not in key_material or b"ENCRYPTED" in key_material:
        raise _fail("tls_material_invalid")
    try:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.verify_mode = ssl.CERT_REQUIRED
        context.check_hostname = True
        context.options |= getattr(ssl, "OP_NO_COMPRESSION", 0)
        context.load_verify_locations(cafile=ca_file)
        context.load_cert_chain(certfile=client_cert_file, keyfile=client_key_file)
    except (OSError, ssl.SSLError) as exc:
        raise _fail("tls_material_invalid") from exc
    snapshots = (
        (ca_before, _checked_path(ca_file, private=False, max_bytes=MAX_TLS_FILE_BYTES)),
        (
            cert_before,
            _checked_path(client_cert_file, private=False, max_bytes=MAX_TLS_FILE_BYTES),
        ),
        (
            key_before,
            _checked_path(client_key_file, private=True, max_bytes=MAX_TLS_FILE_BYTES),
        ),
    )
    if not all(_same_file(before, after) for before, after in snapshots):
        raise _fail("tls_material_invalid")
    return context


def canonical_request(
    method: str,
    request_uri: str,
    timestamp: str,
    nonce: str,
    body: bytes,
) -> bytes:
    body_hash = hashlib.sha256(body).hexdigest()
    return "\n".join(
        (HMAC_SCHEME, timestamp, nonce, method.upper(), request_uri, body_hash)
    ).encode("utf-8")


def sign_request(
    key: bytes,
    method: str,
    request_uri: str,
    timestamp: str,
    nonce: str,
    body: bytes,
) -> str:
    canonical = canonical_request(method, request_uri, timestamp, nonce, body)
    return hmac.new(key, canonical, hashlib.sha256).hexdigest()


def derive_lease_ref(subscription_hash: str, device_hash: str) -> str:
    if not HASH_RE.fullmatch(subscription_hash) or not HASH_RE.fullmatch(device_hash):
        raise _fail("configuration_invalid")
    digest = hashlib.sha256(
        f"levik-lease-v1\n{subscription_hash}\n{device_hash}".encode("ascii")
    ).digest()
    return base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


def _random_idempotency(operation: str) -> str:
    value = f"smoke-{operation}-{secrets.token_urlsafe(24)}"
    if not IDEMPOTENCY_RE.fullmatch(value):
        raise _fail("internal_error")
    return value


def make_lifecycle_plan(now: int, lease_seconds: int) -> LifecyclePlan:
    if not MIN_LEASE_SECONDS <= lease_seconds <= MAX_LEASE_SECONDS:
        raise _fail("configuration_invalid")
    subscription_hash = secrets.token_hex(32)
    device_hash = secrets.token_hex(32)
    lease_ref = derive_lease_ref(subscription_hash, device_hash)
    expires_at = now + lease_seconds
    steps = (
        LifecycleStep(
            operation="apply",
            revision=1,
            request_expires_at=expires_at,
            idempotency_key=_random_idempotency("apply"),
            expected=ExpectedResponse(201, "active", 1, expires_at, "created"),
        ),
        LifecycleStep(
            operation="status",
            revision=1,
            request_expires_at=0,
            idempotency_key=_random_idempotency("status"),
            expected=ExpectedResponse(200, "active", 1, expires_at, "plain"),
        ),
        LifecycleStep(
            operation="revoke",
            revision=2,
            request_expires_at=0,
            idempotency_key=_random_idempotency("revoke"),
            expected=ExpectedResponse(200, "revoked", 2, expires_at, "plain"),
        ),
        LifecycleStep(
            operation="status",
            revision=2,
            request_expires_at=0,
            idempotency_key=_random_idempotency("status"),
            expected=ExpectedResponse(200, "revoked", 2, expires_at, "plain"),
        ),
    )
    return LifecyclePlan(subscription_hash, device_hash, lease_ref, expires_at, steps)


def _json_without_duplicates(value: bytes) -> object:
    def object_hook(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, item in pairs:
            if key in result:
                raise _DuplicateJSONKey
            result[key] = item
        return result

    try:
        return json.loads(value.decode("utf-8"), object_pairs_hook=object_hook)
    except (UnicodeDecodeError, json.JSONDecodeError, _DuplicateJSONKey) as exc:
        raise _fail("response_contract_invalid") from exc


def _header(headers: Mapping[str, str], name: str) -> str | None:
    wanted = name.casefold()
    for key, value in headers.items():
        if key.casefold() == wanted:
            return value.strip()
    return None


def validate_response_headers(headers: Mapping[str, str]) -> None:
    if _header(headers, "Content-Type") != "application/json":
        raise _fail("response_contract_invalid")
    if _header(headers, "Cache-Control") != "no-store":
        raise _fail("response_contract_invalid")
    if _header(headers, "Content-Encoding") not in {None, "identity"}:
        raise _fail("response_contract_invalid")


def validate_error_response(body: bytes) -> str:
    payload = _json_without_duplicates(body)
    if not isinstance(payload, dict) or set(payload) not in (
        {"error"},
        {"error", "requestId"},
    ):
        raise _fail("response_contract_invalid")
    code = payload.get("error")
    if type(code) is not str or code not in KNOWN_ERROR_CODES:
        raise _fail("response_contract_invalid")
    if "requestId" in payload:
        request_id = payload["requestId"]
        if type(request_id) is not str or not REQUEST_ID_RE.fullmatch(request_id):
            raise _fail("response_contract_invalid")
    return code


def validate_success_response(
    response: HTTPResponse,
    *,
    expected: ExpectedResponse,
    expected_lease_ref: str,
) -> LeaseObservation:
    validate_response_headers(response.headers)
    if response.status != expected.http_status:
        node_code: str | None = None
        try:
            node_code = validate_error_response(response.body)
        except SmokeError:
            pass
        raise _fail(
            "node_rejected",
            http_status=response.status,
            node_code=node_code,
        )
    payload = _json_without_duplicates(response.body)
    if not isinstance(payload, dict) or set(payload) != {"requestId", "lease"}:
        raise _fail("response_contract_invalid")
    request_id = payload.get("requestId")
    if type(request_id) is not str or not REQUEST_ID_RE.fullmatch(request_id):
        raise _fail("response_contract_invalid")
    lease = payload.get("lease")
    if not isinstance(lease, dict):
        raise _fail("response_contract_invalid")

    state = lease.get("state")
    if type(state) is not str or state not in KNOWN_STATES or state != expected.state:
        raise _fail("response_contract_invalid")
    required_keys = {"leaseRef", "state", "revision"}
    if state == "absent":
        if expected.expires_at is not None:
            raise _fail("response_contract_invalid")
    else:
        required_keys.add("expiresAt")
        if expected.expires_at is None:
            raise _fail("response_contract_invalid")
    if expected.shape == "created":
        required_keys.update({"created", "credential"})
    elif expected.shape != "plain":
        raise _fail("configuration_invalid")
    if set(lease) != required_keys:
        raise _fail("response_contract_invalid")

    lease_ref = lease.get("leaseRef")
    revision = lease.get("revision")
    if (
        type(lease_ref) is not str
        or not LEASE_REF_RE.fullmatch(lease_ref)
        or not hmac.compare_digest(lease_ref, expected_lease_ref)
        or type(revision) is not int
        or revision < 1
        or revision != expected.revision
    ):
        raise _fail("response_contract_invalid")

    expires_at: int | None = None
    if state != "absent":
        value = lease.get("expiresAt")
        if (
            type(value) is not int
            or value <= 0
            or value != expected.expires_at
        ):
            raise _fail("response_contract_invalid")
        expires_at = value

    if expected.shape == "created":
        if lease.get("created") is not True:
            raise _fail("response_contract_invalid")
        credential = lease.get("credential")
        if not isinstance(credential, dict) or set(credential) != {"password"}:
            raise _fail("response_contract_invalid")
        password = credential.get("password")
        if (
            type(password) is not str
            or len(password) != 16
            or any(character not in PASSWORD_CHARS for character in password)
        ):
            raise _fail("response_contract_invalid")

    return LeaseObservation(lease_ref, state, revision, expires_at)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(
        self,
        request: urllib.request.Request,
        file_pointer: object,
        code: int,
        message: str,
        headers: object,
        new_url: str,
    ) -> None:
        del request, file_pointer, code, message, headers, new_url
        return None


class HTTPSender:
    def __init__(self, context: ssl.SSLContext) -> None:
        self._opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            _NoRedirect(),
            urllib.request.HTTPSHandler(context=context),
        )

    @staticmethod
    def _read_response(stream: object) -> bytes:
        reader = getattr(stream, "read", None)
        if reader is None:
            raise _fail("transport_failed")
        try:
            body = reader(MAX_RESPONSE_BYTES + 1)
        except (OSError, ValueError) as exc:
            raise _fail("transport_failed") from exc
        if not isinstance(body, bytes) or len(body) > MAX_RESPONSE_BYTES:
            raise _fail("response_contract_invalid")
        return body

    def __call__(self, prepared: PreparedRequest, timeout: float) -> HTTPResponse:
        request = urllib.request.Request(
            prepared.url,
            data=prepared.body,
            headers=dict(prepared.headers),
            method="POST",
        )
        request.add_header("User-Agent", "levik-node-lifecycle-smoke/1")
        try:
            with self._opener.open(request, timeout=timeout) as response:
                if response.geturl() != prepared.url:
                    raise _fail("transport_failed")
                return HTTPResponse(
                    status=response.status,
                    headers=dict(response.headers.items()),
                    body=self._read_response(response),
                )
        except urllib.error.HTTPError as exc:
            try:
                if exc.geturl() != prepared.url:
                    raise _fail("transport_failed")
                return HTTPResponse(
                    status=exc.code,
                    headers=dict(exc.headers.items()),
                    body=self._read_response(exc),
                )
            finally:
                exc.close()
        except SmokeError:
            raise
        except (socket.timeout, TimeoutError) as exc:
            raise _fail("transport_timeout") from exc
        except (urllib.error.URLError, ssl.SSLError, OSError) as exc:
            raise _fail("transport_failed") from exc


class NodeClient:
    def __init__(
        self,
        *,
        base_url: str,
        key_id: str,
        control_key: bytes,
        sender: Sender | None,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        clock: Callable[[], float] = time.time,
        nonce_factory: Callable[[int], str] = secrets.token_urlsafe,
    ) -> None:
        self._base_url = validate_base_url(base_url)
        if not KEY_ID_RE.fullmatch(key_id) or len(control_key) < 32:
            raise _fail("configuration_invalid")
        if not 1.0 <= timeout <= 30.0:
            raise _fail("configuration_invalid")
        self._key_id = key_id
        self._control_key = control_key
        self._sender = sender
        self._timeout = timeout
        self._clock = clock
        self._nonce_factory = nonce_factory

    def prepare(self, plan: LifecyclePlan, step: LifecycleStep) -> PreparedRequest:
        if step.operation not in OPERATIONS or not IDEMPOTENCY_RE.fullmatch(
            step.idempotency_key
        ):
            raise _fail("configuration_invalid")
        payload = {
            "subscriptionIdHash": plan.subscription_hash,
            "deviceIdHash": plan.device_hash,
            "expiresAt": step.request_expires_at,
            "revision": step.revision,
            "idempotencyKey": step.idempotency_key,
        }
        body = json.dumps(
            payload,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("ascii")
        url = f"{self._base_url}/v1/leases/{step.operation}"
        request_uri = urllib.parse.urlsplit(url).path
        timestamp = str(int(self._clock()))
        nonce = self._nonce_factory(24)
        if not NONCE_RE.fullmatch(nonce):
            raise _fail("internal_error")
        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "Idempotency-Key": step.idempotency_key,
            "X-Levik-Key-Id": self._key_id,
            "X-Levik-Timestamp": timestamp,
            "X-Levik-Nonce": nonce,
            "X-Levik-Signature": sign_request(
                self._control_key,
                "POST",
                request_uri,
                timestamp,
                nonce,
                body,
            ),
        }
        return PreparedRequest(step.operation, url, request_uri, body, headers)

    def execute(self, plan: LifecyclePlan, step: LifecycleStep) -> LeaseObservation:
        if self._sender is None:
            raise _fail("network_disabled", step=step.operation)
        prepared = self.prepare(plan, step)
        try:
            response = self._sender(prepared, self._timeout)
            return validate_success_response(
                response,
                expected=step.expected,
                expected_lease_ref=plan.lease_ref,
            )
        except SmokeError as exc:
            if exc.step is not None:
                raise
            raise SmokeError(
                exc.code,
                step=step.operation,
                http_status=exc.http_status,
                node_code=exc.node_code,
            ) from exc

    def execute_cleanup(self, plan: LifecyclePlan, step: LifecycleStep) -> None:
        if self._sender is None or step.operation != "revoke":
            raise _fail("network_disabled", step="revoke")
        prepared = self.prepare(plan, step)
        try:
            response = self._sender(prepared, self._timeout)
            try:
                validate_success_response(
                    response,
                    expected=step.expected,
                    expected_lease_ref=plan.lease_ref,
                )
            except SmokeError:
                validate_success_response(
                    response,
                    expected=ExpectedResponse(
                        http_status=200,
                        state="absent",
                        revision=step.revision,
                        expires_at=None,
                        shape="plain",
                    ),
                    expected_lease_ref=plan.lease_ref,
                )
        except SmokeError as exc:
            raise SmokeError(
                exc.code,
                step="revoke",
                http_status=exc.http_status,
                node_code=exc.node_code,
            ) from exc


def prepare_dry_run(client: NodeClient, plan: LifecyclePlan) -> None:
    prepared = tuple(client.prepare(plan, step) for step in plan.steps)
    if len(prepared) != 4 or tuple(item.operation for item in prepared) != (
        "apply",
        "status",
        "revoke",
        "status",
    ):
        raise _fail("internal_error")


def execute_lifecycle(client: NodeClient, plan: LifecyclePlan) -> None:
    apply_attempted = False
    apply_confirmed = False
    revoke_confirmed = False
    revoke_step = plan.steps[2]
    try:
        for step in plan.steps:
            if step.operation == "apply":
                apply_attempted = True
            observation = client.execute(plan, step)
            if not hmac.compare_digest(observation.lease_ref, plan.lease_ref):
                raise _fail("response_contract_invalid", step=step.operation)
            if step.operation == "apply":
                apply_confirmed = True
            elif step.operation == "revoke":
                revoke_confirmed = True
    except SmokeError as primary_error:
        outcome_is_uncertain = (
            primary_error.code != "node_rejected" or primary_error.node_code is None
        )
        cleanup_required = not revoke_confirmed and (
            apply_confirmed or (apply_attempted and outcome_is_uncertain)
        )
        if cleanup_required:
            try:
                client.execute_cleanup(plan, revoke_step)
            except SmokeError as cleanup_error:
                raise SmokeError("cleanup_failed", step="revoke") from cleanup_error
        raise primary_error


def _positive_timeout(value: str) -> float:
    try:
        parsed = float(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be a number") from exc
    if not 1.0 <= parsed <= 30.0:
        raise argparse.ArgumentTypeError("must be between 1 and 30 seconds")
    return parsed


def _lease_seconds(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be an integer") from exc
    if not MIN_LEASE_SECONDS <= parsed <= MAX_LEASE_SECONDS:
        raise argparse.ArgumentTypeError(
            f"must be between {MIN_LEASE_SECONDS} and {MAX_LEASE_SECONDS}"
        )
    return parsed


def build_argument_parser() -> argparse.ArgumentParser:
    parser = SafeArgumentParser(
        description="Validate a synthetic Levik node lease lifecycle without exposing secrets.",
        allow_abbrev=False,
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument(
        "--dry-run",
        action="store_true",
        help="validate files and prepare signed requests without any network I/O",
    )
    mode.add_argument(
        "--execute",
        action="store_true",
        help="perform apply, status, revoke, status against the node",
    )
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--key-id", required=True)
    parser.add_argument("--control-secret-file", required=True)
    parser.add_argument("--ca-file", required=True)
    parser.add_argument("--client-cert-file", required=True)
    parser.add_argument("--client-key-file", required=True)
    parser.add_argument(
        "--timeout-seconds",
        type=_positive_timeout,
        default=DEFAULT_TIMEOUT_SECONDS,
    )
    parser.add_argument(
        "--lease-seconds",
        type=_lease_seconds,
        default=DEFAULT_LEASE_SECONDS,
    )
    return parser


def _safe_result(error: SmokeError | None, *, dry_run: bool) -> str:
    if error is None:
        payload: dict[str, object] = {
            "ok": True,
            "mode": "dry-run" if dry_run else "execute",
            "networkUsed": not dry_run,
            "validatedSteps": ["apply", "status", "revoke", "status"],
        }
    else:
        payload = {"ok": False, "error": error.code}
        if error.step is not None:
            payload["step"] = error.step
        if error.http_status is not None and 100 <= error.http_status <= 599:
            payload["httpStatus"] = error.http_status
        if error.node_code is not None:
            payload["nodeCode"] = error.node_code
    return json.dumps(payload, ensure_ascii=True, separators=(",", ":"), sort_keys=True)


def main(argv: Sequence[str] | None = None) -> int:
    arguments = build_argument_parser().parse_args(argv)
    try:
        base_url = validate_base_url(arguments.base_url)
        control_key = decode_control_key(
            read_private_file(arguments.control_secret_file)
        )
        context = build_ssl_context(
            arguments.ca_file,
            arguments.client_cert_file,
            arguments.client_key_file,
        )
        sender: Sender | None = None if arguments.dry_run else HTTPSender(context)
        client = NodeClient(
            base_url=base_url,
            key_id=arguments.key_id,
            control_key=control_key,
            sender=sender,
            timeout=arguments.timeout_seconds,
        )
        plan = make_lifecycle_plan(int(time.time()), arguments.lease_seconds)
        if arguments.dry_run:
            prepare_dry_run(client, plan)
        else:
            execute_lifecycle(client, plan)
    except SmokeError as exc:
        print(_safe_result(exc, dry_run=arguments.dry_run), file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        error = SmokeError("interrupted")
        print(_safe_result(error, dry_run=arguments.dry_run), file=sys.stderr)
        return 130
    except Exception:
        error = SmokeError("internal_error")
        print(_safe_result(error, dry_run=arguments.dry_run), file=sys.stderr)
        return 2
    print(_safe_result(None, dry_run=arguments.dry_run))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
