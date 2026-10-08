package yandex

import (
	"context"
	"encoding/base64"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/egresspolicy"
)

const restrictedBootstrapPayload = `{"document":{"key":"document-key","fileType":"docx","url":"https://internal-provider.test/file","title":"test.docx","permissions":{"edit":true}},"editorConfig":{"user":{"id":"guest1"}}}`

// These compact JWT fixtures have dummy signatures. They exercise input
// validation only and do not represent verified provider authentication.
func restrictedBootstrapToken(header, payload string) string {
	encode := base64.RawURLEncoding.EncodeToString
	return encode([]byte(header)) + "." + encode([]byte(payload)) + "." + encode(make([]byte, 32))
}

func restrictedBootstrapFixture() GuestBootstrap {
	return GuestBootstrap{
		BalancerURL: "https://observed_guest_1.onlyoffice.disk.yandex.net",
		Token:       restrictedBootstrapToken(`{"alg":"HS256","typ":"JWT"}`, restrictedBootstrapPayload),
		ValidUntil:  time.Now().Add(10 * time.Minute),
	}
}

func TestRestrictedGuestBootstrapConstructorValidatesAndCopiesInput(t *testing.T) {
	for _, balancer := range []string{
		"https://onlyoffice.disk.yandex.net",
		"https://onlyoffice.disk.yandex.net/",
		"https://observed_guest_1.onlyoffice.disk.yandex.net",
	} {
		t.Run(balancer, func(t *testing.T) {
			bootstrap := restrictedBootstrapFixture()
			bootstrap.BalancerURL = balancer
			tr, err := NewRestrictedYandexDocsTransportWithGuestBootstrap("https://disk.yandex.ru/i/share-token_1", bootstrap, transport.DefaultConfig())
			if err != nil || tr == nil || !tr.restricted || tr.guestBootstrap == nil {
				t.Fatalf("valid bootstrap constructor rejected: %v", err)
			}
			want := bootstrap
			bootstrap.BalancerURL = "https://foreign.test"
			bootstrap.Token = "changed"
			bootstrap.ValidUntil = time.Time{}
			if *tr.guestBootstrap != want {
				t.Fatal("constructor retained mutable caller bootstrap state")
			}
			if err := tr.ApplyCookies(map[string]string{"yandexuid": "imported-guest"}); !errors.Is(err, ErrAccountCookies) {
				t.Fatal("bootstrap transport accepted imported cookies")
			}
		})
	}
	for _, share := range []string{
		"https://docs.yandex.ru/i/token",
		"http://disk.yandex.ru/i/token",
		"https://disk.yandex.ru/i/token?secret=value",
		"https://disk.yandex.ru/i/token/next",
	} {
		if _, err := NewRestrictedYandexDocsTransportWithGuestBootstrap(share, restrictedBootstrapFixture(), transport.DefaultConfig()); !errors.Is(err, ErrRestrictedDocument) {
			t.Fatal("bootstrap bypassed initial share URL validation")
		}
	}
}

func TestRestrictedGuestBootstrapRejectsUntrustedBalancer(t *testing.T) {
	for _, balancer := range []string{
		"https://docs.yandex.ru",
		"https://legacy.docs.yandex.ru",
		"https://foreign.test",
		"https://onlyoffice.disk.yandex.net.foreign.test",
		"https://nested.opaque.onlyoffice.disk.yandex.net",
		"https://evil-onlyoffice.disk.yandex.net",
		"https://127.0.0.1",
		"http://onlyoffice.disk.yandex.net",
		"https://onlyoffice.disk.yandex.net:443",
		"https://user@onlyoffice.disk.yandex.net",
		"https://onlyoffice.disk.yandex.net/other",
		"https://onlyoffice.disk.yandex.net/?token=secret",
		"https://onlyoffice.disk.yandex.net/?",
		"https://onlyoffice.disk.yandex.net/#fragment",
	} {
		t.Run(balancer, func(t *testing.T) {
			bootstrap := restrictedBootstrapFixture()
			bootstrap.BalancerURL = balancer
			_, err := NewRestrictedYandexDocsTransportWithGuestBootstrap("https://disk.yandex.ru/i/test", bootstrap, transport.DefaultConfig())
			if !errors.Is(err, ErrGuestBootstrap) && !errors.Is(err, egresspolicy.ErrProviderURL) {
				t.Fatal("untrusted bootstrap balancer accepted")
			}
		})
	}
}

