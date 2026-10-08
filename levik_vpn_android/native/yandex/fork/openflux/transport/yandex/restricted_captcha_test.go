package yandex

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"net/http"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/egresspolicy"
)

func restrictedChallengeHTML(action string) string {
	ssr := `{"uniqueKey":"guest-proof-key","action":"blink","pow":{"complexity":0,"prefix":"abcd"},"timestamp":1234567890}`
	return `<script>window.__SSR_DATA__ = JSON.parse(atob("` + base64.StdEncoding.EncodeToString([]byte(ssr)) + `"))</script>` +
		`<form id="tmgrdfrend-form" action="` + action + `"></form>`
}

func TestRestrictedFastChallengeRequiresExplicitOptIn(t *testing.T) {
	native, err := NewRestrictedYandexDocsTransport("https://disk.yandex.ru/i/test", transport.DefaultConfig())
	if err != nil || native.fastChallengeAllowed.Load() {
		t.Fatal("native challenge enabled by default")
	}
	if native.EnableRestrictedFastChallenge() != nil || !native.fastChallengeAllowed.Load() {
		t.Fatal("native challenge opt-in failed")
	}
	volga, err := NewRestrictedYandexVolgaTransport("https://disk.yandex.ru/i/test", transport.DefaultConfig())
	if err != nil || volga.fastChallengeAllowed.Load() || volga.EnableRestrictedFastChallenge() != nil {
		t.Fatal("Volga challenge opt-in failed")
	}
	if NewYandexDocsTransport("https://disk.yandex.ru/i/test", transport.DefaultConfig()).EnableRestrictedFastChallenge() == nil {
		t.Fatal("upstream transport accepted restricted opt-in")
	}
}

func TestRestrictedFastChallengeUsesOnlyFixedOriginAndOneProof(t *testing.T) {
	calls := 0
	client, guest := restrictedTestClient(t, func(req *http.Request) (*http.Response, error) {
		calls++
		switch calls {
		case 1:
			return restrictedResponse(302, "", http.Header{"Location": {"https://docs.yandex.ru/showcaptchafast?d=test"}}), nil
		case 2:
			return restrictedResponse(200, restrictedChallengeHTML("https://docs.yandex.ru/checkcaptchafast?d=test&amp;retpath=test&amp;s=test"), http.Header{}), nil
		case 3:
			if req.Method != http.MethodPost || req.URL.Host != "docs.yandex.ru" || req.URL.Path != "/checkcaptchafast" {
				t.Fatal("challenge submitted outside the fixed endpoint")
			}
			if req.URL.Query().Get("retpath") != "test" || req.URL.Query().Get("s") != "test" {
				t.Fatal("HTML form query entities were not decoded")
			}
			if err := req.ParseForm(); err != nil || req.PostForm.Get("fingerprint") == "" || req.PostForm.Get("uniquekey") != "guest-proof-key" {
				t.Fatal("invalid proof form")
			}
			return restrictedResponse(302, "", http.Header{"Location": {"https://docs.yandex.ru/editor"}, "Set-Cookie": {"spravka=guest-proof; Path=/; Secure"}}), nil
		case 4:
			return restrictedResponse(200, restrictedTestPage("true"), http.Header{}), nil
		case 5:
			return restrictedResponse(200, restrictedTestAsset(), http.Header{}), nil
		default:
			t.Fatal("unexpected challenge network request")
			return nil, nil
		}
	})
	used := &atomic.Bool{}
	guest.allowFastChallenge, guest.fastChallengeUsed = true, used
	if _, err := fetchRestrictedDocInfo(context.Background(), "https://disk.yandex.ru/i/test", "guest", client, guest); err != nil || calls != 5 || !used.Load() {
		t.Fatalf("challenge flow failed: %v; calls=%d", err, calls)
	}
	challenge, _ := url.Parse("https://docs.yandex.ru/showcaptchafast?d=repeat")
	if _, err := solveRestrictedFastChallenge(context.Background(), challenge, client, guest); !errors.Is(err, ErrCaptchaRequired) || calls != 5 {
		t.Fatal("second challenge was attempted")
	}
}

