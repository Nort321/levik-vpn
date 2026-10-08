package yandex

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"testing"

	"github.com/p1neappleXpress/OpenFlux/transport/egresspolicy"
)

func TestRestrictedEditorBuildRequiresUniqueStrictBuild(t *testing.T) {
	for _, tc := range []struct {
		name, body, want string
	}{
		{"observed", restrictedTestAsset(), "2026.2.1-2268"},
		{"query", `asset.js?a=1&_dc=2026.2.1-2268&b=2`, "2026.2.1-2268"},
		{"missing", `DocEditor.version=function(){return"2026.2.1"}`, ""},
		{"noBuild", `?_dc=2026.2.1`, ""},
		{"duplicate", restrictedTestAsset() + restrictedTestAsset(), ""},
		{"conflicting", restrictedTestAsset() + `?_dc=2024.1.1-375`, ""},
		{"path", `?_dc=2026.2.1-2268/../../`, ""},
		{"suffix", `?_dc=2026.2.1-2268evil`, ""},
		{"overlong", `?_dc=20260.2.1-2268`, ""},
		{"encoded", `?_dc=2026%2e2%2e1-2268`, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			build, err := parseRestrictedEditorBuild([]byte(tc.body))
			if tc.want == "" {
				if !errors.Is(err, ErrEditorBuild) || build != "" {
					t.Fatal("unsupported editor build accepted")
				}
			} else if err != nil || build != tc.want {
				t.Fatalf("valid editor build rejected: %v", err)
			}
		})
	}
}

func TestRestrictedEditorBuildFetchIsFixedOriginAndBounded(t *testing.T) {
	const host = "observed_guest_1.onlyoffice.disk.yandex.net"
	client, guest := restrictedTestClient(t, func(req *http.Request) (*http.Response, error) {
		if req.Method != http.MethodGet || req.URL.Scheme != "https" || req.URL.Host != host || req.URL.Path != "/web-apps/apps/api/documents/api.js" || req.URL.RawQuery != "" {
			t.Fatal("editor asset request changed origin or path")
		}
		return restrictedResponse(200, restrictedTestAsset(), http.Header{}), nil
	})
	if build, err := fetchRestrictedEditorBuild(context.Background(), host, client, guest); err != nil || build != "2026.2.1-2268" {
		t.Fatalf("observed public asset rejected: %v", err)
	}
	for _, location := range []string{"https://" + host + "/other.js", "https://foreign.test/api.js", "https://127.0.0.1/api.js"} {
		calls := 0
		client, guest := restrictedTestClient(t, func(*http.Request) (*http.Response, error) {
			calls++
			return restrictedResponse(302, "", http.Header{"Location": {location}}), nil
		})
		if _, err := fetchRestrictedEditorBuild(context.Background(), host, client, guest); !errors.Is(err, ErrEditorBuild) || calls != 1 {
			t.Fatal("editor asset redirect followed")
		}
	}
	for _, length := range []int64{-1, restrictedEditorAssetLimit + 1} {
		client, guest := restrictedTestClient(t, func(*http.Request) (*http.Response, error) {
			resp := restrictedResponse(200, strings.Repeat("x", restrictedEditorAssetLimit+1), http.Header{})
			resp.ContentLength = length
			return resp, nil
		})
		if _, err := fetchRestrictedEditorBuild(context.Background(), host, client, guest); !errors.Is(err, ErrProviderBodyLimit) {
			t.Fatal("unbounded editor asset accepted")
		}
	}
}

func TestRestrictedEditorBuildRejectsInvalidHostAndAccountState(t *testing.T) {
	calls := 0
	client, guest := restrictedTestClient(t, func(*http.Request) (*http.Response, error) {
		calls++
		return restrictedResponse(200, restrictedTestAsset(), http.Header{"Set-Cookie": {"Session_id=account-secret; Path=/; Secure"}}), nil
	})
	if _, err := fetchRestrictedEditorBuild(context.Background(), "foreign.test", client, guest); !errors.Is(err, egresspolicy.ErrProviderURL) || calls != 0 {
		t.Fatal("invalid asset origin reached network")
	}
	if _, err := fetchRestrictedEditorBuild(context.Background(), "onlyoffice.disk.yandex.net", client, guest); !errors.Is(err, ErrAccountCookies) || calls != 1 {
		t.Fatal("account state from editor asset accepted")
	}
}

func TestRestrictedParserUsesNestedGuestIdentity(t *testing.T) {
	body := strings.Replace(restrictedTestPage("true"), `"editorConfig":{"user":{"id":"guest1"}}`, `"user":{"id":"outer-id"},"editorConfig":{"user":{"id":"nested-id"}}`, 1)
	info, err := parseRestrictedDocInfo([]byte(body), "local-id")
	if err != nil || info.EditorUserID != "nested-id" {
		t.Fatal("nested provider identity was not selected")
	}
	body = strings.Replace(restrictedTestPage("true"), `"editorConfig":{"user":{"id":"guest1"}}`, `"user":{"id":"older-id"}`, 1)
	info, err = parseRestrictedDocInfo([]byte(body), "local-id")
	if err != nil || info.EditorUserID != "older-id" {
		t.Fatal("older guest identity compatibility lost")
	}
	for _, body := range []string{
		strings.Replace(restrictedTestPage("true"), `"officeType":"only_office"`, `"officeType":"volga"`, 1),
		strings.Replace(restrictedTestPage("true"), `"office_online_editor_type":"only_office"`, `"office_online_editor_type":"unknown"`, 1),
	} {
		if _, err := parseRestrictedDocInfo([]byte(body), "local-id"); !errors.Is(err, ErrLegacyEditor) {
			t.Fatal("unsupported provider editor selected")
		}
	}
}

func TestRestrictedParserAcceptsOnlyTerminalDocumentKeyPadding(t *testing.T) {
	for _, tc := range []struct {
		key string
		ok  bool
	}{
		{"document-key", true},
		{"document-key=", true},
		{"document-key==", true},
		{strings.Repeat("a", 254) + "==", true},
		{strings.Repeat("a", 256), true},
		{"document=key", false},
		{"=document-key", false},
		{"document-key===", false},
		{strings.Repeat("a", 255) + "==", false},
		{strings.Repeat("a", 256) + "=", false},
		{"../document-key=", false},
	} {
		body := strings.Replace(restrictedTestPage("true"), `"document-key"`, `"`+tc.key+`"`, 1)
		info, err := parseRestrictedDocInfo([]byte(body), "guest")
		if tc.ok {
			if err != nil || info.DocID != tc.key {
				t.Fatalf("valid padded document key changed or rejected: %v", err)
			}
		} else if !errors.Is(err, ErrProviderResponse) {
			t.Fatal("invalid document key padding accepted")
		}
	}
}
