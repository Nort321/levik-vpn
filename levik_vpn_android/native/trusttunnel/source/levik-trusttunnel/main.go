// The Android app starts this helper with an authenticated loopback SOCKS5
// listener and a single TrustTunnel outbound. The pinned sing-box engine still
// owns routing, TLS, QUIC and socket protection; only the CLI and registry differ.
package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/signal"
	"runtime/debug"
	"slices"
	"syscall"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport/local"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/socks"
	"github.com/sagernet/sing-box/protocol/trusttunnel"
	"github.com/sagernet/sing/common/json"
)

func main() {
	if !slices.Equal(os.Args[1:], []string{"run", "-c", "stdin", "--disable-color"}) {
		fmt.Fprintln(os.Stderr, "usage: levik-trusttunnel run -c stdin --disable-color")
		os.Exit(2)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	err := run(ctx)
	stop()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(ctx context.Context) error {
	inputs := inbound.NewRegistry()
	socks.RegisterInbound(inputs)
	outputs := outbound.NewRegistry()
	trusttunnel.RegisterOutbound(outputs)
	dnsTransports := dns.NewTransportRegistry()
	local.RegisterTransport(dnsTransports)
	ctx = box.Context(ctx, inputs, outputs, endpoint.NewRegistry(), dnsTransports,
		service.NewRegistry(), certificate.NewRegistry())

	data, err := io.ReadAll(os.Stdin)
	if err != nil {
		return fmt.Errorf("read TrustTunnel configuration: %w", err)
	}
	options, err := json.UnmarshalExtendedContext[option.Options](ctx, data)
	if err != nil {
		return errors.New("invalid TrustTunnel configuration")
	}
	if options.Log == nil {
		options.Log = &option.LogOptions{}
	}
	options.Log.DisableColor = true
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		return fmt.Errorf("create TrustTunnel service: %w", err)
	}
	if err := instance.Start(); err != nil {
		return fmt.Errorf("start TrustTunnel service: %w", err)
	}
	debug.FreeOSMemory()
	<-ctx.Done()
	return instance.Close()
}
