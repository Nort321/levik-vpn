package wire

import (
	"bytes"
	"encoding/hex"
	"testing"
)

// Shared with levik_vpn_windows/tests/multipath/wire.test.ts.
const goldenV3 = "4c474d33030201000102030405060708a1b2c3d401020304775375addeadbeefcb007209698767616d65"

func goldenPacket() Packet {
	p := Packet{Version: 3, Kind: KindUp, Path: 2, Sequence: 0x01020304, Address: [4]byte{203, 0, 114, 9}, Port: 27015,
		Payload: []byte("game"), ExitNodeID: "node-a", Hops: 1, Instance: []byte{0xde, 0xad, 0xbe, 0xef}}
	copy(p.Session[:], []byte{1, 2, 3, 4, 5, 6, 7, 8})
	copy(p.Flow[:], []byte{0xa1, 0xb2, 0xc3, 0xd4})
	return p
}

func TestGoldenVersion3(t *testing.T) {
	p := goldenPacket()
	out, e := Append(nil, &p)
	if e != nil || hex.EncodeToString(out) != goldenV3 {
		t.Fatalf("encode mismatch: %x %v", out, e)
	}
	resolver, _ := NewTagResolver([]string{"node-a", "node-b"})
	decoded, ok := Decode(out, resolver)
	if !ok || decoded.ExitNodeID != "node-a" || decoded.Sequence != p.Sequence || decoded.Port != 27015 || decoded.Hops != 1 ||
		!bytes.Equal(decoded.Payload, []byte("game")) || !bytes.Equal(decoded.Instance, p.Instance) || decoded.Session != p.Session || decoded.Flow != p.Flow {
		t.Fatalf("decode mismatch: %+v", decoded)
	}
	if _, ok := Decode(out, TagResolver{}); ok {
		t.Fatal("unknown exit tag must be rejected")
	}
	for _, index := range []int{6, 7} {
		broken := append([]byte(nil), out...)
		broken[index] = 3
		if _, ok := Decode(broken, resolver); ok {
			t.Fatalf("reserved byte %d accepted", index)
		}
	}
	control := p
	control.Kind = KindNodeInfo
	if _, e := Append(nil, &control); e == nil {
		t.Fatal("control frames must stay on version 2")
	}
	long := p
	long.Session[20] = 1
	if _, e := Append(nil, &long); e == nil {
		t.Fatal("long session ids cannot be compacted")
	}
}

func TestVersion2And1RoundTrip(t *testing.T) {
	p := Packet{Version: 2, Kind: KindUp, Path: 7, Address: [4]byte{1, 1, 1, 1}, Port: 7000, Payload: []byte("game"), ExitNodeID: "node-a", Hops: 1, Instance: bytes.Repeat([]byte{1}, 16)}
	for i := range p.Session {
		p.Session[i] = byte(i)
	}
	out, _ := Append([]byte("prefix"), &p)
	if string(out[:6]) != "prefix" || len(out) != 6+HeaderV2+4 {
		t.Fatal("prefix not preserved")
	}
	decoded, ok := Decode(out[6:], nil)
	if !ok || decoded.Session != p.Session || decoded.ExitNodeID != "node-a" || decoded.Hops != 1 || !bytes.Equal(decoded.Instance, p.Instance) {
		t.Fatalf("v2 mismatch %+v", decoded)
	}
	out[6+121] = 2
	if _, ok := Decode(out[6:], nil); ok {
		t.Fatal("hops > 1 accepted")
	}
	legacy := Packet{Version: 1, Kind: KindDown, Path: 1, Address: [4]byte{8, 8, 8, 8}, Port: 53, Payload: []byte{0, 255}}
	encoded, _ := Append(nil, &legacy)
	if len(encoded) != HeaderV1+2 || string(encoded[:4]) != "LGM1" {
		t.Fatal("v1 layout")
	}
	if decoded, ok := Decode(encoded, nil); !ok || decoded.Version != 1 || decoded.Port != 53 {
		t.Fatal("v1 decode")
	}
	if _, ok := Decode(encoded[:55], nil); ok {
		t.Fatal("truncated accepted")
	}
}

