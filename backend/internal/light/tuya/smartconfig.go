package tuya

import (
	"context"
	"crypto/aes"
	"fmt"
	"net"
	"strconv"
	"strings"
	"time"
)

// Pareamento EZ (SmartConfig): a lâmpada em modo de pareamento não está na rede;
// ela escuta o ar esperando receber o nome da rede, a senha e os valores de uso
// único. Os dados viajam no *tamanho* dos pacotes, em duas portadoras que os
// firmwares escutam ao mesmo tempo:
//
//   - multicast: cada byte vira um endereço 226.<sequência>.<segundo>.<primeiro>,
//     enviado para a porta 30012;
//   - broadcast: os mesmos dados agrupados de quatro em quatro, com um cabeçalho
//     CRC-8, enviados com o tamanho no cabeçalho IP para a porta 30011.
//
// O formato foi documentado pela comunidade a partir do tráfego do aplicativo
// do fabricante; a implementação abaixo é própria e verificada por vetores.
const (
	SmartConfigBroadcastPort = 30011
	SmartConfigMulticastPort = 30012

	smartConfigPasswordKey = "a3c6794oiu876t54"
	smartConfigMulticastIP = 226
)

// SmartConfigFrames monta as duas portadoras do pareamento. O tokenGroup é
// region + token + secret, exatamente o que a lâmpada guarda.
func SmartConfigFrames(ssid, password, tokenGroup string) (broadcast []int, multicast []string) {
	return smartConfigBroadcastBody(password, ssid, tokenGroup), smartConfigMulticastBody(password, ssid, tokenGroup)
}

func smartConfigBroadcastHead() []int { return []int{1, 3, 6, 10} }

func smartConfigBroadcastBody(password, ssid, tokenGroup string) []int {
	data := make([]int, 0, len(password)+len(tokenGroup)+len(ssid)+2)
	data = append(data, len(password))
	for _, char := range password {
		data = append(data, int(char))
	}
	data = append(data, len(tokenGroup))
	for _, char := range tokenGroup {
		data = append(data, int(char))
	}
	for _, char := range ssid {
		data = append(data, int(char))
	}

	encoded := make([]int, 0, len(data)+16)
	length := len(data)
	lengthCRC := crc8([]int{length})
	encoded = append(encoded, length>>4|16, length&0x0F|32, lengthCRC>>4|48, lengthCRC&0x0F|64)

	sequence := 0
	lastGroupStart := 0
	for index := 0; index < length; index += 4 {
		lastGroupStart = index
		group := []int{sequence}
		for offset := 0; offset < 4; offset++ {
			if index+offset < length {
				group = append(group, data[index+offset])
			} else {
				group = append(group, 0)
			}
		}
		groupCRC := crc8(group)
		encoded = append(encoded, groupCRC&0x7F|128, sequence|128)
		for offset := 0; offset < 4 && index+offset < length; offset++ {
			encoded = append(encoded, data[index+offset]|256)
		}
		sequence++
	}
	// O rabo marca o fim da transmissão para a lâmpada.
	for i := 0; i < length-lastGroupStart; i++ {
		encoded = append(encoded, 256)
	}
	return encoded
}

func smartConfigMulticastHead() []string {
	return bytesToMulticastAddresses([]int{'T', 'Y', 'S', 'T', '0', '1'}, 120)
}

func smartConfigMulticastBody(password, ssid, tokenGroup string) []string {
	addresses := bytesToMulticastAddresses(smartConfigEncodePlain(ssid), 64)
	addresses = append(addresses, bytesToMulticastAddresses(smartConfigEncodePassword(password), 0)...)
	return append(addresses, bytesToMulticastAddresses(smartConfigEncodePlain(tokenGroup), 32)...)
}

func smartConfigEncodePlain(value string) []int {
	bytes := make([]int, 0, len(value))
	for _, char := range value {
		bytes = append(bytes, int(char))
	}
	sum := crc32IEEE(bytes)
	encoded := []int{len(value), len(value)}
	for shift := 0; shift < 32; shift += 8 {
		encoded = append(encoded, int(sum>>shift)&0xFF)
	}
	return append(encoded, bytes...)
}

func smartConfigEncodePassword(password string) []int {
	bytes := make([]int, 0, len(password))
	for _, char := range password {
		bytes = append(bytes, int(char))
	}
	sum := crc32IEEE(bytes)
	encoded := []int{len(password), len(password)}
	for shift := 0; shift < 32; shift += 8 {
		encoded = append(encoded, int(sum>>shift)&0xFF)
	}
	encrypted := smartConfigEncryptPassword(password)
	for _, value := range encrypted {
		encoded = append(encoded, int(value))
	}
	return encoded
}

// smartConfigEncryptPassword usa AES-128-ECB com a chave fixa do formato,
// preenchendo com zeros até o bloco — igual ao aplicativo do fabricante.
func smartConfigEncryptPassword(password string) []byte {
	raw := []byte(password)
	padded := make([]byte, ((len(raw)+15)/16)*16)
	copy(padded, raw)
	block, err := aes.NewCipher([]byte(smartConfigPasswordKey))
	if err != nil {
		return padded
	}
	for index := 0; index < len(padded); index += aes.BlockSize {
		block.Encrypt(padded[index:index+aes.BlockSize], padded[index:index+aes.BlockSize])
	}
	return padded
}

func bytesToMulticastAddresses(data []int, startSequence int) []string {
	padded := append([]int{}, data...)
	if len(padded)%2 != 0 {
		padded = append(padded, 0)
	}
	addresses := make([]string, 0, len(padded)/2)
	sequence := startSequence
	for index := 0; index < len(padded); index += 2 {
		addresses = append(addresses, fmt.Sprintf("%d.%d.%d.%d", smartConfigMulticastIP, sequence, padded[index+1], padded[index]))
		sequence++
	}
	return addresses
}

