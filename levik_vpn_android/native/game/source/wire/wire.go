// Package wire is the Levik Game frame format, the same bytes as the production
// relay (levik_game_node internal/relay/wire.go) and the Windows client
// (levik_vpn_windows src/main/vpn/multipath/wire.ts); the golden vectors in
// wire_test.go are shared with both.
package wire

import (
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"net/netip"
	"regexp"
)

const (
	DefaultPort     = 37079
	HeaderV1        = 56
	HeaderV2        = HeaderV1 + 82
	HeaderV3        = 38
	MaxControlBytes = 8192
	MaxPaths        = 8
	MaxPayload      = 60_000
	flagHop         = 1
)

// Kind is the frame type; values are shared with the clients.
type Kind uint8

const (
	KindPing         Kind = 1
	KindPong         Kind = 2
	KindUp           Kind = 3
	KindDown         Kind = 4
	KindNodeInfo     Kind = 5
	KindProbeRequest Kind = 6
	KindProbeResult  Kind = 7
	KindMeshRow      Kind = 8
	KindParity       Kind = 9
	KindRegions      Kind = 10
	// KindAuth carries the user's signed Game token to the exit; KindAuthResult answers it.
	KindAuth       Kind = 11
	KindAuthResult Kind = 12
)

// Ping payload flags.
const (
	PingActive = 1
	PingSpread = 2
	PingFEC    = 4
)

func isControl(kind Kind) bool {
	return kind >= KindNodeInfo && kind <= KindMeshRow || kind >= KindRegions && kind <= KindAuthResult
}
func isData(kind Kind) bool { return kind >= KindPing && kind <= KindDown || kind == KindParity }

// Packet is one decoded frame. Payload aliases the received buffer.
type Packet struct {
	Version    int // 1, 2 or 3
	Kind       Kind
	Path       uint8
	Session    [32]byte
	Flow       [8]byte
	Sequence   uint32
	Address    [4]byte
	Port       uint16
	Payload    []byte
	ExitNodeID string
	Hops       uint8
	// Instance is 16 bytes in v2 and a 4-byte prefix in v3; all zero means "any".
	Instance []byte
}

var nodeIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

func ValidNodeID(value string) bool { return nodeIDPattern.MatchString(value) }

// NodeTag is the stable 4-byte v3 exit tag: sha256("levik-game-node:"+nodeId)[0:4].
func NodeTag(nodeID string) [4]byte {
	sum := sha256.Sum256([]byte("levik-game-node:" + nodeID))
	return [4]byte{sum[0], sum[1], sum[2], sum[3]}
}

// TagResolver maps v3 exit tags back to known node ids. Colliding tags are a
// configuration error: a frame must never reach the wrong exit.
type TagResolver map[[4]byte]string

func NewTagResolver(nodeIDs []string) (TagResolver, error) {
	resolver := TagResolver{}
	for _, nodeID := range nodeIDs {
		if !ValidNodeID(nodeID) {
			return nil, errors.New("invalid node id")
		}
		tag := NodeTag(nodeID)
		if previous, ok := resolver[tag]; ok && previous != nodeID {
			return nil, errors.New("game node tag collision")
		}
		resolver[tag] = nodeID
	}
	return resolver, nil
}

func zeroTail(value []byte) bool {
	for _, b := range value {
		if b != 0 {
			return false
		}
	}
	return true
}

// Compactable reports whether a session/flow pair fits the v3 header.
func Compactable(session [32]byte, flow [8]byte) bool {
	return zeroTail(session[8:]) && zeroTail(flow[4:])
}

var errInvalid = errors.New("invalid game packet")

// Append encodes p after dst (dst may hold a transport prefix) in one allocation.
func Append(dst []byte, p *Packet) ([]byte, error) {
	if len(p.Payload) > MaxPayload || p.Path >= MaxPaths || !(isData(p.Kind) || isControl(p.Kind)) ||
		(p.Kind > KindDown && p.Version < 2) || (isControl(p.Kind) && (p.Version != 2 || len(p.Payload) > MaxControlBytes)) ||
		p.Hops > 1 || (p.ExitNodeID != "" && !ValidNodeID(p.ExitNodeID)) {
		return dst, errInvalid
	}
	switch p.Version {
	case 3:
		if !Compactable(p.Session, p.Flow) || (len(p.Instance) != 0 && len(p.Instance) != 4 && len(p.Instance) != 16) {
			return dst, errInvalid
		}
		out := grow(dst, HeaderV3+len(p.Payload))
		header := out[len(dst):]
		copy(header, "LGM3")
		header[4], header[5], header[6], header[7] = byte(p.Kind), p.Path, 0, 0
		if p.Hops == 1 {
			header[6] = flagHop
		}
		copy(header[8:16], p.Session[:8])
		copy(header[16:20], p.Flow[:4])
		binary.BigEndian.PutUint32(header[20:24], p.Sequence)
		clear(header[24:32])
		if p.ExitNodeID != "" {
			tag := NodeTag(p.ExitNodeID)
			copy(header[24:28], tag[:])
		}
		if len(p.Instance) > 0 {
			copy(header[28:32], p.Instance[:4])
		}
		copy(header[32:36], p.Address[:])
		binary.BigEndian.PutUint16(header[36:38], p.Port)
		copy(header[HeaderV3:], p.Payload)
		return out, nil
	case 2:
		if len(p.Instance) != 0 && len(p.Instance) != 16 {
			return dst, errInvalid
		}
		out := grow(dst, HeaderV2+len(p.Payload))
		header := out[len(dst):]
		clear(header[:HeaderV2])
		writeV1(header, p, "LGM2")
		header[56] = byte(len(p.ExitNodeID))
		copy(header[57:], p.ExitNodeID)
		header[121] = p.Hops
		copy(header[122:138], p.Instance)
		copy(header[HeaderV2:], p.Payload)
		return out, nil
	case 1, 0:
		out := grow(dst, HeaderV1+len(p.Payload))
		header := out[len(dst):]
		writeV1(header, p, "LGM1")
		copy(header[HeaderV1:], p.Payload)
		return out, nil
	}
	return dst, errInvalid
}

