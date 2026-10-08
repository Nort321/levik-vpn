package yandex

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/p1neappleXpress/OpenFlux/transport"
)

func refreshFixture(t *testing.T) (*YandexDocsTransport, GuestBootstrap) {
	t.Helper()
	bootstrap := restrictedBootstrapFixture()
	tr, err := NewRestrictedYandexDocsTransportWithGuestBootstrap("https://disk.yandex.ru/i/share-token_1", bootstrap, transport.DefaultConfig())
	if err != nil {
		t.Fatal(err)
	}
	// Exercise credential publication without any provider networking.
	if err := tr.BaseTransport.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = tr.Stop() })
	tr.SetConnected(true)
	return tr, bootstrap
}

func TestGuestRefreshRequiresProviderAdmissionAndPreservesCarrier(t *testing.T) {
	tr, original := refreshFixture(t)
	carrier := &DocSession{}
	tr.session = carrier
	next := original
	next.ValidUntil = next.ValidUntil.Add(30 * time.Second)
	denied := errors.New("provider denied")
	if err := tr.refreshGuestBootstrap(context.Background(), next, func(ctx context.Context, got GuestBootstrap) error {
		if *tr.guestBootstrap != original || tr.session != carrier || !tr.IsConnected() || got != next {
			t.Fatal("credential/carrier changed before provider admission")
		}
		deadline, ok := ctx.Deadline()
		if !ok || time.Until(deadline) > 7*time.Second {
			t.Fatal("probe is not bounded")
		}
		return denied
	}); !errors.Is(err, denied) || *tr.guestBootstrap != original {
		t.Fatal("denied JWT extended the old credential")
	}
	if err := tr.refreshGuestBootstrap(context.Background(), next, func(context.Context, GuestBootstrap) error { return nil }); err != nil {
		t.Fatal(err)
	}
	if *tr.guestBootstrap != next || tr.session != carrier || !tr.IsConnected() {
		t.Fatal("verified refresh replaced the live carrier")
	}
}

