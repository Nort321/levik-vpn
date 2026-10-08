package yandex

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/p1neappleXpress/OpenFlux/transport"
)

func restrictedTestAdmission(ctx context.Context, session *DocSession) error {
	return awaitRestrictedAdmission(ctx, session, []byte(`40{"token":"guest-token"}`), []byte(`42["message",{"type":"auth","jwtOpen":"guest-token"}]`))
}

func restrictedAdmissionSockets(t *testing.T) (*websocket.Conn, *websocket.Conn) {
	t.Helper()
	serverConn := make(chan *websocket.Conn, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := (&websocket.Upgrader{}).Upgrade(w, r, nil)
		if err == nil {
			serverConn <- conn
		}
	}))
	t.Cleanup(server.Close)
	client, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	peer := <-serverConn
	t.Cleanup(func() { _ = peer.Close(); _ = client.Close() })
	return client, peer
}

func TestRestrictedAdmissionRejectsProviderDenial(t *testing.T) {
	for _, frame := range []string{
		`44{"message":"credential-private-detail"}`,
		`44/editor,{"message":"denied"}`,
		`42["message",{"type":"auth","result":0}]`,
		`42["message",{"type":"auth","result":-1}]`,
		`42["message",{"type":"auth"}]`,
		`42["message",{"type":"auth","result":"1"}]`,
		`42["message",{"type":"error","message":"credential-private-detail"}]`,
	} {
		client, peer := restrictedAdmissionSockets(t)
		if err := peer.WriteMessage(websocket.TextMessage, []byte(frame)); err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		err := restrictedTestAdmission(ctx, &DocSession{Conn: client})
		cancel()
		if !errors.Is(err, ErrProviderAuthDenied) || strings.Contains(err.Error(), "private-detail") {
			t.Fatal("provider denial did not produce a fixed authorization error")
		}
	}
}

func TestRestrictedAdmissionTimeoutClosesAttemptWithoutRevokingCredential(t *testing.T) {
	client, _ := restrictedAdmissionSockets(t)
	tr, err := NewRestrictedYandexDocsTransport("https://disk.yandex.ru/i/test", transport.DefaultConfig())
	if err != nil || tr.BaseTransport.Start() != nil {
		t.Fatal("test transport could not start")
	}
	t.Cleanup(func() { _ = tr.Stop() })
	session := &DocSession{Conn: client, WriteQueue: make(chan []byte, 1)}
	tr.session = session
	if !tr.confirmRestrictedAdmission(session) {
		t.Fatal("test admission failed")
	}
	called := false
	tr.SetErrorNotifier(func(error, string, string, string, string) { called = true })
	tr.restrictedAdmissionFailed(session, ErrProviderAdmissionTimeout)
	if called || restrictedTerminalError(ErrProviderAdmissionTimeout) {
		t.Fatal("temporary admission timeout revoked a still-valid credential")
	}
	if tr.IsConnected() || session.admitted.Load() || tr.Send([]byte("packet")) == nil {
		t.Fatal("timed-out attempt retained authorization for traffic")
	}
	if !restrictedTerminalError(ErrProviderAuthDenied) || !restrictedTerminalError(ErrGuestBootstrapExpired) {
		t.Fatal("denial or credential expiry became retryable")
	}
}

