// SPDX-License-Identifier: GPL-3.0-only
//go:build linux || android

package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"
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

func listenTestUDP(t *testing.T) *net.UDPConn {
	t.Helper()
	socket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = socket.Close() })
	return socket
}

func readTestUDP(t *testing.T, socket *net.UDPConn) ([]byte, *net.UDPAddr) {
	t.Helper()
	if err := socket.SetReadDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 1024)
	n, source, err := socket.ReadFromUDP(buffer)
	if err != nil {
		t.Fatal(err)
	}
	return buffer[:n], source
}

func writeTestUDP(t *testing.T, socket *net.UDPConn, packet []byte, target *net.UDPAddr) {
	t.Helper()
	if _, err := socket.WriteToUDP(packet, target); err != nil {
		t.Fatal(err)
	}
}

func testUDPHeader(target *net.UDPAddr) []byte {
	header := []byte{0, 0, 0, socksAddressIPv4, 127, 0, 0, 1, 0, 0}
	binary.BigEndian.PutUint16(header[8:], uint16(target.Port))
	return header
}

func newTestSocksServer(t *testing.T) *levikSocksServer {
	t.Helper()
	server, err := newLevikSocksServer(context.Background(), &net.Dialer{},
		"test-user-12345678", "test-password-12345678901234567890")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(server.Close)
	return server
}

func authenticatedTestUDPAssociation(t *testing.T, server *levikSocksServer, udpClient *net.UDPConn) *net.UDPAddr {
	t.Helper()
	client, err := net.DialTCP("tcp4", nil, server.listener.Addr().(*net.TCPAddr))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = client.Close() })
	if err := client.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatal(err)
	}
	exchange := func(request, expected []byte) {
		t.Helper()
		if _, err := client.Write(request); err != nil {
			t.Fatal(err)
		}
		response := make([]byte, len(expected))
		if _, err := io.ReadFull(client, response); err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(response, expected) {
			t.Fatalf("unexpected SOCKS handshake response: %v", response)
		}
	}
	exchange([]byte{socksVersion, 1, socksAuthPassword}, []byte{socksVersion, socksAuthPassword})
	auth := append([]byte{1, byte(len(server.username))}, server.username...)
	auth = append(auth, byte(len(server.password)))
	auth = append(auth, server.password...)
	exchange(auth, []byte{1, 0})
	request := testUDPHeader(udpClient.LocalAddr().(*net.UDPAddr))
	request[0], request[1] = socksVersion, socksCommandUDP
	exchange(request, []byte{socksVersion, socksReplyOK, 0, socksAddressIPv4})
	address := make([]byte, 6)
	if _, err := io.ReadFull(client, address); err != nil {
		t.Fatal(err)
	}
	return &net.UDPAddr{IP: net.IP(address[:4]), Port: int(binary.BigEndian.Uint16(address[4:]))}
}

func TestSocksUDPAssociationMultipleClientsAndTargets(t *testing.T) {
	socket := listenTestUDP(t)
	clients := []*net.UDPConn{listenTestUDP(t), listenTestUDP(t)}
	targets := []*net.UDPConn{listenTestUDP(t), listenTestUDP(t)}
	ctx, cancel := context.WithCancel(context.Background())
	association := &socksUDPAssociation{
		ctx: ctx, cancel: cancel, socket: socket, network: &net.Dialer{},
		relays: make(map[socksUDPRelayKey]*socksUDPRelay),
	}
	done := make(chan struct{})
	go func() { association.run(); close(done) }()
	t.Cleanup(func() {
		cancel()
		_ = socket.Close()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Error("association did not stop")
		}
	})
	proxyAddress := socket.LocalAddr().(*net.UDPAddr)
	// Reuse each flow and return replies in reverse order. Both local ports
	// must work even when they query the same DNS endpoint concurrently.
	for round := 0; round < 2; round++ {
		for _, target := range targets {
			header := testUDPHeader(target.LocalAddr().(*net.UDPAddr))
			packets := make([][]byte, len(clients))
			for i, client := range clients {
				packets[i] = append(append([]byte(nil), header...), byte(round), byte(i))
				writeTestUDP(t, client, packets[i], proxyAddress)
			}
			payloads := make([][]byte, len(clients))
			sources := make([]*net.UDPAddr, len(clients))
			for i := range clients {
				payloads[i], sources[i] = readTestUDP(t, target)
			}
			if sources[0].String() == sources[1].String() {
				t.Fatal("clients unexpectedly share an upstream flow")
			}
			for i := len(clients) - 1; i >= 0; i-- {
				writeTestUDP(t, target, payloads[i], sources[i])
			}
			for i, client := range clients {
				response, _ := readTestUDP(t, client)
				if !bytes.Equal(response, packets[i]) {
					t.Fatalf("client %d received another flow's response: %v", i, response)
				}
			}
		}
	}
	association.mu.Lock()
	count := len(association.relays)
	association.mu.Unlock()
	if count != len(clients)*len(targets) {
		t.Fatalf("got %d flows; expected one per client and target", count)
	}
}

