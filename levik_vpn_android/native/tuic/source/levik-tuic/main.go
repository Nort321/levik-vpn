// Command levik-tuic is the Levik VPN TUIC v5 sidecar for Android.
//
// It is built inside the digest-locked sing-box source tree against that
// release's go.mod/go.sum, but links only the TUIC client (sing-quic) and the
// SOCKS5 server from sing instead of the whole sing-box runtime. It accepts the
// subset of the sing-box configuration format the Android app writes: one
// authenticated loopback SOCKS5 inbound and one TUIC outbound with a pinned CA
// and a protect_path through which every outbound socket is protected.
//
// Usage: levik-tuic run -c stdin|PATH [--disable-color]
package main

import (
	std_bufio "bufio"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/gofrs/uuid/v5"
	"github.com/sagernet/sing-quic/tuic"
	"github.com/sagernet/sing/common/auth"
	"github.com/sagernet/sing/common/bufio"
	"github.com/sagernet/sing/common/control"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	aTLS "github.com/sagernet/sing/common/tls"
	"github.com/sagernet/sing/protocol/socks"
)

const (
	maxConfigBytes = 256 << 10
	udpTimeout     = 5 * time.Minute
	dialTimeout    = 15 * time.Second
)

type config struct {
	Log struct {
		Level string `json:"level"`
	} `json:"log"`
	Inbounds []struct {
		Type       string `json:"type"`
		Listen     string `json:"listen"`
		ListenPort uint16 `json:"listen_port"`
		Users      []struct {
			Username string `json:"username"`
			Password string `json:"password"`
		} `json:"users"`
	} `json:"inbounds"`
	Outbounds []struct {
		Type              string `json:"type"`
		Server            string `json:"server"`
		ServerPort        uint16 `json:"server_port"`
		UUID              string `json:"uuid"`
		Password          string `json:"password"`
		CongestionControl string `json:"congestion_control"`
		UDPRelayMode      string `json:"udp_relay_mode"`
		Heartbeat         string `json:"heartbeat"`
		ProtectPath       string `json:"protect_path"`
		TLS               struct {
			Enabled     bool     `json:"enabled"`
			ServerName  string   `json:"server_name"`
			ALPN        []string `json:"alpn"`
			Certificate []string `json:"certificate"`
		} `json:"tls"`
	} `json:"outbounds"`
}

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "FATAL", err)
		os.Exit(1)
	}
}

