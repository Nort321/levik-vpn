package wire

import "encoding/binary"

// XOR parity over groups of four consecutive sequences of one flow (fec.ts).
// Parity payload: [mask u8][xor length u16][xor address 4][xor port u16][xor data];
// its header sequence is the group base.
const (
	FECGroup     = 4
	parityHeader = 9
	keepGroups   = 32
)

type FECPacket struct {
	Sequence uint32
	Address  [4]byte
	Port     uint16
	Payload  []byte
}

func groupBase(sequence uint32) uint32 { return sequence - sequence%FECGroup }

type FECEncoder struct {
	group   int64
	mask    uint8
	length  uint16
	address [4]byte
	port    uint16
	data    []byte
	size    int
}

func NewFECEncoder() *FECEncoder { return &FECEncoder{group: -1} }

// Add returns parity to send after this packet, or nil while the group is open.
func (e *FECEncoder) Add(p FECPacket) (uint32, []byte) {
	base := groupBase(p.Sequence)
	var previousSequence uint32
	var previous []byte
	if e.group != int64(base) {
		previousSequence, previous = e.Flush()
		e.group = int64(base)
	}
	if len(p.Payload) > MaxPayload {
		return previousSequence, previous
	}
	offset := p.Sequence - base
	if e.mask&(1<<offset) != 0 {
		return previousSequence, previous
	}
	e.mask |= 1 << offset
	e.length ^= uint16(len(p.Payload))
	e.port ^= p.Port
	for i := range e.address {
		e.address[i] ^= p.Address[i]
	}
	if len(p.Payload) > len(e.data) {
		grown := make([]byte, max(len(p.Payload), len(e.data)*2, 256))
		copy(grown, e.data)
		e.data = grown
	}
	for i, b := range p.Payload {
		e.data[i] ^= b
	}
	e.size = max(e.size, len(p.Payload))
	if offset == FECGroup-1 {
		if previous != nil {
			return previousSequence, previous
		}
		return e.Flush()
	}
	return previousSequence, previous
}

// Flush ends an open group early. A single packet needs no parity.
func (e *FECEncoder) Flush() (uint32, []byte) {
	if e.group < 0 || e.mask == 0 {
		return 0, nil
	}
	var out []byte
	if e.mask&(e.mask-1) != 0 {
		out = make([]byte, parityHeader+e.size)
		out[0] = e.mask
		binary.BigEndian.PutUint16(out[1:3], e.length)
		copy(out[3:7], e.address[:])
		binary.BigEndian.PutUint16(out[7:9], e.port)
		copy(out[parityHeader:], e.data[:e.size])
	}
	sequence := uint32(e.group)
	clear(e.data[:e.size])
	e.mask, e.length, e.address, e.port, e.size = 0, 0, [4]byte{}, 0, 0
	return sequence, out
}

func (e *FECEncoder) Open() bool { return e.mask != 0 }

type fecGroup struct {
	mask    uint8
	packets [FECGroup]*FECPacket
	parity  []byte
	done    bool
}

type FECDecoder struct{ groups map[uint32]*fecGroup }

func NewFECDecoder() *FECDecoder { return &FECDecoder{groups: map[uint32]*fecGroup{}} }

// Data records a received packet; it returns a packet recovered with it, if any.
// The payload is copied: callers reuse their read buffers.
func (d *FECDecoder) Data(p FECPacket) *FECPacket {
	base := groupBase(p.Sequence)
	group := d.entry(base)
	if group == nil {
		return nil
	}
	offset := p.Sequence - base
	if group.packets[offset] != nil {
		return nil
	}
	p.Payload = append([]byte(nil), p.Payload...)
	group.packets[offset] = &p
	return d.recover(base, group)
}

func (d *FECDecoder) Parity(sequence uint32, payload []byte) *FECPacket {
	if sequence%FECGroup != 0 || len(payload) < parityHeader || len(payload) > parityHeader+MaxPayload {
		return nil
	}
	mask := payload[0]
	if mask == 0 || mask >= 1<<FECGroup {
		return nil
	}
	group := d.entry(sequence)
	if group == nil || group.parity != nil {
		return nil
	}
	group.parity = append([]byte(nil), payload...)
	group.mask = mask
	return d.recover(sequence, group)
}

func (d *FECDecoder) entry(base uint32) *fecGroup {
	if group := d.groups[base]; group != nil {
		return group
	}
	newest := int64(-1)
	for key := range d.groups {
		newest = max(newest, int64(key))
	}
	window := int64(keepGroups * FECGroup)
	if newest >= 0 && int64(base) < newest-window {
		return nil
	}
	group := &fecGroup{}
	d.groups[base] = group
	limit := max(newest, int64(base)) - window
	for key := range d.groups {
		if int64(key) < limit {
			delete(d.groups, key)
		}
	}
	return group
}

func (d *FECDecoder) recover(base uint32, group *fecGroup) *FECPacket {
	if group.done || group.parity == nil {
		return nil
	}
	missing := -1
	for offset := 0; offset < FECGroup; offset++ {
		if group.mask&(1<<offset) != 0 && group.packets[offset] == nil {
			if missing >= 0 {
				return nil
			}
			missing = offset
		}
	}
	group.done = true
	if missing < 0 {
		return nil
	}
	parity := group.parity
	length := binary.BigEndian.Uint16(parity[1:3])
	var address [4]byte
	copy(address[:], parity[3:7])
	port := binary.BigEndian.Uint16(parity[7:9])
	data := append([]byte(nil), parity[parityHeader:]...)
	for offset := 0; offset < FECGroup; offset++ {
		packet := group.packets[offset]
		if packet == nil || group.mask&(1<<offset) == 0 {
			continue
		}
		length ^= uint16(len(packet.Payload))
		port ^= packet.Port
		for i := range address {
			address[i] ^= packet.Address[i]
		}
		for i := 0; i < len(packet.Payload) && i < len(data); i++ {
			data[i] ^= packet.Payload[i]
		}
	}
	if int(length) > len(data) || port < 1 || address[0] == 0 {
		return nil
	}
	recovered := &FECPacket{Sequence: base + uint32(missing), Address: address, Port: port, Payload: data[:length]}
	group.packets[missing] = recovered
	return recovered
}
