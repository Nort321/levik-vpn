package main

import (
	"context"
	"errors"
	"io"
	"net"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestChooseDNSRouteFallsBackToSystemAfterDirectTimeouts(t *testing.T) {
	directAttempts := make([]string, 0, 4)
	systemAttempts := 0

	route, probes := chooseDNSRouteWithProbes(
		context.Background(),
		&net.Resolver{},
		func(_ context.Context, route dnsRoute, _ time.Duration) error {
			directAttempts = append(directAttempts, route.Label)
			return context.DeadlineExceeded
		},
		func(_ context.Context, _ *net.Resolver, _ time.Duration) error {
			systemAttempts++
			return nil
		},
	)

	wantAttempts := []string{
		"77.88.8.8 UDP",
		"77.88.8.1 UDP",
		"77.88.8.8 TCP",
		"77.88.8.1 TCP",
	}
	if len(directAttempts) != len(wantAttempts) {
		t.Fatalf("direct attempts = %v, want %v", directAttempts, wantAttempts)
	}
	for i := range wantAttempts {
		if directAttempts[i] != wantAttempts[i] {
			t.Fatalf("direct attempts = %v, want %v", directAttempts, wantAttempts)
		}
	}
	if !route.System || route.Label != "системный DNS устройства" {
		t.Fatalf("route = %+v, want system resolver", route)
	}
	if systemAttempts != 1 {
		t.Fatalf("system attempts = %d, want 1", systemAttempts)
	}
	if len(probes) != len(wantAttempts)+1 {
		t.Fatalf("probes = %d, want %d", len(probes), len(wantAttempts)+1)
	}
}

func TestLookupHostRequiresDNSResponseFromConnectedSocket(t *testing.T) {
	resolver := &net.Resolver{
		PreferGo: true,
		Dial: func(context.Context, string, string) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				defer server.Close()
				_, _ = io.Copy(io.Discard, server)
			}()
			return client, nil
		},
	}
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()

	if err := lookupHostWithResolver(ctx, resolver, dnsProbeHost); err == nil {
		t.Fatal("connected DNS socket without a response was treated as working")
	}
}

func TestLevikDNSPolicyNeverCallsSystemResolver(t *testing.T) {
	systemCalls := 0
	direct := func(context.Context, dnsRoute, time.Duration) error {
		return errors.New("unavailable")
	}
	system := func(context.Context, *net.Resolver, time.Duration) error {
		systemCalls++
		return nil
	}
	route, _ := chooseDNSRouteForMode(context.Background(), &net.Resolver{}, direct, system, false, "")
	if route.Address != "" || route.System || systemCalls != 0 {
		t.Fatalf("Levik DNS policy used unprotected resolver: route=%#v systemCalls=%d", route, systemCalls)
	}
}

func TestLevikDNSUsesPhysicalNetworkWithoutPublicOrSystemDNS(t *testing.T) {
	for _, workingNetwork := range []string{"udp", "tcp"} {
		t.Run(workingNetwork, func(t *testing.T) {
			attempts := 0
			route, _ := chooseDNSRouteForMode(context.Background(), &net.Resolver{},
				func(_ context.Context, candidate dnsRoute, _ time.Duration) error {
					attempts++
					if candidate.Address != "10.0.0.1:53" {
						t.Fatalf("queried public DNS despite working operator DNS: %+v", candidate)
					}
					if candidate.Network != workingNetwork {
						return context.DeadlineExceeded
					}
					return nil
				},
				func(context.Context, *net.Resolver, time.Duration) error {
					t.Fatal("used unprotected system resolver")
					return nil
				}, false, "10.0.0.1:53")
			if route.Address != "10.0.0.1:53" || route.Network != workingNetwork || route.System {
				t.Fatalf("unexpected DNS route: %+v", route)
			}
			if attempts > 2 {
				t.Fatalf("operator resolver took %d attempts", attempts)
			}
		})
	}
}

