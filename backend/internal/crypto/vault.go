package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"os"
)

type Vault struct{ aead cipher.AEAD }

func LoadOrCreate(path string) (*Vault, error) {
	key, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		key = make([]byte, 32)
		if _, err = rand.Read(key); err != nil {
			return nil, err
		}
		if err = os.WriteFile(path, key, 0o600); err != nil {
			return nil, err
		}
	} else if err != nil {
		return nil, err
	}
	if len(key) != 32 {
		return nil, fmt.Errorf("master key must contain 32 bytes")
	}
	// A chave já existente não é revalidada na criação: um backup ou uma
	// ferramenta externa pode tê-la deixado legível por outros usuários.
	if err = os.Chmod(path, 0o600); err != nil {
		return nil, fmt.Errorf("restrict master key permissions: %w", err)
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &Vault{aead: aead}, nil
}

func (v *Vault) EncryptString(plain string) ([]byte, error) {
	nonce := make([]byte, v.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	return v.aead.Seal(nonce, nonce, []byte(plain), nil), nil
}

func (v *Vault) DecryptString(sealed []byte) (string, error) {
	n := v.aead.NonceSize()
	if len(sealed) < n {
		return "", fmt.Errorf("invalid ciphertext")
	}
	plain, err := v.aead.Open(nil, sealed[:n], sealed[n:], nil)
	if err != nil {
		return "", err
	}
	return string(plain), nil
}

func RandomToken(bytes int) (string, error) {
	buf := make([]byte, bytes)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(buf), nil
}

func Hash(value string) []byte {
	sum := sha256.Sum256([]byte(value))
	return sum[:]
}