func TestSocksUDPAuthenticatedAssociationsConcurrentDNS(t *testing.T) {
	server := newTestSocksServer(t)
	clients := []*net.UDPConn{listenTestUDP(t), listenTestUDP(t)}
	associations := []*net.UDPAddr{
		authenticatedTestUDPAssociation(t, server, clients[0]),
		authenticatedTestUDPAssociation(t, server, clients[1]),
	}
	if associations[0].String() == associations[1].String() {
		t.Fatal("authenticated clients unexpectedly share an association")
	}
	targets := []*net.UDPConn{listenTestUDP(t), listenTestUDP(t)}
	// Reuse flows, keep both queries outstanding, and reverse reply order.
	// Equal DNS transaction IDs ensure isolation comes from the association.
	for round := 0; round < 2; round++ {
		for _, target := range targets {
			header := testUDPHeader(target.LocalAddr().(*net.UDPAddr))
			packets := make([][]byte, len(clients))
			for i, client := range clients {
				query := []byte{0, byte(round), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
					1, byte('a' + i), 4, 't', 'e', 's', 't', 0, 0, 1, 0, 1}
				packets[i] = append(append([]byte(nil), header...), query...)
				writeTestUDP(t, client, packets[i], associations[i])
				packets[i][len(header)+2] |= 0x80 // Expected DNS response QR bit.
			}
			payloads := make([][]byte, len(clients))
			sources := make([]*net.UDPAddr, len(clients))
			for i := range clients {
				payloads[i], sources[i] = readTestUDP(t, target)
				if len(payloads[i]) < 12 {
					t.Fatal("truncated DNS query")
				}
				payloads[i][2] |= 0x80
			}
			if sources[0].String() == sources[1].String() {
				t.Fatal("associations unexpectedly share an upstream flow")
			}
			for i := len(clients) - 1; i >= 0; i-- {
				writeTestUDP(t, target, payloads[i], sources[i])
			}
			for i, client := range clients {
				response, _ := readTestUDP(t, client)
				if !bytes.Equal(response, packets[i]) {
					t.Fatalf("client %d received another association's DNS response: %v", i, response)
				}
			}
		}
	}
}

func TestSocksUDPAssociationPrunesAtCapacityDuringTraffic(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	association := &socksUDPAssociation{
		ctx: ctx, cancel: cancel, socket: listenTestUDP(t), network: &net.Dialer{},
		relays: make(map[socksUDPRelayKey]*socksUDPRelay),
	}
	t.Cleanup(association.close)
	for i := 0; i < socksUDPMaxTargets; i++ {
		key := socksUDPRelayKey{
			client: netip.AddrPortFrom(netip.MustParseAddr("127.0.0.1"), uint16(10000+i)),
			target: "127.0.0.1:53",
		}
		connection, peer := net.Pipe()
		_ = peer.Close()
		association.relays[key] = &socksUDPRelay{key: key, conn: connection, lastUsed: time.Now()}
	}
	target := listenTestUDP(t).LocalAddr().(*net.UDPAddr)
	key := socksUDPRelayKey{client: netip.MustParseAddrPort("127.0.0.1:20000"), target: target.String()}
	if association.relayFor(key, testUDPHeader(target)) != nil {
		t.Fatal("active flow limit must be enforced")
	}
	for _, relay := range association.relays {
		relay.lastUsed = time.Now().Add(-socksUDPIdleTimeout - time.Second)
	}
	if association.relayFor(key, testUDPHeader(target)) == nil {
		t.Fatal("idle flows must be reclaimed even without a socket read timeout")
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
