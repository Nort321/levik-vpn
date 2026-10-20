package game

import (
	"bytes"
	"encoding/binary"
	"encoding/hex"
	"io"
	"net"
	"strings"
	"sync"
	"time"

	"levik.local/levik-game/wire"
)

const (
	maxConnections    = 128
	maxFlowsPerConn   = 256
	maxFlows          = 1024
	connectionIdle    = 120 * time.Second
	maxConnectionBuf  = 128 * 1024
	writeQueuePackets = 256
	// A light socket (a ping: at most lightPackets exchanged) silent this long gives its
	// place to a new one when the bridge is full; anything else only after evictHeavyIdle.
	evictLightIdle = 2 * time.Second
	evictHeavyIdle = 30 * time.Second
	lightPackets   = 16
)

type endpoint struct {
	address [4]byte
	port    uint16
}

// conn is one VLESS connection from Xray's Game outbound: one game socket.
type conn struct {
	c       net.Conn
	xudp    bool
	out     chan []byte
	once    sync.Once
	closed  bool
	flows   map[endpoint]*flow
	wires   map[string]*wireFlow
	exit    string
	touched time.Time
}

func (cn *conn) close() {
	cn.once.Do(func() {
		cn.closed = true
		close(cn.out)
		_ = cn.c.Close()
	})
}

// Listen accepts Xray's VLESS connections on 127.0.0.1:port (0 picks one).
func (c *Client) Listen(port int) (int, error) {
	l, e := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port})
	if e != nil {
		return 0, e
	}
	c.mu.Lock()
	c.listener = l
	c.mu.Unlock()
	identity, _ := hex.DecodeString(strings.ReplaceAll(c.cfg.Listen.UUID, "-", ""))
	go func() {
		for {
			socket, e := l.AcceptTCP()
			if e != nil {
				return
			}
			_ = socket.SetNoDelay(true)
			go c.serve(socket, identity)
		}
	}()
	return l.Addr().(*net.TCPAddr).Port, nil
}

func (c *Client) serve(socket net.Conn, identity []byte) {
	c.mu.Lock()
	if c.stopped || (len(c.conns) >= maxConnections && !c.evictConnection()) {
		c.mu.Unlock()
		_ = socket.Close()
		return
	}
	cn := &conn{c: socket, out: make(chan []byte, writeQueuePackets), flows: map[endpoint]*flow{}, wires: map[string]*wireFlow{}, touched: c.now()}
	c.conns[cn] = struct{}{}
	c.mu.Unlock()
	go func() {
		for frame := range cn.out {
			_ = socket.SetWriteDeadline(time.Now().Add(5 * time.Second))
			if _, e := socket.Write(frame); e != nil {
				_ = socket.Close()
				for range cn.out {
				}
				return
			}
		}
	}()
	defer func() {
		c.mu.Lock()
		c.dropConnection(cn)
		c.mu.Unlock()
	}()
	read := func(buffer []byte) bool {
		_ = socket.SetReadDeadline(time.Now().Add(connectionIdle))
		_, e := io.ReadFull(socket, buffer)
		return e == nil
	}
	header := make([]byte, 19)
	if !read(header) || header[0] != 0 || !bytes.Equal(header[1:17], identity) || header[17] != 0 {
		return
	}
	var target endpoint
	hasTarget := false
	switch header[18] {
	case 3:
		// Xray's default cone mode uses XUDP for game ports even with mux disabled.
		cn.xudp = true
	case 2:
		address := make([]byte, 7)
		if !read(address) || address[2] != 1 || binary.BigEndian.Uint16(address[:2]) == 0 {
			return
		}
		target.port = binary.BigEndian.Uint16(address[:2])
		copy(target.address[:], address[3:7])
		hasTarget = true
	default:
		return
	}
	if !c.enqueue(cn, []byte{0, 0}) {
		return
	}
	length := make([]byte, 2)
	meta := make([]byte, 64)
	payload := make([]byte, wire.MaxPayload)
	for {
		if !read(length) {
			return
		}
		size := int(binary.BigEndian.Uint16(length))
		if cn.xudp {
			// This local outbound emits one XUDP session (id zero) per VLESS connection.
			if size < 4 || size > len(meta) || !read(meta[:size]) {
				return
			}
			m := meta[:size]
			if binary.BigEndian.Uint16(m[:2]) != 0 || (m[2] != 1 && m[2] != 2) || m[3] != 1 {
				return
			}
			if size > 4 {
				if size < 12 || m[4] != 2 || m[7] != 1 || binary.BigEndian.Uint16(m[5:7]) == 0 {
					return
				}
				target.port = binary.BigEndian.Uint16(m[5:7])
				copy(target.address[:], m[8:12])
				hasTarget = true
			}
			if !read(length) {
				return
			}
			size = int(binary.BigEndian.Uint16(length))
		}
		if size > wire.MaxPayload || !hasTarget || !read(payload[:size]) {
			return
		}
		c.mu.Lock()
		c.forward(cn, target, payload[:size])
		c.mu.Unlock()
	}
}

