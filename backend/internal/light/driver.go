package light

import (
	"context"
	"errors"
)

// Driver is the provider-neutral boundary between the lighting domain and a
// concrete local protocol. Device-specific adapters live behind this contract.
type Driver interface {
	Available() bool
	Discover(context.Context, Light, Credentials) (string, error)
	Status(context.Context, Light, Credentials) (State, error)
	Apply(context.Context, Light, Credentials, StatePatch) (State, error)
}

var ErrNoLightingAdapter = errors.New("no local lighting adapter is installed")

// Registration errors. A lamp that answered and refused the key is a different
// problem from one that never answered, and the administrator should see which
// one happened.
var (
	// ErrLightKeyRefused marks a device that answered on the network but did not
	// accept the local key.
	ErrLightKeyRefused = errors.New("the light refused the local key")
	// ErrLightUnreachable marks a device that did not answer at the address.
	ErrLightUnreachable = errors.New("the light did not answer on the network")
	// ErrInvalidRegistration marks input the administrator must fix (a missing
	// address, a key that is not 16 characters, a duplicate device).
	ErrInvalidRegistration = errors.New("invalid light registration")
)

// UnavailableDriver keeps the generic lighting API and UI stable while no
// vendor-specific adapter is enabled. It deliberately performs no cloud or LAN
// discovery.
type UnavailableDriver struct{}

func NewUnavailableDriver() *UnavailableDriver { return &UnavailableDriver{} }

func (*UnavailableDriver) Available() bool { return false }

func (*UnavailableDriver) Discover(context.Context, Light, Credentials) (string, error) {
	return "", ErrNoLightingAdapter
}

func (*UnavailableDriver) Status(context.Context, Light, Credentials) (State, error) {
	return State{}, ErrNoLightingAdapter
}

func (*UnavailableDriver) Apply(context.Context, Light, Credentials, StatePatch) (State, error) {
	return State{}, ErrNoLightingAdapter
}
