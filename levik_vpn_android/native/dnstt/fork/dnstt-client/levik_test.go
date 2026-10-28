package main

import (
	"encoding/binary"
	"net"
	"testing"
	"time"
)

type testDNSConn struct {
	net.PacketConn
	sent []time.Time
}

func (c *testDNSConn) WriteTo(p []byte, _ net.Addr) (int, error) {
	c.sent = append(c.sent, time.Now())
	return len(p), nil
}
func (c *testDNSConn) Close() error { return nil }

func TestPacingNoBurst(t *testing.T) {
	transport := &testDNSConn{}
	addr := &net.UDPAddr{IP: net.IPv4(77, 88, 8, 8), Port: 53}
	conn := newPacedDNSConn(transport, addr, 40)
	packet := make([]byte, 12)
	for i := 0; i < 4; i++ {
		binary.BigEndian.PutUint16(packet, uint16(i))
		if _, err := conn.WriteTo(packet, addr); err != nil {
			t.Fatal(err)
		}
	}
	for i := 1; i < len(transport.sent); i++ {
		if transport.sent[i].Sub(transport.sent[i-1]) < 45*time.Millisecond {
			t.Fatal("query burst")
		}
	}
	if len(conn.pending) != 4 {
		t.Fatal("transaction tracking missing")
	}
}

func TestNoReplyBackoffAndClose(t *testing.T) {
	transport := &testDNSConn{}
	addr := &net.UDPAddr{IP: net.IPv4(77, 88, 8, 8), Port: 53}
	conn := newPacedDNSConn(transport, addr, 40)
	conn.lastReply = time.Now().Add(-4 * time.Second)
	if _, err := conn.WriteTo(make([]byte, 12), addr); err != nil {
		t.Fatal(err)
	}
	if conn.qps != 4 {
		t.Fatal("did not back off")
	}
	conn.next = time.Now().Add(time.Second)
	done := make(chan error, 1)
	go func() { _, err := conn.WriteTo(make([]byte, 12), addr); done <- err }()
	conn.Close()
	select {
	case err := <-done:
		if err != net.ErrClosed {
			t.Fatalf("expected closed, got %v", err)
		}
	case <-time.After(200 * time.Millisecond):
		t.Fatal("stop waited for pacing timer")
	}
}
