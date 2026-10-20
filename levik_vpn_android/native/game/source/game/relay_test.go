package game

import (
	"bufio"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"levik.local/levik-game/wire"
)

// Cross-implementation checks against the production relay, built by levik_game_node
// `make cross-test` (go build -tags relaytest ./cmd/levik-game-relay).
func relayBinary(t *testing.T) string {
	binary := os.Getenv("LEVIK_TEST_GO_RELAY")
	if binary == "" {
		binary = "../../../../../levik_game_node/dist/levik-game-relay-test"
	}
	if _, e := os.Stat(binary); e != nil {
		t.Skip("no relay test binary (levik_game_node: make cross-test)")
	}
	return binary
}

func freeUDPPort(t *testing.T) int {
	socket, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	defer socket.Close()
	return socket.LocalAddr().(*net.UDPAddr).Port
}

type meshPeer struct {
	NodeID    string `json:"nodeId"`
	Address   string `json:"address"`
	Port      int    `json:"port"`
	PublicKey string `json:"publicKey"`
}

type meshSpec struct {
	port  int
	key   string
	peers []meshPeer
}

func startRelay(t *testing.T, directory, nodeID string, mesh *meshSpec) int {
	port, _ := startRelayOn(t, directory, nodeID, mesh, freeUDPPort(t))
	return port
}

func startRelayOn(t *testing.T, directory, nodeID string, mesh *meshSpec, port int) (int, func()) {
	config := map[string]any{"nodeId": nodeID, "port": port}
	env := append(os.Environ(), "LEVIK_GAME_RELAY_TEST_LOOPBACK=1")
	if mesh != nil {
		config["mesh"] = map[string]any{"address": "127.0.0.1", "port": mesh.port, "transport": "aead", "peers": mesh.peers}
		keyFile := filepath.Join(directory, nodeID+".key")
		if e := os.WriteFile(keyFile, []byte(mesh.key+"\n"), 0o600); e != nil {
			t.Fatal(e)
		}
		env = append(env, "LEVIK_GAME_MESH_KEY_FILE="+keyFile)
	}
	raw, _ := json.Marshal(config)
	file := filepath.Join(directory, nodeID+".json")
	if e := os.WriteFile(file, raw, 0o600); e != nil {
		t.Fatal(e)
	}
	command := exec.Command(relayBinary(t), "-config", file)
	command.Env = env
	command.Stderr = io.Discard
	stdout, _ := command.StdoutPipe()
	if e := command.Start(); e != nil {
		t.Fatal(e)
	}
	var once sync.Once
	kill := func() { once.Do(func() { _ = command.Process.Kill(); _ = command.Wait() }) }
	t.Cleanup(kill)
	ready := make(chan struct{})
	go func() {
		_, _ = bufio.NewReader(stdout).ReadString('\n')
		close(ready)
		_, _ = io.Copy(io.Discard, stdout)
	}()
	select {
	case <-ready:
	case <-time.After(5 * time.Second):
		t.Fatal("relay did not start")
	}
	return port, kill
}

func meshKey(t *testing.T) (private, public string) {
	key, e := ecdh.X25519().GenerateKey(rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	return base64.StdEncoding.EncodeToString(key.Bytes()), base64.StdEncoding.EncodeToString(key.PublicKey().Bytes())
}

// network stands in for Xray's SOCKS inbounds: route port -> relay port, with a drop hook.
type network struct {
	mu     sync.Mutex
	relays map[int]int
	drop   func(direction string, route int, data []byte) bool
	magics sync.Map
}

type fakePath struct {
	socket *net.UDPConn
	closed atomic.Bool
}

func (p *fakePath) Send(frame []byte) error {
	if p.closed.Load() {
		return errors.New("closed")
	}
	_, e := p.socket.Write(frame)
	return e
}
func (p *fakePath) Close()       { p.closed.Store(true); _ = p.socket.Close() }
func (p *fakePath) Closed() bool { return p.closed.Load() }

func (n *network) dropping(direction string, route int, data []byte) bool {
	n.mu.Lock()
	drop := n.drop
	n.mu.Unlock()
	return drop != nil && drop(direction, route, data)
}

func (n *network) setDrop(drop func(direction string, route int, data []byte) bool) {
	n.mu.Lock()
	n.drop = drop
	n.mu.Unlock()
}

func (n *network) dial(port int, _ string, receive func([]byte)) (Transport, error) {
	n.mu.Lock()
	relay := n.relays[port]
	n.mu.Unlock()
	socket, e := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: relay})
	if e != nil {
		return nil, e
	}
	p := &fakePath{socket: socket}
	go func() {
		buffer := make([]byte, 65536)
		for {
			size, e := socket.Read(buffer)
			if e != nil {
				return
			}
			if !p.closed.Load() && !n.dropping("down", port, buffer[:size]) {
				receive(append([]byte(nil), buffer[:size]...))
			}
		}
	}()
	return &droppingPath{fakePath: p, network: n, route: port}, nil
}

