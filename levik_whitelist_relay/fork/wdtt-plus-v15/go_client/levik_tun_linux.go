// SPDX-License-Identifier: GPL-3.0-only
//go:build linux || android

//
// Derived in part from qWDTT go_client/tun_fd.go (GPL-3.0).
// Levik changes: peer UID verification, exact-FD validation, deadlines and
// explicit ownership transfer to wireguard-go.

package main

import (
	"context"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
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

func startLevikWireGuard(
	ctx context.Context,
	rawConfig string,
	pinnedServerKey, proxyUsername, proxyPassword string,
	control *levikControl,
) error {
	config, err := parseLevikWGConfig(rawConfig)
	if err != nil {
		return err
	}
	pinned, err := decodePinnedWGKey(pinnedServerKey)
	actual, decodeErr := hex.DecodeString(config.peerPublicHex)
	if err != nil || decodeErr != nil || len(pinned) != 32 || len(actual) != 32 || subtle.ConstantTimeCompare(pinned, actual) != 1 {
		return errLevikServerKeyMismatch
	}
	return startLevikSocksDataPlane(
		ctx,
		config,
		proxyUsername,
		proxyPassword,
		control,
	)
}
