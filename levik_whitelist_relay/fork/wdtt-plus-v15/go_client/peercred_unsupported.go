// SPDX-License-Identifier: GPL-3.0-only
//go:build !linux && !android

package main

import (
	"errors"
	"net"
)

func verifyLocalPeer(_ *net.UnixConn, _ int) error {
	return errors.New("peer credentials unsupported on this platform")
}
