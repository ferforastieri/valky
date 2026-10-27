package remote

import (
	"context"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

// scanSubnet procura um aparelho na mesma faixa /24 de um endereço conhecido.
// O broadcast não atravessa a rede do container, mas o TCP atravessa: é essa
// varredura que reencontra a TV quando o DHCP troca o endereço dela, do mesmo
// jeito que a descoberta das lâmpadas Tuya faz.
func scanSubnet(ctx context.Context, known string, ports []int, timeout time.Duration) []string {
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
	slots := make(chan struct{}, 96)
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
			for _, port := range ports {
				conn, err := net.DialTimeout("tcp", net.JoinHostPort(target, strconv.Itoa(port)), timeout)
				if err != nil {
					continue
				}
				_ = conn.Close()
				select {
				case found <- target:
				case <-ctx.Done():
				}
				return
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

// sameMAC compara endereços de rede ignorando separador e caixa, porque a TV
// pode devolver o MAC com dois-pontos, hífen ou tudo minúsculo.
func sameMAC(left, right string) bool {
	first, err := net.ParseMAC(strings.TrimSpace(left))
	if err != nil {
		return false
	}
	second, err := net.ParseMAC(strings.TrimSpace(right))
	if err != nil {
		return false
	}
	return string(first) == string(second)
}
