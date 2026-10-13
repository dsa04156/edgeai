package runtime

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	api "github.com/kubeedge/api/apis/dmi/v1beta1"
	"github.com/kubeedge/mapper-framework/pkg/common"
	"google.golang.org/grpc"
	"io.edgeai/mapper/internal/dmi"
)

type configurationReports struct {
	reports
	observed          chan *api.Twin
	connectionReports atomic.Int32
}

func (s *configurationReports) ReportDeviceStatus(_ context.Context, r *api.ReportDeviceStatusRequest) (*api.ReportDeviceStatusResponse, error) {
	for _, twin := range r.ReportedDevice.Twins {
		if twin.PropertyName == "connection" {
			s.connectionReports.Add(1)
		}
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
	budget := filepath.Join(t.TempDir(), "budget")
	if err := os.WriteFile(budget, []byte(strconv.FormatInt(time.Now().Add(400*time.Millisecond).UnixMilli(), 10)), 0600); err != nil {
		t.Fatal(err)
	}
	panel := New("edgeai-devices", "edgeai-virtual", dmi.Client{Socket: socket, BudgetPath: budget, BudgetGap: 300 * time.Millisecond})
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
			if sink.connectionReports.Load() != 0 {
				t.Fatal("measurement device emitted redundant connection twin")
			}
			if ok, _ := regexp.MatchString(`^[a-zA-Z0-9-_.,:/@#]{1,512}$`, twin.Reported.Value); !ok {
				t.Fatal("reported value violates installed KubeEdge Twin charset/length")
			}
			payload, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(twin.Reported.Value, "edgeai.measurement/v1:"))
			if err != nil {
				t.Fatal(err)
			}
			var observation struct {
				ApiVersion    string `json:"apiVersion"`
				Value         string `json:"value"`
				Configuration string `json:"configurationSha256"`
				CollectedAt   int64  `json:"collectedAt"`
			}
			// KubeEdge 1.23 discards arbitrary DMI metadata and replaces timestamp.
			twin.Reported.Metadata = nil
			if err := json.Unmarshal(payload, &observation); err != nil {
				t.Fatal("measurement identity was lost when DMI metadata was dropped:", err)
			}
			if observation.ApiVersion != "edgeai.measurement/v1" || observation.Value != strconv.Itoa(20*(i+1)) || observation.Configuration != identity || observation.CollectedAt <= 0 {
				t.Fatalf("calibrated value and applied configuration diverged: %v", observation)
			}
			if age := time.Since(time.UnixMilli(observation.CollectedAt)); age > 350*time.Millisecond {
				t.Fatalf("DMI sent the queued sample instead of the latest collection: age=%v", age)
			}
		case <-time.After(8 * time.Second):
			t.Fatal("no calibrated DMI observation")
		}
	}
}
