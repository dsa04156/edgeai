package driver

import (
	"context"
	"fmt"
	"golang.org/x/sys/unix"
	"io"
	"strings"
	"testing"
	"time"
)

func TestHardwareFramesRequireFreshPhysicalSamples(t *testing.T) {
	s := &hardwareSource{config: Connection{DeviceID: "arduino-001"}, frames: map[string]hardwareFrame{}}
	h := &Hardware{source: s, config: Connection{TimeoutMs: 100}, used: map[string]uint64{}, snapshot: map[string]hardwareFrame{}}
	props := []Property{{Sensor: "acceleration", JSONPath: "x", DataType: "int32", Scale: 1}, {Sensor: "acceleration", JSONPath: "y", DataType: "int32", Scale: 1}, {Sensor: "temperature", JSONPath: "raw", DataType: "int32", Scale: 1}}
	if s.accept([]byte(`{"device_id":"wrong","sensor":"acceleration","x":99}`), true) {
		t.Fatal("wrong physical device accepted")
	}
	if s.accept([]byte(`{"device_id":"arduino-001","sensor":"temperature","raw":5} {}`), true) {
		t.Fatal("trailing JSON accepted")
	}
	s.accept([]byte(`{"device_id":"arduino-001","sensor":"acceleration","x":191,"y":175}`), true)
	if h.BeginCycle(context.Background(), props) == nil {
		t.Fatal("incomplete source became online")
	}
	s.accept([]byte(`{"device_id":"arduino-001","sensor":"temperature","raw":200}`), true)
	if err := h.BeginCycle(context.Background(), props); err != nil {
		t.Fatal(err)
	}
	for i, want := range []string{"191", "175", "200"} {
		got, err := h.Read(context.Background(), props[i])
		if err != nil || got != want {
			t.Fatalf("%d: %s %v", i, got, err)
		}
	}
	if h.BeginCycle(context.Background(), props) == nil {
		t.Fatal("stalled frames were marked fresh")
	}
	// Identical numerical values in newly received frames are still real samples.
	s.accept([]byte(`{"device_id":"arduino-001","sensor":"acceleration","x":191,"y":175}`), true)
	s.accept([]byte(`{"device_id":"arduino-001","sensor":"temperature","raw":200}`), true)
	if err := h.BeginCycle(context.Background(), props); err != nil {
		t.Fatal(err)
	}
}

func TestSenseHatSourceTimestampAndTypedValues(t *testing.T) {
	s := &hardwareSource{config: Connection{DeviceID: "sensehat-001"}, frames: map[string]hardwareFrame{}}
	for _, payload := range []string{`{"device_id":"sensehat-001","origin":0}`, `{"device_id":"sensehat-001"}`} {
		if s.accept([]byte(payload), false) {
			t.Fatal("invalid timestamp accepted")
		}
	}
	payload := []byte(`{"device_id":"sensehat-001","origin":123,"humidity":22.5}`)
	if !s.accept(payload, false) || s.accept(payload, false) {
		t.Fatal("timestamp replay accepted")
	}
	h := &Hardware{source: s, config: Connection{TimeoutMs: 100}, used: map[string]uint64{}, snapshot: map[string]hardwareFrame{}}
	got, err := h.Read(context.Background(), Property{JSONPath: "humidity", DataType: "float64", Scale: 1})
	if err != nil || got != "22.5" {
		t.Fatalf("%s %v", got, err)
	}
}

func TestHardwareObservationKeepsTheFrameReceiveTimeAcrossReads(t *testing.T) {
	s := &hardwareSource{config: Connection{DeviceID: "arduino-001"}, frames: map[string]hardwareFrame{}}
	s.accept([]byte(`{"device_id":"arduino-001","sensor":"temperature","raw":200}`), true)
	original := time.Now().Add(-time.Second)
	f := s.frames["temperature"]
	f.received = original
	s.frames["temperature"] = f
	h := &Hardware{source: s, config: Connection{TimeoutMs: 100}, used: map[string]uint64{}, snapshot: map[string]hardwareFrame{}}
	p := Property{Sensor: "temperature", JSONPath: "raw", DataType: "float64", Scale: 1}
	for range 2 {
		observation, err := ReadObservation(context.Background(), h, p)
		if err != nil || observation.ObservedAt != original.UnixMilli() || observation.TimeBasis != "RECEIVED" {
			t.Fatalf("frame was marked fresh: %+v %v", observation, err)
		}
	}
}

func TestHardwareConnectionTestsShareOnePhysicalReader(t *testing.T) {
	c := Connection{DeviceID: "arduino-001", DevicePath: "/dev/edgeai/unit-test-missing", BaudRate: 115200, TimeoutMs: 100}
	a, err := newHardware("edgeai-serial-json", c)
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	b, err := newHardware("edgeai-serial-json", c)
	if err != nil {
		t.Fatal(err)
	}
	if a.source != b.source {
		t.Fatal("connection test created a competing serial reader")
	}
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
	c.BaudRate = 9600
	if _, err := newHardware("edgeai-serial-json", c); err == nil {
		t.Fatal("conflicting serial configuration accepted")
	}
	c.DevicePath = "/dev/edgeai/../../etc/shadow"
	if _, err := newHardware("edgeai-serial-json", c); err == nil {
		t.Fatal("unsafe endpoint accepted")
	}
}

func TestSerialDriverReadsRealTTYAndCancels(t *testing.T) {
	fd, err := unix.Open("/dev/ptmx", unix.O_RDWR|unix.O_NOCTTY, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fd)
	if err := unix.IoctlSetPointerInt(fd, unix.TIOCSPTLCK, 0); err != nil {
		t.Fatal(err)
	}
	n, err := unix.IoctlGetInt(fd, unix.TIOCGPTN)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	port, err := openHardwareSerial(ctx, Connection{DevicePath: fmt.Sprintf("/dev/pts/%d", n), BaudRate: 115200})
	if err != nil {
		t.Fatal(err)
	}
	defer port.Close()
	if _, err := unix.Write(fd, []byte("{\"sensor\":\"temperature\",\"raw\":200}\n")); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 128)
	count, err := port.Read(buffer)
	if err != nil || !strings.Contains(string(buffer[:count]), `"raw":200`) {
		t.Fatalf("TTY read: %s %v", buffer[:count], err)
	}
	cancel()
	start := time.Now()
	_, err = port.Read(buffer)
	if err == nil || time.Since(start) > time.Second {
		t.Fatal("serial cancellation did not stop read")
	}
}

func TestFrameBoundRejectsOversizedSource(t *testing.T) {
	s := &hardwareSource{config: Connection{DeviceID: "arduino-001"}, frames: map[string]hardwareFrame{}}
	s.scan(io.LimitReader(strings.NewReader(strings.Repeat("x", 5000)+"\n"), 6000), true)
	if len(s.frames) != 0 {
		t.Fatal("oversized frame accepted")
	}
}
