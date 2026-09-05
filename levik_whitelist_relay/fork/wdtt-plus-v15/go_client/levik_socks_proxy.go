// SPDX-License-Identifier: GPL-3.0-only
//go:build linux || android

package main

import (
	"bufio"
	"context"
	"crypto/subtle"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

const (
	socksVersion          = 5
	socksAuthPassword     = 2
	socksCommandConnect   = 1
	socksCommandUDP       = 3
	socksAddressIPv4      = 1
	socksAddressDomain    = 3
	socksAddressIPv6      = 4
	socksReplyOK          = 0
	socksReplyFailure     = 1
	socksReplyUnsupported = 7
	socksUDPMaxTargets    = 256
	socksUDPIdleTimeout   = 2 * time.Minute
	socksDialTimeout      = 15 * time.Second
)

type levikProxyPlan struct {
	Address string `json:"address"`
	Port    int    `json:"port"`
}

type levikSocksServer struct {
	ctx      context.Context
	cancel   context.CancelFunc
	listener *net.TCPListener
	network  *netstack.Net
	username string
	password string
	close    sync.Once
}

func startLevikSocksDataPlane(
	ctx context.Context,
	config levikWGConfig,
	username, password string,
	control *levikControl,
) error {
	addresses := make([]netip.Addr, 0, len(config.addresses))
	for _, value := range config.addresses {
		prefix, err := netip.ParsePrefix(value)
		if err != nil || !prefix.Addr().Is4() {
			return errors.New("invalid WireGuard netstack address")
		}
		addresses = append(addresses, prefix.Addr())
	}
	dnsServers := make([]netip.Addr, 0, len(config.dns))
	for _, value := range config.dns {
		address, err := netip.ParseAddr(value)
		if err != nil || !address.Is4() {
			return errors.New("invalid WireGuard netstack DNS")
		}
		dnsServers = append(dnsServers, address)
	}

	tunDevice, network, err := netstack.CreateNetTUN(addresses, dnsServers, config.mtu)
	if err != nil {
		return fmt.Errorf("WireGuard netstack: %w", err)
	}
	wgDevice := device.NewDevice(
		tunDevice,
		conn.NewDefaultBind(),
		device.NewLogger(device.LogLevelError, "[LEVIK-WG] "),
	)
	cleanup := func() {
		wgDevice.Close()
		_ = tunDevice.Close()
	}
	if err := wgDevice.IpcSet(config.ipcRequest()); err != nil {
		cleanup()
		return fmt.Errorf("WireGuard configure: %w", err)
	}
	if err := wgDevice.Up(); err != nil {
		cleanup()
		return fmt.Errorf("WireGuard start: %w", err)
	}
	if err := waitForProtectedWireGuardSocket(ctx); err != nil {
		cleanup()
		return err
	}

	proxy, err := newLevikSocksServer(ctx, network, username, password)
	if err != nil {
		cleanup()
		return err
	}
	context.AfterFunc(ctx, func() {
		proxy.Close()
		cleanup()
	})
	port := proxy.listener.Addr().(*net.TCPAddr).Port
	control.emit(levikControlEvent{
		Version: levikControlVersion,
		Type:    "proxy_plan",
		Phase:   "PREPARED",
		Data: levikProxyPlan{
			Address: "127.0.0.1",
			Port:    port,
		},
	})
	control.ready("RUNNING", map[string]any{"protocolVersion": levikControlVersion})
	return nil
}

func waitForProtectedWireGuardSocket(ctx context.Context) error {
	deadline := time.NewTimer(3 * time.Second)
	ticker := time.NewTicker(25 * time.Millisecond)
	defer deadline.Stop()
	defer ticker.Stop()
	for {
		if levikProtectedSocketCount() > 0 {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-deadline.C:
			return errors.New("no external socket received Android protect/bind ACK")
		case <-ticker.C:
		}
	}
}

func newLevikSocksServer(
	parent context.Context,
	network *netstack.Net,
	username, password string,
) (*levikSocksServer, error) {
	if !validProxyCredential(username, 16, 64) || !validProxyCredential(password, 32, 128) {
		return nil, errors.New("invalid SOCKS credentials")
	}
	listener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return nil, fmt.Errorf("SOCKS listen: %w", err)
	}
	ctx, cancel := context.WithCancel(parent)
	server := &levikSocksServer{
		ctx:      ctx,
		cancel:   cancel,
		listener: listener,
		network:  network,
		username: username,
		password: password,
	}
	go server.acceptLoop()
	return server, nil
}