func TestGuestRefreshRejectsChangedBindingAndCanceledProbe(t *testing.T) {
	tr, original := refreshFixture(t)
	called := false
	verify := func(context.Context, GuestBootstrap) error { called = true; return nil }
	for _, next := range []GuestBootstrap{
		{BalancerURL: "https://other.onlyoffice.disk.yandex.net", Token: original.Token, ValidUntil: original.ValidUntil},
		{BalancerURL: original.BalancerURL, Token: strings.Replace(original.Token, ".", "!", 1), ValidUntil: original.ValidUntil},
	} {
		if tr.refreshGuestBootstrap(context.Background(), next, verify) == nil || called || *tr.guestBootstrap != original {
			t.Fatal("invalid binding reached provider verification or changed credentials")
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	next := original
	next.ValidUntil = next.ValidUntil.Add(30 * time.Second)
	if tr.refreshGuestBootstrap(ctx, next, verify) == nil || *tr.guestBootstrap != original {
		t.Fatal("canceled verification published a fresh deadline")
	}
}

func TestAuthorizedProviderSessionRolloverKeepsCarrierUntilAdmission(t *testing.T) {
	for _, change := range []func(*GuestBootstrap){
		func(b *GuestBootstrap) { b.BalancerURL = "https://replacement.onlyoffice.disk.yandex.net" },
		func(b *GuestBootstrap) {
			b.Token = restrictedBootstrapToken(`{"alg":"HS256","typ":"JWT"}`, strings.Replace(restrictedBootstrapPayload, "document-key", "replacement-key", 1))
		},
	} {
		tr, original := refreshFixture(t)
		carrier := &DocSession{}
		tr.session = carrier
		next := original
		next.ValidUntil = next.ValidUntil.Add(time.Minute)
		change(&next)
		denied := errors.New("provider denied rollover")
		if err := tr.refreshGuestSession(context.Background(), next, true, func(context.Context, GuestBootstrap) error { return denied }); !errors.Is(err, denied) {
			t.Fatal("provider denial was not returned")
		}
		if *tr.guestBootstrap != original || tr.session != carrier || !tr.IsConnected() {
			t.Fatal("unverified rollover changed the admitted carrier or deadline")
		}
		if err := tr.refreshGuestSession(context.Background(), next, true, func(context.Context, GuestBootstrap) error { return nil }); err != nil {
			t.Fatal(err)
		}
		if *tr.guestBootstrap != next || tr.session != carrier || !tr.IsConnected() {
			t.Fatal("verified rollover did not preserve the active carrier")
		}
		info, err := parseRestrictedGuestBootstrap(*tr.guestBootstrap, "guest")
		expected, expectedErr := parseRestrictedGuestBootstrap(next, "guest")
		if err != nil || expectedErr != nil || info.DocID != expected.DocID || info.Host != expected.Host {
			t.Fatal("reconnect did not use the verified replacement credential")
		}
	}
}

func TestAuthorizedRolloverCannotExpandProviderOriginPolicy(t *testing.T) {
	tr, original := refreshFixture(t)
	next := original
	next.BalancerURL = "https://127.0.0.1"
	called := false
	if err := tr.refreshGuestSession(context.Background(), next, true, func(context.Context, GuestBootstrap) error { called = true; return nil }); err == nil || called || *tr.guestBootstrap != original {
		t.Fatal("rollover bypassed the closed provider-origin policy")
	}
}

func TestGuestRefreshSerializesConcurrentPublication(t *testing.T) {
	tr, original := refreshFixture(t)
	next := original
	next.ValidUntil = next.ValidUntil.Add(time.Minute)
	entered := make(chan struct{}, 2)
	release := make(chan struct{})
	results := make(chan error, 2)
	var wg sync.WaitGroup
	for range 2 {
		wg.Go(func() {
			results <- tr.refreshGuestBootstrap(context.Background(), next, func(context.Context, GuestBootstrap) error {
				entered <- struct{}{}
				<-release
				return nil
			})
		})
	}
	<-entered
	<-entered
	close(release)
	wg.Wait()
	first, second := <-results, <-results
	if (first == nil) == (second == nil) {
		t.Fatal("concurrent stale refresh publications both committed")
	}
}

func TestGuestRefreshRetriesOnlyFailuresFromSupersededCredentials(t *testing.T) {
	tr, original := refreshFixture(t)
	old := tr.guestBootstrap
	info := YandexDocsInfo{guestCredential: old}
	next := original
	next.ValidUntil = next.ValidUntil.Add(time.Minute)
	if err := tr.refreshGuestBootstrap(context.Background(), next, func(context.Context, GuestBootstrap) error { return nil }); err != nil {
		t.Fatal(err)
	}
	if !tr.staleGuestFailure(info, ErrGuestBootstrapExpired) {
		t.Fatal("superseded expiry terminated the fresh authorization")
	}
	if tr.staleGuestFailure(info, ErrProviderAuthDenied) {
		t.Fatal("a refreshed deadline hid denial of the same provider token")
	}
	// A different, verified token can recover denial of the previous token.
	next.Token = restrictedBootstrapToken(`{"alg":"HS256","typ":"JWT"}`, strings.Replace(restrictedBootstrapPayload, "guest1", "guest2", 1))
	if err := tr.refreshGuestBootstrap(context.Background(), next, func(context.Context, GuestBootstrap) error { return nil }); err != nil {
		t.Fatal(err)
	}
	if !tr.staleGuestFailure(info, ErrProviderAuthDenied) || tr.staleGuestFailure(info, ErrEditPermission) {
		t.Fatal("stale failure classification concealed current document policy")
	}
	currentInfo := YandexDocsInfo{guestCredential: tr.guestBootstrap}
	if tr.staleGuestFailure(currentInfo, ErrProviderAuthDenied) || tr.staleGuestFailure(currentInfo, ErrGuestBootstrapExpired) {
		t.Fatal("current credential failure was suppressed")
	}
	called := false
	tr.SetErrorNotifier(func(error, string, string, string, string) { called = true })
	session := &DocSession{Info: info}
	tr.session = session
	if !tr.restrictedAdmissionFailed(session, ErrProviderAuthDenied) || called {
		t.Fatal("old admitted connection denial did not retry fresh credentials")
	}
	session.Info = currentInfo
	if tr.restrictedAdmissionFailed(session, ErrProviderAuthDenied) || !called {
		t.Fatal("current provider denial did not fail closed")
	}
}

func TestExpiredGuestFetchCarriesItsCredentialSnapshot(t *testing.T) {
	tr, original := refreshFixture(t)
	expired := original
	expired.ValidUntil = time.Now().Add(-time.Second)
	tr.guestBootstrap = &expired
	info, err := tr.fetchRestrictedDocInfo(context.Background(), tr.url, "guest")
	if !errors.Is(err, ErrGuestBootstrapExpired) || info.guestCredential != &expired {
		t.Fatal("failed fetch lost the credential snapshot needed for refresh recovery")
	}
	// Publish a provider-verified fresh snapshot while the older attempt's
	// terminal result is still awaiting delivery to its connection loop.
	tr.Mu.Lock()
	tr.guestBootstrap = &original
	tr.Mu.Unlock()
	if !tr.staleGuestFailure(info, err) {
		t.Fatal("in-flight old fetch failure invalidated the new credential")
	}
}
