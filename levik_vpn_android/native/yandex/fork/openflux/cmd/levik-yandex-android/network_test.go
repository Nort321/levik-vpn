//go:build linux || android || darwin

package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

func unixPair(t *testing.T) (*net.UnixConn, *net.UnixConn) {
	t.Helper()
	// Darwin's Unix socket path limit is shorter than Go's named test dirs.
	dir, err := os.MkdirTemp("", "ydx-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: filepath.Join(dir, "ipc"), Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	accepted := make(chan *net.UnixConn, 1)
	go func() { conn, _ := listener.AcceptUnix(); accepted <- conn }()
	client, err := net.DialUnix("unix", nil, listener.Addr().(*net.UnixAddr))
	if err != nil {
		t.Fatal(err)
	}
	peer := <-accepted
	if peer == nil || verifyPeer(peer) != nil || verifyPeer(client) != nil {
		t.Fatal("same-UID local channel verification failed")
	}
	t.Cleanup(func() { client.Close(); peer.Close() })
	return client, peer
}

type rawSocket int

func (r rawSocket) Control(fn func(uintptr)) error { fn(uintptr(r)); return nil }
func (r rawSocket) Read(func(uintptr) bool) error  { return nil }
func (r rawSocket) Write(func(uintptr) bool) error { return nil }

func TestProtectPassesOneUnconnectedFDAndRequiresPositiveACK(t *testing.T) {
	client, peer := unixPair(t)
	network := newNetworkChannel(client, nil)
	fd, err := unix.Socket(unix.AF_INET, unix.SOCK_STREAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fd)
	result := make(chan error, 1)
	go func() { result <- network.protect(context.Background(), "tcp4", "8.8.8.8:443", rawSocket(fd)) }()
	buffer, oob := make([]byte, maxNetworkFrame), make([]byte, unix.CmsgSpace(4))
	_ = peer.SetReadDeadline(time.Now().Add(time.Second))
	n, on, flags, _, err := peer.ReadMsgUnix(buffer, oob)
	if err != nil || flags&(unix.MSG_TRUNC|unix.MSG_CTRUNC) != 0 {
		t.Fatal("socket request was truncated")
	}
	var request networkRequest
	if strictDecode(buffer[:n], &request) != nil || request.Type != "PROTECT_SOCKET" || request.Address != "8.8.8.8:443" || !validEnvelope(request.Magic, request.Version) {
		t.Fatal("invalid protect request")
	}
	controls, err := unix.ParseSocketControlMessage(oob[:on])
	if err != nil || len(controls) != 1 {
		t.Fatal("protect request did not carry one control message")
	}
	fds, err := unix.ParseUnixRights(&controls[0])
	if err != nil || len(fds) != 1 {
		t.Fatal("protect request did not carry one descriptor")
	}
	receivedFD := fds[0]
	defer unix.Close(receivedFD)
	if _, err := unix.Getpeername(receivedFD); err == nil {
		t.Fatal("provider socket connected before protection ACK")
	}
	select {
	case <-result:
		t.Fatal("socket protection returned before ACK")
	case <-time.After(20 * time.Millisecond):
	}
	if json.NewEncoder(peer).Encode(protectACK{Magic: protocolMagic, Version: protocolVersion, Type: "PROTECT_SOCKET_ACK", RequestID: request.RequestID, OK: true}) != nil {
		t.Fatal("ACK write failed")
	}
	if err := <-result; err != nil || network.protected.Load() != 1 {
		t.Fatal("valid ACK did not release protected socket")
	}
	if _, err := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0); err != nil {
		t.Fatal("protector took ownership of original socket")
	}
}

func TestNetworkResolverUsesOnlyProviderHostsAndChecksAllIPs(t *testing.T) {
	for _, addresses := range [][]string{{"8.8.8.8", "2606:4700:4700::1111"}, {"8.8.8.8", "127.0.0.1"}, {}, {"invalid"}} {
		client, peer := unixPair(t)
		network := newNetworkChannel(client, nil)
		go func() {
			frame, _ := readFrame(bufio.NewReaderSize(peer, maxNetworkFrame))
			var request networkRequest
			_ = strictDecode(frame, &request)
			_ = json.NewEncoder(peer).Encode(resolveACK{Magic: protocolMagic, Version: protocolVersion, Type: "RESOLVE_HOST_ACK", RequestID: request.RequestID, OK: true, Addresses: addresses})
		}()
		ips, err := network.lookup(context.Background(), "fixture.onlyoffice.disk.yandex.net")
		if len(addresses) == 2 && addresses[1] != "127.0.0.1" {
			if err != nil || len(ips) != 2 || network.resolved.Load() != 1 {
				t.Fatal("selected-network addresses rejected")
			}
		} else if err == nil {
			t.Fatal("invalid or mixed public/private answer accepted")
		}
		if _, err := network.lookup(context.Background(), "example.com"); err == nil {
			t.Fatal("user traffic host reached provider DNS IPC")
		}
	}
}

