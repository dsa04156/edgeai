package runtime

import (
	"context"
	"net"
	"path/filepath"
	"sync"
	"testing"
	"time"

	api "github.com/kubeedge/api/apis/dmi/v1beta1"
	"github.com/kubeedge/mapper-framework/pkg/common"
	"google.golang.org/grpc"
	"io.edgeai/mapper/internal/dmi"
)

type reports struct {
	api.UnimplementedDeviceManagerServiceServer
	states chan string
}

func (s *reports) ReportDeviceStates(_ context.Context, r *api.ReportDeviceStatesRequest) (*api.ReportDeviceStatesResponse, error) {
	s.states <- r.State
	return &api.ReportDeviceStatesResponse{}, nil
}
func TestConcurrentLifecycleAndInvalidConfigurationReportsOfflineOverDMI(t *testing.T) {
	socket := filepath.Join(t.TempDir(), "dmi.sock")
	listener, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	server := grpc.NewServer()
	sink := &reports{states: make(chan string, 40)}
	api.RegisterDeviceManagerServiceServer(server, sink)
	go server.Serve(listener)
	defer server.Stop()
	p := New("edgeai-devices", "edgeai-modbus-tcp", dmi.Client{Socket: socket})
	instance := common.DeviceInstance{ID: "edgeai-devices/sensor", Name: "sensor", Namespace: "edgeai-devices"}
	// Malformed/missing connection data is deliberately injected through lifecycle callbacks.
	var workers sync.WaitGroup
	for i := 0; i < 20; i++ {
		workers.Add(1)
		go func() { defer workers.Done(); p.UpdateDev(nil, &instance) }()
	}
	workers.Wait()
	select {
	case state := <-sink.states:
		if state != "offline" {
			t.Fatalf("state %s", state)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no DMI OFFLINE report")
	}
	if len(p.Snapshot()) != 1 {
		t.Fatal("duplicate managed device")
	}
	if err := p.RemoveDevice(instance.ID); err != nil {
		t.Fatal(err)
	}
	if len(p.Snapshot()) != 0 {
		t.Fatal("deleted collector remains")
	}
	select {
	case <-sink.states:
		t.Fatal("duplicate collector reported state")
	default:
	}
}