func TestRestrictedGuestBootstrapRequiresBoundedFutureDeadline(t *testing.T) {
	for _, tc := range []struct {
		name     string
		deadline time.Time
		want     error
	}{
		{"missing", time.Time{}, ErrGuestBootstrap},
		{"past", time.Now().Add(-time.Minute), ErrGuestBootstrapExpired},
		{"overlong", time.Now().Add(16 * time.Minute), ErrGuestBootstrap},
	} {
		t.Run(tc.name, func(t *testing.T) {
			bootstrap := restrictedBootstrapFixture()
			bootstrap.ValidUntil = tc.deadline
			_, err := NewRestrictedYandexDocsTransportWithGuestBootstrap("https://disk.yandex.ru/i/test", bootstrap, transport.DefaultConfig())
			if !errors.Is(err, tc.want) {
				t.Fatalf("deadline rejection: got %v, want %v", err, tc.want)
			}
		})
	}
}

func TestRestrictedGuestBootstrapRejectsMalformedJWT(t *testing.T) {
	valid := restrictedBootstrapFixture().Token
	segments := strings.Split(valid, ".")
	for _, tc := range []struct {
		name, token string
	}{
		{"missing", ""},
		{"notCompact", "guest-editor-token"},
		{"extraSegment", valid + ".extra"},
		{"emptySignature", segments[0] + "." + segments[1] + "."},
		{"shortSignature", segments[0] + "." + segments[1] + "." + base64.RawURLEncoding.EncodeToString(make([]byte, 31))},
		{"paddedBase64", segments[0] + "." + segments[1] + "." + segments[2] + "="},
		{"base64Newline", segments[0] + "." + segments[1] + ".\n" + segments[2]},
		{"nonCanonicalBase64", segments[0] + "." + segments[1] + "." + segments[2][:len(segments[2])-1] + "B"},
		{"invalidBase64", "!" + valid},
		{"noneAlgorithm", restrictedBootstrapToken(`{"alg":"none","typ":"JWT"}`, restrictedBootstrapPayload)},
		{"otherAlgorithm", restrictedBootstrapToken(`{"alg":"HS512","typ":"JWT"}`, restrictedBootstrapPayload)},
		{"missingAlgorithm", restrictedBootstrapToken(`{"typ":"JWT"}`, restrictedBootstrapPayload)},
		{"externalKeyHeader", restrictedBootstrapToken(`{"alg":"HS256","typ":"JWT","jku":"https://foreign.test/key"}`, restrictedBootstrapPayload)},
		{"caseAliasHeader", restrictedBootstrapToken(`{"alg":"HS256","ALG":"HS256","typ":"JWT"}`, restrictedBootstrapPayload)},
		{"duplicateHeaderKey", restrictedBootstrapToken(`{"alg":"HS256","alg":"HS256","typ":"JWT"}`, restrictedBootstrapPayload)},
		{"invalidHeaderJSON", restrictedBootstrapToken(`{"alg":`, restrictedBootstrapPayload)},
		{"invalidPayloadJSON", restrictedBootstrapToken(`{"alg":"HS256","typ":"JWT"}`, `{"document":`)},
		{"trailingPayloadJSON", restrictedBootstrapToken(`{"alg":"HS256","typ":"JWT"}`, restrictedBootstrapPayload+`{}`)},
		{"oversize", restrictedBootstrapToken(`{"alg":"HS256","typ":"JWT"}`, strings.Replace(restrictedBootstrapPayload, "test.docx", strings.Repeat("x", 32<<10), 1))},
	} {
		t.Run(tc.name, func(t *testing.T) {
			bootstrap := restrictedBootstrapFixture()
			bootstrap.Token = tc.token
			_, err := NewRestrictedYandexDocsTransportWithGuestBootstrap("https://disk.yandex.ru/i/test", bootstrap, transport.DefaultConfig())
			if !errors.Is(err, ErrGuestBootstrap) {
				t.Fatal("malformed bootstrap JWT accepted")
			}
		})
	}
}