func TestLevikDNSRetainsProtectedPublicFallback(t *testing.T) {
	var attempted []dnsRoute
	route, _ := chooseDNSRouteForMode(context.Background(), &net.Resolver{},
		func(_ context.Context, candidate dnsRoute, _ time.Duration) error {
			attempted = append(attempted, candidate)
			if candidate.Address == "10.0.0.1:53" {
				return context.DeadlineExceeded
			}
			return nil
		},
		func(context.Context, *net.Resolver, time.Duration) error {
			t.Fatal("used unprotected system resolver")
			return nil
		}, false, "10.0.0.1:53")
	if route.Address != "77.88.8.8:53" || route.System || len(attempted) != 3 {
		t.Fatalf("protected public fallback missing: route=%+v attempts=%+v", route, attempted)
	}
}

func TestBootstrapDNSServerOnlyAcceptsNumericPhysicalResolvers(t *testing.T) {
	for _, endpoint := range []string{"", "10.0.0.1:53", "192.168.1.1:53", "[2001:db8::53]:53", "[fe80::1%rmnet0]:53"} {
		if err := validateBootstrapDNSServer(endpoint); err != nil {
			t.Errorf("valid physical resolver rejected: %q (%v)", endpoint, err)
		}
	}
	for _, endpoint := range []string{"dns.example:53", "10.0.0.1:443", "127.0.0.1:53", "0.0.0.0:53", "[::1]:53", "[::]:53", "[::ffff:127.0.0.1]:53", "224.0.0.1:53", "[ff02::1]:53", "10.0.0.1:53\n", "[fe80::1%bad zone]:53"} {
		if err := validateBootstrapDNSServer(endpoint); err == nil {
			t.Errorf("invalid physical resolver accepted: %q", endpoint)
		}
	}
}

func TestSummarizeDNSFailuresSkipsSystemWhenRequested(t *testing.T) {
	probes := []dnsRouteProbe{
		{Route: dnsRoute{Label: "77.88.8.8 UDP"}, Err: errors.New("timeout")},
		{Route: dnsRoute{Label: "системный DNS устройства", System: true}, Err: nil},
	}
	got := summarizeDNSFailures(probes, false)
	if got != "77.88.8.8 UDP: timeout" {
		t.Fatalf("summary = %q", got)
	}
}

func TestPhysicalDNSDiagnosticsNeverIncludeResolverOrQuery(t *testing.T) {
	for _, test := range []struct {
		name string
		err  error
		code string
	}{
		{"timeout", &net.DNSError{Err: "read udp 10.0.0.2:12345->10.0.0.1:53: i/o timeout", Name: "login.vk.ru", Server: "10.0.0.1:53", IsTimeout: true}, "dns_timeout"},
		{"refused", &net.OpError{Op: "dial", Net: "udp", Addr: &net.UDPAddr{IP: net.ParseIP("10.0.0.1"), Port: 53}, Err: syscall.ECONNREFUSED}, "dns_refused"},
		{"unreachable", &net.OpError{Op: "dial", Net: "udp", Addr: &net.UDPAddr{IP: net.ParseIP("10.0.0.1"), Port: 53}, Err: syscall.ENETUNREACH}, "dns_unreachable"},
		{"other", errors.New("unclassified error querying login.vk.ru via 10.0.0.1:53"), "dns_failed"},
	} {
		t.Run(test.name, func(t *testing.T) {
			summary := summarizeDNSFailures([]dnsRouteProbe{{Route: dnsRoute{Label: "physical network UDP", Physical: true}, Err: test.err}}, true)
			if summary != "physical network UDP: "+test.code {
				t.Fatalf("unexpected diagnostic category: %q", summary)
			}
			if strings.Contains(summary, "10.0.0.") || strings.Contains(summary, "login.vk.ru") {
				t.Fatalf("physical DNS details leaked: %q", summary)
			}
		})
	}
}

func TestShortDNSErrorLimitsLongText(t *testing.T) {
	long := "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
	got := shortDNSError(errors.New(long))
	if len(got) > 123 {
		t.Fatalf("short error too long: %d", len(got))
	}
}