type droppingPath struct {
	*fakePath
	network *network
	route   int
}

func (p *droppingPath) Send(frame []byte) error {
	p.network.magics.Store(string(frame[:4]), true)
	if p.network.dropping("up", p.route, frame) {
		return nil
	}
	return p.fakePath.Send(frame)
}

type echoServer struct {
	socket  *net.UDPConn
	mu      sync.Mutex
	got     []string
	sources map[string]bool
}

func startEcho(t *testing.T) *echoServer {
	socket, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	s := &echoServer{socket: socket, sources: map[string]bool{}}
	t.Cleanup(func() { _ = socket.Close() })
	go func() {
		buffer := make([]byte, 2048)
		for {
			size, peer, e := socket.ReadFromUDP(buffer)
			if e != nil {
				return
			}
			s.mu.Lock()
			s.got = append(s.got, string(buffer[:size]))
			s.sources[peer.String()] = true
			s.mu.Unlock()
			_, _ = socket.WriteToUDP(append([]byte("echo:"), buffer[:size]...), peer)
		}
	}()
	return s
}

func (s *echoServer) port() uint16 { return uint16(s.socket.LocalAddr().(*net.UDPAddr).Port) }
func (s *echoServer) count() int   { s.mu.Lock(); defer s.mu.Unlock(); return len(s.got) }
func (s *echoServer) sourceCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.sources)
}

// gameSocket is Xray's Game outbound: one VLESS connection carrying XUDP.
type gameSocket struct {
	socket  net.Conn
	mu      sync.Mutex
	replies []string
}

const testUUID = "0f5d3a8e-3c1b-4d8e-9a6b-2f4c1e7d9b0a"

func openGame(t *testing.T, port int) *gameSocket {
	socket, e := net.Dial("tcp4", net.JoinHostPort("127.0.0.1", itoaTest(port)))
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { _ = socket.Close() })
	header := make([]byte, 19)
	identity := strings.ReplaceAll(testUUID, "-", "")
	for i := 0; i < 16; i++ {
		header[1+i] = hexByte(identity[2*i : 2*i+2])
	}
	header[18] = 3
	if _, e := socket.Write(header); e != nil {
		t.Fatal(e)
	}
	response := make([]byte, 2)
	if _, e := io.ReadFull(socket, response); e != nil || response[0] != 0 || response[1] != 0 {
		t.Fatal("no VLESS response")
	}
	g := &gameSocket{socket: socket}
	go func() {
		head := make([]byte, 16)
		for {
			if _, e := io.ReadFull(socket, head); e != nil {
				return
			}
			body := make([]byte, binary.BigEndian.Uint16(head[14:16]))
			if _, e := io.ReadFull(socket, body); e != nil {
				return
			}
			g.mu.Lock()
			g.replies = append(g.replies, string(body))
			g.mu.Unlock()
		}
	}()
	return g
}

func (g *gameSocket) send(port uint16, payload string) {
	frame := make([]byte, 16+len(payload))
	binary.BigEndian.PutUint16(frame[0:2], 12)
	frame[4], frame[5], frame[6] = 1, 1, 2
	binary.BigEndian.PutUint16(frame[7:9], port)
	frame[9] = 1
	copy(frame[10:14], []byte{127, 0, 0, 1})
	binary.BigEndian.PutUint16(frame[14:16], uint16(len(payload)))
	copy(frame[16:], payload)
	_, _ = g.socket.Write(frame)
}

func (g *gameSocket) has(reply string) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	for _, value := range g.replies {
		if value == reply {
			return true
		}
	}
	return false
}

