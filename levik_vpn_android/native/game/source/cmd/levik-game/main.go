// levik-game is the Levik Game sidecar of the Direct Android app (liblevikgame.so).
// The app writes the configuration (game.Config, with the SOCKS password and the relay
// token) to stdin and closes it; nothing is written to disk. stdout carries "ready <port>"
// and then one JSON status line every two seconds; stderr carries log lines without
// addresses. Every socket it opens is loopback: Xray owns and protects the real ones.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/signal"
	"syscall"
	"time"

	"levik.local/levik-game/game"
)

func main() {
	raw, e := io.ReadAll(io.LimitReader(os.Stdin, 64*1024+1))
	if e != nil {
		fail("configuration unreadable")
	}
	cfg, e := game.ParseConfig(raw)
	clear(raw)
	if e != nil {
		fail(e.Error())
	}
	logger := bufio.NewWriter(os.Stderr)
	log := func(line string) { _, _ = logger.WriteString(line + "\n"); _ = logger.Flush() }
	client := game.New(cfg, nil, log)
	port, e := client.Listen(cfg.Listen.Port)
	if e != nil {
		fail("listener unavailable")
	}
	fmt.Printf("ready %d\n", port)
	go client.Run()
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGTERM, syscall.SIGINT)
	encoder := json.NewEncoder(os.Stdout)
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-signals:
			client.Stop()
			return
		case <-ticker.C:
			// The app stopped reading: it is gone, and so is this sidecar.
			if encoder.Encode(client.Status()) != nil {
				client.Stop()
				return
			}
		}
	}
}

func fail(reason string) {
	fmt.Fprintln(os.Stderr, "levik-game: "+reason)
	os.Exit(2)
}
