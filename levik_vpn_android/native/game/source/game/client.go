package game

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"math"
	"net"
	"sort"
	"strconv"
	"sync"
	"time"

	"levik.local/levik-game/wire"
)

const (
	pingTimeout = 1500 * time.Millisecond
	// Below the relay's 60 s session idle timeout with room for two lost pings.
	idlePing      = 20 * time.Second
	idleFresh     = 65 * time.Second
	activeSilence = 6 * time.Second
	playingWindow = 5 * time.Second
	fastPing      = 200 * time.Millisecond
	activePing    = time.Second
	// A pinned exit with no reply on any of its paths for this long is gone: the socket re-decides.
	deadExit  = 15 * time.Second
	flowIdle  = 60 * time.Second
	authEvery = 15 * time.Second
	infoEvery = 30 * time.Second
	// The exit of the next match keeps backup entries through other nodes' mesh open this long.
	warmFor   = 2 * time.Minute
	meshPaths = 2
	// A better exit takes over new sockets only when it is clearly better.
	switchMinGainMs = 15.0
	switchRatio     = 0.2
	// Packets per second that make a flow a game connection: it never moves while alive.
	busyPackets = 10
	maxPending  = 64
)

type caps struct {
	LGM3 bool `json:"lgm3"`
	Mesh bool `json:"mesh"`
	EIM  bool `json:"eim"`
	Auth bool `json:"auth"`
}

type exit struct {
	id        string
	session   [32]byte
	caps      caps
	capsKnown bool
	instance  [16]byte
	askedAt   time.Time
	auth      string
	warmAt    time.Time
}

type path struct {
	route     RouteConfig
	entry     string
	exit      string
	slot      uint8
	transport Transport
	epoch     int
	opening   bool
	nextOpen  time.Time
	backoff   time.Duration
	removed   bool

	instance      [16]byte
	hasInstance   bool
	rttMs         float64
	lastReply     time.Time
	awaitingSince time.Time
	lastPing      time.Time
	sent          map[uint32]time.Time
	misses        int
	active        bool
	authKey       string
	firstReplies  uint64
}

// wireFlow is the relay-side identity of one game socket at one exit: with endpoint-independent
// mapping every destination of the socket shares one external IP:port there.
type wireFlow struct {
	id        [8]byte
	key       string
	sequence  uint32
	seen      *wire.SequenceWindow
	exit      string
	instance  [16]byte
	conn      *conn
	flows     map[endpoint]*flow
	repliedAt time.Time
}

type flow struct {
	target   endpoint
	conn     *conn
	wire     *wireFlow
	sent     uint64
	received uint64
	started  time.Time
	touched  time.Time
	// Once a flow carried match-rate traffic it is a game connection.
	busy       bool
	sentAtTick uint64
}

// Client is the Game data plane. All state is guarded by mu.
type Client struct {
	mu       sync.Mutex
	cfg      Config
	dial     Dialer
	log      func(string)
	now      func() time.Time
	resolve  wire.TagResolver
	exits    map[string]*exit
	paths    []*path
	conns    map[*conn]struct{}
	wires    map[[8]byte]*wireFlow
	listener net.Listener
	stopped  bool
	// validTarget: public unicast only (tests allow loopback servers).
	validTarget func([4]byte) bool
	wake        chan struct{}

	flowCount  int
	pingSeq    uint32
	controlSeq uint32
	lastPacket time.Time
	lastAll    time.Time
	lastAuth   time.Time
	lastRate   time.Time
	current    string
	authNoted  map[string]bool

	received, duplicates, uploaded, dropped uint64
}