func (server *levikSocksServer) Close() {
	server.close.Do(func() {
		server.cancel()
		_ = server.listener.Close()
	})
}

func (server *levikSocksServer) acceptLoop() {
	for {
		client, err := server.listener.AcceptTCP()
		if err != nil {
			if server.ctx.Err() != nil {
				return
			}
			continue
		}
		if !client.RemoteAddr().(*net.TCPAddr).IP.IsLoopback() {
			_ = client.Close()
			continue
		}
		go server.handleClient(client)
	}
}

func (server *levikSocksServer) handleClient(client *net.TCPConn) {
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(15 * time.Second))
	reader := bufio.NewReaderSize(client, 4<<10)
	if err := server.authenticate(reader, client); err != nil {
		return
	}
	header := make([]byte, 4)
	if _, err := io.ReadFull(reader, header); err != nil ||
		header[0] != socksVersion || header[2] != 0 {
		return
	}
	target, _, err := readSocksAddress(reader, header[3])
	if err != nil {
		_ = writeSocksReply(client, socksReplyFailure, 0)
		return
	}
	_ = client.SetDeadline(time.Time{})
	switch header[1] {
	case socksCommandConnect:
		server.handleConnect(client, reader, target)
	case socksCommandUDP:
		server.handleUDPAssociate(client)
	default:
		_ = writeSocksReply(client, socksReplyUnsupported, 0)
	}
}

func (server *levikSocksServer) authenticate(reader *bufio.Reader, client net.Conn) error {
	header := make([]byte, 2)
	if _, err := io.ReadFull(reader, header); err != nil ||
		header[0] != socksVersion || header[1] == 0 || header[1] > 16 {
		return errors.New("invalid SOCKS greeting")
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(reader, methods); err != nil {
		return err
	}
	supported := false
	for _, method := range methods {
		if method == socksAuthPassword {
			supported = true
		}
	}
	if !supported {
		_, _ = client.Write([]byte{socksVersion, 0xff})
		return errors.New("SOCKS authentication unavailable")
	}
	if _, err := client.Write([]byte{socksVersion, socksAuthPassword}); err != nil {
		return err
	}
	if version, err := reader.ReadByte(); err != nil || version != 1 {
		return errors.New("invalid SOCKS authentication")
	}
	username, err := readLengthPrefixed(reader)
	if err != nil {
		return err
	}
	password, err := readLengthPrefixed(reader)
	if err != nil {
		return err
	}
	valid := subtle.ConstantTimeCompare(username, []byte(server.username)) == 1 &&
		subtle.ConstantTimeCompare(password, []byte(server.password)) == 1
	status := byte(0)
	if !valid {
		status = 1
	}
	_, _ = client.Write([]byte{1, status})
	if !valid {
		return errors.New("invalid SOCKS credentials")
	}
	return nil
}

func readLengthPrefixed(reader io.Reader) ([]byte, error) {
	length := []byte{0}
	if _, err := io.ReadFull(reader, length); err != nil || length[0] == 0 {
		return nil, errors.New("invalid length-prefixed value")
	}
	value := make([]byte, int(length[0]))
	_, err := io.ReadFull(reader, value)
	return value, err
}

func (server *levikSocksServer) handleConnect(
	client net.Conn,
	reader io.Reader,
	target string,
) {
	ctx, cancel := context.WithTimeout(server.ctx, socksDialTimeout)
	upstream, err := server.network.DialContext(ctx, "tcp", target)
	cancel()
	if err != nil {
		_ = writeSocksReply(client, socksReplyFailure, 0)
		return
	}
	defer upstream.Close()
	if err := writeSocksReply(client, socksReplyOK, 0); err != nil {
		return
	}
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(upstream, reader)
		if tcp, ok := upstream.(interface{ CloseWrite() error }); ok {
			_ = tcp.CloseWrite()
		}
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(client, upstream)
		if tcp, ok := client.(interface{ CloseWrite() error }); ok {
			_ = tcp.CloseWrite()
		}
		done <- struct{}{}
	}()
	select {
	case <-server.ctx.Done():
	case <-done:
	}
}

