package dmi

import (
	"errors"
	"sync"
	"time"
)

// SocketError identifies failure to establish the DMI transport, separately
// from a live server's rate limit or invalid device/configuration response.
type SocketError struct{ Cause error }

func (e *SocketError) Error() string { return "DMI socket connection: " + e.Cause.Error() }
func (e *SocketError) Unwrap() error { return e.Cause }

// RecoveryHealth allows brief EdgeCore outages. Sustained dial failures make
// liveness fail so the container restarts and rebinds a replaced hostPath socket.
// Restarting only the gRPC connection cannot refresh a file bind mount's inode.
type RecoveryHealth struct {
	mu          sync.Mutex
	failedSince time.Time
}

const SocketRecoveryGrace = 90 * time.Second

func (h *RecoveryHealth) Record(err error, now time.Time) {
	h.mu.Lock()
	defer h.mu.Unlock()
	var socketErr *SocketError
	if errors.As(err, &socketErr) {
		if h.failedSince.IsZero() {
			h.failedSince = now
		}
	} else {
		// Any response from the server proves that the mount is usable, even
		// when the response cannot synchronize a particular device.
		h.failedSince = time.Time{}
	}
}

func (h *RecoveryHealth) Healthy(now time.Time) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.failedSince.IsZero() || now.Sub(h.failedSince) < SocketRecoveryGrace
}
