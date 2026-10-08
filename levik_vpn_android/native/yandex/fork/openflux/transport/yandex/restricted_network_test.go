package yandex

import (
	"context"
	"errors"
	"net"
	"syscall"
	"testing"

	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/egresspolicy"
)

func TestRestrictedNetworkHooksApplyToMetadataAndCannotChangeWhileRunning(t *testing.T) {
	lookups := 0
	hooks := egresspolicy.NetworkHooks{
		LookupIPAddr: func(_ context.Context, host string) ([]net.IPAddr, error) {
			lookups++
			if host != "disk.yandex.ru" {
				t.Fatal("unexpected provider host")
			}
			return nil, errors.New("selected network unavailable")
		},
		ControlContext: func(context.Context, string, string, syscall.RawConn) error {
			t.Fatal("failed DNS reached a socket")
			return nil
		},
	}
	tr, _ := NewRestrictedYandexDocsTransport("https://disk.yandex.ru/i/test", transport.DefaultConfig())
	if tr.SetRestrictedNetworkHooks(hooks) != nil {
		t.Fatal("restricted network hooks rejected")
	}
	if _, err := tr.fetchRestrictedDocInfo(context.Background(), tr.url, "guest"); err == nil || lookups != 1 {
		t.Fatal("metadata bypassed selected-network resolver")
	}
	if tr.BaseTransport.Start() != nil {
		t.Fatal("test start failed")
	}
	defer tr.Stop()
	if tr.SetRestrictedNetworkHooks(hooks) == nil {
		t.Fatal("running transport allowed hook replacement")
	}
	upstream := NewYandexDocsTransport("https://upstream.example", transport.DefaultConfig())
	if upstream.SetRestrictedNetworkHooks(hooks) == nil {
		t.Fatal("upstream behavior changed")
	}
}
