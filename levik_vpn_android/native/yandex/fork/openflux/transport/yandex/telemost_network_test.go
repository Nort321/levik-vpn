package yandex

import (
	"context"
	"errors"
	"net"
	"syscall"
	"testing"

	"github.com/p1neappleXpress/OpenFlux/transport/egresspolicy"
)

func TestManagedTelemostBoundaries(t *testing.T) {
	for _, raw := range []string{"http://telemost.yandex.ru/j/12345678901234", "https://telemost.yandex.ru.evil.test/j/12345678901234", "https://telemost.yandex.ru/j/12345678901234?x=1", "https://u@telemost.yandex.ru/j/12345678901234", "https://127.0.0.1/j/12345678901234"} {
		if ValidateConferenceURL(raw) == nil {
			t.Fatal("invalid conference accepted")
		}
	}
	if ValidateConferenceURL("https://telemost.yandex.ru/j/12345678901234") != nil {
		t.Fatal("conference rejected")
	}
	for _, raw := range []string{"https://evil.test/", "wss://evil.test/", "https://goloom.strm.yandex.net:444/", "https://u@goloom.strm.yandex.net/", "https://goloom.strm.yandex.net.evil.test/"} {
		if telemostProviderURL(raw, true) {
			t.Fatal("untrusted endpoint accepted")
		}
	}
	if !telemostProviderURL("wss://goloom.strm.yandex.net/socket", true) {
		t.Fatal("provider rejected")
	}
	if _, err := newTelemostNetwork(egresspolicy.NetworkHooks{LookupIPAddr: net.DefaultResolver.LookupIPAddr}); err == nil {
		t.Fatal("partial hooks accepted")
	}
}

func TestManagedTelemostProtectFailureHasNoFallback(t *testing.T) {
	denied := errors.New("denied")
	n, err := newTelemostNetwork(egresspolicy.NetworkHooks{
		LookupIPAddr: func(context.Context, string) ([]net.IPAddr, error) {
			return []net.IPAddr{{IP: net.ParseIP("1.1.1.1")}, {IP: net.ParseIP("127.0.0.1")}}, nil
		},
		ControlContext: func(context.Context, string, string, syscall.RawConn) error { return denied },
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = n.ResolveIPAddr("ip4", "goloom.strm.yandex.net"); err == nil {
		t.Fatal("mixed public/private DNS accepted")
	}
	if c, err := n.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero}); err == nil {
		c.Close()
		t.Fatal("unprotected UDP socket opened")
	}
	if c, err := n.ListenTCP("tcp4", &net.TCPAddr{}); err == nil {
		c.Close()
		t.Fatal("inbound TCP opened")
	}
	u := &publicUDP{}
	if _, err = u.WriteToUDP([]byte{1}, &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 80}); err == nil {
		t.Fatal("private peer accepted")
	}
}
