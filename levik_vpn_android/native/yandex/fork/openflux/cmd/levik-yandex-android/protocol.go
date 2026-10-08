package main

import (
	"bufio"
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net"
	"reflect"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/yandex"
)

const (
	protocolMagic   = "LEVIK_YANDEX_ANDROID"
	protocolVersion = 2
	maxControlFrame = 32 << 10
	maxNetworkFrame = 8 << 10
)

var errProtocol = errors.New("invalid_protocol")

type bootstrapWire struct {
	BalancerURL string `json:"balancerUrl"`
	Token       string `json:"token"`
	ValidUntil  int64  `json:"validUntil"`
}

type initWire struct {
	Magic           string        `json:"magic"`
	Version         int           `json:"version"`
	Type            string        `json:"type"`
	DocumentURL     string        `json:"documentUrl"`
	LeaseRef        string        `json:"leaseRef"`
	SharedKey       string        `json:"sharedKey"`
	LeaseExpiresAt  int64         `json:"leaseExpiresAt"`
	ProviderAuth    bootstrapWire `json:"providerAuth"`
	ProtectFDSocket string        `json:"protectFdSocket"`
	ProxyUsername   string        `json:"proxyUsername"`
	ProxyPassword   string        `json:"proxyPassword"`
}

type commandWire struct {
	Magic   string `json:"magic"`
	Version int    `json:"version"`
	Type    string `json:"type"`
}

type refreshCommandWire struct {
	Magic          string        `json:"magic"`
	Version        int           `json:"version"`
	Type           string        `json:"type"`
	RequestID      int64         `json:"requestId"`
	LeaseExpiresAt int64         `json:"leaseExpiresAt"`
	ProviderAuth   bootstrapWire `json:"providerAuth"`
}

type refreshResult struct {
	RequestID      int64  `json:"requestId"`
	OK             bool   `json:"ok"`
	LeaseExpiresAt int64  `json:"leaseExpiresAt,omitempty"`
	ValidUntil     int64  `json:"validUntil,omitempty"`
	Code           string `json:"code,omitempty"`
}

type eventWire struct {
	Magic   string `json:"magic"`
	Version int    `json:"version"`
	Type    string `json:"type"`
	Phase   string `json:"phase,omitempty"`
	Code    string `json:"code,omitempty"`
	Data    any    `json:"data,omitempty"`
}

type controlChannel struct {
	conn    *net.UnixConn
	reader  *bufio.Reader
	writeMu sync.Mutex
}

func readFrame(reader *bufio.Reader) ([]byte, error) {
	frame, err := reader.ReadSlice('\n')
	if err != nil || len(frame) < 3 || frame[len(frame)-1] != '\n' {
		return nil, errProtocol
	}
	return frame[:len(frame)-1], nil
}

// Validate each object's exact field spelling and uniqueness before Go's
// case-insensitive struct decoder sees it. Size limits are enforced by the
// framing reader, and nesting/items are bounded independently.
func strictDecode(frame []byte, target any) error {
	decoder := json.NewDecoder(bytes.NewReader(frame))
	decoder.UseNumber()
	items := 0
	if uniqueValue(decoder, 0, &items) != nil {
		return errProtocol
	}
	if _, err := decoder.Token(); err != io.EOF {
		return errProtocol
	}
	typ := reflect.TypeOf(target)
	if typ.Kind() != reflect.Pointer || exactFields(frame, typ.Elem()) != nil {
		return errProtocol
	}
	decoder = json.NewDecoder(bytes.NewReader(frame))
	decoder.DisallowUnknownFields()
	if decoder.Decode(target) != nil || decoder.Decode(&struct{}{}) != io.EOF {
		return errProtocol
	}
	return nil
}

func uniqueValue(decoder *json.Decoder, depth int, items *int) error {
	*items++
	if depth > 16 || *items > 4096 {
		return errProtocol
	}
	token, err := decoder.Token()
	if err != nil {
		return errProtocol
	}
	delim, ok := token.(json.Delim)
	if !ok {
		return nil
	}
	switch delim {
	case '{':
		keys := make(map[string]bool)
		for decoder.More() {
			keyToken, err := decoder.Token()
			key, ok := keyToken.(string)
			if err != nil || !ok || keys[key] {
				return errProtocol
			}
			keys[key] = true
			if uniqueValue(decoder, depth+1, items) != nil {
				return errProtocol
			}
		}
		end, err := decoder.Token()
		if err != nil || end != json.Delim('}') {
			return errProtocol
		}
	case '[':
		for decoder.More() {
			if uniqueValue(decoder, depth+1, items) != nil {
				return errProtocol
			}
		}
		end, err := decoder.Token()
		if err != nil || end != json.Delim(']') {
			return errProtocol
		}
	default:
		return errProtocol
	}
	return nil
}