func TestRestrictedGuestBootstrapRequiresSignedEditAndNestedIdentity(t *testing.T) {
	for _, tc := range []struct {
		name, old, replacement string
	}{
		{"missingEdit", `"edit":true`, `"copy":true`},
		{"readOnly", `"edit":true`, `"edit":false`},
		{"nullEdit", `"edit":true`, `"edit":null`},
		{"stringEdit", `"edit":true`, `"edit":"true"`},
		{"duplicateEdit", `"edit":true`, `"edit":true,"edit":true`},
		{"missingNestedUser", `"editorConfig":{"user":{"id":"guest1"}}`, `"user":{"id":"outer-guest"}`},
		{"emptyNestedUser", `"id":"guest1"`, `"id":""`},
		{"userAlias", `"user":{"id":"guest1"}`, `"User":{"id":"guest1"}`},
		{"idAlias", `"id":"guest1"`, `"ID":"guest1"`},
		{"keyAlias", `"key":"document-key"`, `"Key":"document-key"`},
		{"fileTypeAlias", `"fileType":"docx"`, `"FileType":"docx"`},
		{"urlAlias", `"url":"https://internal-provider.test/file"`, `"URL":"https://internal-provider.test/file"`},
		{"permissionsAlias", `"permissions":{"edit":true}`, `"Permissions":{"edit":true}`},
		{"duplicateNestedUser", `"id":"guest1"`, `"id":"guest1","id":"guest1"`},
		{"duplicateDocumentKey", `"key":"document-key"`, `"key":"document-key","key":"document-key"`},
		{"unsafeDocumentKey", `"key":"document-key"`, `"key":"../escape"`},
		{"unsupportedFileType", `"fileType":"docx"`, `"fileType":"exe"`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			bootstrap := restrictedBootstrapFixture()
			payload := strings.Replace(restrictedBootstrapPayload, tc.old, tc.replacement, 1)
			bootstrap.Token = restrictedBootstrapToken(`{"alg":"HS256","typ":"JWT"}`, payload)
			_, err := NewRestrictedYandexDocsTransportWithGuestBootstrap("https://disk.yandex.ru/i/test", bootstrap, transport.DefaultConfig())
			if !errors.Is(err, ErrGuestBootstrap) {
				t.Fatal("invalid signed bootstrap configuration accepted")
			}
		})
	}
}

func TestRestrictedGuestBootstrapHonorsJWTExpiration(t *testing.T) {
	bootstrap := restrictedBootstrapFixture()
	future := strconv.FormatInt(time.Now().Add(time.Hour).Unix(), 10)
	payload := strings.TrimSuffix(restrictedBootstrapPayload, "}") + `,"exp":` + future + `}`
	bootstrap.Token = restrictedBootstrapToken(`{"alg":"HS256","typ":"JWT"}`, payload)
	if _, err := NewRestrictedYandexDocsTransportWithGuestBootstrap("https://disk.yandex.ru/i/test", bootstrap, transport.DefaultConfig()); err != nil {
		t.Fatalf("valid future JWT expiration rejected: %v", err)
	}
	for _, exp := range []string{`"expired"`, `null`, `0`, `1`} {
		bootstrap := restrictedBootstrapFixture()
		payload := strings.TrimSuffix(restrictedBootstrapPayload, "}") + `,"exp":` + exp + `}`
		bootstrap.Token = restrictedBootstrapToken(`{"alg":"HS256","typ":"JWT"}`, payload)
		_, err := NewRestrictedYandexDocsTransportWithGuestBootstrap("https://disk.yandex.ru/i/test", bootstrap, transport.DefaultConfig())
		if !errors.Is(err, ErrGuestBootstrap) && !errors.Is(err, ErrGuestBootstrapExpired) {
			t.Fatal("invalid or expired JWT expiration accepted")
		}
	}
}

