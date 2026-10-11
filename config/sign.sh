#!/bin/sh
# Signs config/levik-config-v1.json for the apps (ECDSA P-256, SHA-256, base64 DER).
# Before signing: raise "sequence" by one and update "issuedAt"; the apps ignore
# a document whose sequence is lower than the one they already have.
# Key: ~/.config/levik/remote-config-p256.pem (keep an offline backup).
set -eu

DIR=$(cd "$(dirname "$0")" && pwd)
DOC=$DIR/levik-config-v1.json
KEY=${LEVIK_CONFIG_KEY:-$HOME/.config/levik/remote-config-p256.pem}
PUB=MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEBxwZK9Ledu9WcstW72cqoVTP9AjtB5dzNIhqmvTlm0lXRaqspdY1KbwCnzhjgCY10hAi9CL37PxhY/GGaIdAdQ==

python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["schema"]==1 and int(d["sequence"])>=1' "$DOC"
[ "$(openssl ec -in "$KEY" -pubout -outform DER 2>/dev/null | base64 | tr -d '\n')" = "$PUB" ] || {
	echo "key does not match the public key built into the apps" >&2
	exit 1
}
openssl dgst -sha256 -sign "$KEY" "$DOC" | base64 | tr -d '\n' >"$DOC.sig"
printf '\n' >>"$DOC.sig"

# Verify with the public key exactly as the apps do.
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
printf '%s' "$PUB" | base64 -d >"$TMP/pub.der"
openssl ec -pubin -inform DER -in "$TMP/pub.der" -out "$TMP/pub.pem" 2>/dev/null
base64 -d <"$DOC.sig" >"$TMP/sig.der"
openssl dgst -sha256 -verify "$TMP/pub.pem" -signature "$TMP/sig.der" "$DOC"
echo "signed sequence $(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sequence"])' "$DOC")"
