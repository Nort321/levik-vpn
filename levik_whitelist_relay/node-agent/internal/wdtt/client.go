// SPDX-License-Identifier: AGPL-3.0-only

package wdtt

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

const maxAdminResponseBytes = 2 << 20

var (
	ErrAdminRejected = errors.New("WDTT admin request rejected")
	ErrBadSocket     = errors.New("invalid WDTT admin socket")
)

type Password struct {
	Password      string `json:"password"`
	Label         string `json:"label,omitempty"`
	VKHash        string `json:"vk_hash,omitempty"`
	Ports         string `json:"ports,omitempty"`
	Status        string `json:"status,omitempty"`
	ExpiresAt     int64  `json:"expires_at,omitempty"`
	PurgeAfter    int64  `json:"purge_after,omitempty"`
	IsDeactivated bool   `json:"-"`
}

type adminRequest struct {
	MainPassword string   `json:"main_password"`
	Args         []string `json:"args"`
}

type adminResponse struct {
	OK        bool       `json:"ok"`
	Code      string     `json:"code,omitempty"`
	Message   string     `json:"message,omitempty"`
	Password  *Password  `json:"password,omitempty"`
	Passwords []Password `json:"passwords,omitempty"`
}

type CommandError struct {
	Code string
}

func (e *CommandError) Error() string {
	if e.Code == "" {
		return ErrAdminRejected.Error()
	}
	return ErrAdminRejected.Error() + ": " + e.Code
}

func (e *CommandError) Unwrap() error { return ErrAdminRejected }

type Store interface {
	Ping(context.Context) error
	LookupLabel(context.Context, string) (Password, bool, error)
	Create(context.Context, CreateInput) (Password, error)
	SetExpiry(context.Context, string, int64) (Password, error)
	SetLabel(context.Context, string, string) (Password, error)
	SetPassword(context.Context, string, string) (Password, error)
	Activate(context.Context, string) (Password, error)
	Deactivate(context.Context, string) (Password, error)
}

type CreateInput struct {
	Password   string
	Label      string
	ExpiresAt  int64
	PurgeAfter int64
	Ports      string
}

type UnixClient struct {
	SocketPath   string
	MainPassword string
	Timeout      time.Duration
}

func (c *UnixClient) validateSocket() error {
	info, err := os.Lstat(c.SocketPath)
	if err != nil {
		return fmt.Errorf("%w: unavailable", ErrBadSocket)
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if info.Mode()&os.ModeSocket == 0 || info.Mode().Perm() != 0600 || !ok || stat.Uid != 0 || stat.Nlink != 1 {
		return ErrBadSocket
	}
	parent := filepath.Dir(filepath.Clean(c.SocketPath))
	canonicalParent, err := filepath.EvalSymlinks(parent)
	if err != nil || canonicalParent != parent {
		return ErrBadSocket
	}
	parentInfo, err := os.Lstat(parent)
	if err != nil {
		return ErrBadSocket
	}
	parentStat, statOK := parentInfo.Sys().(*syscall.Stat_t)
	if !parentInfo.IsDir() || parentInfo.Mode().Perm() != 0700 || !statOK || parentStat.Uid != 0 {
		return ErrBadSocket
	}
	return nil
}

func (c *UnixClient) call(ctx context.Context, args ...string) (adminResponse, error) {
	if err := c.validateSocket(); err != nil {
		return adminResponse{}, err
	}
	if strings.TrimSpace(c.MainPassword) == "" {
		return adminResponse{}, errors.New("WDTT admin secret is empty")
	}
	timeout := c.Timeout
	if timeout <= 0 {
		timeout = 8 * time.Second
	}
	dialer := net.Dialer{Timeout: timeout}
	conn, err := dialer.DialContext(ctx, "unix", c.SocketPath)
	if err != nil {
		return adminResponse{}, fmt.Errorf("WDTT admin unavailable: %w", err)
	}
	defer conn.Close()
	if err := c.validateSocket(); err != nil {
		return adminResponse{}, err
	}
	deadline := time.Now().Add(timeout)
	if current, ok := ctx.Deadline(); ok && current.Before(deadline) {
		deadline = current
	}
	_ = conn.SetDeadline(deadline)
	request := adminRequest{MainPassword: c.MainPassword, Args: append([]string(nil), args...)}
	if err := json.NewEncoder(conn).Encode(request); err != nil {
		return adminResponse{}, fmt.Errorf("WDTT admin request: %w", err)
	}
	var response adminResponse
	decoder := json.NewDecoder(io.LimitReader(conn, maxAdminResponseBytes))
	if err := decoder.Decode(&response); err != nil {
		return adminResponse{}, fmt.Errorf("WDTT admin response: %w", err)
	}
	if !response.OK {
		return adminResponse{}, &CommandError{Code: response.Code}
	}
	return response, nil
}

func (c *UnixClient) List(ctx context.Context) ([]Password, error) {
	response, err := c.call(ctx, "list")
	if err != nil {
		return nil, err
	}
	return response.Passwords, nil
}

func (c *UnixClient) Ping(ctx context.Context) error {
	_, err := c.call(ctx, "ping")
	return err
}

func (c *UnixClient) LookupLabel(ctx context.Context, label string) (Password, bool, error) {
	response, err := c.call(ctx, "lookup-label", "--label", label)
	if err != nil {
		var commandErr *CommandError
		if errors.As(err, &commandErr) && commandErr.Code == "not_found" {
			return Password{}, false, nil
		}
		return Password{}, false, err
	}
	if response.Password == nil {
		return Password{}, false, errors.New("WDTT lookup returned no lease")
	}
	return *response.Password, true, nil
}

func (c *UnixClient) Create(ctx context.Context, input CreateInput) (Password, error) {
	if input.ExpiresAt <= 0 || input.PurgeAfter <= input.ExpiresAt {
		return Password{}, errors.New("WDTT create retention is invalid")
	}
	args := []string{
		"create",
		"--client-password", input.Password,
		"--label", input.Label,
		"--expires-at", fmt.Sprintf("%d", input.ExpiresAt),
		"--purge-after", fmt.Sprintf("%d", input.PurgeAfter),
	}
	if input.Ports != "" {
		args = append(args, "--ports", input.Ports)
	}
	response, err := c.call(ctx, args...)
	if err != nil {
		return Password{}, err
	}
	if response.Password == nil {
		return Password{}, errors.New("WDTT create returned no credential")
	}
	return *response.Password, nil
}

func (c *UnixClient) mutate(ctx context.Context, args ...string) (Password, error) {
	response, err := c.call(ctx, args...)
	if err != nil {
		return Password{}, err
	}
	if response.Password == nil {
		return Password{}, errors.New("WDTT mutation returned no lease")
	}
	return *response.Password, nil
}

func (c *UnixClient) SetExpiry(ctx context.Context, password string, expiresAt int64) (Password, error) {
	return c.mutate(ctx, "set-expiry", "--password", password, "--expires-at", fmt.Sprintf("%d", expiresAt))
}

func (c *UnixClient) SetLabel(ctx context.Context, password, label string) (Password, error) {
	return c.mutate(ctx, "set-label", "--password", password, "--label", label)
}

func (c *UnixClient) SetPassword(ctx context.Context, password, replacement string) (Password, error) {
	return c.mutate(ctx, "set-password", "--password", password, "--new-password", replacement)
}

func (c *UnixClient) Activate(ctx context.Context, password string) (Password, error) {
	return c.mutate(ctx, "activate", "--password", password)
}

func (c *UnixClient) Deactivate(ctx context.Context, password string) (Password, error) {
	return c.mutate(ctx, "deactivate", "--password", password)
}
