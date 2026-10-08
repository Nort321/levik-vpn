package yandex

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net"
	"time"

	"github.com/gorilla/websocket"
)

var (
	ErrProviderAuthDenied       = errors.New("yandex: provider denied editor authorization")
	ErrProviderAdmissionTimeout = errors.New("yandex: provider editor authorization timed out")
	restrictedAuthUnlock        = []byte(`42["message",{"type":"unLockDocument","isSave":false,"unlock":true,"releaseLocks":false}]`)
)

// A waitAuth notification is sent to the joining editor; connectState with
// waitAuth true is sent to the existing admitted editor that owns the auth
// lock. Only the latter requests a no-save release. Never copy provider fields
// into the fixed command (in particular deleteIndex, URLs, or other locks).
func restrictedCoauthoringState(data []byte) (handled, unlock bool) {
	if !bytes.HasPrefix(data, []byte("42")) || !restrictedUniqueJSON(data[2:]) {
		return false, false
	}
	var event []json.RawMessage
	if json.Unmarshal(data[2:], &event) != nil || len(event) != 2 {
		return false, false
	}
	var name string
	if json.Unmarshal(event[0], &name) != nil || name != "message" {
		return false, false
	}
	var message map[string]json.RawMessage
	if json.Unmarshal(event[1], &message) != nil {
		return false, false
	}
	var kind string
	if json.Unmarshal(message["type"], &kind) != nil || kind != "connectState" {
		return false, false
	}
	var waitAuth bool
	return true, json.Unmarshal(message["waitAuth"], &waitAuth) == nil && waitAuth
}

func (s *DocSession) restrictedWrite(ctx context.Context, messageType int, data []byte) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	if err := ctx.Err(); err != nil {
		return err
	}
	deadline := time.Now().Add(docWriteTimeout)
	if attemptDeadline, ok := ctx.Deadline(); ok && attemptDeadline.Before(deadline) {
		deadline = attemptDeadline
	}
	if err := s.Conn.SetWriteDeadline(deadline); err != nil {
		return err
	}
	return s.Conn.WriteMessage(messageType, data)
}

// An Engine.IO upgrade and Socket.IO namespace acknowledgement do not mean
// the editor admitted this participant. Only the observed application auth
// result 1 permits traffic or a connected status in restricted mode.
func restrictedAdmissionFrame(data []byte) (handled, accepted bool, err error) {
	if bytes.HasPrefix(data, []byte("44")) {
		return true, false, ErrProviderAuthDenied
	}
	if !bytes.HasPrefix(data, []byte("42")) {
		return false, false, nil
	}
	var event []json.RawMessage
	if !restrictedUniqueJSON(data[2:]) || json.Unmarshal(data[2:], &event) != nil || len(event) != 2 {
		return false, false, nil
	}
	var name string
	if json.Unmarshal(event[0], &name) != nil || name != "message" {
		return false, false, nil
	}
	var message map[string]json.RawMessage
	if json.Unmarshal(event[1], &message) != nil {
		return false, false, nil
	}
	var kind string
	if json.Unmarshal(message["type"], &kind) != nil {
		return false, false, nil
	}
	if kind == "error" {
		return true, false, ErrProviderAuthDenied
	}
	if kind != "auth" {
		return false, false, nil
	}
	var result int
	if json.Unmarshal(message["result"], &result) != nil || result != 1 {
		return true, false, ErrProviderAuthDenied
	}
	return true, true, nil
}

