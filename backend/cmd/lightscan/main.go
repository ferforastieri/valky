// Command lightscan lists what answers the local lighting announcement. It is a
// diagnostic tool: it never contacts a cloud service and it exists so the
// reachability of a lamp can be checked from the server itself.
//
//	go run ./cmd/lightscan
//	go run ./cmd/lightscan -probe
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
	asJSON := flag.Bool("json", false, "print the result as JSON")
	flag.Parse()

	ctx, cancel := context.WithTimeout(context.Background(), *duration+30*time.Second)
	defer cancel()

	if !*asJSON {
		fmt.Printf("Procurando lâmpadas na rede local por %s (sem nuvem, sem conta)...\n", duration.String())
	}
	devices, err := tuya.Discover(ctx, tuya.ScanOptions{Duration: *duration, TCPProbe: *probe, Timeout: 3 * time.Second})
	if err != nil {
		fmt.Fprintf(os.Stderr, "falha na descoberta: %v\n", err)
		os.Exit(1)
	}
	if *asJSON {
		encoded, _ := json.MarshalIndent(devices, "", "  ")
		fmt.Println(string(encoded))
		return
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
			identity = "(sem identificador)"
		}
		fmt.Printf("- %s%s  id=%s  produto=%s  mac=%s\n", device.Address, version, identity, device.ProductKey, device.MAC)
	}
	fmt.Println("\nUma lâmpada em modo de pareamento não aparece aqui: ela ainda não está na rede. Use Adicionar no aplicativo para configurá-la.")
}