func crc8(values []int) int {
	crc := 0
	for _, value := range values {
		crc = crc8Table[(crc^value)&0xFF]
	}
	return crc
}

var crc8Table = func() [256]int {
	var table [256]int
	for index := 0; index < 256; index++ {
		crc := index
		for bit := 0; bit < 8; bit++ {
			if crc&1 != 0 {
				crc = (crc >> 1) ^ 0x8C
			} else {
				crc >>= 1
			}
		}
		table[index] = crc & 0xFF
	}
	return table
}()

func crc32IEEE(values []int) uint32 {
	crc := uint32(0xFFFFFFFF)
	for _, value := range values {
		crc = (crc >> 8) ^ crc32Table[(crc^uint32(value))&0xFF]
	}
	return crc ^ 0xFFFFFFFF
}

var crc32Table = func() [256]uint32 {
	var table [256]uint32
	for index := 0; index < 256; index++ {
		crc := uint32(index)
		for bit := 0; bit < 8; bit++ {
			if crc&1 != 0 {
				crc = (crc >> 1) ^ 0xEDB88320
			} else {
				crc >>= 1
			}
		}
		table[index] = crc
	}
	return table
}()

// SendSmartConfig transmite o pareamento pela rede local até o contexto acabar.
// O servidor está no cabo e o ponto de acesso costuma repassar esses quadros
// para o ar de 2,4 GHz, que é onde a lâmpada escuta.
func SendSmartConfig(ctx context.Context, ssid, password, tokenGroup string, onRound func(int)) error {
	broadcastBody, multicastBody := SmartConfigFrames(ssid, password, tokenGroup)
	broadcastHead := smartConfigBroadcastHead()
	multicastHead := smartConfigMulticastHead()

	broadcastTargets := broadcastAddresses()
	multicastTargets := make([]*net.UDPAddr, 0, len(multicastBody))
	for _, address := range multicastBody {
		if parsed := net.ParseIP(address); parsed != nil {
			multicastTargets = append(multicastTargets, &net.UDPAddr{IP: parsed.To4(), Port: SmartConfigMulticastPort})
		}
	}
	multicastHeads := make([]*net.UDPAddr, 0, len(multicastHead))
	for _, address := range multicastHead {
		if parsed := net.ParseIP(address); parsed != nil {
			multicastHeads = append(multicastHeads, &net.UDPAddr{IP: parsed.To4(), Port: SmartConfigMulticastPort})
		}
	}

	broadcastSocket, err := net.ListenUDP("udp4", &net.UDPAddr{})
	if err != nil {
		return err
	}
	defer broadcastSocket.Close()
	multicastSocket, err := net.ListenUDP("udp4", &net.UDPAddr{})
	if err != nil {
		return err
	}
	defer multicastSocket.Close()

	round := 0
	for {
		if ctx.Err() != nil {
			return nil
		}
		round++
		if onRound != nil {
			onRound(round)
		}
		// O cabeçalho abre a transmissão; depois vêm os dados.
		for i := 0; i < 20; i++ {
			writeMulticast(multicastSocket, multicastHeads)
			writeBroadcast(broadcastSocket, broadcastHead, broadcastTargets)
		}
		for i := 0; i < 6; i++ {
			writeMulticast(multicastSocket, multicastHeads)
			writeMulticast(multicastSocket, multicastTargets)
			writeBroadcast(broadcastSocket, broadcastBody, broadcastTargets)
		}
		select {
		case <-ctx.Done():
			return nil
		case <-time.After(1500 * time.Millisecond):
		}
	}
}

// writeBroadcast envia um pacote por valor: o tamanho é o dado.
func writeBroadcast(socket *net.UDPConn, values []int, targets []string) {
	for _, value := range values {
		if value <= 0 {
			continue
		}
		payload := make([]byte, value)
		for _, target := range targets {
			address := net.ParseIP(target)
			if address == nil {
				continue
			}
			_, _ = socket.WriteToUDP(payload, &net.UDPAddr{IP: address.To4(), Port: SmartConfigBroadcastPort})
		}
	}
}

func writeMulticast(socket *net.UDPConn, targets []*net.UDPAddr) {
	payload := []byte{0}
	for _, target := range targets {
		_, _ = socket.WriteToUDP(payload, target)
	}
}

// SmartConfigTokenGroup junta as partes que a lâmpada guarda.
func SmartConfigTokenGroup(region, token, secret string) string {
	return strings.TrimSpace(region) + strings.TrimSpace(token) + strings.TrimSpace(secret)
}

// ParseMulticastSequence expõe a sequência de um endereço, útil em diagnóstico.
func ParseMulticastSequence(address string) int {
	parts := strings.Split(address, ".")
	if len(parts) != 4 {
		return -1
	}
	value, err := strconv.Atoi(parts[1])
	if err != nil {
		return -1
	}
	return value
}

// PackPlainQuery monta a consulta de estado no formato dos firmwares 3.1, que
// não exige chave: útil para descobrir o que um dispositivo fala.
func PackPlainQuery(payload []byte) ([]byte, error) {
	return packFrame(1, CmdDPQuery, payload, 3.1, nil)
}

// PlainPayload devolve o payload quando ele vem em texto puro (resposta 3.1).
func PlainPayload(frame []byte) string {
	message, err := unpackFrame(frame, 3.1, nil)
	if err != nil {
		return ""
	}
	text := string(message.payload)
	if len(text) > 0 && text[0] == '{' {
		return text
	}
	return ""
}