func TestRestrictedAdmissionRequiresApplicationSuccess(t *testing.T) {
	client, peer := restrictedAdmissionSockets(t)
	tr, err := NewRestrictedYandexDocsTransport("https://disk.yandex.ru/i/test", transport.DefaultConfig())
	if err != nil || tr.BaseTransport.Start() != nil {
		t.Fatal("test transport could not start")
	}
	t.Cleanup(func() { _ = tr.Stop() })
	session := &DocSession{Conn: client, WriteQueue: make(chan []byte, 1)}
	tr.session = session
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	result := make(chan error, 1)
	go func() { result <- restrictedTestAdmission(ctx, session) }()
	for _, frame := range []string{`0{"sid":"guest"}`, `40{"sid":"namespace"}`, `42["message",{"type":"cursor","cursor":"18;cGFja2V0"}]`, `2`} {
		if err := peer.WriteMessage(websocket.TextMessage, []byte(frame)); err != nil {
			t.Fatal(err)
		}
	}
	_ = peer.SetReadDeadline(time.Now().Add(time.Second))
	for _, prefix := range []string{"40", "42"} {
		_, frame, err := peer.ReadMessage()
		if err != nil || !strings.HasPrefix(string(frame), prefix) {
			t.Fatal("pending authorization protocol phase was not sent")
		}
	}
	_, pong, err := peer.ReadMessage()
	if err != nil || string(pong) != "3" {
		t.Fatal("pending provider authorization did not answer Engine.IO ping")
	}
	if tr.IsConnected() || session.admitted.Load() || tr.Send([]byte("packet")) == nil {
		t.Fatal("WebSocket upgrade or namespace ACK admitted transport traffic")
	}
	if err := peer.WriteMessage(websocket.TextMessage, []byte(`42["message",{"type":"auth","result":1}]`)); err != nil {
		t.Fatal(err)
	}
	if err := <-result; err != nil || !tr.confirmRestrictedAdmission(session) || !tr.IsConnected() {
		t.Fatalf("provider auth success was not admitted: %v", err)
	}
	if err := tr.Send([]byte("packet")); err != nil {
		t.Fatal("admitted transport rejected traffic")
	}
	// The attempt deadline must not close or poison an established session.
	<-ctx.Done()
	if err := peer.WriteMessage(websocket.TextMessage, []byte("2")); err != nil {
		t.Fatal(err)
	}
	_ = client.SetReadDeadline(time.Now().Add(time.Second))
	if _, frame, err := client.ReadMessage(); err != nil || string(frame) != "2" {
		t.Fatal("admission deadline remained active after auth success")
	}
}

func TestRestrictedAdmissionSendsEachAuthOnlyAfterItsProtocolPhase(t *testing.T) {
	client, peer := restrictedAdmissionSockets(t)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	result := make(chan error, 1)
	go func() { result <- restrictedTestAdmission(ctx, &DocSession{Conn: client}) }()
	frames := make(chan []byte, 8)
	go func() {
		for {
			_, frame, err := peer.ReadMessage()
			if err != nil {
				return
			}
			frames <- frame
		}
	}()
	noAuth := func() {
		t.Helper()
		select {
		case <-frames:
			t.Fatal("authentication was sent before its required protocol acknowledgement")
		case <-result:
			t.Fatal("early acknowledgement admitted or terminated the handshake")
		case <-time.After(20 * time.Millisecond):
		}
	}
	send := func(frame string) {
		t.Helper()
		if peer.WriteMessage(websocket.TextMessage, []byte(frame)) != nil {
			t.Fatal("test peer write failed")
		}
	}
	expect := func(want string) {
		t.Helper()
		select {
		case frame := <-frames:
			if string(frame) != want {
				t.Fatal("incorrect outgoing protocol phase")
			}
		case <-time.After(time.Second):
			t.Fatal("required outgoing protocol phase was not sent")
		}
	}
	noAuth()
	send(`40{"sid":"early-namespace"}`)
	send(`42["message",{"type":"auth","result":1}]`)
	noAuth()
	send(`0{"sid":"engine","pingInterval":25000,"pingTimeout":20000}`)
	expect(`40{"token":"guest-token"}`)
	noAuth()
	send(`0{"sid":"duplicate-engine"}`)
	send(`42["message",{"type":"auth","result":1}]`)
	noAuth()
	send(`2`)
	expect(`3`)
	send(`40{"sid":"namespace"}`)
	expect(`42["message",{"type":"auth","jwtOpen":"guest-token"}]`)
	send(`42["message",{"type":"license"}]`)
	noAuth()
	send(`42["message",{"type":"auth","result":1}]`)
	if err := <-result; err != nil {
		t.Fatalf("strict phased handshake failed: %v", err)
	}
}