func TestRestrictedFastChallengeRejectsUnsafeFormAndRedirect(t *testing.T) {
	for _, tc := range []struct {
		action, redirect string
		want             error
	}{
		{"https://foreign.test/checkcaptchafast", "", ErrCaptchaRequired},
		{"http://docs.yandex.ru/checkcaptchafast", "", ErrCaptchaRequired},
		{"https://docs.yandex.ru/arbitrary-post", "", ErrCaptchaRequired},
		{"https://docs.yandex.ru:8443/checkcaptchafast", "", ErrCaptchaRequired},
		{"https://docs.yandex.ru/checkcaptchafast", "https://foreign.test/steal", egresspolicy.ErrProviderURL},
		{"https://docs.yandex.ru/checkcaptchafast", "https://docs.yandex.ru/showcaptcha", ErrCaptchaRequired},
	} {
		calls := 0
		client, guest := restrictedTestClient(t, func(*http.Request) (*http.Response, error) {
			calls++
			if calls == 1 {
				return restrictedResponse(200, restrictedChallengeHTML(tc.action), http.Header{}), nil
			}
			return restrictedResponse(302, "", http.Header{"Location": {tc.redirect}}), nil
		})
		guest.allowFastChallenge, guest.fastChallengeUsed = true, &atomic.Bool{}
		challenge, _ := url.Parse("https://docs.yandex.ru/showcaptchafast?d=test")
		if _, err := solveRestrictedFastChallenge(context.Background(), challenge, client, guest); !errors.Is(err, tc.want) {
			t.Fatalf("unsafe challenge accepted: %v want %v", err, tc.want)
		}
		if tc.redirect == "" && calls != 1 {
			t.Fatal("unsafe form was submitted")
		}
	}
}

func TestRestrictedFastChallengeDoesNotSolveInteractiveCaptcha(t *testing.T) {
	calls := 0
	client, guest := restrictedTestClient(t, func(*http.Request) (*http.Response, error) {
		calls++
		return restrictedResponse(302, "", http.Header{"Location": {"https://docs.yandex.ru/showcaptcha"}}), nil
	})
	guest.allowFastChallenge, guest.fastChallengeUsed = true, &atomic.Bool{}
	if _, _, err := fetchRestrictedPage(context.Background(), "https://disk.yandex.ru/i/test", client, guest); !errors.Is(err, ErrCaptchaRequired) || calls != 1 || guest.fastChallengeUsed.Load() {
		t.Fatal("interactive challenge was attempted")
	}
}

func TestRestrictedPoWRejectsExcessAndCancellation(t *testing.T) {
	for _, tc := range []struct {
		prefix     string
		complexity int
	}{
		{"abcd", -1}, {"abcd", restrictedPowMaxComplexity + 1}, {"invalid", 0}, {strings.Repeat("ab", 257), 0}, {"", 0},
	} {
		if _, err := solveRestrictedPoW(context.Background(), tc.prefix, tc.complexity); !errors.Is(err, ErrCaptchaRequired) {
			t.Fatal("unbounded proof input accepted")
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := solveRestrictedPoW(ctx, "abcd", 0); !errors.Is(err, ErrCaptchaRequired) {
		t.Fatal("proof ignored context cancellation")
	}
	nonce, err := solveRestrictedPoW(context.Background(), "abcd", 8)
	if err != nil {
		t.Fatal(err)
	}
	decoded, _ := hexDecode(nonce)
	prefix, _ := hexDecode("abcd")
	hash := sha256.Sum256(append(decoded, prefix...))
	if !captchaCheckComplexity(hash[:], 8) {
		t.Fatal("proof does not satisfy the provider check")
	}
}