func run(args []string) error {
	path, err := configPath(args)
	if err != nil {
		return err
	}
	var source io.Reader = os.Stdin
	if path != "stdin" {
		file, err := os.Open(path)
		if err != nil {
			return err
		}
		defer file.Close()
		source = file
	}
	raw, err := io.ReadAll(io.LimitReader(source, maxConfigBytes+1))
	if err != nil {
		return err
	}
	if len(raw) > maxConfigBytes {
		return errors.New("configuration is too large")
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	service, err := newService(ctx, raw)
	clear(raw)
	if err != nil {
		return err
	}
	return service.serve(ctx)
}

func configPath(args []string) (string, error) {
	if len(args) < 3 || args[0] != "run" || args[1] != "-c" || args[2] == "" {
		return "", errors.New("usage: levik-tuic run -c stdin|PATH [--disable-color]")
	}
	for _, extra := range args[3:] {
		if extra != "--disable-color" {
			return "", fmt.Errorf("unknown argument %q", extra)
		}
	}
	return args[2], nil
}

type service struct {
	listener      net.Listener
	authenticator *auth.Authenticator
	client        *tuic.Client
	verbose       bool
}

func newService(ctx context.Context, raw []byte) (*service, error) {
	var options config
	if err := json.Unmarshal(raw, &options); err != nil {
		return nil, fmt.Errorf("parse configuration: %w", err)
	}
	if len(options.Inbounds) != 1 || len(options.Outbounds) != 1 {
		return nil, errors.New("exactly one inbound and one outbound are required")
	}
	inbound, outbound := options.Inbounds[0], options.Outbounds[0]
	listen, err := netip.ParseAddr(inbound.Listen)
	if inbound.Type != "socks" || err != nil || !listen.IsLoopback() || inbound.ListenPort == 0 || len(inbound.Users) != 1 ||
		inbound.Users[0].Username == "" || inbound.Users[0].Password == "" {
		return nil, errors.New("inbound must be one authenticated loopback socks listener")
	}
	server, err := netip.ParseAddr(outbound.Server)
	if outbound.Type != "tuic" || err != nil || outbound.ServerPort == 0 || outbound.Password == "" {
		return nil, errors.New("outbound must be a tuic server with an IP address")
	}
	userUUID, err := uuid.FromString(outbound.UUID)
	if err != nil {
		return nil, errors.New("invalid tuic uuid")
	}
	var udpStream bool
	switch outbound.UDPRelayMode {
	case "", "native":
	case "quic":
		udpStream = true
	default:
		return nil, errors.New("invalid udp_relay_mode")
	}
	heartbeat := 10 * time.Second
	if outbound.Heartbeat != "" {
		if heartbeat, err = time.ParseDuration(outbound.Heartbeat); err != nil || heartbeat <= 0 {
			return nil, errors.New("invalid heartbeat")
		}
	}
	tlsConfig, err := newTLSConfig(outbound.TLS.Enabled, outbound.TLS.ServerName, outbound.TLS.ALPN, outbound.TLS.Certificate)
	if err != nil {
		return nil, err
	}
	client, err := tuic.NewClient(tuic.ClientOptions{
		Context:           ctx,
		Dialer:            protectedDialer{control: control.ProtectPath(outbound.ProtectPath), protect: outbound.ProtectPath != ""},
		ServerAddress:     M.SocksaddrFrom(server.Unmap(), outbound.ServerPort),
		TLSConfig:         tlsConfig,
		UUID:              userUUID,
		Password:          outbound.Password,
		CongestionControl: outbound.CongestionControl,
		UDPStream:         udpStream,
		Heartbeat:         heartbeat,
	})
	if err != nil {
		return nil, err
	}
	listener, err := net.ListenTCP("tcp", net.TCPAddrFromAddrPort(netip.AddrPortFrom(listen, inbound.ListenPort)))
	if err != nil {
		return nil, err
	}
	level := strings.ToLower(options.Log.Level)
	return &service{
		listener:      listener,
		authenticator: auth.NewAuthenticator([]auth.User{{Username: inbound.Users[0].Username, Password: inbound.Users[0].Password}}),
		client:        client,
		verbose:       level == "debug" || level == "info" || level == "trace",
	}, nil
}

func (s *service) serve(ctx context.Context) error {
	go func() {
		<-ctx.Done()
		_ = s.listener.Close()
		_ = s.client.CloseWithError(os.ErrClosed)
	}()
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			var netErr net.Error
			if errors.As(err, &netErr) && netErr.Timeout() {
				continue
			}
			return err
		}
		go s.handle(ctx, conn)
	}
}

func (s *service) handle(ctx context.Context, conn net.Conn) {
	onClose := func(error) {}
	err := socks.HandleConnectionEx(ctx, conn, std_bufio.NewReader(conn), s.authenticator, s, s, udpTimeout, M.SocksaddrFromNet(conn.RemoteAddr()), onClose)
	N.CloseOnHandshakeFailure(conn, onClose, err)
	if err != nil && s.verbose {
		fmt.Fprintln(os.Stderr, "ERROR socks:", err)
	}
}

// ListenPacket opens the SOCKS5 UDP relay socket. It stays on loopback and is never protected.
func (s *service) ListenPacket(listenConfig net.ListenConfig, ctx context.Context, network string, address string) (net.PacketConn, error) {
	return listenConfig.ListenPacket(ctx, network, address)
}

