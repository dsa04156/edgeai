package runtime

import (
	"context"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"golang.org/x/sys/unix"
)

// Allocation is independent of collection/publication/DMI so even a held lock
// or slow durable write cannot delay the existing telemetry path. At most one
// allocator runs per collector; retries are sequential and cancellation-aware.
func observationSession(ctx context.Context, budgetPath string) <-chan string {
	result := make(chan string, 1)
	if budgetPath == "" {
		return result
	}
	go func() {
		for ctx.Err() == nil {
			session, err := nextObservationSession(ctx, budgetPath+".observations", time.Now().UnixNano())
			if err == nil {
				select {
				case result <- session:
				case <-ctx.Done():
				}
				return
			}
			timer := time.NewTimer(time.Second)
			select {
			case <-ctx.Done():
				timer.Stop()
				return
			case <-timer.C:
			}
		}
	}()
	return result
}

// The existing node-persistent Mapper callback mount stores one counter shared
// by protocol processes. A separate lock survives atomic counter replacement.
// Advancing before returning prevents reuse after a crash or backward clock step.
func nextObservationSession(ctx context.Context, path string, now int64) (string, error) {
	if !filepath.IsAbs(path) || now <= 0 {
		return "", fmt.Errorf("persistent observation session state required")
	}
	lock, err := os.OpenFile(path+".lock", os.O_CREATE|os.O_RDWR|unix.O_NOFOLLOW, 0600)
	if err != nil {
		return "", fmt.Errorf("observation session lock unavailable")
	}
	defer lock.Close()
	for {
		if err := ctx.Err(); err != nil {
			return "", err
		}
		err = unix.Flock(int(lock.Fd()), unix.LOCK_EX|unix.LOCK_NB)
		if err == nil {
			break
		}
		if err != unix.EWOULDBLOCK && err != unix.EAGAIN {
			return "", fmt.Errorf("observation session lock failed")
		}
		timer := time.NewTimer(5 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return "", ctx.Err()
		case <-timer.C:
		}
	}
	defer unix.Flock(int(lock.Fd()), unix.LOCK_UN)
	last := int64(0)
	state, err := os.OpenFile(path, os.O_RDONLY|unix.O_NOFOLLOW, 0)
	if err == nil {
		data, readErr := io.ReadAll(io.LimitReader(state, 33))
		state.Close()
		last, err = strconv.ParseInt(string(data), 10, 64)
		if readErr != nil || err != nil || last <= 0 || strconv.FormatInt(last, 10) != string(data) {
			return "", fmt.Errorf("observation session state corrupt")
		}
	} else if !os.IsNotExist(err) {
		return "", fmt.Errorf("observation session state unavailable")
	}
	if last == math.MaxInt64 {
		return "", fmt.Errorf("observation session counter exhausted")
	}
	if now <= last {
		now = last + 1
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".observation-session-*")
	if err != nil {
		return "", fmt.Errorf("observation session write unavailable")
	}
	defer os.Remove(temporary.Name())
	if _, err = temporary.WriteString(strconv.FormatInt(now, 10)); err == nil {
		err = temporary.Sync()
	}
	closeErr := temporary.Close()
	if err != nil || closeErr != nil {
		return "", fmt.Errorf("observation session write failed")
	}
	if err = os.Rename(temporary.Name(), path); err != nil {
		return "", fmt.Errorf("observation session commit failed")
	}
	directory, err := os.Open(filepath.Dir(path))
	if err != nil {
		return "", fmt.Errorf("observation session directory unavailable")
	}
	err = directory.Sync()
	directory.Close()
	if err != nil {
		return "", fmt.Errorf("observation session directory commit failed")
	}
	return strconv.FormatInt(now, 36), nil
}
