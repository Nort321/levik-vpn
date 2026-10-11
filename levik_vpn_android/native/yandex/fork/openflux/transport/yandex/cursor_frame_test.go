package yandex

import (
	"bytes"
	"encoding/base64"
	"strconv"
	"strings"
	"testing"

	"github.com/p1neappleXpress/OpenFlux/transport"
)

var _ transport.PacedTransport = (*YandexDocsTransport)(nil)

// A full batch plus encryption (header, nonce, tag) and batch framing must stay
// under Yandex's ~4096-character cursor string, or the provider drops it.
func TestCursorBatchFitsTheProviderCursorLimit(t *testing.T) {
	const encryptionAndFraming = 64
	frame := cursorFrame(make([]byte, cursorBatchBytes+encryptionAndFraming))
	cursor := ExtractBase64(string(frame))
	declared := strconv.Itoa(cursorBatchBytes+encryptionAndFraming) + ";"
	if n := len(declared) + len(cursor); n > 4096 {
		t.Fatalf("cursor string is %d characters, above the 4096 the provider relays", n)
	}
}

func TestCursorFrameDeclaresTheRealPacketLength(t *testing.T) {
	for _, size := range []int{1, 16, 18, 69, 102, 512, 1500} {
		packet := bytes.Repeat([]byte{0xa5}, size)
		frame := string(cursorFrame(packet))
		prefix := `42["message",{"type":"cursor","cursor":"` + strconv.Itoa(size) + ";"
		if !strings.HasPrefix(frame, prefix) || !strings.HasSuffix(frame, `"}]`) {
			t.Fatalf("size %d: unexpected frame %q", size, frame[:min(len(frame), 80)])
		}
		// The receiving side must recover exactly the sent packet.
		decoded, err := base64.StdEncoding.DecodeString(ExtractBase64(frame))
		if err != nil || !bytes.Equal(decoded, packet) {
			t.Fatalf("size %d: payload does not round-trip", size)
		}
	}
}
