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
