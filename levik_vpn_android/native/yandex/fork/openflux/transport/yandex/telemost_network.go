package yandex

import (
	"bytes"
	"context"
	"errors"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/egresspolicy"
	"github.com/pion/ice/v4"
	pionnet "github.com/pion/transport/v5"
	"github.com/pion/transport/v5/stdnet"
	"github.com/pion/webrtc/v4"
)

var conferencePattern = regexp.MustCompile(`^https://telemost\.yandex\.(ru|com)/j/[0-9]{10,64}$`)
var errTelemostNetwork = errors.New("telemost_network_unavailable")

// ValidateConferenceURL never follows caller-supplied redirects or accepts a
// provider API endpoint in place of the user's public conference link.
func ValidateConferenceURL(raw string) error {
	if len(raw) > 128 || !conferencePattern.MatchString(raw) {
		return errors.New("invalid_conference_url")
	}
	return nil
}

// RestrictedTelemost is the managed carrier. Provider cookies and environment
// overrides are not accepted by its caller. All lanes inherit the same network.
type RestrictedTelemost struct {
	*transport.StripedTransport
	group *TelemostGroup
}

func NewRestrictedTelemost(raw string, exit bool, hooks egresspolicy.NetworkHooks) (*RestrictedTelemost, error) {
	if err := ValidateConferenceURL(raw); err != nil {
		return nil, err
	}
	n, err := newTelemostNetwork(hooks)
	if err != nil {
		return nil, err
	}
	g := newTelemostGroup("", raw, 3, exit, transport.DefaultConfig(), n)
	return &RestrictedTelemost{transport.NewStripedTransportAuto(g.Lanes(), g, 6, true), g}, nil
}

// Media keepalives are carrier padding, never authenticated session messages.
func (t *RestrictedTelemost) Receive(cb func([]byte)) {
	t.StripedTransport.Receive(func(b []byte) {
		if bytes.Equal(b, keepalivePayload(0)) || bytes.Equal(b, probeKeepalivePayload(0)) {
			return
		}
		cb(b)
	})
}

// IsTelemostProviderURL is shared with the Android network boundary.
func IsTelemostProviderURL(raw string) bool { return telemostProviderURL(raw, false) }

func (t *RestrictedTelemost) Stop() error {
	t.group.close()
	return t.StripedTransport.Stop()
}

type telemostNetwork struct {
	rtc         rtcDiagnostics
	pionnet.Net // Only interface enumeration is delegated. All I/O is overridden.
	hooks       egresspolicy.NetworkHooks
	dial        func(context.Context, string, string) (net.Conn, error)
}