func (s *service) NewConnectionEx(ctx context.Context, conn net.Conn, _ M.Socksaddr, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	remote, err := s.client.DialConn(ctx, destination)
	if err == nil {
		// TUIC sends its CONNECT header lazily with the first payload. Send it now
		// so server-first protocols (SMTP, SSH banners) do not wait for the client.
		_, err = remote.Write(nil)
		if err != nil {
			remote.Close()
		}
	}
	if err != nil {
		N.CloseOnHandshakeFailure(conn, onClose, err)
		s.logError("open connection", err)
		return
	}
	if err = N.ReportConnHandshakeSuccess(conn, remote); err != nil {
		conn.Close()
		remote.Close()
		return
	}
	err = bufio.CopyConn(ctx, conn, remote)
	onClose(err)
}

func (s *service) NewPacketConnectionEx(ctx context.Context, conn N.PacketConn, _ M.Socksaddr, _ M.Socksaddr, onClose N.CloseHandlerFunc) {
	remote, err := s.client.ListenPacket(ctx)
	if err != nil {
		N.CloseOnHandshakeFailure(conn, onClose, err)
		s.logError("open packet connection", err)
		return
	}
	if err = N.ReportPacketConnHandshakeSuccess(conn, remote); err != nil {
		conn.Close()
		remote.Close()
		return
	}
	err = bufio.CopyPacketConn(ctx, conn, bufio.NewPacketConn(remote))
	onClose(err)
}

func (s *service) logError(operation string, err error) {
	if s.verbose {
		fmt.Fprintln(os.Stderr, "ERROR", operation+":", err)
	}
}

// protectedDialer opens the QUIC socket to the TUIC server. Every socket is
// handed to the app over protect_path before use, so it bypasses the VPN.
type protectedDialer struct {
	control control.Func
	protect bool
}

func (d protectedDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if N.NetworkName(network) != N.NetworkUDP || !destination.IsIP() {
		return nil, errors.New("only UDP to an IP address is supported")
	}
	dialer := net.Dialer{Timeout: dialTimeout}
	if d.protect {
		dialer.Control = d.control
	}
	return dialer.DialContext(ctx, "udp", destination.String())
}

func (d protectedDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("unsupported")
}

// pinnedTLS trusts only the CA pinned by the Levik profile. QUIC reads the
// standard configuration through STDConfig; TCP TLS is never used.
type pinnedTLS struct {
	config  *tls.Config
	timeout time.Duration
}

func newTLSConfig(enabled bool, serverName string, alpn []string, certificates []string) (aTLS.Config, error) {
	if !enabled || serverName == "" || len(alpn) == 0 || len(certificates) != 1 {
		return nil, errors.New("tls with server_name, alpn and one pinned certificate is required")
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM([]byte(certificates[0])) {
		return nil, errors.New("invalid pinned certificate")
	}
	return &pinnedTLS{
		config:  &tls.Config{ServerName: serverName, NextProtos: alpn, RootCAs: roots, MinVersion: tls.VersionTLS13},
		timeout: dialTimeout,
	}, nil
}

func (c *pinnedTLS) ServerName() string                        { return c.config.ServerName }
func (c *pinnedTLS) SetServerName(serverName string)           { c.config.ServerName = serverName }
func (c *pinnedTLS) NextProtos() []string                      { return c.config.NextProtos }
func (c *pinnedTLS) SetNextProtos(nextProto []string)          { c.config.NextProtos = nextProto }
func (c *pinnedTLS) HandshakeTimeout() time.Duration           { return c.timeout }
func (c *pinnedTLS) SetHandshakeTimeout(timeout time.Duration) { c.timeout = timeout }
func (c *pinnedTLS) STDConfig() (*aTLS.STDConfig, error)       { return c.config, nil }
func (c *pinnedTLS) Clone() aTLS.Config {
	return &pinnedTLS{config: c.config.Clone(), timeout: c.timeout}
}

func (c *pinnedTLS) Client(net.Conn) (aTLS.Conn, error) {
	return nil, errors.New("TLS over TCP is not supported")
}
