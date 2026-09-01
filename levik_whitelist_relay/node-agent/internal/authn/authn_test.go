// SPDX-License-Identifier: AGPL-3.0-only

package authn

import (
	"bytes"
	"errors"
	"net/http"
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
