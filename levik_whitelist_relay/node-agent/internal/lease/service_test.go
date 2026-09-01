// SPDX-License-Identifier: AGPL-3.0-only

package lease

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/statefile"
	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/wdtt"
)

type memoryState struct{ records map[string]statefile.Record }

func (s *memoryState) Get(key string) (statefile.Record, bool) {
	record, ok := s.records[key]
	return record, ok
}

func (s *memoryState) Put(record statefile.Record) error {
	s.records[record.LeaseRef] = record
	return nil
}

type fakeWDTT struct {
	passwords []wdtt.Password
	events    []string
}

func (f *fakeWDTT) List(context.Context) ([]wdtt.Password, error) {
	return append([]wdtt.Password(nil), f.passwords...), nil
}

func (f *fakeWDTT) Ping(context.Context) error { return nil }

func (f *fakeWDTT) LookupLabel(_ context.Context, label string) (wdtt.Password, bool, error) {
	for _, password := range f.passwords {
		if password.Label == truncateLabel(label) {
			return password, true, nil
		}
	}
	return wdtt.Password{}, false, nil
}

func truncateLabel(value string) string {
	runes := []rune(value)
	if len(runes) > 40 {
		runes = runes[:40]
	}
	return string(runes)
}

func (f *fakeWDTT) Create(_ context.Context, input wdtt.CreateInput) (wdtt.Password, error) {
	for _, password := range f.passwords {
		if password.Password == input.Password {
			return wdtt.Password{}, errors.New("duplicate")
		}
	}
	created := wdtt.Password{Password: input.Password, Label: truncateLabel(input.Label), ExpiresAt: input.ExpiresAt, PurgeAfter: input.PurgeAfter, Ports: input.Ports, Status: "active"}
	f.passwords = append(f.passwords, created)
	return created, nil
}

func (f *fakeWDTT) mutate(password string, update func(*wdtt.Password)) (wdtt.Password, error) {
	for index := range f.passwords {
		if f.passwords[index].Password == password {
			update(&f.passwords[index])
			return f.passwords[index], nil
		}
	}
	return wdtt.Password{}, errors.New("not found")
}

func (f *fakeWDTT) SetExpiry(_ context.Context, password string, expiresAt int64) (wdtt.Password, error) {
	return f.mutate(password, func(value *wdtt.Password) {
		retention := value.PurgeAfter - value.ExpiresAt
		value.ExpiresAt = expiresAt
		if retention > 0 {
			value.PurgeAfter = expiresAt + retention
		}
	})
}

func (f *fakeWDTT) SetLabel(_ context.Context, password, label string) (wdtt.Password, error) {
	return f.mutate(password, func(value *wdtt.Password) { value.Label = truncateLabel(label) })
}

func (f *fakeWDTT) SetPassword(_ context.Context, password, replacement string) (wdtt.Password, error) {
	updated, err := f.mutate(password, func(value *wdtt.Password) { value.Password = replacement })
	if err == nil {
		f.events = append(f.events, "set-password")
	}
	return updated, err
}

func (f *fakeWDTT) Activate(_ context.Context, password string) (wdtt.Password, error) {
	updated, err := f.mutate(password, func(value *wdtt.Password) { value.Status = "active" })
	if err == nil {
		f.events = append(f.events, "activate")
	}
	return updated, err
}

func (f *fakeWDTT) Deactivate(_ context.Context, password string) (wdtt.Password, error) {
	return f.mutate(password, func(value *wdtt.Password) { value.Status = "deactivated" })
}

func testRequest(revision uint64, idempotency string) Request {
	return Request{
		SubscriptionIDHash: strings.Repeat("a", 64),
		DeviceIDHash:       strings.Repeat("b", 64),
		ExpiresAt:          time.Unix(1788172800, 0).Add(23 * time.Hour).Unix(),
		Revision:           revision,
		IdempotencyKey:     idempotency,
	}
}

func newTestService(t *testing.T) (*Service, *fakeWDTT, *memoryState) {
	t.Helper()
	backend := &fakeWDTT{}
	state := &memoryState{records: make(map[string]statefile.Record)}
	service, err := New(backend, state, "56000,56001,9000", []byte(strings.Repeat("k", 32)), DefaultRetentionGrace)
	if err != nil {
		t.Fatal(err)
	}
	service.now = func() time.Time { return time.Unix(1788172800, 0) }
	return service, backend, state
}

