package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/netip"
	"strings"
	"syscall"
	"time"
)

const dnsProbeHost = "login.vk.ru"

// Keep Android's resolver before setupGlobalResolver optionally replaces the
// process-wide resolver with a direct DNS route optimized for VK. Some public
// resolvers return different Cloudflare WARP API edges, so MASQUE enrollment
// must be able to consult the device resolver independently.
var deviceSystemResolver = net.DefaultResolver

type dnsRoute struct {
	Label    string
	Network  string
	Address  string
	System   bool
	Physical bool
}

type dnsRouteProbe struct {
	Route dnsRoute
	Err   error
}

type directDNSProbe func(context.Context, dnsRoute, time.Duration) error
type systemDNSProbe func(context.Context, *net.Resolver, time.Duration) error

func setupGlobalResolver(bootstrapDNSServer string) error {
	if err := validateBootstrapDNSServer(bootstrapDNSServer); err != nil {
		return err
	}
	systemResolver := deviceSystemResolver
	route, probes := chooseDNSRouteForMode(context.Background(), systemResolver, probeDNSRoute, probeSystemDNS, !levikMode.Load(), bootstrapDNSServer)
	if route.System {
		if levikMode.Load() {
			return fmt.Errorf("Levik cellular mode requires a protectable direct DNS route")
		}
		logDNSSystemRoute(probes)
		return nil
	}
	if route.Address == "" {
		logDNSUnavailable(probes)
		return fmt.Errorf("no usable DNS route")
	}

	log.Printf("[DNS] Прямой DNS %s отвечает для %s; используем его", route.Label, dnsProbeHost)
	net.DefaultResolver = fixedDNSResolver(route)
	return nil
}

func chooseDNSRoute(ctx context.Context, systemResolver *net.Resolver) (dnsRoute, []dnsRouteProbe) {
	return chooseDNSRouteForMode(ctx, systemResolver, probeDNSRoute, probeSystemDNS, true, "")
}

func chooseDNSRouteWithProbes(
	ctx context.Context,
	systemResolver *net.Resolver,
	probeDirect directDNSProbe,
	probeSystem systemDNSProbe,
) (dnsRoute, []dnsRouteProbe) {
	return chooseDNSRouteForMode(ctx, systemResolver, probeDirect, probeSystem, true, "")
}

func chooseDNSRouteForMode(
	ctx context.Context,
	systemResolver *net.Resolver,
	probeDirect directDNSProbe,
	probeSystem systemDNSProbe,
	allowSystem bool,
	bootstrapDNSServer string,
) (dnsRoute, []dnsRouteProbe) {
	// Android supplies the resolver of the selected physical network, not the
	// tunnel's DNS. fixedDNSResolver still requires a protect/bind ACK for each
	// socket, so an operator-only DNS route cannot recurse through our own VPN.
	candidates := make([]dnsRoute, 0, 6)
	if bootstrapDNSServer != "" {
		candidates = append(candidates,
			dnsRoute{Label: "physical network UDP", Network: "udp", Address: bootstrapDNSServer, Physical: true},
			dnsRoute{Label: "physical network TCP", Network: "tcp", Address: bootstrapDNSServer, Physical: true},
		)
	}
	candidates = append(candidates, []dnsRoute{
		{Label: "77.88.8.8 UDP", Network: "udp", Address: "77.88.8.8:53"},
		{Label: "77.88.8.1 UDP", Network: "udp", Address: "77.88.8.1:53"},
		{Label: "77.88.8.8 TCP", Network: "tcp", Address: "77.88.8.8:53"},
		{Label: "77.88.8.1 TCP", Network: "tcp", Address: "77.88.8.1:53"},
	}...)

	probes := make([]dnsRouteProbe, 0, len(candidates)+1)
	for _, route := range candidates {
		timeout := 1200 * time.Millisecond
		if route.Network == "tcp" {
			timeout = 1500 * time.Millisecond
		}
		err := probeDirect(ctx, route, timeout)
		probes = append(probes, dnsRouteProbe{Route: route, Err: err})
		if err == nil {
			return route, probes
		}
	}

	systemRoute := dnsRoute{Label: "системный DNS устройства", System: true}
	if !allowSystem {
		probes = append(probes, dnsRouteProbe{Route: systemRoute, Err: fmt.Errorf("system DNS disabled in Levik mode")})
		return dnsRoute{}, probes
	}
	err := probeSystem(ctx, systemResolver, 1800*time.Millisecond)
	probes = append(probes, dnsRouteProbe{Route: systemRoute, Err: err})
	if err == nil {
		return systemRoute, probes
	}
	return dnsRoute{}, probes
}

