# SPDX-License-Identifier: AGPL-3.0-only

from __future__ import annotations

import base64
import contextlib
import hashlib
import hmac
import importlib.util
import io
import json
import os
import ssl
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT_PATH = Path(__file__).with_name("node_lifecycle_smoke.py")
SPEC = importlib.util.spec_from_file_location("node_lifecycle_smoke", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("cannot load smoke module")
smoke = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = smoke
SPEC.loader.exec_module(smoke)


CONTROL_KEY = b"r" * 32
RESPONSE_HEADERS = {
    "Content-Type": "application/json",
    "Cache-Control": "no-store",
}
PASSWORD = "ABCDEFGHJKLMNPQR"


def response(status: int, payload: object) -> object:
    return smoke.HTTPResponse(
        status=status,
        headers=RESPONSE_HEADERS,
        body=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
    )


def envelope(request_id: int, lease: dict[str, object]) -> dict[str, object]:
    return {"requestId": f"{request_id:024x}", "lease": lease}


class HMACContractTest(unittest.TestCase):
    def test_golden_vector_matches_node_agent(self) -> None:
        body = (
            b'{"deviceIdHash":"beae0469261d83fc1ad6b28d5f5a8990b79384c2ce7c3db0e4d87296d3f47f4c",'
            b'"expiresAt":1790086400,"idempotencyKey":"423e4567-e89b-42d3-a456-426614174000",'
            b'"revision":7,"subscriptionIdHash":"229c6cc56ae91bb2e8c21b7abdd63bb48f01b4da129660ef5a7be9d0df11476c"}'
        )
        signature = smoke.sign_request(
            CONTROL_KEY,
            "POST",
            "/internal/v1/leases/apply",
            "1790000000",
            "AAAAAAAAAAAAAAAAAAAAAA",
            body,
        )
        self.assertEqual(
            signature,
            "35962d9f3a232cd11918e86013d34b865b0d3939b28b87e602d9dff32261fa56",
        )

    def test_control_key_decoder_matches_supported_encodings(self) -> None:
        encodings = (
            CONTROL_KEY.hex().encode("ascii"),
            base64.b64encode(CONTROL_KEY),
            base64.b64encode(CONTROL_KEY).rstrip(b"="),
            base64.urlsafe_b64encode(CONTROL_KEY).rstrip(b"="),
        )
        for encoded in encodings:
            with self.subTest(encoded=encoded[:4]):
                self.assertEqual(smoke.decode_control_key(encoded), CONTROL_KEY)
        with self.assertRaises(smoke.SmokeError):
            smoke.decode_control_key(b"too-short")


class ResponseContractTest(unittest.TestCase):
    def test_absent_strictly_omits_expiry(self) -> None:
        lease_ref = "A" * 43
        expected = smoke.ExpectedResponse(200, "absent", 7, None, "plain")
        accepted = response(
            200,
            envelope(
                1,
                {"leaseRef": lease_ref, "state": "absent", "revision": 7},
            ),
        )
        observation = smoke.validate_success_response(
            accepted,
            expected=expected,
            expected_lease_ref=lease_ref,
        )
        self.assertIsNone(observation.expires_at)

        for forbidden_expiry in (0, 1_800_000_000):
            with self.subTest(expires_at=forbidden_expiry), self.assertRaises(
                smoke.SmokeError
            ):
                smoke.validate_success_response(
                    response(
                        200,
                        envelope(
                            2,
                            {
                                "leaseRef": lease_ref,
                                "state": "absent",
                                "expiresAt": forbidden_expiry,
                                "revision": 7,
                            },
                        ),
                    ),
                    expected=expected,
                    expected_lease_ref=lease_ref,
                )

    def test_non_absent_requires_exact_positive_expiry(self) -> None:
        lease_ref = "B" * 43
        expected = smoke.ExpectedResponse(200, "active", 3, 1_800_000_000, "plain")
        invalid_leases = (
            {"leaseRef": lease_ref, "state": "active", "revision": 3},
            {
                "leaseRef": lease_ref,
                "state": "active",
                "expiresAt": 0,
                "revision": 3,
            },
            {
                "leaseRef": lease_ref,
                "state": "active",
                "expiresAt": 1_800_000_001,
                "revision": 3,
            },
        )
        for lease in invalid_leases:
            with self.subTest(lease=lease), self.assertRaises(smoke.SmokeError):
                smoke.validate_success_response(
                    response(200, envelope(3, lease)),
                    expected=expected,
                    expected_lease_ref=lease_ref,
                )

    def test_status_rejects_credential_or_any_extra_field(self) -> None:
        lease_ref = "C" * 43
        expected = smoke.ExpectedResponse(200, "active", 1, 1_800_000_000, "plain")
        lease = {
            "leaseRef": lease_ref,
            "state": "active",
            "expiresAt": 1_800_000_000,
            "revision": 1,
            "credential": {"password": PASSWORD},
        }
        with self.assertRaises(smoke.SmokeError) as raised:
            smoke.validate_success_response(
                response(200, envelope(4, lease)),
                expected=expected,
                expected_lease_ref=lease_ref,
            )
        self.assertNotIn(PASSWORD, str(raised.exception))

    def test_created_shape_requires_exact_credential_contract(self) -> None:
        lease_ref = "D" * 43
        expected = smoke.ExpectedResponse(201, "active", 1, 1_800_000_000, "created")
        valid = {
            "leaseRef": lease_ref,
            "state": "active",
            "expiresAt": 1_800_000_000,
            "revision": 1,
            "created": True,
            "credential": {"password": PASSWORD},
        }
        smoke.validate_success_response(
            response(201, envelope(5, valid)),
            expected=expected,
            expected_lease_ref=lease_ref,
        )
        invalid = {**valid, "credential": {"password": "not-a-credential"}}
        with self.assertRaises(smoke.SmokeError):
            smoke.validate_success_response(
                response(201, envelope(6, invalid)),
                expected=expected,
                expected_lease_ref=lease_ref,
            )

    def test_duplicate_json_keys_and_wrong_headers_fail_closed(self) -> None:
        lease_ref = "E" * 43
        expected = smoke.ExpectedResponse(200, "absent", 1, None, "plain")
        duplicate = smoke.HTTPResponse(
            200,
            RESPONSE_HEADERS,
            (
                b'{"requestId":"000000000000000000000001",'
                b'"requestId":"000000000000000000000002",'
                b'"lease":{"leaseRef":"'
                + lease_ref.encode("ascii")
                + b'","state":"absent","revision":1}}'
            ),
        )
        with self.assertRaises(smoke.SmokeError):
            smoke.validate_success_response(
                duplicate,
                expected=expected,
                expected_lease_ref=lease_ref,
            )
        with self.assertRaises(smoke.SmokeError):
            smoke.validate_success_response(
                smoke.HTTPResponse(
                    200,
                    {"Content-Type": "text/plain", "Cache-Control": "no-store"},
                    response(
                        200,
                        envelope(
                            7,
                            {"leaseRef": lease_ref, "state": "absent", "revision": 1},
                        ),
                    ).body,
                ),
                expected=expected,
                expected_lease_ref=lease_ref,
            )


class LifecycleTest(unittest.TestCase):
    def test_full_lifecycle_uses_random_identifiers_and_exact_requests(self) -> None:
        now = 1_800_000_000
        plan = smoke.make_lifecycle_plan(now, 600)
        seen_nonces: set[str] = set()
        seen_idempotency: set[str] = set()
        calls: list[dict[str, object]] = []
        status_count = 0

        def sender(prepared: object, timeout: float) -> object:
            nonlocal status_count
            self.assertEqual(timeout, 10.0)
            payload = json.loads(prepared.body)
            calls.append(payload)
            self.assertEqual(
                set(payload),
                {
                    "subscriptionIdHash",
                    "deviceIdHash",
                    "expiresAt",
                    "revision",
                    "idempotencyKey",
                },
            )
            self.assertRegex(payload["subscriptionIdHash"], r"^[0-9a-f]{64}$")
            self.assertRegex(payload["deviceIdHash"], r"^[0-9a-f]{64}$")
            idempotency = prepared.headers["Idempotency-Key"]
            nonce = prepared.headers["X-Levik-Nonce"]
            self.assertEqual(idempotency, payload["idempotencyKey"])
            self.assertNotIn(idempotency, seen_idempotency)
            self.assertNotIn(nonce, seen_nonces)
            seen_idempotency.add(idempotency)
            seen_nonces.add(nonce)
            canonical = smoke.canonical_request(
                "POST",
                prepared.request_uri,
                prepared.headers["X-Levik-Timestamp"],
                nonce,
                prepared.body,
            )
            expected_signature = hmac.new(
                CONTROL_KEY, canonical, hashlib.sha256
            ).hexdigest()
            self.assertTrue(
                hmac.compare_digest(
                    prepared.headers["X-Levik-Signature"], expected_signature
                )
            )
            lease_ref = smoke.derive_lease_ref(
                payload["subscriptionIdHash"], payload["deviceIdHash"]
            )
            common = {
                "leaseRef": lease_ref,
                "expiresAt": plan.expires_at,
            }
            request_id = len(calls)
            if prepared.operation == "apply":
                return response(
                    201,
                    envelope(
                        request_id,
                        {
                            **common,
                            "state": "active",
                            "revision": 1,
                            "created": True,
                            "credential": {"password": PASSWORD},
                        },
                    ),
                )
            if prepared.operation == "revoke":
                return response(
                    200,
                    envelope(
                        request_id,
                        {**common, "state": "revoked", "revision": 2},
                    ),
                )
            status_count += 1
            return response(
                200,
                envelope(
                    request_id,
                    {
                        **common,
                        "state": "active" if status_count == 1 else "revoked",
                        "revision": 1 if status_count == 1 else 2,
                    },
                ),
            )

        client = smoke.NodeClient(
            base_url="https://relay.example:8443/internal",
            key_id="levik-relay-control-v1",
            control_key=CONTROL_KEY,
            sender=sender,
            timeout=10.0,
            clock=lambda: now,
        )
        smoke.execute_lifecycle(client, plan)
        self.assertEqual(
            [step.operation for step in plan.steps],
            ["apply", "status", "revoke", "status"],
        )
        self.assertEqual([call["revision"] for call in calls], [1, 1, 2, 2])
        self.assertEqual(
            [call["expiresAt"] for call in calls],
            [plan.expires_at, 0, 0, 0],
        )
        self.assertEqual(len(seen_nonces), 4)
        self.assertEqual(len(seen_idempotency), 4)

    def test_dry_run_never_invokes_sender(self) -> None:
        calls = 0

        def forbidden_sender(prepared: object, timeout: float) -> object:
            nonlocal calls
            del prepared, timeout
            calls += 1
            raise AssertionError("network sender invoked")

        client = smoke.NodeClient(
            base_url="https://relay.example/internal",
            key_id="control-v1",
            control_key=CONTROL_KEY,
            sender=forbidden_sender,
            clock=lambda: 1_800_000_000,
        )
        smoke.prepare_dry_run(
            client,
            smoke.make_lifecycle_plan(1_800_000_000, 600),
        )
        self.assertEqual(calls, 0)

    def test_malicious_error_body_is_never_exposed(self) -> None:
        secret_marker = "credential-must-never-be-printed"
        plan = smoke.make_lifecycle_plan(1_800_000_000, 600)

        def sender(prepared: object, timeout: float) -> object:
            del prepared, timeout
            return smoke.HTTPResponse(
                503,
                RESPONSE_HEADERS,
                json.dumps({"password": secret_marker}).encode("utf-8"),
            )

        client = smoke.NodeClient(
            base_url="https://relay.example/internal",
            key_id="control-v1",
            control_key=CONTROL_KEY,
            sender=sender,
            clock=lambda: 1_800_000_000,
        )
        with self.assertRaises(smoke.SmokeError) as raised:
            smoke.execute_lifecycle(client, plan)
        rendered = smoke._safe_result(raised.exception, dry_run=False)
        self.assertNotIn(secret_marker, str(raised.exception))
        self.assertNotIn(secret_marker, rendered)

    def test_failure_after_apply_runs_idempotent_cleanup(self) -> None:
        plan = smoke.make_lifecycle_plan(1_800_000_000, 600)
        operations: list[str] = []

        def sender(prepared: object, timeout: float) -> object:
            del timeout
            operations.append(prepared.operation)
            if prepared.operation == "apply":
                return response(
                    201,
                    envelope(
                        1,
                        {
                            "leaseRef": plan.lease_ref,
                            "state": "active",
                            "expiresAt": plan.expires_at,
                            "revision": 1,
                            "created": True,
                            "credential": {"password": PASSWORD},
                        },
                    ),
                )
            if prepared.operation == "status":
                return smoke.HTTPResponse(200, RESPONSE_HEADERS, b"{}")
            return response(
                200,
                envelope(
                    2,
                    {
                        "leaseRef": plan.lease_ref,
                        "state": "revoked",
                        "expiresAt": plan.expires_at,
                        "revision": 2,
                    },
                ),
            )

        client = smoke.NodeClient(
            base_url="https://relay.example/internal",
            key_id="control-v1",
            control_key=CONTROL_KEY,
            sender=sender,
            clock=lambda: 1_800_000_000,
        )
        with self.assertRaises(smoke.SmokeError) as raised:
            smoke.execute_lifecycle(client, plan)
        self.assertEqual(raised.exception.code, "response_contract_invalid")
        self.assertEqual(operations, ["apply", "status", "revoke"])


class ConfigurationTest(unittest.TestCase):
    def test_base_url_is_https_and_exact_control_prefix(self) -> None:
        self.assertEqual(
            smoke.validate_base_url("https://relay.example:8443/internal/"),
            "https://relay.example:8443/internal",
        )
        invalid = (
            "http://relay.example/internal",
            "https://user:pass@relay.example/internal",
            "https://relay.example/",
            "https://relay.example/internal?debug=true",
            "https://relay.example/internal/extra",
        )
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(smoke.SmokeError):
                smoke.validate_base_url(value)

    def test_private_file_reader_rejects_unsafe_permissions_and_symlinks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            secret = root / "control-hmac"
            secret.write_bytes(base64.urlsafe_b64encode(CONTROL_KEY).rstrip(b"="))
            secret.chmod(0o600)
            self.assertEqual(
                smoke.decode_control_key(smoke.read_private_file(str(secret))),
                CONTROL_KEY,
            )
            secret.chmod(0o640)
            with self.assertRaises(smoke.SmokeError):
                smoke.read_private_file(str(secret))
            secret.chmod(0o600)
            symlink = root / "linked-secret"
            symlink.symlink_to(secret)
            with self.assertRaises(smoke.SmokeError):
                smoke.read_private_file(str(symlink))

    def test_cli_dry_run_emits_only_static_safe_metadata(self) -> None:
        encoded_key = base64.urlsafe_b64encode(CONTROL_KEY).rstrip(b"=")
        stdout = io.StringIO()
        stderr = io.StringIO()
        arguments = [
            "--dry-run",
            "--base-url",
            "https://relay.example/internal",
            "--key-id",
            "control-v1",
            "--control-secret-file",
            "/private/control-hmac",
            "--ca-file",
            "/private/ca.crt",
            "--client-cert-file",
            "/private/client.crt",
            "--client-key-file",
            "/private/client.key",
        ]
        with (
            mock.patch.object(smoke, "read_private_file", return_value=encoded_key),
            mock.patch.object(
                smoke,
                "build_ssl_context",
                return_value=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT),
            ),
            contextlib.redirect_stdout(stdout),
            contextlib.redirect_stderr(stderr),
        ):
            exit_code = smoke.main(arguments)
        self.assertEqual(exit_code, 0)
        self.assertEqual(stderr.getvalue(), "")
        payload = json.loads(stdout.getvalue())
        self.assertEqual(
            payload,
            {
                "mode": "dry-run",
                "networkUsed": False,
                "ok": True,
                "validatedSteps": ["apply", "status", "revoke", "status"],
            },
        )
        self.assertNotIn(encoded_key.decode("ascii"), stdout.getvalue())
        self.assertNotIn("control-hmac", stdout.getvalue())

    def test_argument_errors_do_not_reflect_accidental_secret_values(self) -> None:
        marker = "must-not-be-reflected"
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit) as raised:
            smoke.main(["--unknown-secret-option", marker])
        self.assertEqual(raised.exception.code, 2)
        self.assertEqual(
            json.loads(stderr.getvalue()),
            {"error": "arguments_invalid", "ok": False},
        )
        self.assertNotIn(marker, stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