func TestRestrictedAdmissionBoundsMissingAckAndCancellation(t *testing.T) {
	for _, cancelImmediately := range []bool{false, true} {
		client, peer := restrictedAdmissionSockets(t)
		_ = peer.WriteMessage(websocket.TextMessage, []byte(`40{"sid":"namespace-only"}`))
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Millisecond)
		if cancelImmediately {
			cancel()
		}
		started := time.Now()
		err := restrictedTestAdmission(ctx, &DocSession{Conn: client})
		cancel()
		if !errors.Is(err, ErrProviderAdmissionTimeout) || time.Since(started) > time.Second {
			t.Fatal("missing provider auth was not bounded by caller context")
		}
	}
}

func TestRestrictedAdmissionSuccessKeepsSocketOpenAfterAttemptCancellation(t *testing.T) {
	for iteration := 0; iteration < 24; iteration++ {
		client, peer := restrictedAdmissionSockets(t)
		for _, frame := range []string{`0{"sid":"guest"}`, `40{"sid":"namespace"}`, `42["message",{"type":"auth","result":1}]`} {
			if peer.WriteMessage(websocket.TextMessage, []byte(frame)) != nil {
				t.Fatal("test peer could not send admission")
			}
		}
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		if err := restrictedTestAdmission(ctx, &DocSession{Conn: client}); err != nil {
			cancel()
			t.Fatal(err)
		}
		cancel()
		if peer.WriteMessage(websocket.TextMessage, []byte("post-admission")) != nil {
			t.Fatal("admitted provider connection was closed")
		}
		_ = client.SetReadDeadline(time.Now().Add(time.Second))
		if _, frame, err := client.ReadMessage(); err != nil || string(frame) != "post-admission" {
			t.Fatal("attempt cancellation closed an admitted connection")
		}
		_ = peer.Close()
		_ = client.Close()
	}
}

func TestRestrictedAdmissionFailureClearsStateAndRedactsNotifier(t *testing.T) {
	tr, _ := NewRestrictedYandexDocsTransport("https://disk.yandex.ru/i/test", transport.DefaultConfig())
	_ = tr.BaseTransport.Start()
	t.Cleanup(func() { _ = tr.Stop() })
	session := &DocSession{}
	tr.session = session
	if !tr.confirmRestrictedAdmission(session) {
		t.Fatal("test admission failed")
	}
	called := false
	tr.SetErrorNotifier(func(err error, name, rawURL, html, reason string) {
		called = true
		if !errors.Is(err, ErrProviderAuthDenied) || name != "yandex" || rawURL != "" || html != "" || reason != "restricted_document" {
			t.Fatal("provider rejection leaked untrusted details")
		}
	})
	err := tr.handleMessage(session, []byte(`44{"message":"private-credential"}`))
	if !errors.Is(err, ErrProviderAuthDenied) {
		t.Fatal("provider rejected an admitted participant without terminating")
	}
	tr.restrictedAdmissionFailed(session, err)
	if !called || tr.IsConnected() || session.admitted.Load() {
		t.Fatal("provider rejection retained ready state")
	}
}

func TestRestrictedAdmissionDoesNotDeliverPreAuthPayloads(t *testing.T) {
	tr, _ := NewRestrictedYandexDocsTransport("https://disk.yandex.ru/i/test", transport.DefaultConfig())
	received := 0
	tr.Receive(func([]byte) { received++ })
	frame := []byte(`42["message",{"type":"cursor","cursor":"18;cGFja2V0"}]`)
	if tr.handleMessage(&DocSession{}, frame) != nil || received != 0 {
		t.Fatal("pre-auth traffic reached the tunnel")
	}
	admitted := &DocSession{}
	admitted.admitted.Store(true)
	if tr.handleMessage(admitted, []byte(`42["message",{"type":"auth","result":1,"cursor":"18;cGFja2V0"}]`)) != nil || received != 0 {
		t.Fatal("provider auth metadata was mistaken for tunnel traffic")
	}
	upstream := NewYandexDocsTransport("https://upstream.example", transport.DefaultConfig())
	upstream.Receive(func([]byte) { received++ })
	if upstream.handleMessage(&DocSession{}, []byte(`44{"message":"upstream-compatible"}`)) != nil || upstream.handleMessage(&DocSession{}, frame) != nil || received != 1 {
		t.Fatal("unrestricted upstream handler behavior changed")
	}
}

