// SPDX-License-Identifier: GPL-3.0-only

package main

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func writeSecretFixture(t *testing.T, name, value string, mode os.FileMode) string {
	t.Helper()
	path := filepath.Join(canonicalTempDir(t), name)
	if err := os.WriteFile(path, []byte(value), mode); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(path, mode); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestReadOwnerOnlySecretFile(t *testing.T) {
	if os.Geteuid() != 0 {
		t.Skip("root ownership is a production invariant; run in the pinned build container")
	}
	path := writeSecretFixture(t, "secret", "  value\n", 0600)
	value, err := readOwnerOnlySecretFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if value != "value" {
		t.Fatalf("unexpected value %q", value)
	}
}

func TestReadOwnerOnlySecretFileRejectsUnsafeInputs(t *testing.T) {
	permissive := writeSecretFixture(t, "permissive", "value", 0640)
	empty := writeSecretFixture(t, "empty", " \n", 0600)
	target := writeSecretFixture(t, "target", "value", 0600)
	symlink := filepath.Join(canonicalTempDir(t), "secret-link")
	if err := os.Symlink(target, symlink); err != nil {
		t.Fatal(err)
	}
	for name, path := range map[string]string{
		"relative":   "relative-secret",
		"permissive": permissive,
		"empty":      empty,
		"symlink":    symlink,
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := readOwnerOnlySecretFile(path); !errors.Is(err, errUnsafeSecretFile) {
				t.Fatalf("expected unsafe secret error, got %v", err)
			}
		})
	}
}

func TestChooseFileSecretRejectsArgv(t *testing.T) {
	if os.Geteuid() != 0 {
		t.Skip("root ownership is a production invariant; run in the pinned build container")
	}
	path := writeSecretFixture(t, "secret", "value", 0600)
	if _, err := chooseFileSecret("leaked", path, "password", true); err == nil {
		t.Fatal("expected direct secret rejection")
	}
	if _, err := chooseFileSecret("", "", "password", true); err == nil {
		t.Fatal("expected required file rejection")
	}
}
