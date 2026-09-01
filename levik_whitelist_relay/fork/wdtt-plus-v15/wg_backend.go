package main

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"os/exec"
	"strconv"
	"strings"
	"sync"
)

type wgDevice interface {
	IpcSet(string) error
	Close()
}

type kernelWGDevice struct {
	iface string
	mu    sync.Mutex
}

func (d *kernelWGDevice) Close() {
	if d == nil || d.iface == "" {
		return
	}
	_, _ = runCmd("ip", "link", "del", d.iface)
}

func (d *kernelWGDevice) IpcSet(configuration string) error {
	if d == nil || d.iface == "" {
		return errors.New("kernel WireGuard is not initialized")
	}
	d.mu.Lock()
	defer d.mu.Unlock()

	values := make(map[string]string)
	for _, line := range strings.Split(configuration, "\n") {
		key, value, ok := strings.Cut(strings.TrimSpace(line), "=")
		if ok {
			values[key] = value
		}
	}
	publicHex := strings.TrimSpace(values["public_key"])
	if publicHex == "" {
		return errors.New("kernel WireGuard peer update has no public key")
	}
	publicKey, err := wireGuardHexToBase64(publicHex)
	if err != nil {
		return fmt.Errorf("kernel WireGuard public key: %w", err)
	}
	args := []string{"set", d.iface, "peer", publicKey}
	if values["remove"] == "true" {
		args = append(args, "remove")
	} else if allowedIP := strings.TrimSpace(values["allowed_ip"]); allowedIP != "" {
		args = append(args, "allowed-ips", allowedIP)
	} else {
		return errors.New("kernel WireGuard peer update has no operation")
	}
	if output, err := runCmd("wg", args...); err != nil {
		return fmt.Errorf("wg peer update: %s", output)
	}
	return nil
}

func wireGuardHexToBase64(value string) (string, error) {
	raw, err := hex.DecodeString(value)
	if err != nil {
		return "", err
	}
	if len(raw) != 32 {
		return "", fmt.Errorf("key length %d != 32", len(raw))
	}
	return base64.StdEncoding.EncodeToString(raw), nil
}

type kernelWGCommandRunner func(name string, args []string, stdin []byte) (string, error)

func runCommandWithStdin(name string, args []string, stdin []byte) (string, error) {
	cmd := exec.Command(name, args...)
	cmd.Stdin = bytes.NewReader(stdin)
	out, err := cmd.CombinedOutput()
	return strings.TrimSpace(string(out)), err
}

func redactKernelWGError(output string, commandErr error, privateKey string) string {
	detail := strings.TrimSpace(output)
	if detail == "" && commandErr != nil {
		detail = commandErr.Error()
	}
	if privateKey != "" {
		detail = strings.ReplaceAll(detail, privateKey, "[REDACTED]")
	}
	if detail == "" {
		return "wg command failed"
	}
	return detail
}

func configureKernelWG(privateKey string, wgPort int, runner kernelWGCommandRunner) error {
	if _, err := b64ToHex(privateKey); err != nil {
		return fmt.Errorf("invalid WireGuard private key: %w", err)
	}
	if wgPort < 1 || wgPort > 65535 {
		return fmt.Errorf("invalid WireGuard listen port %d", wgPort)
	}
	if runner == nil {
		return errors.New("kernel WireGuard command runner is unavailable")
	}

	// wg reads the private key synchronously from fd 0. This deliberately avoids
	// both a persistent/runtime key file and exposing the key in process argv.
	keyInput := []byte(privateKey + "\n")
	defer clear(keyInput)
	args := []string{
		"set", wgIfaceName,
		"private-key", "/dev/stdin",
		"listen-port", strconv.Itoa(wgPort),
	}
	output, err := runner("wg", args, keyInput)
	if err != nil {
		return errors.New(redactKernelWGError(output, err, privateKey))
	}
	return nil
}

func startKernelWG(keys *wgKeys, wgPort int, manageHostNetwork bool) (wgDevice, error) {
	if !commandExists("wg") || !commandExists("ip") {
		return nil, errors.New("commands ip/wg are not installed")
	}
	_, _ = runCmd("ip", "link", "del", wgIfaceName)
	if output, err := runCmd("ip", "link", "add", wgIfaceName, "type", "wireguard"); err != nil {
		return nil, fmt.Errorf("kernel WireGuard unavailable: %s", output)
	}
	dev := &kernelWGDevice{iface: wgIfaceName}
	ok := false
	defer func() {
		if !ok {
			dev.Close()
		}
	}()

	if err := configureKernelWG(keys.serverPrivate, wgPort, runCommandWithStdin); err != nil {
		return nil, fmt.Errorf("configure kernel WireGuard: %w", err)
	}
	if err := configureInterface(wgIfaceName); err != nil {
		return nil, err
	}
	var networkErr error
	if manageHostNetwork {
		networkErr = setupFullConeNAT(wgIfaceName)
	} else {
		networkErr = verifyHostNetwork(wgIfaceName)
	}
	if networkErr != nil {
		return nil, networkErr
	}
	ok = true
	logWGBackend("kernel")
	return dev, nil
}

func startWGBackend(mode string, keys *wgKeys, wgPort int, manageHostNetwork bool) (wgDevice, error) {
	mode = strings.ToLower(strings.TrimSpace(mode))
	if mode == "" {
		mode = "auto"
	}
	switch mode {
	case "kernel":
		return startKernelWG(keys, wgPort, manageHostNetwork)
	case "userspace":
		dev, err := startUserspaceWG(keys, wgPort, manageHostNetwork)
		if err == nil {
			logWGBackend("userspace")
		}
		return dev, err
	case "auto":
		if dev, err := startKernelWG(keys, wgPort, manageHostNetwork); err == nil {
			return dev, nil
		} else {
			fmt.Printf("[WG] Kernel backend недоступен, использую userspace: %v\n", err)
		}
		dev, err := startUserspaceWG(keys, wgPort, manageHostNetwork)
		if err == nil {
			logWGBackend("userspace")
		}
		return dev, err
	default:
		return nil, fmt.Errorf("unknown WireGuard backend %q", mode)
	}
}

var activeWGBackend atomicString

type atomicString struct {
	mu    sync.RWMutex
	value string
}

func (s *atomicString) Store(value string) {
	s.mu.Lock()
	s.value = value
	s.mu.Unlock()
}

func (s *atomicString) Load() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.value
}

func logWGBackend(name string) {
	activeWGBackend.Store(name)
	fmt.Printf("[WG] Backend: %s\n", name)
}
