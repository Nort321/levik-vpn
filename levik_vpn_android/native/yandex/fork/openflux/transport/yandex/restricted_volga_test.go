package yandex

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/egresspolicy"
)

func restrictedVolgaPage(action string) string {
	return clientConfigPage(`{"officeActionData":{"officeType":"volga",` +
		`"action_url":"https://volga.yandex.ru/auth/initial","access_token":"guest-access-token",` +
		`"access_token_ttl":1234567890},"editorParams":{"idDoc":"guest-document","action":"` + action + `"}}`)
}

func restrictedVolgaLocation(requestPath string) string {
	data, _ := json.Marshal(map[string]interface{}{
		"sessionId": "guest-session", "userId": json.Number("1234567890123456"),
		"xiva": map[string]string{"sign": "guest-sign", "ts": "1234567890", "user": "1234567890123456"},
	})
	query := url.Values{"token": {"guest-relay-token"}, "request-path": {requestPath}, "json": {string(data)}}
	return "https://volga.yandex.ru/document/guest-document?" + query.Encode()
}

func TestRestrictedVolgaConstructorAndCookies(t *testing.T) {
	tr, err := NewRestrictedYandexVolgaTransport("https://disk.yandex.ru/i/test", transport.DefaultConfig())
	if err != nil || !tr.restricted {
		t.Fatalf("constructor failed: %v", err)
	}
	if tr.config.WorkerCount != 4 || tr.config.QueueSize != 1024 || tr.config.MaxPayloadBytes > 64<<10 || tr.config.WSReadBufferSize > 32<<10 {
		t.Fatal("unbounded restricted profile")
	}
	if _, err := NewRestrictedYandexVolgaTransport("https://docs.yandex.ru/edit/d/test", transport.DefaultConfig()); !errors.Is(err, ErrRestrictedDocument) {
		t.Fatal("constructor accepted direct editor URL")
	}
	if err := tr.LoadCookieFile("/path/must/not/be/read"); !errors.Is(err, ErrAccountCookies) {
		t.Fatal("cookie-file import attempted")
	}
	if err := tr.ApplyCookies(map[string]string{"yandexuid": "imported"}); !errors.Is(err, ErrAccountCookies) {
		t.Fatal("client cookies imported")
	}
	if values, err := tr.FetchCookies(); err != nil || len(values) != 0 {
		t.Fatal("cookies exported")
	}
	if NewYandexVolgaTransport("https://upstream.example/", transport.DefaultConfig()).restricted {
		t.Fatal("upstream constructor behavior changed")
	}
}

func TestRestrictedVolgaAuthorizesExistingConfigContract(t *testing.T) {
	calls := 0
	client, guest := restrictedTestClient(t, func(req *http.Request) (*http.Response, error) {
		calls++
		switch calls {
		case 1:
			return restrictedResponse(302, "", http.Header{"Location": {"https://docs.yandex.ru/editor"}, "Set-Cookie": {"yandexuid=host-only; Path=/; Secure"}}), nil
		case 2:
			return restrictedResponse(200, restrictedVolgaPage("edit"), http.Header{}), nil
		case 3:
			if req.Method != http.MethodPost || req.URL.String() != "https://volga.yandex.ru/auth/initial" || req.Header.Get("Cookie") != "" {
				t.Fatal("authorization endpoint/cookie scope violated")
			}
			body, _ := io.ReadAll(req.Body)
			values, _ := url.ParseQuery(string(body))
			if values.Get("access_token") != "guest-access-token" || values.Get("access_token_ttl") != "1234567890" {
				t.Fatal("authorization form contract changed")
			}
			return restrictedResponse(302, "", http.Header{"Location": {restrictedVolgaLocation("guest-path")}, "Set-Cookie": {"spravka=volga-host-only; Path=/; Secure", "ymex=shared-guest; Domain=yandex.ru; Path=/; Secure"}}), nil
		case 4:
			return restrictedResponse(200, "<html>document</html>", http.Header{}), nil
		default:
			t.Fatal("unexpected network request")
			return nil, nil
		}
	})
	auth, err := authorizeRestrictedVolga(context.Background(), "https://disk.yandex.ru/i/test", client, guest)
	if err != nil {
		t.Fatal(err)
	}
	if calls != 4 || auth.UserID != 1234567890123456 || auth.RequestPath != "guest-path" || auth.Action != "edit" || len(auth.Cookies) != 0 {
		t.Fatal("authorization result is invalid")
	}
	cookies, err := restrictedVolgaCookies(auth, "wss://push.yandex.ru/v2/subscribe/websocket")
	if err != nil || cookies != "ymex=shared-guest" {
		t.Fatalf("Volga host-only cookie leaked to push: cookie=%q error=%v", cookies, err)
	}
}

