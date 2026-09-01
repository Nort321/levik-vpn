// SPDX-License-Identifier: GPL-3.0-only
//go:build linux || android

package main

import (
	"errors"
	"net"

	"golang.org/x/sys/unix"
)

func verifyLocalPeer(conn *net.UnixConn, expectedUID int) error {
	raw, err := conn.SyscallConn()
	if err != nil {
		return err
	}
	var credential *unix.Ucred
	var socketErr error
	if err := raw.Control(func(fd uintptr) {
		credential, socketErr = unix.GetsockoptUcred(int(fd), unix.SOL_SOCKET, unix.SO_PEERCRED)
	}); err != nil {
		return err
	}
	if socketErr != nil {
		return socketErr
	}
	if credential == nil || int(credential.Uid) != expectedUID {
		return errors.New("peer UID mismatch")
	}
	return nil
}
