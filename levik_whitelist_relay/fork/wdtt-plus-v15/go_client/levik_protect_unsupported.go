// SPDX-License-Identifier: GPL-3.0-only
//go:build !linux && !android

package main

import (
	"context"
	"errors"
	"net"
	"time"
)

type levikSocketProtector struct{}

func acceptLevikProtector(_ context.Context, _ string, _ *levikControl) (*levikSocketProtector, error) {
	return nil, errors.New("socket protection requires Android/Linux")
}

func (p *levikSocketProtector) close() {}

func protectedDialer(timeout, keepAlive time.Duration) net.Dialer {
	return net.Dialer{Timeout: timeout, KeepAlive: keepAlive}
}

func levikProtectedSocketCount() uint64 { return 0 }

func levikRejectedUnprotectedSocketCount() uint64 { return 0 }
