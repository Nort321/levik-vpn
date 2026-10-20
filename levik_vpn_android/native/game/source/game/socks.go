package game

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"sync"
	"time"

	"levik.local/levik-game/wire"
)

// SocksUser is the user of every Game SOCKS inbound the app adds to Xray (GAME_PROBE_USER on Windows).
const SocksUser = "levik-game"

// Transport is one authenticated datagram path to the relay behind a route.
type Transport interface {
	Send(frame []byte) error
	Close()
	Closed() bool
}

// Dialer opens a path through the route's inbound; receive gets each relay datagram.
type Dialer func(port int, password string, receive func([]byte)) (Transport, error)

var errSocks = errors.New("game path unavailable")

// socksPath is SOCKS5 UDP ASSOCIATE through an Xray inbound to the relay at 127.0.0.1:37079
// behind the game server (the same path as openRelayPath in the Windows client).
type socksPath struct {
	tcp    net.Conn
	udp    *net.UDPConn
	prefix [10]byte
	once   sync.Once
	mu     sync.Mutex
	closed bool
}

// DialSocks is the production Dialer.
func DialSocks(port int, password string, receive func([]byte)) (Transport, error) {
	if len(password) > 255 {
		return nil, errSocks
	}
	tcp, e := net.DialTimeout("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), 3*time.Second)
	if e != nil {
		return nil, errSocks
	}
	fail := func() (Transport, error) { _ = tcp.Close(); return nil, errSocks }
	_ = tcp.SetDeadline(time.Now().Add(3 * time.Second))
	if _, e := tcp.Write([]byte{5, 1, 2}); e != nil {
		return fail()
	}
	reply := make([]byte, 10)
	if _, e := io.ReadFull(tcp, reply[:2]); e != nil || reply[0] != 5 || reply[1] != 2 {
		return fail()
	}
	auth := append([]byte{1, byte(len(SocksUser))}, SocksUser...)
	auth = append(append(auth, byte(len(password))), password...)
	if _, e := tcp.Write(auth); e != nil {
		return fail()
	}
	if _, e := io.ReadFull(tcp, reply[:2]); e != nil || reply[0] != 1 || reply[1] != 0 {
		return fail()
	}
	if _, e := tcp.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}); e != nil {
		return fail()
	}
	if _, e := io.ReadFull(tcp, reply); e != nil || reply[0] != 5 || reply[1] != 0 || reply[3] != 1 {
		return fail()
	}
	relay := net.IP(reply[4:8])
	relayPort := binary.BigEndian.Uint16(reply[8:10])
	if relayPort == 0 || !(relay.Equal(net.IPv4zero) || relay.Equal(net.IPv4(127, 0, 0, 1))) {
		return fail()
	}
	udp, e := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: int(relayPort)})
	if e != nil {
		return fail()
	}
	_ = tcp.SetDeadline(time.Time{})
	p := &socksPath{tcp: tcp, udp: udp, prefix: [10]byte{0, 0, 0, 1, 127, 0, 0, 1, wire.DefaultPort >> 8, wire.DefaultPort & 0xff}}
	// The association lives as long as its TCP control connection.
	go func() { _, _ = io.Copy(io.Discard, tcp); p.Close() }()
	go func() {
		buffer := make([]byte, 65536)
		for {
			n, e := udp.Read(buffer)
			if e != nil {
				p.Close()
				return
			}
			if n >= 10 && bytes.Equal(buffer[:10], p.prefix[:]) {
				receive(append([]byte(nil), buffer[10:n]...))
			}
		}
	}()
	return p, nil
}

func (p *socksPath) Send(frame []byte) error {
	if p.Closed() {
		return errSocks
	}
	out := make([]byte, 0, len(p.prefix)+len(frame))
	_, e := p.udp.Write(append(append(out, p.prefix[:]...), frame...))
	return e
}

func (p *socksPath) Close() {
	p.once.Do(func() {
		p.mu.Lock()
		p.closed = true
		p.mu.Unlock()
		_ = p.tcp.Close()
		_ = p.udp.Close()
	})
}

func (p *socksPath) Closed() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.closed
}
