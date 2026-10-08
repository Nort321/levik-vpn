package yandex

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"sort"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/p1neappleXpress/OpenFlux/transport/egresspolicy"
	"golang.org/x/net/publicsuffix"
)

type probeRoundTripper struct {
	next http.RoundTripper
	t    *testing.T
}

// TestOperatorGuestProbe verifies provider application authorization separately
// from the encrypted peer handshake. No frame contents or credentials are logged.
func TestOperatorGuestProbe(t *testing.T) {
	path := os.Getenv("LEVIK_GUEST_PROBE_CONFIG")
	if path == "" {
		t.Skip("operator-only guest probe")
	}
	f, err := os.Open(path)
	if err != nil {
		t.Fatal("private_config_unavailable")
	}
	defer f.Close()
	var cfg struct {
		ProviderAuth struct {
			BalancerURL string `json:"balancerUrl"`
			Token       string `json:"token"`
			ValidUntil  int64  `json:"validUntil"`
		} `json:"providerAuth"`
	}
	if json.NewDecoder(io.LimitReader(f, 16<<10)).Decode(&cfg) != nil {
		t.Fatal("invalid_config")
	}
	jar, _ := cookiejar.New(&cookiejar.Options{PublicSuffixList: publicsuffix.List})
	guest := &guestCookieJar{jar: jar}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	info, err := fetchRestrictedGuestBootstrap(ctx, GuestBootstrap{BalancerURL: cfg.ProviderAuth.BalancerURL, Token: cfg.ProviderAuth.Token, ValidUntil: time.Unix(cfg.ProviderAuth.ValidUntil, 0)}, "pilot-probe", restrictedHTTPClient(guest), guest)
	if err != nil {
		code := "metadata_request_failed"
		switch err {
		case ErrGuestBootstrap:
			code = "guest_format_failed"
		case ErrGuestBootstrapExpired:
			code = "guest_expired"
		case ErrEditorBuild:
			code = "editor_build_failed"
		case egresspolicy.ErrProviderURL:
			code = "provider_origin_failed"
		}
		t.Fatal(code)
	}
	dialer := websocket.Dialer{HandshakeTimeout: 15 * time.Second, NetDialContext: egresspolicy.PublicDialContext, ReadBufferSize: 32 << 10, WriteBufferSize: 32 << 10}
	headers := http.Header{"Origin": []string{info.Origin}, "User-Agent": []string{"Mozilla/5.0"}}
	conn, resp, err := dialer.DialContext(ctx, info.WsURL, headers)
	if err != nil {
		status := 0
		if resp != nil {
			status = resp.StatusCode
		}
		t.Fatalf("websocket_failed status=%d", status)
	}
	defer conn.Close()
	conn.SetReadLimit(restrictedWSLimit)
	_ = conn.SetReadDeadline(time.Now().Add(20 * time.Second))
	_ = conn.SetWriteDeadline(time.Now().Add(20 * time.Second))
	token, _ := json.Marshal(map[string]string{"token": info.Token})
	auth := map[string]interface{}{"type": "auth", "docid": info.DocID, "token": "fghhfgsjdgfjs", "user": map[string]string{"id": "pilot-probe"}, "editorType": 0, "lastOtherSaveTime": -1, "permissions": info.Permissions, "openCmd": info.OpenCmd, "coEditingMode": "fast", "jwtOpen": info.Token}
	frame, _ := json.Marshal([]interface{}{"message", auth})
	socketSent, documentSent := false, false
	authorized := false
	for count := 0; count < 30; count++ {
		_, body, err := conn.ReadMessage()
		if err != nil {
			code := 0
			if closed, ok := err.(*websocket.CloseError); ok {
				code = closed.Code
			}
			t.Fatalf("provider_read_ended closeCode=%d socketAuthSent=%t documentAuthSent=%t", code, socketSent, documentSent)
		}
		if string(body) == "2" {
			_ = conn.WriteMessage(websocket.TextMessage, []byte("3"))
			continue
		}
		if !strings.HasPrefix(string(body), "42") {
			prefix := "other"
			if len(body) >= 2 && (string(body[:2]) == "40" || string(body[:2]) == "44") {
				prefix = string(body[:2])
			} else if len(body) > 0 && body[0] == '0' {
				prefix = "engine_open"
			}
			t.Logf("socketFrame=%s", prefix)
			if prefix == "engine_open" && !socketSent {
				if conn.WriteMessage(websocket.TextMessage, append([]byte("40"), token...)) != nil {
					t.Fatal("socket_auth_write_failed")
				}
				socketSent = true
			}
			if prefix == "40" && !documentSent {
				if conn.WriteMessage(websocket.TextMessage, append([]byte("42"), frame...)) != nil {
					t.Fatal("document_auth_write_failed")
				}
				documentSent = true
			}
			if prefix == "44" {
				t.Fatal("socket_auth_rejected")
			}
			continue
		}
		var event []json.RawMessage
		if json.Unmarshal(body[2:], &event) != nil || len(event) != 2 {
			continue
		}
		var message map[string]json.RawMessage
		if json.Unmarshal(event[1], &message) != nil {
			continue
		}
		var kind string
		_ = json.Unmarshal(message["type"], &kind)
		switch kind {
		case "auth", "error", "documentOpen", "waitAuth", "license", "connectState", "cursor", "saveChanges":
		default:
			kind = "other"
		}
		var result int
		_ = json.Unmarshal(message["result"], &result)
		t.Logf("applicationType=%s result=%d", kind, result)
		if kind == "auth" {
			if result != 1 {
				t.Fatal("provider_auth_rejected")
			}
			var index int
			_ = json.Unmarshal(message["indexUser"], &index)
			var view, closed bool
			_ = json.Unmarshal(message["view"], &view)
			_ = json.Unmarshal(message["isCloseCoAuthoring"], &closed)
			t.Logf("applicationAuth=true indexUser=%d view=%t closedCoauthoring=%t", index, view, closed)
			if os.Getenv("LEVIK_GUEST_PROBE_LISTEN") != "1" {
				return
			}
			authorized = true
		}
		if authorized && (kind == "cursor" || kind == "saveChanges" || kind == "documentOpen") {
			var data map[string]json.RawMessage
			_ = json.Unmarshal(message["data"], &data)
			var status string
			_ = json.Unmarshal(data["status"], &status)
			if status != "ok" && status != "err" {
				status = "other"
			}
			t.Logf("receivedCarrierEvent=%s payloadExtractable=%t documentStatus=%s", kind, ExtractBase64(string(body)) != "", status)
			if kind == "cursor" || kind == "saveChanges" {
				return
			}
		}
		if kind == "error" {
			t.Fatal("provider_application_error")
		}
	}
	t.Fatal("provider_auth_not_confirmed")
}

