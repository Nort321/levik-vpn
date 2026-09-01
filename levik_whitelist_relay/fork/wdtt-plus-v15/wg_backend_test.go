// SPDX-License-Identifier: GPL-3.0-only

package main

import (
	"errors"
	"reflect"
	"strings"
	"testing"
)

func TestConfigureKernelWGPassesPrivateKeyOnlyOnStdin(t *testing.T) {
	privateKey := "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
	var gotName string
	var gotArgs []string
	var gotStdin []byte

	runner := func(name string, args []string, stdin []byte) (string, error) {
		gotName = name
		gotArgs = append([]string(nil), args...)
		gotStdin = append([]byte(nil), stdin...)
		return "", nil
	}

	if err := configureKernelWG(privateKey, 56001, runner); err != nil {
		t.Fatal(err)
	}
	if gotName != "wg" {
		t.Fatalf("unexpected command %q", gotName)
	}
	wantArgs := []string{"set", wgIfaceName, "private-key", "/dev/stdin", "listen-port", "56001"}
	if !reflect.DeepEqual(gotArgs, wantArgs) {
		t.Fatalf("unexpected argv: %#v", gotArgs)
	}
	if strings.Contains(strings.Join(gotArgs, "\x00"), privateKey) {
		t.Fatal("WireGuard private key was exposed in process argv")
	}
	if string(gotStdin) != privateKey+"\n" {
		t.Fatal("WireGuard private key was not passed exactly via stdin")
	}
	for index, arg := range gotArgs {
		if arg == "private-key" && (index+1 >= len(gotArgs) || gotArgs[index+1] != "/dev/stdin") {
			t.Fatalf("private-key references a filesystem path: %#v", gotArgs)
		}
	}
}

func TestConfigureKernelWGClearsBorrowedStdinBuffer(t *testing.T) {
	privateKey := "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
	var borrowed []byte
	runner := func(_ string, _ []string, stdin []byte) (string, error) {
		borrowed = stdin
		return "", nil
	}

	if err := configureKernelWG(privateKey, 56001, runner); err != nil {
		t.Fatal(err)
	}
	if len(borrowed) == 0 {
		t.Fatal("runner did not receive stdin")
	}
	for _, value := range borrowed {
		if value != 0 {
			t.Fatal("WireGuard private key stdin buffer was not cleared")
		}
	}
}

func TestConfigureKernelWGRedactsPrivateKeyFromCommandFailure(t *testing.T) {
	privateKey := "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
	runner := func(_ string, _ []string, _ []byte) (string, error) {
		return "wg rejected key " + privateKey, errors.New("exit status 1: " + privateKey)
	}

	err := configureKernelWG(privateKey, 56001, runner)
	if err == nil {
		t.Fatal("expected wg failure")
	}
	if strings.Contains(err.Error(), privateKey) {
		t.Fatalf("private key leaked through command error: %v", err)
	}
	if !strings.Contains(err.Error(), "[REDACTED]") {
		t.Fatalf("redaction marker missing from error: %v", err)
	}
}

func TestConfigureKernelWGRedactsPrivateKeyFromBareExecutionError(t *testing.T) {
	privateKey := "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
	runner := func(_ string, _ []string, _ []byte) (string, error) {
		return "", errors.New("cannot execute with " + privateKey)
	}

	err := configureKernelWG(privateKey, 56001, runner)
	if err == nil || strings.Contains(err.Error(), privateKey) {
		t.Fatalf("private key leaked through bare execution error: %v", err)
	}
}
