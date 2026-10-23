package tuya

import (
	"context"
	"net"
	"strconv"
	"testing"
	"time"
)

// A sondagem é o que encontra uma lâmpada quando o broadcast não chega até a
// rede dela (backend atrás da bridge de um container). Ela precisa usar o
// contexto do chamador, que continua vivo depois da janela de escuta.
func TestProbeFindsTheControlPortOnAGivenNetwork(t *testing.T) {
	listener, err := net.Listen("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(PortTCP)))
	if err != nil {
		t.Skipf("porta %d indisponível: %v", PortTCP, err)
	}
	defer listener.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	devices := probeLocalNetworks(ctx, nil, []string{"127.0.0"}, 300*time.Millisecond)

	if len(devices) != 1 || devices[0].Address != "127.0.0.1" {
		t.Fatalf("esperava 127.0.0.1, veio %+v", devices)
	}
}

// Sem o contexto do chamador a sondagem não pode nem começar: era o bug que
// fazia a busca por lâmpadas nunca encontrar nada, mesmo na mesma rede.
func TestProbeStopsWhenTheCallerCancels(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	start := time.Now()
	if devices := probeLocalNetworks(ctx, nil, []string{"127.0.0"}, 300*time.Millisecond); len(devices) != 0 {
		t.Fatalf("esperava nenhuma lâmpada, veio %+v", devices)
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Fatalf("a sondagem deveria parar no cancelamento, levou %s", elapsed)
	}
}

// A descoberta completa: escuta o broadcast e, quando ele não acha ninguém,
// ainda sonda as faixas informadas. Antes a sondagem rodava com o prazo da
// escuta já vencido e voltava sempre vazia.
func TestDiscoverProbesAfterListening(t *testing.T) {
	listener, err := net.Listen("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(PortTCP)))
	if err != nil {
		t.Skipf("porta %d indisponível: %v", PortTCP, err)
	}
	defer listener.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	devices, err := Discover(ctx, ScanOptions{
		Duration: 100 * time.Millisecond,
		TCPProbe: true,
		Networks: []string{"127.0.0"},
		Timeout:  300 * time.Millisecond,
	})
	if err != nil {
		t.Fatal(err)
	}
	for _, device := range devices {
		if device.Address == "127.0.0.1" {
			return
		}
	}
	t.Fatalf("a sondagem não devolveu 127.0.0.1: %+v", devices)
}
