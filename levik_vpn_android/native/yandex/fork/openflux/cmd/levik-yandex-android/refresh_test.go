//go:build linux || android || darwin

package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/p1neappleXpress/OpenFlux/transport/yandex"
)

func refreshFixture(id int64) refreshCommandWire {
	init := initFixture()
	return refreshCommandWire{protocolMagic, protocolVersion, "REFRESH", id, init.LeaseExpiresAt, init.ProviderAuth}
}

func TestRefreshCannotImportImmutableBindingsOrNestedOptions(t *testing.T) {
	encoded, _ := json.Marshal(refreshFixture(1))
	for _, invalid := range []string{
		strings.Replace(string(encoded), `"type":"REFRESH"`, `"type":"REFRESH","sharedKey":"private"`, 1),
		strings.Replace(string(encoded), `"type":"REFRESH"`, `"type":"REFRESH","documentUrl":"https://private"`, 1),
		strings.Replace(string(encoded), `"type":"REFRESH"`, `"type":"REFRESH","leaseRef":"private"`, 1),
		strings.Replace(string(encoded), `"balancerUrl"`, `"BalancerURL"`, 1),
		strings.Replace(string(encoded), `"providerAuth":{`, `"providerAuth":{"cookies":{},`, 1),
		strings.Replace(string(encoded), `"requestId":1`, `"requestId":1,"requestId":2`, 1),
	} {
		var command refreshCommandWire
		if strictDecode([]byte(invalid), &command) == nil {
			t.Fatal("refresh imported extra state or an ambiguous field")
		}
	}
}

func TestVerifiedRefreshPreservesTimerUntilProviderAdmission(t *testing.T) {
	ctx, cancel := context.WithCancelCause(context.Background())
	defer cancel(nil)
	old := time.Now().Add(250 * time.Millisecond)
	lease := newCredentialLease(old, "provider_auth_expired", cancel)
	defer lease.stop()
	command := refreshFixture(1)
	denied := lease.refresh(ctx, command, func(context.Context, yandex.GuestBootstrap) error { return errors.New("private-provider-message") })
	if denied.OK || denied.Code != "refresh_authorization_failed" || !lease.deadline.Equal(old) {
		t.Fatal("failed proof changed old deadline")
	}
	accepted := lease.refresh(ctx, command, func(context.Context, yandex.GuestBootstrap) error { return nil })
	if !accepted.OK || accepted.RequestID != 1 || accepted.ValidUntil != command.ProviderAuth.ValidUntil || !lease.deadline.After(old) {
		t.Fatal("admitted refresh not published")
	}
	// An already queued callback from the old timer rechecks the replacement.
	lease.expire()
	select {
	case <-ctx.Done():
		t.Fatal("old timer revoked verified refresh")
	default:
	}
	encoded, _ := json.Marshal(accepted)
	if strings.Contains(string(encoded), "token") || strings.Contains(string(encoded), "balancer") || strings.Contains(string(encoded), "private") {
		t.Fatal("ACK reflected credentials")
	}
}

func TestRefreshCannotReviveExpiredLeaseOrOutliveCancellation(t *testing.T) {
	ctx, cancel := context.WithCancelCause(context.Background())
	defer cancel(nil)
	lease := newCredentialLease(time.Now().Add(150*time.Millisecond), "lease_expired", cancel)
	defer lease.stop()
	result := lease.refresh(ctx, refreshFixture(1), func(probe context.Context, _ yandex.GuestBootstrap) error { <-probe.Done(); return probe.Err() })
	if result.OK || result.Code != "refresh_authorization_failed" {
		t.Fatal("deadline-crossing proof admitted")
	}
	select {
	case <-ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("old deadline was extended by pending proof")
	}
	if result := lease.refresh(ctx, refreshFixture(2), func(context.Context, yandex.GuestBootstrap) error { t.Fatal("expired lease verified"); return nil }); result.OK || result.Code != "refresh_expired" {
		t.Fatal("expired lease revived")
	}
}

func TestRefreshBoundsRejectBeforeAnyProviderOperation(t *testing.T) {
	ctx, cancel := context.WithCancelCause(context.Background())
	defer cancel(nil)
	lease := newCredentialLease(time.Now().Add(time.Minute), "lease_expired", cancel)
	defer lease.stop()
	for _, change := range []func(*refreshCommandWire){
		func(c *refreshCommandWire) { c.RequestID = 0 },
		func(c *refreshCommandWire) { c.RequestID = 1<<53 + 1 },
		func(c *refreshCommandWire) { c.LeaseExpiresAt = time.Now().Add(2 * time.Hour).Unix() },
		func(c *refreshCommandWire) { c.ProviderAuth.ValidUntil = c.LeaseExpiresAt + 1 },
		func(c *refreshCommandWire) { c.ProviderAuth.Token = strings.Repeat("x", 8193) },
	} {
		command := refreshFixture(1)
		change(&command)
		result := lease.refresh(ctx, command, func(context.Context, yandex.GuestBootstrap) error {
			t.Fatal("invalid refresh reached provider")
			return nil
		})
		if result.OK || result.Code != "invalid_refresh" {
			t.Fatal("invalid refresh accepted")
		}
	}
}

