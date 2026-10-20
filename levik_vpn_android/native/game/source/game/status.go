package game

import "math"

type PathStatus struct {
	EntryNodeID string   `json:"entryNodeId"`
	ExitNodeID  string   `json:"exitNodeId"`
	LatencyMs   *float64 `json:"latencyMs"`
	Responding  bool     `json:"responding"`
	Active      bool     `json:"active"`
}

// Status is one line for the app: no addresses, only node ids and counters.
type Status struct {
	// State: protected (two copies), single, unavailable.
	State      string       `json:"state"`
	ExitNodeID string       `json:"exitNodeId,omitempty"`
	ExitName   string       `json:"exitName,omitempty"`
	LatencyMs  *float64     `json:"latencyMs"`
	Playing    bool         `json:"playing"`
	Flows      int          `json:"flows"`
	Auth       string       `json:"auth,omitempty"`
	Paths      []PathStatus `json:"paths"`
	Received   uint64       `json:"receivedPackets"`
	Duplicates uint64       `json:"duplicateReplies"`
	Uploaded   uint64       `json:"uploadedPackets"`
	Dropped    uint64       `json:"droppedPackets"`
}

func latency(value float64) *float64 {
	if value <= 0 || math.IsInf(value, 0) {
		return nil
	}
	rounded := math.Round(value*10) / 10
	return &rounded
}

func (c *Client) Status() Status {
	c.mu.Lock()
	defer c.mu.Unlock()
	exitID := c.current
	if exitID == "" {
		exitID = c.chooseExit()
	}
	s := Status{State: "unavailable", Playing: c.playing(), Flows: c.flowCount, Received: c.received, Duplicates: c.duplicates,
		Uploaded: c.uploaded, Dropped: c.dropped, Paths: []PathStatus{}}
	copies := 0
	for _, p := range c.paths {
		responding := c.fresh(p)
		s.Paths = append(s.Paths, PathStatus{EntryNodeID: p.entry, ExitNodeID: p.exit, LatencyMs: latency(p.rttMs), Responding: responding, Active: p.active})
		if p.exit == exitID && responding && p.active {
			copies++
		}
	}
	if exitID != "" {
		s.ExitNodeID, s.ExitName, s.LatencyMs = exitID, c.cfg.name(exitID), latency(c.exitRTT(exitID))
		if e := c.exits[exitID]; e != nil {
			s.Auth = e.auth
		}
	}
	switch {
	case copies >= 2:
		s.State = "protected"
	case copies == 1 || exitID != "" && !math.IsInf(c.exitRTT(exitID), 1):
		s.State = "single"
	}
	return s
}
