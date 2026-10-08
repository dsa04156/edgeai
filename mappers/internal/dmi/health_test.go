package dmi

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"
)

func TestSocketOutageGetsGraceThenRequiresContainerRecycle(t *testing.T) {
	var h RecoveryHealth
	start := time.Unix(1000, 0)
	if !h.Healthy(start) {
		t.Fatal("new process should be live before registration")
	}
	h.Record(fmt.Errorf("register: %w", &SocketError{Cause: context.DeadlineExceeded}), start)
	h.Record(&SocketError{Cause: context.DeadlineExceeded}, start.Add(60*time.Second))
	if !h.Healthy(start.Add(89 * time.Second)) {
		t.Fatal("brief EdgeCore outage should not restart the container")
	}
	if h.Healthy(start.Add(SocketRecoveryGrace)) {
		t.Fatal("repeated dial failures must not reset the recovery clock")
	}
	h.Record(nil, start.Add(91*time.Second))
	if !h.Healthy(start.Add(92 * time.Second)) {
		t.Fatal("successful reconnect must cancel recycling")
	}
	// A later outage receives its own grace period.
	h.Record(&SocketError{Cause: context.DeadlineExceeded}, start.Add(100*time.Second))
	if !h.Healthy(start.Add(101 * time.Second)) {
		t.Fatal("recovered outage must not poison later connection attempts")
	}
}

func TestLiveDMIResponseErrorsDoNotRecycleCollectors(t *testing.T) {
	var h RecoveryHealth
	start := time.Unix(1000, 0)
	h.Record(&SocketError{Cause: context.DeadlineExceeded}, start)
	h.Record(errors.New("DMI rate limit / invalid device"), start.Add(60*time.Second))
	if !h.Healthy(start.Add(10 * time.Minute)) {
		t.Fatal("a usable socket should not recycle collectors for server response errors")
	}
}
