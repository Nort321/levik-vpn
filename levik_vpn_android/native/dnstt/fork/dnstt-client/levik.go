package main

import (
	"encoding/binary"
	"errors"
	"net"
	"runtime"
	"sync"
	"time"

	"golang.org/x/sys/unix"
)

// The app receives the descriptor via SCM_RIGHTS, protects it and binds it to
// the chosen physical network before acknowledging. Never send DNS before ACK.
func protectUDP(conn *net.UDPConn, path string) error {
	if path == "" {
		if runtime.GOOS == "android" {
			return errors.New("dnstt_protect_required")
		}
		return nil
	} // Desktop probes do not have a VpnService.
	ipc, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return errors.New("dnstt_protect_connect")
	}
	defer ipc.Close()
	_ = ipc.SetDeadline(time.Now().Add(2 * time.Second))
	raw, err := conn.SyscallConn()
	if err != nil {
		return err
	}
	var sendErr error
	if err = raw.Control(func(fd uintptr) {
		_, _, sendErr = ipc.WriteMsgUnix([]byte{1}, unix.UnixRights(int(fd)), nil)
	}); err != nil {
		return err
	}
	if sendErr != nil {
		return errors.New("dnstt_protect_send")
	}
	var ack [1]byte
	if n, err := ipc.Read(ack[:]); err != nil || n != 1 || ack[0] != 1 {
		return errors.New("dnstt_protect_rejected")
	}
	return nil
}

// No token bucket bursts. Only replies from the configured resolver to a live
// transaction can influence pacing. Failure backs off; successful replies ramp
// up slowly. The configured ceiling is a pilot policy, not a resolver SLA.
type pacedDNSConn struct {
	net.PacketConn
	remote                        net.Addr
	ceiling                       int
	mu                            sync.Mutex
	writeMu                       sync.Mutex
	qps                           int
	next, lastReply, lastIncrease time.Time
	pending                       map[uint16]time.Time
	done                          chan struct{}
	once                          sync.Once
}

func newPacedDNSConn(conn net.PacketConn, remote net.Addr, qps int) *pacedDNSConn {
	now := time.Now()
	return &pacedDNSConn{PacketConn: conn, remote: remote, ceiling: qps, qps: min(20, qps),
		lastReply: now, lastIncrease: now, pending: make(map[uint16]time.Time), done: make(chan struct{})}
}
func (c *pacedDNSConn) Close() error {
	c.once.Do(func() { close(c.done) })
	return c.PacketConn.Close()
}
func (c *pacedDNSConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	if len(p) < 12 || addr.String() != c.remote.String() {
		return 0, errors.New("dnstt_invalid_query")
	}
	c.mu.Lock()
	now := time.Now()
	if now.Sub(c.lastReply) > 3*time.Second {
		c.qps = 4
	}
	wait := time.Until(c.next)
	c.mu.Unlock()
	if wait > 0 {
		timer := time.NewTimer(wait)
		defer timer.Stop()
		select {
		case <-timer.C:
		case <-c.done:
			return 0, net.ErrClosed
		}
	}
	c.mu.Lock()
	now = time.Now()
	c.next = now.Add(time.Second / time.Duration(c.qps))
	for id, sent := range c.pending {
		if now.Sub(sent) > 5*time.Second {
			delete(c.pending, id)
		}
	}
	c.pending[binary.BigEndian.Uint16(p[:2])] = now
	c.mu.Unlock()
	return c.PacketConn.WriteTo(p, addr)
}
func (c *pacedDNSConn) ReadFrom(p []byte) (int, net.Addr, error) {
	for {
		n, addr, err := c.PacketConn.ReadFrom(p)
		if err != nil {
			return n, addr, err
		}
		if n < 12 || addr.String() != c.remote.String() || p[2]&0x80 == 0 {
			continue
		}
		id := binary.BigEndian.Uint16(p[:2])
		now := time.Now()
		c.mu.Lock()
		sent, ok := c.pending[id]
		delete(c.pending, id)
		if !ok || now.Sub(sent) > 5*time.Second {
			c.mu.Unlock()
			continue
		}
		c.lastReply = now
		if p[3]&15 != 0 || p[2]&2 != 0 { // RCODE / truncated reply: back off, never amplify retries.
			c.qps = max(4, c.qps/2)
			c.lastIncrease = now
		} else if now.Sub(c.lastIncrease) > time.Second {
			c.qps = min(c.ceiling, c.qps+2)
			c.lastIncrease = now
		}
		c.mu.Unlock()
		return n, addr, nil
	}
}