func TestNormalizeEnforcesTwentyFourHourLeaseBoundary(t *testing.T) {
	now := time.Unix(1788172800, 0)
	request := testRequest(1, "boundary-operation-0001")
	request.ExpiresAt = now.Add(maxLeaseDuration).Unix()
	if _, err := normalize(request, now, true); err != nil {
		t.Fatalf("exact 24-hour expiry rejected: %v", err)
	}
	request.ExpiresAt--
	if _, err := normalize(request, now, true); err != nil {
		t.Fatalf("just-inside 24-hour expiry rejected: %v", err)
	}
	request.ExpiresAt = now.Add(maxLeaseDuration).Unix() + 1
	if _, err := normalize(request, now, true); !errors.Is(err, ErrInvalidRequest) {
		t.Fatalf("just-outside 24-hour expiry accepted: %v", err)
	}
}

func TestLeaseLifecycleIsIdempotentAndLabelSurvivesWDTTLimit(t *testing.T) {
	service, backend, _ := newTestService(t)
	ctx := context.Background()
	create := testRequest(1, "create-operation-0001")
	first, err := service.Apply(ctx, create)
	if err != nil {
		t.Fatal(err)
	}
	if first.Credential == nil || !first.Created || len(backend.passwords) != 1 {
		t.Fatalf("unexpected create result: %#v", first)
	}
	if got, want := backend.passwords[0].PurgeAfter, create.ExpiresAt+int64(DefaultRetentionGrace/time.Second); got != want {
		t.Fatalf("retention deadline mismatch: got %d want %d", got, want)
	}
	if len([]rune(backend.passwords[0].Label)) > 40 || !strings.HasPrefix(backend.passwords[0].Label, "lr1-") {
		t.Fatalf("unsafe upstream label %q", backend.passwords[0].Label)
	}
	retry, err := service.Apply(ctx, create)
	if err != nil || retry.Credential == nil || retry.Credential.Password != first.Credential.Password || len(backend.passwords) != 1 {
		t.Fatalf("create retry was not idempotent: %#v err=%v", retry, err)
	}
	renew := testRequest(2, "renew-operation-0002")
	renew.ExpiresAt++
	renewed, err := service.Apply(ctx, renew)
	if err != nil || renewed.Credential != nil || renewed.Revision != 2 {
		t.Fatalf("unexpected renewal: %#v err=%v", renewed, err)
	}
	rotate := testRequest(3, "rotate-operation-0003")
	rotated, err := service.Rotate(ctx, rotate)
	if err != nil || rotated.Credential == nil || rotated.Credential.Password == first.Credential.Password {
		t.Fatalf("unexpected rotation: %#v err=%v", rotated, err)
	}
	rotateRetry, err := service.Rotate(ctx, rotate)
	if err != nil || rotateRetry.Credential == nil || rotateRetry.Credential.Password != rotated.Credential.Password {
		t.Fatalf("rotation retry changed credential: %#v err=%v", rotateRetry, err)
	}
	revoke := testRequest(4, "revoke-operation-0004")
	revoked, err := service.Revoke(ctx, revoke)
	if err != nil || revoked.Credential != nil || revoked.State != "revoked" {
		t.Fatalf("unexpected revoke: %#v err=%v", revoked, err)
	}
	status := testRequest(4, "status-operation-0004")
	observed, err := service.Status(ctx, status)
	if err != nil || observed.Credential != nil || observed.State != "revoked" {
		t.Fatalf("status leaked or failed: %#v err=%v", observed, err)
	}
}

func TestRevisionGapFailsClosed(t *testing.T) {
	service, _, _ := newTestService(t)
	if _, err := service.Apply(context.Background(), testRequest(1, "create-operation-0001")); err != nil {
		t.Fatal(err)
	}
	if _, err := service.Apply(context.Background(), testRequest(3, "renew-operation-0003")); !errors.Is(err, ErrRevisionGap) {
		t.Fatalf("expected revision gap, got %v", err)
	}
}

func TestCreateCrashRecoveryUsesDeterministicCredential(t *testing.T) {
	service, backend, state := newTestService(t)
	request := testRequest(1, "create-operation-0001")
	ref := leaseRef(request)
	credential := service.deriveCredential(ref, request.Revision)
	backend.passwords = append(backend.passwords, wdtt.Password{Password: credential, Label: formatLabel(ref), ExpiresAt: request.ExpiresAt, PurgeAfter: request.ExpiresAt + int64(DefaultRetentionGrace/time.Second), Status: "active"})
	result, err := service.Apply(context.Background(), request)
	if err != nil || result.Credential == nil || result.Credential.Password != credential {
		t.Fatalf("crash recovery failed: %#v err=%v", result, err)
	}
	if _, ok := state.Get(ref); !ok {
		t.Fatal("recovered operation was not persisted")
	}
}

