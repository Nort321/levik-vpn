package yandex

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"testing"

	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/egresspolicy"
	"golang.org/x/net/publicsuffix"
)

type restrictedRoundTrip func(*http.Request) (*http.Response, error)

func (f restrictedRoundTrip) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func restrictedTestPage(edit string) string {
	return clientConfigPage(`{"officeType":"only_office","officeActionData":{"office_online_editor_type":"only_office","balancer_url":"https://legacy.docs.yandex.ru",` +
		`"editor_config":{"token":"guest-editor-token","editorConfig":{"user":{"id":"guest1"}},` +
		`"document":{"key":"document-key","fileType":"docx","url":"https://internal-provider.test/file",` +
		`"title":"test.docx","permissions":{"edit":` + edit + `}}}}}`)
}

func restrictedTestAsset() string {
	return `var build="?_dc=2026.2.1-2268";`
}

func restrictedTestClient(t *testing.T, roundTrip restrictedRoundTrip) (*http.Client, *guestCookieJar) {
	t.Helper()
	jar, err := cookiejar.New(&cookiejar.Options{PublicSuffixList: publicsuffix.List})
	if err != nil {
		t.Fatal(err)
	}
	guest := &guestCookieJar{jar: jar}
	return &http.Client{
		Transport: roundTrip, Jar: guest,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}, guest
}

func restrictedResponse(status int, body string, header http.Header) *http.Response {
	return &http.Response{StatusCode: status, Header: header, Body: io.NopCloser(strings.NewReader(body)), ContentLength: -1}
}

func TestRestrictedConstructorAcceptsOnlyInitialShareLinks(t *testing.T) {
	tr, err := NewRestrictedYandexDocsTransport("https://disk.yandex.ru/i/share-token_1", transport.DefaultConfig())
	if err != nil || tr == nil || !tr.restricted {
		t.Fatalf("valid constructor: %v", err)
	}
	for _, raw := range []string{
		"https://docs.yandex.ru/i/token", "http://disk.yandex.ru/i/token", "https://disk.yandex.ru/i/token?x=1",
		"https://disk.yandex.ru/i/token?", "https://disk.yandex.ru/i/token#fragment", "https://disk.yandex.ru/d/token",
		"https://disk.yandex.ru/i/", "https://disk.yandex.ru/i/token/next", "https://disk.yandex.ru/i/to%6ben",
		"https://user@disk.yandex.ru/i/token", "https://disk.yandex.ru:443/i/token", "https://disk.yandex.ru/i/../token",
	} {
		if _, err := NewRestrictedYandexDocsTransport(raw, transport.DefaultConfig()); !errors.Is(err, ErrRestrictedDocument) {
			t.Errorf("expected initial-link rejection for %q: %v", raw, err)
		}
	}
	if NewYandexDocsTransport("http://upstream.example/test", transport.DefaultConfig()).restricted {
		t.Fatal("upstream constructor became restricted")
	}
	if err := tr.ApplyCookies(map[string]string{"yandexuid": "imported-guest"}); !errors.Is(err, ErrAccountCookies) {
		t.Fatalf("imported client cookie accepted: %v", err)
	}
	if cookies, err := tr.FetchCookies(); err != nil || len(cookies) != 0 {
		t.Fatal("restricted transport exported cookies")
	}
}

func TestRestrictedFetchScopesGuestCookiesToWebsocket(t *testing.T) {
	calls := 0
	client, guest := restrictedTestClient(t, func(r *http.Request) (*http.Response, error) {
		calls++
		if calls == 1 {
			return restrictedResponse(302, "", http.Header{
				"Location":   {"https://docs.yandex.ru/editor"},
				"Set-Cookie": {"yandexuid=host-only; Path=/; Secure", "ymex=domain-guest; Domain=yandex.ru; Path=/; Secure", "unknown=discard; Domain=yandex.ru; Path=/; Secure"},
			}), nil
		}
		if r.Header.Get("Cookie") != "ymex=domain-guest" {
			t.Fatalf("redirect leaked host-only/unknown cookies: %q", r.Header.Get("Cookie"))
		}
		if calls == 3 {
			if r.URL.Host != "legacy.docs.yandex.ru" || r.URL.Path != "/web-apps/apps/api/documents/api.js" {
				t.Fatal("editor build request changed origin/path")
			}
			return restrictedResponse(200, restrictedTestAsset(), http.Header{}), nil
		}
		return restrictedResponse(200, restrictedTestPage("true"), http.Header{}), nil
	})
	info, err := fetchRestrictedDocInfo(context.Background(), "https://disk.yandex.ru/i/test", "00001", client, guest)
	if err != nil {
		t.Fatal(err)
	}
	if calls != 3 || info.CookieStr != "ymex=domain-guest" || info.Host != "legacy.docs.yandex.ru" {
		t.Fatalf("unexpected scoped result: calls=%d cookie=%q host=%q", calls, info.CookieStr, info.Host)
	}
	if info.EditorUserID != "guest1" || info.WsURL != "wss://legacy.docs.yandex.ru/2026.2.1-2268/doc/document-key/c/?EIO=4&transport=websocket" {
		t.Fatal("nested guest identity or dynamic editor build missing")
	}
}

