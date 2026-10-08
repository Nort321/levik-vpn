package yandex

import (
	"context"
	"errors"
	"time"

	"github.com/p1neappleXpress/OpenFlux/transport"
)

// An attempt already in flight can report expiry or denial for the snapshot it
// read before a verified refresh. Retry with the new snapshot in that case;
// never hide a denial of the currently published token.
func (t *YandexDocsTransport) staleGuestFailure(info YandexDocsInfo, err error) bool {
	old := info.guestCredential
	t.Mu.RLock()
	current := t.guestBootstrap
	t.Mu.RUnlock()
	if old == nil || current == nil || old == current || !time.Now().Before(current.ValidUntil) {
		return false
	}
	if errors.Is(err, ErrGuestBootstrapExpired) {
		return current.ValidUntil.After(old.ValidUntil)
	}
	return errors.Is(err, ErrProviderAuthDenied) && old.Token != current.Token
}

// VerifyAndRefreshGuestBootstrap verifies fresh provider admission on a bounded
// temporary connection before replacing the reconnect credential. It never
// closes the admitted carrier or its authenticated VPN session. Merely valid
// JWT syntax cannot extend the old authorization deadline.
func (t *YandexDocsTransport) VerifyAndRefreshGuestBootstrap(ctx context.Context, next GuestBootstrap) error {
	return t.refreshGuestBootstrap(ctx, next, t.verifyGuestAdmission)
}

func (t *YandexDocsTransport) refreshGuestBootstrap(ctx context.Context, next GuestBootstrap, verify func(context.Context, GuestBootstrap) error) error {
	return t.refreshGuestSession(ctx, next, false, verify)
}

// VerifyAndRefreshGuestSession permits a provider document-key/host rollover
// only after fresh editor admission. Callers must first authenticate the same
// VPN owner/device, public document and session binding. The old carrier stays
// admitted while both ends receive and verify their next reconnect credential.
func (t *YandexDocsTransport) VerifyAndRefreshGuestSession(ctx context.Context, next GuestBootstrap) error {
	return t.refreshGuestSession(ctx, next, true, t.verifyGuestAdmission)
}

func (t *YandexDocsTransport) refreshGuestSession(ctx context.Context, next GuestBootstrap, allowRollover bool, verify func(context.Context, GuestBootstrap) error) error {
	t.Mu.RLock()
	current := t.guestBootstrap
	t.Mu.RUnlock()
	if !t.restricted || current == nil || !t.IsRunning() {
		return ErrGuestBootstrap
	}
	oldInfo, err := parseRestrictedGuestBootstrap(*current, "refresh")
	if err != nil {
		return err
	}
	newInfo, err := parseRestrictedGuestBootstrap(next, "refresh")
	if err != nil {
		return err
	}
	if !allowRollover && (oldInfo.Host != newInfo.Host || oldInfo.DocID != newInfo.DocID) {
		return ErrGuestBootstrap
	}
	deadline := time.Now().Add(7 * time.Second)
	for _, expiry := range []time.Time{current.ValidUntil, next.ValidUntil} {
		if expiry.Before(deadline) {
			deadline = expiry
		}
	}
	ctx, cancel := context.WithDeadline(ctx, deadline)
	defer cancel()
	if err := verify(ctx, next); err != nil {
		return err
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	// Recheck both deadlines after I/O, before publishing a new snapshot.
	if err := restrictedGuestDeadline(current.ValidUntil); err != nil {
		return err
	}
	if _, err := parseRestrictedGuestBootstrap(next, "refresh"); err != nil {
		return err
	}
	t.Mu.Lock()
	defer t.Mu.Unlock()
	if !t.IsRunning() || t.guestBootstrap != current {
		return ErrGuestBootstrap
	}
	t.guestBootstrap = &next
	return nil
}

func (t *YandexDocsTransport) verifyGuestAdmission(ctx context.Context, next GuestBootstrap) error {
	config := transport.TransportConfig{MaxReconnectAttempts: 1, ReconnectDelay: time.Second,
		ReconnectMultiplier: 1, MaxQueueSize: 8, KeepAliveInterval: 10 * time.Second}
	probe, err := NewRestrictedYandexDocsTransportWithGuestBootstrap(t.url, next, config)
	if err != nil {
		return err
	}
	// Preserve selected-network hooks if used by an Android caller. Server
	// transports retain the default public numeric-pinned dial policy.
	probe.restrictedDial = t.restrictedPublicDialContext()
	failures := make(chan error, 1)
	probe.SetErrorNotifier(func(err error, _, _, _, _ string) {
		select {
		case failures <- err:
		default:
		}
	})
	if err := probe.Start(); err != nil {
		return err
	}
	defer probe.Stop()
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-t.Done():
			return ErrGuestBootstrap
		case err := <-failures:
			return err
		case <-ticker.C:
			if probe.IsConnected() {
				return nil
			}
		}
	}
}
