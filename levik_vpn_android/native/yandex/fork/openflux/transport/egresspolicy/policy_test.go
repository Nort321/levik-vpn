package egresspolicy

import (
	"context"
	"errors"
	"net"
	"strings"
	"syscall"
	"testing"
)

func TestValidateProviderURL(t *testing.T) {
	for _, raw := range []string{
		"https://disk.yandex.ru/i/test", "https://docs.yandex.ru/editor", "https://legacy.docs.yandex.ru/",
		"https://onlyoffice.disk.yandex.net/", "https://opaque_klg_804_hash.onlyoffice.disk.yandex.net/",
	} {
		if _, err := ValidateProviderURL(raw, false); err != nil {
			t.Errorf("allowed URL rejected: %v", err)
		}
	}
	if _, err := ValidateProviderURL("wss://legacy.docs.yandex.ru/socket", true); err != nil {
		t.Fatal(err)
	}
	for _, raw := range []string{
		"wss://docs.yandex.ru/socket", "http://docs.yandex.ru/", "https://evil-docs.yandex.ru/",
		"https://docs.yandex.ru.evil.test/", "https://yandex.ru/", "https://mail.yandex.ru/",
		"https://docs.yandex.net/", "https://127.0.0.1/", "https://[::1]/", "https://docs.yandex.ru:443/",
		"https://user@docs.yandex.ru/", "https://docs.yandex.ru/#secret", "https://docs.yandex.ru./",
		"https://DOCS.yandex.ru/", " https://docs.yandex.ru/", "https://a..docs.yandex.ru/",
		"https://-bad.docs.yandex.ru/", "https://a_b.docs.yandex.ru/", "https://docs.yandex.ru%2fevil.test/",
		"https://nested.opaque.onlyoffice.disk.yandex.net/", "https://onlyoffice.disk.yandex.net.evil.test/",
		"https://evil-onlyoffice.disk.yandex.net/", "https://office.disk.yandex.net/", "https://onlyoffice.disk.yandex.net:443/",
		"https://bad!label.onlyoffice.disk.yandex.net/", "https://" + strings.Repeat("a", 64) + ".onlyoffice.disk.yandex.net/",
	} {
		if _, err := ValidateProviderURL(raw, false); !errors.Is(err, ErrProviderURL) {
			t.Errorf("forbidden URL accepted or wrong error for %q: %v", raw, err)
		}
	}
}

func TestIsPublicIP(t *testing.T) {
	for _, raw := range []string{"8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "2a02:6b8::1"} {
		if !IsPublicIP(net.ParseIP(raw)) {
			t.Errorf("public IP rejected: %s", raw)
		}
	}
	for _, raw := range []string{
		"0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.169.254", "172.16.1.1", "192.168.1.1",
		"192.0.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255",
		"::", "::1", "::ffff:127.0.0.1", "fe80::1", "fc00::1", "ff02::1", "64:ff9b::a00:1",
		"2001::1", "2001:db8::1", "2002:a00:1::1", "3fff::1", "invalid",
	} {
		if IsPublicIP(net.ParseIP(raw)) {
			t.Errorf("nonpublic IP accepted: %s", raw)
		}
	}
}

func TestPublicDialPinsValidatedAddress(t *testing.T) {
	lookups, dials := 0, 0
	lookup := func(context.Context, string) ([]net.IPAddr, error) {
		lookups++
		return []net.IPAddr{{IP: net.ParseIP("8.8.8.8")}, {IP: net.ParseIP("2606:4700:4700::1111")}}, nil
	}
	left, right := net.Pipe()
	defer left.Close()
	defer right.Close()
	dial := func(_ context.Context, network, address string) (net.Conn, error) {
		dials++
		if network != "tcp4" || address != "8.8.8.8:443" {
			t.Fatalf("dial did not pin the validated IP: %s %s", network, address)
		}
		return left, nil
	}
	if _, err := publicDialContext(context.Background(), "tcp", "provider.test:443", lookup, dial); err != nil {
		t.Fatal(err)
	}
	if lookups != 1 || dials != 1 {
		t.Fatalf("lookup/dial counts = %d/%d", lookups, dials)
	}
}

