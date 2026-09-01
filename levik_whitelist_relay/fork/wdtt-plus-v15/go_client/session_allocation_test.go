package main

import (
	"context"
	"testing"
	"time"
)

func TestTURNAllocationDeadlineUsesBoundedTimeout(t *testing.T) {
	now := time.Unix(1_788_172_800, 0)
	if got, want := turnAllocationDeadline(context.Background(), now), now.Add(turnAllocationTimeout); !got.Equal(want) {
		t.Fatalf("turnAllocationDeadline() = %v, want %v", got, want)
	}
}

func TestTURNAllocationDeadlineHonorsEarlierContextDeadline(t *testing.T) {
	now := time.Unix(1_788_172_800, 0)
	contextDeadline := now.Add(3 * time.Second)
	ctx, cancel := context.WithDeadline(context.Background(), contextDeadline)
	defer cancel()

	if got := turnAllocationDeadline(ctx, now); !got.Equal(contextDeadline) {
		t.Fatalf("turnAllocationDeadline() = %v, want context deadline %v", got, contextDeadline)
	}
}
