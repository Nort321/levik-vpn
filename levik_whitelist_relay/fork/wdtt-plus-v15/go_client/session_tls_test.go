package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"net"
	"testing"
	"time"
)

type recordingConn struct {
	writes [][]byte
}

func (conn *recordingConn) Read(_ []byte) (int, error)         { return 0, nil }
func (conn *recordingConn) Close() error                       { return nil }
func (conn *recordingConn) LocalAddr() net.Addr                { return nil }
func (conn *recordingConn) RemoteAddr() net.Addr               { return nil }
func (conn *recordingConn) SetDeadline(_ time.Time) error      { return nil }
func (conn *recordingConn) SetReadDeadline(_ time.Time) error  { return nil }
func (conn *recordingConn) SetWriteDeadline(_ time.Time) error { return nil }

func (conn *recordingConn) Write(payload []byte) (int, error) {
	copyOfPayload := append([]byte(nil), payload...)
	conn.writes = append(conn.writes, copyOfPayload)
	return len(payload), nil
}

type turnTestCA struct {
	certificate *x509.Certificate
	privateKey  *ecdsa.PrivateKey
	roots       *x509.CertPool
}

func newTURNTestCA(t *testing.T) turnTestCA {
	t.Helper()
	privateKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate test CA key: %v", err)
	}
	now := time.Now()
	template := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "TURN test CA"},
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.Add(time.Hour),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	rawCertificate, err := x509.CreateCertificate(
		rand.Reader,
		template,
		template,
		&privateKey.PublicKey,
		privateKey,
	)
	if err != nil {
		t.Fatalf("create test CA: %v", err)
	}
	certificate, err := x509.ParseCertificate(rawCertificate)
	if err != nil {
		t.Fatalf("parse test CA: %v", err)
	}
	roots := x509.NewCertPool()
	roots.AddCert(certificate)
	return turnTestCA{certificate: certificate, privateKey: privateKey, roots: roots}
}

func (ca turnTestCA) issueServerCertificate(
	t *testing.T,
	dnsNames []string,
	ipAddresses []net.IP,
) tls.Certificate {
	t.Helper()
	privateKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate test server key: %v", err)
	}
	now := time.Now()
	template := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: "TURN test server"},
		NotBefore:    now.Add(-time.Hour),
		NotAfter:     now.Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:     dnsNames,
		IPAddresses:  ipAddresses,
	}
	rawCertificate, err := x509.CreateCertificate(
		rand.Reader,
		template,
		ca.certificate,
		&privateKey.PublicKey,
		ca.privateKey,
	)
	if err != nil {
		t.Fatalf("create test server certificate: %v", err)
	}
	certificate, err := x509.ParseCertificate(rawCertificate)
	if err != nil {
		t.Fatalf("parse test server certificate: %v", err)
	}
	return tls.Certificate{
		Certificate: [][]byte{rawCertificate},
		PrivateKey:  privateKey,
		Leaf:        certificate,
	}
}

func TestNormalizeTURNFrontSNI(t *testing.T) {
	tests := []struct {
		input string
		want  string
		ok    bool
	}{
		{input: " Ya.RU ", want: "ya.ru", ok: true},
		{input: "telemost.yandex.ru", want: "telemost.yandex.ru", ok: true},
		{input: "", want: "", ok: true},
		{input: "ya", ok: false},
		{input: "127.0.0.1", ok: false},
		{input: "я.рф", ok: false},
		{input: "-ya.ru", ok: false},
		{input: "ya..ru", ok: false},
	}
	for _, tt := range tests {
		t.Run(tt.input, func(t *testing.T) {
			got, err := normalizeTURNFrontSNI(tt.input)
			if (err == nil) != tt.ok {
				t.Fatalf("normalizeTURNFrontSNI(%q) error=%v", tt.input, err)
			}
			if got != tt.want {
				t.Fatalf("normalizeTURNFrontSNI(%q)=%q, want %q", tt.input, got, tt.want)
			}
		})
	}
}

func TestTurnTLSConfigUsesFrontSNIWithoutDisablingChainCheck(t *testing.T) {
	endpoint := turnEndpoint{Host: "turn.example", Port: "443", Transport: turnTransportTLS}
	config := turnTLSConfig(endpoint, "ya.ru")
	if config.ServerName != "ya.ru" {
		t.Fatalf("ServerName=%q, want ya.ru", config.ServerName)
	}
	if !config.InsecureSkipVerify || config.VerifyConnection == nil {
		t.Fatal("front SNI must replace default verification with explicit CA-chain and endpoint verification")
	}

	normal := turnTLSConfig(endpoint, "")
	if normal.ServerName != "turn.example" || normal.InsecureSkipVerify || normal.VerifyConnection != nil {
		t.Fatalf("normal TLS verification changed unexpectedly: %#v", normal)
	}
}