func awaitRestrictedAdmission(ctx context.Context, session *DocSession, namespaceAuth, applicationAuth []byte) error {
	ctx, cancel := context.WithTimeout(ctx, restrictedAttemptTimeout)
	defer cancel()
	deadline, _ := ctx.Deadline()
	if session == nil || session.Conn == nil || session.Conn.SetReadDeadline(deadline) != nil {
		return ErrProviderResponse
	}
	session.Conn.SetReadLimit(restrictedWSLimit)
	done := make(chan struct{})
	watcherDone := make(chan struct{})
	// Join before the earlier deferred cancel runs. Otherwise both channels
	// may become ready together and the watcher can close an admitted socket.
	defer func() {
		close(done)
		<-watcherDone
	}()
	go func() {
		defer close(watcherDone)
		select {
		case <-ctx.Done():
			_ = session.Conn.Close()
		case <-done:
		}
	}()
	phase := 0 // Engine.IO open -> Socket.IO ACK -> editor auth result
	for {
		kind, data, err := session.Conn.ReadMessage()
		if err != nil {
			var timeout net.Error
			if ctx.Err() != nil || (errors.As(err, &timeout) && timeout.Timeout()) {
				return ErrProviderAdmissionTimeout
			}
			return ErrProviderResponse
		}
		if kind != websocket.TextMessage {
			continue
		}
		if bytes.Equal(data, []byte("2")) {
			if session.restrictedWrite(ctx, websocket.TextMessage, []byte("3")) != nil {
				return ErrProviderResponse
			}
			continue
		}
		_, accepted, err := restrictedAdmissionFrame(data)
		if err != nil {
			return err
		}
		switch phase {
		case 0:
			if restrictedEngineOpen(data) {
				if err := session.restrictedWrite(ctx, websocket.TextMessage, namespaceAuth); err != nil {
					return restrictedHandshakeWriteError(ctx, err)
				}
				phase = 1
			}
			continue
		case 1:
			if restrictedNamespaceAck(data) {
				if err := session.restrictedWrite(ctx, websocket.TextMessage, applicationAuth); err != nil {
					return restrictedHandshakeWriteError(ctx, err)
				}
				phase = 2
			}
			continue
		}
		if phase == 2 && accepted {
			if ctx.Err() != nil {
				return ErrProviderAdmissionTimeout
			}
			if session.Conn.SetReadDeadline(time.Time{}) != nil {
				return ErrProviderResponse
			}
			return nil
		}
	}
}

func restrictedEngineOpen(data []byte) bool {
	if !bytes.HasPrefix(data, []byte("0{")) || !restrictedUniqueJSON(data[1:]) {
		return false
	}
	var open map[string]json.RawMessage
	if json.Unmarshal(data[1:], &open) != nil {
		return false
	}
	var sid string
	return json.Unmarshal(open["sid"], &sid) == nil && sid != "" && len(sid) <= 512
}

func restrictedNamespaceAck(data []byte) bool {
	if bytes.Equal(data, []byte("40")) {
		return true
	}
	if !bytes.HasPrefix(data, []byte("40{")) || !restrictedUniqueJSON(data[2:]) {
		return false
	}
	var ack map[string]json.RawMessage
	return json.Unmarshal(data[2:], &ack) == nil && ack != nil
}

func restrictedHandshakeWriteError(ctx context.Context, err error) error {
	var timeout net.Error
	if ctx.Err() != nil || (errors.As(err, &timeout) && timeout.Timeout()) {
		return ErrProviderAdmissionTimeout
	}
	return ErrProviderResponse
}

func (t *YandexDocsTransport) restrictedAdmissionFailed(session *DocSession, err error) bool {
	session.admitted.Store(false)
	t.Mu.Lock()
	current := t.session == session
	if current {
		t.SetConnected(false)
	}
	t.Mu.Unlock()
	if session.Conn != nil {
		_ = session.Conn.Close()
	}
	retryFresh := current && t.staleGuestFailure(session.Info, err)
	if current && !retryFresh && t.IsRunning() && restrictedTerminalError(err) && t.errNotifier != nil {
		t.errNotifier(err, "yandex", "", "", "restricted_document")
	}
	return retryFresh
}

func (t *YandexDocsTransport) confirmRestrictedAdmission(session *DocSession) bool {
	t.Mu.Lock()
	defer t.Mu.Unlock()
	if !t.IsRunning() || t.session != session {
		return false
	}
	session.admitted.Store(true)
	t.SetConnected(true)
	return true
}
