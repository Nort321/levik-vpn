package main

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func initFixture() initWire {
	encode := base64.RawURLEncoding.EncodeToString
	token := encode([]byte(`{"alg":"HS256","typ":"JWT"}`)) + "." + encode([]byte(`{"document":{"key":"fixture-key","fileType":"docx","url":"http://localhost:12701/fixture","permissions":{"edit":true}},"editorConfig":{"user":{"id":"fixture-guest"}}}`)) + "." + encode(make([]byte, 32))
	return initWire{protocolMagic, protocolVersion, "init", "https://disk.yandex.ru/i/fixture-document", strings.Repeat("a", 42) + "A", strings.Repeat("b", 64), time.Now().Add(time.Hour).Unix(), bootstrapWire{"https://fixture_guest.onlyoffice.disk.yandex.net", token, time.Now().Add(10 * time.Minute).Unix()}, "@levik_ydx_" + strings.Repeat("n", 24), strings.Repeat("u", 24), strings.Repeat("p", 43)}
}

func TestInitIsBoundedStrictAndCarriesNoAccountOptions(t *testing.T) {
	init := initFixture()
	frame, _ := json.Marshal(init)
	var decoded initWire
	if strictDecode(frame, &decoded) != nil || decoded != init {
		t.Fatal("valid init did not round trip")
	}
	for _, frame := range []string{
		`{"magic":"LEVIK_YANDEX_ANDROID","version":2,"type":"STOP","type":"STATUS"}`,
		`{"magic":"LEVIK_YANDEX_ANDROID","version":2,"Type":"STOP"}`,
		`{"magic":"LEVIK_YANDEX_ANDROID","version":2,"type":"STOP","cookies":{}}`,
		`{"magic":"LEVIK_YANDEX_ANDROID","version":2,"type":"STOP"}{}`,
		`null`,
	} {
		var command commandWire
		if strictDecode([]byte(frame), &command) == nil {
			t.Fatal("noncanonical or secret-import command accepted")
		}
	}
	bad := strings.Replace(string(frame), `"balancerUrl"`, `"BalancerURL"`, 1)
	if strictDecode([]byte(bad), &decoded) == nil {
		t.Fatal("nested case alias accepted")
	}
	reader := bufio.NewReaderSize(strings.NewReader(strings.Repeat("a", maxControlFrame)+"\n"), maxControlFrame)
	if _, err := readFrame(reader); err == nil {
		t.Fatal("oversized frame accepted")
	}
	if validEnvelope("", 2) || validEnvelope(protocolMagic, 1) {
		t.Fatal("cross-protocol envelope accepted")
	}
}

func TestInitDeadlineCannotExtendAndCredentialsAreValidatedLocally(t *testing.T) {
	init := initFixture()
	now := time.Now()
	_, deadline, code := validateInit(init, now)
	if code != "" || !deadline.Equal(time.Unix(init.ProviderAuth.ValidUntil, 0)) || time.Until(deadline) > 10*time.Minute {
		t.Fatal("valid init did not preserve earliest credential deadline")
	}
	for _, change := range []func(*initWire){
		func(v *initWire) { v.ProxyPassword = "" },
		func(v *initWire) { v.SharedKey = strings.Repeat("g", 64) },
		func(v *initWire) { v.LeaseRef = strings.Repeat("a", 43) },
		func(v *initWire) { v.ProtectFDSocket = "/tmp/shared.sock" },
		func(v *initWire) { v.ProviderAuth.BalancerURL = "https://localhost" },
		func(v *initWire) { v.ProviderAuth.ValidUntil = time.Now().Add(16 * time.Minute).Unix() },
		func(v *initWire) { v.ProviderAuth.ValidUntil = now.Unix() - 1 },
		func(v *initWire) { v.LeaseExpiresAt = now.Unix() - 1 },
		func(v *initWire) { v.Magic = "WDTT" },
	} {
		bad := init
		change(&bad)
		if _, _, code := validateInit(bad, now); code == "" {
			t.Fatal("unsafe init accepted")
		}
	}
}

func TestTunnelDestinationRequiresPublicIPv4Literal(t *testing.T) {
	for _, address := range []string{"1.1.1.1:443", "8.8.8.8:53"} {
		if validateTunnelDestination(address) != nil {
			t.Fatal("public IPv4 rejected")
		}
	}
	for _, address := range []string{"example.com:443", "localhost:80", "127.0.0.1:443", "10.0.0.1:443", "169.254.169.254:80", "[2606:4700::1111]:443", "1.1.1.1:0", "1.1.1.1:65536"} {
		if validateTunnelDestination(address) == nil {
			t.Fatal("nonpublic or locally resolved destination accepted")
		}
	}
}
