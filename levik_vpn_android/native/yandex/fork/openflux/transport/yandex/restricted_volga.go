package yandex

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/egresspolicy"
	"golang.org/x/net/publicsuffix"
)

var restrictedRequestPath = regexp.MustCompile(`^[A-Za-z0-9_-]{1,512}$`)

// NewRestrictedYandexVolgaTransport uses the current Volga client-config
// contract with anonymous guest credentials and a bounded pilot profile.
// Unlike the upstream constructors it never imports account cookies or uses
// the upstream CAPTCHA solver. Fast proof requires separate explicit opt-in.
// The share link grants access, not proof of ownership.
func NewRestrictedYandexVolgaTransport(rawURL string, cfg transport.TransportConfig) (*YandexVolgaTransport, error) {
	u, err := egresspolicy.ValidateProviderURL(rawURL, false)
	if err != nil || u.Host != "disk.yandex.ru" || !initialDocumentPath.MatchString(u.Path) || u.RawPath != "" || u.RawQuery != "" || u.ForceQuery {
		return nil, ErrRestrictedDocument
	}
	profile := SlimVolgaConfig()
	profile.WorkerCount = 4
	profile.QueueSize = 1024
	profile.BatchMaxBytes = 64 << 10
	profile.MaxPayloadBytes = 64 << 10
	profile.WSReadBufferSize = 32 << 10
	profile.WSWriteBufferSize = 32 << 10
	profile.ReconnectMinDelay = 2 * time.Second
	jar, err := cookiejar.New(&cookiejar.Options{PublicSuffixList: publicsuffix.List})
	if err != nil {
		return nil, ErrProviderResponse
	}
	t := NewYandexVolgaTransportWithConfig(rawURL, cfg, profile)
	t.cookieJar = jar
	t.restricted = true
	return t, nil
}

func (t *YandexVolgaTransport) authorize() (*volgaAuth, error) {
	if !t.restricted {
		return authorizeWithJar(t.docURL, t.jar())
	}
	ctx, cancel := context.WithTimeout(context.Background(), restrictedAttemptTimeout)
	defer cancel()
	go func() {
		select {
		case <-t.Done():
			cancel()
		case <-ctx.Done():
		}
	}()
	guest := &guestCookieJar{jar: t.jar(), allowFastChallenge: t.fastChallengeAllowed.Load(), fastChallengeUsed: &t.fastChallengeUsed}
	return authorizeRestrictedVolga(ctx, t.docURL, restrictedHTTPClient(guest), guest)
}