// New validates nothing: cfg comes from ParseConfig.
func New(cfg Config, dial Dialer, log func(string)) *Client {
	if dial == nil {
		dial = DialSocks
	}
	if log == nil {
		log = func(string) {}
	}
	ids := []string{}
	for _, route := range cfg.Routes {
		ids = append(ids, route.NodeID)
	}
	for _, node := range cfg.Nodes {
		ids = append(ids, node.NodeID)
	}
	resolve, _ := wire.NewTagResolver(ids)
	c := &Client{cfg: cfg, dial: dial, log: log, now: time.Now, resolve: resolve, exits: map[string]*exit{}, conns: map[*conn]struct{}{},
		wires: map[[8]byte]*wireFlow{}, wake: make(chan struct{}, 1), authNoted: map[string]bool{}, controlSeq: 0x80000000, validTarget: wire.PublicIPv4}
	for _, route := range cfg.Routes {
		if cfg.state(route.NodeID) == "disabled" {
			continue
		}
		c.paths = append(c.paths, &path{route: route, entry: route.NodeID, exit: route.NodeID, sent: map[uint32]time.Time{}})
	}
	for _, p := range c.paths {
		p.slot = c.freeSlot(c.exitOf(p.exit), p)
	}
	return c
}

func (c *Client) exitOf(id string) *exit {
	e := c.exits[id]
	if e == nil {
		e = &exit{id: id}
		_, _ = rand.Read(e.session[:8])
		c.exits[id] = e
	}
	return e
}

func (c *Client) freeSlot(e *exit, self *path) uint8 {
	used := map[uint8]bool{}
	for _, p := range c.paths {
		if p != self && !p.removed && p.exit == e.id {
			used[p.slot] = true
		}
	}
	for slot := uint8(0); slot < wire.MaxPaths; slot++ {
		if !used[slot] {
			return slot
		}
	}
	return wire.MaxPaths
}

// Run drives pings, path repair and housekeeping until Stop.
func (c *Client) Run() {
	for {
		c.mu.Lock()
		if c.stopped {
			c.mu.Unlock()
			return
		}
		c.tick()
		interval := time.Second
		if c.playing() {
			interval = fastPing
		}
		c.mu.Unlock()
		timer := time.NewTimer(interval)
		select {
		case <-timer.C:
		case <-c.wake:
			timer.Stop()
		}
	}
}

func (c *Client) Stop() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.stopped {
		return
	}
	c.stopped = true
	if c.listener != nil {
		_ = c.listener.Close()
	}
	for cn := range c.conns {
		c.dropConnection(cn)
	}
	for _, p := range c.paths {
		if p.transport != nil {
			p.transport.Close()
		}
	}
	c.cfg.Password, c.cfg.RelayToken = "", ""
	select {
	case c.wake <- struct{}{}:
	default:
	}
}

func (c *Client) playing() bool { return c.now().Sub(c.lastPacket) < playingWindow }

func (c *Client) silenceLimit() time.Duration {
	if c.playing() {
		return activeSilence
	}
	return idleFresh
}

func (c *Client) usable(p *path) bool {
	return !p.removed && p.transport != nil && !p.transport.Closed() && p.hasInstance
}

// fresh: a path is usable while it answers; an idle path stays usable until it was asked and kept silent.
func (c *Client) fresh(p *path) bool {
	now := c.now()
	return c.usable(p) && now.Sub(p.lastReply) < idleFresh && (p.awaitingSince.IsZero() || now.Sub(p.awaitingSince) < c.silenceLimit())
}

