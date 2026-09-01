// SPDX-License-Identifier: AGPL-3.0-only

package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/audit"
	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/authn"
	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/lease"
)

type fakeLeaseService struct{}

func (fakeLeaseService) Apply(_ context.Context, request lease.Request) (lease.Result, error) {
	return lease.Result{
		LeaseRef:   strings.Repeat("a", 43),
		State:      "active",
		ExpiresAt:  request.ExpiresAt,
		Revision:   request.Revision,
		Created:    true,
		Credential: &lease.Credential{Password: "AbcdEFGH2345jkmN"},
	}, nil
}
func (fakeLeaseService) Rotate(context.Context, lease.Request) (lease.Result, error) {
	return lease.Result{}, nil
}
func (fakeLeaseService) Revoke(context.Context, lease.Request) (lease.Result, error) {
	return lease.Result{}, nil
}
func (fakeLeaseService) Status(context.Context, lease.Request) (lease.Result, error) {
	return lease.Result{}, nil
}
func (fakeLeaseService) Ready(context.Context) error { return nil }

func TestApplyContractAuthenticatesExactRawBodyAndPath(t *testing.T) {
	key := []byte(strings.Repeat("r", 32))
	verifier, err := authn.NewVerifier(
		map[string][]byte{"bridge-v1": key},
		2*time.Minute,
		16<<10,
		authn.NewReplayCache(5*time.Minute, 100),
		authn.NewLimiter(100, 100),
	)
	if err != nil {
		t.Fatal(err)
	}
	handler := New(fakeLeaseService{}, verifier, audit.New(&bytes.Buffer{}))
	expiresAt := time.Now().Add(24 * time.Hour).Unix()
	body := []byte(`{"subscriptionIdHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","deviceIdHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","expiresAt":` + strconv.FormatInt(expiresAt, 10) + `,"revision":1,"idempotencyKey":"create-operation-0001"}`)
	timestamp := strconv.FormatInt(time.Now().Unix(), 10)
	request := httptest.NewRequest(http.MethodPost, "/internal/v1/leases/apply", bytes.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Idempotency-Key", "create-operation-0001")
	request.Header.Set(authn.HeaderKeyID, "bridge-v1")
	request.Header.Set(authn.HeaderTimestamp, timestamp)
	request.Header.Set(authn.HeaderNonce, "AAAAAAAAAAAAAAAAAAAAAA")
	request.Header.Set(authn.HeaderSignature, authn.Sign(key, request.Method, request.URL.RequestURI(), timestamp, "AAAAAAAAAAAAAAAAAAAAAA", body))
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("apply status=%d body=%s", recorder.Code, recorder.Body.String())
	}
	var response struct {
		RequestID string       `json:"requestId"`
		Lease     lease.Result `json:"lease"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if len(response.RequestID) != 24 || response.Lease.Credential == nil || response.Lease.Credential.Password != "AbcdEFGH2345jkmN" {
		t.Fatalf("unexpected apply envelope: %#v", response)
	}
}