func waitFor(t *testing.T, what string, timeout time.Duration, check func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for !check() {
		if time.Now().After(deadline) {
			t.Fatal("timed out: " + what)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func testClient(t *testing.T, n *network, routes []RouteConfig, nodes []NodeConfig, preferred, token string) (*Client, int) {
	raw, _ := json.Marshal(map[string]any{"version": 1, "listen": map[string]any{"port": 0, "uuid": testUUID}, "password": "test-password-0123456789",
		"routes": routes, "nodes": nodes, "preferredExitId": preferred, "relayToken": token})
	cfg, e := ParseConfig(raw)
	if e != nil {
		t.Fatal(e)
	}
	client := New(cfg, n.dial, func(line string) { t.Log(line) })
	client.validTarget = func([4]byte) bool { return true }
	port, e := client.Listen(0)
	if e != nil {
		t.Fatal(e)
	}
	go client.Run()
	t.Cleanup(client.Stop)
	return client, port
}

func TestPlaysThroughTheRelayOverTwoCopies(t *testing.T) {
	n := &network{relays: map[int]int{}}
	relay := startRelay(t, t.TempDir(), "exit-a", nil)
	n.relays[2001], n.relays[2002] = relay, relay
	client, port := testClient(t, n, []RouteConfig{{Index: 0, Port: 2001, NodeID: "exit-a"}, {Index: 1, Port: 2002, NodeID: "exit-a"}},
		[]NodeConfig{{NodeID: "exit-a", Name: "Exit A", State: "enabled"}}, "", "")
	waitFor(t, "two copies", 5*time.Second, func() bool { return client.Status().State == "protected" })
	first, second := startEcho(t), startEcho(t)
	game := openGame(t, port)
	for i := 0; i < 4; i++ {
		game.send(first.port(), "a-"+itoaTest(i))
		game.send(second.port(), "b-"+itoaTest(i))
	}
	waitFor(t, "echoes", 5*time.Second, func() bool { return game.has("echo:a-3") && game.has("echo:b-3") })
	// One exit port for all destinations of the socket; duplicates never reach the game twice.
	if first.sourceCount() != 1 || second.sourceCount() != 1 {
		t.Fatal("game socket used several exit ports")
	}
	if first.count() != 4 || second.count() != 4 {
		t.Fatalf("relay forwarded %d/%d datagrams, want 4/4", first.count(), second.count())
	}
	if _, ok := n.magics.Load("LGM3"); !ok {
		t.Fatal("data did not use LGM3")
	}
	// Losing one copy upwards loses nothing.
	n.setDrop(func(direction string, route int, data []byte) bool {
		packet, ok := wire.Decode(data, client.resolve)
		return direction == "up" && route == 2001 && ok && packet.Kind == wire.KindUp
	})
	game.send(first.port(), "a-lost-copy")
	waitFor(t, "second copy", 3*time.Second, func() bool { return game.has("echo:a-lost-copy") })
	if status := client.Status(); status.Duplicates == 0 || status.ExitNodeID != "exit-a" {
		t.Fatalf("status %+v", status)
	}
}

func TestPlaysThroughTheMeshWhenTheExitsOwnPathDies(t *testing.T) {
	directory := t.TempDir()
	aPrivate, aPublic := meshKey(t)
	bPrivate, bPublic := meshKey(t)
	aMesh, bMesh := freeUDPPort(t), freeUDPPort(t)
	n := &network{relays: map[int]int{}}
	n.relays[3001] = startRelay(t, directory, "entry-a", &meshSpec{port: aMesh, key: aPrivate,
		peers: []meshPeer{{NodeID: "exit-b", Address: "127.0.0.1", Port: bMesh, PublicKey: bPublic}}})
	n.relays[3002] = startRelay(t, directory, "exit-b", &meshSpec{port: bMesh, key: bPrivate,
		peers: []meshPeer{{NodeID: "entry-a", Address: "127.0.0.1", Port: aMesh, PublicKey: aPublic}}})
	client, port := testClient(t, n, []RouteConfig{{Index: 0, Port: 3001, NodeID: "entry-a"}, {Index: 1, Port: 3002, NodeID: "exit-b"}},
		[]NodeConfig{{NodeID: "entry-a", Name: "A", State: "enabled"}, {NodeID: "exit-b", Name: "B", State: "enabled"}}, "exit-b", "")
	carrying := func() bool {
		active := map[string]bool{}
		for _, p := range client.Status().Paths {
			if p.ExitNodeID == "exit-b" && p.Active && p.Responding {
				active[p.EntryNodeID] = true
			}
		}
		return active["entry-a"] && active["exit-b"]
	}
	waitFor(t, "a copy through entry A's mesh", 10*time.Second, carrying)
	server := startEcho(t)
	game := openGame(t, port)
	game.send(server.port(), "direct")
	waitFor(t, "echo", 3*time.Second, func() bool { return game.has("echo:direct") })
	n.setDrop(func(_ string, route int, _ []byte) bool { return route == 3002 })
	for i := 0; i < 4; i++ {
		game.send(server.port(), "mesh-"+itoaTest(i))
	}
	waitFor(t, "echoes through the mesh", 5*time.Second, func() bool { return game.has("echo:mesh-3") && game.has("echo:mesh-0") })
	if server.sourceCount() != 1 {
		t.Fatal("the game server saw the entry node")
	}
}

func TestHandsTheTokenToARelayThatRequiresOne(t *testing.T) {
	directory := t.TempDir()
	seed := make([]byte, 32)
	for i := range seed {
		seed[i] = byte(i + 1)
	}
	key := ed25519.NewKeyFromSeed(seed)
	auth, _ := json.Marshal(map[string]any{"version": 1, "mode": "enforce", "requireToken": true,
		"publicKeys": []string{base64.StdEncoding.EncodeToString(key.Public().(ed25519.PublicKey))}})
	if e := os.WriteFile(filepath.Join(directory, "relay-auth.json"), auth, 0o600); e != nil {
		t.Fatal(e)
	}
	signed := make([]byte, 30)
	copy(signed, "LGT1")
	signed[4] = 2
	now := uint32(time.Now().Unix())
	binary.BigEndian.PutUint32(signed[6:10], now)
	binary.BigEndian.PutUint32(signed[10:14], now+3600)
	for i := 14; i < 30; i++ {
		signed[i] = 0xa5
	}
	token := base64.RawURLEncoding.EncodeToString(append(signed, ed25519.Sign(key, signed)...))
	n := &network{relays: map[int]int{4001: startRelay(t, directory, "exit-a", nil)}}
	client, port := testClient(t, n, []RouteConfig{{Index: 0, Port: 4001, NodeID: "exit-a"}}, nil, "", token)
	waitFor(t, "token accepted", 5*time.Second, func() bool { return client.Status().Auth == "ok" })
	server := startEcho(t)
	game := openGame(t, port)
	game.send(server.port(), "with-token")
	waitFor(t, "echo", 3*time.Second, func() bool { return game.has("echo:with-token") })
}

func TestReopensTheSocketsOfARestartedExit(t *testing.T) {
	directory := t.TempDir()
	port, kill := startRelayOn(t, directory, "exit-a", nil, freeUDPPort(t))
	n := &network{relays: map[int]int{5001: port}}
	client, listen := testClient(t, n, []RouteConfig{{Index: 0, Port: 5001, NodeID: "exit-a"}}, nil, "", "")
	waitFor(t, "path", 5*time.Second, func() bool { return client.Status().State != "unavailable" })
	server := startEcho(t)
	game := openGame(t, listen)
	game.send(server.port(), "before")
	waitFor(t, "echo", 3*time.Second, func() bool { return game.has("echo:before") })
	kill()
	startRelayOn(t, directory, "exit-a", nil, port)
	// The new process answers pings with a new instance; the socket gets a new relay flow.
	for i := 0; i < 100 && !game.has("echo:after"); i++ {
		game.send(server.port(), "after")
		time.Sleep(100 * time.Millisecond)
	}
	if !game.has("echo:after") {
		t.Fatal("no echo after the exit restarted")
	}
}

func itoaTest(value int) string {
	raw, _ := json.Marshal(value)
	return string(raw)
}

func hexByte(pair string) byte {
	value := byte(0)
	for _, r := range pair {
		value <<= 4
		switch {
		case r >= '0' && r <= '9':
			value |= byte(r - '0')
		default:
			value |= byte(r-'a') + 10
		}
	}
	return value
}
