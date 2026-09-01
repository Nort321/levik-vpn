// SPDX-License-Identifier: AGPL-3.0-only

package audit

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"sync"
	"time"
)

// Logger emits one bounded JSON record per management operation. Callers must
// never put credentials, HMAC material, request bodies, or raw subscriber and
// device identifiers into Event.
type Logger struct {
	mu  sync.Mutex
	out io.Writer
	now func() time.Time
}

type Event struct {
	Time       string `json:"time"`
	RequestID  string `json:"request_id"`
	KeyID      string `json:"key_id,omitempty"`
	Operation  string `json:"operation"`
	LeaseRef   string `json:"lease_ref,omitempty"`
	Revision   uint64 `json:"revision,omitempty"`
	Status     int    `json:"status"`
	Code       string `json:"code"`
	DurationMS int64  `json:"duration_ms"`
}

func New(out io.Writer) *Logger {
	return &Logger{out: out, now: time.Now}
}

func (l *Logger) Write(event Event) {
	if l == nil || l.out == nil {
		return
	}
	event.Time = l.now().UTC().Format(time.RFC3339Nano)
	l.mu.Lock()
	defer l.mu.Unlock()
	_ = json.NewEncoder(l.out).Encode(event)
}

// Ref turns an already opaque identifier into a short, non-reversible audit
// correlation value. It deliberately uses no secret material.
func Ref(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:8])
}
