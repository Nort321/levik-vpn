package yandex

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/egresspolicy"
)

const restrictedGuestMaxLifetime = 15 * time.Minute

var (
	ErrGuestBootstrap        = errors.New("yandex: invalid guest bootstrap")
	ErrGuestBootstrapExpired = errors.New("yandex: guest bootstrap has expired")
)

// GuestBootstrap contains a short-lived anonymous editor credential captured by
// an authorized client. The fields and decoded JWT claims remain untrusted.
// Only the provider can authenticate the JWT signature during WS authorization.
// No cookies or arbitrary request headers are accepted.
type GuestBootstrap struct {
	BalancerURL string
	Token       string
	ValidUntil  time.Time
}

// NewRestrictedYandexDocsTransportWithGuestBootstrap avoids the frontend on
// every connection and reconnect. Expired credentials require replacement by
// the caller; there is deliberately no frontend or account-cookie fallback.
func NewRestrictedYandexDocsTransportWithGuestBootstrap(rawURL string, bootstrap GuestBootstrap, config transport.TransportConfig) (*YandexDocsTransport, error) {
	t, err := NewRestrictedYandexDocsTransport(rawURL, config)
	if err != nil {
		return nil, err
	}
	if _, err := parseRestrictedGuestBootstrap(bootstrap, "guest"); err != nil {
		return nil, err
	}
	t.guestBootstrap = &bootstrap
	return t, nil
}

func fetchRestrictedGuestBootstrap(ctx context.Context, bootstrap GuestBootstrap, userID string, client *http.Client, guest *guestCookieJar) (YandexDocsInfo, error) {
	ctx, cancel := context.WithTimeout(ctx, restrictedAttemptTimeout)
	defer cancel()
	ctx, expire := context.WithDeadline(ctx, bootstrap.ValidUntil)
	defer expire()
	info, err := parseRestrictedGuestBootstrap(bootstrap, userID)
	if err != nil {
		return YandexDocsInfo{}, err
	}
	build, err := fetchRestrictedEditorBuild(ctx, info.Host, client, guest)
	if err != nil {
		return YandexDocsInfo{}, err
	}
	if _, err := parseRestrictedGuestBootstrap(bootstrap, userID); err != nil {
		return YandexDocsInfo{}, err
	}
	wsURL := &url.URL{Scheme: "wss", Host: info.Host, Path: "/" + build + "/doc/" + info.DocID + "/c/", RawQuery: "EIO=4&transport=websocket"}
	if _, err := egresspolicy.ValidateProviderURL(wsURL.String(), true); err != nil {
		return YandexDocsInfo{}, egresspolicy.ErrProviderURL
	}
	info.WsURL = wsURL.String()
	// Joining from a supplied guest JWT never promotes cookies obtained while
	// fetching public build metadata into the WebSocket credential.
	info.CookieStr = ""
	return info, nil
}

func restrictedGuestDeadline(deadline time.Time) error {
	now := time.Now()
	if deadline.IsZero() {
		return ErrGuestBootstrap
	}
	if !deadline.After(now) {
		return ErrGuestBootstrapExpired
	}
	if deadline.After(now.Add(restrictedGuestMaxLifetime)) {
		return ErrGuestBootstrap
	}
	return nil
}

