package tuya

import (
	"context"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ScanAddresses procura dispositivos Tuya na mesma faixa de um endereço
// conhecido. A descoberta por broadcast (Discover) não atravessa a rede do
// container; a varredura TCP atravessa, e é ela que traz a lâmpada de volta
// quando o DHCP troca o endereço dela.
func ScanAddresses(ctx context.Context, known string, port int, timeout time.Duration) []string {
	host := strings.TrimSpace(known)
	if i := strings.LastIndex(host, ":"); i > 0 {
		host = host[:i]
	}
	if len(strings.Split(host, ".")) != 4 || net.ParseIP(host) == nil {
		return nil
	}
	prefix := host[:strings.LastIndex(host, ".")] + "."
	found := make(chan string, 254)
	var wg sync.WaitGroup
	slots := make(chan struct{}, 64)
	for host := 1; host <= 254; host++ {
		address := prefix + strconv.Itoa(host)
		wg.Add(1)
		go func(candidate string) {
			defer wg.Done()
			select {
			case slots <- struct{}{}:
				defer func() { <-slots }()
			case <-ctx.Done():
				return
			}
			conn, err := net.DialTimeout("tcp", net.JoinHostPort(candidate, strconv.Itoa(port)), timeout)
			if err != nil {
				return
			}
			_ = conn.Close()
			select {
			case found <- candidate:
			case <-ctx.Done():
			}
		}(address)
	}
	go func() { wg.Wait(); close(found) }()
	addresses := []string{}
	for address := range found {
		addresses = append(addresses, address)
	}
	return addresses
}
