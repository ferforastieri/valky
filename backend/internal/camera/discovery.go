package camera

import (
	"context"
	"strings"
	"sync"
	"time"
)

// Discovery é o resultado de uma ronda atrás da câmera: o endereço novo, se ela
// foi reencontrada, e como cada candidato foi identificado.
type Discovery struct {
	Host    string
	Found   bool
	Match   string
	Scanned int
	Errs    []string
}

// Identity é o que permite reconhecer a câmera em outro endereço. Sem um destes
// campos o resultado da ronda não prova nada e é descartado, porque gravar o IP
// de outra câmera da mesma faixa seria pior do que continuar sem imagem.
func Identity(mac, serial string) bool {
	return strings.TrimSpace(mac) != "" || strings.TrimSpace(serial) != ""
}

// Discover procura a câmera na faixa do último endereço conhecido. A ordem é do
// mais barato para o mais caro: primeiro o próprio endereço salvo, depois o
// RTSP dos candidatos que têm a porta ONVIF aberta, e só então a sondagem ONVIF
// autenticada de quem respondeu ao TCP.
func (c *ONVIFClient) Discover(ctx context.Context, cam Camera, cred Credentials, scan func(context.Context, string, int, time.Duration) []string) Discovery {
	result := Discovery{Host: cam.Host}
	if scan == nil {
		scan = ScanPort
	}
	// O endereço salvo pode simplesmente voltar a responder: nesse caso não há
	// nada a corrigir.
	if c.Reachable(ctx, cam.Host, cam.Port) {
		result.Found, result.Match = true, "current"
		return result
	}
	scanCtx, cancel := context.WithTimeout(ctx, 8*time.Second)
	candidates := scan(scanCtx, cam.Host, defaultONVIFPort(cam.Port), 250*time.Millisecond)
	cancel()
	result.Scanned = len(candidates)
	if len(candidates) == 0 {
		return result
	}

	// O RTSP responde antes de qualquer autenticação e já elimina quem não é
	// câmera, então ele filtra a lista antes das sondagens ONVIF.
	rtspHosts := make([]string, 0, len(candidates))
	var rtspMu sync.Mutex
	var rtspProbes sync.WaitGroup
	for _, candidate := range candidates {
		if candidate == cam.Host {
			continue
		}
		rtspProbes.Add(1)
		go func(address string) {
			defer rtspProbes.Done()
			if c.speaksRTSP(ctx, address) {
				rtspMu.Lock()
				rtspHosts = append(rtspHosts, address)
				rtspMu.Unlock()
			}
		}(candidate)
	}
	rtspProbes.Wait()
	if len(rtspHosts) == 0 {
		return result
	}

	type device struct {
		host    string
		serial  string
		address string
	}
	devices := make(chan device, len(rtspHosts))
	var probes sync.WaitGroup
	for _, candidate := range rtspHosts {
		probes.Add(1)
		go func(address string) {
			defer probes.Done()
			probeCtx, probeCancel := context.WithTimeout(ctx, 6*time.Second)
			defer probeCancel()
			serial, mac := c.Identity(probeCtx, address, defaultONVIFPort(cam.Port), cred.Username, cred.Password)
			if serial == "" && mac == "" {
				return
			}
			select {
			case devices <- device{host: address, serial: serial, address: mac}:
			default:
			}
		}(candidate)
	}
	probes.Wait()
	close(devices)

	for candidate := range devices {
		switch {
		case cam.MAC != "" && sameIdentity(candidate.address, cam.MAC):
			result.Host, result.Found, result.Match = candidate.host, true, "mac"
			return result
		case cam.Serial != "" && sameIdentity(candidate.serial, cam.Serial):
			result.Host, result.Found, result.Match = candidate.host, true, "serial"
			return result
		}
	}
	return result
}

// Reachable confere se o endereço já responde sem tentar autenticar, para não
// gastar a senha da câmera a cada ronda e para funcionar mesmo quando a
// credencial está errada e é justamente isso que precisa ser diagnosticado.
func (c *ONVIFClient) Reachable(ctx context.Context, host string, port int) bool {
	if strings.TrimSpace(host) == "" {
		return false
	}
	conn, err := (&netDialer{timeout: 2 * time.Second}).DialContext(ctx, "tcp", address(host, defaultONVIFPort(port)))
	if err != nil {
		return false
	}
	_ = conn.Close()
	return true
}

// speaksRTSP confirma que a porta 554 fala RTSP, o que separa uma câmera de
// qualquer outro aparelho que também abra a porta 2020.
func (c *ONVIFClient) speaksRTSP(ctx context.Context, host string) bool {
	conn, err := (&netDialer{timeout: 1500 * time.Millisecond}).DialContext(ctx, "tcp", address(host, 554))
	if err != nil {
		return false
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
	if _, err = conn.Write([]byte("OPTIONS rtsp://" + host + "/ RTSP/1.0\r\nCSeq: 1\r\n\r\n")); err != nil {
		return false
	}
	buffer := make([]byte, 64)
	read, err := conn.Read(buffer)
	if err != nil {
		return false
	}
	return strings.HasPrefix(string(buffer[:read]), "RTSP/")
}

// sameIdentity compara identificadores de rede ignorando separador e caixa,
// porque o mesmo MAC aparece como 98:03:8E e 98-03-8e dependendo do firmware.
func sameIdentity(left, right string) bool {
	normalize := func(value string) string {
		value = strings.ToLower(strings.TrimSpace(value))
		value = strings.ReplaceAll(value, ":", "")
		value = strings.ReplaceAll(value, "-", "")
		value = strings.ReplaceAll(value, ".", "")
		return value
	}
	left, right = normalize(left), normalize(right)
	return left != "" && left == right
}

func defaultONVIFPort(port int) int {
	if port == 0 {
		return 2020
	}
	return port
}