func parseRestrictedGuestBootstrap(bootstrap GuestBootstrap, userID string) (YandexDocsInfo, error) {
	if err := restrictedGuestDeadline(bootstrap.ValidUntil); err != nil {
		return YandexDocsInfo{}, err
	}
	balancer, err := egresspolicy.ValidateProviderURL(bootstrap.BalancerURL, false)
	const root = "onlyoffice.disk.yandex.net"
	if err != nil || (balancer.Path != "" && balancer.Path != "/") || balancer.RawQuery != "" || balancer.ForceQuery ||
		(balancer.Host != root && (!strings.HasSuffix(balancer.Host, "."+root) || strings.Contains(strings.TrimSuffix(balancer.Host, "."+root), "."))) {
		return YandexDocsInfo{}, ErrGuestBootstrap
	}
	if len(bootstrap.Token) == 0 || len(bootstrap.Token) > 32<<10 || strings.TrimSpace(bootstrap.Token) != bootstrap.Token {
		return YandexDocsInfo{}, ErrGuestBootstrap
	}
	parts := strings.Split(bootstrap.Token, ".")
	if len(parts) != 3 || len(parts[0]) > 1366 {
		return YandexDocsInfo{}, ErrGuestBootstrap
	}
	for _, part := range parts {
		for _, c := range part {
			if (c < 'A' || c > 'Z') && (c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '-' && c != '_' {
				return YandexDocsInfo{}, ErrGuestBootstrap
			}
		}
	}
	decode := base64.RawURLEncoding.Strict().DecodeString
	header, err := decode(parts[0])
	if err != nil || len(header) > 1024 || !restrictedUniqueJSON(header) {
		return YandexDocsInfo{}, ErrGuestBootstrap
	}
	var headerKeys map[string]json.RawMessage
	if json.Unmarshal(header, &headerKeys) != nil {
		return YandexDocsInfo{}, ErrGuestBootstrap
	}
	for key := range headerKeys {
		if key != "alg" && key != "typ" {
			return YandexDocsInfo{}, ErrGuestBootstrap
		}
	}
	var h struct {
		Algorithm string `json:"alg"`
		Type      string `json:"typ"`
	}
	hd := json.NewDecoder(bytes.NewReader(header))
	hd.DisallowUnknownFields()
	if hd.Decode(&h) != nil || h.Algorithm != "HS256" || (h.Type != "" && h.Type != "JWT") {
		return YandexDocsInfo{}, ErrGuestBootstrap
	}
	signature, err := decode(parts[2])
	if err != nil || len(signature) != 32 {
		return YandexDocsInfo{}, ErrGuestBootstrap
	}
	payload, err := decode(parts[1])
	if err != nil || len(payload) > 24<<10 || !restrictedUniqueJSON(payload) {
		return YandexDocsInfo{}, ErrGuestBootstrap
	}
	var claims map[string]json.RawMessage
	if json.Unmarshal(payload, &claims) != nil || claims == nil {
		return YandexDocsInfo{}, ErrGuestBootstrap
	}
	for key := range claims {
		switch key {
		case "document", "documentType", "editorConfig", "type", "exp", "iat", "nbf":
		default:
			return YandexDocsInfo{}, ErrGuestBootstrap
		}
	}
	now := time.Now().Unix()
	for _, key := range []string{"exp", "nbf", "iat"} {
		if raw, ok := claims[key]; ok {
			value, err := strconv.ParseInt(string(raw), 10, 64)
			if err != nil || value <= 0 {
				return YandexDocsInfo{}, ErrGuestBootstrap
			}
			if key == "exp" && value <= now {
				return YandexDocsInfo{}, ErrGuestBootstrapExpired
			}
			if key == "nbf" && value > now {
				return YandexDocsInfo{}, ErrGuestBootstrap
			}
		}
	}
	var editor struct {
		Mode string `json:"mode"`
		User struct {
			ID string `json:"id"`
		} `json:"user"`
	}
	editorObject, ok := restrictedExactObject(claims["editorConfig"], "mode", "user")
	if !ok {
		return YandexDocsInfo{}, ErrGuestBootstrap
	}
	if _, ok := restrictedExactObject(editorObject["user"], "id"); !ok {
		return YandexDocsInfo{}, ErrGuestBootstrap
	}
	documentObject, ok := restrictedExactObject(claims["document"], "key", "fileType", "url", "title", "permissions")
	if !ok {
		return YandexDocsInfo{}, ErrGuestBootstrap
	}
	if _, ok := restrictedExactObject(documentObject["permissions"], "edit"); !ok {
		return YandexDocsInfo{}, ErrGuestBootstrap
	}
	if json.Unmarshal(claims["editorConfig"], &editor) != nil || editor.User.ID == "" || len(editor.User.ID) > 256 ||
		strings.ContainsAny(editor.User.ID, "\x00\r\n") || (editor.Mode != "" && editor.Mode != "edit") {
		return YandexDocsInfo{}, ErrGuestBootstrap
	}
	claims["token"], _ = json.Marshal(bootstrap.Token)
	page := map[string]interface{}{
		"officeType": "only_office",
		"officeActionData": map[string]interface{}{
			"office_online_editor_type": "only_office", "balancer_url": bootstrap.BalancerURL, "editor_config": claims,
		},
	}
	pageJSON, err := json.Marshal(page)
	if err != nil {
		return YandexDocsInfo{}, ErrGuestBootstrap
	}
	// The established parser derives a fixed open command from the signed
	// document-shaped data; caller-supplied open commands are never accepted.
	body := append([]byte(`<script id="client-config" type="application/json">`), pageJSON...)
	body = append(body, []byte(`</script>`)...)
	info, err := parseRestrictedDocInfo(body, userID)
	if err != nil {
		return YandexDocsInfo{}, ErrGuestBootstrap
	}
	documentURL, _ := info.OpenCmd["url"].(string)
	u, err := url.Parse(documentURL)
	title, _ := info.OpenCmd["title"].(string)
	// The observed signed ONLYOFFICE config uses its provider-internal source
	// at localhost:12701. This value is copied unchanged into the provider's
	// open command, never fetched or dialed by this client. Provider JWT
	// verification must authorize it; it is not an exception to egress policy.
	if err != nil || u.Host == "" || u.User != nil || u.Fragment != "" ||
		(u.Scheme != "https" && !(u.Scheme == "http" && u.Host == "localhost:12701")) ||
		len(documentURL) > 8192 || len(title) > 4096 {
		return YandexDocsInfo{}, ErrGuestBootstrap
	}
	return info, nil
}

// encoding/json matches struct fields without regard to case, while provider
// JavaScript consumes exact property names. Reject aliases for fields used to
// build our auth command so those interpretations cannot diverge.
func restrictedExactObject(raw json.RawMessage, fields ...string) (map[string]json.RawMessage, bool) {
	var object map[string]json.RawMessage
	if json.Unmarshal(raw, &object) != nil || object == nil {
		return nil, false
	}
	for key := range object {
		for _, field := range fields {
			if key != field && strings.EqualFold(key, field) {
				return nil, false
			}
		}
	}
	return object, true
}

// Reject duplicate JSON keys and excessive nesting before decoding claims.
// This checks syntax only, never the provider signature or claim authenticity.
func restrictedUniqueJSON(body []byte) bool {
	if !utf8.Valid(body) {
		return false
	}
	d := json.NewDecoder(bytes.NewReader(body))
	d.UseNumber()
	items := 0
	var visit func(int) bool
	visit = func(depth int) bool {
		items++
		if depth > 16 || items > 4096 {
			return false
		}
		token, err := d.Token()
		if err != nil {
			return false
		}
		delim, isDelim := token.(json.Delim)
		if !isDelim {
			return true
		}
		switch delim {
		case '{':
			seen := make(map[string]bool)
			for d.More() {
				key, err := d.Token()
				name, ok := key.(string)
				if err != nil || !ok || seen[name] || !visit(depth+1) {
					return false
				}
				seen[name] = true
			}
			end, err := d.Token()
			return err == nil && end == json.Delim('}')
		case '[':
			for d.More() {
				if !visit(depth + 1) {
					return false
				}
			}
			end, err := d.Token()
			return err == nil && end == json.Delim(']')
		default:
			return false
		}
	}
	if !visit(0) {
		return false
	}
	_, err := d.Token()
	return err == io.EOF
}