func validateBootstrapDNSServer(value string) error {
	if value == "" {
		return nil // Older control clients omit this optional resolver.
	}
	endpoint, err := netip.ParseAddrPort(value)
	if err != nil || len(value) > 128 || strings.ContainsAny(value, "\x00\r\n\t ") || endpoint.Port() != 53 {
		return fmt.Errorf("invalid physical network DNS endpoint")
	}
	address := endpoint.Addr().Unmap()
	if address.IsUnspecified() || address.IsLoopback() || address.IsMulticast() {
		return fmt.Errorf("invalid physical network DNS address")
	}
	return nil
}

func probeDNSRoute(ctx context.Context, route dnsRoute, timeout time.Duration) error {
	probeCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	resolver := fixedDNSResolver(route)
	return lookupHostWithResolver(probeCtx, resolver, dnsProbeHost)
}

func probeSystemDNS(ctx context.Context, resolver *net.Resolver, timeout time.Duration) error {
	if resolver == nil {
		resolver = &net.Resolver{}
	}
	probeCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	return lookupHostWithResolver(probeCtx, resolver, dnsProbeHost)
}

func fixedDNSResolver(route dnsRoute) *net.Resolver {
	timeout := 1200 * time.Millisecond
	if route.Network == "tcp" {
		timeout = 1500 * time.Millisecond
	}
	return &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
			dialer := protectedDialer(timeout, 30*time.Second)
			return dialer.DialContext(ctx, route.Network, route.Address)
		},
	}
}

func lookupHostWithResolver(ctx context.Context, resolver *net.Resolver, host string) error {
	addrs, err := resolver.LookupIPAddr(ctx, host)
	if err != nil {
		return err
	}
	if len(addrs) == 0 {
		return fmt.Errorf("пустой DNS-ответ")
	}
	return nil
}

func logDNSSystemRoute(probes []dnsRouteProbe) {
	failedDirect := summarizeDNSFailures(probes, false)
	if failedDirect == "" {
		log.Printf("[DNS] Системный DNS устройства отвечает для %s; используем его", dnsProbeHost)
		return
	}
	log.Printf("[DNS] Прямой DNS клиента недоступен (%s); системный DNS устройства отвечает — используем его", failedDirect)
}

func logDNSUnavailable(probes []dnsRouteProbe) {
	log.Printf("[DNS] DNS до VK недоступен: прямой DNS клиента и системный DNS устройства не ответили (%s)", summarizeDNSFailures(probes, true))
}

func summarizeDNSFailures(probes []dnsRouteProbe, includeSystem bool) string {
	parts := make([]string, 0, len(probes))
	for _, probe := range probes {
		if probe.Err == nil || (!includeSystem && probe.Route.System) {
			continue
		}
		detail := shortDNSError(probe.Err)
		if probe.Route.Physical {
			// net.DNSError and net.OpError can embed carrier/private resolver
			// addresses. Only fixed categories may leave this local DNS path.
			detail = physicalDNSErrorCode(probe.Err)
		}
		parts = append(parts, probe.Route.Label+": "+detail)
	}
	return strings.Join(parts, "; ")
}

func physicalDNSErrorCode(err error) string {
	var networkError net.Error
	switch {
	case errors.Is(err, context.DeadlineExceeded) || (errors.As(err, &networkError) && networkError.Timeout()):
		return "dns_timeout"
	case errors.Is(err, syscall.ECONNREFUSED):
		return "dns_refused"
	case errors.Is(err, syscall.ENETUNREACH), errors.Is(err, syscall.EHOSTUNREACH), errors.Is(err, syscall.EADDRNOTAVAIL):
		return "dns_unreachable"
	default:
		return "dns_failed"
	}
}

func shortDNSError(err error) string {
	if err == nil {
		return "OK"
	}
	text := strings.ReplaceAll(err.Error(), "\n", " ")
	text = strings.ReplaceAll(text, "\r", " ")
	if len(text) > 120 {
		return text[:120] + "…"
	}
	return text
}
