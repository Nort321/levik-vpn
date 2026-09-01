// SPDX-License-Identifier: AGPL-3.0-only

package statefile

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func validTestRecord() Record {
	return Record{
		LeaseRef:           strings.Repeat("a", 43),
		Label:              "lr1-abcdefghijklmnopqrstuvwxyz234567",
		Revision:           1,
		CredentialRevision: 1,
		IdempotencyRef:     strings.Repeat("b", 22),
		Operation:          "create",
		ExpiresAt:          1798761600,
	}
}

func TestOpenRejectsRelativeStatePath(t *testing.T) {
	if _, err := Open("state.json"); err == nil {
		t.Fatal("relative state path accepted")
	}
}

func TestStorePersistsAtomicallyAndRejectsUnsafeLoad(t *testing.T) {
	if os.Geteuid() != 0 {
		t.Skip("root ownership assertions require a root test container")
	}
	directory, err := os.MkdirTemp("/tmp", "levik-state-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(directory) })
	if err := os.Chmod(directory, 0700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(directory, "state.json")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	record := validTestRecord()
	if err := store.Put(record); err != nil {
		t.Fatal(err)
	}
	reloaded, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if got, ok := reloaded.Get(record.LeaseRef); !ok || got.Operation != "create" || got.UpdatedAt == 0 {
		t.Fatalf("state did not survive reopen: %#v ok=%v", got, ok)
	}
	if err := os.Chmod(path, 0640); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(path); !errors.Is(err, ErrCorrupt) {
		t.Fatalf("permissive state accepted: %v", err)
	}
	if err := os.Chmod(path, 0600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(directory, "state-link.json")
	if err := os.Symlink(path, link); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(link); err == nil {
		t.Fatal("state symlink accepted")
	}
}

func TestStoreRejectsInvalidLoadedRecord(t *testing.T) {
	if !validRecord(strings.Repeat("a", 43), validTestRecord()) {
		t.Fatal("valid test record rejected")
	}
	bad := validTestRecord()
	bad.Label = strings.Repeat("x", 80)
	if validRecord(bad.LeaseRef, bad) {
		t.Fatal("oversized malformed label accepted")
	}
	bad = validTestRecord()
	bad.CredentialRevision = bad.Revision + 1
	if validRecord(bad.LeaseRef, bad) {
		t.Fatal("future credential revision accepted")
	}
}
