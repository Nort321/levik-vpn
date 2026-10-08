//go:build linux || android || darwin

// Command levik-yandex-android is a standalone, unprivileged SOCKS carrier.
// Its command line contains only a random abstract socket name. All leased
// credentials arrive over same-UID, bounded local IPC and remain in memory.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/p1neappleXpress/OpenFlux/socks5"
	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/egresspolicy"
	"github.com/p1neappleXpress/OpenFlux/transport/yandex"
	"github.com/p1neappleXpress/OpenFlux/tunnel"
	"github.com/p1neappleXpress/OpenFlux/utils"
)

func acceptChannel(ctx context.Context, name string, listening func() error) (*net.UnixConn, error) {
	if !socketPattern.MatchString(name) {
		return nil, errProtocol
	}
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: name, Net: "unix"})
	if err != nil {
		return nil, errProtocol
	}
	defer listener.Close()
	stop := context.AfterFunc(ctx, func() { _ = listener.Close() })
	defer stop()
	if listening != nil && listening() != nil {
		return nil, errProtocol
	}
	deadline := time.Now().Add(15 * time.Second)
	if limit, ok := ctx.Deadline(); ok && limit.Before(deadline) {
		deadline = limit
	}
	_ = listener.SetDeadline(deadline)
	conn, err := listener.AcceptUnix()
	if err != nil {
		return nil, errProtocol
	}
	if verifyPeer(conn) != nil {
		conn.Close()
		return nil, errProtocol
	}
	return conn, nil
}

type tunnelDialer struct {
	tunnel *tunnel.TCPTunnel
	ctx    context.Context
}

func (d tunnelDialer) DialTCP(address string) (net.Conn, error) {
	if validateTunnelDestination(address) != nil || d.ctx.Err() != nil {
		return nil, egresspolicy.ErrDestination
	}
	conn, err := d.tunnel.DialTCP(address)
	if err == nil && d.ctx.Err() != nil {
		conn.Close()
		return nil, net.ErrClosed
	}
	return conn, err
}
func (d tunnelDialer) DialUDP(address string) (net.Conn, error) {
	if validateTunnelDestination(address) != nil || d.ctx.Err() != nil {
		return nil, egresspolicy.ErrDestination
	}
	conn, err := d.tunnel.DialUDP(address)
	if err == nil && d.ctx.Err() != nil {
		conn.Close()
		return nil, net.ErrClosed
	}
	return conn, err
}

type helperStatus struct {
	State                    string                       `json:"state"`
	StrictSession            bool                         `json:"strictSession"`
	BytesUp                  int64                        `json:"bytesUp"`
	BytesDown                int64                        `json:"bytesDown"`
	ProtectedExternalSockets uint64                       `json:"protectedExternalSockets"`
	ResolvedProviderHosts    uint64                       `json:"resolvedProviderHosts"`
	CarrierPacketsUp         uint64                       `json:"carrierPacketsUp"`
	CarrierPacketsDown       uint64                       `json:"carrierPacketsDown"`
	DataPath                 transport.SessionDiagnostics `json:"dataPath"`
	Proxy                    socks5.Diagnostics           `json:"proxy"`
}

