// SPDX-License-Identifier: GPL-3.0-only
//go:build !linux && !android

package main

import (
	"context"
	"errors"
)

func startLevikWireGuard(_ context.Context, _, _, _, _ string, _ *levikControl) error {
	return errors.New("Levik TUN mode requires Android/Linux")
}
