// SPDX-License-Identifier: GPL-3.0-only

package main

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const accountCredentialLifetime = 9 * time.Minute

type accountTurnCredentials struct {
	Username string
	Password string
	URLs     []string
}

var (
	vkAuthModeValue  atomic.Value
	accountRequestID atomic.Uint64
	accountControl   atomic.Pointer[levikControl]
	accountWaiters   = struct {
		sync.Mutex
		byID map[string]chan accountTurnCredentials
	}{byID: make(map[string]chan accountTurnCredentials)}
)

func init() {
	vkAuthModeValue.Store("anonymous")
}

func setVKAuthMode(mode string) string {
	mode = strings.ToLower(strings.TrimSpace(mode))
	if mode != "account" {
		mode = "anonymous"
	}
	vkAuthModeValue.Store(mode)
	return mode
}

func getVKAuthMode() string {
	mode, _ := vkAuthModeValue.Load().(string)
	if mode == "account" {
		return mode
	}
	return "anonymous"
}

func setAccountControl(control *levikControl) {
	accountControl.Store(control)
}

func requestAccountTurnCredentials(ctx context.Context, hash string, streamID int) (fetchedTurnCredentials, error) {
	control := accountControl.Load()
	if control == nil {
		return fetchedTurnCredentials{}, errors.New("VK account auth requires Levik control")
	}
	requestID := fmt.Sprintf("%d-%d", streamID, accountRequestID.Add(1))
	result := make(chan accountTurnCredentials, 1)
	accountWaiters.Lock()
	accountWaiters.byID[requestID] = result
	accountWaiters.Unlock()
	defer func() {
		accountWaiters.Lock()
		delete(accountWaiters.byID, requestID)
		accountWaiters.Unlock()
	}()

	control.emit(levikControlEvent{
		Version: levikControlVersion,
		Type:    "vk_auth_required",
		Data:    map[string]any{"requestId": requestID, "hash": hash},
	})

	waitCtx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	select {
	case creds := <-result:
		if creds.Username == "" || creds.Password == "" || len(creds.URLs) == 0 {
			return fetchedTurnCredentials{}, errors.New("VK account auth cancelled")
		}
		control.diagnostic("turn_credentials_received")
		return fetchedTurnCredentials{
			Username:    creds.Username,
			Password:    creds.Password,
			ServerAddrs: cloneStringSlice(creds.URLs),
			Lifetime:    accountCredentialLifetime,
			Provider:    "vk-account-webview",
		}, nil
	case <-waitCtx.Done():
		return fetchedTurnCredentials{}, fmt.Errorf("VK account auth timeout: %w", waitCtx.Err())
	}
}

func deliverAccountTurnCredentials(command levikControlCommand) error {
	requestID := strings.TrimSpace(command.RequestID)
	if requestID == "" || len(requestID) > 64 || strings.TrimSpace(command.Hash) == "" ||
		len(command.Username) == 0 || len(command.Username) > 512 ||
		len(command.Password) == 0 || len(command.Password) > 512 ||
		len(command.URLs) == 0 || len(command.URLs) > 16 {
		return errors.New("invalid TURN credentials")
	}
	addresses := make([]string, 0, len(command.URLs))
	for _, raw := range command.URLs {
		address := strings.TrimSpace(raw)
		if len(address) == 0 || len(address) > 512 || strings.ContainsAny(address, "\x00\r\n") {
			return errors.New("invalid TURN address")
		}
		addresses = append(addresses, address)
	}
	accountWaiters.Lock()
	waiter := accountWaiters.byID[requestID]
	accountWaiters.Unlock()
	if waiter == nil {
		return errors.New("unknown VK auth request")
	}
	select {
	case waiter <- accountTurnCredentials{
		Username: command.Username,
		Password: command.Password,
		URLs:     addresses,
	}:
		return nil
	default:
		return errors.New("duplicate VK auth result")
	}
}