func TestRestrictedCoauthoringStateRequiresExactBooleanControl(t *testing.T) {
	for _, tc := range []struct {
		frame   string
		handled bool
		unlock  bool
	}{
		{`42["message",{"type":"connectState","waitAuth":true}]`, true, true},
		{`42["message",{"type":"connectState","waitAuth":false}]`, true, false},
		{`42["message",{"type":"connectState","waitAuth":"true"}]`, true, false},
		{`42["message",{"type":"connectState","waitAuth":1}]`, true, false},
		{`42["message",{"type":"connectState","waitAuth":null}]`, true, false},
		{`42["message",{"type":"connectState"}]`, true, false},
		{`42["message",{"type":"connectState","state":{"waitAuth":true}}]`, true, false},
		{`42["message",{"type":"connectState","waitauth":true}]`, true, false},
		{`42["message",{"Type":"connectState","waitAuth":true}]`, false, false},
		{`42["message",{"type":"waitAuth","waitAuth":true}]`, false, false},
		{`42["other",{"type":"connectState","waitAuth":true}]`, false, false},
		{`42/editor,["message",{"type":"connectState","waitAuth":true}]`, false, false},
		{`42["message",{"type":"connectState","waitAuth":false,"waitAuth":true}]`, false, false},
		{`42["message",{"type":"cursor","type":"connectState","waitAuth":true}]`, false, false},
		{`42["message",{"type":"connectState","waitAuth":true},{}]`, false, false},
		{`42["message",{"type":"connectState","waitAuth":true}`, false, false},
	} {
		if handled, unlock := restrictedCoauthoringState([]byte(tc.frame)); handled != tc.handled || unlock != tc.unlock {
			t.Fatal("coauthoring control did not require an exact, unique boolean field")
		}
	}
}

func TestRestrictedAdmittedOwnerUnlocksWithoutDocumentChanges(t *testing.T) {
	client, peer := restrictedAdmissionSockets(t)
	tr, _ := NewRestrictedYandexDocsTransport("https://disk.yandex.ru/i/test", transport.DefaultConfig())
	received := 0
	tr.Receive(func([]byte) { received++ })
	session := &DocSession{Conn: client}
	frames := make(chan []byte, 4)
	go func() {
		for {
			_, frame, err := peer.ReadMessage()
			if err != nil {
				return
			}
			frames <- frame
		}
	}()
	noCommand := func() {
		t.Helper()
		select {
		case <-frames:
			t.Fatal("a pending, unrelated, or unrestricted participant released an auth lock")
		case <-time.After(20 * time.Millisecond):
		}
	}
	// The owner notification may contain arbitrary other fields. None can be
	// reflected into the command or interpreted as carrier payload.
	control := []byte(`42["message",{"type":"connectState","waitAuth":true,"deleteIndex":0,"isSave":true,"releaseLocks":true,"cursor":"18;cGFja2V0","url":"https://private.invalid/secret"}]`)
	if tr.handleMessage(session, control) != nil {
		t.Fatal("pending owner control returned an error")
	}
	noCommand()
	session.admitted.Store(true)
	if err := tr.handleMessage(session, control); err != nil {
		t.Fatal("admitted owner could not release its auth lock")
	}
	select {
	case frame := <-frames:
		if string(frame) != string(restrictedAuthUnlock) || received != 0 {
			t.Fatal("auth unlock reflected provider fields or delivered control as traffic")
		}
		var event []json.RawMessage
		var command map[string]json.RawMessage
		if json.Unmarshal(frame[2:], &event) != nil || json.Unmarshal(event[1], &command) != nil || command["deleteIndex"] != nil || len(command) != 4 {
			t.Fatal("auth unlock could delete changes or execute an additional command")
		}
	case <-time.After(time.Second):
		t.Fatal("admitted owner did not release its auth lock")
	}
	for _, frame := range []string{
		`42["message",{"type":"waitAuth","waitAuth":true}]`,
		`42["message",{"type":"connectState","waitAuth":false}]`,
		`42["message",{"type":"connectState","waitAuth":"true"}]`,
	} {
		if tr.handleMessage(session, []byte(frame)) != nil {
			t.Fatal("unrelated provider control returned an error")
		}
		noCommand()
	}
	upstream := NewYandexDocsTransport("https://upstream.example", transport.DefaultConfig())
	if upstream.handleMessage(session, []byte(`42["message",{"type":"connectState","waitAuth":true}]`)) != nil {
		t.Fatal("upstream control handling changed")
	}
	noCommand()
}