func authorizeRestrictedVolga(ctx context.Context, rawURL string, client *http.Client, guest *guestCookieJar) (*volgaAuth, error) {
	ctx, cancel := context.WithTimeout(ctx, restrictedAttemptTimeout)
	defer cancel()
	body, finalURL, err := fetchRestrictedPage(ctx, rawURL, client, guest)
	if err != nil {
		return nil, err
	}
	m := clientConfigRe.FindSubmatch(body)
	if len(m) != 2 {
		return nil, ErrLegacyEditor
	}
	var page map[string]interface{}
	decoder := json.NewDecoder(bytes.NewReader(m[1]))
	decoder.UseNumber()
	if decoder.Decode(&page) != nil {
		return nil, ErrProviderResponse
	}
	office, _ := page["officeActionData"].(map[string]interface{})
	editor, _ := page["editorParams"].(map[string]interface{})
	if office == nil || editor == nil || (getStr(office, "officeType") != "" && getStr(office, "officeType") != "volga") {
		return nil, ErrLegacyEditor
	}
	if getStr(editor, "action") != "edit" {
		return nil, ErrEditPermission
	}
	actionURL, err := egresspolicy.ValidateProviderURL(getStr(office, "action_url"), false)
	if err != nil || actionURL.Host != "volga.yandex.ru" || (actionURL.Path != "/auth/initial" && actionURL.Path != "/auth/initial/") || actionURL.RawPath != "" || actionURL.RawQuery != "" || actionURL.ForceQuery {
		return nil, egresspolicy.ErrProviderURL
	}
	accessToken := getStr(office, "access_token")
	ttl := formatTTL(office["access_token_ttl"])
	ttlNumber, ttlErr := strconv.ParseUint(ttl, 10, 64)
	if !boundedCredential(accessToken, 32<<10) || ttlErr != nil || ttlNumber == 0 {
		return nil, ErrProviderResponse
	}
	docID := getStr(editor, "idDoc")
	if !legacyDocumentKey.MatchString(docID) || docID == "." || docID == ".." {
		return nil, ErrProviderResponse
	}
	form := url.Values{"access_token": {accessToken}, "access_token_ttl": {ttl}}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, actionURL.String(), strings.NewReader(form.Encode()))
	if err != nil {
		return nil, ErrProviderResponse
	}
	req.Header.Set("User-Agent", volgaUserAgent)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Origin", "https://disk.yandex.ru")
	req.Header.Set("Referer", finalURL.String())
	req.Header.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
	req.Header.Set("Accept-Language", "ru-RU,ru;q=0.9")
	req.Header.Set("Sec-Fetch-Dest", "iframe")
	req.Header.Set("Sec-Fetch-Mode", "navigate")
	req.Header.Set("Sec-Fetch-Site", "cross-site")
	resp, err := client.Do(req)
	if err != nil {
		return nil, ErrProviderResponse
	}
	_, readErr := readRestrictedResponse(resp)
	if guest.rejected.Load() {
		return nil, ErrAccountCookies
	}
	if readErr != nil {
		return nil, readErr
	}
	if resp.StatusCode != http.StatusFound {
		return nil, ErrProviderResponse
	}
	ref, err := url.Parse(resp.Header.Get("Location"))
	if err != nil || resp.Header.Get("Location") == "" {
		return nil, ErrProviderResponse
	}
	resolved := actionURL.ResolveReference(ref)
	if err := restrictedCheckURL(resolved); err != nil {
		return nil, err
	}
	location, err := egresspolicy.ValidateProviderURL(resolved.String(), false)
	if err != nil || location.Host != "volga.yandex.ru" || !strings.HasPrefix(location.Path, "/document/") || strings.HasPrefix(location.Path, "/document/error/") || location.RawPath != "" {
		return nil, egresspolicy.ErrProviderURL
	}
	query, err := url.ParseQuery(location.RawQuery)
	if err != nil {
		return nil, ErrProviderResponse
	}
	for _, key := range []string{"token", "request-path", "json"} {
		if len(query[key]) != 1 {
			return nil, ErrProviderResponse
		}
	}
	auth := &volgaAuth{
		Session: client, AccessToken: accessToken, Token: query.Get("token"),
		RequestPath: query.Get("request-path"), DocID: docID, Action: "edit",
		ResourceURL: getStr(office, "resource_url"),
	}
	if !restrictedRequestPath.MatchString(auth.RequestPath) || !boundedCredential(auth.Token, 32<<10) {
		return nil, ErrProviderResponse
	}
	var sessionData map[string]interface{}
	decoder = json.NewDecoder(strings.NewReader(query.Get("json")))
	decoder.UseNumber()
	if decoder.Decode(&sessionData) != nil {
		return nil, ErrProviderResponse
	}
	auth.SessionID = getStr(sessionData, "sessionId")
	userID, err := strconv.ParseInt(getStr(sessionData, "userId"), 10, 64)
	if err != nil || userID == 0 || int64(int(userID)) != userID {
		return nil, ErrProviderResponse
	}
	auth.UserID = int(userID)
	xiva, _ := sessionData["xiva"].(map[string]interface{})
	auth.Sign, auth.TS, auth.UserIDStr = getStr(xiva, "sign"), getStr(xiva, "ts"), getStr(xiva, "user")
	if !boundedCredential(auth.Sign, 4096) || !boundedCredential(auth.TS, 64) || !boundedCredential(auth.UserIDStr, 512) || !boundedCredential(auth.SessionID, 512) {
		return nil, ErrProviderResponse
	}
	if _, _, err := fetchRestrictedPage(ctx, location.String(), client, guest); err != nil {
		return nil, err
	}
	// Cookies remain in Session.Jar; each HTTP/WS endpoint gets only the
	// cookies selected for its own origin, never an unscoped copied slice.
	return auth, nil
}

func boundedCredential(value string, limit int) bool {
	if value == "" || len(value) > limit {
		return false
	}
	for _, c := range value {
		if c < 0x21 || c > 0x7e {
			return false
		}
	}
	return true
}

func readRestrictedResponse(resp *http.Response) ([]byte, error) {
	defer resp.Body.Close()
	if resp.ContentLength > restrictedHTMLLimit {
		return nil, ErrProviderBodyLimit
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, restrictedHTMLLimit+1))
	if len(body) > restrictedHTMLLimit {
		return nil, ErrProviderBodyLimit
	}
	if err != nil {
		return nil, ErrProviderResponse
	}
	return body, nil
}

func restrictRelayClient(r *relayClient) {
	r.restricted = true
	r.httpClient = restrictedHTTPClient(nil)
	tr := r.httpClient.Transport.(*http.Transport)
	tr.DisableKeepAlives = false
	tr.MaxIdleConns, tr.MaxIdleConnsPerHost, tr.MaxConnsPerHost = 16, 8, 8
	tr.IdleConnTimeout = 30 * time.Second
}

func restrictedVolgaCookies(auth *volgaAuth, rawURL string) (string, error) {
	if auth.Session == nil || auth.Session.Jar == nil {
		return "", ErrProviderResponse
	}
	u, err := egresspolicy.ValidateProviderURL(rawURL, true)
	if err != nil {
		return "", err
	}
	if u.Scheme == "wss" {
		u.Scheme = "https"
	}
	var parts []string
	for _, cookie := range auth.Session.Jar.Cookies(u) {
		if accountCookie(cookie.Name) {
			return "", ErrAccountCookies
		}
		if guestCookie(cookie.Name) {
			parts = append(parts, cookie.String())
		}
	}
	if jar, ok := auth.Session.Jar.(*guestCookieJar); !ok || jar.rejected.Load() {
		return "", ErrAccountCookies
	}
	return strings.Join(parts, "; "), nil
}

func (t *YandexVolgaTransport) restrictedFailure(err error) {
	t.SetConnected(false)
	if t.errNotifier != nil {
		t.errNotifier(err, "vyandex", "", "", "restricted_document")
	}
}
