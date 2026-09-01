// SPDX-License-Identifier: GPL-3.0-only
//go:build linux || android

package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

type levikProtectRequest struct {
	Version   int    `json:"version"`
	Type      string `json:"type"`
	RequestID uint64 `json:"requestId"`
	Network   string `json:"network"`
	Address   string `json:"address"`
}

type levikProtectACK struct {
	Version   int    `json:"version"`
	Type      string `json:"type"`
	RequestID uint64 `json:"requestId"`
	OK        bool   `json:"ok"`
	Code      string `json:"code,omitempty"`
}

type levikSocketProtector struct {
	conn      *net.UnixConn
	reader    *bufio.Reader
	mu        sync.Mutex
	nextID    atomic.Uint64
	protected atomic.Uint64
}

var activeLevikProtector atomic.Pointer[levikSocketProtector]
var rejectedLevikUnprotectedSockets atomic.Uint64

func acceptLevikProtector(ctx context.Context, socketName string, control *levikControl) (*levikSocketProtector, error) {
	if err := validateLevikSocketName(socketName); err != nil {
		return nil, err
	}
	address, err := net.ResolveUnixAddr("unix", socketName)
	if err != nil {
		return nil, err
	}
	listener, err := net.ListenUnix("unix", address)
	if err != nil {
		return nil, err
	}
	defer listener.Close()
	control.ready("PROTECT_CHANNEL_LISTENING", nil)
	for {
		_ = listener.SetDeadline(time.Now().Add(time.Second))
		conn, acceptErr := listener.AcceptUnix()
		if acceptErr != nil {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			if netErr, ok := acceptErr.(net.Error); ok && netErr.Timeout() {
				continue
			}
			return nil, acceptErr
		}
		if err := verifyLocalPeer(conn, os.Getuid()); err != nil {
			conn.Close()
			return nil, err
		}
		protector := &levikSocketProtector{conn: conn, reader: bufio.NewReaderSize(conn, 8<<10)}
		activeLevikProtector.Store(protector)
		levikMode.Store(true)
		control.ready("PROTECT_CHANNEL_READY", nil)
		return protector, nil
	}
}

func (p *levikSocketProtector) close() {
	if p == nil {
		return
	}
	activeLevikProtector.CompareAndSwap(p, nil)
	_ = p.conn.Close()
}

func (p *levikSocketProtector) protect(network, address string, raw syscall.RawConn) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	var duplicate int = -1
	var duplicateErr error
	if err := raw.Control(func(fd uintptr) {
		duplicate, duplicateErr = unix.Dup(int(fd))
	}); err != nil {
		return err
	}
	if duplicateErr != nil {
		return duplicateErr
	}
	defer unix.Close(duplicate)
	requestID := p.nextID.Add(1)
	request := levikProtectRequest{Version: levikControlVersion, Type: "PROTECT_SOCKET", RequestID: requestID, Network: network, Address: address}
	payload, err := json.Marshal(request)
	if err != nil {
		return err
	}
	payload = append(payload, '\n')
	_ = p.conn.SetDeadline(time.Now().Add(10 * time.Second))
	if _, _, err := p.conn.WriteMsgUnix(payload, unix.UnixRights(duplicate), nil); err != nil {
		return fmt.Errorf("send socket for Android protect/bind: %w", err)
	}
	line, err := p.reader.ReadBytes('\n')
	if err != nil {
		return fmt.Errorf("protect ACK: %w", err)
	}
	if len(line) > 8<<10 {
		return errors.New("protect ACK too large")
	}
	var ack levikProtectACK
	decoder := json.NewDecoder(bytes.NewReader(line))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&ack); err != nil {
		return errors.New("invalid protect ACK")
	}
	if ack.Version != levikControlVersion || ack.Type != "PROTECT_SOCKET_ACK" || ack.RequestID != requestID || !ack.OK {
		return fmt.Errorf("socket protect/bind rejected: %s", ack.Code)
	}
	p.protected.Add(1)
	_ = p.conn.SetDeadline(time.Time{})
	return nil
}

func levikProtectedSocketCount() uint64 {
	if protector := activeLevikProtector.Load(); protector != nil {
		return protector.protected.Load()
	}
	return 0
}

func levikRejectedUnprotectedSocketCount() uint64 {
	return rejectedLevikUnprotectedSockets.Load()
}

func protectedDialer(timeout, keepAlive time.Duration) net.Dialer {
	dialer := net.Dialer{Timeout: timeout, KeepAlive: keepAlive}
	if protector := activeLevikProtector.Load(); protector != nil {
		dialer.Control = protector.protect
	} else if levikMode.Load() {
		dialer.Control = func(_, _ string, _ syscall.RawConn) error {
			rejectedLevikUnprotectedSockets.Add(1)
			return errors.New("Android socket protect/bind channel unavailable")
		}
	}
	return dialer
}
