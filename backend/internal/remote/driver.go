package remote

import (
	"context"
	"errors"
)

// Driver is the provider-neutral boundary between the remote domain and a
// concrete device protocol. The smart-TV WebSocket channel is the first
// implementation.
type Driver interface {
	Available() bool
	// Pair connects without a token and waits for the TV to authorize this
	// controller, returning the token the TV issued.
	Pair(context.Context, Device) (string, error)
	// Send forwards one remote key using a previously stored token.
	Send(context.Context, Device, string, string) error
	// SendText types text into the TV's focused field.
	SendText(context.Context, Device, string, string) error
	// DeviceMAC reads the device network address used for Wake-on-LAN.
	DeviceMAC(context.Context, Device) (string, error)
	// Reachable reports whether the device is powered on and answering.
	Reachable(context.Context, Device) bool
}

var ErrNoRemoteAdapter = errors.New("no remote control adapter is installed")

// UnavailableDriver keeps the API and UI stable while no adapter is enabled.
type UnavailableDriver struct{}

func NewUnavailableDriver() *UnavailableDriver { return &UnavailableDriver{} }

func (*UnavailableDriver) Available() bool { return false }

func (*UnavailableDriver) Pair(context.Context, Device) (string, error) {
	return "", ErrNoRemoteAdapter
}

func (*UnavailableDriver) Send(context.Context, Device, string, string) error {
	return ErrNoRemoteAdapter
}

func (*UnavailableDriver) SendText(context.Context, Device, string, string) error {
	return ErrNoRemoteAdapter
}

func (*UnavailableDriver) DeviceMAC(context.Context, Device) (string, error) {
	return "", ErrNoRemoteAdapter
}

func (*UnavailableDriver) Reachable(context.Context, Device) bool { return false }