type socksUDPRelay struct {
	target    string
	header    []byte
	conn      net.Conn
	lastUsed  time.Time
	writeLock sync.Mutex
}

type socksUDPAssociation struct {
	ctx        context.Context
	cancel     context.CancelFunc
	socket     *net.UDPConn
	network    *netstack.Net
	mu         sync.Mutex
	clientAddr *net.UDPAddr
	relays     map[string]*socksUDPRelay
}

func (server *levikSocksServer) handleUDPAssociate(client *net.TCPConn) {
	socket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		_ = writeSocksReply(client, socksReplyFailure, 0)
		return
	}
	port := socket.LocalAddr().(*net.UDPAddr).Port
	if err := writeSocksReply(client, socksReplyOK, port); err != nil {
		_ = socket.Close()
		return
	}
	ctx, cancel := context.WithCancel(server.ctx)
	association := &socksUDPAssociation{
		ctx:     ctx,
		cancel:  cancel,
		socket:  socket,
		network: server.network,
		relays:  make(map[string]*socksUDPRelay),
	}
	go func() {
		_, _ = io.Copy(io.Discard, client)
		cancel()
		_ = socket.Close()
	}()
	association.run()
}

func (association *socksUDPAssociation) run() {
	defer association.close()
	buffer := make([]byte, 65_535)
	for {
		_ = association.socket.SetReadDeadline(time.Now().Add(time.Second))
		count, client, err := association.socket.ReadFromUDP(buffer)
		if err != nil {
			if association.ctx.Err() != nil {
				return
			}
			if netError, ok := err.(net.Error); ok && netError.Timeout() {
				association.pruneIdle()
				continue
			}
			return
		}
		if !client.IP.IsLoopback() || count < 7 {
			continue
		}
		association.mu.Lock()
		if association.clientAddr == nil {
			association.clientAddr = client
		}
		acceptedClient := association.clientAddr.IP.Equal(client.IP) &&
			association.clientAddr.Port == client.Port
		association.mu.Unlock()
		if !acceptedClient {
			continue
		}
		target, payloadOffset, replyHeader, err := parseSocksUDPPacket(buffer[:count])
		if err != nil {
			continue
		}
		relay := association.relayFor(target, replyHeader)
		if relay == nil {
			continue
		}
		relay.writeLock.Lock()
		_, err = relay.conn.Write(buffer[payloadOffset:count])
		relay.writeLock.Unlock()
		if err != nil {
			association.removeRelay(target, relay)
		}
	}
}

func (association *socksUDPAssociation) relayFor(target string, header []byte) *socksUDPRelay {
	association.mu.Lock()
	if relay := association.relays[target]; relay != nil {
		relay.lastUsed = time.Now()
		association.mu.Unlock()
		return relay
	}
	if len(association.relays) >= socksUDPMaxTargets {
		association.mu.Unlock()
		return nil
	}
	association.mu.Unlock()

	ctx, cancel := context.WithTimeout(association.ctx, socksDialTimeout)
	connection, err := association.network.DialContext(ctx, "udp", target)
	cancel()
	if err != nil {
		return nil
	}
	relay := &socksUDPRelay{
		target:   target,
		header:   append([]byte(nil), header...),
		conn:     connection,
		lastUsed: time.Now(),
	}
	association.mu.Lock()
	if existing := association.relays[target]; existing != nil {
		association.mu.Unlock()
		_ = connection.Close()
		return existing
	}
	association.relays[target] = relay
	association.mu.Unlock()
	go association.readResponses(relay)
	return relay
}

