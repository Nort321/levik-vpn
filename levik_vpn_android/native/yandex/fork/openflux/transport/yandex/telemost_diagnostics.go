package yandex

import (
	"fmt"
	"github.com/pion/logging"
	"strings"
	"sync/atomic"
)

// Managed RTC diagnostics retain fixed counters only, never provider log text.
type rtcDiagnostics struct{ unknownSSRC, replay, decrypt, other, partialDescription, nullDisconnect, videoReaders, videoReadErrors, videoShortReads atomic.Uint64 }
type rtcLogger struct{ d *rtcDiagnostics }

func (d *rtcDiagnostics) NewLogger(string) logging.LeveledLogger { return rtcLogger{d} }
func (l rtcLogger) record(format string, args ...interface{}) {
	s := strings.ToLower(fmt.Sprintf(format, args...))
	switch {
	case strings.Contains(s, "unhandled rtp") || strings.Contains(s, "explicit ssrc"):
		l.d.unknownSSRC.Add(1)
	case strings.Contains(s, "replay") || strings.Contains(s, "duplicated packet"):
		l.d.replay.Add(1)
	case strings.Contains(s, "decrypt") || strings.Contains(s, "auth tag"):
		l.d.decrypt.Add(1)
	default:
		l.d.other.Add(1)
	}
}
func (l rtcLogger) Trace(s string)                       {}
func (l rtcLogger) Tracef(s string, args ...interface{}) {}
func (l rtcLogger) Debug(s string)                       {}
func (l rtcLogger) Debugf(s string, args ...interface{}) {}
func (l rtcLogger) Info(s string)                        { l.record("%s", s) }
func (l rtcLogger) Infof(s string, args ...interface{})  { l.record(s, args...) }
func (l rtcLogger) Warn(s string)                        { l.record("%s", s) }
func (l rtcLogger) Warnf(s string, args ...interface{})  { l.record(s, args...) }
func (l rtcLogger) Error(s string)                       { l.record("%s", s) }
func (l rtcLogger) Errorf(s string, args ...interface{}) { l.record(s, args...) }
func (t *RestrictedTelemost) RTCFailures() map[string]uint64 {
	d := &t.group.network.rtc
	out := map[string]uint64{"unknownSSRC": d.unknownSSRC.Load(), "replay": d.replay.Load(), "decrypt": d.decrypt.Load(), "other": d.other.Load(), "partialDescription": d.partialDescription.Load(), "nullDisconnect": d.nullDisconnect.Load(), "videoReaders": d.videoReaders.Load(), "videoReadErrors": d.videoReadErrors.Load(), "videoShortReads": d.videoShortReads.Load()}
	return out
}
