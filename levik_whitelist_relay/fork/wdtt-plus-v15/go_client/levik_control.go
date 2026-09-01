// SPDX-License-Identifier: GPL-3.0-only
// Levik VPN modification: local versioned control plane for the native client.

package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	levikControlVersion = 1
	maxControlMessage   = 64 << 10
)

var levikMode atomic.Bool
var errLevikServerKeyMismatch = errors.New("pinned server WireGuard key mismatch")

type levikControlInit struct {
	Version          int             `json:"version"`
	Type             string          `json:"type"`
	Peer             string          `json:"peer"`
	TurnHost         string          `json:"turnHost,omitempty"`
	TurnPort         string          `json:"turnPort,omitempty"`
	VKHashes         []string        `json:"vkHashes"`
	Password         string          `json:"password"`
	DeviceID         string          `json:"deviceId"`
	DeviceInfo       json.RawMessage `json:"deviceInfo,omitempty"`
	TransportSession string          `json:"transportSession,omitempty"`
	Workers          int             `json:"workers,omitempty"`
	CaptchaMode      string          `json:"captchaMode,omitempty"`
	TurnStreamFirst  bool            `json:"turnStreamFirst,omitempty"`
	TurnSNI          string          `json:"turnSni,omitempty"`
	Fingerprint      string          `json:"fingerprint,omitempty"`
	ClientIDs        string          `json:"clientIds,omitempty"`
	VKClientID       string          `json:"vkClientId,omitempty"`
	VKClientSecret   string          `json:"vkClientSecret,omitempty"`
	VKAuthMode       string          `json:"vkAuthMode,omitempty"`
	TunFDSocket      string          `json:"tunFdSocket"`
	ProtectFDSocket  string          `json:"protectFdSocket"`
	ServerPublicKey  string          `json:"serverPublicKey"`
}

type levikControlCommand struct {
	Version   int      `json:"version"`
	Type      string   `json:"type"`
	RequestID string   `json:"requestId,omitempty"`
	Value     string   `json:"value,omitempty"`
	Hash      string   `json:"hash,omitempty"`
	Username  string   `json:"username,omitempty"`
	Password  string   `json:"password,omitempty"`
	URLs      []string `json:"urls,omitempty"`
}

type levikControlEvent struct {
	Version int    `json:"version"`
	Type    string `json:"type"`
	Phase   string `json:"phase,omitempty"`
	Code    string `json:"code,omitempty"`
	Message string `json:"message,omitempty"`
	Data    any    `json:"data,omitempty"`
}

type levikControl struct {
	conn    *net.UnixConn
	decoder *json.Decoder
	writeMu sync.Mutex
}

