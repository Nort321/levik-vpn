//go:build linux || android || darwin

package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/p1neappleXpress/OpenFlux/transport/egresspolicy"
	"golang.org/x/sys/unix"
)

var errNetworkChannel = errors.New("network_channel_failed")

type networkOperationError string

func (e networkOperationError) Error() string { return string(e) }

const (
	errResolveFailed networkOperationError = "resolve_failed"
	errProtectFailed networkOperationError = "protect_bind_failed"
)

type networkRequest struct {
	Magic     string `json:"magic"`
	Version   int    `json:"version"`
	Type      string `json:"type"`
	RequestID uint64 `json:"requestId"`
	Network   string `json:"network,omitempty"`
	Address   string `json:"address,omitempty"`
	Host      string `json:"host,omitempty"`
}
type protectACK struct {
	Magic     string `json:"magic"`
	Version   int    `json:"version"`
	Type      string `json:"type"`
	RequestID uint64 `json:"requestId"`
	OK        bool   `json:"ok"`
	Code      string `json:"code,omitempty"`
}
type resolveACK struct {
	Magic     string   `json:"magic"`
	Version   int      `json:"version"`
	Type      string   `json:"type"`
	RequestID uint64   `json:"requestId"`
	OK        bool     `json:"ok"`
	Addresses []string `json:"addresses"`
	Code      string   `json:"code,omitempty"`
}

type networkChannel struct {
	conn      *net.UnixConn
	reader    *bufio.Reader
	gate      chan struct{}
	nextID    atomic.Uint64
	protected atomic.Uint64
	resolved  atomic.Uint64
	closeOnce sync.Once
	onFailure func()
}

func newNetworkChannel(conn *net.UnixConn, onFailure func()) *networkChannel {
	return &networkChannel{conn: conn, reader: bufio.NewReaderSize(conn, maxNetworkFrame), gate: make(chan struct{}, 1), onFailure: onFailure}
}

func (n *networkChannel) close() { n.closeOnce.Do(func() { _ = n.conn.Close() }) }
func (n *networkChannel) fail() error {
	n.close()
	if n.onFailure != nil {
		n.onFailure()
	}
	return errNetworkChannel
}

func (n *networkChannel) exchange(ctx context.Context, request networkRequest, fd int, ack any) error {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	select {
	case n.gate <- struct{}{}:
	case <-ctx.Done():
		return errNetworkChannel
	}
	defer func() { <-n.gate }()
	if ctx.Err() != nil {
		return errNetworkChannel
	}
	deadline, _ := ctx.Deadline()
	_ = n.conn.SetDeadline(deadline)
	// Join a running cancellation callback before releasing the gate so it
	// cannot poison the next operation's Unix socket deadline.
	canceled := make(chan struct{})
	stop := context.AfterFunc(ctx, func() { _ = n.conn.SetDeadline(time.Now()); close(canceled) })
	joined := false
	joinCancellation := func() {
		if !joined {
			joined = true
			if !stop() {
				<-canceled
			}
		}
	}
	defer joinCancellation()
	request.Magic, request.Version = protocolMagic, protocolVersion
	request.RequestID = n.nextID.Add(1)
	if request.RequestID > 1<<53 {
		return n.fail()
	}
	payload, err := json.Marshal(request)
	if err != nil || len(payload)+1 > maxNetworkFrame {
		return n.fail()
	}
	payload = append(payload, '\n')
	var count int
	if fd >= 0 {
		count, _, err = n.conn.WriteMsgUnix(payload, unix.UnixRights(fd), nil)
	} else {
		count, err = n.conn.Write(payload)
	}
	if err != nil || count != len(payload) {
		return n.fail()
	}
	frame, err := readFrame(n.reader)
	if err != nil || strictDecode(frame, ack) != nil {
		return n.fail()
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(frame, &fields) != nil || (string(fields["ok"]) != "true" && string(fields["ok"]) != "false") {
		return n.fail()
	}
	var operationError error
	switch value := ack.(type) {
	case *protectACK:
		if !validEnvelope(value.Magic, value.Version) || value.Type != "PROTECT_SOCKET_ACK" || value.RequestID != request.RequestID {
			return n.fail()
		}
		if value.OK {
			if value.Code != "" {
				return n.fail()
			}
		} else {
			if value.Code != string(errProtectFailed) {
				return n.fail()
			}
			operationError = errProtectFailed
		}
	case *resolveACK:
		if !validEnvelope(value.Magic, value.Version) || value.Type != "RESOLVE_HOST_ACK" || value.RequestID != request.RequestID {
			return n.fail()
		}
		if value.OK {
			if value.Code != "" {
				return n.fail()
			}
		} else {
			if value.Code != string(errResolveFailed) || value.Addresses == nil || len(value.Addresses) != 0 {
				return n.fail()
			}
			operationError = errResolveFailed
		}
	default:
		return n.fail()
	}
	joinCancellation()
	// Cancellation remains fatal to this channel.
	if ctx.Err() != nil {
		return n.fail()
	}
	_ = n.conn.SetDeadline(time.Time{})
	return operationError
}

func (n *networkChannel) lookup(ctx context.Context, host string) ([]net.IPAddr, error) {
	// Only provider endpoints may reach Android's selected-network DNS. User
	// traffic destinations are IPv4 literals and cannot use this channel.
	if _, err := egresspolicy.ValidateProviderURL("https://"+host+"/", false); err != nil {
		return nil, egresspolicy.ErrProviderURL
	}
	var ack resolveACK
	if err := n.exchange(ctx, networkRequest{Type: "RESOLVE_HOST", Host: host}, -1, &ack); err != nil {
		return nil, err
	}
	if len(ack.Addresses) == 0 || len(ack.Addresses) > 64 {
		return nil, n.fail()
	}
	addresses := make([]net.IPAddr, 0, len(ack.Addresses))
	for _, address := range ack.Addresses {
		ip := net.ParseIP(address)
		if ip == nil || strings.Contains(address, "%") || !egresspolicy.IsPublicIP(ip) {
			return nil, n.fail()
		}
		addresses = append(addresses, net.IPAddr{IP: ip})
	}
	n.resolved.Add(1)
	return addresses, nil
}

func (n *networkChannel) protect(ctx context.Context, network, address string, raw syscall.RawConn) error {
	host, port, err := net.SplitHostPort(address)
	ip := net.ParseIP(host)
	if err != nil || port != "443" || ip == nil || !egresspolicy.IsPublicIP(ip) ||
		(network != "tcp4" && network != "tcp6") || (network == "tcp4") != (ip.To4() != nil) {
		return egresspolicy.ErrDestination
	}
	fd := -1
	var duplicateErr error
	if err = raw.Control(func(value uintptr) { fd, duplicateErr = unix.FcntlInt(value, unix.F_DUPFD_CLOEXEC, 0) }); err != nil || duplicateErr != nil {
		return n.fail()
	}
	defer unix.Close(fd)
	var ack protectACK
	if err := n.exchange(ctx, networkRequest{Type: "PROTECT_SOCKET", Network: network, Address: address}, fd, &ack); err != nil {
		return err
	}
	n.protected.Add(1)
	return nil
}

func validateTunnelDestination(address string) error {
	host, service, err := net.SplitHostPort(address)
	ip := net.ParseIP(host)
	port, portErr := strconv.Atoi(service)
	if err != nil || portErr != nil || port < 1 || port > 65535 || ip == nil || ip.To4() == nil || !egresspolicy.IsPublicIP(ip) {
		return egresspolicy.ErrDestination
	}
	return nil
}