func TestNetworkACKRejectsMismatchDuplicateOversizeAndCancellation(t *testing.T) {
	for _, mode := range []string{"mismatch", "duplicate", "negative", "missing_ok", "null_ok", "negative_addresses", "wrong_type", "oversize", "cancel"} {
		t.Run(mode, func(t *testing.T) {
			client, peer := unixPair(t)
			var failed atomic.Int32
			network := newNetworkChannel(client, func() { failed.Add(1) })
			ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
			defer cancel()
			go func() {
				frame, _ := readFrame(bufio.NewReaderSize(peer, maxNetworkFrame))
				var request networkRequest
				_ = strictDecode(frame, &request)
				switch mode {
				case "mismatch":
					_ = json.NewEncoder(peer).Encode(resolveACK{Magic: protocolMagic, Version: protocolVersion, Type: "RESOLVE_HOST_ACK", RequestID: request.RequestID + 1, OK: true, Addresses: []string{"8.8.8.8"}})
				case "duplicate":
					_, _ = peer.Write([]byte(`{"magic":"LEVIK_YANDEX_ANDROID","version":2,"type":"RESOLVE_HOST_ACK","requestId":1,"ok":false,"ok":true,"addresses":["8.8.8.8"]}` + "\n"))
				case "negative":
					_ = json.NewEncoder(peer).Encode(resolveACK{Magic: protocolMagic, Version: protocolVersion, Type: "RESOLVE_HOST_ACK", RequestID: request.RequestID, OK: false, Code: "private-secret-detail"})
				case "missing_ok", "null_ok":
					ack := map[string]any{"magic": protocolMagic, "version": protocolVersion, "type": "RESOLVE_HOST_ACK", "requestId": request.RequestID, "addresses": []string{}, "code": "resolve_failed"}
					if mode == "null_ok" {
						ack["ok"] = nil
					}
					_ = json.NewEncoder(peer).Encode(ack)
				case "negative_addresses":
					_ = json.NewEncoder(peer).Encode(resolveACK{Magic: protocolMagic, Version: protocolVersion, Type: "RESOLVE_HOST_ACK", RequestID: request.RequestID, OK: false, Addresses: []string{"8.8.8.8"}, Code: "resolve_failed"})
				case "wrong_type":
					_ = json.NewEncoder(peer).Encode(resolveACK{Magic: protocolMagic, Version: protocolVersion, Type: "PROTECT_SOCKET_ACK", RequestID: request.RequestID, OK: false, Addresses: []string{}, Code: "resolve_failed"})
				case "oversize":
					_, _ = peer.Write([]byte(strings.Repeat("a", maxNetworkFrame) + "\n"))
				case "cancel":
					cancel()
				}
			}()
			started := time.Now()
			_, err := network.lookup(ctx, "docs.yandex.ru")
			if err != errNetworkChannel || failed.Load() == 0 || time.Since(started) > time.Second {
				t.Fatal("invalid ACK or cancellation did not fail closed with fixed error")
			}
		})
	}
}

func TestValidNegativeProtectACKDeniesOnlyCurrentOperation(t *testing.T) {
	client, peer := unixPair(t)
	var failed atomic.Int32
	network := newNetworkChannel(client, func() { failed.Add(1) })
	go func() {
		reader := bufio.NewReaderSize(peer, maxNetworkFrame)
		for index := 0; index < 2; index++ {
			frame, _ := readFrame(reader)
			var request networkRequest
			_ = strictDecode(frame, &request)
			ack := protectACK{Magic: protocolMagic, Version: protocolVersion, Type: "PROTECT_SOCKET_ACK", RequestID: request.RequestID, OK: index == 1}
			if !ack.OK {
				ack.Code = "protect_bind_failed"
			}
			_ = json.NewEncoder(peer).Encode(ack)
		}
	}()
	for index := 0; index < 2; index++ {
		var ack protectACK
		err := network.exchange(context.Background(), networkRequest{Type: "PROTECT_SOCKET", Network: "tcp4", Address: "8.8.8.8:443"}, -1, &ack)
		if index == 0 && !errors.Is(err, errProtectFailed) || index == 1 && err != nil || failed.Load() != 0 {
			t.Fatal("valid negative ACK closed the shared channel or released the denied operation")
		}
	}
}
