package api

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"
)

type Hub struct {
	mu      sync.RWMutex
	clients map[*websocket.Conn]struct{}
}

func NewHub() *Hub { return &Hub{clients: map[*websocket.Conn]struct{}{}} }
func (h *Hub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("X-Valkyris-Message", "Realtime connection established")
	w.Header().Set("X-Valkyris-Success", "true")
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{OriginPatterns: []string{"*"}})
	if err != nil {
		return
	}
	h.mu.Lock()
	h.clients[conn] = struct{}{}
	h.mu.Unlock()
	defer func() {
		h.mu.Lock()
		delete(h.clients, conn)
		h.mu.Unlock()
		conn.Close(websocket.StatusNormalClosure, "")
	}()
	for {
		ctx, cancel := context.WithTimeout(r.Context(), 60*time.Second)
		_, _, err = conn.Read(ctx)
		cancel()
		if err != nil {
			return
		}
	}
}
func (h *Hub) Broadcast(value any) {
	payload, err := json.Marshal(value)
	if err != nil {
		return
	}
	h.mu.RLock()
	clients := make([]*websocket.Conn, 0, len(h.clients))
	for c := range h.clients {
		clients = append(clients, c)
	}
	h.mu.RUnlock()
	for _, c := range clients {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		_ = c.Write(ctx, websocket.MessageText, payload)
		cancel()
	}
}
