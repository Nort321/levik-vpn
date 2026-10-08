package yandex

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/p1neappleXpress/OpenFlux/transport/egresspolicy"
)

const (
	restrictedPowMaxComplexity = 22
	restrictedPowMaxAttempts   = 4 << 20
	restrictedPowTimeout       = 3 * time.Second
)

// EnableRestrictedFastChallenge opts a stopped restricted carrier into one
// automatic docs.yandex.ru blink-check proof. It never enables SmartCaptcha,
// external solvers, JavaScript execution or account-cookie import.
func (t *YandexDocsTransport) EnableRestrictedFastChallenge() error {
	if !t.restricted || t.IsRunning() {
		return ErrProviderResponse
	}
	t.fastChallengeAllowed.Store(true)
	return nil
}

func (t *YandexVolgaTransport) EnableRestrictedFastChallenge() error {
	if !t.restricted || t.IsRunning() {
		return ErrProviderResponse
	}
	t.fastChallengeAllowed.Store(true)
	return nil
}

func restrictedFastChallengeURL(u *url.URL) bool {
	return u != nil && u.Scheme == "https" && u.Host == "docs.yandex.ru" &&
		u.Path == "/showcaptchafast" && u.RawPath == "" && u.User == nil && u.Fragment == ""
}

func solveRestrictedFastChallenge(ctx context.Context, challenge *url.URL, client *http.Client, guest *guestCookieJar) (*url.URL, error) {
	if !guest.allowFastChallenge || guest.fastChallengeUsed == nil || !restrictedFastChallengeURL(challenge) ||
		!guest.fastChallengeUsed.CompareAndSwap(false, true) {
		return nil, ErrCaptchaRequired
	}
	if _, err := egresspolicy.ValidateProviderURL(challenge.String(), false); err != nil {
		return nil, ErrCaptchaRequired
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, challenge.String(), nil)
	if err != nil {
		return nil, ErrCaptchaRequired
	}
	req.Header.Set("User-Agent", volgaUserAgent)
	req.Header.Set("Accept-Language", "ru-RU,ru;q=0.9")
	resp, err := client.Do(req)
	if err != nil {
		return nil, ErrCaptchaRequired
	}
	body, err := readRestrictedResponse(resp)
	if guest.rejected.Load() {
		return nil, ErrAccountCookies
	}
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, ErrCaptchaRequired
	}
	ssr, rawAction, err := parseCaptchaHTML(string(body))
	if err != nil || ssr.Timestamp <= 0 || !boundedCredential(ssr.UniqueKey, 4096) || len(ssr.Pow.Prefix) > 512 {
		return nil, ErrCaptchaRequired
	}
	actionRef, err := url.Parse(rawAction)
	if err != nil {
		return nil, ErrCaptchaRequired
	}
	action, err := egresspolicy.ValidateProviderURL(challenge.ResolveReference(actionRef).String(), false)
	if err != nil || action.Host != "docs.yandex.ru" || action.RawPath != "" ||
		(action.Path != "/checkcaptcha" && action.Path != "/checkcaptchafast") {
		return nil, ErrCaptchaRequired
	}
	nonce, err := solveRestrictedPoW(ctx, ssr.Pow.Prefix, ssr.Pow.Complexity)
	if err != nil {
		return nil, err
	}
	fingerprint := encodeCaptchaFingerprint(buildCaptchaFingerprint(nonce, volgaUserAgent))
	form := url.Values{"version": {"1.5.0"}, "uniquekey": {ssr.UniqueKey}, "chstate": {"ok"}, "fingerprint": {fingerprint}}
	req, err = http.NewRequestWithContext(ctx, http.MethodPost, action.String(), strings.NewReader(form.Encode()))
	if err != nil {
		return nil, ErrCaptchaRequired
	}
	req.Header.Set("User-Agent", volgaUserAgent)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Origin", "https://docs.yandex.ru")
	req.Header.Set("Referer", challenge.String())
	resp, err = client.Do(req)
	if err != nil {
		return nil, ErrCaptchaRequired
	}
	_, err = readRestrictedResponse(resp)
	if guest.rejected.Load() {
		return nil, ErrAccountCookies
	}
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusFound && resp.StatusCode != http.StatusSeeOther {
		return nil, ErrCaptchaRequired
	}
	ref, err := url.Parse(resp.Header.Get("Location"))
	if err != nil || resp.Header.Get("Location") == "" {
		return nil, ErrCaptchaRequired
	}
	resolved := action.ResolveReference(ref)
	if err := restrictedCheckURL(resolved); err != nil {
		return nil, err
	}
	next, err := egresspolicy.ValidateProviderURL(resolved.String(), false)
	if err != nil {
		return nil, egresspolicy.ErrProviderURL
	}
	return next, nil
}

func solveRestrictedPoW(ctx context.Context, prefixHex string, complexity int) (string, error) {
	if complexity < 0 || complexity > restrictedPowMaxComplexity || len(prefixHex) == 0 || len(prefixHex) > 512 {
		return "", ErrCaptchaRequired
	}
	prefix, err := hexDecode(prefixHex)
	if err != nil || len(prefix) == 0 {
		return "", ErrCaptchaRequired
	}
	ctx, cancel := context.WithTimeout(ctx, restrictedPowTimeout)
	defer cancel()
	buffer := make([]byte, 16+len(prefix))
	if _, err := rand.Read(buffer[:16]); err != nil {
		return "", ErrCaptchaRequired
	}
	copy(buffer[16:], prefix)
	seed := binary.LittleEndian.Uint64(buffer[8:16])
	for attempt := 0; attempt < restrictedPowMaxAttempts; attempt++ {
		if attempt%1024 == 0 {
			if ctx.Err() != nil {
				return "", ErrCaptchaRequired
			}
			putU64LE(buffer[:8], uint64(time.Now().UnixMilli()))
		}
		putU64LE(buffer[8:16], seed+uint64(attempt))
		hash := sha256.Sum256(buffer)
		if captchaCheckComplexity(hash[:], complexity) {
			return hexEncode(buffer[:16]), nil
		}
	}
	return "", ErrCaptchaRequired
}
