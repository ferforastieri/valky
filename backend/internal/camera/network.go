package camera

import (
	"context"
	"net"
	"strconv"
	"time"
)

// netDialer é o dialer usado pelas sondas de rede da câmera.
type netDialer struct{ timeout time.Duration }

func (d *netDialer) DialContext(ctx context.Context, network, target string) (net.Conn, error) {
	dialer := &net.Dialer{Timeout: d.timeout}
	return dialer.DialContext(ctx, network, target)
}

// address monta host:porta para as sondas TCP.
func address(host string, port int) string {
	return net.JoinHostPort(host, strconv.Itoa(port))
}
