package yandex

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/url"
	"regexp"

	"github.com/p1neappleXpress/OpenFlux/transport/egresspolicy"
)

const restrictedEditorAssetLimit = 256 << 10

var restrictedEditorBuildPattern = regexp.MustCompile(`[?&]_dc=([0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}-[0-9]{1,10})(?:["'&#\s]|$)`)

func fetchRestrictedEditorBuild(ctx context.Context, host string, client *http.Client, guest *guestCookieJar) (string, error) {
	assetURL := &url.URL{Scheme: "https", Host: host, Path: "/web-apps/apps/api/documents/api.js"}
	if _, err := egresspolicy.ValidateProviderURL(assetURL.String(), false); err != nil {
		return "", egresspolicy.ErrProviderURL
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, assetURL.String(), nil)
	if err != nil {
		return "", ErrEditorBuild
	}
	req.Header.Set("User-Agent", volgaUserAgent)
	resp, err := client.Do(req)
	if err != nil {
		return "", ErrEditorBuild
	}
	defer resp.Body.Close()
	if guest.rejected.Load() {
		return "", ErrAccountCookies
	}
	// The observed public asset responds directly. No redirect, including a
	// same-origin redirect, is followed without a confirmed compatibility need.
	if resp.StatusCode != http.StatusOK {
		return "", ErrEditorBuild
	}
	if resp.ContentLength > restrictedEditorAssetLimit {
		return "", ErrProviderBodyLimit
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, restrictedEditorAssetLimit+1))
	if len(body) > restrictedEditorAssetLimit {
		return "", ErrProviderBodyLimit
	}
	if err != nil {
		return "", ErrEditorBuild
	}
	return parseRestrictedEditorBuild(body)
}

func parseRestrictedEditorBuild(body []byte) (string, error) {
	if len(body) > restrictedEditorAssetLimit {
		return "", ErrProviderBodyLimit
	}
	if bytes.Count(body, []byte("_dc=")) != 1 {
		return "", ErrEditorBuild
	}
	matches := restrictedEditorBuildPattern.FindAllSubmatch(body, 2)
	if len(matches) != 1 {
		return "", ErrEditorBuild
	}
	return string(matches[0][1]), nil
}