func (c *Client) tick() {
	now := c.now()
	for _, p := range c.paths {
		if p.removed || p.opening {
			continue
		}
		broken := p.transport == nil || p.transport.Closed()
		silent := !p.awaitingSince.IsZero() && now.Sub(p.awaitingSince) >= c.silenceLimit()
		if (broken || silent) && !now.Before(p.nextOpen) {
			c.open(p)
		}
	}
	for cn := range c.conns {
		for _, f := range cn.flows {
			if now.Sub(f.touched) > flowIdle {
				c.dropFlow(f)
				continue
			}
		}
	}
	if elapsed := now.Sub(c.lastRate); elapsed >= time.Second {
		c.lastRate = now
		for cn := range c.conns {
			for _, f := range cn.flows {
				if float64(f.sent-f.sentAtTick) >= busyPackets*elapsed.Seconds() && elapsed < 3*time.Second {
					f.busy = true
				}
				f.sentAtTick = f.sent
			}
		}
	}
	// A pinned exit with no reply on any path for long is a lost match: the socket re-decides
	// instead of dropping the game's packets forever.
	for _, w := range c.wires {
		if !w.repliedAt.IsZero() && now.Sub(w.repliedAt) < deadExit || now.Sub(c.flowsStarted(w)) < deadExit {
			continue
		}
		if !c.exitAlive(w.exit, w.instance) {
			c.log("Game: выход " + c.cfg.name(w.exit) + " перестал отвечать; сокет игры выбирает выход заново")
			c.dropWire(w)
		}
	}
	c.warmMesh(now)
	c.selectActive()
	playing := c.playing()
	all := now.Sub(c.lastAll) >= idlePing || (playing && now.Sub(c.lastAll) >= activePing)
	if all {
		c.lastAll = now
	}
	for _, p := range c.paths {
		if p.removed || p.transport == nil || p.transport.Closed() {
			continue
		}
		// A path that never answered is asked every second until it does.
		if all || (playing && p.active) || (!p.hasInstance && now.Sub(p.lastPing) >= activePing) {
			c.ping(p)
		}
	}
	if c.cfg.RelayToken != "" && now.Sub(c.lastAuth) >= authEvery {
		c.lastAuth = now
		for _, p := range c.paths {
			if c.usable(p) {
				c.authenticate(p)
			}
		}
	}
	for _, e := range c.exits {
		if !e.capsKnown && now.Sub(e.askedAt) >= 2*time.Second || e.capsKnown && now.Sub(e.askedAt) >= infoEvery {
			for _, p := range c.paths {
				if c.fresh(p) && p.entry == p.exit && p.exit == e.id {
					c.control(p, wire.KindNodeInfo, map[string]any{})
					e.askedAt = now
					break
				}
			}
		}
	}
}

func (c *Client) flowsStarted(w *wireFlow) time.Time {
	var latest time.Time
	for _, f := range w.flows {
		if f.started.After(latest) {
			latest = f.started
		}
	}
	return latest
}

func (c *Client) exitAlive(id string, instance [16]byte) bool {
	for _, p := range c.paths {
		if p.exit == id && p.hasInstance && p.instance == instance && c.now().Sub(p.lastReply) < deadExit && c.usable(p) {
			return true
		}
	}
	return false
}

// open (re)creates a path's SOCKS association; the path keeps its slot and its exit session.
func (c *Client) open(p *path) {
	if p.transport != nil {
		p.transport.Close()
	}
	p.transport, p.opening, p.active = nil, true, false
	p.awaitingSince, p.lastReply = time.Time{}, time.Time{}
	clear(p.sent)
	p.epoch++
	epoch, port, password := p.epoch, p.route.Port, c.cfg.Password
	go func() {
		t, e := c.dial(port, password, func(data []byte) { c.receive(p, epoch, data) })
		c.mu.Lock()
		defer c.mu.Unlock()
		p.opening = false
		if e != nil || c.stopped || p.removed || p.epoch != epoch {
			if t != nil {
				t.Close()
			}
			// Xray may still be starting, or the node is down: back off up to five seconds.
			p.backoff = min(5*time.Second, max(time.Second, p.backoff*2))
			p.nextOpen = c.now().Add(p.backoff)
			return
		}
		p.backoff = 0
		p.transport = t
		c.ping(p)
	}()
}

func (c *Client) version(p *path) int {
	e, entry := c.exits[p.exit], c.exits[p.entry]
	if e != nil && e.capsKnown && e.caps.LGM3 && (p.entry == p.exit || entry != nil && entry.capsKnown && entry.caps.LGM3) {
		return 3
	}
	return 2
}

func (c *Client) send(p *path, packet *wire.Packet) bool {
	if p.transport == nil || p.transport.Closed() {
		return false
	}
	packet.Path, packet.ExitNodeID = p.slot, p.exit
	frame, e := wire.Append(nil, packet)
	if e != nil {
		return false
	}
	return p.transport.Send(frame) == nil
}

