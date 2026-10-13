// Command lightsend transmits the local pairing payload, the same one the phone
// sends over the air, from the server itself. It exists because the server is
// wired and the access point usually relays these frames to the 2.4 GHz air,
// which is where a lamp in pairing mode listens.
//
//	go run ./cmd/lightsend -ssid Casa -pass senha -token <16> -secret <4> -duration 90s
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/light/tuya"
)

func main() {
	ssid := flag.String("ssid", "", "nome da rede 2,4 GHz que a lâmpada deve usar")
	password := flag.String("pass", "", "senha dessa rede")
	region := flag.String("region", "AY", "região gravada junto com o token")
	token := flag.String("token", "", "valor de 16 caracteres gerado pelo backend")
	secret := flag.String("secret", "", "valor de 4 caracteres gerado pelo backend")
	duration := flag.Duration("duration", 90*time.Second, "por quanto tempo transmitir")
	flag.Parse()

	if *ssid == "" || *password == "" {
		fmt.Fprintln(os.Stderr, "informe -ssid e -pass")
		os.Exit(2)
	}
	if len(*token) != 16 || len(*secret) != 4 {
		fmt.Fprintln(os.Stderr, "informe -token (16 caracteres) e -secret (4 caracteres) da janela de pareamento")
		os.Exit(2)
	}

	group := tuya.SmartConfigTokenGroup(*region, *token, *secret)
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()
	ctx, cancelTimeout := context.WithTimeout(ctx, *duration)
	defer cancelTimeout()

	fmt.Printf("Transmitindo o pareamento para %q por %s (a lâmpada precisa estar piscando rápido)...\n", *ssid, duration.String())
	started := time.Now()
	err := tuya.SendSmartConfig(ctx, *ssid, *password, group, func(round int) {
		if round%10 == 0 {
			fmt.Printf("  rodada %d (%.0fs)\n", round, time.Since(started).Seconds())
		}
	})
	if err != nil {
		fmt.Fprintf(os.Stderr, "falha ao transmitir: %v\n", err)
		os.Exit(1)
	}
	fmt.Println("transmissão concluída. Se a lâmpada entrou na rede, ela aparece na janela de pareamento do Valkyris.")
}
