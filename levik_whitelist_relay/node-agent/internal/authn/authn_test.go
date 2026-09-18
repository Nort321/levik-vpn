// SPDX-License-Identifier: AGPL-3.0-only

package authn

import (
	"bytes"
	"errors"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"
)

func signedRequest(t *testing.T, key []byte, timestamp, nonce string, body []byte) *http.Request {
	t.Helper()
	request, err := http.NewRequest(http.MethodPost, "http://node/v1/leases/apply", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set(HeaderKeyID, "test-v1")
	request.Header.Set(HeaderTimestamp, timestamp)
	request.Header.Set(HeaderNonce, nonce)
	request.Header.Set(HeaderSignature, Sign(key, request.Method, request.URL.RequestURI(), timestamp, nonce, body))
	return request
}

func TestVerifierAcceptsSignatureAndRejectsReplay(t *testing.T) {
	key := bytes.Repeat([]byte{0x42}, 32)
	now := time.Unix(1788172800, 0)
	verifier, err := NewVerifier(map[string][]byte{"test-v1": key}, 2*time.Minute, 4096, NewReplayCache(5*time.Minute, 100), NewLimiter(10, 10))
	if err != nil {
		t.Fatal(err)
	}
	verifier.now = func() time.Time { return now }
	body := []byte(`{"revision":1}`)
	request := signedRequest(t, key, "1788172800", "MDEyMzQ1Njc4OWFiY2RlZg", body)
	if _, keyID, err := verifier.Verify(request); err != nil || keyID != "test-v1" {
		t.Fatalf("verify failed: key=%q err=%v", keyID, err)
	}
	replay := signedRequest(t, key, "1788172800", "MDEyMzQ1Njc4OWFiY2RlZg", body)
	if _, _, err := verifier.Verify(replay); !errors.Is(err, ErrReplay) {
		t.Fatalf("expected replay rejection, got %v", err)
	}
}

func TestVerifierClockSkewBoundaryAndRecovery(t *testing.T) {
	key := bytes.Repeat([]byte{0x42}, 32)
	now := time.Unix(1788172800, 0)
	verifier, err := NewVerifier(map[string][]byte{"test-v1": key}, 2*time.Minute, 4096, NewReplayCache(5*time.Minute, 100), NewLimiter(10, 10))
	if err != nil {
		t.Fatal(err)
	}
	verifier.now = func() time.Time { return now }
	body := []byte(`{"revision":1}`)

	for _, test := range []struct {
		name      string
		timestamp time.Time
		nonce     string
		wantError bool
	}{
		{name: "past boundary", timestamp: now.Add(-2 * time.Minute), nonce: "cGFzdC1ib3VuZGFyeS0wMDE", wantError: false},
		{name: "past outside", timestamp: now.Add(-2*time.Minute - time.Second), nonce: "cGFzdC1vdXRzaWRlLTAwMQ", wantError: true},
		{name: "future boundary", timestamp: now.Add(2 * time.Minute), nonce: "ZnV0dXJlLWJvdW5kYXJ5LTE", wantError: false},
		{name: "future outside", timestamp: now.Add(2*time.Minute + time.Second), nonce: "ZnV0dXJlLW91dHNpZGUtMDE", wantError: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			timestamp := strconv.FormatInt(test.timestamp.Unix(), 10)
			request := signedRequest(t, key, timestamp, test.nonce, body)
			_, _, verifyErr := verifier.Verify(request)
			if test.wantError && !errors.Is(verifyErr, ErrClockSkew) {
				t.Fatalf("expected clock skew, got %v", verifyErr)
			}
			if !test.wantError && verifyErr != nil {
				t.Fatalf("boundary request rejected: %v", verifyErr)
			}
		})
	}

	staleTimestamp := strconv.FormatInt(now.Add(-10*time.Minute).Unix(), 10)
	stale := signedRequest(t, key, staleTimestamp, "c3RhbGUtYmVmb3JlLWZpeDA", body)
	if _, _, err := verifier.Verify(stale); !errors.Is(err, ErrClockSkew) {
		t.Fatalf("expected stale request rejection, got %v", err)
	}
	freshTimestamp := strconv.FormatInt(now.Unix(), 10)
	fresh := signedRequest(t, key, freshTimestamp, "ZnJlc2gtYWZ0ZXItZml4MDA", body)
	if _, _, err := verifier.Verify(fresh); err != nil {
		t.Fatalf("fresh request after correction rejected: %v", err)
	}
}

