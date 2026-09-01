// SPDX-License-Identifier: AGPL-3.0-only

package lease

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base32"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"math"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/statefile"
	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/wdtt"
)

var (
	ErrInvalidRequest  = errors.New("invalid lease request")
	ErrNotFound        = errors.New("lease not found")
	ErrConflict        = errors.New("lease state conflict")
	ErrStaleRevision   = errors.New("stale lease revision")
	ErrRevisionGap     = errors.New("lease revision gap")
	ErrRevoked         = errors.New("lease is revoked")
	ErrDuplicateRecord = errors.New("duplicate WDTT lease records")
)

const (
	labelPrefix           = "lr1-"
	operationCreate       = "create"
	operationRenew        = "renew"
	operationRotate       = "rotate"
	operationRevoke       = "revoke"
	passwordChars         = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
	passwordLength        = 16
	maxLeaseDuration      = 24 * time.Hour
	DefaultRetentionGrace = 48 * time.Hour
	MinRetentionGrace     = time.Hour
	MaxRetentionGrace     = 7 * 24 * time.Hour
)

var (
	hashPattern        = regexp.MustCompile(`^[0-9a-fA-F]{64}$`)
	idempotencyPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{16,128}$`)
)

type Request struct {
	SubscriptionIDHash string `json:"subscriptionIdHash"`
	DeviceIDHash       string `json:"deviceIdHash"`
	ExpiresAt          int64  `json:"expiresAt"`
	Revision           uint64 `json:"revision"`
	IdempotencyKey     string `json:"idempotencyKey"`
}

type Credential struct {
	Password string `json:"password"`
}

type Result struct {
	LeaseRef   string      `json:"leaseRef"`
	State      string      `json:"state"`
	ExpiresAt  int64       `json:"expiresAt,omitempty"`
	Revision   uint64      `json:"revision"`
	Created    bool        `json:"created,omitempty"`
	Rotated    bool        `json:"rotated,omitempty"`
	Credential *Credential `json:"credential,omitempty"`
}

type Service struct {
	store wdtt.Store
	state interface {
		Get(string) (statefile.Record, bool)
		Put(statefile.Record) error
	}
	ports          string
	credentialKey  []byte
	retentionGrace time.Duration
	now            func() time.Time
	mu             sync.Mutex
}

func New(store wdtt.Store, state interface {
	Get(string) (statefile.Record, bool)
	Put(statefile.Record) error
}, ports string, credentialKey []byte, retentionGrace time.Duration) (*Service, error) {
	if store == nil || state == nil || strings.TrimSpace(ports) == "" || len(credentialKey) < 32 ||
		retentionGrace < MinRetentionGrace || retentionGrace > MaxRetentionGrace || retentionGrace%time.Second != 0 {
		return nil, ErrInvalidRequest
	}
	return &Service{
		store:          store,
		state:          state,
		ports:          strings.TrimSpace(ports),
		credentialKey:  append([]byte(nil), credentialKey...),
		retentionGrace: retentionGrace,
		now:            time.Now,
	}, nil
}

func normalize(input Request, now time.Time, requireFutureExpiry bool) (Request, error) {
	input.SubscriptionIDHash = strings.ToLower(strings.TrimSpace(input.SubscriptionIDHash))
	input.DeviceIDHash = strings.ToLower(strings.TrimSpace(input.DeviceIDHash))
	input.IdempotencyKey = strings.TrimSpace(input.IdempotencyKey)
	if !hashPattern.MatchString(input.SubscriptionIDHash) || !hashPattern.MatchString(input.DeviceIDHash) {
		return Request{}, ErrInvalidRequest
	}
	if input.Revision == 0 || !idempotencyPattern.MatchString(input.IdempotencyKey) {
		return Request{}, ErrInvalidRequest
	}
	if requireFutureExpiry {
		minExpiry := now.Add(time.Minute).Unix()
		maxExpiry := now.Add(maxLeaseDuration).Unix()
		if input.ExpiresAt < minExpiry || input.ExpiresAt > maxExpiry {
			return Request{}, ErrInvalidRequest
		}
	} else if input.ExpiresAt < 0 {
		return Request{}, ErrInvalidRequest
	}
	return input, nil
}

func leaseRef(input Request) string {
	sum := sha256.Sum256([]byte("levik-lease-v1\n" + input.SubscriptionIDHash + "\n" + input.DeviceIDHash))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

func idempotencyRef(value string) string {
	sum := sha256.Sum256([]byte("levik-idempotency-v1\n" + value))
	return base64.RawURLEncoding.EncodeToString(sum[:16])
}

func formatLabel(ref string) string {
	sum := sha256.Sum256([]byte("levik-wdtt-label-v1\n" + ref))
	encoded := base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(sum[:20])
	return labelPrefix + strings.ToLower(encoded)
}

func stateOf(password wdtt.Password) string {
	switch strings.ToLower(strings.TrimSpace(password.Status)) {
	case "deactivated":
		return "revoked"
	case "expired":
		return "expired"
	case "active":
		return "active"
	default:
		return "unknown"
	}
}

func (s *Service) find(ctx context.Context, ref string) (*wdtt.Password, statefile.Record, bool, error) {
	label := formatLabel(ref)
	password, found, err := s.store.LookupLabel(ctx, label)
	if err != nil {
		return nil, statefile.Record{}, false, err
	}
	var existing *wdtt.Password
	if found {
		if strings.TrimSpace(password.Label) != label {
			return nil, statefile.Record{}, false, ErrConflict
		}
		existing = &password
	}
	record, hasRecord := s.state.Get(ref)
	if hasRecord && (record.LeaseRef != ref || record.Label != label) {
		return nil, statefile.Record{}, false, ErrConflict
	}
	return existing, record, hasRecord, nil
}

func (s *Service) deriveCredential(ref string, credentialRevision uint64) string {
	seed := fmt.Sprintf("levik-wdtt-credential-v2\n%s\n%d", ref, credentialRevision)
	result := make([]byte, 0, passwordLength)
	for counter := byte(0); len(result) < passwordLength; counter++ {
		mac := hmac.New(sha256.New, s.credentialKey)
		_, _ = mac.Write([]byte(seed))
		_, _ = mac.Write([]byte{counter})
		for _, value := range mac.Sum(nil) {
			limit := 256 - (256 % len(passwordChars))
			if int(value) >= limit {
				continue
			}
			result = append(result, passwordChars[int(value)%len(passwordChars)])
			if len(result) == passwordLength {
				break
			}
		}
	}
	return string(result)
}

func (s *Service) purgeAfter(expiresAt int64) (int64, error) {
	graceSeconds := int64(s.retentionGrace / time.Second)
	if expiresAt <= 0 || graceSeconds <= 0 || expiresAt > math.MaxInt64-graceSeconds {
		return 0, ErrInvalidRequest
	}
	return expiresAt + graceSeconds, nil
}

func (s *Service) createUpstream(ctx context.Context, ref, credential string, expiresAt int64) (wdtt.Password, error) {
	purgeAfter, err := s.purgeAfter(expiresAt)
	if err != nil {
		return wdtt.Password{}, err
	}
	return s.store.Create(ctx, wdtt.CreateInput{
		Password:   credential,
		Label:      formatLabel(ref),
		ExpiresAt:  expiresAt,
		PurgeAfter: purgeAfter,
		Ports:      s.ports,
	})
}

func (s *Service) reissueRevoked(ctx context.Context, ref string, input Request, existing *wdtt.Password) (Result, error) {
	credential := s.deriveCredential(ref, input.Revision)
	var updated wdtt.Password
	var err error
	if existing == nil {
		updated, err = s.createUpstream(ctx, ref, credential, input.ExpiresAt)
	} else {
		updated = *existing
		if updated.Password != credential {
			updated, err = s.store.SetPassword(ctx, updated.Password, credential)
		}
		if err == nil && updated.ExpiresAt != input.ExpiresAt {
			updated, err = s.store.SetExpiry(ctx, updated.Password, input.ExpiresAt)
		}
		if err == nil && stateOf(updated) != "active" {
			updated, err = s.store.Activate(ctx, updated.Password)
		}
	}
	if err != nil {
		return Result{}, err
	}
	record := statefile.Record{
		LeaseRef: ref, Label: formatLabel(ref), Revision: input.Revision,
		CredentialRevision: input.Revision, IdempotencyRef: idempotencyRef(input.IdempotencyKey),
		Operation: operationCreate, ExpiresAt: updated.ExpiresAt,
	}
	if err := s.state.Put(record); err != nil {
		return Result{}, err
	}
	return Result{
		LeaseRef: ref, State: stateOf(updated), ExpiresAt: updated.ExpiresAt,
		Revision: input.Revision, Created: true, Credential: &Credential{Password: credential},
	}, nil
}

func checkRevision(input Request, record statefile.Record) error {
	if input.Revision < record.Revision {
		return ErrStaleRevision
	}
	if input.Revision > record.Revision+1 {
		return ErrRevisionGap
	}
	if input.Revision == record.Revision && idempotencyRef(input.IdempotencyKey) != record.IdempotencyRef {
		return ErrConflict
	}
	return nil
}

func (s *Service) Apply(ctx context.Context, input Request) (Result, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	input, err := normalize(input, s.now(), true)
	if err != nil {
		return Result{}, err
	}
	ref := leaseRef(input)
	existing, record, hasRecord, err := s.find(ctx, ref)
	if err != nil {
		return Result{}, err
	}
	if existing == nil {
		if !hasRecord {
			credential := s.deriveCredential(ref, input.Revision)
			created, err := s.createUpstream(ctx, ref, credential, input.ExpiresAt)
			if err != nil {
				return Result{}, err
			}
			record = statefile.Record{
				LeaseRef: ref, Label: formatLabel(ref), Revision: input.Revision,
				CredentialRevision: input.Revision, IdempotencyRef: idempotencyRef(input.IdempotencyKey),
				Operation: operationCreate, ExpiresAt: created.ExpiresAt,
			}
			if err := s.state.Put(record); err != nil {
				return Result{}, err
			}
			return Result{LeaseRef: ref, State: stateOf(created), ExpiresAt: created.ExpiresAt, Revision: input.Revision, Created: true, Credential: &Credential{Password: credential}}, nil
		}
		if err := checkRevision(input, record); err != nil {
			return Result{}, err
		}
		if input.Revision == record.Revision {
			if input.ExpiresAt != record.ExpiresAt {
				return Result{}, ErrConflict
			}
			switch record.Operation {
			case operationCreate, operationRenew:
			default:
				return Result{}, ErrConflict
			}
		} else if record.Operation == operationRevoke {
			return s.reissueRevoked(ctx, ref, input, nil)
		}

		credential := s.deriveCredential(ref, record.CredentialRevision)
		created, err := s.createUpstream(ctx, ref, credential, input.ExpiresAt)
		if err != nil {
			return Result{}, err
		}
		if input.Revision > record.Revision {
			record.Revision = input.Revision
			record.IdempotencyRef = idempotencyRef(input.IdempotencyKey)
			record.Operation = operationRenew
			record.ExpiresAt = created.ExpiresAt
			if err := s.state.Put(record); err != nil {
				return Result{}, err
			}
		}
		result := Result{LeaseRef: ref, State: stateOf(created), ExpiresAt: created.ExpiresAt, Revision: record.Revision}
		if record.Operation == operationCreate {
			result.Created = true
			result.Credential = &Credential{Password: credential}
		}
		return result, nil
	}
	if !hasRecord {
		// Crash recovery for the only mutation that can create an upstream
		// record without durable local state. Deterministic credential material
		// proves this is the exact create retry, not an unrelated short-label hit.
		expected := s.deriveCredential(ref, input.Revision)
		if existing.Password != expected {
			return Result{}, ErrConflict
		}
		record = statefile.Record{
			LeaseRef: ref, Label: formatLabel(ref), Revision: input.Revision,
			CredentialRevision: input.Revision, IdempotencyRef: idempotencyRef(input.IdempotencyKey),
			Operation: operationCreate, ExpiresAt: existing.ExpiresAt,
		}
		if err := s.state.Put(record); err != nil {
			return Result{}, err
		}
	}
	if err := checkRevision(input, record); err != nil {
		return Result{}, err
	}
	if input.Revision == record.Revision {
		if input.ExpiresAt != record.ExpiresAt {
			return Result{}, ErrConflict
		}
		result := Result{LeaseRef: ref, State: stateOf(*existing), ExpiresAt: existing.ExpiresAt, Revision: record.Revision}
		switch record.Operation {
		case operationCreate:
			result.Created = true
			result.Credential = &Credential{Password: existing.Password}
		case operationRenew:
		default:
			return Result{}, ErrConflict
		}
		return result, nil
	}
	if record.Operation == operationRevoke {
		return s.reissueRevoked(ctx, ref, input, existing)
	}
	if stateOf(*existing) == "revoked" {
		return Result{}, ErrRevoked
	}
	updated := *existing
	if updated.ExpiresAt != input.ExpiresAt {
		updated, err = s.store.SetExpiry(ctx, updated.Password, input.ExpiresAt)
		if err != nil {
			return Result{}, err
		}
	}
	record = statefile.Record{
		LeaseRef: ref, Label: formatLabel(ref), Revision: input.Revision,
		CredentialRevision: record.CredentialRevision, IdempotencyRef: idempotencyRef(input.IdempotencyKey),
		Operation: operationRenew, ExpiresAt: updated.ExpiresAt,
	}
	if err := s.state.Put(record); err != nil {
		return Result{}, err
	}
	return Result{LeaseRef: ref, State: stateOf(updated), ExpiresAt: updated.ExpiresAt, Revision: input.Revision}, nil
}

func (s *Service) Rotate(ctx context.Context, input Request) (Result, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	input, err := normalize(input, s.now(), true)
	if err != nil {
		return Result{}, err
	}
	ref := leaseRef(input)
	existing, record, hasRecord, err := s.find(ctx, ref)
	if err != nil {
		return Result{}, err
	}
	if !hasRecord {
		return Result{}, ErrNotFound
	}
	if err := checkRevision(input, record); err != nil {
		return Result{}, err
	}
	if existing == nil {
		if input.Revision == record.Revision {
			if record.Operation != operationRotate || input.ExpiresAt != record.ExpiresAt {
				return Result{}, ErrConflict
			}
		} else if record.Operation == operationRevoke {
			return Result{}, ErrRevoked
		}
		credentialRevision := input.Revision
		if input.Revision == record.Revision {
			credentialRevision = record.CredentialRevision
		}
		replacement := s.deriveCredential(ref, credentialRevision)
		created, err := s.createUpstream(ctx, ref, replacement, input.ExpiresAt)
		if err != nil {
			return Result{}, err
		}
		if input.Revision > record.Revision {
			record.Revision = input.Revision
			record.CredentialRevision = credentialRevision
			record.IdempotencyRef = idempotencyRef(input.IdempotencyKey)
			record.Operation = operationRotate
			record.ExpiresAt = created.ExpiresAt
			if err := s.state.Put(record); err != nil {
				return Result{}, err
			}
		}
		return Result{LeaseRef: ref, State: stateOf(created), ExpiresAt: created.ExpiresAt, Revision: record.Revision, Rotated: true, Credential: &Credential{Password: replacement}}, nil
	}
	if input.Revision == record.Revision {
		if record.Operation != operationRotate || input.ExpiresAt != record.ExpiresAt {
			return Result{}, ErrConflict
		}
		return Result{LeaseRef: ref, State: stateOf(*existing), ExpiresAt: existing.ExpiresAt, Revision: record.Revision, Rotated: true, Credential: &Credential{Password: existing.Password}}, nil
	}
	if stateOf(*existing) == "revoked" {
		return Result{}, ErrRevoked
	}
	replacement := s.deriveCredential(ref, input.Revision)
	updated := *existing
	if updated.Password != replacement {
		updated, err = s.store.SetPassword(ctx, updated.Password, replacement)
		if err != nil {
			return Result{}, err
		}
	}
	if updated.ExpiresAt != input.ExpiresAt {
		updated, err = s.store.SetExpiry(ctx, updated.Password, input.ExpiresAt)
		if err != nil {
			return Result{}, err
		}
	}
	record = statefile.Record{
		LeaseRef: ref, Label: formatLabel(ref), Revision: input.Revision,
		CredentialRevision: input.Revision, IdempotencyRef: idempotencyRef(input.IdempotencyKey),
		Operation: operationRotate, ExpiresAt: updated.ExpiresAt,
	}
	if err := s.state.Put(record); err != nil {
		return Result{}, err
	}
	return Result{LeaseRef: ref, State: stateOf(updated), ExpiresAt: updated.ExpiresAt, Revision: input.Revision, Rotated: true, Credential: &Credential{Password: replacement}}, nil
}

func (s *Service) Revoke(ctx context.Context, input Request) (Result, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	input, err := normalize(input, s.now(), false)
	if err != nil {
		return Result{}, err
	}
	ref := leaseRef(input)
	existing, record, hasRecord, err := s.find(ctx, ref)
	if err != nil {
		return Result{}, err
	}
	if existing == nil {
		if !hasRecord {
			return Result{LeaseRef: ref, State: "absent", Revision: input.Revision}, nil
		}
		if err := checkRevision(input, record); err != nil {
			return Result{}, err
		}
		if input.Revision == record.Revision {
			if record.Operation != operationRevoke {
				return Result{}, ErrConflict
			}
			return Result{LeaseRef: ref, State: "absent", Revision: record.Revision}, nil
		}
		record.Revision = input.Revision
		record.IdempotencyRef = idempotencyRef(input.IdempotencyKey)
		record.Operation = operationRevoke
		if err := s.state.Put(record); err != nil {
			return Result{}, err
		}
		return Result{LeaseRef: ref, State: "absent", Revision: record.Revision}, nil
	}
	if !hasRecord {
		return Result{}, ErrConflict
	}
	if err := checkRevision(input, record); err != nil {
		return Result{}, err
	}
	if input.Revision == record.Revision {
		if record.Operation != operationRevoke {
			return Result{}, ErrConflict
		}
		return Result{LeaseRef: ref, State: "revoked", ExpiresAt: existing.ExpiresAt, Revision: record.Revision}, nil
	}
	updated := *existing
	if stateOf(updated) != "revoked" {
		updated, err = s.store.Deactivate(ctx, updated.Password)
		if err != nil {
			return Result{}, err
		}
	}
	record = statefile.Record{
		LeaseRef: ref, Label: formatLabel(ref), Revision: input.Revision,
		CredentialRevision: record.CredentialRevision, IdempotencyRef: idempotencyRef(input.IdempotencyKey),
		Operation: operationRevoke, ExpiresAt: updated.ExpiresAt,
	}
	if err := s.state.Put(record); err != nil {
		return Result{}, err
	}
	return Result{LeaseRef: ref, State: "revoked", ExpiresAt: updated.ExpiresAt, Revision: input.Revision}, nil
}

func (s *Service) Status(ctx context.Context, input Request) (Result, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	input, err := normalize(input, s.now(), false)
	if err != nil {
		return Result{}, err
	}
	ref := leaseRef(input)
	existing, record, hasRecord, err := s.find(ctx, ref)
	if err != nil {
		return Result{}, err
	}
	if existing == nil {
		if hasRecord {
			return Result{LeaseRef: ref, State: "absent", Revision: record.Revision}, nil
		}
		return Result{LeaseRef: ref, State: "absent", Revision: input.Revision}, nil
	}
	if !hasRecord {
		return Result{}, ErrConflict
	}
	return Result{LeaseRef: ref, State: stateOf(*existing), ExpiresAt: existing.ExpiresAt, Revision: record.Revision}, nil
}

func (s *Service) Ready(ctx context.Context) error {
	return s.store.Ping(ctx)
}

// ValidateCredentialKeyHex is useful for offline deployment validation without
// ever printing the key.
func ValidateCredentialKeyHex(value string) error {
	decoded, err := hex.DecodeString(strings.TrimSpace(value))
	if err != nil || len(decoded) < 32 {
		return ErrInvalidRequest
	}
	return nil
}
