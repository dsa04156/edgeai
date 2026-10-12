package runtime

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	api "github.com/kubeedge/api/apis/dmi/v1beta1"
	"github.com/kubeedge/mapper-framework/pkg/common"
	"google.golang.org/grpc"
	"io.edgeai/mapper/internal/dmi"
)

type configurationReports struct {
	reports
	observed chan *api.Twin
}

func (s *configurationReports) ReportDeviceStatus(_ context.Context, r *api.ReportDeviceStatusRequest) (*api.ReportDeviceStatusResponse, error) {
	for _, twin := range r.ReportedDevice.Twins {
		if twin.PropertyName == "temperature" {
			s.observed <- twin
		}
	}
	return &api.ReportDeviceStatusResponse{}, nil
}

func TestAppliedConfigurationIdentityTravelsWithCalibratedDMIObservation(t *testing.T) {
	hardware := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte(`{"temperature":20}`)) }))
	defer hardware.Close()
	host, portText, _ := net.SplitHostPort(strings.TrimPrefix(hardware.URL, "http://"))
	port, _ := strconv.Atoi(portText)
	socket := filepath.Join(t.TempDir(), "dmi.sock")
	listener, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	server := grpc.NewServer()
	sink := &configurationReports{reports: reports{states: make(chan string, 16)}, observed: make(chan *api.Twin, 16)}
	api.RegisterDeviceManagerServiceServer(server, sink)
	go server.Serve(listener)
	defer server.Stop()
	panel := New("edgeai-devices", "edgeai-virtual", dmi.Client{Socket: socket})
	defer panel.RemoveDevice("edgeai-devices/sensor")
	encode := func(v any) json.RawMessage {
		b, e := json.Marshal(v)
		if e != nil {
			t.Fatal(e)
		}
		return b
	}
	instance := func(identity string, scale int) *common.DeviceInstance {
		return &common.DeviceInstance{ID: "edgeai-devices/sensor", Name: "sensor", Namespace: "edgeai-devices",
			PProtocol: common.ProtocolConfig{ProtocolName: "edgeai-virtual", ConfigData: encode(map[string]any{"configData": map[string]any{"host": host, "port": port, "path": "/properties", "timeoutMs": 1000}})},
			Properties: []common.DeviceProperty{{Name: "temperature", CollectCycle: 100, ReportCycle: 5000,
				PProperty:  common.ModelProperty{DataType: "FLOAT"},
				Visitors:   encode(map[string]any{"configData": map[string]any{"jsonPath": "temperature", "dataType": "float32", "scale": scale, "offset": 0, "configurationSha256": identity}}),
				PushMethod: common.PushMethodConfig{MethodName: "mqtt", MethodConfig: encode(map[string]any{"address": "tcp://127.0.0.1:1", "topic": "test", "qos": 1})}}}}
	}
	for i, letter := range []string{"a", "b"} {
		identity := "sha256:" + strings.Repeat(letter, 64)
		panel.UpdateDev(nil, instance(identity, i+1))
		select {
		case twin := <-sink.observed:
			if twin.Reported.Value != strconv.Itoa(20*(i+1)) || twin.Reported.Metadata["edgeaiConfigurationSha256"] != identity || twin.Reported.Metadata["timestamp"] == "" {
				t.Fatalf("calibrated value and applied configuration diverged: %v", twin)
			}
		case <-time.After(8 * time.Second):
			t.Fatal("no calibrated DMI observation")
		}
	}
}