func TestMiddlewareKeepsAuthenticationReasonPrivate(t *testing.T) {
	key := bytes.Repeat([]byte{0x42}, 32)
	now := time.Unix(1788172800, 0)
	verifier, err := NewVerifier(map[string][]byte{"test-v1": key}, 2*time.Minute, 4096, NewReplayCache(5*time.Minute, 100), NewLimiter(10, 10))
	if err != nil {
		t.Fatal(err)
	}
	verifier.now = func() time.Time { return now }
	timestamp := strconv.FormatInt(now.Add(-10*time.Minute).Unix(), 10)
	request := signedRequest(t, key, timestamp, "c3RhbGUtZ2VuZXJpYy00MDE", []byte(`{}`))
	recorder := httptest.NewRecorder()
	verifier.Middleware(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		t.Fatal("protected handler was called")
	})).ServeHTTP(recorder, request)
	if recorder.Code != http.StatusUnauthorized || recorder.Body.String() != "{\"error\":\"unauthorized\"}\n" {
		t.Fatalf("authentication detail leaked: status=%d body=%q", recorder.Code, recorder.Body.String())
	}
}

func TestVerifierRejectsReplayWindowShorterThanTimestampLifetime(t *testing.T) {
	key := bytes.Repeat([]byte{0x42}, 32)
	if _, err := NewVerifier(map[string][]byte{"test-v1": key}, 2*time.Minute, 4096, NewReplayCache(4*time.Minute-time.Nanosecond, 100), NewLimiter(10, 10)); !errors.Is(err, ErrInvalidKey) {
		t.Fatalf("unsafe replay window accepted: %v", err)
	}
	if _, err := NewVerifier(map[string][]byte{"test-v1": key}, 2*time.Minute, 4096, NewReplayCache(4*time.Minute, 100), NewLimiter(10, 10)); err != nil {
		t.Fatalf("exact safe replay window rejected: %v", err)
	}
}

func TestReplayCacheRetainsNonceAtInclusiveTimestampBoundary(t *testing.T) {
	key := bytes.Repeat([]byte{0x42}, 32)
	timestamp := time.Unix(1788172800, 0)
	firstSeen := timestamp.Add(-2 * time.Minute)
	verifier, err := NewVerifier(map[string][]byte{"test-v1": key}, 2*time.Minute, 4096, NewReplayCache(4*time.Minute, 100), NewLimiter(10, 10))
	if err != nil {
		t.Fatal(err)
	}
	now := firstSeen
	verifier.now = func() time.Time { return now }
	timestampText := strconv.FormatInt(timestamp.Unix(), 10)
	nonce := "aW5jbHVzaXZlLWJvdW5kYXJ5"
	body := []byte(`{"revision":1}`)
	if _, _, err := verifier.Verify(signedRequest(t, key, timestampText, nonce, body)); err != nil {
		t.Fatalf("first boundary request rejected: %v", err)
	}
	now = timestamp.Add(2 * time.Minute)
	if _, _, err := verifier.Verify(signedRequest(t, key, timestampText, nonce, body)); !errors.Is(err, ErrReplay) {
		t.Fatalf("nonce replayed at inclusive boundary: %v", err)
	}
}

func TestInvalidSignatureDoesNotConsumeAuthenticatedBucket(t *testing.T) {
	key := bytes.Repeat([]byte{0x24}, 32)
	now := time.Unix(1788172800, 0)
	verifier, err := NewVerifier(map[string][]byte{"test-v1": key}, time.Minute, 4096, NewReplayCache(5*time.Minute, 100), NewLimiter(0.0001, 1))
	if err != nil {
		t.Fatal(err)
	}
	verifier.now = func() time.Time { return now }
	bad := signedRequest(t, key, "1788172800", "YWJjZGVmZ2hpamtsbW5vcA", []byte(`{}`))
	bad.Header.Set(HeaderSignature, string(bytes.Repeat([]byte{'0'}, 64)))
	if _, _, err := verifier.Verify(bad); !errors.Is(err, ErrBadSignature) {
		t.Fatalf("expected bad signature, got %v", err)
	}
	good := signedRequest(t, key, "1788172800", "cXJzdHV2d3h5ejAxMjM0NQ", []byte(`{}`))
	if _, _, err := verifier.Verify(good); err != nil {
		t.Fatalf("valid request was starved by invalid traffic: %v", err)
	}
}

func TestSharedBridgeGoldenVector(t *testing.T) {
	body := []byte(`{"deviceIdHash":"beae0469261d83fc1ad6b28d5f5a8990b79384c2ce7c3db0e4d87296d3f47f4c","expiresAt":1790086400,"idempotencyKey":"423e4567-e89b-42d3-a456-426614174000","revision":7,"subscriptionIdHash":"229c6cc56ae91bb2e8c21b7abdd63bb48f01b4da129660ef5a7be9d0df11476c"}`)
	got := Sign([]byte("rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr"), "POST", "/internal/v1/leases/apply", "1790000000", "AAAAAAAAAAAAAAAAAAAAAA", body)
	const want = "35962d9f3a232cd11918e86013d34b865b0d3939b28b87e602d9dff32261fa56"
	if got != want {
		t.Fatalf("golden signature mismatch: got %s want %s", got, want)
	}
}
