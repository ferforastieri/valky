// Command lightprobe fala com uma lâmpada já cadastrada na rede local para
// diagnóstico: lê o estado e mostra os pontos de controle com a chave informada.
// É a mesma conversa que o backend faz; nada sai da rede local.
//
//	go run ./cmd/lightprobe -address 192.168.1.60 -key 0123456789abcdef
package main

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"os"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/light/tuya"
)

// probeRaw consulta sem chave, do jeito que os firmwares 3.1 respondem, para
// descobrir o que o dispositivo fala antes de termos a chave local.
func probeRaw(address string) {
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(address, "6668"), 5*time.Second)
	if err != nil {
		fmt.Fprintf(os.Stderr, "sem conexão: %v\n", err)
		os.Exit(1)
	}
	defer conn.Close()
	query := []byte(`{"gwId":"","devId":"","uid":"","t":"0"}`)
	frame, err := tuya.PackPlainQuery(query)
	if err != nil {
		fmt.Fprintf(os.Stderr, "falha ao montar a consulta: %v\n", err)
		os.Exit(1)
	}
	if _, err = conn.Write(frame); err != nil {
		fmt.Fprintf(os.Stderr, "falha ao enviar: %v\n", err)
		os.Exit(1)
	}
	_ = conn.SetReadDeadline(time.Now().Add(8 * time.Second))
	buffer := make([]byte, 4096)
	read, err := conn.Read(buffer)
	if err != nil {
		fmt.Fprintf(os.Stderr, "sem resposta: %v\n", err)
		os.Exit(1)
	}
	fmt.Printf("resposta de %d bytes:\n%s\n", read, hex.EncodeToString(buffer[:read]))
	if text := tuya.PlainPayload(buffer[:read]); text != "" {
		fmt.Printf("payload em texto puro:\n%s\n", text)
	}
}

func main() {
	address := flag.String("address", "", "endereço da lâmpada na rede local")
	key := flag.String("key", "", "chave local de 16 caracteres")
	deviceID := flag.String("id", "", "identificador do dispositivo (opcional)")
	version := flag.Float64("version", 0, "versão do protocolo; 0 tenta a escada inteira")
	raw := flag.Bool("raw", false, "envia a consulta 3.1 em texto puro e mostra a resposta crua (sem chave)")
	flag.Parse()

	if *raw {
		if *address == "" {
			fmt.Fprintln(os.Stderr, "informe -address")
			os.Exit(2)
		}
		probeRaw(*address)
		return
	}

	if *address == "" || len(*key) != 16 {
		fmt.Fprintln(os.Stderr, "informe -address e -key (16 caracteres)")
		os.Exit(2)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()

	options := tuya.Options{DeviceID: *deviceID, Address: *address, LocalKey: *key, Version: *version, Timeout: 6 * time.Second}
	dps, working, err := tuya.Identify(ctx, options)
	if err != nil {
		fmt.Fprintf(os.Stderr, "sem resposta: %v\n", err)
		os.Exit(1)
	}
	fmt.Printf("respondeu em %s com o protocolo %.1f\n", *address, working)
	encoded, _ := json.MarshalIndent(dps, "", "  ")
	fmt.Printf("pontos de controle:\n%s\n", encoded)
}