func TestStopAndStatusRemainResponsiveDuringSingleRefresh(t *testing.T) {
	client, peer := unixPair(t)
	control := &controlChannel{conn: client, reader: bufio.NewReaderSize(client, maxControlFrame)}
	ctx, cancel := context.WithCancelCause(context.Background())
	defer cancel(nil)
	entered := make(chan struct{})
	done := make(chan struct{})
	go func() {
		control.runCommands(ctx, cancel, func() helperStatus { return helperStatus{State: "running", StrictSession: true} }, func(probe context.Context, command refreshCommandWire) refreshResult {
			close(entered)
			<-probe.Done()
			return refreshResult{RequestID: command.RequestID, Code: "refresh_expired"}
		})
		close(done)
	}()
	encoder := json.NewEncoder(peer)
	_ = encoder.Encode(refreshFixture(1))
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("refresh did not begin")
	}
	_ = encoder.Encode(refreshFixture(2))
	_ = encoder.Encode(commandWire{protocolMagic, protocolVersion, "STATUS"})
	_ = peer.SetReadDeadline(time.Now().Add(time.Second))
	reader := bufio.NewReaderSize(peer, maxControlFrame)
	frame, err := readFrame(reader)
	if err != nil || !strings.Contains(string(frame), `"refresh_busy"`) || strings.Contains(string(frame), `"token"`) {
		t.Fatal("second refresh was queued or leaked metadata")
	}
	frame, err = readFrame(reader)
	if err != nil || !strings.Contains(string(frame), `"type":"status"`) {
		t.Fatal("status blocked behind proof")
	}
	_ = encoder.Encode(commandWire{protocolMagic, protocolVersion, "STOP"})
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("STOP failed to cancel pending provider proof")
	}
}

func TestStandbyDNSFailureKeepsOldLeaseCarrierAndControlResponsive(t *testing.T) {
	networkClient, networkPeer := unixPair(t)
	ctx, cancel := context.WithCancelCause(context.Background())
	defer cancel(nil)
	var channelFailures atomic.Int32
	network := newNetworkChannel(networkClient, func() { channelFailures.Add(1); cancel(errNetworkChannel) })
	go func() {
		reader := bufio.NewReaderSize(networkPeer, maxNetworkFrame)
		for index := 0; index < 2; index++ {
			frame, _ := readFrame(reader)
			var request networkRequest
			_ = strictDecode(frame, &request)
			ack := resolveACK{Magic: protocolMagic, Version: protocolVersion, Type: "RESOLVE_HOST_ACK", RequestID: request.RequestID, OK: index == 1, Addresses: []string{}}
			if index == 0 {
				ack.Code = "resolve_failed"
			} else {
				ack.Addresses = []string{"8.8.8.8"}
			}
			_ = json.NewEncoder(networkPeer).Encode(ack)
		}
	}()
	old := time.Now().Add(time.Minute)
	lease := newCredentialLease(old, "provider_auth_expired", cancel)
	defer lease.stop()
	controlClient, controlPeer := unixPair(t)
	control := &controlChannel{conn: controlClient, reader: bufio.NewReaderSize(controlClient, maxControlFrame)}
	done := make(chan struct{})
	go func() {
		control.runCommands(ctx, cancel, func() helperStatus { return helperStatus{State: "running", StrictSession: true} }, func(probe context.Context, command refreshCommandWire) refreshResult {
			return lease.refresh(probe, command, func(probe context.Context, _ yandex.GuestBootstrap) error {
				_, err := network.lookup(probe, "fixture.onlyoffice.disk.yandex.net")
				return err
			})
		})
		close(done)
	}()
	encoder := json.NewEncoder(controlPeer)
	_ = encoder.Encode(refreshFixture(1))
	_ = controlPeer.SetReadDeadline(time.Now().Add(time.Second))
	reader := bufio.NewReaderSize(controlPeer, maxControlFrame)
	frame, err := readFrame(reader)
	if err != nil || !strings.Contains(string(frame), `"refresh_authorization_failed"`) || channelFailures.Load() != 0 || ctx.Err() != nil {
		t.Fatal("standby DNS failure terminated admitted carrier")
	}
	lease.mu.Lock()
	unchanged := lease.deadline.Equal(old)
	lease.mu.Unlock()
	if !unchanged {
		t.Fatal("failed standby proof extended the old deadline")
	}
	_ = encoder.Encode(commandWire{protocolMagic, protocolVersion, "STATUS"})
	frame, err = readFrame(reader)
	if err != nil || !strings.Contains(string(frame), `"strictSession":true`) {
		t.Fatal("old session status unavailable after failed proof")
	}
	addresses, err := network.lookup(ctx, "fixture.onlyoffice.disk.yandex.net")
	if err != nil || len(addresses) != 1 || channelFailures.Load() != 0 {
		t.Fatal("negative ACK left next DNS exchange misaligned")
	}
	_ = encoder.Encode(commandWire{protocolMagic, protocolVersion, "STOP"})
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("STOP did not remain responsive")
	}
}
