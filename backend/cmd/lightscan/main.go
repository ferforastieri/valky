// Command lightscan looks for Tuya-based bulbs on the local network and, when a
// local key is supplied, validates that the device answers. It exists so the
// pairing step can be checked from the server itself, before the Android app is
// involved: nothing here contacts a cloud service.
//
//	go run ./cmd/lightscan
//	go run ./cmd/lightscan -probe
//	go run ./cmd/lightscan -id <device id> -key <16 character key> -address 192.0.2.60
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/light/tuya"
)

func main() {
	duration := flag.Duration("duration", 8*time.Second, "how long to listen for device announcements")
	probe := flag.Bool("probe", false, "also try the control port on every address of the local subnets")
	deviceID := flag.String("id", "", "device identifier to validate")
	localKey := flag.String("key", "", "local key (16 characters) used to validate the device")
	address := flag.String("address", "", "device address; discovered automatically when omitted")
	version := flag.Float64("version", 0, "protocol version to try first (3.1, 3.3, 3.4 or 3.5)")
	flag.Parse()

	ctx, cancel := context.WithTimeout(context.Background(), *duration+30*time.Second)
	defer cancel()

	fmt.Printf("Procurando lâmpadas na rede local por %s (sem nuvem, sem conta)...\n", duration.String())
	devices, err := tuya.Discover(ctx, tuya.ScanOptions{Duration: *duration, TCPProbe: *probe, Timeout: 3 * time.Second})
	if err != nil {
		fmt.Fprintf(os.Stderr, "falha na descoberta: %v\n", err)
		os.Exit(1)
	}
	if len(devices) == 0 {
		fmt.Println("nenhum dispositivo respondeu. Confira se a lâmpada está energizada e no mesmo Wi-Fi do servidor.")
		return
	}
	for _, device := range devices {
		version := ""
		if device.Version > 0 {
			version = fmt.Sprintf(" v%.1f", device.Version)
		}
		identity := device.ID
		if identity == "" {
			identity = "(identificador oculto: informe a chave para confirmar)"
		}
		fmt.Printf("- %s%s  id=%s  produto=%s  mac=%s\n", device.Address, version, identity, device.ProductKey, device.MAC)
	}

	if *deviceID == "" || *localKey == "" {
		fmt.Println("\nPara validar uma lâmpada, rode de novo com -id e -key.")
		return
	}
	target := *address
	if target == "" {
		for _, device := range devices {
			if device.ID == *deviceID {
				target = device.Address
				break
			}
		}
	}
	if target == "" {
		fmt.Fprintln(os.Stderr, "endereço não encontrado: informe -address")
		os.Exit(1)
	}
	dps, working, err := tuya.Identify(ctx, tuya.Options{
		DeviceID: *deviceID,
		Address:  target,
		LocalKey: *localKey,
		Version:  *version,
		Timeout:  8 * time.Second,
	})
	if err != nil {
		fmt.Fprintf(os.Stderr, "a lâmpada não respondeu: %v\n", err)
		os.Exit(1)
	}
	fmt.Printf("\nRespondeu em %s usando o protocolo %.1f\n", target, working)
	encoded, _ := json.MarshalIndent(dps, "", "  ")
	fmt.Printf("pontos de controle:\n%s\n", encoded)
}
