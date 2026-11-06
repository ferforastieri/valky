package camera

import (
	"context"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ScanPort procura câmeras na mesma faixa /24 de um endereço conhecido.
// O WS-Discovery (UDP 3702) não atravessa a rede do container e muitas câmeras
// nem respondem a ele, então a varredura TCP é o que realmente reencontra a
// câmera quando o DHCP troca o endereço dela — mesma estratégia das lâmpadas
// Tuya e das TVs.
func ScanPort(ctx context.Context, known string, port int, timeout time.Duration) []string {
	host := strings.TrimSpace(known)
	if index := strings.LastIndex(host, ":"); index > 0 {
		host = host[:index]
	}
	ip := net.ParseIP(host)
	if ip == nil || ip.To4() == nil {
		return nil
	}
	prefix := host[:strings.LastIndex(host, ".")] + "."
	found := make(chan string, 254)
	var wg sync.WaitGroup
	slots := make(chan struct{}, 64)
	for last := 1; last <= 254; last++ {
		candidate := prefix + strconv.Itoa(last)
		wg.Add(1)
		go func(target string) {
			defer wg.Done()
			select {
			case slots <- struct{}{}:
				defer func() { <-slots }()
			case <-ctx.Done():
				return
			}
			conn, err := net.DialTimeout("tcp", net.JoinHostPort(target, strconv.Itoa(port)), timeout)
			if err != nil {
				return
			}
			_ = conn.Close()
			select {
			case found <- target:
			case <-ctx.Done():
			}
		}(candidate)
	}
	go func() { wg.Wait(); close(found) }()
	addresses := []string{}
	for address := range found {
		addresses = append(addresses, address)
	}
	return addresses
}
