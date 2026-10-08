//go:build linux || android

package main

import (
	"golang.org/x/sys/unix"
	"net"
	"os"
)

func verifyPeer(conn *net.UnixConn) error {
	raw, err := conn.SyscallConn()
	if err != nil {
		return errProtocol
	}
	var credentials *unix.Ucred
	var socketErr error
	if raw.Control(func(fd uintptr) {
		credentials, socketErr = unix.GetsockoptUcred(int(fd), unix.SOL_SOCKET, unix.SO_PEERCRED)
	}) != nil || socketErr != nil || credentials == nil || int(credentials.Uid) != os.Getuid() {
		return errProtocol
	}
	return nil
}