func writeV1(header []byte, p *Packet, magic string) {
	copy(header, magic)
	header[4], header[5] = byte(p.Kind), p.Path
	copy(header[6:38], p.Session[:])
	copy(header[38:46], p.Flow[:])
	binary.BigEndian.PutUint32(header[46:50], p.Sequence)
	copy(header[50:54], p.Address[:])
	binary.BigEndian.PutUint16(header[54:56], p.Port)
}

func grow(dst []byte, n int) []byte {
	if cap(dst)-len(dst) >= n {
		return dst[:len(dst)+n]
	}
	out := make([]byte, len(dst)+n, len(dst)+n)
	copy(out, dst)
	return out
}

// Decode parses one frame. A v3 frame with an unknown nonzero exit tag is rejected.
func Decode(data []byte, resolve TagResolver) (Packet, bool) {
	var p Packet
	// Every version's header is at least HeaderV3 bytes; shorter input never reaches data[4].
	if len(data) < HeaderV3 {
		return p, false
	}
	switch string(data[:4]) {
	case "LGM3":
		kind := Kind(data[4])
		if len(data) < HeaderV3 || len(data) > HeaderV3+MaxPayload || !isData(kind) || data[5] >= MaxPaths ||
			data[6]&^flagHop != 0 || data[7] != 0 {
			return p, false
		}
		var tag [4]byte
		copy(tag[:], data[24:28])
		if tag != ([4]byte{}) {
			nodeID, ok := resolve[tag]
			if !ok {
				return p, false
			}
			p.ExitNodeID = nodeID
		}
		p.Version, p.Kind, p.Path, p.Hops = 3, kind, data[5], data[6]&flagHop
		copy(p.Session[:8], data[8:16])
		copy(p.Flow[:4], data[16:20])
		p.Sequence = binary.BigEndian.Uint32(data[20:24])
		p.Instance = data[28:32]
		copy(p.Address[:], data[32:36])
		p.Port = binary.BigEndian.Uint16(data[36:38])
		p.Payload = data[HeaderV3:]
		return p, true
	case "LGM2":
		kind := Kind(data[4])
		if len(data) < HeaderV2 || len(data) > HeaderV2+MaxPayload || !(isData(kind) || isControl(kind)) || data[5] >= MaxPaths ||
			(isControl(kind) && len(data) > HeaderV2+MaxControlBytes) {
			return p, false
		}
		length := int(data[56])
		if length > 64 || data[121] > 1 || !zeroTail(data[57+length:121]) {
			return p, false
		}
		nodeID := string(data[57 : 57+length])
		if nodeID != "" && !ValidNodeID(nodeID) {
			return p, false
		}
		readV1(&p, data)
		p.Version, p.ExitNodeID, p.Hops, p.Instance, p.Payload = 2, nodeID, data[121], data[122:138], data[HeaderV2:]
		return p, true
	case "LGM1":
		kind := Kind(data[4])
		if len(data) < HeaderV1 || len(data) > HeaderV1+MaxPayload || kind < KindPing || kind > KindDown || data[5] >= MaxPaths {
			return p, false
		}
		readV1(&p, data)
		p.Version, p.Payload = 1, data[HeaderV1:]
		return p, true
	}
	return p, false
}

func readV1(p *Packet, data []byte) {
	p.Kind, p.Path = Kind(data[4]), data[5]
	copy(p.Session[:], data[6:38])
	copy(p.Flow[:], data[38:46])
	p.Sequence = binary.BigEndian.Uint32(data[46:50])
	copy(p.Address[:], data[50:54])
	p.Port = binary.BigEndian.Uint16(data[54:56])
}

// InstanceMatches: all zero means "any"; a 4-byte v3 tag matches the prefix.
func InstanceMatches(received []byte, instance [16]byte) bool {
	if zeroTail(received) {
		return true
	}
	if len(received) > len(instance) {
		return false
	}
	for i, b := range received {
		if instance[i] != b {
			return false
		}
	}
	return true
}

// PublicIPv4 mirrors wire.ts publicIPv4: only public unicast game destinations.
func PublicIPv4(address [4]byte) bool {
	a, b, c := address[0], address[1], address[2]
	return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
		(a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254) ||
		(a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) ||
		(a == 192 && b == 0) || (a == 192 && b == 88 && c == 99) ||
		(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100))) ||
		(a == 203 && b == 0 && c == 113))
}

func addrOf(address [4]byte, port uint16) netip.AddrPort {
	return netip.AddrPortFrom(netip.AddrFrom4(address), port)
}

// SequenceWindow is the bounded replay window: first arrival wins, nothing waits.
type SequenceWindow struct {
	high  int64
	slots [512]uint64
}

func NewSequenceWindow() *SequenceWindow { return &SequenceWindow{high: -1} }

func (w *SequenceWindow) Accept(sequence uint32) bool {
	s := int64(sequence)
	if s <= w.high-512 {
		return false
	}
	index := sequence % 512
	if w.slots[index] == uint64(sequence)+1 {
		return false
	}
	w.slots[index] = uint64(sequence) + 1
	if s > w.high {
		w.high = s
	}
	return true
}
