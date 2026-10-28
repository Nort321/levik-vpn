package main

import (
	"net"
	"time"
)

// Global pilot ceiling, shared across recursive resolver addresses. A per-IP
// limit would unfairly combine many legitimate users behind a recursive DNS.
// Keep the kernel receive queue draining but never allocate tunnel state for
// an unbounded packet flood. Host CPU/memory are also bounded by systemd.
type limitedDNSConn struct {
	net.PacketConn
	start time.Time
	count int
}

func (c *limitedDNSConn) ReadFrom(p []byte) (int, net.Addr, error) {
	for {
		n, addr, err := c.PacketConn.ReadFrom(p)
		if err != nil {
			return n, addr, err
		}
		now := time.Now()
		if now.Sub(c.start) >= time.Second {
			c.start = now
			c.count = 0
		}
		c.count++
		if c.count <= 600 {
			return n, addr, nil
		}
	}
}