func TestInstanceAndWindowAndAddresses(t *testing.T) {
	var instance [16]byte
	copy(instance[:], []byte{0, 0x11, 0x22, 0x33, 0x44})
	if !InstanceMatches([]byte{0, 0x11, 0x22, 0x33}, instance) || !InstanceMatches(make([]byte, 4), instance) || InstanceMatches([]byte{0, 0x11, 0x22, 0x34}, instance) {
		t.Fatal("instance prefix")
	}
	w := NewSequenceWindow()
	for _, step := range []struct {
		sequence uint32
		accept   bool
	}{{3, true}, {1, true}, {3, false}, {1024, true}, {2, false}, {1023, true}} {
		if w.Accept(step.sequence) != step.accept {
			t.Fatalf("window %d", step.sequence)
		}
	}
	for _, address := range [][4]byte{{127, 0, 0, 1}, {169, 254, 169, 254}, {10, 0, 0, 1}, {192, 168, 1, 1}, {172, 16, 0, 1}, {100, 64, 0, 1}, {224, 0, 0, 1}, {198, 18, 0, 1}, {192, 0, 2, 1}, {203, 0, 113, 1}} {
		if PublicIPv4(address) {
			t.Fatalf("%v accepted", address)
		}
	}
	if !PublicIPv4([4]byte{1, 1, 1, 1}) {
		t.Fatal("public rejected")
	}
}

func TestFECRecoversAnySingleLoss(t *testing.T) {
	for lost := 0; lost < 4; lost++ {
		encoder, decoder := NewFECEncoder(), NewFECDecoder()
		packets := []FECPacket{{8, [4]byte{1, 2, 3, 4}, 7008, []byte("a")}, {9, [4]byte{5, 6, 7, 8}, 7009, []byte("longer payload")},
			{10, [4]byte{9, 9, 9, 9}, 7010, []byte{}}, {11, [4]byte{1, 1, 1, 1}, 7011, []byte("xyz")}}
		var parity []byte
		var paritySequence uint32
		var recovered *FECPacket
		for i, p := range packets {
			if sequence, out := encoder.Add(p); out != nil {
				paritySequence, parity = sequence, out
			}
			if i != lost {
				if got := decoder.Data(p); got != nil {
					recovered = got
				}
			}
		}
		if paritySequence != 8 || parity == nil {
			t.Fatal("no parity")
		}
		if got := decoder.Parity(paritySequence, parity); got != nil {
			recovered = got
		}
		want := packets[lost]
		if recovered == nil || recovered.Sequence != want.Sequence || recovered.Address != want.Address || recovered.Port != want.Port || !bytes.Equal(recovered.Payload, want.Payload) {
			t.Fatalf("lost %d: %+v", lost, recovered)
		}
	}
	encoder := NewFECEncoder()
	encoder.Add(FECPacket{0, [4]byte{1, 1, 1, 1}, 1, []byte("only")})
	if _, out := encoder.Flush(); out != nil {
		t.Fatal("single packet parity")
	}
	decoder := NewFECDecoder()
	for _, bad := range [][]byte{make([]byte, 4), {0, 0, 0, 0, 0, 0, 0, 0, 0}, {16, 0, 0, 0, 0, 0, 0, 0, 0}} {
		if decoder.Parity(0, bad) != nil {
			t.Fatal("malformed parity")
		}
	}
	if decoder.Parity(1, make([]byte, 12)) != nil {
		t.Fatal("unaligned parity")
	}
}

// Parity produced by fec.ts for the same group (cross-implementation check).
func TestFECMatchesReference(t *testing.T) {
	encoder := NewFECEncoder()
	encoder.Add(FECPacket{4, [4]byte{1, 1, 1, 1}, 7000, []byte("one")})
	encoder.Add(FECPacket{5, [4]byte{8, 8, 8, 8}, 7001, []byte("three")})
	sequence, parity := encoder.Flush()
	if sequence != 4 || hex.EncodeToString(parity) != fecReference {
		t.Fatalf("parity %d %x", sequence, parity)
	}
}

const fecReference = "0300060909090900011b06176565"