func TestApplyReconcilesMissingUpstreamWithoutRotatingCredential(t *testing.T) {
	service, backend, state := newTestService(t)
	ctx := context.Background()
	create := testRequest(1, "create-operation-0001")
	created, err := service.Apply(ctx, create)
	if err != nil || created.Credential == nil {
		t.Fatalf("create failed: %#v err=%v", created, err)
	}
	originalCredential := created.Credential.Password
	backend.passwords = nil

	renew := testRequest(2, "renew-operation-0002")
	renew.ExpiresAt++
	reconciled, err := service.Apply(ctx, renew)
	if err != nil {
		t.Fatalf("missing upstream reconciliation failed: %v", err)
	}
	if reconciled.Credential != nil || len(backend.passwords) != 1 || backend.passwords[0].Password != originalCredential {
		t.Fatalf("reconciliation unexpectedly rotated credential: %#v upstream=%#v", reconciled, backend.passwords)
	}
	if got, want := backend.passwords[0].PurgeAfter, renew.ExpiresAt+int64(DefaultRetentionGrace/time.Second); got != want {
		t.Fatalf("reconciled retention deadline mismatch: got %d want %d", got, want)
	}
	record, ok := state.Get(leaseRef(renew))
	if !ok || record.Revision != 2 || record.CredentialRevision != 1 || record.Operation != operationRenew {
		t.Fatalf("reconciled state mismatch: %#v ok=%v", record, ok)
	}

	backend.passwords = nil
	retry, err := service.Apply(ctx, renew)
	if err != nil || retry.Credential != nil || len(backend.passwords) != 1 || backend.passwords[0].Password != originalCredential {
		t.Fatalf("exact renewal retry did not reconcile: %#v upstream=%#v err=%v", retry, backend.passwords, err)
	}
}

func TestRotateRetryReconcilesMissingUpstream(t *testing.T) {
	service, backend, _ := newTestService(t)
	ctx := context.Background()
	if _, err := service.Apply(ctx, testRequest(1, "create-operation-0001")); err != nil {
		t.Fatal(err)
	}
	rotate := testRequest(2, "rotate-operation-0002")
	rotated, err := service.Rotate(ctx, rotate)
	if err != nil || rotated.Credential == nil {
		t.Fatalf("rotate failed: %#v err=%v", rotated, err)
	}
	rotatedCredential := rotated.Credential.Password
	backend.passwords = nil
	retry, err := service.Rotate(ctx, rotate)
	if err != nil || retry.Credential == nil || retry.Credential.Password != rotatedCredential || len(backend.passwords) != 1 || backend.passwords[0].Password != rotatedCredential {
		t.Fatalf("missing rotated credential was not reconciled: %#v upstream=%#v err=%v", retry, backend.passwords, err)
	}
}

