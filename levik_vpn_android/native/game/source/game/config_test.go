package game

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

func validConfig() map[string]any {
	return map[string]any{"version": 1, "listen": map[string]any{"port": 41000, "uuid": testUUID}, "password": "test-password-0123456789",
		"routes": []map[string]any{{"index": 0, "port": 48000, "nodeId": "spb"}, {"index": 1, "port": 48001, "nodeId": "msk"}},
		"nodes":  []map[string]any{{"nodeId": "spb", "name": "Санкт-Петербург", "state": "enabled"}, {"nodeId": "msk", "name": "Москва", "state": "draining"}}}
}

func TestParseConfigRejectsWhatTheAppNeverWrites(t *testing.T) {
	raw, _ := json.Marshal(validConfig())
	if _, e := ParseConfig(raw); e != nil {
		t.Fatal(e)
	}
	cases := map[string]func(map[string]any){
		"version":    func(c map[string]any) { c["version"] = 2 },
		"uuid":       func(c map[string]any) { c["listen"] = map[string]any{"port": 1, "uuid": "x"} },
		"short pass": func(c map[string]any) { c["password"] = "short" },
		"no routes":  func(c map[string]any) { c["routes"] = []any{} },
		"dup port": func(c map[string]any) {
			c["routes"] = []map[string]any{{"index": 0, "port": 48000, "nodeId": "a"}, {"index": 1, "port": 48000, "nodeId": "b"}}
		},
		"low port":      func(c map[string]any) { c["routes"] = []map[string]any{{"index": 0, "port": 80, "nodeId": "a"}} },
		"bad node":      func(c map[string]any) { c["routes"] = []map[string]any{{"index": 0, "port": 48000, "nodeId": "a b"}} },
		"bad state":     func(c map[string]any) { c["nodes"] = []map[string]any{{"nodeId": "spb", "name": "x", "state": "on"}} },
		"bad token":     func(c map[string]any) { c["relayToken"] = "TEdUMQ" + strings.Repeat("A", 119) },
		"bad preferred": func(c map[string]any) { c["preferredExitId"] = "../x" },
	}
	for name, mutate := range cases {
		c := validConfig()
		mutate(c)
		raw, _ := json.Marshal(c)
		if _, e := ParseConfig(raw); e == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}

// A minimal Xray SOCKS inbound: user/password auth, UDP ASSOCIATE, a relay echo behind it.
func TestDialSocksAuthenticatesAndCarriesFrames(t *testing.T) {
	listener, e := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	defer listener.Close()
	udp, _ := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	defer udp.Close()
	credentials := make(chan string, 1)
	go func() {
		socket, e := listener.Accept()
		if e != nil {
			return
		}
		defer socket.Close()
		buffer := make([]byte, 600)
		_, _ = io.ReadFull(socket, buffer[:3])
		_, _ = socket.Write([]byte{5, 2})
		_, _ = io.ReadFull(socket, buffer[:2])
		user := make([]byte, buffer[1])
		_, _ = io.ReadFull(socket, user)
		_, _ = io.ReadFull(socket, buffer[:1])
		password := make([]byte, buffer[0])
		_, _ = io.ReadFull(socket, password)
		credentials <- string(user) + ":" + string(password)
		_, _ = socket.Write([]byte{1, 0})
		_, _ = io.ReadFull(socket, buffer[:10])
		reply := []byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0}
		binary.BigEndian.PutUint16(reply[8:], uint16(udp.LocalAddr().(*net.UDPAddr).Port))
		_, _ = socket.Write(reply)
		go func() {
			datagram := make([]byte, 2048)
			for {
				size, peer, e := udp.ReadFromUDP(datagram)
				if e != nil {
					return
				}
				// Echo with the relay as source, as Xray reports it.
				_, _ = udp.WriteToUDP(append([]byte(nil), datagram[:size]...), peer)
			}
		}()
		_, _ = io.Copy(io.Discard, socket)
	}()
	got := make(chan []byte, 1)
	path, e := DialSocks(listener.Addr().(*net.TCPAddr).Port, "secret-password", func(data []byte) { got <- data })
	if e != nil {
		t.Fatal(e)
	}
	defer path.Close()
	if value := <-credentials; value != SocksUser+":secret-password" {
		t.Fatalf("credentials %q", value)
	}
	if e := path.Send([]byte("LGM3-frame")); e != nil {
		t.Fatal(e)
	}
	select {
	case data := <-got:
		if !bytes.Equal(data, []byte("LGM3-frame")) {
			t.Fatalf("got %q", data)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("no datagram")
	}
	path.Close()
	if !path.Closed() {
		t.Fatal("not closed")
	}
}
