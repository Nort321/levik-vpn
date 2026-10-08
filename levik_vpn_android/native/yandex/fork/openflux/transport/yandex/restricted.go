package yandex

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"regexp"
	"strings"
	"sync/atomic"
	"time"

	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/egresspolicy"
	"golang.org/x/net/publicsuffix"
)

const (
	restrictedHTMLLimit      = 2 << 20
	restrictedWSLimit        = 2 << 20
	restrictedAttemptTimeout = 30 * time.Second
)

var (
	ErrRestrictedDocument = errors.New("yandex: a public disk.yandex.ru document link is required")
	ErrLegacyEditor       = errors.New("yandex: a compatible legacy editor is required")
	ErrEditPermission     = errors.New("yandex: public editing permission is required")
	ErrAccountCookies     = errors.New("yandex: account cookies are not accepted")
	ErrProviderResponse   = errors.New("yandex: invalid provider response")
	ErrProviderBodyLimit  = errors.New("yandex: provider response exceeds the size limit")
	ErrEditorBuild        = errors.New("yandex: a compatible editor build could not be determined")
	initialDocumentPath   = regexp.MustCompile(`^/i/[A-Za-z0-9_-]{1,256}$`)
	legacyDocumentKey     = regexp.MustCompile(`^[A-Za-z0-9_.-]{1,256}={0,2}$`)
)

// NewRestrictedYandexDocsTransport is the opt-in dedicated relay carrier.
// It accepts only public share links, never imported account/captcha cookies,
// and fails closed on login/captcha/unsupported editors. The general-purpose
// NewYandexDocsTransport constructor deliberately retains upstream behavior.
func NewRestrictedYandexDocsTransport(rawURL string, config transport.TransportConfig) (*YandexDocsTransport, error) {
	u, err := egresspolicy.ValidateProviderURL(rawURL, false)
	if err != nil || u.Host != "disk.yandex.ru" || !initialDocumentPath.MatchString(u.Path) || u.RawPath != "" || u.RawQuery != "" || u.ForceQuery {
		return nil, ErrRestrictedDocument
	}
	t := NewYandexDocsTransport(rawURL, config)
	jar, err := cookiejar.New(&cookiejar.Options{PublicSuffixList: publicsuffix.List})
	if err != nil {
		return nil, ErrProviderResponse
	}
	t.cookieJar = jar
	t.restricted = true
	return t, nil
}

// guestCookieJar rejects account credentials even if a provider response tries
// to set them. Guest cookies remain origin/domain/path scoped by cookiejar;
// no raw Set-Cookie values are copied into a different host's WS request.
type guestCookieJar struct {
	jar                http.CookieJar
	rejected           atomic.Bool
	allowFastChallenge bool
	fastChallengeUsed  *atomic.Bool
}

func accountCookie(name string) bool {
	switch strings.ToLower(name) {
	case "session_id", "sessionid", "sessionid2", "sessguard", "yandex_login", "oauth_token", "access_token", "refresh_token", "l":
		return true
	}
	return false
}

func guestCookie(name string) bool {
	// Unknown cookies are not promoted into credentials for the next origin.
	// Extend only when a documented guest flow actually needs a new cookie.
	switch strings.ToLower(name) {
	case "yandexuid", "yuidss", "ymex", "yp", "ys", "spravka", "gdpr", "gdpr_popup", "is_gdpr", "is_gdpr_b", "_ym_uid", "_ym_d", "_yasc", "i", "yashr":
		return true
	}
	return false
}

func (j *guestCookieJar) Cookies(u *url.URL) []*http.Cookie {
	var cookies []*http.Cookie
	for _, c := range j.jar.Cookies(u) {
		if accountCookie(c.Name) {
			j.rejected.Store(true)
			continue
		}
		if guestCookie(c.Name) {
			cookies = append(cookies, c)
		}
	}
	return cookies
}