func TestRestrictedFetchRejectsRedirectsBeforeFollowing(t *testing.T) {
	for _, tc := range []struct {
		name, location string
		want           error
	}{
		{"foreign", "https://foreign.test/editor", egresspolicy.ErrProviderURL},
		{"lookalike", "https://docs.yandex.ru.foreign.test/", egresspolicy.ErrProviderURL},
		{"private", "https://127.0.0.1/editor", egresspolicy.ErrProviderURL},
		{"nonTLS", "http://docs.yandex.ru/editor", egresspolicy.ErrProviderURL},
		{"port", "https://docs.yandex.ru:8443/editor", egresspolicy.ErrProviderURL},
		{"login", "https://passport.yandex.ru/auth", ErrLoginRequired},
		{"captcha", "https://docs.yandex.ru/showcaptchafast?key=secret", ErrCaptchaRequired},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			client, guest := restrictedTestClient(t, func(*http.Request) (*http.Response, error) {
				calls++
				return restrictedResponse(302, "", http.Header{"Location": {tc.location}}), nil
			})
			_, err := fetchRestrictedDocInfo(context.Background(), "https://disk.yandex.ru/i/test", "guest", client, guest)
			if !errors.Is(err, tc.want) || calls != 1 {
				t.Fatalf("got %v and %d requests; want %v and 1", err, calls, tc.want)
			}
		})
	}
}

func TestRestrictedFetchBoundsHTMLAndRedirectCount(t *testing.T) {
	for _, advertised := range []int64{-1, restrictedHTMLLimit + 1} {
		client, guest := restrictedTestClient(t, func(*http.Request) (*http.Response, error) {
			resp := restrictedResponse(200, strings.Repeat("x", restrictedHTMLLimit+1), http.Header{})
			resp.ContentLength = advertised
			return resp, nil
		})
		if _, err := fetchRestrictedDocInfo(context.Background(), "https://disk.yandex.ru/i/test", "guest", client, guest); !errors.Is(err, ErrProviderBodyLimit) {
			t.Fatalf("expected bounded body rejection: %v", err)
		}
	}
	calls := 0
	client, guest := restrictedTestClient(t, func(*http.Request) (*http.Response, error) {
		calls++
		return restrictedResponse(302, "", http.Header{"Location": {"/loop"}}), nil
	})
	if _, err := fetchRestrictedDocInfo(context.Background(), "https://disk.yandex.ru/i/test", "guest", client, guest); !errors.Is(err, ErrProviderResponse) || calls != 10 {
		t.Fatalf("redirect loop not bounded: calls=%d error=%v", calls, err)
	}
}

func TestRestrictedFetchRejectsAccountCookie(t *testing.T) {
	client, guest := restrictedTestClient(t, func(*http.Request) (*http.Response, error) {
		return restrictedResponse(200, restrictedTestPage("true"), http.Header{"Set-Cookie": {"Session_id=account-secret; Domain=yandex.ru; Path=/; Secure"}}), nil
	})
	if _, err := fetchRestrictedDocInfo(context.Background(), "https://disk.yandex.ru/i/test", "guest", client, guest); !errors.Is(err, ErrAccountCookies) {
		t.Fatalf("account cookie accepted: %v", err)
	}
	u, _ := url.Parse("https://disk.yandex.ru/")
	if len(guest.jar.Cookies(u)) != 0 {
		t.Fatal("account cookie persisted")
	}
}

