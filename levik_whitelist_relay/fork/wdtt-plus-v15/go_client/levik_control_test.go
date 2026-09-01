// SPDX-License-Identifier: GPL-3.0-only

package main

import (
	"encoding/base64"
	"testing"
)

func TestPinnedServerKeyAcceptsContractBase64URL(t *testing.T) {
	raw := make([]byte, 32)
	for index := range raw {
		raw[index] = byte(index + 240)
	}
	encoded := base64.RawURLEncoding.EncodeToString(raw)
	if len(encoded) != 43 || !validPinnedServerKey(encoded) {
		t.Fatalf("43-character base64url key rejected: %q", encoded)
	}
	decoded, err := decodePinnedWGKey(encoded)
	if err != nil || string(decoded) != string(raw) {
		t.Fatalf("base64url pin decode failed: %v", err)
	}
}

func TestPinnedServerKeyRejectsWrongLength(t *testing.T) {
	if validPinnedServerKey(base64.RawURLEncoding.EncodeToString(make([]byte, 31))) {
		t.Fatal("31-byte server key accepted")
	}
}
