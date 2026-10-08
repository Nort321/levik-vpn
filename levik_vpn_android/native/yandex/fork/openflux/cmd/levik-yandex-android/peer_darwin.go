//go:build darwin

package main

import (
	"golang.org/x/sys/unix"
	"net"
	"os"
)

// Darwin supports local IPC regression tests. The production helper's
// abstract socket names and build script target Android/Linux only.
func verifyPeer(conn *net.UnixConn) error {
	raw, err := conn.SyscallConn()
	if err != nil {
		return errProtocol
	}
	var credentials *unix.Xucred
	var socketErr error
	if raw.Control(func(fd uintptr) {
		credentials, socketErr = unix.GetsockoptXucred(int(fd), unix.SOL_LOCAL, unix.LOCAL_PEERCRED)
	}) != nil || socketErr != nil || credentials == nil || int(credentials.Uid) != os.Getuid() {
		return errProtocol
	}
	return nil
}
