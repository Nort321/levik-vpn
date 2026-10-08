// Package egresspolicy restricts the dedicated relay's outbound connections.
// It is opt-in: the upstream general-purpose transports retain their behavior.
package egresspolicy

import (
	"context"
	"errors"
	"net"
	"net/url"
	"strconv"
	"strings"
	"syscall"
	"time"
)

var (
	ErrProviderURL = errors.New("egress: provider URL is not allowed")
	ErrDestination = errors.New("egress: destination is not public")
	ErrResolution  = errors.New("egress: destination resolution failed")
	ErrConnection  = errors.New("egress: connection failed")
)

// ValidateProviderURL validates a provider URL before any request is made.
// User info, custom ports, fragments, ambiguous host encodings and IP literals
// are excluded. DNS and resolved-address policy are enforced at each dial.
func ValidateProviderURL(rawURL string, allowWSS bool) (*url.URL, error) {
	if len(rawURL) > 8192 || strings.TrimSpace(rawURL) != rawURL {
		return nil, ErrProviderURL
	}
	u, err := url.Parse(rawURL)
	if err != nil || u.Opaque != "" || u.User != nil || u.Fragment != "" || u.RawFragment != "" || u.Host == "" {
		return nil, ErrProviderURL
	}
	if u.Scheme != "https" && !(allowWSS && u.Scheme == "wss") {
		return nil, ErrProviderURL
	}
	host := strings.ToLower(u.Hostname())
	if host != u.Host || strings.HasSuffix(host, ".") || strings.ContainsAny(host, "\\%:") || !providerHost(host) {
		return nil, ErrProviderURL
	}
	for index, label := range strings.Split(host, ".") {
		if label == "" || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return nil, ErrProviderURL
		}
		for _, c := range label {
			if (c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '-' &&
				!(c == '_' && index == 0 && onlyOfficeHost(host) && host != "onlyoffice.disk.yandex.net") {
				return nil, ErrProviderURL
			}
		}
	}
	return u, nil
}

func providerHost(host string) bool {
	// Keep the pilot limited to the share and document service. Additional
	// legacy balancers require a separately verified suffix, rather than
	// permitting every service under yandex.ru or yandex.net.
	return host == "disk.yandex.ru" || host == "docs.yandex.ru" || strings.HasSuffix(host, ".docs.yandex.ru") ||
		host == "volga.yandex.ru" || host == "push.yandex.ru" || onlyOfficeHost(host)
}

func onlyOfficeHost(host string) bool {
	const root = "onlyoffice.disk.yandex.net"
	if host == root {
		return true
	}
	if !strings.HasSuffix(host, "."+root) {
		return false
	}
	prefix := strings.TrimSuffix(host, "."+root)
	// The observed editor uses one opaque DNS label, including underscores.
	// Nested subdomains and arbitrary hosts under yandex.net stay excluded.
	return prefix != "" && len(prefix) <= 63 && !strings.Contains(prefix, ".")
}

var deniedRanges = func() []*net.IPNet {
	var ranges []*net.IPNet
	for _, cidr := range []string{
		"0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
		"169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24",
		"192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24",
		"203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
		"2001::/23", "2001:db8::/32", "2002::/16", "3fff::/20",
	} {
		_, network, err := net.ParseCIDR(cidr)
		if err != nil {
			panic("invalid static egress range")
		}
		ranges = append(ranges, network)
	}
	return ranges
}()

// IsPublicIP excludes local, private, special-use and transition addresses.
// IPv4-mapped IPv6 is checked using its IPv4 value. NAT64/6to4/Teredo are
// excluded so an apparently global address cannot encode a private endpoint.
func IsPublicIP(ip net.IP) bool {
	if ip == nil || !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
		return false
	}
	if ip.To4() == nil {
		ip = ip.To16()
		if ip == nil || ip[0]&0xe0 != 0x20 { // currently allocated IPv6 global unicast, 2000::/3
			return false
		}
	}
	for _, network := range deniedRanges {
		if network.Contains(ip) {
			return false
		}
	}
	return true
}

type lookupFunc func(context.Context, string) ([]net.IPAddr, error)
type dialFunc func(context.Context, string, string) (net.Conn, error)

// NetworkHooks selects an application's underlying network without weakening
// destination validation. Both hooks are mandatory: no system DNS or
// unprotected socket fallback is permitted when this profile is selected.
type NetworkHooks struct {
	LookupIPAddr   func(context.Context, string) ([]net.IPAddr, error)
	ControlContext func(context.Context, string, string, syscall.RawConn) error
}

// NewPublicDialContext retains full address validation and numeric pinning,
// while letting Android resolve on and protect/bind sockets to its selected
// Network before connect. HTTP/TLS callers retain the provider hostname.
func NewPublicDialContext(hooks NetworkHooks) (func(context.Context, string, string) (net.Conn, error), error) {
	if hooks.LookupIPAddr == nil || hooks.ControlContext == nil {
		return nil, ErrConnection
	}
	dialer := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second, ControlContext: hooks.ControlContext}
	return func(ctx context.Context, network, address string) (net.Conn, error) {
		return publicDialContext(ctx, network, address, hooks.LookupIPAddr, dialer.DialContext)
	}, nil
}

// PublicDialContext resolves once, validates every returned address, then
// connects to a pinned numeric address. Callers retain the original hostname
// in their HTTP/TLS/WebSocket request, preserving TLS hostname verification.
// It never reads proxy environment variables.
func PublicDialContext(ctx context.Context, network, address string) (net.Conn, error) {
	dialer := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	return publicDialContext(ctx, network, address, net.DefaultResolver.LookupIPAddr, dialer.DialContext)
}

func publicDialContext(ctx context.Context, network, address string, lookup lookupFunc, dial dialFunc) (net.Conn, error) {
	switch network {
	case "tcp", "tcp4", "tcp6", "udp", "udp4", "udp6":
	default:
		return nil, ErrDestination
	}
	host, port, err := net.SplitHostPort(address)
	n, portErr := strconv.Atoi(port)
	if err != nil || portErr != nil || n < 1 || n > 65535 || host == "" || strings.Contains(host, "%") {
		return nil, ErrDestination
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	var addresses []net.IPAddr
	if ip := net.ParseIP(host); ip != nil {
		addresses = []net.IPAddr{{IP: ip}}
	} else {
		addresses, err = lookup(ctx, host)
		if err != nil || len(addresses) == 0 || len(addresses) > 64 {
			return nil, ErrResolution
		}
	}
	// Reject a mixed public/private answer rather than silently falling back
	// to a public member. Reconnects repeat this check; no second DNS lookup
	// occurs between checking an address and opening its socket.
	for _, a := range addresses {
		if a.Zone != "" || !IsPublicIP(a.IP) {
			return nil, ErrDestination
		}
	}
	for _, a := range addresses {
		ipv4 := a.IP.To4() != nil
		if (strings.HasSuffix(network, "4") && !ipv4) || (strings.HasSuffix(network, "6") && ipv4) {
			continue
		}
		family := "6"
		if ipv4 {
			family = "4"
		}
		base := "tcp"
		if strings.HasPrefix(network, "udp") {
			base = "udp"
		}
		conn, err := dial(ctx, base+family, net.JoinHostPort(a.IP.String(), port))
		if err == nil {
			return conn, nil
		}
		if ctx.Err() != nil {
			break
		}
	}
	return nil, ErrConnection
}