func exactFields(raw json.RawMessage, typ reflect.Type) error {
	if typ.Kind() != reflect.Struct {
		return nil
	}
	var object map[string]json.RawMessage
	if json.Unmarshal(raw, &object) != nil || object == nil {
		return errProtocol
	}
	fields := make(map[string]reflect.Type)
	for index := 0; index < typ.NumField(); index++ {
		field := typ.Field(index)
		name := strings.Split(field.Tag.Get("json"), ",")[0]
		fields[name] = field.Type
	}
	for key, value := range object {
		fieldType, ok := fields[key]
		if !ok || exactFields(value, fieldType) != nil {
			return errProtocol
		}
	}
	return nil
}

var (
	socketPattern        = regexp.MustCompile(`^@levik_ydx_[A-Za-z0-9_-]{16,90}$`)
	leaseRefPattern      = regexp.MustCompile(`^[A-Za-z0-9_-]{43}$`)
	sharedKeyPattern     = regexp.MustCompile(`^[0-9a-f]{64}$`)
	proxyUserPattern     = regexp.MustCompile(`^[A-Za-z0-9_-]{16,64}$`)
	proxyPasswordPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{32,128}$`)
)

func validEnvelope(magic string, version int) bool {
	return magic == protocolMagic && version == protocolVersion
}

func validateInit(init initWire, now time.Time) (*yandex.YandexDocsTransport, time.Time, string) {
	leaseBytes, leaseErr := base64.RawURLEncoding.DecodeString(init.LeaseRef)
	if !validEnvelope(init.Magic, init.Version) || init.Type != "init" || !socketPattern.MatchString(init.ProtectFDSocket) ||
		!leaseRefPattern.MatchString(init.LeaseRef) || !sharedKeyPattern.MatchString(init.SharedKey) ||
		!proxyUserPattern.MatchString(init.ProxyUsername) || !proxyPasswordPattern.MatchString(init.ProxyPassword) ||
		leaseErr != nil || len(leaseBytes) != 32 || base64.RawURLEncoding.EncodeToString(leaseBytes) != init.LeaseRef {
		return nil, time.Time{}, "invalid_init"
	}
	if init.LeaseExpiresAt <= now.Unix() || init.LeaseExpiresAt > now.Add(24*time.Hour).Unix() {
		return nil, time.Time{}, "lease_expired"
	}
	auth := init.ProviderAuth
	if auth.ValidUntil <= now.Unix() {
		return nil, time.Time{}, "provider_auth_expired"
	}
	if auth.ValidUntil > now.Add(15*time.Minute).Unix() || auth.ValidUntil > init.LeaseExpiresAt || len(auth.Token) > 8192 {
		return nil, time.Time{}, "invalid_init"
	}
	raw, err := yandex.NewRestrictedYandexDocsTransportWithGuestBootstrap(init.DocumentURL, yandex.GuestBootstrap{
		BalancerURL: auth.BalancerURL, Token: auth.Token, ValidUntil: time.Unix(auth.ValidUntil, 0),
	}, transport.DefaultConfig())
	if err != nil {
		return nil, time.Time{}, "invalid_provider_auth"
	}
	// Add the wall-clock duration to a monotonic time. Moving the Android clock
	// backwards cannot extend this process's lease or provider authorization.
	deadline := now.Add(time.Unix(min(init.LeaseExpiresAt, auth.ValidUntil), 0).Sub(now))
	return raw, deadline, ""
}

func (c *controlChannel) emit(kind, phase, code string, data any) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	_ = c.conn.SetWriteDeadline(time.Now().Add(3 * time.Second))
	err := json.NewEncoder(c.conn).Encode(eventWire{protocolMagic, protocolVersion, kind, phase, code, data})
	_ = c.conn.SetWriteDeadline(time.Time{})
	return err
}