func TestRestrictedParserRequiresEditAndSafeLegacyConfig(t *testing.T) {
	for _, edit := range []string{"false", "null", `"true"`, "1"} {
		if _, err := parseRestrictedDocInfo([]byte(restrictedTestPage(edit)), "guest"); !errors.Is(err, ErrEditPermission) {
			t.Errorf("edit permission %s accepted: %v", edit, err)
		}
	}
	for _, tc := range []struct {
		body string
		want error
	}{
		{`<html>public viewer</html>`, ErrLegacyEditor},
		{`<form id="tmgrdfrend-form" action="https://foreign.test/solve">`, ErrCaptchaRequired},
		{clientConfigPage(`{"officeActionData":{"officeType":"volga"}}`), ErrLegacyEditor},
		{strings.Replace(restrictedTestPage("true"), "legacy.docs.yandex.ru", "balancer.foreign.test", 1), egresspolicy.ErrProviderURL},
		{strings.Replace(restrictedTestPage("true"), `"document-key"`, `"../escape"`, 1), ErrProviderResponse},
		{strings.Replace(restrictedTestPage("true"), `"guest-editor-token"`, `""`, 1), ErrProviderResponse},
		{strings.Replace(restrictedTestPage("true"), `"docx"`, `"exe"`, 1), ErrLegacyEditor},
	} {
		if _, err := parseRestrictedDocInfo([]byte(tc.body), "guest"); !errors.Is(err, tc.want) {
			t.Errorf("expected %v: got %v", tc.want, err)
		}
	}
}

func TestRestrictedHTTPClientDoesNotUseProxy(t *testing.T) {
	client := restrictedHTTPClient(nil)
	tr := client.Transport.(*http.Transport)
	if tr.Proxy != nil || tr.DialContext == nil || client.Timeout != restrictedAttemptTimeout || tr.MaxResponseHeaderBytes == 0 {
		t.Fatal("restricted HTTP transport policy is missing")
	}
}

func TestRestrictedFetchDoesNotMistakeEditorAssetsForCaptcha(t *testing.T) {
	client, guest := restrictedTestClient(t, func(r *http.Request) (*http.Response, error) {
		if r.URL.Path == "/web-apps/apps/api/documents/api.js" {
			return restrictedResponse(200, restrictedTestAsset(), http.Header{}), nil
		}
		body := restrictedTestPage("true") + `<script>var errorPath="/showcaptchafast"</script>`
		return restrictedResponse(200, body, http.Header{}), nil
	})
	if _, err := fetchRestrictedDocInfo(context.Background(), "https://disk.yandex.ru/i/test", "guest", client, guest); err != nil {
		t.Fatalf("valid editor asset was classified as CAPTCHA: %v", err)
	}
}

func TestRestrictedGuestJarRetainsConfirmedAnonymousChallengeCookies(t *testing.T) {
	_, guest := restrictedTestClient(t, nil)
	docs, _ := url.Parse("https://docs.yandex.ru/showcaptchafast")
	guest.SetCookies(docs, []*http.Cookie{
		{Name: "_yasc", Value: "guest-yasc", Secure: true, Path: "/", Domain: "docs.yandex.ru"},
		{Name: "i", Value: "guest-i", Secure: true, Path: "/", Domain: "yandex.ru"},
		{Name: "yashr", Value: "guest-yashr", Secure: true, Path: "/showcaptchafast"},
		{Name: "yandex_360_session_exp_cache", Value: "unneeded-cache", Secure: true, Path: "/"},
	})
	if guest.rejected.Load() {
		t.Fatal("confirmed anonymous cookies rejected as account credentials")
	}
	if len(guest.Cookies(docs)) != 3 {
		t.Fatal("confirmed anonymous challenge state was not retained")
	}
	check, _ := url.Parse("https://docs.yandex.ru/checkcaptchafast")
	checkCookies := guest.Cookies(check)
	if len(checkCookies) != 2 {
		t.Fatal("cookie path scope was not respected")
	}
	push, _ := url.Parse("https://push.yandex.ru/v2/subscribe/websocket")
	pushCookies := guest.Cookies(push)
	if len(pushCookies) != 1 || pushCookies[0].Name != "i" {
		t.Fatal("cookie domain scope was not respected")
	}
	foreign, _ := url.Parse("https://foreign.test/")
	if len(guest.Cookies(foreign)) != 0 {
		t.Fatal("anonymous state leaked to a foreign domain")
	}
}