func (j *guestCookieJar) SetCookies(u *url.URL, cookies []*http.Cookie) {
	var guest []*http.Cookie
	for _, c := range cookies {
		if accountCookie(c.Name) {
			j.rejected.Store(true)
			continue
		}
		if guestCookie(c.Name) {
			guest = append(guest, c)
		}
	}
	j.jar.SetCookies(u, guest)
}

func restrictedHTTPClient(jar http.CookieJar) *http.Client {
	return &http.Client{
		Jar: jar,
		Transport: &http.Transport{
			Proxy:                  nil,
			DialContext:            egresspolicy.PublicDialContext,
			TLSHandshakeTimeout:    10 * time.Second,
			ResponseHeaderTimeout:  10 * time.Second,
			MaxResponseHeaderBytes: 64 << 10,
			DisableKeepAlives:      true,
		},
		Timeout:       restrictedAttemptTimeout,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
}

// SetRestrictedNetworkHooks is configured before Start by the standalone
// Android helper. The default server path continues to use PublicDialContext.
func (t *YandexDocsTransport) SetRestrictedNetworkHooks(hooks egresspolicy.NetworkHooks) error {
	dial, err := egresspolicy.NewPublicDialContext(hooks)
	if err != nil {
		return err
	}
	t.Mu.Lock()
	defer t.Mu.Unlock()
	if !t.restricted || t.IsRunning() {
		return ErrProviderResponse
	}
	t.restrictedDial = dial
	return nil
}

func (t *YandexDocsTransport) restrictedPublicDialContext() func(context.Context, string, string) (net.Conn, error) {
	t.Mu.RLock()
	dial := t.restrictedDial
	t.Mu.RUnlock()
	if dial == nil {
		return egresspolicy.PublicDialContext
	}
	return dial
}

func (t *YandexDocsTransport) fetchRestrictedDocInfo(ctx context.Context, rawURL, userID string) (YandexDocsInfo, error) {
	t.jarMu.RLock()
	jar := t.cookieJar
	t.jarMu.RUnlock()
	guest := &guestCookieJar{jar: jar, allowFastChallenge: t.fastChallengeAllowed.Load(), fastChallengeUsed: &t.fastChallengeUsed}
	client := restrictedHTTPClient(guest)
	client.Transport.(*http.Transport).DialContext = t.restrictedPublicDialContext()
	t.Mu.RLock()
	bootstrap := t.guestBootstrap
	t.Mu.RUnlock()
	if bootstrap != nil {
		info, err := fetchRestrictedGuestBootstrap(ctx, *bootstrap, userID, client, guest)
		info.guestCredential = bootstrap
		return info, err
	}
	return fetchRestrictedDocInfo(ctx, rawURL, userID, client, guest)
}

// The client parameter keeps policy tests deterministic without opening real
// provider documents. Production always uses restrictedHTTPClient above.
func fetchRestrictedDocInfo(ctx context.Context, rawURL, userID string, client *http.Client, guest *guestCookieJar) (YandexDocsInfo, error) {
	ctx, cancel := context.WithTimeout(ctx, restrictedAttemptTimeout)
	defer cancel()
	body, _, err := fetchRestrictedPage(ctx, rawURL, client, guest)
	if err != nil {
		return YandexDocsInfo{}, err
	}
	info, err := parseRestrictedDocInfo(body, userID)
	if err != nil {
		return YandexDocsInfo{}, err
	}
	build, err := fetchRestrictedEditorBuild(ctx, info.Host, client, guest)
	if err != nil {
		return YandexDocsInfo{}, err
	}
	wsURL := &url.URL{Scheme: "wss", Host: info.Host, Path: "/" + build + "/doc/" + info.DocID + "/c/", RawQuery: "EIO=4&transport=websocket"}
	if _, err := egresspolicy.ValidateProviderURL(wsURL.String(), true); err != nil {
		return YandexDocsInfo{}, egresspolicy.ErrProviderURL
	}
	info.WsURL = wsURL.String()
	ws, _ := url.Parse(info.WsURL) // produced and validated above
	ws.Scheme = "https"            // cookiejar accepts HTTP(S), not WSS
	var cookieParts []string
	for _, c := range guest.Cookies(ws) {
		cookieParts = append(cookieParts, c.String())
	}
	if guest.rejected.Load() {
		return YandexDocsInfo{}, ErrAccountCookies
	}
	info.CookieStr = strings.Join(cookieParts, "; ")
	return info, nil
}

func fetchRestrictedPage(ctx context.Context, rawURL string, client *http.Client, guest *guestCookieJar) ([]byte, *url.URL, error) {
	ctx, cancel := context.WithTimeout(ctx, restrictedAttemptTimeout)
	defer cancel()
	current, err := egresspolicy.ValidateProviderURL(rawURL, false)
	if err != nil {
		return nil, nil, egresspolicy.ErrProviderURL
	}
	for hop := 0; hop < 10; hop++ {
		if err := restrictedCheckURL(current); err != nil {
			if err == ErrCaptchaRequired && guest.allowFastChallenge && restrictedFastChallengeURL(current) {
				current, err = solveRestrictedFastChallenge(ctx, current, client, guest)
				if err != nil {
					return nil, nil, err
				}
				continue
			}
			return nil, nil, err
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, current.String(), nil)
		if err != nil {
			return nil, nil, ErrProviderResponse
		}
		req.Header.Set("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:153.0) Gecko/20100101 Firefox/153.0")
		resp, err := client.Do(req)
		if err != nil {
			return nil, nil, ErrProviderResponse
		}
		if guest.rejected.Load() {
			resp.Body.Close()
			return nil, nil, ErrAccountCookies
		}
		if resp.StatusCode >= 300 && resp.StatusCode < 400 {
			location := resp.Header.Get("Location")
			resp.Body.Close()
			ref, err := url.Parse(location)
			if err != nil || location == "" {
				return nil, nil, ErrProviderResponse
			}
			resolved := current.ResolveReference(ref)
			if err := restrictedCheckURL(resolved); err != nil && !(err == ErrCaptchaRequired && guest.allowFastChallenge && restrictedFastChallengeURL(resolved)) {
				return nil, nil, err
			}
			current, err = egresspolicy.ValidateProviderURL(resolved.String(), false)
			if err != nil {
				return nil, nil, egresspolicy.ErrProviderURL
			}
			continue
		}
		if resp.StatusCode != http.StatusOK {
			resp.Body.Close()
			return nil, nil, ErrProviderResponse
		}
		if resp.ContentLength > restrictedHTMLLimit {
			resp.Body.Close()
			return nil, nil, ErrProviderBodyLimit
		}
		body, readErr := io.ReadAll(io.LimitReader(resp.Body, restrictedHTMLLimit+1))
		resp.Body.Close()
		if len(body) > restrictedHTMLLimit {
			return nil, nil, ErrProviderBodyLimit
		}
		if readErr != nil {
			return nil, nil, ErrProviderResponse
		}
		if !clientConfigRe.Match(body) && (bytes.Contains(body, []byte(`id="tmgrdfrend-form"`)) || bytes.Contains(body, []byte("showcaptchafast"))) {
			return nil, nil, ErrCaptchaRequired
		}
		return body, current, nil
	}
	return nil, nil, ErrProviderResponse
}

func restrictedCheckURL(u *url.URL) error {
	host := u.Hostname()
	if host == "passport.yandex.ru" || strings.HasPrefix(host, "passport.") || host == "id.yandex.ru" {
		return ErrLoginRequired
	}
	path := strings.ToLower(u.Path)
	if strings.Contains(path, "captcha") {
		return ErrCaptchaRequired
	}
	return nil
}

func restrictedTerminalError(err error) bool {
	// Admission timeouts close that attempt, but bounded reconnect may retry
	// while the original credential remains valid. Explicit denial is terminal.
	return errors.Is(err, ErrCaptchaRequired) || errors.Is(err, ErrLoginRequired) ||
		errors.Is(err, ErrAccountCookies) || errors.Is(err, ErrEditPermission) ||
		errors.Is(err, ErrLegacyEditor) || errors.Is(err, ErrProviderBodyLimit) ||
		errors.Is(err, ErrEditorBuild) ||
		errors.Is(err, ErrGuestBootstrap) || errors.Is(err, ErrGuestBootstrapExpired) ||
		errors.Is(err, ErrProviderAuthDenied) ||
		errors.Is(err, egresspolicy.ErrProviderURL)
}

func parseRestrictedDocInfo(body []byte, userID string) (YandexDocsInfo, error) {
	if len(body) > restrictedHTMLLimit {
		return YandexDocsInfo{}, ErrProviderBodyLimit
	}
	matches := clientConfigRe.FindSubmatch(body)
	if len(matches) != 2 {
		if bytes.Contains(body, []byte(`id="tmgrdfrend-form"`)) || bytes.Contains(body, []byte("showcaptchafast")) {
			return YandexDocsInfo{}, ErrCaptchaRequired
		}
		return YandexDocsInfo{}, ErrLegacyEditor
	}
	var page struct {
		Type   string `json:"officeType"`
		Office struct {
			Type       string `json:"officeType"`
			OnlineType string `json:"office_online_editor_type"`
			Balancer   string `json:"balancer_url"`
			Editor     struct {
				Token string `json:"token"`
				User  struct {
					ID string `json:"id"`
				} `json:"user"`
				Config struct {
					User struct {
						ID string `json:"id"`
					} `json:"user"`
				} `json:"editorConfig"`
				Document struct {
					Key         string                 `json:"key"`
					FileType    string                 `json:"fileType"`
					URL         string                 `json:"url"`
					Title       string                 `json:"title"`
					Permissions map[string]interface{} `json:"permissions"`
				} `json:"document"`
			} `json:"editor_config"`
		} `json:"officeActionData"`
	}
	if err := json.Unmarshal(matches[1], &page); err != nil {
		return YandexDocsInfo{}, ErrProviderResponse
	}
	office := page.Office
	if office.Type == "volga" || office.Balancer == "" ||
		(page.Type != "" && page.Type != "only_office") ||
		(office.OnlineType != "" && office.OnlineType != "only_office") {
		return YandexDocsInfo{}, ErrLegacyEditor
	}
	balancer, err := egresspolicy.ValidateProviderURL(office.Balancer, false)
	if err != nil || (balancer.Path != "" && balancer.Path != "/") || balancer.RawQuery != "" || balancer.ForceQuery {
		return YandexDocsInfo{}, egresspolicy.ErrProviderURL
	}
	editor := office.Editor
	doc := editor.Document
	if len(doc.Key) > 256 || !legacyDocumentKey.MatchString(doc.Key) || doc.Key == "." || doc.Key == ".." || editor.Token == "" || len(editor.Token) > 32<<10 {
		return YandexDocsInfo{}, ErrProviderResponse
	}
	if edit, ok := doc.Permissions["edit"].(bool); !ok || !edit {
		return YandexDocsInfo{}, ErrEditPermission
	}
	switch strings.ToLower(doc.FileType) {
	case "docx", "doc", "odt", "rtf", "txt", "xlsx", "xls", "xlsm", "ods", "csv", "pptx", "ppt", "odp":
	default:
		return YandexDocsInfo{}, ErrLegacyEditor
	}
	editorUserID := editor.Config.User.ID
	if editorUserID == "" {
		editorUserID = editor.User.ID // compatibility with older config fixtures
	}
	return YandexDocsInfo{
		Token: editor.Token, DocID: doc.Key, EditorUserID: editorUserID,
		IsExcel: isExcelFile(doc.FileType), Origin: "https://" + balancer.Host,
		Host: balancer.Host, Permissions: doc.Permissions,
		OpenCmd: map[string]interface{}{
			"c": "open", "id": doc.Key, "userid": userID, "format": doc.FileType,
			"url": doc.URL, "title": doc.Title, "lcid": 25,
		},
	}, nil
}
