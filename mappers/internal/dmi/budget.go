package dmi

import (
	"context"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"golang.org/x/sys/unix"
)

// KubeEdge 1.23 shares a one-request/second limiter across all Mapper RPCs.
// A host-mounted lock coordinates independent protocol Mapper processes on a node.
// No Kubernetes credentials or high-rate device data are written to this file.
func waitBudget(ctx context.Context, path string, gap time.Duration) error {
	if path == "" {
		return nil
	}
	if gap == 0 {
		gap = 1100 * time.Millisecond
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return fmt.Errorf("DMI budget unavailable")
	}
	defer f.Close()
	for {
		if err = ctx.Err(); err != nil {
			return err
		}
		err = unix.Flock(int(f.Fd()), unix.LOCK_EX|unix.LOCK_NB)
		delay := 20 * time.Millisecond
		if err == nil {
			b := make([]byte, 32)
			n, _ := f.ReadAt(b, 0)
			last, _ := strconv.ParseInt(strings.TrimSpace(string(b[:n])), 10, 64)
			now := time.Now()
			// Reserve a future slot once, in lock acquisition order. Competing
			// collectors must not race for every slot and starve another device.
			slot := time.UnixMilli(last).Add(gap)
			if slot.Before(now) {
				slot = now
			}
			if err = f.Truncate(0); err == nil {
				_, err = f.WriteAt([]byte(strconv.FormatInt(slot.UnixMilli(), 10)), 0)
			}
			_ = unix.Flock(int(f.Fd()), unix.LOCK_UN)
			if err != nil {
				return fmt.Errorf("DMI budget write failed")
			}
			timer := time.NewTimer(time.Until(slot))
			defer timer.Stop()
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-timer.C:
				return nil
			}
		} else if err != unix.EWOULDBLOCK && err != unix.EAGAIN {
			return fmt.Errorf("DMI budget lock failed")
		}
		timer := time.NewTimer(delay)
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
	}
}