// enqueue hands a frame to the connection's writer; a game that stops reading loses its socket.
func (c *Client) enqueue(cn *conn, frame []byte) bool {
	if cn.closed {
		return false
	}
	select {
	case cn.out <- frame:
		return true
	default:
		cn.close()
		return false
	}
}

// deliver writes one reply as XUDP (with its source, for voice and P2P peers) or plain UDP.
func (c *Client) deliver(f *flow, payload []byte) {
	cn := f.conn
	header := 2
	if cn.xudp {
		header = 16
	}
	frame := make([]byte, header+len(payload))
	if cn.xudp {
		binary.BigEndian.PutUint16(frame[0:2], 12)
		frame[4], frame[5], frame[6] = 2, 1, 2
		binary.BigEndian.PutUint16(frame[7:9], f.target.port)
		frame[9] = 1
		copy(frame[10:14], f.target.address[:])
	}
	binary.BigEndian.PutUint16(frame[header-2:header], uint16(len(payload)))
	copy(frame[header:], payload)
	c.enqueue(cn, frame)
}

// evictConnection: the longest-silent light socket goes first, then any long-silent one.
func (c *Client) evictConnection() bool {
	now := c.now()
	var light, heavy *conn
	var lightAt, heavyAt time.Time
	for cn := range c.conns {
		touched, packets, playing := cn.touched, uint64(0), false
		for _, f := range cn.flows {
			if f.touched.After(touched) {
				touched = f.touched
			}
			packets += f.sent + f.received
			playing = playing || f.busy && now.Sub(f.touched) < 5*time.Second
		}
		if playing {
			continue
		}
		idle := now.Sub(touched)
		if idle >= evictLightIdle && packets <= lightPackets && (light == nil || touched.Before(lightAt)) {
			light, lightAt = cn, touched
		}
		if idle >= evictHeavyIdle && (heavy == nil || touched.Before(heavyAt)) {
			heavy, heavyAt = cn, touched
		}
	}
	victim := light
	if victim == nil {
		victim = heavy
	}
	if victim == nil {
		return false
	}
	c.dropConnection(victim)
	return true
}

func (c *Client) dropConnection(cn *conn) {
	if _, ok := c.conns[cn]; !ok {
		return
	}
	delete(c.conns, cn)
	for _, f := range cn.flows {
		c.dropFlow(f)
	}
	cn.close()
}

// admitFlow makes room inside the bridge by the same rule: ping-like flows first.
func (c *Client) admitFlow(cn *conn) bool {
	evict := func(flows func(yield func(*flow) bool)) bool {
		now := c.now()
		var light, heavy *flow
		for f := range flows {
			if f.busy {
				continue
			}
			if f.sent+f.received <= lightPackets && (light == nil || f.touched.Before(light.touched)) {
				light = f
			}
			if now.Sub(f.touched) >= evictHeavyIdle && (heavy == nil || f.touched.Before(heavy.touched)) {
				heavy = f
			}
		}
		if light == nil {
			light = heavy
		}
		if light == nil {
			return false
		}
		c.dropFlow(light)
		return true
	}
	if len(cn.flows) >= maxFlowsPerConn && !evict(func(yield func(*flow) bool) {
		for _, f := range cn.flows {
			if !yield(f) {
				return
			}
		}
	}) {
		return false
	}
	if c.flowCount >= maxFlows && !evict(func(yield func(*flow) bool) {
		for other := range c.conns {
			for _, f := range other.flows {
				if !yield(f) {
					return
				}
			}
		}
	}) {
		return false
	}
	return true
}