func TestPublicDialRejectsEveryPrivateDNSCandidate(t *testing.T) {
	for _, network := range []string{"tcp", "tcp4", "udp"} {
		t.Run(network, func(t *testing.T) {
			lookup := func(context.Context, string) ([]net.IPAddr, error) {
				return []net.IPAddr{{IP: net.ParseIP("8.8.8.8")}, {IP: net.ParseIP("fc00::1")}}, nil
			}
			dial := func(context.Context, string, string) (net.Conn, error) {
				t.Fatal("mixed public/private answer reached dial")
				return nil, nil
			}
			if _, err := publicDialContext(context.Background(), network, "provider.test:443", lookup, dial); !errors.Is(err, ErrDestination) {
				t.Fatalf("expected destination rejection, got %v", err)
			}
		})
	}
}

func TestPublicDialRejectsInvalidDestinationsBeforeDNS(t *testing.T) {
	lookup := func(context.Context, string) ([]net.IPAddr, error) {
		t.Fatal("invalid destination reached DNS")
		return nil, nil
	}
	dial := func(context.Context, string, string) (net.Conn, error) {
		t.Fatal("invalid destination reached dial")
		return nil, nil
	}
	for _, address := range []string{"127.0.0.1:443", "[::1]:443", "[fe80::1%en0]:443", "host:0", "host:65536", "host:https", ":443", "host"} {
		if _, err := publicDialContext(context.Background(), "tcp", address, lookup, dial); !errors.Is(err, ErrDestination) {
			t.Errorf("expected destination error for %s: %v", address, err)
		}
	}
}

func TestPublicDialResolutionFailureIsSanitized(t *testing.T) {
	lookup := func(context.Context, string) ([]net.IPAddr, error) {
		return nil, errors.New("internal resolver detail")
	}
	if _, err := publicDialContext(context.Background(), "tcp", "provider.test:443", lookup, nil); err != ErrResolution {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestProtectedPublicDialRequiresBothHooksAndChecksEveryCandidate(t *testing.T) {
	lookup := func(context.Context, string) ([]net.IPAddr, error) {
		return []net.IPAddr{{IP: net.ParseIP("8.8.8.8")}, {IP: net.ParseIP("fc00::1")}}, nil
	}
	controls := 0
	control := func(context.Context, string, string, syscall.RawConn) error {
		controls++
		return errors.New("never connect")
	}
	for _, hooks := range []NetworkHooks{{}, {LookupIPAddr: lookup}, {ControlContext: control}} {
		if _, err := NewPublicDialContext(hooks); err == nil {
			t.Fatal("incomplete hooks enabled a fallback")
		}
	}
	dial, err := NewPublicDialContext(NetworkHooks{LookupIPAddr: lookup, ControlContext: control})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = dial(context.Background(), "tcp4", "provider.test:443"); err != ErrDestination || controls != 0 {
		t.Fatal("private address in another family reached socket creation")
	}
}

func TestProtectedPublicDialPinsIPAndRejectsBeforeConnect(t *testing.T) {
	lookups, controls := 0, 0
	dial, err := NewPublicDialContext(NetworkHooks{
		LookupIPAddr: func(_ context.Context, host string) ([]net.IPAddr, error) {
			lookups++
			if host != "provider.test" {
				t.Fatal("unexpected lookup")
			}
			return []net.IPAddr{{IP: net.ParseIP("8.8.8.8")}}, nil
		},
		ControlContext: func(_ context.Context, network, address string, raw syscall.RawConn) error {
			controls++
			if network != "tcp4" || address != "8.8.8.8:443" || raw == nil {
				t.Fatal("unvalidated hostname reached socket protection")
			}
			return errors.New("test denies socket before connect")
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = dial(context.Background(), "tcp", "provider.test:443"); err != ErrConnection || lookups != 1 || controls != 1 {
		t.Fatal("socket was not pinned and rejected before connect")
	}
}
