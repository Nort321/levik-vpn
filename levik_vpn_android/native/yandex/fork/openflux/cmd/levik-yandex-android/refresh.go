package main

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/p1neappleXpress/OpenFlux/transport/yandex"
)

// The timer and refresh publication share one lock. A callback from the old
// timer cannot revoke a successfully verified replacement deadline.
type credentialLease struct {
	mu         sync.Mutex
	deadline   time.Time
	expiryCode string
	closed     bool
	timer      *time.Timer
	cancel     context.CancelCauseFunc
}

func newCredentialLease(deadline time.Time, code string, cancel context.CancelCauseFunc) *credentialLease {
	lease := &credentialLease{deadline: deadline, expiryCode: code, cancel: cancel}
	lease.timer = time.AfterFunc(time.Until(deadline), lease.expire)
	return lease
}

func (l *credentialLease) expire() {
	l.mu.Lock()
	if l.closed || time.Now().Before(l.deadline) {
		l.mu.Unlock()
		return
	}
	l.closed = true
	code := l.expiryCode
	l.mu.Unlock()
	l.cancel(errors.New(code))
}

func (l *credentialLease) stop() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.closed = true
	l.timer.Stop()
}

func (l *credentialLease) refresh(ctx context.Context, command refreshCommandWire, verify func(context.Context, yandex.GuestBootstrap) error) refreshResult {
	result := refreshResult{RequestID: command.RequestID}
	now := time.Now()
	auth := command.ProviderAuth
	if command.RequestID < 1 || command.RequestID > 1<<53 || command.LeaseExpiresAt <= now.Unix() ||
		command.LeaseExpiresAt > now.Add(time.Hour).Unix() || auth.ValidUntil <= now.Unix() ||
		auth.ValidUntil > now.Add(15*time.Minute).Unix() || auth.ValidUntil > command.LeaseExpiresAt ||
		len(auth.Token) < 64 || len(auth.Token) > 8192 || len(auth.BalancerURL) > 256 {
		result.Code = "invalid_refresh"
		return result
	}
	nextDeadline := now.Add(time.Unix(min(command.LeaseExpiresAt, auth.ValidUntil), 0).Sub(now))
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.closed || ctx.Err() != nil || !time.Now().Before(l.deadline) {
		result.Code = "refresh_expired"
		return result
	}
	probeCtx, cancel := context.WithDeadline(ctx, minTime(l.deadline, time.Now().Add(7*time.Second)))
	defer cancel()
	if verify(probeCtx, yandex.GuestBootstrap{BalancerURL: auth.BalancerURL, Token: auth.Token, ValidUntil: time.Unix(auth.ValidUntil, 0)}) != nil {
		result.Code = "refresh_authorization_failed"
		return result
	}
	now = time.Now()
	if l.closed || ctx.Err() != nil || probeCtx.Err() != nil || !now.Before(l.deadline) || !now.Before(nextDeadline) {
		result.Code = "refresh_expired"
		return result
	}
	l.deadline = nextDeadline
	l.expiryCode = "provider_auth_expired"
	if command.LeaseExpiresAt <= auth.ValidUntil {
		l.expiryCode = "lease_expired"
	}
	l.timer.Reset(time.Until(l.deadline))
	result.OK, result.LeaseExpiresAt, result.ValidUntil = true, command.LeaseExpiresAt, auth.ValidUntil
	return result
}

func minTime(a, b time.Time) time.Time {
	if a.Before(b) {
		return a
	}
	return b
}
