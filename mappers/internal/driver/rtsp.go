package driver

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"image/jpeg"
	"io"
	"net"
	"net/url"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// RTSP keeps video out of the numeric MQTT/Twin path. Only decoded-frame
// freshness is a device property; previews are bounded JPEG snapshots.
type RTSP struct {
	config  Connection
	ctx     context.Context
	cancel  context.CancelFunc
	once    sync.Once
	done    chan struct{}
	mu      sync.Mutex
	changed chan struct{}
	frame   []byte
	at      time.Time
}

func newRTSP(c Connection) (Reader, error) {
	p, err := url.Parse(c.Path)
	if err != nil || !strings.HasPrefix(c.Path, "/") || p.IsAbs() || p.Host != "" || p.User != nil || p.RawQuery != "" || p.Fragment != "" || len(c.Path) > 512 || strings.Contains(c.Path, "..") || strings.ContainsAny(c.Path, "\r\n") || !regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9.\-]*$`).MatchString(c.Host) {
		return nil, fmt.Errorf("anonymous RTSP host and absolute stream path required")
	}
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		return nil, fmt.Errorf("RTSP decoder is not installed")
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &RTSP{config: c, ctx: ctx, cancel: cancel, done: make(chan struct{}), changed: make(chan struct{})}, nil
}
func (r *RTSP) start()       { r.once.Do(func() { go r.run() }) }
func (r *RTSP) Close() error { r.start(); r.cancel(); <-r.done; return nil }
func (r *RTSP) Read(ctx context.Context, p Property) (string, error) {
	v, e := r.ReadObservation(ctx, p)
	return v.Value, e
}
func (r *RTSP) ReadObservation(ctx context.Context, p Property) (Observation, error) {
	if p.JSONPath != "connected" || p.DataType != "bool" || p.Scale != 1 || p.Offset != 0 {
		return Observation{}, fmt.Errorf("RTSP exposes only the connected boolean property")
	}
	_, at, err := r.Preview(ctx)
	if err != nil {
		return Observation{}, err
	}
	return Observation{Value: "true", ObservedAt: at.UnixMilli(), TimeBasis: "COLLECTED"}, nil
}
func (r *RTSP) Preview(ctx context.Context) ([]byte, time.Time, error) {
	r.start()
	ctx, cancel := context.WithTimeout(ctx, time.Duration(r.config.TimeoutMs)*time.Millisecond)
	defer cancel()
	for {
		if ctx.Err() != nil || r.ctx.Err() != nil {
			return nil, time.Time{}, fmt.Errorf("camera preview cancelled")
		}
		r.mu.Lock()
		frame, at, changed := r.frame, r.at, r.changed
		r.mu.Unlock()
		if !at.IsZero() && time.Since(at) <= time.Duration(r.config.TimeoutMs)*time.Millisecond {
			return append([]byte(nil), frame...), at, nil
		}
		select {
		case <-ctx.Done():
			return nil, time.Time{}, fmt.Errorf("fresh camera frame unavailable")
		case <-r.ctx.Done():
			return nil, time.Time{}, fmt.Errorf("camera closed")
		case <-changed:
		}
	}
}
func (r *RTSP) publish(frame []byte, at time.Time) {
	r.mu.Lock()
	r.frame = frame
	r.at = at
	close(r.changed)
	r.changed = make(chan struct{})
	r.mu.Unlock()
}
func (r *RTSP) run() {
	defer close(r.done)
	endpoint := (&url.URL{Scheme: "rtsp", Host: net.JoinHostPort(r.config.Host, strconv.Itoa(r.config.Port)), Path: r.config.Path}).String()
	for r.ctx.Err() == nil {
		cmd := exec.CommandContext(r.ctx, "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-rtsp_transport", "tcp", "-timeout", strconv.Itoa(r.config.TimeoutMs*1000), "-analyzeduration", "100000", "-probesize", "32768", "-i", endpoint, "-map", "0:v:0", "-an", "-vf", "fps=2,scale=640:640:force_original_aspect_ratio=decrease", "-threads", "1", "-filter_threads", "1", "-c:v", "mjpeg", "-f", "image2pipe", "-flush_packets", "1", "pipe:1")
		cmd.Stderr = io.Discard
		pipe, err := cmd.StdoutPipe()
		if err == nil {
			err = cmd.Start()
		}
		if err == nil {
			reader := bufio.NewReaderSize(pipe, 65536)
			for {
				frame, e := cameraJPEG(reader)
				if e != nil {
					break
				}
				r.publish(frame, time.Now())
			}
			_ = cmd.Process.Kill()
			_ = cmd.Wait()
		}
		r.publish(nil, time.Time{})
		timer := time.NewTimer(500 * time.Millisecond)
		select {
		case <-r.ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}
	}
}
func cameraJPEG(reader *bufio.Reader) ([]byte, error) {
	const limit = 4 << 20
	var data []byte
	for {
		chunk, err := reader.ReadSlice(0xd9)
		if len(data)+len(chunk) > limit {
			return nil, fmt.Errorf("camera preview exceeds limit")
		}
		data = append(data, chunk...)
		if len(data) >= 4 && bytes.Equal(data[:2], []byte{0xff, 0xd8}) && bytes.Equal(data[len(data)-2:], []byte{0xff, 0xd9}) {
			c, e := jpeg.DecodeConfig(bytes.NewReader(data))
			if e != nil || c.Width <= 0 || c.Height <= 0 || c.Width > 640 || c.Height > 640 {
				return nil, fmt.Errorf("invalid camera preview")
			}
			return data, nil
		}
		if err != nil && err != bufio.ErrBufferFull {
			return nil, err
		}
	}
}
