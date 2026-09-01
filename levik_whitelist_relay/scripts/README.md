# Relay operations scripts

## Synthetic node lifecycle smoke

`node_lifecycle_smoke.py` validates the node control path with the exact
`apply -> status -> revoke -> status` sequence. A live run uses a fresh random
subscription hash, device hash, idempotency key for every operation, and HMAC
nonce for every request. It requires HTTPS with certificate validation, mTLS,
and the `levik-hmac-v1` signature.

The tool deliberately:

- accepts the HMAC secret, CA, client certificate, and client private key only
  as absolute file paths;
- rejects symlinks, replaced files, non-regular files, unsafe private-file
  permissions, and encrypted client keys;
- disables environment HTTP proxies and redirects so authentication headers
  cannot be forwarded to another origin;
- caps response bodies and rejects duplicate or extra JSON properties;
- never prints the control secret, HMAC, synthetic identity, lease reference,
  credential, response body, or TLS material;
- requires `absent` responses to omit `expiresAt`, while non-absent states must
  include the exact positive expiry;
- keeps the credential returned by `apply` only long enough to validate its
  shape and never returns it from the parser;
- makes a best-effort idempotent revoke when an apply outcome is uncertain or
  a later pre-revoke step fails; a failed cleanup is reported explicitly.

Run the no-network validation on the bridge host (`94.156.114.70`) after the
relay source has been installed at `/opt/levik-relay`:

```sh
cd /opt/levik-relay
sudo python3 scripts/node_lifecycle_smoke.py \
  --dry-run \
  --base-url https://2.27.201.130:8443/internal \
  --key-id levik-relay-control-v1 \
  --control-secret-file /opt/remnawave-bot/secrets/relay/control-hmac \
  --ca-file /opt/remnawave-bot/secrets/relay/ca.crt \
  --client-cert-file /opt/remnawave-bot/secrets/relay/client.crt \
  --client-key-file /opt/remnawave-bot/secrets/relay/client.key
```

`--dry-run` loads and validates the real TLS files, prepares all four signed
requests, and guarantees that no network sender is constructed. It emits only
static JSON metadata.

When a live synthetic lifecycle is explicitly authorized, replace `--dry-run`
with `--execute`. A live run consumes one of the node's 249 lease slots until
the revoked entry reaches its retention/purge deadline, so it must not be used
as a frequent scheduled health check. Use `/readyz` for ordinary monitoring.

The lease duration defaults to 600 seconds and can be changed with
`--lease-seconds` in the safe range 120..86400. The request timeout defaults to
15 seconds and can be changed with `--timeout-seconds` in the range 1..30.

Exit code `0` means all requested checks passed. Exit code `2` is a
configuration, transport, node, or response-contract failure. Exit code `130`
means the operator interrupted the run. Failure output contains only a stable
error code, the operation name, and—when validated as safe—the HTTP status and
node error code.

Run the isolated unit suite without contacting a node:

```sh
python3 -m unittest -v scripts/test_node_lifecycle_smoke.py
```
