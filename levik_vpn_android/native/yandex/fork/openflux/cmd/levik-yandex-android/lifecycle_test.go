//go:build linux || android || darwin

package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"
)

func TestControlStatusThenStopAndClosedChannelCancel(t *testing.T) {
	for _, disconnect := range []bool{false, true} {
		client, peer := unixPair(t)
		control := &controlChannel{conn: client, reader: bufio.NewReaderSize(client, maxControlFrame)}
		ctx, cancel := context.WithCancelCause(context.Background())
		defer cancel(nil)
		done := make(chan struct{})
		go func() {
			control.runCommands(ctx, cancel, func() helperStatus { return helperStatus{State: "connecting", ProtectedExternalSockets: 2} }, nil)
			close(done)
		}()
		if json.NewEncoder(peer).Encode(commandWire{protocolMagic, protocolVersion, "STATUS"}) != nil {
			t.Fatal("status request failed")
		}
		_ = peer.SetReadDeadline(time.Now().Add(time.Second))
		frame, err := readFrame(bufio.NewReaderSize(peer, maxControlFrame))
		var event eventWire
		if err != nil || strictDecode(frame, &event) != nil || !validEnvelope(event.Magic, event.Version) || event.Type != "status" || event.Code != "" {
			t.Fatal("status response invalid")
		}
		if strings.Contains(string(frame), "token") || strings.Contains(string(frame), "document") || strings.Contains(string(frame), "password") {
			t.Fatal("status contained credential metadata")
		}
		if disconnect {
			peer.Close()
		} else {
			_ = json.NewEncoder(peer).Encode(commandWire{protocolMagic, protocolVersion, "STOP"})
		}
		select {
		case <-done:
		case <-time.After(time.Second):
			t.Fatal("stop or local channel loss did not terminate command loop")
		}
		if disconnect {
			if context.Cause(ctx) == nil || context.Cause(ctx).Error() != "control_channel_failed" {
				t.Fatal("channel loss was not fatal")
			}
		} else if !errors.Is(context.Cause(ctx), context.Canceled) {
			t.Fatal("STOP was not a normal cancellation")
		}
	}
}

func TestUnknownControlCommandFailsWithoutReflectingSecrets(t *testing.T) {
	client, peer := unixPair(t)
	control := &controlChannel{conn: client, reader: bufio.NewReaderSize(client, maxControlFrame)}
	ctx, cancel := context.WithCancelCause(context.Background())
	defer cancel(nil)
	go control.runCommands(ctx, cancel, func() helperStatus { return helperStatus{} }, nil)
	_, _ = peer.Write([]byte(`{"magic":"LEVIK_YANDEX_ANDROID","version":2,"type":"IMPORT_COOKIES","token":"private-secret"}` + "\n"))
	select {
	case <-ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("unrecognized command remained active")
	}
	if context.Cause(ctx).Error() != "bad_command" {
		t.Fatal("private data escaped through command error")
	}
}