func run(parent context.Context, socketName string) string {
	ctx, cancel := context.WithCancelCause(parent)
	defer cancel(nil)
	controlConn, err := acceptChannel(ctx, socketName, nil)
	if err != nil {
		return "control_channel_failed"
	}
	defer controlConn.Close()
	control := &controlChannel{conn: controlConn, reader: bufio.NewReaderSize(controlConn, maxControlFrame)}
	_ = controlConn.SetReadDeadline(time.Now().Add(10 * time.Second))
	frame, err := readFrame(control.reader)
	var init initWire
	if err != nil || strictDecode(frame, &init) != nil || init.ProtectFDSocket == socketName {
		_ = control.emit("error", "", "invalid_init", nil)
		return "invalid_init"
	}
	_ = controlConn.SetReadDeadline(time.Time{})
	raw, deadline, code := validateInit(init, time.Now())
	if code != "" {
		_ = control.emit("error", "", code, nil)
		return code
	}
	expiryCode := "provider_auth_expired"
	if init.LeaseExpiresAt <= init.ProviderAuth.ValidUntil {
		expiryCode = "lease_expired"
	}
	expiry := newCredentialLease(deadline, expiryCode, cancel)
	defer expiry.stop()
	if control.emit("ready", "control", "", nil) != nil {
		return "control_channel_failed"
	}
	var statusMu sync.Mutex
	status := helperStatus{State: "connecting"}
	var network *networkChannel
	var proxy *socks5.SOCKS5Server
	var diagnostics func() transport.SessionDiagnostics
	snapshot := func() helperStatus {
		statusMu.Lock()
		defer statusMu.Unlock()
		out := status
		if diagnostics != nil {
			out.DataPath = diagnostics()
		}
		carrier := raw.Stats()
		out.CarrierPacketsUp, out.CarrierPacketsDown = uint64(carrier.PacketsSent), uint64(carrier.PacketsRecv)
		if network != nil {
			out.ProtectedExternalSockets = network.protected.Load()
			out.ResolvedProviderHosts = network.resolved.Load()
		}
		if proxy != nil {
			out.BytesUp = proxy.BytesSent()
			out.BytesDown = proxy.BytesReceived()
			out.Proxy = proxy.Diagnostics()
		}
		return out
	}
	commandsDone := make(chan struct{})
	defer func() { cancel(nil); controlConn.Close(); <-commandsDone }()
	go func() {
		defer close(commandsDone)
		control.runCommands(ctx, cancel, snapshot, func(ctx context.Context, command refreshCommandWire) refreshResult {
			if !snapshot().StrictSession {
				return refreshResult{RequestID: command.RequestID, Code: "refresh_not_running"}
			}
			// The app admits REFRESH only from an authenticated profile with the
			// same device, document URL, lease, PSK and routing plan.
			return expiry.refresh(ctx, command, raw.VerifyAndRefreshGuestSession)
		})
	}()
	networkConn, err := acceptChannel(ctx, init.ProtectFDSocket, func() error { return control.emit("ready", "PROTECT_CHANNEL_LISTENING", "", nil) })
	if err != nil {
		return finish(control, ctx, "network_channel_failed")
	}
	netChannel := newNetworkChannel(networkConn, func() { cancel(errNetworkChannel) })
	defer netChannel.close()
	statusMu.Lock()
	network = netChannel
	statusMu.Unlock()
	if control.emit("ready", "PROTECT_CHANNEL_READY", "", nil) != nil {
		return "control_channel_failed"
	}
	if raw.SetRestrictedNetworkHooks(egresspolicy.NetworkHooks{LookupIPAddr: netChannel.lookup, ControlContext: netChannel.protect}) != nil {
		return finish(control, ctx, "network_channel_failed")
	}
	raw.SetErrorNotifier(func(err error, _, _, _, _ string) {
		code := "provider_authorization_failed"
		if errors.Is(err, yandex.ErrGuestBootstrapExpired) {
			code = "provider_auth_expired"
		}
		cancel(errors.New(code))
	})
	session, err := transport.NewSession(transport.PeerParameters{Capabilities: transport.CapabilityIPv4 | transport.CapabilityTCP | transport.CapabilityUDP, MaxPacketSize: 1500}, false)
	if err != nil || session.RequirePeerLiveness() != nil || session.AddTransport("yandex", raw, init.SharedKey, "levik-yandex-v1:"+init.LeaseRef, 1) != nil {
		return finish(control, ctx, "session_configuration_failed")
	}
	defer session.Stop()
	statusMu.Lock()
	diagnostics = session.Diagnostics
	statusMu.Unlock()
	tcpTunnel := tunnel.NewTCPTunnel(session, false)
	defer tcpTunnel.Close()
	// No lease material is written to disk or embedded in argv. Clear local
	// DTO references once their immutable Session/bootstrap copies exist.
	init.SharedKey = ""
	init.ProviderAuth.Token = ""
	stopNetwork := context.AfterFunc(ctx, func() { netChannel.close(); tcpTunnel.Close(); _ = session.Stop() })
	defer stopNetwork()
	if ctx.Err() != nil {
		return finish(control, ctx, "")
	}
	started := make(chan error, 1)
	go func() { started <- session.Start() }()
	select {
	case err := <-started:
		if err != nil {
			return finish(control, ctx, "strict_handshake_failed")
		}
	case <-ctx.Done():
		return finish(control, ctx, "")
	}
	if !session.IsConnected() {
		return finish(control, ctx, "strict_handshake_failed")
	}
	socks := socks5.NewSOCKS5Server("127.0.0.1:0", tunnelDialer{tcpTunnel, ctx})
	if socks.SetUDPIdleTimeouts(2*time.Minute, 5*time.Second) != nil {
		return finish(control, ctx, "proxy_failed")
	}
	socks.SetAuth(init.ProxyUsername, init.ProxyPassword)
	init.ProxyUsername, init.ProxyPassword = "", ""
	defer socks.Close()
	if socks.Bind() != nil {
		return finish(control, ctx, "proxy_failed")
	}
	addr, ok := socks.Addr().(*net.TCPAddr)
	if !ok || !addr.IP.Equal(net.IPv4(127, 0, 0, 1)) || addr.Port == 0 {
		return finish(control, ctx, "proxy_failed")
	}
	statusMu.Lock()
	proxy = socks
	status.State = "running"
	status.StrictSession = true
	statusMu.Unlock()
	if control.emit("proxy_plan", "PREPARED", "", struct {
		Address string `json:"address"`
		Port    int    `json:"port"`
	}{"127.0.0.1", addr.Port}) != nil {
		return "control_channel_failed"
	}
	go func() {
		if socks.Start() != nil && ctx.Err() == nil {
			cancel(errors.New("proxy_failed"))
		}
	}()
	if control.emit("ready", "RUNNING", "", struct {
		ProtocolVersion int `json:"protocolVersion"`
	}{protocolVersion}) != nil {
		return "control_channel_failed"
	}
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return finish(control, ctx, "")
		case <-ticker.C:
			statusMu.Lock()
			status.StrictSession = session.IsConnected()
			statusMu.Unlock()
			if control.emit("stats", "", "", snapshot()) != nil {
				return "control_channel_failed"
			}
		}
	}
}

