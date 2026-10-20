// Package game is the Levik Game data plane of the Direct Android app: the same
// protocol and behaviour as the Windows client (levik_vpn_windows multipath/client.ts),
// in a loopback sidecar. Xray keeps the TUN and routes the selected games' UDP to
// this listener (VLESS/XUDP); the listener carries it to the game relay of one exit
// over every live path (Xray SOCKS inbounds to the game nodes, directly or through
// another node's mesh) and returns the first copy of every reply.
package game

import (
	"encoding/json"
	"errors"
	"regexp"

	"levik.local/levik-game/wire"
)

const (
	// MaxRoutes: one Xray SOCKS inbound per game server of the profile.
	MaxRoutes = 32
	MaxNodes  = 64
	maxConfig = 64 * 1024
)

type RouteConfig struct {
	Index  int    `json:"index"`
	Port   int    `json:"port"`
	NodeID string `json:"nodeId"`
}

type NodeConfig struct {
	NodeID string `json:"nodeId"`
	Name   string `json:"name"`
	// State: enabled, draining (no new exits) or disabled (never used).
	State string `json:"state"`
}

type Config struct {
	Version int `json:"version"`
	Listen  struct {
		Port int    `json:"port"`
		UUID string `json:"uuid"`
	} `json:"listen"`
	// Password of the "levik-game" user of every route's SOCKS inbound.
	Password      string        `json:"password"`
	Routes        []RouteConfig `json:"routes"`
	Nodes         []NodeConfig  `json:"nodes"`
	PreferredExit string        `json:"preferredExitId,omitempty"`
	// RelayToken: the profile's signed per-user token (levik_game_node auth.go).
	RelayToken string `json:"relayToken,omitempty"`
	// Protection (default on): every packet goes over the two best paths of its exit.
	Protection *bool `json:"protection,omitempty"`
}

func (c Config) protection() bool { return c.Protection == nil || *c.Protection }

var (
	uuidPattern  = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	tokenPattern = regexp.MustCompile(`^TEdUMQ[A-Za-z0-9_-]{120}$`)
	namePattern  = regexp.MustCompile(`^[\p{L}\p{N} ._()/-]{0,64}$`)
)

// ParseConfig rejects anything the app would not write.
func ParseConfig(raw []byte) (Config, error) {
	var c Config
	rejected := errors.New("invalid game configuration")
	if len(raw) > maxConfig || json.Unmarshal(raw, &c) != nil || c.Version != 1 {
		return c, rejected
	}
	if c.Listen.Port < 0 || c.Listen.Port > 65535 || !uuidPattern.MatchString(c.Listen.UUID) ||
		len(c.Password) < 16 || len(c.Password) > 128 || len(c.Routes) < 1 || len(c.Routes) > MaxRoutes || len(c.Nodes) > MaxNodes ||
		(c.RelayToken != "" && !tokenPattern.MatchString(c.RelayToken)) || (c.PreferredExit != "" && !wire.ValidNodeID(c.PreferredExit)) {
		return c, rejected
	}
	ports, indexes, nodes := map[int]bool{}, map[int]bool{}, map[string]bool{}
	for _, node := range c.Nodes {
		if !wire.ValidNodeID(node.NodeID) || nodes[node.NodeID] || !namePattern.MatchString(node.Name) ||
			(node.State != "enabled" && node.State != "draining" && node.State != "disabled") {
			return c, rejected
		}
		nodes[node.NodeID] = true
	}
	for _, route := range c.Routes {
		if route.Port < 1024 || route.Port > 65535 || ports[route.Port] || route.Index < 0 || indexes[route.Index] || !wire.ValidNodeID(route.NodeID) {
			return c, rejected
		}
		ports[route.Port], indexes[route.Index] = true, true
	}
	ids := make([]string, 0, len(nodes)+len(c.Routes))
	for _, route := range c.Routes {
		ids = append(ids, route.NodeID)
	}
	for id := range nodes {
		ids = append(ids, id)
	}
	if _, e := wire.NewTagResolver(ids); e != nil {
		return c, rejected
	}
	return c, nil
}

func (c Config) state(nodeID string) string {
	for _, node := range c.Nodes {
		if node.NodeID == nodeID {
			return node.State
		}
	}
	return "enabled"
}

func (c Config) name(nodeID string) string {
	for _, node := range c.Nodes {
		if node.NodeID == nodeID && node.Name != "" {
			return node.Name
		}
	}
	return nodeID
}
