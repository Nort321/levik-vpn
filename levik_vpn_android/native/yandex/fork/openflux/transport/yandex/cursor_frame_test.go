package yandex

import (
	"bytes"
	"encoding/base64"
	"strconv"
	"strings"
	"testing"
)

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