func (c *Client) ping(p *path) {
	now := c.now()
	for sequence, at := range p.sent {
		if now.Sub(at) >= pingTimeout {
			delete(p.sent, sequence)
			p.misses++
		}
	}
	if len(p.sent) >= 32 {
		return
	}
	c.pingSeq++
	sequence := c.pingSeq
	var flags byte
	if p.active {
		flags = wire.PingActive
	}
	packet := wire.Packet{Version: c.version(p), Kind: wire.KindPing, Session: c.exitOf(p.exit).session, Sequence: sequence, Payload: []byte{flags}}
	if c.send(p, &packet) {
		p.sent[sequence] = now
		p.lastPing = now
		if p.awaitingSince.IsZero() {
			p.awaitingSince = now
		}
	}
}

func (c *Client) control(p *path, kind wire.Kind, body any) {
	payload, e := json.Marshal(body)
	if e != nil {
		return
	}
	c.controlSeq++
	packet := wire.Packet{Version: 2, Kind: kind, Session: c.exitOf(p.exit).session, Sequence: c.controlSeq, Payload: payload}
	c.send(p, &packet)
}

// authenticate hands the profile's token to the exit for this path's session.
func (c *Client) authenticate(p *path) {
	if c.cfg.RelayToken == "" {
		return
	}
	e := c.exitOf(p.exit)
	p.authKey = hex.EncodeToString(p.instance[:]) + ":" + hex.EncodeToString(e.session[:8])
	c.control(p, wire.KindAuth, map[string]string{"token": c.cfg.RelayToken})
}

// selectActive picks per exit the paths that carry copies: the fastest, and with protection
// the next fastest, preferring another entry node (an independent route).
func (c *Client) selectActive() {
	groups := map[string][]*path{}
	for _, p := range c.paths {
		p.active = false
		if c.fresh(p) && p.rttMs > 0 && p.misses < 3 {
			key := p.exit + ":" + hex.EncodeToString(p.instance[:])
			groups[key] = append(groups[key], p)
		}
	}
	for _, paths := range groups {
		sort.SliceStable(paths, func(i, j int) bool { return paths[i].rttMs < paths[j].rttMs })
		paths[0].active = true
		if len(paths) < 2 || !c.cfg.protection() {
			continue
		}
		second := paths[1]
		for _, p := range paths[1:] {
			if p.entry != paths[0].entry && p.rttMs < paths[0].rttMs*2+40 {
				second = p
				break
			}
		}
		second.active = true
	}
	// The relay answers only over active paths: an exit that carries flows keeps its most
	// recently answering path active even while every path misses pings.
	for _, w := range c.wires {
		var best *path
		for _, p := range c.exitPaths(w) {
			if p.active {
				best = nil
				break
			}
			if best == nil || p.lastReply.After(best.lastReply) {
				best = p
			}
		}
		if best != nil {
			best.active = true
		}
	}
}

func (c *Client) exitPaths(w *wireFlow) []*path {
	var list []*path
	for _, p := range c.paths {
		if p.exit == w.exit && p.hasInstance && p.instance == w.instance && c.usable(p) {
			list = append(list, p)
		}
	}
	return list
}

// exitRTT is the exit's best fresh path, or +Inf.
func (c *Client) exitRTT(id string) float64 {
	best := math.Inf(1)
	for _, p := range c.paths {
		if p.exit == id && c.fresh(p) && p.rttMs > 0 && p.misses < 3 && p.rttMs < best {
			best = p.rttMs
		}
	}
	return best
}