func TestMissingRevokeAdvancesTombstoneAndApplyReissuesFreshCredential(t *testing.T) {
	service, backend, state := newTestService(t)
	ctx := context.Background()
	create := testRequest(1, "create-operation-0001")
	created, err := service.Apply(ctx, create)
	if err != nil || created.Credential == nil {
		t.Fatalf("create failed: %#v err=%v", created, err)
	}
	oldCredential := created.Credential.Password
	backend.passwords = nil

	status, err := service.Status(ctx, testRequest(99, "status-operation-0099"))
	if err != nil || status.State != "absent" || status.ExpiresAt != 0 || status.Revision != 1 {
		t.Fatalf("absent status contract mismatch: %#v err=%v", status, err)
	}
	revoke := testRequest(2, "revoke-operation-0002")
	revoke.ExpiresAt = 0
	revoked, err := service.Revoke(ctx, revoke)
	if err != nil || revoked.State != "absent" || revoked.ExpiresAt != 0 || revoked.Revision != 2 {
		t.Fatalf("absent revoke contract mismatch: %#v err=%v", revoked, err)
	}
	record, ok := state.Get(leaseRef(create))
	if !ok || record.Operation != operationRevoke || record.Revision != 2 || record.CredentialRevision != 1 {
		t.Fatalf("absent revoke was not durably reconciled: %#v ok=%v", record, ok)
	}
	encoded, err := json.Marshal(revoked)
	if err != nil || strings.Contains(string(encoded), `"expiresAt":`) {
		t.Fatalf("absent response leaked stale expiresAt: %s err=%v", encoded, err)
	}

	apply := testRequest(3, "renew-operation-0003")
	reissued, err := service.Apply(ctx, apply)
	if err != nil || !reissued.Created || reissued.Credential == nil || reissued.Credential.Password == oldCredential {
		t.Fatalf("revoked missing lease was not freshly reissued: %#v err=%v", reissued, err)
	}
	if len(backend.passwords) != 1 || backend.passwords[0].Password != reissued.Credential.Password || backend.passwords[0].Status != "active" {
		t.Fatalf("fresh reissue upstream mismatch: %#v", backend.passwords)
	}
	record, ok = state.Get(leaseRef(create))
	if !ok || record.Operation != operationCreate || record.Revision != 3 || record.CredentialRevision != 3 {
		t.Fatalf("fresh reissue state mismatch: %#v ok=%v", record, ok)
	}
	backend.passwords = nil
	retry, err := service.Apply(ctx, apply)
	if err != nil || retry.Credential == nil || retry.Credential.Password != reissued.Credential.Password || len(backend.passwords) != 1 {
		t.Fatalf("missing fresh reissue retry changed credential: %#v upstream=%#v err=%v", retry, backend.passwords, err)
	}
}

func TestPresentRevokedApplyReplacesCredentialBeforeActivation(t *testing.T) {
	service, backend, _ := newTestService(t)
	ctx := context.Background()
	created, err := service.Apply(ctx, testRequest(1, "create-operation-0001"))
	if err != nil || created.Credential == nil {
		t.Fatalf("create failed: %#v err=%v", created, err)
	}
	oldCredential := created.Credential.Password
	revoke := testRequest(2, "revoke-operation-0002")
	if _, err := service.Revoke(ctx, revoke); err != nil {
		t.Fatal(err)
	}
	apply := testRequest(3, "restore-operation-0003")
	reissued, err := service.Apply(ctx, apply)
	if err != nil || !reissued.Created || reissued.Credential == nil || reissued.Credential.Password == oldCredential {
		t.Fatalf("revoked lease was not freshly reissued: %#v err=%v", reissued, err)
	}
	if len(backend.passwords) != 1 || backend.passwords[0].Password != reissued.Credential.Password || backend.passwords[0].Status != "active" {
		t.Fatalf("old credential was reactivated: %#v", backend.passwords)
	}
	if len(backend.events) < 2 || backend.events[len(backend.events)-2] != "set-password" || backend.events[len(backend.events)-1] != "activate" {
		t.Fatalf("credential replacement did not precede activation: %#v", backend.events)
	}
	retry, err := service.Apply(ctx, apply)
	if err != nil || retry.Credential == nil || retry.Credential.Password != reissued.Credential.Password {
		t.Fatalf("fresh reissue retry changed credential: %#v err=%v", retry, err)
	}
}

func TestNewRejectsUnsafeRetentionGrace(t *testing.T) {
	backend := &fakeWDTT{}
	state := &memoryState{records: make(map[string]statefile.Record)}
	for _, grace := range []time.Duration{0, MinRetentionGrace - time.Second, MaxRetentionGrace + time.Second, MinRetentionGrace + time.Nanosecond} {
		if _, err := New(backend, state, "56000,56001,9000", []byte(strings.Repeat("k", 32)), grace); !errors.Is(err, ErrInvalidRequest) {
			t.Fatalf("unsafe retention grace %s accepted: %v", grace, err)
		}
	}
}

func TestSharedBridgeLeaseRefGoldenVector(t *testing.T) {
	request := Request{
		SubscriptionIDHash: "229c6cc56ae91bb2e8c21b7abdd63bb48f01b4da129660ef5a7be9d0df11476c",
		DeviceIDHash:       "beae0469261d83fc1ad6b28d5f5a8990b79384c2ce7c3db0e4d87296d3f47f4c",
	}
	const want = "bymQUNzxPB1u8KJZEf7AOrj7t--6SL_2kcSdUXjnonA"
	if got := leaseRef(request); got != want {
		t.Fatalf("golden lease ref mismatch: got %s want %s", got, want)
	}
}
