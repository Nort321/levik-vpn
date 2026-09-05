// SPDX-License-Identifier: GPL-3.0-only
//go:build linux || android

package main

import (
	"encoding/binary"
	"testing"
)

func TestParseSocksUDPPacketDomain(t *testing.T) {
	packet := []byte{0, 0, 0, socksAddressDomain, 11}
	packet = append(packet, []byte("example.com")...)
	port := make([]byte, 2)
	binary.BigEndian.PutUint16(port, 443)
	packet = append(packet, port...)
	packet = append(packet, []byte("payload")...)

	target, offset, header, err := parseSocksUDPPacket(packet)
	if err != nil {
		t.Fatal(err)
	}
	if target != "example.com:443" {
		t.Fatalf("unexpected target %q", target)
	}
	if string(packet[offset:]) != "payload" {
		t.Fatalf("unexpected payload offset %d", offset)
	}
	if len(header) != offset {
		t.Fatalf("unexpected response header length %d", len(header))
	}
}

func TestParseSocksUDPPacketRejectsFragments(t *testing.T) {
	packet := []byte{0, 0, 1, socksAddressIPv4, 1, 1, 1, 1, 0, 53}
	if _, _, _, err := parseSocksUDPPacket(packet); err == nil {
		t.Fatal("expected fragmented SOCKS UDP packet to be rejected")
	}
}

func TestProxyCredentialValidation(t *testing.T) {
	if !validProxyCredential("valid_User-123456", 16, 64) {
		t.Fatal("expected URL-safe credential to be accepted")
	}
	if validProxyCredential("invalid credential", 16, 64) {
		t.Fatal("expected credential with spaces to be rejected")
	}
}
