// SPDX-License-Identifier: AGPL-3.0-only

package httpapi

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/audit"
	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/authn"
	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/lease"
	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/wdtt"
)

type leaseService interface {
	Apply(context.Context, lease.Request) (lease.Result, error)
	Rotate(context.Context, lease.Request) (lease.Result, error)
	Revoke(context.Context, lease.Request) (lease.Result, error)
	Status(context.Context, lease.Request) (lease.Result, error)
	Ready(context.Context) error
}

type Server struct {
	service leaseService
	audit   *audit.Logger
}

func New(service leaseService, verifier *authn.Verifier, logger *audit.Logger) http.Handler {
	server := &Server{service: service, audit: logger}
	public := http.NewServeMux()
	public.HandleFunc("GET /livez", server.livez)
	public.HandleFunc("GET /readyz", server.readyz)

	protected := http.NewServeMux()
	protected.HandleFunc("POST /internal/v1/leases/apply", server.apply)
	protected.HandleFunc("POST /internal/v1/leases/rotate", server.rotate)
	protected.HandleFunc("POST /internal/v1/leases/revoke", server.revoke)
	protected.HandleFunc("POST /internal/v1/leases/status", server.status)
	public.Handle("/internal/v1/", verifier.Middleware(protected))
	return securityHeaders(public)
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		next.ServeHTTP(w, r)
	})
}

func requestID() (string, error) {
	var value [12]byte
	if _, err := rand.Read(value[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(value[:]), nil
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func (s *Server) livez(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "protocolVersion": 1})
}

func (s *Server) readyz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := s.service.Ready(ctx); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"ok": false, "code": "wdtt_unavailable"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "protocolVersion": 1})
}

func decodeRequest(r *http.Request) (lease.Request, error) {
	if r.URL.RawQuery != "" {
		return lease.Request{}, lease.ErrInvalidRequest
	}
	if contentType := r.Header.Get("Content-Type"); !strings.HasPrefix(strings.ToLower(contentType), "application/json") {
		return lease.Request{}, lease.ErrInvalidRequest
	}
	decoder := json.NewDecoder(bytes.NewReader(authn.Body(r.Context())))
	decoder.DisallowUnknownFields()
	var request lease.Request
	if err := decoder.Decode(&request); err != nil {
		return lease.Request{}, lease.ErrInvalidRequest
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return lease.Request{}, lease.ErrInvalidRequest
	}
	if strings.TrimSpace(r.Header.Get("Idempotency-Key")) != request.IdempotencyKey {
		return lease.Request{}, lease.ErrInvalidRequest
	}
	return request, nil
}

func errorStatus(err error) (int, string) {
	switch {
	case errors.Is(err, lease.ErrInvalidRequest):
		return http.StatusBadRequest, "invalid_request"
	case errors.Is(err, lease.ErrNotFound):
		return http.StatusNotFound, "not_found"
	case errors.Is(err, lease.ErrStaleRevision):
		return http.StatusConflict, "stale_revision"
	case errors.Is(err, lease.ErrRevisionGap):
		return http.StatusConflict, "revision_gap"
	case errors.Is(err, lease.ErrConflict), errors.Is(err, lease.ErrRevoked), errors.Is(err, lease.ErrDuplicateRecord):
		return http.StatusConflict, "state_conflict"
	case errors.Is(err, wdtt.ErrAdminRejected):
		return http.StatusConflict, "wdtt_rejected"
	default:
		return http.StatusServiceUnavailable, "node_unavailable"
	}
}

func (s *Server) operation(w http.ResponseWriter, r *http.Request, name string, run func(context.Context, lease.Request) (lease.Result, error)) {
	started := time.Now()
	id, idErr := requestID()
	if idErr != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "node_unavailable"})
		return
	}
	request, err := decodeRequest(r)
	if err != nil {
		status, code := errorStatus(err)
		s.audit.Write(audit.Event{RequestID: id, KeyID: authn.KeyID(r.Context()), Operation: name, Status: status, Code: code, DurationMS: time.Since(started).Milliseconds()})
		writeJSON(w, status, map[string]any{"error": code, "requestId": id})
		return
	}
	result, err := run(r.Context(), request)
	if err != nil {
		status, code := errorStatus(err)
		s.audit.Write(audit.Event{RequestID: id, KeyID: authn.KeyID(r.Context()), Operation: name, LeaseRef: audit.Ref(request.SubscriptionIDHash + ":" + request.DeviceIDHash), Revision: request.Revision, Status: status, Code: code, DurationMS: time.Since(started).Milliseconds()})
		writeJSON(w, status, map[string]any{"error": code, "requestId": id})
		return
	}
	status := http.StatusOK
	if result.Created {
		status = http.StatusCreated
	}
	s.audit.Write(audit.Event{RequestID: id, KeyID: authn.KeyID(r.Context()), Operation: name, LeaseRef: audit.Ref(result.LeaseRef), Revision: result.Revision, Status: status, Code: "ok", DurationMS: time.Since(started).Milliseconds()})
	writeJSON(w, status, map[string]any{"requestId": id, "lease": result})
}

func (s *Server) apply(w http.ResponseWriter, r *http.Request) {
	s.operation(w, r, "lease.apply", s.service.Apply)
}

func (s *Server) rotate(w http.ResponseWriter, r *http.Request) {
	s.operation(w, r, "lease.rotate", s.service.Rotate)
}

func (s *Server) revoke(w http.ResponseWriter, r *http.Request) {
	s.operation(w, r, "lease.revoke", s.service.Revoke)
}

func (s *Server) status(w http.ResponseWriter, r *http.Request) {
	s.operation(w, r, "lease.status", s.service.Status)
}
