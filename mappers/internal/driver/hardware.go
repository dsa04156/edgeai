package driver

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os/exec"
	"regexp"
	"sync"
	"time"
)

// A physical endpoint has one source reader, including during connection tests.
// Each consumer snapshots only newly received frames; constant values are valid
// samples, but a stalled source can never refresh the Twin or MQTT timestamps.
var hardwarePool = struct {
	sync.Mutex
	sources map[string]*hardwareSource
}{sources: map[string]*hardwareSource{}}
var serialPath = regexp.MustCompile(`^/dev/edgeai/[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}$`)
var hardwareID = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}$`)

type hardwareFrame struct {
	payload  []byte
	received time.Time
	sequence uint64
}
type hardwareSource struct {
	mu         sync.Mutex
	config     Connection
	frames     map[string]hardwareFrame
	sequence   uint64
	lastOrigin int64
	refs       int
	cancel     context.CancelFunc
	done       chan struct{}
}
type Hardware struct {
	source   *hardwareSource
	key      string
	config   Connection
	used     map[string]uint64
	snapshot map[string]hardwareFrame
	closed   bool
}

func newHardware(protocol string, c Connection) (*Hardware, error) {
	if !hardwareID.MatchString(c.DeviceID) || c.TimeoutMs < 100 || c.TimeoutMs > 5000 {
		return nil, fmt.Errorf("hardware device ID and timeout required")
	}
	if protocol == "edgeai-serial-json" {
		if !serialPath.MatchString(c.DevicePath) || !map[int]bool{9600: true, 19200: true, 38400: true, 57600: true, 115200: true}[c.BaudRate] {
			return nil, fmt.Errorf("invalid serial endpoint or baud rate")
		}
	} else if c.DevicePath != "/dev/i2c-1" {
		return nil, fmt.Errorf("Sense HAT v1 requires /dev/i2c-1")
	}
	key := protocol + ":" + c.DevicePath
	hardwarePool.Lock()
	defer hardwarePool.Unlock()
	s := hardwarePool.sources[key]
	if s != nil && (s.config.DeviceID != c.DeviceID || s.config.BaudRate != c.BaudRate) {
		return nil, fmt.Errorf("physical endpoint already has a different device configuration")
	}
	if s == nil {
		ctx, cancel := context.WithCancel(context.Background())
		s = &hardwareSource{config: c, frames: map[string]hardwareFrame{}, cancel: cancel, done: make(chan struct{})}
		hardwarePool.sources[key] = s
		go s.run(ctx, protocol)
	}
	s.refs++
	return &Hardware{source: s, key: key, config: c, used: map[string]uint64{}, snapshot: map[string]hardwareFrame{}}, nil
}

func (h *Hardware) Close() error {
	hardwarePool.Lock()
	if h.closed {
		hardwarePool.Unlock()
		return nil
	}
	h.closed = true
	h.source.refs--
	if h.source.refs != 0 {
		hardwarePool.Unlock()
		return nil
	}
	h.source.cancel()
	// Keep the endpoint reserved until its old reader has actually stopped.
	<-h.source.done
	delete(hardwarePool.sources, h.key)
	hardwarePool.Unlock()
	return nil
}

func (s *hardwareSource) run(ctx context.Context, protocol string) {
	defer close(s.done)
	for ctx.Err() == nil {
		if protocol == "edgeai-serial-json" {
			port, err := openHardwareSerial(ctx, s.config)
			if err == nil {
				s.scan(port, true)
				_ = port.Close()
			}
		} else {
			cmd := exec.CommandContext(ctx, "/usr/bin/python3", "-u", "/opt/edgeai/read_sensehat.py", "--device-id", s.config.DeviceID, "--interval", "0.2")
			stdout, err := cmd.StdoutPipe()
			if err == nil && cmd.Start() == nil {
				s.scan(stdout, false)
				_ = cmd.Process.Kill()
				_ = cmd.Wait()
			}
		}
		s.mu.Lock()
		s.frames = map[string]hardwareFrame{}
		s.mu.Unlock()
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Second):
		}
	}
}

func (s *hardwareSource) scan(input io.Reader, serial bool) {
	scanner := bufio.NewScanner(input)
	scanner.Buffer(make([]byte, 4096), 4096)
	for scanner.Scan() {
		s.accept(scanner.Bytes(), serial)
	}
}

func (s *hardwareSource) accept(payload []byte, serial bool) bool {
	var wire map[string]any
	dec := json.NewDecoder(bytes.NewReader(payload))
	dec.UseNumber()
	if dec.Decode(&wire) != nil || wire["device_id"] != s.config.DeviceID {
		return false
	}
	var trailing any
	if dec.Decode(&trailing) != io.EOF {
		return false
	}
	group := ""
	if serial {
		var ok bool
		group, ok = wire["sensor"].(string)
		if !ok || !hardwareID.MatchString(group) {
			return false
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if !serial {
		origin, ok := wire["origin"].(json.Number)
		if !ok {
			return false
		}
		n, err := origin.Int64()
		if err != nil || n <= s.lastOrigin {
			return false
		}
		s.lastOrigin = n
	}
	s.sequence++
	s.frames[group] = hardwareFrame{append([]byte(nil), payload...), time.Now(), s.sequence}
	return true
}

func (h *Hardware) BeginCycle(ctx context.Context, properties []Property) error {
	ctx, cancel := context.WithTimeout(ctx, time.Duration(h.config.TimeoutMs)*time.Millisecond)
	defer cancel()
	for {
		h.source.mu.Lock()
		frames := map[string]hardwareFrame{}
		complete := true
		for _, p := range properties {
			f, ok := h.source.frames[p.Sensor]
			if !ok || f.sequence <= h.used[p.Sensor] || time.Since(f.received) > 5*time.Second {
				complete = false
				break
			}
			frames[p.Sensor] = f
		}
		h.source.mu.Unlock()
		if complete && len(frames) > 0 {
			h.snapshot = frames
			for group, f := range frames {
				h.used[group] = f.sequence
			}
			return nil
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("fresh physical sample unavailable: %w", ctx.Err())
		case <-time.After(10 * time.Millisecond):
		}
	}
}

func (h *Hardware) Read(ctx context.Context, p Property) (string, error) {
	f, ok := h.snapshot[p.Sensor]
	if !ok {
		if err := h.BeginCycle(ctx, []Property{p}); err != nil {
			return "", err
		}
		f = h.snapshot[p.Sensor]
	}
	return jsonValue(f.payload, p)
}