func probePath(u *url.URL) string {
	if strings.Contains(u.Path, "captcha") {
		switch u.Path {
		case "/showcaptchafast", "/showcaptcha", "/checkcaptchafast", "/checkcaptcha":
			return u.Path
		default:
			return "/captcha_other"
		}
	}
	return "/document"
}

func (p probeRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	resp, err := p.next.RoundTrip(req)
	if err != nil {
		p.t.Log("provider_request_failed")
		return resp, err
	}
	var sent, received []string
	for _, c := range req.Cookies() {
		sent = append(sent, c.Name)
	}
	for _, c := range resp.Cookies() {
		received = append(received, c.Name)
	}
	sort.Strings(sent)
	sort.Strings(received)
	location, _ := url.Parse(resp.Header.Get("Location"))
	p.t.Logf("method=%s host=%s pathClass=%s status=%d nextHost=%s nextPathClass=%s sentCookieNames=%v receivedCookieNames=%v", req.Method, req.URL.Hostname(), probePath(req.URL), resp.StatusCode, location.Hostname(), probePath(location), sent, received)
	return resp, nil
}

// This explicit operator probe is skipped by ordinary test runs. It prints
// only protocol shape and cookie names, never a document URL/body/token/value.
func TestOperatorProviderProbe(t *testing.T) {
	path := os.Getenv("LEVIK_PROBE_CONFIG")
	if path == "" {
		t.Skip("operator-only provider probe")
	}
	f, err := os.Open(path)
	if err != nil {
		t.Fatal("private_config_unavailable")
	}
	defer f.Close()
	var cfg struct {
		DocumentURL string `json:"documentUrl"`
	}
	if json.NewDecoder(io.LimitReader(f, 16<<10)).Decode(&cfg) != nil {
		t.Fatal("invalid_config")
	}
	jar, _ := cookiejar.New(&cookiejar.Options{PublicSuffixList: publicsuffix.List})
	var used atomic.Bool
	guest := &guestCookieJar{jar: jar, allowFastChallenge: true, fastChallengeUsed: &used}
	client := restrictedHTTPClient(guest)
	client.Transport = probeRoundTripper{client.Transport, t}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	body, _, err := fetchRestrictedPage(ctx, cfg.DocumentURL, client, guest)
	if err != nil {
		t.Fatalf("restricted_result=%v", err)
	}
	m := clientConfigRe.FindSubmatch(body)
	if len(m) != 2 {
		t.Fatal("no_client_config")
	}
	var page map[string]interface{}
	if json.Unmarshal(m[1], &page) != nil {
		t.Fatal("invalid_client_config")
	}
	office, _ := page["officeActionData"].(map[string]interface{})
	editor, _ := office["editor_config"].(map[string]interface{})
	doc, _ := editor["document"].(map[string]interface{})
	permissions, _ := doc["permissions"].(map[string]interface{})
	user, _ := editor["user"].(map[string]interface{})
	balancer, _ := url.Parse(getStr(office, "balancer_url"))
	t.Logf("clientConfig=true officeKeys=%v balancerHost=%s fileType=%s edit=%t userIdType=%s", mapKeys(office), balancer.Hostname(), getStr(doc, "fileType"), permissions["edit"] == true, fmt.Sprintf("%T", user["id"]))
	if _, err := parseRestrictedDocInfo(body, "pilot-guest"); err != nil {
		t.Fatalf("restricted_parse=%v", err)
	}
}