func (association *socksUDPAssociation) readResponses(relay *socksUDPRelay) {
	buffer := make([]byte, 65_507)
	for {
		_ = relay.conn.SetReadDeadline(time.Now().Add(time.Second))
		count, err := relay.conn.Read(buffer)
		if err != nil {
			if association.ctx.Err() == nil {
				if netError, ok := err.(net.Error); ok && netError.Timeout() {
					continue
				}
			}
			association.removeRelay(relay.target, relay)
			return
		}
		association.mu.Lock()
		client := association.clientAddr
		relay.lastUsed = time.Now()
		association.mu.Unlock()
		if client == nil {
			continue
		}
		packet := make([]byte, len(relay.header)+count)
		copy(packet, relay.header)
		copy(packet[len(relay.header):], buffer[:count])
		_, _ = association.socket.WriteToUDP(packet, client)
	}
}

func (association *socksUDPAssociation) pruneIdle() {
	cutoff := time.Now().Add(-socksUDPIdleTimeout)
	association.mu.Lock()
	var expired []*socksUDPRelay
	for target, relay := range association.relays {
		if relay.lastUsed.Before(cutoff) {
			delete(association.relays, target)
			expired = append(expired, relay)
		}
	}
	association.mu.Unlock()
	for _, relay := range expired {
		_ = relay.conn.Close()
	}
}

func (association *socksUDPAssociation) removeRelay(target string, expected *socksUDPRelay) {
	association.mu.Lock()
	if association.relays[target] == expected {
		delete(association.relays, target)
	}
	association.mu.Unlock()
	_ = expected.conn.Close()
}

func (association *socksUDPAssociation) close() {
	association.cancel()
	_ = association.socket.Close()
	association.mu.Lock()
	relays := association.relays
	association.relays = make(map[string]*socksUDPRelay)
	association.mu.Unlock()
	for _, relay := range relays {
		_ = relay.conn.Close()
	}
}

func readSocksAddress(reader io.Reader, addressType byte) (string, []byte, error) {
	address := []byte{addressType}
	var host string
	switch addressType {
	case socksAddressIPv4:
		value := make([]byte, net.IPv4len)
		if _, err := io.ReadFull(reader, value); err != nil {
			return "", nil, err
		}
		host = net.IP(value).String()
		address = append(address, value...)
	case socksAddressIPv6:
		value := make([]byte, net.IPv6len)
		if _, err := io.ReadFull(reader, value); err != nil {
			return "", nil, err
		}
		host = net.IP(value).String()
		address = append(address, value...)
	case socksAddressDomain:
		value, err := readLengthPrefixed(reader)
		if err != nil {
			return "", nil, err
		}
		host = string(value)
		address = append(address, byte(len(value)))
		address = append(address, value...)
	default:
		return "", nil, errors.New("unsupported SOCKS address")
	}
	portBytes := make([]byte, 2)
	if _, err := io.ReadFull(reader, portBytes); err != nil {
		return "", nil, err
	}
	port := int(binary.BigEndian.Uint16(portBytes))
	if port == 0 || host == "" {
		return "", nil, errors.New("invalid SOCKS target")
	}
	address = append(address, portBytes...)
	return net.JoinHostPort(host, strconv.Itoa(port)), address, nil
}

func parseSocksUDPPacket(packet []byte) (string, int, []byte, error) {
	if len(packet) < 7 || packet[0] != 0 || packet[1] != 0 || packet[2] != 0 {
		return "", 0, nil, errors.New("invalid SOCKS UDP packet")
	}
	reader := &byteReader{data: packet[4:]}
	target, address, err := readSocksAddress(reader, packet[3])
	if err != nil {
		return "", 0, nil, err
	}
	offset := 4 + reader.offset
	header := make([]byte, 3+len(address))
	copy(header[3:], address)
	return target, offset, header, nil
}

type byteReader struct {
	data   []byte
	offset int
}

func (reader *byteReader) Read(output []byte) (int, error) {
	if reader.offset >= len(reader.data) {
		return 0, io.EOF
	}
	count := copy(output, reader.data[reader.offset:])
	reader.offset += count
	return count, nil
}

func writeSocksReply(writer io.Writer, reply byte, port int) error {
	response := []byte{socksVersion, reply, 0, socksAddressIPv4, 127, 0, 0, 1, 0, 0}
	binary.BigEndian.PutUint16(response[8:], uint16(port))
	_, err := writer.Write(response)
	return err
}