func newTelemostNetwork(hooks egresspolicy.NetworkHooks) (*telemostNetwork, error) {
	if (hooks.LookupIPAddr == nil) != (hooks.ControlContext == nil) {
		return nil, errTelemostNetwork
	}
	if hooks.LookupIPAddr == nil {
		hooks.LookupIPAddr = net.DefaultResolver.LookupIPAddr
		hooks.ControlContext = func(context.Context, string, string, syscall.RawConn) error { return nil }
	}
	base, err := stdnet.NewNet()
	if err != nil {
		return nil, errTelemostNetwork
	}
	dial, err := egresspolicy.NewPublicDialContext(hooks)
	if err != nil {
		return nil, err
	}
	return &telemostNetwork{Net: base, hooks: hooks, dial: dial}, nil
}
func telemostProviderURL(raw string, ws bool) bool {
	u, err := url.Parse(raw)
	if err != nil || u.User != nil || u.Fragment != "" || u.Opaque != "" || u.Host != u.Hostname() {
		return false
	}
	if u.Scheme != "https" && !(ws && u.Scheme == "wss") {
		return false
	}
	h := u.Hostname()
	return h == "cloud-api.yandex.ru" || h == "telemost.yandex.ru" || h == "telemost.yandex.com" ||
		strings.HasSuffix(h, ".telemost.yandex.ru") || strings.HasSuffix(h, ".telemost.yandex.net") ||
		strings.HasSuffix(h, ".strm.yandex.net") || strings.HasSuffix(h, ".rtc.yandex.net")
}
func (n *telemostNetwork) httpClient() *http.Client {
	return &http.Client{Timeout: 15 * time.Second, Transport: &http.Transport{DialContext: n.dial, TLSHandshakeTimeout: 10 * time.Second, ResponseHeaderTimeout: 10 * time.Second, DisableKeepAlives: true},
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 3 || !telemostProviderURL(req.URL.String(), false) {
				return errTelemostNetwork
			}
			return nil
		}}
}
func (n *telemostNetwork) wsDial(raw string, headers http.Header) (*websocket.Conn, *http.Response, error) {
	if !telemostProviderURL(raw, true) {
		return nil, nil, errTelemostNetwork
	}
	d := websocket.Dialer{NetDialContext: n.dial, HandshakeTimeout: 15 * time.Second}
	c, r, e := d.Dial(raw, headers)
	if c != nil {
		c.SetReadLimit(2 << 20)
	}
	return c, r, e
}
func (n *telemostNetwork) settings() webrtc.SettingEngine {
	var s webrtc.SettingEngine
	s.LoggerFactory = &n.rtc
	s.SetNet(n)
	s.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	s.SetICEMulticastDNSMode(ice.MulticastDNSModeDisabled)
	s.SetRemoteIPFilter(egresspolicy.IsPublicIP)
	s.SetInterfaceFilter(func(name string) bool {
		return !strings.HasPrefix(name, "tun") && !strings.HasPrefix(name, "utun") && !strings.HasPrefix(name, "lo")
	})
	return s
}
func (n *telemostNetwork) Dial(network, address string) (net.Conn, error) {
	return n.dial(context.Background(), network, address)
}
func (n *telemostNetwork) DialUDP(network string, laddr, raddr *net.UDPAddr) (pionnet.UDPConn, error) {
	if raddr == nil {
		return nil, errTelemostNetwork
	}
	c, e := n.Dial(network, raddr.String())
	if e != nil {
		return nil, e
	}
	u, ok := c.(*net.UDPConn)
	if !ok {
		c.Close()
		return nil, errTelemostNetwork
	}
	return &publicUDP{u}, nil
}
func (n *telemostNetwork) DialTCP(network string, laddr, raddr *net.TCPAddr) (pionnet.TCPConn, error) {
	if raddr == nil {
		return nil, errTelemostNetwork
	}
	c, e := n.Dial(network, raddr.String())
	if e != nil {
		return nil, e
	}
	t, ok := c.(*net.TCPConn)
	if !ok {
		c.Close()
		return nil, errTelemostNetwork
	}
	return t, nil
}
func (n *telemostNetwork) ListenUDP(network string, addr *net.UDPAddr) (pionnet.UDPConn, error) {
	if network != "udp" && network != "udp4" {
		return nil, errTelemostNetwork
	}
	if addr == nil {
		addr = &net.UDPAddr{IP: net.IPv4zero}
	}
	cfg := net.ListenConfig{Control: func(network, address string, raw syscall.RawConn) error {
		return n.hooks.ControlContext(context.Background(), network, address, raw)
	}}
	c, e := cfg.ListenPacket(context.Background(), "udp4", addr.String())
	if e != nil {
		return nil, e
	}
	u, ok := c.(*net.UDPConn)
	if !ok {
		c.Close()
		return nil, errTelemostNetwork
	}
	return &publicUDP{u}, nil
}
func (n *telemostNetwork) ListenPacket(network, address string) (net.PacketConn, error) {
	a, e := net.ResolveUDPAddr("udp4", address)
	if e != nil {
		return nil, errTelemostNetwork
	}
	u, e := n.ListenUDP(network, a)
	if e != nil {
		return nil, e
	}
	return u, nil
}
func (n *telemostNetwork) ListenTCP(string, *net.TCPAddr) (pionnet.TCPListener, error) {
	return nil, errTelemostNetwork
}
func (n *telemostNetwork) resolve(network, address string) (*net.IPAddr, error) {
	if ip := net.ParseIP(address); ip != nil {
		if !egresspolicy.IsPublicIP(ip) {
			return nil, errTelemostNetwork
		}
		return &net.IPAddr{IP: ip}, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	ips, e := n.hooks.LookupIPAddr(ctx, address)
	if e != nil || len(ips) == 0 {
		return nil, errTelemostNetwork
	}
	for _, ip := range ips {
		if !egresspolicy.IsPublicIP(ip.IP) || ip.Zone != "" {
			return nil, errTelemostNetwork
		}
	}
	for _, ip := range ips {
		if ip.IP.To4() != nil {
			return &ip, nil
		}
	}
	return nil, errTelemostNetwork
}
func (n *telemostNetwork) ResolveIPAddr(network, address string) (*net.IPAddr, error) {
	return n.resolve(network, address)
}
func (n *telemostNetwork) ResolveUDPAddr(network, address string) (*net.UDPAddr, error) {
	h, p, e := net.SplitHostPort(address)
	port, pe := strconv.Atoi(p)
	if e != nil || pe != nil || port < 1 || port > 65535 {
		return nil, errTelemostNetwork
	}
	ip, e := n.resolve(network, h)
	if e != nil {
		return nil, e
	}
	return &net.UDPAddr{IP: ip.IP, Port: port}, nil
}
func (n *telemostNetwork) ResolveTCPAddr(network, address string) (*net.TCPAddr, error) {
	a, e := n.ResolveUDPAddr(network, address)
	if e != nil {
		return nil, e
	}
	return &net.TCPAddr{IP: a.IP, Port: a.Port}, nil
}

type protectedDialer struct{ n *telemostNetwork }

func (d protectedDialer) Dial(network, address string) (net.Conn, error) {
	return d.n.Dial(network, address)
}
func (n *telemostNetwork) CreateDialer(*net.Dialer) pionnet.Dialer { return protectedDialer{n} }

type protectedListener struct{ n *telemostNetwork }

func (l protectedListener) Listen(context.Context, string, string) (net.Listener, error) {
	return nil, errTelemostNetwork
}
func (l protectedListener) ListenPacket(_ context.Context, network, address string) (net.PacketConn, error) {
	return l.n.ListenPacket(network, address)
}
func (n *telemostNetwork) CreateListenConfig(*net.ListenConfig) pionnet.ListenConfig {
	return protectedListener{n}
}

// Reject private peer candidates even when sent as a peer-reflexive address.
type publicUDP struct{ *net.UDPConn }

func (u *publicUDP) WriteTo(b []byte, a net.Addr) (int, error) {
	p, ok := a.(*net.UDPAddr)
	if !ok {
		return 0, errTelemostNetwork
	}
	return u.WriteToUDP(b, p)
}
func (u *publicUDP) WriteToUDP(b []byte, a *net.UDPAddr) (int, error) {
	if a == nil || a.Zone != "" || !egresspolicy.IsPublicIP(a.IP) {
		return 0, errTelemostNetwork
	}
	return u.UDPConn.WriteToUDP(b, a)
}
func (u *publicUDP) WriteMsgUDP(b, oob []byte, a *net.UDPAddr) (int, int, error) {
	if a == nil || a.Zone != "" || !egresspolicy.IsPublicIP(a.IP) {
		return 0, 0, errTelemostNetwork
	}
	return u.UDPConn.WriteMsgUDP(b, oob, a)
}
func (u *publicUDP) WriteToUDPAddrPort(b []byte, a netip.AddrPort) (int, error) {
	if !egresspolicy.IsPublicIP(net.IP(a.Addr().AsSlice())) {
		return 0, errTelemostNetwork
	}
	return u.UDPConn.WriteToUDPAddrPort(b, a)
}

// ManagedCarrier is the existing helper/worker contract. Telemost has no
// OnlyOffice credential refresh; its VPN lease is renewed independently.
type ManagedCarrier interface {
	transport.Transport
	SetRestrictedNetworkHooks(egresspolicy.NetworkHooks) error
	SetErrorNotifier(func(error, string, string, string, string))
	VerifyAndRefreshGuestSession(context.Context, GuestBootstrap) error
	EnableRestrictedFastChallenge() error
}

func (t *RestrictedTelemost) SetRestrictedNetworkHooks(h egresspolicy.NetworkHooks) error {
	n, e := newTelemostNetwork(h)
	if e != nil {
		return e
	}
	t.group.mu.Lock()
	defer t.group.mu.Unlock()
	for _, l := range t.group.lanes {
		if l.IsRunning() {
			return errTelemostNetwork
		}
	}
	t.group.network = n
	for _, l := range t.group.lanes {
		l.network = n
	}
	return nil
}
func (t *RestrictedTelemost) SetErrorNotifier(func(error, string, string, string, string)) {}
func (t *RestrictedTelemost) VerifyAndRefreshGuestSession(context.Context, GuestBootstrap) error {
	return errors.New("unsupported_provider_refresh")
}
func (t *RestrictedTelemost) EnableRestrictedFastChallenge() error { return nil }

// MediaStats counts carrier writes without including any peer information.
func (t *RestrictedTelemost) MediaStats() (sent, failed uint64) {
	t.group.mu.Lock()
	defer t.group.mu.Unlock()
	for _, l := range t.group.lanes {
		sent += l.stats.RTPSendSuccess.Load()
		failed += l.stats.RTPSendFailed.Load()
	}
	return
}