// chooseExit decides the exit of a new game socket: the chosen server, else the fastest
// enabled one with hysteresis; a draining exit only when nothing else answers.
func (c *Client) chooseExit() string {
	if preferred := c.cfg.PreferredExit; preferred != "" && c.cfg.state(preferred) == "enabled" && !math.IsInf(c.exitRTT(preferred), 1) {
		return preferred
	}
	best, bestRTT := "", math.Inf(1)
	fallback, fallbackRTT := "", math.Inf(1)
	for id := range c.exits {
		rtt := c.exitRTT(id)
		switch c.cfg.state(id) {
		case "enabled":
			if rtt < bestRTT || rtt == bestRTT && id < best {
				best, bestRTT = id, rtt
			}
		case "draining":
			if rtt < fallbackRTT {
				fallback, fallbackRTT = id, rtt
			}
		}
	}
	if best == "" {
		return fallback
	}
	if current := c.current; current != "" && current != best && c.cfg.state(current) == "enabled" {
		if rtt := c.exitRTT(current); !math.IsInf(rtt, 1) && rtt-bestRTT < math.Max(switchMinGainMs, rtt*switchRatio) {
			return current
		}
	}
	return best
}

// warmMesh keeps up to meshPaths copies through other entry nodes open towards the exits that
// carry or will carry a match, and closes them for exits gone cold.
func (c *Client) warmMesh(now time.Time) {
	warm := map[string]bool{}
	// Before the first socket the likely exit is warmed too: a match has a copy from its first packet.
	for _, id := range []string{c.current, c.chooseExit()} {
		if id != "" {
			c.exitOf(id).warmAt = now
		}
	}
	for _, w := range c.wires {
		c.exitOf(w.exit).warmAt = now
	}
	for id, e := range c.exits {
		warm[id] = !e.warmAt.IsZero() && now.Sub(e.warmAt) < warmFor
	}
	for _, p := range c.paths {
		if p.entry != p.exit && !p.removed && !warm[p.exit] {
			p.removed = true
			p.epoch++
			if p.transport != nil {
				p.transport.Close()
			}
		}
	}
	kept := c.paths[:0]
	for _, p := range c.paths {
		if !p.removed {
			kept = append(kept, p)
		}
	}
	c.paths = kept
	for id, isWarm := range warm {
		e := c.exits[id]
		if !isWarm || !e.capsKnown || !e.caps.Mesh || c.cfg.state(id) == "disabled" {
			continue
		}
		have, entries := 0, map[string]bool{}
		for _, p := range c.paths {
			if p.exit == id && p.entry != id {
				have++
				entries[p.entry] = true
			}
		}
		var candidates []*path
		for _, p := range c.paths {
			entry := c.exits[p.entry]
			if p.entry == p.exit && p.entry != id && !entries[p.entry] && c.fresh(p) && p.rttMs > 0 && entry != nil && entry.capsKnown && entry.caps.Mesh &&
				c.cfg.state(p.entry) != "disabled" {
				candidates = append(candidates, p)
				entries[p.entry] = true
			}
		}
		sort.SliceStable(candidates, func(i, j int) bool { return candidates[i].rttMs < candidates[j].rttMs })
		for _, entry := range candidates {
			if have >= meshPaths {
				break
			}
			p := &path{route: entry.route, entry: entry.entry, exit: id, sent: map[uint32]time.Time{}}
			if p.slot = c.freeSlot(e, p); p.slot >= wire.MaxPaths {
				break
			}
			c.paths = append(c.paths, p)
			c.open(p)
			have++
		}
	}
}

// forward carries one datagram of a game socket (called with mu held).
func (c *Client) forward(cn *conn, target endpoint, payload []byte) {
	now := c.now()
	c.lastPacket = now
	cn.touched = now
	if !c.validTarget(target.address) {
		c.dropped++
		return
	}
	f := cn.flows[target]
	if f == nil {
		if !c.admitFlow(cn) {
			c.dropped++
			return
		}
		f = &flow{target: target, conn: cn, started: now}
		cn.flows[target] = f
		c.flowCount++
	}
	f.touched = now
	f.sent++
	if f.wire == nil && !c.bind(f) {
		c.dropped++
		return
	}
	w := f.wire
	paths := []*path{}
	for _, p := range c.exitPaths(w) {
		if p.active && c.fresh(p) {
			paths = append(paths, p)
		}
	}
	if len(paths) == 0 {
		// With every path missing pings for a moment the game keeps the path that answered last.
		var last *path
		for _, p := range c.exitPaths(w) {
			if last == nil || p.lastReply.After(last.lastReply) {
				last = p
			}
		}
		if last == nil {
			c.dropped++
			return
		}
		paths = append(paths, last)
	}
	if w.sequence == math.MaxUint32 {
		c.dropWire(w)
		return
	}
	packet := wire.Packet{Kind: wire.KindUp, Session: c.exitOf(w.exit).session, Flow: w.id, Sequence: w.sequence,
		Address: target.address, Port: target.port, Payload: payload}
	w.sequence++
	packet.Instance = w.instance[:]
	for _, p := range paths {
		packet.Version = c.version(p)
		c.send(p, &packet)
	}
	c.uploaded++
	if c.current == "" {
		c.current = w.exit
	}
}

