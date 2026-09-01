// SPDX-License-Identifier: AGPL-3.0-only

package main

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/audit"
	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/authn"
	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/httpapi"
	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/lease"
	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/securefile"
	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/statefile"
	"github.com/leviknet/levik-whitelist-relay/node-agent/internal/wdtt"
)

const version = "1.0.0"

func decodeKey(value []byte) ([]byte, error) {
	text := strings.TrimSpace(string(value))
	decoders := []func(string) ([]byte, error){
		hex.DecodeString,
		base64.RawStdEncoding.DecodeString,
		base64.StdEncoding.DecodeString,
		base64.RawURLEncoding.DecodeString,
		base64.URLEncoding.DecodeString,
	}
	for _, decode := range decoders {
		decoded, err := decode(text)
		if err == nil && len(decoded) >= 32 {
			return decoded, nil
		}
	}
	return nil, errors.New("key must encode at least 32 bytes")
}

func requireLoopback(address string) error {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return err
	}
	ip := net.ParseIP(strings.Trim(host, "[]"))
	if ip == nil || !ip.IsLoopback() {
		return errors.New("node-agent must bind to a numeric loopback address")
	}
	return nil
}

func main() {
	listen := flag.String("listen", "127.0.0.1:9088", "loopback HTTP listen address")
	adminSocket := flag.String("wdtt-admin-socket", "/run/levik-relay/admin.sock", "local WDTT Unix admin socket")
	adminSecretFile := flag.String("wdtt-main-password-file", "", "absolute root-only WDTT master secret file")
	hmacKeyFile := flag.String("hmac-key-file", "", "absolute root-only control-plane HMAC key file")
	hmacKeyID := flag.String("hmac-key-id", "control-v1", "non-secret key identifier")
	credentialKeyFile := flag.String("credential-key-file", "", "absolute root-only credential derivation key file")
	stateFile := flag.String("state-file", "/var/lib/levik-relay-agent/state.json", "absolute crash-safe agent state file")
	ports := flag.String("wdtt-ports", "56000,56001,9000", "fixed WDTT port tuple assigned to leases")
	retentionGrace := flag.Duration("retention-grace", lease.DefaultRetentionGrace, "expired credential retention for renewal/reconciliation")
	requestTimeout := flag.Duration("request-timeout", 10*time.Second, "per-request timeout")
	maxClockSkew := flag.Duration("max-clock-skew", 2*time.Minute, "maximum HMAC timestamp skew")
	nonceTTL := flag.Duration("nonce-ttl", 5*time.Minute, "accepted nonce retention")
	requestRate := flag.Float64("request-rate", 20, "authenticated requests per second per key")
	requestBurst := flag.Int("request-burst", 40, "authenticated request burst per key")
	showVersion := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	if *showVersion {
		fmt.Println(version)
		return
	}
	if flag.NArg() != 0 {
		log.Fatal("positional arguments are not accepted")
	}
	if err := requireLoopback(*listen); err != nil {
		log.Fatalf("unsafe listen address: %v", err)
	}
	if *requestTimeout < time.Second || *requestTimeout > 30*time.Second || *requestRate <= 0 || *requestBurst < 1 || *requestBurst > 1000 {
		log.Fatal("invalid runtime limits")
	}

	adminSecret, err := securefile.Read(*adminSecretFile)
	if err != nil {
		log.Fatalf("WDTT secret file rejected: %v", err)
	}
	hmacEncoded, err := securefile.Read(*hmacKeyFile)
	if err != nil {
		log.Fatalf("HMAC key file rejected: %v", err)
	}
	hmacKey, err := decodeKey(hmacEncoded)
	if err != nil {
		log.Fatalf("HMAC key rejected: %v", err)
	}
	credentialEncoded, err := securefile.Read(*credentialKeyFile)
	if err != nil {
		log.Fatalf("credential key file rejected: %v", err)
	}
	credentialKey, err := decodeKey(credentialEncoded)
	if err != nil {
		log.Fatalf("credential key rejected: %v", err)
	}

	admin := &wdtt.UnixClient{SocketPath: *adminSocket, MainPassword: string(adminSecret), Timeout: *requestTimeout}
	durableState, err := statefile.Open(*stateFile)
	if err != nil {
		log.Fatalf("state store rejected: %v", err)
	}
	service, err := lease.New(admin, durableState, *ports, credentialKey, *retentionGrace)
	if err != nil {
		log.Fatalf("lease service configuration rejected: %v", err)
	}
	replay := authn.NewReplayCache(*nonceTTL, 100000)
	limiter := authn.NewLimiter(*requestRate, *requestBurst)
	verifier, err := authn.NewVerifier(map[string][]byte{*hmacKeyID: hmacKey}, *maxClockSkew, 16<<10, replay, limiter)
	if err != nil {
		log.Fatalf("authentication configuration rejected: %v", err)
	}
	handler := httpapi.New(service, verifier, audit.New(os.Stdout))
	server := &http.Server{
		Addr:              *listen,
		Handler:           handler,
		ReadHeaderTimeout: 3 * time.Second,
		ReadTimeout:       *requestTimeout + time.Second,
		WriteTimeout:      *requestTimeout + time.Second,
		IdleTimeout:       30 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	listener, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatalf("listen failed: %v", err)
	}
	log.Printf("levik-relay-agent v%s ready on loopback", version)
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdownCtx)
	}()
	if err := server.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("HTTP server failed: %v", err)
	}
}
