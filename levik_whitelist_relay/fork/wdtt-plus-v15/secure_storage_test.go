// SPDX-License-Identifier: GPL-3.0-only

package main

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func privateTestDirectory(t *testing.T) string {
	t.Helper()
	if os.Geteuid() != 0 {
		t.Skip("root ownership assertions require a root test container")
	}
	directory, err := os.MkdirTemp("/tmp", "levik-wdtt-storage-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(directory) })
	if err := os.Chmod(directory, 0700); err != nil {
		t.Fatal(err)
	}
	return directory
}

func canonicalTempDir(t *testing.T) string {
	t.Helper()
	if os.Geteuid() != 0 {
		t.Skip("root-owned production storage requires a root test container")
	}
	directory, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(directory, 0700); err != nil {
		t.Fatal(err)
	}
	return directory
}

func TestRootPrivateFileRoundTripAndUnsafeVariants(t *testing.T) {
	directory := privateTestDirectory(t)
	path := filepath.Join(directory, "credentials.json")
	if err := writeRootPrivateFileAtomic(path, []byte("secret-state")); err != nil {
		t.Fatal(err)
	}
	value, err := readRootPrivateFile(path, 1024)
	if err != nil || string(value) != "secret-state" {
		t.Fatalf("secure round trip failed: value=%q err=%v", value, err)
	}
	if err := os.Chmod(path, 0640); err != nil {
		t.Fatal(err)
	}
	if _, err := readRootPrivateFile(path, 1024); !errors.Is(err, errUnsafeStorage) {
		t.Fatalf("group-readable credential file accepted: %v", err)
	}
	if err := os.Chmod(path, 0600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(directory, "credentials-link.json")
	if err := os.Symlink(path, link); err != nil {
		t.Fatal(err)
	}
	if _, err := readRootPrivateFile(link, 1024); err == nil {
		t.Fatal("credential symlink accepted")
	}
	if err := os.Chmod(directory, 0750); err != nil {
		t.Fatal(err)
	}
	if err := writeRootPrivateFileAtomic(path, []byte("replacement")); err == nil {
		t.Fatal("write into permissive credential directory accepted")
	}
}

func TestWireGuardKeysAreStableAndTamperFailsClosed(t *testing.T) {
	directory := privateTestDirectory(t)
	first, err := loadOrGenerateKeys(directory)
	if err != nil {
		t.Fatal(err)
	}
	second, err := loadOrGenerateKeys(directory)
	if err != nil {
		t.Fatal(err)
	}
	if first.serverPrivate != second.serverPrivate || first.serverPublic != second.serverPublic {
		t.Fatal("server identity changed across secure reload")
	}
	path := filepath.Join(directory, "wg-keys.dat")
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(directory, "attacker-keys")
	if err := os.WriteFile(target, []byte("invalid"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(target, path); err != nil {
		t.Fatal(err)
	}
	if _, err := loadOrGenerateKeys(directory); err == nil {
		t.Fatal("symlink key substitution regenerated or loaded keys")
	}
}
