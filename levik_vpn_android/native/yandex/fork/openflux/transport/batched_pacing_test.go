package transport

import (
	"testing"
	"time"
)

// A paced carrier sends the first packet at once, then never faster than its
// interval, and carries everything that arrived meanwhile in the next batch.
func TestBatchedTransportPacingSpacesBatchesWithoutLosingPackets(t *testing.T) {
	inner := &fakeTransport{}
	bt := NewBatchedTransport(inner)
	bt.SetPacing(100*time.Millisecond, 256*1024)
	if err := bt.Start(); err != nil {
		t.Fatalf("start: %v", err)
	}
	defer bt.Stop()

	if err := bt.Send([]byte{1}); err != nil {
		t.Fatalf("send: %v", err)
	}
	time.Sleep(30 * time.Millisecond)
	if c := inner.sendCount(); c != 1 {
		t.Fatalf("idle link must send the first packet at once, got %d messages", c)
	}

	const total = 200
	start := time.Now()
	for i := 0; i < total; i++ {
		if err := bt.Send(make([]byte, 1200)); err != nil {
			t.Fatalf("send %d: %v", i, err)
		}
		time.Sleep(2 * time.Millisecond)
	}
	time.Sleep(250 * time.Millisecond)
	elapsed := time.Since(start)

	inner.mu.Lock()
	sent := append([][]byte(nil), inner.sent...)
	inner.mu.Unlock()
	if limit := int(elapsed/(100*time.Millisecond)) + 2; len(sent) > limit {
		t.Fatalf("%d messages in %v exceed the pace (limit %d)", len(sent), elapsed, limit)
	}
	packets := 0
	for _, m := range sent {
		pkts, err := decodeBatch(m)
		if err != nil {
			t.Fatalf("decode: %v", err)
		}
		packets += len(pkts)
	}
	if packets != total+1 {
		t.Fatalf("delivered %d packets, want %d", packets, total+1)
	}
}
