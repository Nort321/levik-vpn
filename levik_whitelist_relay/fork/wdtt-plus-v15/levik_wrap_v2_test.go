// SPDX-License-Identifier: GPL-3.0-only

package main

import (
	"bytes"
	"testing"
)

func TestLevikWrapV2UsesCredentialIDAndAuthenticates(t *testing.T) {
	const password = "client-secret-123"
	key, err := deriveWrapKey(password)
	if err != nil {
		t.Fatal(err)
	}
	id := deriveLevikWrapID(password)
	payload := []byte("dtls-client-hello")
	wire, err := obfsWrapPacketV2(key, id, payload, NewObfsConfig(), NewObfsState())
	if err != nil {
		t.Fatal(err)
	}
	gotID, ok := obfsV2CredentialID(wire)
	if !ok || gotID != id {
		t.Fatal("WRAP v2 credential id missing from authenticated extension")
	}
	store := newWrapKeyStore()
	if err := store.SetPasswords("owner-secret", []string{password}); err != nil {
		t.Fatal(err)
	}
	dst := make([]byte, 256)
	selectedKey, identity, selectedID, isV2, n, err := store.Unwrap(wire, dst)
	if err != nil {
		t.Fatal(err)
	}
	defer zeroBytes(selectedKey)
	if !isV2 || selectedID != id || identity.password != password || !bytes.Equal(dst[:n], payload) {
		t.Fatalf("unexpected WRAP v2 selection: id=%x identity=%q payload=%q", selectedID, identity.id, dst[:n])
	}
}

func TestLevikWrapLegacyDisabledByDefault(t *testing.T) {
	store := newWrapKeyStore()
	if err := store.SetPasswords("owner-secret", []string{"client-secret"}); err != nil {
		t.Fatal(err)
	}
	key, err := deriveWrapKey("client-secret")
	if err != nil {
		t.Fatal(err)
	}
	wire, err := obfsWrapPacket(key, []byte("legacy"), NewObfsConfig(), NewObfsState())
	if err != nil {
		t.Fatal(err)
	}
	if _, _, _, _, _, err := store.Unwrap(wire, make([]byte, 128)); err == nil {
		t.Fatal("legacy WRAP packet accepted while compatibility flag is disabled")
	}
	store.SetAllowLegacy(true)
	if _, _, _, isV2, _, err := store.Unwrap(wire, make([]byte, 128)); err != nil || isV2 {
		t.Fatalf("explicit legacy compatibility did not accept packet: isV2=%v err=%v", isV2, err)
	}
}

func TestLevikReplayWindowRejectsDuplicate(t *testing.T) {
	key, err := deriveWrapKey("client-secret")
	if err != nil {
		t.Fatal(err)
	}
	id := deriveLevikWrapID("client-secret")
	wire, err := obfsWrapPacketV2(key, id, []byte("packet"), NewObfsConfig(), NewObfsState())
	if err != nil {
		t.Fatal(err)
	}
	var replay levikReplayWindow
	if !replay.Accept(wire) || replay.Accept(wire) {
		t.Fatal("replay window failed to accept once and reject duplicate")
	}
}

func TestOwnerAdminSecretIsNotADataPlaneCredentialByDefault(t *testing.T) {
	previousDB := db
	previousStore := serverWrapKeys
	previousAllow := allowOwnerTransportAccess
	t.Cleanup(func() {
		db = previousDB
		serverWrapKeys = previousStore
		allowOwnerTransportAccess = previousAllow
	})
	db = &Database{
		MainPassword: "owner-admin-secret",
		Passwords: map[string]*PasswordEntry{
			"leased-client-secret": {},
		},
	}
	serverWrapKeys = newWrapKeyStore()
	allowOwnerTransportAccess = false
	if err := refreshWrapKeysFromDBLocked(); err != nil {
		t.Fatal(err)
	}
	if serverWrapKeys.Count() != 1 {
		t.Fatalf("owner secret entered data plane; key count=%d", serverWrapKeys.Count())
	}
	serverWrapKeys.mu.RLock()
	defer serverWrapKeys.mu.RUnlock()
	if len(serverWrapKeys.entries) != 1 || serverWrapKeys.entries[0].identity.isMain || serverWrapKeys.entries[0].identity.password != "leased-client-secret" {
		t.Fatalf("unexpected transport identity: %#v", serverWrapKeys.entries)
	}
}

func TestEmptyDataPlaneKeyStoreFailsPacketsButPermitsIdleBootstrap(t *testing.T) {
	store := newWrapKeyStore()
	if store.Count() != 0 {
		t.Fatal("new WRAP key store is not empty")
	}
	if _, _, _, _, _, err := store.Unwrap(make([]byte, 64), make([]byte, 64)); err == nil {
		t.Fatal("empty key store authenticated a packet")
	}
	if err := validateWrapKeyStore(store); err != nil {
		t.Fatalf("idle bootstrap rejected empty key store: %v", err)
	}
	if err := validateWrapKeyStore(nil); err == nil {
		t.Fatal("nil key store accepted")
	}
}