func TestRestrictedEditorActivityNeverStartsSyntheticChanges(t *testing.T) {
	tr, _ := NewRestrictedYandexDocsTransport("https://disk.yandex.ru/i/test", transport.DefaultConfig())
	if tr.BaseTransport.Start() != nil {
		t.Fatal("test transport could not start")
	}
	t.Cleanup(func() { _ = tr.Stop() })
	done := make(chan struct{})
	go func() {
		tr.editorActivityLoop()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(100 * time.Millisecond):
		t.Fatal("restricted synthetic document activity loop remained active")
	}
}

func TestRestrictedJoiningPeerWaitsForAdmittedOwnerUnlock(t *testing.T) {
	ownerClient, ownerPeer := restrictedAdmissionSockets(t)
	joiningClient, joiningPeer := restrictedAdmissionSockets(t)
	owner, _ := NewRestrictedYandexDocsTransport("https://disk.yandex.ru/i/test", transport.DefaultConfig())
	ownerSession := &DocSession{Conn: ownerClient}
	ownerSession.admitted.Store(true)
	joiningSession := &DocSession{Conn: joiningClient}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	result := make(chan error, 1)
	go func() { result <- restrictedTestAdmission(ctx, joiningSession) }()
	for _, frame := range []string{
		`0{"sid":"engine"}`,
		`40{"sid":"namespace"}`,
		`42["message",{"type":"waitAuth","lockDocument":{"id":"existing-owner"}}]`,
		`42["message",{"type":"documentOpen"}]`,
	} {
		if joiningPeer.WriteMessage(websocket.TextMessage, []byte(frame)) != nil {
			t.Fatal("joining peer could not receive provider state")
		}
	}
	_ = joiningPeer.SetReadDeadline(time.Now().Add(time.Second))
	for _, prefix := range []string{"40", "42"} {
		_, frame, err := joiningPeer.ReadMessage()
		if err != nil || !strings.HasPrefix(string(frame), prefix) {
			t.Fatal("joining peer did not send phased authentication")
		}
	}
	select {
	case <-result:
		t.Fatal("waitAuth or documentOpen admitted a joining participant")
	case <-time.After(20 * time.Millisecond):
	}
	if err := owner.handleMessage(ownerSession, []byte(`42["message",{"type":"connectState","waitAuth":true}]`)); err != nil {
		t.Fatal("owner could not release the temporary authentication lock")
	}
	_ = ownerPeer.SetReadDeadline(time.Now().Add(time.Second))
	if _, frame, err := ownerPeer.ReadMessage(); err != nil || string(frame) != string(restrictedAuthUnlock) {
		t.Fatal("owner did not send the fixed no-save unlock")
	}
	if joiningPeer.WriteMessage(websocket.TextMessage, []byte(`42["message",{"type":"auth","result":1}]`)) != nil {
		t.Fatal("provider could not admit the joining peer")
	}
	if err := <-result; err != nil {
		t.Fatal("joining peer did not finish admission after owner unlock")
	}
}