// bind pins the socket's exit (one public address for all its destinations, as behind any NAT)
// and gives the flow its relay identity.
func (c *Client) bind(f *flow) bool {
	cn := f.conn
	if cn.exit == "" || math.IsInf(c.exitRTT(cn.exit), 1) && !c.exitAliveAny(cn.exit) {
		cn.exit = c.chooseExit()
		if cn.exit == "" {
			return false
		}
		if cn.exit != c.current {
			if c.current != "" {
				c.log("Game: новые сокеты игры идут через " + c.cfg.name(cn.exit))
			}
			c.current = cn.exit
		}
	}
	var instance [16]byte
	found := false
	for _, p := range c.paths {
		if p.exit == cn.exit && c.fresh(p) {
			instance, found = p.instance, true
			if p.active {
				break
			}
		}
	}
	if !found {
		return false
	}
	key := cn.exit + ":" + hex.EncodeToString(instance[:])
	if e := c.exits[cn.exit]; e == nil || !e.capsKnown || !e.caps.EIM {
		key += ":" + net.IP(f.target.address[:]).String() + ":" + strconv.Itoa(int(f.target.port))
	}
	w := cn.wires[key]
	if w == nil {
		w = &wireFlow{key: key, seen: wire.NewSequenceWindow(), exit: cn.exit, instance: instance, conn: cn, flows: map[endpoint]*flow{}}
		for {
			_, _ = rand.Read(w.id[:4])
			if _, taken := c.wires[w.id]; !taken {
				break
			}
		}
		cn.wires[key] = w
		c.wires[w.id] = w
	}
	w.flows[f.target] = f
	f.wire = w
	return true
}

func (c *Client) exitAliveAny(id string) bool {
	for _, p := range c.paths {
		if p.exit == id && c.usable(p) && c.now().Sub(p.lastReply) < deadExit {
			return true
		}
	}
	return false
}

func (c *Client) dropFlow(f *flow) {
	cn := f.conn
	if cn.flows[f.target] != f {
		return
	}
	delete(cn.flows, f.target)
	c.flowCount--
	if w := f.wire; w != nil {
		delete(w.flows, f.target)
		if len(w.flows) == 0 {
			delete(c.wires, w.id)
			delete(cn.wires, w.key)
		}
	}
	f.wire = nil
	if len(cn.flows) == 0 {
		cn.exit = ""
	}
}

// dropWire ends a relay identity: its flows re-decide with the next packet (a new exit port).
func (c *Client) dropWire(w *wireFlow) {
	delete(c.wires, w.id)
	delete(w.conn.wires, w.key)
	for _, f := range w.flows {
		f.wire = nil
	}
	if w.conn.exit == w.exit {
		w.conn.exit = ""
	}
	if c.current == w.exit {
		c.current = ""
	}
}

