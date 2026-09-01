// SPDX-License-Identifier: AGPL-3.0-only

package authn

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	HeaderKeyID     = "X-Levik-Key-Id"
	HeaderTimestamp = "X-Levik-Timestamp"
	HeaderNonce     = "X-Levik-Nonce"
	HeaderSignature = "X-Levik-Signature"
)

var (
	keyIDPattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)
	noncePattern = regexp.MustCompile(`^[A-Za-z0-9_-]{22,128}$`)
)

type contextKey uint8

const (
	bodyContextKey contextKey = iota
	keyIDContextKey
)

var (
	ErrMissing      = errors.New("missing authentication headers")
	ErrUnknownKey   = errors.New("unknown authentication key")
	ErrClockSkew    = errors.New("request timestamp outside allowed window")
	ErrBadNonce     = errors.New("invalid nonce")
	ErrBadSignature = errors.New("invalid signature")
	ErrReplay       = errors.New("replayed nonce")
	ErrBodyTooLarge = errors.New("request body too large")
	ErrRateLimited  = errors.New("request rate exceeded")
	ErrInvalidKey   = errors.New("invalid HMAC key")
)

type replayEntry struct {
	expires time.Time
}

type ReplayCache struct {
	mu      sync.Mutex
	entries map[string]replayEntry
	ttl     time.Duration
	max     int
}

func NewReplayCache(ttl time.Duration, max int) *ReplayCache {
	return &ReplayCache{entries: make(map[string]replayEntry), ttl: ttl, max: max}
}

func (c *ReplayCache) Use(keyID, nonce string, now time.Time) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	for key, entry := range c.entries {
		if !entry.expires.After(now) {
			delete(c.entries, key)
		}
	}
	key := keyID + "\x00" + nonce
	if _, exists := c.entries[key]; exists {
		return false
	}
	if len(c.entries) >= c.max {
		return false
	}
	c.entries[key] = replayEntry{expires: now.Add(c.ttl)}
	return true
}

type bucket struct {
	tokens float64
	last   time.Time
}

type Limiter struct {
	mu    sync.Mutex
	rate  float64
	burst float64
	byKey map[string]bucket
}

func NewLimiter(rate float64, burst int) *Limiter {
	return &Limiter{rate: rate, burst: float64(burst), byKey: make(map[string]bucket)}
}

func (l *Limiter) Allow(key string, now time.Time) bool {
	if l == nil || l.rate <= 0 || l.burst <= 0 {
		return false
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	b := l.byKey[key]
	if b.last.IsZero() {
		b.tokens = l.burst
		b.last = now
	} else {
		b.tokens += now.Sub(b.last).Seconds() * l.rate
		if b.tokens > l.burst {
			b.tokens = l.burst
		}
		b.last = now
	}
	if b.tokens < 1 {
		l.byKey[key] = b
		return false
	}
	b.tokens--
	l.byKey[key] = b
	return true
}

type Verifier struct {
	keys    map[string][]byte
	maxSkew time.Duration
	maxBody int64
	replay  *ReplayCache
	limiter *Limiter
	now     func() time.Time
}

func NewVerifier(keys map[string][]byte, maxSkew time.Duration, maxBody int64, replay *ReplayCache, limiter *Limiter) (*Verifier, error) {
	copyKeys := make(map[string][]byte, len(keys))
	for id, key := range keys {
		if !keyIDPattern.MatchString(id) || len(key) < 32 {
			return nil, fmt.Errorf("%w: %q", ErrInvalidKey, id)
		}
		copyKeys[id] = append([]byte(nil), key...)
	}
	if len(copyKeys) == 0 || maxSkew <= 0 || maxBody <= 0 || replay == nil || limiter == nil {
		return nil, ErrInvalidKey
	}
	return &Verifier{keys: copyKeys, maxSkew: maxSkew, maxBody: maxBody, replay: replay, limiter: limiter, now: time.Now}, nil
}

func Canonical(method, requestURI, timestamp, nonce string, body []byte) []byte {
	sum := sha256.Sum256(body)
	return []byte(strings.Join([]string{
		"levik-hmac-v1",
		timestamp,
		nonce,
		strings.ToUpper(method),
		requestURI,
		hex.EncodeToString(sum[:]),
	}, "\n"))
}

func Sign(key []byte, method, requestURI, timestamp, nonce string, body []byte) string {
	mac := hmac.New(sha256.New, key)
	_, _ = mac.Write(Canonical(method, requestURI, timestamp, nonce, body))
	return hex.EncodeToString(mac.Sum(nil))
}

func (v *Verifier) Verify(r *http.Request) ([]byte, string, error) {
	keyID := strings.TrimSpace(r.Header.Get(HeaderKeyID))
	timestampText := strings.TrimSpace(r.Header.Get(HeaderTimestamp))
	nonce := strings.TrimSpace(r.Header.Get(HeaderNonce))
	signatureText := strings.TrimSpace(r.Header.Get(HeaderSignature))
	if keyID == "" || timestampText == "" || nonce == "" || signatureText == "" {
		return nil, "", ErrMissing
	}
	key, exists := v.keys[keyID]
	if !exists {
		return nil, "", ErrUnknownKey
	}
	if !noncePattern.MatchString(nonce) {
		return nil, "", ErrBadNonce
	}
	timestamp, err := strconv.ParseInt(timestampText, 10, 64)
	if err != nil {
		return nil, "", ErrClockSkew
	}
	now := v.now().UTC()
	requestTime := time.Unix(timestamp, 0)
	delta := now.Sub(requestTime)
	if delta < -v.maxSkew || delta > v.maxSkew {
		return nil, "", ErrClockSkew
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, v.maxBody+1))
	if err != nil {
		return nil, "", err
	}
	if int64(len(body)) > v.maxBody {
		return nil, "", ErrBodyTooLarge
	}
	provided, err := hex.DecodeString(signatureText)
	if err != nil || len(provided) != sha256.Size {
		return nil, "", ErrBadSignature
	}
	mac := hmac.New(sha256.New, key)
	_, _ = mac.Write(Canonical(r.Method, r.URL.RequestURI(), timestampText, nonce, body))
	if !hmac.Equal(mac.Sum(nil), provided) {
		return nil, "", ErrBadSignature
	}
	// Per-key limiting happens only after authentication. Key IDs are public;
	// charging unauthenticated traffic to their buckets would let an attacker
	// starve legitimate control-plane calls.
	if !v.limiter.Allow(keyID, now) {
		return nil, "", ErrRateLimited
	}
	if !v.replay.Use(keyID, nonce, now) {
		return nil, "", ErrReplay
	}
	return body, keyID, nil
}

func Body(ctx context.Context) []byte {
	body, _ := ctx.Value(bodyContextKey).([]byte)
	return body
}

func KeyID(ctx context.Context) string {
	keyID, _ := ctx.Value(keyIDContextKey).(string)
	return keyID
}

func (v *Verifier) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, keyID, err := v.Verify(r)
		if err != nil {
			status := http.StatusUnauthorized
			code := "unauthorized"
			if errors.Is(err, ErrBodyTooLarge) {
				status, code = http.StatusRequestEntityTooLarge, "body_too_large"
			} else if errors.Is(err, ErrRateLimited) {
				status, code = http.StatusTooManyRequests, "rate_limited"
			}
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "no-store")
			w.WriteHeader(status)
			_, _ = io.WriteString(w, "{\"error\":\""+code+"\"}\n")
			return
		}
		ctx := context.WithValue(r.Context(), bodyContextKey, body)
		ctx = context.WithValue(ctx, keyIDContextKey, keyID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}