func validateLevikSocketName(value string) error {
	if len(value) < 24 || len(value) > 107 || !strings.HasPrefix(value, "@levik_wlr_") {
		return errors.New("invalid abstract socket name")
	}
	for _, char := range value[1:] {
		if (char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z') ||
			(char >= '0' && char <= '9') || char == '_' || char == '-' {
			continue
		}
		return errors.New("invalid abstract socket name")
	}
	return nil
}

func acceptLevikControl(ctx context.Context, socketName string) (*levikControl, levikControlInit, error) {
	if err := validateLevikSocketName(socketName); err != nil {
		return nil, levikControlInit{}, err
	}
	address, err := net.ResolveUnixAddr("unix", socketName)
	if err != nil {
		return nil, levikControlInit{}, err
	}
	listener, err := net.ListenUnix("unix", address)
	if err != nil {
		return nil, levikControlInit{}, fmt.Errorf("control listen: %w", err)
	}
	defer listener.Close()
	var conn *net.UnixConn
	for {
		_ = listener.SetDeadline(time.Now().Add(time.Second))
		conn, err = listener.AcceptUnix()
		if err == nil {
			break
		}
		if ctx.Err() != nil {
			return nil, levikControlInit{}, ctx.Err()
		}
		if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
			continue
		}
		return nil, levikControlInit{}, fmt.Errorf("control accept: %w", err)
	}
	if err := verifyLocalPeer(conn, os.Getuid()); err != nil {
		conn.Close()
		return nil, levikControlInit{}, fmt.Errorf("control peer: %w", err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	decoder := json.NewDecoder(io.LimitReader(conn, maxControlMessage))
	decoder.DisallowUnknownFields()
	var init levikControlInit
	if err := decoder.Decode(&init); err != nil {
		conn.Close()
		return nil, levikControlInit{}, fmt.Errorf("control init: %w", err)
	}
	_ = conn.SetReadDeadline(time.Time{})
	if err := validateLevikInit(init); err != nil {
		conn.Close()
		return nil, levikControlInit{}, err
	}
	control := &levikControl{conn: conn, decoder: decoder}
	control.emit(levikControlEvent{Version: levikControlVersion, Type: "ready", Phase: "control"})
	return control, init, nil
}

func validateLevikInit(init levikControlInit) error {
	if init.Version != levikControlVersion || init.Type != "init" {
		return errors.New("unsupported control protocol")
	}
	if len(init.Peer) == 0 || len(init.Peer) > 255 || len(init.Password) < 16 || len(init.Password) > 256 {
		return errors.New("invalid control init")
	}
	if len(init.DeviceID) < 8 || len(init.DeviceID) > 128 || len(init.VKHashes) == 0 || len(init.VKHashes) > 4 {
		return errors.New("invalid control init")
	}
	for _, hash := range init.VKHashes {
		if len(hash) == 0 || len(hash) > 2048 || strings.ContainsAny(hash, "\x00\r\n") {
			return errors.New("invalid VK hash")
		}
	}
	if len(init.DeviceInfo) > 8<<10 || len(init.VKClientID) > 128 || len(init.VKClientSecret) > 512 {
		return errors.New("invalid control init")
	}
	if init.VKAuthMode != "" && init.VKAuthMode != "anonymous" && init.VKAuthMode != "account" {
		return errors.New("invalid VK auth mode")
	}
	if init.Workers != 0 && (init.Workers < 1 || init.Workers > 108) {
		return errors.New("invalid worker count")
	}
	if err := validateLevikSocketName(init.TunFDSocket); err != nil {
		return err
	}
	if init.ProtectFDSocket == init.TunFDSocket {
		return errors.New("control sockets must be distinct")
	}
	if !validPinnedServerKey(init.ServerPublicKey) {
		return errors.New("invalid pinned server key")
	}
	return validateLevikSocketName(init.ProtectFDSocket)
}

func validPinnedServerKey(value string) bool {
	_, err := decodePinnedWGKey(value)
	return err == nil
}

func decodePinnedWGKey(value string) ([]byte, error) {
	for _, encoding := range []*base64.Encoding{
		base64.RawURLEncoding,
		base64.URLEncoding,
		base64.RawStdEncoding,
		base64.StdEncoding,
	} {
		decoded, err := encoding.DecodeString(strings.TrimSpace(value))
		if err == nil && len(decoded) == 32 {
			return decoded, nil
		}
	}
	return nil, errors.New("invalid pinned WireGuard key")
}

func (c *levikControl) close() {
	if c != nil && c.conn != nil {
		_ = c.conn.Close()
	}
}

func (c *levikControl) emit(event levikControlEvent) {
	if c == nil || c.conn == nil {
		return
	}
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	_ = c.conn.SetWriteDeadline(time.Now().Add(3 * time.Second))
	_ = json.NewEncoder(c.conn).Encode(event)
	_ = c.conn.SetWriteDeadline(time.Time{})
}

func (c *levikControl) ready(phase string, data any) {
	c.emit(levikControlEvent{Version: levikControlVersion, Type: "ready", Phase: phase, Data: data})
}

func (c *levikControl) diagnostic(code string) {
	if _, allowed := levikDiagnosticCodes[code]; !allowed {
		return
	}
	c.emit(levikControlEvent{Version: levikControlVersion, Type: "diagnostic", Code: code})
}

var levikDiagnosticCodes = map[string]struct{}{
	"turn_credentials_received": {},
	"turn_tls_attempt":          {},
	"turn_tcp_attempt":          {},
	"turn_udp_attempt":          {},
	"turn_tls_failed":           {},
	"turn_tcp_failed":           {},
	"turn_udp_failed":           {},
	"turn_allocation_ready":     {},
	"dtls_handshake_started":    {},
	"dtls_handshake_failed":     {},
	"dtls_handshake_ready":      {},
	"relay_config_received":     {},
}

func (c *levikControl) fail(code string, err error) {
	// Error details from VK/TURN may contain join URLs, hashes or provider
	// metadata. Only a stable code crosses the IPC boundary; diagnostic details
	// remain in local logs where the Android layer applies its own redaction.
	_ = err
	message := "native transport operation failed"
	c.emit(levikControlEvent{Version: levikControlVersion, Type: "error", Code: code, Message: message})
}

func (c *levikControl) runCommands(ctx context.Context, handle func(levikControlCommand)) {
	for {
		_ = c.conn.SetReadDeadline(time.Now().Add(30 * time.Second))
		var command levikControlCommand
		err := c.decoder.Decode(&command)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
				continue
			}
			return
		}
		if command.Version != levikControlVersion {
			c.fail("bad_command_version", nil)
			continue
		}
		handle(command)
	}
}

func (c *levikControl) runStats(ctx context.Context, stats *Stats) {
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-ticker.C:
			c.emit(levikControlEvent{Version: levikControlVersion, Type: "stats", Data: map[string]any{
				"at":                         now.UTC().Format(time.RFC3339),
				"activeConnections":          stats.ActiveConnections.Load(),
				"bytesUp":                    stats.TotalBytesUp.Load(),
				"bytesDown":                  stats.TotalBytesDown.Load(),
				"protectedExternalSockets":   levikProtectedSocketCount(),
				"rejectedUnprotectedSockets": levikRejectedUnprotectedSocketCount(),
			}})
		}
	}
}
