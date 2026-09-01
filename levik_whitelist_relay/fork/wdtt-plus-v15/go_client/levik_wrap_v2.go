// SPDX-License-Identifier: GPL-3.0-only
// Levik WRAP v2: authenticated RTP extension with O(1) credential selection.

package main

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"sync"

	"golang.org/x/crypto/chacha20poly1305"
)

const (
	levikWrapIDLen       = 16
	levikWrapV2HeaderLen = 12 + 4 + levikWrapIDLen
	levikWrapV2Profile   = 0xBEDE
	levikReplaySize      = 4096
	levikReplayWords     = levikReplaySize / 64
)

func deriveLevikWrapID(password string) [levikWrapIDLen]byte {
	sum := sha256.Sum256([]byte("LEVIK-WRAP-CREDENTIAL-ID-v2\x00" + password))
	var id [levikWrapIDLen]byte
	copy(id[:], sum[:levikWrapIDLen])
	return id
}

func obfsV2CredentialID(wire []byte) ([levikWrapIDLen]byte, bool) {
	var id [levikWrapIDLen]byte
	if len(wire) <= levikWrapV2HeaderLen+chacha20poly1305.Overhead || wire[0]&0x10 == 0 ||
		binary.BigEndian.Uint16(wire[12:14]) != levikWrapV2Profile || binary.BigEndian.Uint16(wire[14:16]) != levikWrapIDLen/4 {
		return id, false
	}
	copy(id[:], wire[16:16+levikWrapIDLen])
	return id, true
}

func obfsWrapPacketV2(key []byte, credentialID [levikWrapIDLen]byte, payload []byte, cfg *ObfsConfig, state *ObfsState) ([]byte, error) {
	if len(key) != wrapKeyLen || len(payload) == 0 {
		return nil, errors.New("invalid WRAP v2 input")
	}
	state.mu.Lock()
	counter := state.count
	state.count++
	state.mu.Unlock()
	seq := state.initSeq + uint16(counter)
	timestamp := state.initTs + uint32(counter)*960 + uint32(counter>>16)
	nonce := obfsBuildNonce(cfg.SSRC, seq, timestamp)
	paddingRandom := 0
	if cfg.PaddingMax > 0 {
		var value [1]byte
		_, _ = rand.Read(value[:])
		paddingRandom = int(value[0]) % cfg.PaddingMax
	}
	paddingTotal := paddingRandom + 1
	output := make([]byte, levikWrapV2HeaderLen+len(payload)+chacha20poly1305.Overhead+paddingTotal)
	output[0] = 0x80 | 0x20 | 0x10 // RTP V=2, padding, extension.
	output[1] = cfg.PayloadType & 0x7f
	binary.BigEndian.PutUint16(output[2:4], seq)
	binary.BigEndian.PutUint32(output[4:8], timestamp)
	binary.BigEndian.PutUint32(output[8:12], cfg.SSRC)
	binary.BigEndian.PutUint16(output[12:14], levikWrapV2Profile)
	binary.BigEndian.PutUint16(output[14:16], levikWrapIDLen/4)
	copy(output[16:levikWrapV2HeaderLen], credentialID[:])
	aead, err := getAEAD(key)
	if err != nil {
		return nil, err
	}
	sealed := aead.Seal(output[levikWrapV2HeaderLen:levikWrapV2HeaderLen], nonce, payload, output[:levikWrapV2HeaderLen])
	paddingStart := levikWrapV2HeaderLen + len(sealed)
	if paddingRandom > 0 {
		_, _ = rand.Read(output[paddingStart : paddingStart+paddingRandom])
	}
	output[len(output)-1] = byte(paddingTotal)
	return output, nil
}

func obfsUnwrapPacketV2(key []byte, expectedID [levikWrapIDLen]byte, wire, dst []byte) (int, error) {
	id, ok := obfsV2CredentialID(wire)
	if !ok || id != expectedID || len(key) != wrapKeyLen {
		return 0, errors.New("invalid WRAP v2 header")
	}
	payloadEnd := len(wire)
	if wire[0]&0x20 != 0 {
		padding := int(wire[len(wire)-1])
		if padding == 0 || padding > payloadEnd-levikWrapV2HeaderLen {
			return 0, errors.New("invalid WRAP v2 padding")
		}
		payloadEnd -= padding
	}
	if payloadEnd-levikWrapV2HeaderLen <= chacha20poly1305.Overhead || payloadEnd-levikWrapV2HeaderLen-chacha20poly1305.Overhead > len(dst) {
		return 0, errors.New("invalid WRAP v2 payload")
	}
	nonce := obfsBuildNonce(binary.BigEndian.Uint32(wire[8:12]), binary.BigEndian.Uint16(wire[2:4]), binary.BigEndian.Uint32(wire[4:8]))
	aead, err := getAEAD(key)
	if err != nil {
		return 0, err
	}
	plain, err := aead.Open(dst[:0], nonce, wire[levikWrapV2HeaderLen:payloadEnd], wire[:levikWrapV2HeaderLen])
	if err != nil {
		return 0, errors.New("WRAP v2 authentication failed")
	}
	return len(plain), nil
}

type levikReplayWindow struct {
	mu           sync.Mutex
	initialized  bool
	maxSequence  uint64
	maxTimestamp uint32
	bits         [levikReplayWords]uint64
}

func (w *levikReplayWindow) Accept(wire []byte) bool {
	if len(wire) < 12 {
		return false
	}
	sequence := uint64(binary.BigEndian.Uint16(wire[2:4]))
	timestamp := binary.BigEndian.Uint32(wire[4:8])
	w.mu.Lock()
	defer w.mu.Unlock()
	if !w.initialized {
		w.initialized = true
		w.maxSequence = sequence
		w.maxTimestamp = timestamp
		w.bits[0] = 1
		return true
	}
	base := w.maxSequence &^ 0xffff
	candidate := base | sequence
	if candidate+32768 < w.maxSequence {
		candidate += 65536
	} else if candidate > w.maxSequence+32768 && candidate >= 65536 {
		candidate -= 65536
	}
	delta := int64(candidate) - int64(w.maxSequence)
	expectedTimestamp := uint32(int64(w.maxTimestamp) + delta*960)
	timestampError := int64(int32(timestamp - expectedTimestamp))
	if timestampError < -2 || timestampError > 2 {
		return false
	}
	if delta > 0 {
		w.shift(uint64(delta))
		w.maxSequence = candidate
		w.maxTimestamp = timestamp
		w.bits[0] |= 1
		return true
	}
	distance := uint64(-delta)
	if distance >= levikReplaySize {
		return false
	}
	word, bit := distance/64, distance%64
	mask := uint64(1) << bit
	if w.bits[word]&mask != 0 {
		return false
	}
	w.bits[word] |= mask
	return true
}

func (w *levikReplayWindow) shift(distance uint64) {
	if distance >= levikReplaySize {
		w.bits = [levikReplayWords]uint64{}
		return
	}
	wordShift, bitShift := int(distance/64), uint(distance%64)
	var shifted [levikReplayWords]uint64
	for destination := levikReplayWords - 1; destination >= 0; destination-- {
		source := destination - wordShift
		if source < 0 {
			continue
		}
		shifted[destination] |= w.bits[source] << bitShift
		if bitShift != 0 && source > 0 {
			shifted[destination] |= w.bits[source-1] >> (64 - bitShift)
		}
	}
	w.bits = shifted
}