func (control *controlChannel) runCommands(ctx context.Context, cancel context.CancelCauseFunc, snapshot func() helperStatus, refresh func(context.Context, refreshCommandWire) refreshResult) {
	var refreshWG sync.WaitGroup
	defer refreshWG.Wait()
	refreshSlot := make(chan struct{}, 1)
	for {
		frame, err := readFrame(control.reader)
		if err != nil {
			cancel(errors.New("control_channel_failed"))
			return
		}
		var hint struct {
			Type string `json:"type"`
		}
		if json.Unmarshal(frame, &hint) != nil {
			cancel(errors.New("bad_command"))
			return
		}
		if hint.Type == "REFRESH" {
			var command refreshCommandWire
			if strictDecode(frame, &command) != nil || !validEnvelope(command.Magic, command.Version) || command.RequestID < 1 || command.RequestID > 1<<53 {
				cancel(errors.New("bad_command"))
				return
			}
			select {
			case refreshSlot <- struct{}{}:
				refreshWG.Add(1)
				go func() {
					defer refreshWG.Done()
					defer func() { <-refreshSlot }()
					result := refreshResult{RequestID: command.RequestID, Code: "refresh_not_running"}
					if refresh != nil {
						result = refresh(ctx, command)
					}
					if ctx.Err() == nil && control.emit("refreshed", "", "", result) != nil {
						cancel(errors.New("control_channel_failed"))
					}
				}()
			default:
				if control.emit("refreshed", "", "", refreshResult{RequestID: command.RequestID, Code: "refresh_busy"}) != nil {
					cancel(errors.New("control_channel_failed"))
					return
				}
			}
			continue
		}
		var command commandWire
		if strictDecode(frame, &command) != nil || !validEnvelope(command.Magic, command.Version) {
			cancel(errors.New("bad_command"))
			return
		}
		switch command.Type {
		case "STOP":
			cancel(nil)
			return
		case "STATUS":
			if control.emit("status", "", "", snapshot()) != nil {
				cancel(errors.New("control_channel_failed"))
				return
			}
		default:
			cancel(errors.New("bad_command"))
			return
		}
	}
}

func finish(control *controlChannel, ctx context.Context, fallback string) string {
	code := fallback
	if cause := context.Cause(ctx); cause != nil {
		if errors.Is(cause, context.Canceled) {
			_ = control.emit("ready", "STOPPED", "", nil)
			return ""
		}
		code = cause.Error()
	}
	if code == "" {
		code = "transport_failed"
	}
	_ = control.emit("error", "", code, nil)
	return code
}

func main() {
	utils.SetOutput(io.Discard)
	utils.SetLogSink(nil)
	utils.SetSensitive(false)
	utils.SetLevel(0)
	flags := flag.NewFlagSet("levik-yandex-android", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	socketName := flags.String("control-sock", "", "Private abstract Unix control socket")
	if flags.Parse(os.Args[1:]) != nil || flags.NArg() != 0 || !socketPattern.MatchString(*socketName) {
		fmt.Fprintln(os.Stderr, "invalid_arguments")
		os.Exit(1)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if code := run(ctx, *socketName); code != "" {
		fmt.Fprintln(os.Stderr, code)
		os.Exit(1)
	}
}