func TestRestrictedVolgaCaptchaFailsWithoutSolver(t *testing.T) {
	for _, tc := range []struct{ location, body string }{
		{location: "https://docs.yandex.ru/showcaptchafast?key=secret"},
		{body: `<html><form id="tmgrdfrend-form" action="https://foreign.test/solve"></form></html>`},
	} {
		calls := 0
		client, guest := restrictedTestClient(t, func(*http.Request) (*http.Response, error) {
			calls++
			if tc.location != "" {
				return restrictedResponse(302, "", http.Header{"Location": {tc.location}}), nil
			}
			return restrictedResponse(200, tc.body, http.Header{}), nil
		})
		if _, err := authorizeRestrictedVolga(context.Background(), "https://disk.yandex.ru/i/test", client, guest); !errors.Is(err, ErrCaptchaRequired) || calls != 1 {
			t.Fatalf("CAPTCHA did not fail closed: error=%v requests=%d", err, calls)
		}
	}
}

func TestRestrictedVolgaRejectsEditDenialAndUntrustedAuth(t *testing.T) {
	for _, tc := range []struct {
		page string
		want error
	}{
		{restrictedVolgaPage("view"), ErrEditPermission},
		{restrictedVolgaPage(""), ErrEditPermission},
		{strings.Replace(restrictedVolgaPage("edit"), "https://volga.yandex.ru/auth/initial", "https://foreign.test/auth/initial", 1), egresspolicy.ErrProviderURL},
		{strings.Replace(restrictedVolgaPage("edit"), "/auth/initial", "/arbitrary-post", 1), egresspolicy.ErrProviderURL},
		{strings.Replace(restrictedVolgaPage("edit"), `"guest-access-token"`, `""`, 1), ErrProviderResponse},
	} {
		calls := 0
		client, guest := restrictedTestClient(t, func(*http.Request) (*http.Response, error) {
			calls++
			return restrictedResponse(200, tc.page, http.Header{}), nil
		})
		if _, err := authorizeRestrictedVolga(context.Background(), "https://disk.yandex.ru/i/test", client, guest); !errors.Is(err, tc.want) || calls != 1 {
			t.Fatalf("config rejection error=%v requests=%d want=%v", err, calls, tc.want)
		}
	}
}

func TestRestrictedVolgaRejectsInjectedSessionPathAndAccountCookie(t *testing.T) {
	for _, tc := range []struct {
		path, cookie string
		want         error
	}{
		{"../private", "", ErrProviderResponse},
		{"path?injected=1", "", ErrProviderResponse},
		{"guest-path", "Session_id=account-secret; Domain=yandex.ru; Secure; Path=/", ErrAccountCookies},
	} {
		calls := 0
		client, guest := restrictedTestClient(t, func(*http.Request) (*http.Response, error) {
			calls++
			if calls == 1 {
				return restrictedResponse(200, restrictedVolgaPage("edit"), http.Header{}), nil
			}
			return restrictedResponse(302, "", http.Header{"Location": {restrictedVolgaLocation(tc.path)}, "Set-Cookie": {tc.cookie}}), nil
		})
		if _, err := authorizeRestrictedVolga(context.Background(), "https://disk.yandex.ru/i/test", client, guest); !errors.Is(err, tc.want) || calls != 2 {
			t.Fatalf("session rejection: error=%v requests=%d want=%v", err, calls, tc.want)
		}
	}
}

func TestRestrictedVolgaRelayBoundsResponseAndStopsRedirects(t *testing.T) {
	for _, status := range []int{200, 302} {
		var shared atomic.Pointer[volgaAuth]
		client, guest := restrictedTestClient(t, func(req *http.Request) (*http.Response, error) {
			if req.URL.Host != "volga.yandex.ru" || req.URL.Path != "/session/main/guest-path/relay" {
				t.Fatal("relay destination changed")
			}
			if status == 302 {
				return restrictedResponse(status, "", http.Header{"Location": {"https://127.0.0.1/private"}}), nil
			}
			return restrictedResponse(status, strings.Repeat("x", restrictedHTMLLimit+1), http.Header{}), nil
		})
		shared.Store(&volgaAuth{Session: &http.Client{Jar: guest}, Token: "guest", RequestPath: "guest-path", UserID: 1})
		relay := newRelayClient(&shared, SlimVolgaConfig(), &VolgaStats{})
		restrictRelayClient(relay)
		relay.httpClient = client
		err := relay.sendBatch([][]byte{{1, 2, 3}})
		relay.Stop()
		if status == 200 && !errors.Is(err, ErrProviderBodyLimit) {
			t.Fatalf("relay body was not bounded: %v", err)
		}
		if status == 302 && err == nil {
			t.Fatal("relay redirect accepted")
		}
	}
}