func TestTurnTLSFrontSNIAuthenticatesEndpointDNSName(t *testing.T) {
	trustedCA := newTURNTestCA(t)
	endpoint := turnEndpoint{Host: "turn.example", Port: "443", Transport: turnTransportTLS}
	config := turnTLSConfig(endpoint, "ya.ru")
	config.RootCAs = trustedCA.roots

	validCertificate := trustedCA.issueServerCertificate(t, []string{"turn.example"}, nil)
	validState := tls.ConnectionState{PeerCertificates: []*x509.Certificate{validCertificate.Leaf}}
	if err := config.VerifyConnection(validState); err != nil {
		t.Fatalf("certificate for the real TURN endpoint was rejected: %v", err)
	}

	trustedFrontName := trustedCA.issueServerCertificate(t, []string{"ya.ru"}, nil)
	wrongNameState := tls.ConnectionState{PeerCertificates: []*x509.Certificate{trustedFrontName.Leaf}}
	if err := config.VerifyConnection(wrongNameState); err == nil {
		t.Fatal("trusted certificate for the outer SNI instead of the TURN endpoint was accepted")
	}

	untrustedCA := newTURNTestCA(t)
	untrustedCorrectName := untrustedCA.issueServerCertificate(t, []string{"turn.example"}, nil)
	untrustedState := tls.ConnectionState{PeerCertificates: []*x509.Certificate{untrustedCorrectName.Leaf}}
	if err := config.VerifyConnection(untrustedState); err == nil {
		t.Fatal("certificate outside the trusted CA chain was accepted")
	}
}

func TestTurnTLSFrontSNIAuthenticatesEndpointIP(t *testing.T) {
	trustedCA := newTURNTestCA(t)
	endpointIP := net.ParseIP("203.0.113.7")
	endpoint := turnEndpoint{Host: endpointIP.String(), Port: "443", Transport: turnTransportTLS}
	config := turnTLSConfig(endpoint, "ya.ru")
	config.RootCAs = trustedCA.roots

	validCertificate := trustedCA.issueServerCertificate(t, nil, []net.IP{endpointIP})
	validState := tls.ConnectionState{PeerCertificates: []*x509.Certificate{validCertificate.Leaf}}
	if err := config.VerifyConnection(validState); err != nil {
		t.Fatalf("certificate for the real TURN endpoint IP was rejected: %v", err)
	}

	wrongIPCertificate := trustedCA.issueServerCertificate(t, nil, []net.IP{net.ParseIP("203.0.113.8")})
	wrongIPState := tls.ConnectionState{PeerCertificates: []*x509.Certificate{wrongIPCertificate.Leaf}}
	if err := config.VerifyConnection(wrongIPState); err == nil {
		t.Fatal("trusted certificate for another IP address was accepted")
	}
}

func TestTurnTLSWithoutFrontSNIAuthenticatesEndpointIP(t *testing.T) {
	trustedCA := newTURNTestCA(t)
	endpointIP := net.ParseIP("203.0.113.7")
	endpoint := turnEndpoint{Host: endpointIP.String(), Port: "443", Transport: turnTransportTLS}
	serverCertificate := trustedCA.issueServerCertificate(t, nil, []net.IP{endpointIP})
	clientConfig := turnTLSConfig(endpoint, "")
	clientConfig.RootCAs = trustedCA.roots
	clientConfig.MaxVersion = tls.VersionTLS12
	if clientConfig.ServerName != endpoint.Host {
		t.Fatalf("ServerName=%q, want endpoint IP %q", clientConfig.ServerName, endpoint.Host)
	}

	clientRaw, serverRaw := net.Pipe()
	serverResult := make(chan struct {
		err        error
		serverName string
	}, 1)
	go func() {
		serverConnection := tls.Server(serverRaw, &tls.Config{
			Certificates: []tls.Certificate{serverCertificate},
			MinVersion:   tls.VersionTLS12,
			MaxVersion:   tls.VersionTLS12,
		})
		err := serverConnection.Handshake()
		serverName := serverConnection.ConnectionState().ServerName
		_ = serverRaw.Close()
		serverResult <- struct {
			err        error
			serverName string
		}{err: err, serverName: serverName}
	}()

	clientConnection := tls.Client(clientRaw, clientConfig)
	clientErr := clientConnection.Handshake()
	_ = clientRaw.Close()
	result := <-serverResult
	if clientErr != nil {
		t.Fatalf("tls.Client rejected the valid endpoint IP certificate: %v", clientErr)
	}
	if result.err != nil {
		t.Fatalf("test TLS server handshake failed: %v", result.err)
	}
	if result.serverName != "" {
		t.Fatalf("IP endpoint leaked into ClientHello SNI: %q", result.serverName)
	}
}

func TestSplitFirstWriteConnOnlySplitsFirstWrite(t *testing.T) {
	underlying := &recordingConn{}
	conn := &splitFirstWriteConn{Conn: underlying, splitAt: 6}

	first := []byte("0123456789")
	if n, err := conn.Write(first); err != nil || n != len(first) {
		t.Fatalf("first Write() = (%d, %v), want (%d, nil)", n, err, len(first))
	}
	second := []byte("abcdef")
	if n, err := conn.Write(second); err != nil || n != len(second) {
		t.Fatalf("second Write() = (%d, %v), want (%d, nil)", n, err, len(second))
	}

	if len(underlying.writes) != 3 {
		t.Fatalf("underlying writes=%q, want three writes", underlying.writes)
	}
	if got := string(underlying.writes[0]); got != "012345" {
		t.Fatalf("first fragment=%q, want %q", got, "012345")
	}
	if got := string(underlying.writes[1]); got != "6789" {
		t.Fatalf("second fragment=%q, want %q", got, "6789")
	}
	if got := string(underlying.writes[2]); got != "abcdef" {
		t.Fatalf("subsequent write=%q, want %q", got, "abcdef")
	}
}
