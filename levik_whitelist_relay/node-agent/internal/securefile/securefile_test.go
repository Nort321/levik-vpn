// SPDX-License-Identifier: AGPL-3.0-only

package securefile

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestReadRejectsRelativePath(t *testing.T) {
	if _, err := Read("secret"); !errors.Is(err, ErrUnsafeSecretFile) {
		t.Fatalf("relative secret path accepted: %v", err)
	}
}

func TestReadOwnerOnlySecretAndRejectUnsafeVariants(t *testing.T) {
	if os.Geteuid() != 0 {
		t.Skip("root ownership assertions require a root test container")
	}
	directory, err := os.MkdirTemp("/tmp", "levik-securefile-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(directory) })
	if err := os.Chmod(directory, 0700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(directory, "secret")
	if err := os.WriteFile(path, []byte("  test-secret\n"), 0600); err != nil {
		t.Fatal(err)
	}
	value, err := Read(path)
	if err != nil || string(value) != "test-secret" {
		t.Fatalf("owner-only secret rejected: value=%q err=%v", value, err)
	}
	if err := os.Chmod(path, 0640); err != nil {
		t.Fatal(err)
	}
	if _, err := Read(path); !errors.Is(err, ErrUnsafeSecretFile) {
		t.Fatalf("group-readable secret accepted: %v", err)
	}
	if err := os.Chmod(path, 0600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(directory, "link")
	if err := os.Symlink(path, link); err != nil {
		t.Fatal(err)
	}
	if _, err := Read(link); !errors.Is(err, ErrUnsafeSecretFile) {
		t.Fatalf("symlink secret accepted: %v", err)
	}
	if err := os.WriteFile(path, []byte(" \n"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := Read(path); !errors.Is(err, ErrUnsafeSecretFile) {
		t.Fatalf("empty secret accepted: %v", err)
	}
}
