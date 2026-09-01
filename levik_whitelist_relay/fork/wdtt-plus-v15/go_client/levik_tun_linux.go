// SPDX-License-Identifier: GPL-3.0-only
//go:build linux || android

//
// Derived in part from qWDTT go_client/tun_fd.go (GPL-3.0).
// Levik changes: peer UID verification, exact-FD validation, deadlines and
// explicit ownership transfer to wireguard-go.

package main

import (
	"bytes"
	"context"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"time"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

type levikWGConfig struct {
	privateKeyHex string
	addresses     []string
	dns           []string
	mtu           int
	peerPublicHex string
	allowedIPs    []string
	endpoint      string
	keepalive     uint16
}

type levikTunPlan struct {
	Addresses []string `json:"addresses"`
	DNS       []string `json:"dns"`
	MTU       int      `json:"mtu"`
	Routes    []string `json:"routes"`
}

func decodeWGKey(value string) (string, error) {
	for _, encoding := range []*base64.Encoding{
		base64.RawURLEncoding,
		base64.URLEncoding,
		base64.RawStdEncoding,
		base64.StdEncoding,
	} {
		raw, err := encoding.DecodeString(strings.TrimSpace(value))
		if err == nil && len(raw) == 32 {
			return hex.EncodeToString(raw), nil
		}
	}
	return "", errors.New("invalid WireGuard key")
}

func parseLevikWGConfig(raw string) (levikWGConfig, error) {
	config := levikWGConfig{mtu: 1280, keepalive: 25}
	section := ""
	for _, rawLine := range strings.Split(raw, "\n") {
		line := strings.TrimSpace(rawLine)
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = strings.ToLower(strings.TrimSpace(line[1 : len(line)-1]))
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		key, value = strings.ToLower(strings.TrimSpace(key)), strings.TrimSpace(value)
		switch section {
		case "interface":
			switch key {
			case "privatekey":
				decoded, err := decodeWGKey(value)
				if err != nil {
					return levikWGConfig{}, err
				}
				config.privateKeyHex = decoded
			case "address":
				for _, item := range strings.Split(value, ",") {
					prefix, err := netip.ParsePrefix(strings.TrimSpace(item))
					if err != nil || !prefix.Addr().Is4() {
						return levikWGConfig{}, errors.New("invalid IPv4 tunnel address")
					}
					config.addresses = append(config.addresses, prefix.String())
				}
			case "dns":
				for _, item := range strings.Split(value, ",") {
					address, err := netip.ParseAddr(strings.TrimSpace(item))
					if err != nil || !address.Is4() {
						return levikWGConfig{}, errors.New("invalid IPv4 DNS address")
					}
					config.dns = append(config.dns, address.String())
				}
			case "mtu":
				mtu, err := strconv.Atoi(value)
				if err != nil || mtu < 576 || mtu > 1500 {
					return levikWGConfig{}, errors.New("invalid tunnel MTU")
				}
				config.mtu = mtu
			}
		case "peer":
			switch key {
			case "publickey":
				decoded, err := decodeWGKey(value)
				if err != nil {
					return levikWGConfig{}, err
				}
				config.peerPublicHex = decoded
			case "allowedips":
				for _, item := range strings.Split(value, ",") {
					prefix, err := netip.ParsePrefix(strings.TrimSpace(item))
					if err != nil || !prefix.Addr().Is4() {
						return levikWGConfig{}, errors.New("invalid IPv4 route")
					}
					config.allowedIPs = append(config.allowedIPs, prefix.String())
				}
			case "endpoint":
				if _, _, err := net.SplitHostPort(value); err != nil {
					return levikWGConfig{}, errors.New("invalid WireGuard endpoint")
				}
				config.endpoint = value
			case "persistentkeepalive":
				keepalive, err := strconv.Atoi(value)
				if err != nil || keepalive < 0 || keepalive > 65535 {
					return levikWGConfig{}, errors.New("invalid keepalive")
				}
				config.keepalive = uint16(keepalive)
			}
		}
	}
	if config.privateKeyHex == "" || config.peerPublicHex == "" || len(config.addresses) == 0 || config.endpoint == "" || len(config.allowedIPs) == 0 {
		return levikWGConfig{}, errors.New("incomplete WireGuard config")
	}
	if len(config.dns) == 0 {
		config.dns = []string{"1.1.1.1"}
	}
	return config, nil
}

func (c levikWGConfig) ipcRequest() string {
	var builder strings.Builder
	fmt.Fprintf(&builder, "private_key=%s\nreplace_peers=true\n", c.privateKeyHex)
	fmt.Fprintf(&builder, "public_key=%s\nendpoint=%s\npersistent_keepalive_interval=%d\n", c.peerPublicHex, c.endpoint, c.keepalive)
	for _, route := range c.allowedIPs {
		fmt.Fprintf(&builder, "allowed_ip=%s\n", route)
	}
	return builder.String()
}

func receiveLevikTunFD(ctx context.Context, socketName string) (int, error) {
	address, err := net.ResolveUnixAddr("unix", socketName)
	if err != nil {
		return -1, err
	}
	listener, err := net.ListenUnix("unix", address)
	if err != nil {
		return -1, err
	}
	defer listener.Close()
	for {
		_ = listener.SetDeadline(time.Now().Add(time.Second))
		conn, acceptErr := listener.AcceptUnix()
		if acceptErr != nil {
			if ctx.Err() != nil {
				return -1, ctx.Err()
			}
			if netErr, ok := acceptErr.(net.Error); ok && netErr.Timeout() {
				continue
			}
			return -1, acceptErr
		}
		if err := verifyLocalPeer(conn, os.Getuid()); err != nil {
			conn.Close()
			return -1, err
		}
		_ = conn.SetReadDeadline(time.Now().Add(30 * time.Second))
		payload := make([]byte, 16)
		oob := make([]byte, unix.CmsgSpace(4*4))
		payloadCount, oobCount, flags, _, err := conn.ReadMsgUnix(payload, oob)
		conn.Close()
		if err != nil {
			return -1, err
		}
		if flags&unix.MSG_CTRUNC != 0 || !bytes.Equal(payload[:payloadCount], []byte("TUN_FD_V1\n")) {
			return -1, errors.New("invalid TUN fd message")
		}
		messages, err := unix.ParseSocketControlMessage(oob[:oobCount])
		if err != nil || len(messages) != 1 {
			return -1, errors.New("expected exactly one SCM_RIGHTS message")
		}
		fds, err := unix.ParseUnixRights(&messages[0])
		if err != nil || len(fds) != 1 {
			for _, fd := range fds {
				_ = unix.Close(fd)
			}
			return -1, errors.New("expected exactly one TUN fd")
		}
		return fds[0], nil
	}
}

func startLevikWireGuard(ctx context.Context, rawConfig, tunSocket, pinnedServerKey string, control *levikControl) error {
	config, err := parseLevikWGConfig(rawConfig)
	if err != nil {
		return err
	}
	pinned, err := decodePinnedWGKey(pinnedServerKey)
	actual, decodeErr := hex.DecodeString(config.peerPublicHex)
	if err != nil || decodeErr != nil || len(pinned) != 32 || len(actual) != 32 || subtle.ConstantTimeCompare(pinned, actual) != 1 {
		return errLevikServerKeyMismatch
	}
	control.emit(levikControlEvent{Version: levikControlVersion, Type: "tun_plan", Phase: "PREPARED", Data: levikTunPlan{
		Addresses: append([]string(nil), config.addresses...),
		DNS:       append([]string(nil), config.dns...),
		MTU:       config.mtu,
		Routes:    append([]string(nil), config.allowedIPs...),
	}})
	fdContext, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	fd, err := receiveLevikTunFD(fdContext, tunSocket)
	if err != nil {
		return fmt.Errorf("TUN fd: %w", err)
	}
	tunDevice, _, err := tun.CreateUnmonitoredTUNFromFD(fd)
	if err != nil {
		_ = unix.Close(fd)
		return fmt.Errorf("TUN attach: %w", err)
	}
	control.ready("FD_ATTACHED", nil)
	wgDevice := device.NewDevice(tunDevice, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "[LEVIK-WG] "))
	if err := wgDevice.IpcSet(config.ipcRequest()); err != nil {
		wgDevice.Close()
		return fmt.Errorf("WireGuard configure: %w", err)
	}
	if err := wgDevice.Up(); err != nil {
		wgDevice.Close()
		return fmt.Errorf("WireGuard start: %w", err)
	}
	if levikProtectedSocketCount() == 0 {
		wgDevice.Close()
		return errors.New("no external socket received Android protect/bind ACK")
	}
	context.AfterFunc(ctx, wgDevice.Close)
	control.ready("RUNNING", map[string]any{"protocolVersion": levikControlVersion})
	return nil
}
