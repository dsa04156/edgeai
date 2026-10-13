package dmi

import (
	"context"
	"errors"
	"net"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	api "github.com/kubeedge/api/apis/dmi/v1beta1"
	"google.golang.org/grpc"
)

type registrationServer struct {
	api.UnimplementedDeviceManagerServiceServer
}

type twinServer struct {
	api.UnimplementedDeviceManagerServiceServer
	mu         sync.Mutex
	properties []string
	state      string
}

func (s *twinServer) ReportDeviceStates(_ context.Context, r *api.ReportDeviceStatesRequest) (*api.ReportDeviceStatesResponse, error) {
	s.state = r.State
	return &api.ReportDeviceStatesResponse{}, nil
}
func (s *twinServer) ReportDeviceStatus(_ context.Context, r *api.ReportDeviceStatusRequest) (*api.ReportDeviceStatusResponse, error) {
	if len(r.ReportedDevice.Twins) != 1 {
		return nil, errors.New("unsafe concurrent Twin fanout")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.properties = append(s.properties, r.ReportedDevice.Twins[0].PropertyName)
	return &api.ReportDeviceStatusResponse{}, nil
}
func TestTwinReportsUseIndividuallyBudgetedRpcAndCancellation(t *testing.T) {
	socket := filepath.Join(t.TempDir(), "dmi.sock")
	listener, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	server := grpc.NewServer()
	s := &twinServer{}
	api.RegisterDeviceManagerServiceServer(server, s)
	go server.Serve(listener)
	defer server.Stop()
	twins := []*api.Twin{{PropertyName: "connection"}, {PropertyName: "humidity"}, {PropertyName: "pitch"}}
	c := Client{Socket: socket, BudgetPath: filepath.Join(t.TempDir(), "budget"), BudgetGap: time.Millisecond}
	if err := c.Report("edgeai-devices", "sensehat-001", "online", twins); err != nil {
		t.Fatal(err)
	}
	s.mu.Lock()
	got := append([]string(nil), s.properties...)
	s.mu.Unlock()
	if len(got) != 3 || got[0] != "connection" || got[2] != "pitch" {
		t.Fatal(got)
	}
	if err := c.ReportLatestContext(context.Background(), "edgeai-devices", "sensehat-001", "online", twins, func(string) *api.Twin { return nil }); err != nil {
		t.Fatal(err)
	}
	s.mu.Lock()
	count := len(s.properties)
	s.mu.Unlock()
	if count != len(got) {
		t.Fatal("queued Twin was sent after the latest collection became unavailable")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if c.ReportContext(ctx, "edgeai-devices", "sensehat-001", "offline", twins) == nil {
		t.Fatal("cancelled report succeeded")
	}
}

func (registrationServer) MapperRegister(context.Context, *api.MapperRegisterRequest) (*api.MapperRegisterResponse, error) {
	return &api.MapperRegisterResponse{}, nil
}

func TestReplacedSocketNeedsFreshMountAndDialFailuresAreClassified(t *testing.T) {
	dir := t.TempDir()
	source := filepath.Join(dir, "dmi.sock")
	mounted := filepath.Join(dir, "mounted.sock")
	serve := func() *grpc.Server {
		listener, err := net.Listen("unix", source)
		if err != nil {
			t.Fatal(err)
		}
		server := grpc.NewServer()
		api.RegisterDeviceManagerServiceServer(server, registrationServer{})
		go server.Serve(listener)
		t.Cleanup(server.Stop)
		return server
	}
	first := serve()
	// A hard link models the inode pinned by a single-file hostPath bind mount.
	if err := os.Link(source, mounted); err != nil {
		t.Fatal(err)
	}
	client := Client{Socket: mounted}
	if _, _, err := client.Register(); err != nil {
		t.Fatal(err)
	}
	first.Stop()
	serve()
	if _, _, err := (Client{Socket: source}).Register(); err != nil {
		t.Fatal("replacement DMI server should be usable", err)
	}
	_, _, err := client.Register()
	var socketErr *SocketError
	if !errors.As(err, &socketErr) {
		t.Fatal("old inode should fail specifically at the transport boundary", err)
	}
	var health RecoveryHealth
	start := time.Now()
	health.Record(err, start)
	if health.Healthy(start.Add(SocketRecoveryGrace)) {
		t.Fatal("persistent stale mount must trigger recycling")
	}
	if err := os.Remove(mounted); err != nil {
		t.Fatal(err)
	}
	if err := os.Link(source, mounted); err != nil {
		t.Fatal(err)
	}
	_, _, err = client.Register()
	health.Record(err, start.Add(SocketRecoveryGrace))
	if err != nil || !health.Healthy(start.Add(SocketRecoveryGrace)) {
		t.Fatal("rebinding the fresh socket should recover registration and liveness", err)
	}
}