func TestRestrictedGuestBootstrapAcceptsOnlyObservedProviderInternalHTTPSource(t *testing.T) {
	for _, tc := range []struct {
		url string
		ok  bool
	}{
		{"http://localhost:12701/opaque-provider-source", true},
		{"http://localhost/opaque-provider-source", false},
		{"http://localhost:12702/opaque-provider-source", false},
		{"http://127.0.0.1:12701/opaque-provider-source", false},
		{"http://localhost.foreign.test:12701/source", false},
		{"http://foreign.test:12701/source", false},
		{"http://user@localhost:12701/source", false},
		{"http://localhost:12701/source#fragment", false},
	} {
		bootstrap := restrictedBootstrapFixture()
		bootstrap.Token = restrictedBootstrapToken(`{"alg":"HS256","typ":"JWT"}`, strings.Replace(restrictedBootstrapPayload, "https://internal-provider.test/file", tc.url, 1))
		info, err := parseRestrictedGuestBootstrap(bootstrap, "guest")
		if tc.ok {
			if err != nil || info.OpenCmd["url"] != tc.url {
				t.Fatalf("observed signed internal source was not retained unchanged: %v", err)
			}
		} else if !errors.Is(err, ErrGuestBootstrap) {
			t.Fatal("unobserved HTTP document source accepted")
		}
	}
}

func TestRestrictedGuestBootstrapFetchUsesOnlySameOriginPublicAsset(t *testing.T) {
	bootstrap := restrictedBootstrapFixture()
	calls := 0
	client, guest := restrictedTestClient(t, func(req *http.Request) (*http.Response, error) {
		calls++
		if req.Method != http.MethodGet || req.URL.String() != bootstrap.BalancerURL+"/web-apps/apps/api/documents/api.js" {
			t.Fatal("bootstrap fetched frontend or changed editor asset origin/path")
		}
		if req.Header.Get("Authorization") != "" || req.Header.Get("Cookie") != "" {
			t.Fatal("fresh bootstrap asset request included credentials")
		}
		return restrictedResponse(200, `var build="?_dc=2025.4.3-987";`, http.Header{
			"Set-Cookie": {"ymex=bootstrap-guest; Domain=onlyoffice.disk.yandex.net; Path=/; Secure", "unknown=discard; Path=/; Secure"},
		}), nil
	})
	info, err := fetchRestrictedGuestBootstrap(context.Background(), bootstrap, "local-guest", client, guest)
	if err != nil {
		t.Fatalf("valid guest bootstrap rejected: %v", err)
	}
	if calls != 1 || info.Host != "observed_guest_1.onlyoffice.disk.yandex.net" || info.Origin != bootstrap.BalancerURL || info.Token != bootstrap.Token || info.DocID != "document-key" || info.EditorUserID != "guest1" {
		t.Fatal("bootstrap lost signed document or guest identity")
	}
	if info.WsURL != "wss://observed_guest_1.onlyoffice.disk.yandex.net/2025.4.3-987/doc/document-key/c/?EIO=4&transport=websocket" {
		t.Fatal("bootstrap did not use the observed dynamic editor build")
	}
	if info.OpenCmd["userid"] != "local-guest" || info.OpenCmd["url"] != "https://internal-provider.test/file" || info.OpenCmd["title"] != "test.docx" || info.OpenCmd["format"] != "docx" || info.Permissions["edit"] != true || info.IsExcel {
		t.Fatal("bootstrap altered signed document parameters")
	}
	if info.CookieStr != "" {
		t.Fatal("public bootstrap metadata cookies became WebSocket credentials")
	}
}