// receive handles one relay datagram of a path (from the path's reader goroutine).
func (c *Client) receive(p *path, epoch int, data []byte) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.stopped || p.removed || p.epoch != epoch {
		return
	}
	packet, ok := wire.Decode(data, c.resolve)
	if !ok || packet.Path != p.slot || packet.ExitNodeID != "" && packet.ExitNodeID != p.exit {
		return
	}
	e := c.exitOf(p.exit)
	if packet.Session != e.session {
		return
	}
	now := c.now()
	switch packet.Kind {
	case wire.KindPong:
		started, asked := p.sent[packet.Sequence]
		if !asked || len(packet.Payload) != 16 {
			return
		}
		delete(p.sent, packet.Sequence)
		var instance [16]byte
		copy(instance[:], packet.Payload)
		if instance != e.instance {
			c.instanceChanged(e, instance)
		}
		p.instance, p.hasInstance, p.misses = instance, true, 0
		sample := float64(now.Sub(started)) / float64(time.Millisecond)
		if p.rttMs == 0 {
			p.rttMs = math.Max(sample, 0.1)
		} else {
			p.rttMs = p.rttMs*0.8 + sample*0.2
		}
		p.lastReply, p.awaitingSince = now, time.Time{}
		if c.cfg.RelayToken != "" && p.authKey != hex.EncodeToString(instance[:])+":"+hex.EncodeToString(e.session[:8]) {
			c.authenticate(p)
		}
	case wire.KindNodeInfo:
		var body struct {
			Version      int    `json:"version"`
			NodeID       string `json:"nodeId"`
			Instance     string `json:"instance"`
			Capabilities caps   `json:"capabilities"`
		}
		if packet.Version != 2 || json.Unmarshal(packet.Payload, &body) != nil || body.Version != 2 || body.NodeID != p.exit ||
			!p.hasInstance || body.Instance != hex.EncodeToString(p.instance[:]) {
			return
		}
		e.caps, e.capsKnown = body.Capabilities, true
	case wire.KindAuthResult:
		var body struct {
			Status string `json:"status"`
		}
		if packet.Version != 2 || json.Unmarshal(packet.Payload, &body) != nil || len(body.Status) == 0 || len(body.Status) > 16 {
			return
		}
		e.auth = body.Status
		if note := p.exit + ":" + body.Status; body.Status != "ok" && !c.authNoted[note] && len(c.authNoted) < 64 {
			c.authNoted[note] = true
			c.log("Game: узел " + c.cfg.name(p.exit) + " не принял ключ Levik Game (" + body.Status + ")")
		}
	case wire.KindDown:
		w := c.wires[packet.Flow]
		if w == nil || w.exit != p.exit || !p.hasInstance || w.instance != p.instance {
			return
		}
		p.lastReply, p.awaitingSince = now, time.Time{}
		w.repliedAt = now
		target := endpoint{address: packet.Address, port: packet.Port}
		if !w.seen.Accept(packet.Sequence) {
			c.duplicates++
			return
		}
		f := w.flows[target]
		if f == nil {
			f = c.inboundFlow(w, target)
		}
		if f == nil {
			return
		}
		p.firstReplies++
		f.received++
		f.touched = now
		c.received++
		c.deliver(f, packet.Payload)
	}
}

// inboundFlow: a peer the game socket never wrote to (a voice server, a P2P peer) reaches it as
// through a full-cone NAT, on the same exit port. Only XUDP can tell the game who sent it.
func (c *Client) inboundFlow(w *wireFlow, target endpoint) *flow {
	cn := w.conn
	if !cn.xudp || cn.closed || cn.flows[target] != nil || len(w.flows) == 0 || !c.validTarget(target.address) || !c.admitFlow(cn) {
		return nil
	}
	now := c.now()
	f := &flow{target: target, conn: cn, wire: w, started: now, touched: now}
	cn.flows[target] = f
	w.flows[target] = f
	c.flowCount++
	return f
}

// instanceChanged: a restarted relay is a new exit process; its old flows are gone.
func (c *Client) instanceChanged(e *exit, instance [16]byte) {
	if e.instance != ([16]byte{}) {
		c.log("Game: узел " + c.cfg.name(e.id) + " перезапустился; его сокеты открываются заново")
	}
	e.instance, e.capsKnown, e.askedAt, e.auth = instance, false, time.Time{}, ""
	for _, w := range c.wires {
		if w.exit == e.id && w.instance != instance {
			c.dropWire(w)
		}
	}
}