func TestRestrictedGuestBootstrapPreservesPaddedDocumentKeyInWebsocketPath(t *testing.T) {
	bootstrap := restrictedBootstrapFixture()
	bootstrap.Token = restrictedBootstrapToken(`{"alg":"HS256","typ":"JWT"}`, strings.Replace(restrictedBootstrapPayload, "document-key", "document-key=", 1))
	client, guest := restrictedTestClient(t, func(*http.Request) (*http.Response, error) {
		return restrictedResponse(200, restrictedTestAsset(), http.Header{}), nil
	})
	info, err := fetchRestrictedGuestBootstrap(context.Background(), bootstrap, "guest", client, guest)
	if err != nil || info.DocID != "document-key=" || !strings.Contains(info.WsURL, "/doc/document-key=/c/?") || info.OpenCmd["id"] != "document-key=" {
		t.Fatalf("signed document key padding was changed: %v", err)
	}
}

func TestRestrictedGuestBootstrapFetchRechecksDeadlineBeforeNetwork(t *testing.T) {
	bootstrap := restrictedBootstrapFixture()
	if _, err := NewRestrictedYandexDocsTransportWithGuestBootstrap("https://disk.yandex.ru/i/test", bootstrap, transport.DefaultConfig()); err != nil {
		t.Fatal(err)
	}
	bootstrap.ValidUntil = time.Now().Add(-time.Second)
	calls := 0
	client, guest := restrictedTestClient(t, func(*http.Request) (*http.Response, error) {
		calls++
		return restrictedResponse(200, restrictedTestAsset(), http.Header{}), nil
	})
	if _, err := fetchRestrictedGuestBootstrap(context.Background(), bootstrap, "local-guest", client, guest); !errors.Is(err, ErrGuestBootstrapExpired) || calls != 0 {
		t.Fatal("expired bootstrap reached network")
	}
}

func TestRestrictedGuestBootstrapFetchRechecksJWTExpirationAfterMetadata(t *testing.T) {
	bootstrap := restrictedBootstrapFixture()
	expiry := time.Now().Add(2 * time.Second).Unix()
	payload := strings.TrimSuffix(restrictedBootstrapPayload, "}") + `,"exp":` + strconv.FormatInt(expiry, 10) + `}`
	bootstrap.Token = restrictedBootstrapToken(`{"alg":"HS256","typ":"JWT"}`, payload)
	calls := 0
	client, guest := restrictedTestClient(t, func(*http.Request) (*http.Response, error) {
		calls++
		time.Sleep(time.Until(time.Unix(expiry, 0)) + 10*time.Millisecond)
		return restrictedResponse(200, restrictedTestAsset(), http.Header{}), nil
	})
	if _, err := fetchRestrictedGuestBootstrap(context.Background(), bootstrap, "local-guest", client, guest); !errors.Is(err, ErrGuestBootstrapExpired) || calls != 1 {
		t.Fatal("bootstrap JWT that expired during metadata fetch was accepted")
	}
}

func TestRestrictedGuestBootstrapFetchRejectsRedirectAndAccountCookie(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		header http.Header
		want   error
	}{
		{"sameOriginRedirect", 302, http.Header{"Location": {"/other.js"}}, ErrEditorBuild},
		{"foreignRedirect", 302, http.Header{"Location": {"https://foreign.test/api.js"}}, ErrEditorBuild},
		{"accountCookie", 200, http.Header{"Set-Cookie": {"Session_id=account-secret; Path=/; Secure"}}, ErrAccountCookies},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			client, guest := restrictedTestClient(t, func(*http.Request) (*http.Response, error) {
				calls++
				return restrictedResponse(tc.status, restrictedTestAsset(), tc.header), nil
			})
			_, err := fetchRestrictedGuestBootstrap(context.Background(), restrictedBootstrapFixture(), "local-guest", client, guest)
			if !errors.Is(err, tc.want) || calls != 1 {
				t.Fatalf("bootstrap response rejection: got %v after %d requests", err, calls)
			}
		})
	}
}
