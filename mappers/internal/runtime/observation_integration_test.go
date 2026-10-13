package runtime

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
	api "github.com/kubeedge/api/apis/dmi/v1beta1"
	"github.com/kubeedge/mapper-framework/pkg/common"
	"golang.org/x/sys/unix"
	"google.golang.org/grpc"
	"io.edgeai/mapper/internal/dmi"
)

func mapperObservationBroker(t *testing.T) string {
	t.Helper()
	root, err := filepath.Abs("../../..")
	if err != nil {
		t.Fatal(err)
	}
	binary := os.Getenv("MAPPER_TEST_MOSQUITTO")
	if binary == "" {
		binary = filepath.Join(root, ".tools/mosquitto/usr/sbin/mosquitto")
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	file := filepath.Join(t.TempDir(), "broker.conf")
	if err = os.WriteFile(file, []byte(fmt.Sprintf("listener %d 127.0.0.1\nallow_anonymous true\npersistence false\n", port)), 0600); err != nil {
		t.Fatal(err)
	}
	command := exec.Command(binary, "-c", file)
	command.Env = append(os.Environ(), "LD_LIBRARY_PATH="+filepath.Join(root, ".tools/mosquitto/usr/lib/x86_64-linux-gnu"))
	command.Stdout, command.Stderr = io.Discard, io.Discard
	if err = command.Start(); err != nil {
		t.Fatal("real MQTT broker required", err)
	}
	t.Cleanup(func() { command.Process.Kill(); command.Wait() })
	address := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	for deadline := time.Now().Add(3 * time.Second); time.Now().Before(deadline); {
		connection, err := net.DialTimeout("tcp", address, 100*time.Millisecond)
		if err == nil {
			connection.Close()
			return "tcp://" + address
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("real MQTT broker did not start")
	return ""
}

func TestQualifiedMapperReadAndReportBudget(t *testing.T) {
	for _, scenario := range []struct {
		name                         string
		qualified, corrupt, heldLock bool
	}{
		{"legacy", false, false, false}, {"qualified", true, false, false}, {"session-failed", true, true, false}, {"session-locked", true, false, true},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			broker := mapperObservationBroker(t)
			var reads atomic.Int32
			observedAt := time.Now().Add(-time.Second).UnixMilli()
			hardware := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				reads.Add(1)
				fmt.Fprintf(w, `{"temperature":20,"measured":%d}`, observedAt)
			}))
			defer hardware.Close()
			host, portText, _ := net.SplitHostPort(strings.TrimPrefix(hardware.URL, "http://"))
			port, _ := strconv.Atoi(portText)
			directory := t.TempDir()
			socket := filepath.Join(directory, "dmi.sock")
			listener, err := net.Listen("unix", socket)
			if err != nil {
				t.Fatal(err)
			}
			server := grpc.NewServer()
			sink := &configurationReports{reports: reports{states: make(chan string, 32)}, observed: make(chan *api.Twin, 32)}
			api.RegisterDeviceManagerServiceServer(server, sink)
			go server.Serve(listener)
			defer server.Stop()
			budget := filepath.Join(directory, "budget")
			if scenario.heldLock {
				lock, err := os.OpenFile(budget+".observations.lock", os.O_CREATE|os.O_RDWR, 0600)
				if err != nil {
					t.Fatal(err)
				}
				defer lock.Close()
				if err := unix.Flock(int(lock.Fd()), unix.LOCK_EX); err != nil {
					t.Fatal(err)
				}
			}
			if scenario.corrupt {
				if err := os.WriteFile(budget+".observations", []byte("corrupt"), 0600); err != nil {
					t.Fatal(err)
				}
			}
			panel := New("edgeai-devices", "edgeai-virtual", dmi.Client{Socket: socket, BudgetPath: budget, BudgetGap: time.Millisecond})
			defer panel.RemoveDevice("edgeai-devices/sensor")
			type packet struct {
				topic    string
				retained bool
				payload  []byte
			}
			packets := make(chan packet, 64)
			client := mqtt.NewClient(mqtt.NewClientOptions().AddBroker(broker).SetClientID("mapper-observation-test").SetCleanSession(true))
			defer client.Disconnect(100)
			if token := client.Connect(); !token.WaitTimeout(3*time.Second) || token.Error() != nil {
				t.Fatal("subscriber connect failed")
			}
			if token := client.SubscribeMultiple(map[string]byte{"legacy/temperature": 1, "edgeai/observations/v1/edgeai-devices/sensor/temperature": 1}, func(_ mqtt.Client, m mqtt.Message) {
				packets <- packet{m.Topic(), m.Retained(), append([]byte(nil), m.Payload()...)}
			}); !token.WaitTimeout(3*time.Second) || token.Error() != nil {
				t.Fatal("subscriber setup failed")
			}
			encode := func(v any) json.RawMessage {
				b, err := json.Marshal(v)
				if err != nil {
					t.Fatal(err)
				}
				return b
			}
			identity := "sha256:" + strings.Repeat("a", 64)
			generation := ""
			if scenario.qualified {
				generation = "00000000-0000-4000-8000-000000000001"
			}
			instance := &common.DeviceInstance{ID: "edgeai-devices/sensor", Name: "sensor", Namespace: "edgeai-devices",
				PProtocol: common.ProtocolConfig{ProtocolName: "edgeai-virtual", ConfigData: encode(map[string]any{"configData": map[string]any{"host": host, "port": port, "path": "/properties", "timeoutMs": 1000}})},
				Properties: []common.DeviceProperty{{Name: "temperature", CollectCycle: 250, ReportCycle: 5000, PProperty: common.ModelProperty{DataType: "FLOAT"},
					Visitors:   encode(map[string]any{"configData": map[string]any{"jsonPath": "temperature", "timestampPath": "measured", "dataType": "float32", "scale": 2, "configurationSha256": identity, "sourceGeneration": generation, "unit": "°C"}}),
					PushMethod: common.PushMethodConfig{MethodName: "mqtt", MethodConfig: encode(map[string]any{"address": broker, "topic": "legacy/temperature", "qos": 1, "retained": false})}}}}
			panel.UpdateDev(nil, instance)
			rawCount, qualifiedCount := 0, 0
			validate := func(message packet) {
				if message.topic == "legacy/temperature" {
					var raw common.DataModel
					if err := json.Unmarshal(message.payload, &raw); err != nil {
						t.Fatal(err)
					}
					if raw.Value != "40" || raw.TimeStamp != observedAt {
						t.Fatalf("raw payload changed: %+v", raw)
					}
					rawCount++
				} else {
					var v struct {
						Generation    string  `json:"sourceGeneration"`
						Configuration string  `json:"configurationSha256"`
						ObservedAt    int64   `json:"observedAt"`
						TimeBasis     string  `json:"timeBasis"`
						Session       string  `json:"sessionId"`
						Sequence      int     `json:"sequence"`
						Value         float64 `json:"value"`
						Unit          string  `json:"unit"`
					}
					if err := json.Unmarshal(message.payload, &v); err != nil {
						t.Fatal(err)
					}
					qualifiedCount++
					if message.retained || v.Generation != generation || v.Configuration != identity || v.ObservedAt != observedAt || v.TimeBasis != "SOURCE" || v.Value != 40 || v.Unit != "°C" || v.Sequence != qualifiedCount {
						t.Fatalf("qualified payload changed identity/time/value: %+v", v)
					}
					counter, err := os.ReadFile(budget + ".observations")
					incarnation, _ := strconv.ParseInt(v.Session, 36, 64)
					if err != nil || string(counter) != strconv.FormatInt(incarnation, 10) {
						t.Fatal("session not durably allocated", err)
					}
				}
			}
			wantQualified := 0
			if scenario.qualified && !scenario.corrupt && !scenario.heldLock {
				wantQualified = 4
			}
			timer := time.NewTimer(5 * time.Second)
			defer timer.Stop()
			for rawCount < 4 || qualifiedCount < wantQualified {
				select {
				case message := <-packets:
					validate(message)
				case <-timer.C:
					t.Fatalf("publication stalled: raw=%d qualified=%d reads=%d", rawCount, qualifiedCount, reads.Load())
				}
			}
			if err := panel.RemoveDevice(instance.ID); err != nil {
				t.Fatal(err)
			}
			// Drain messages already published before the collector stopped.
			settle := time.NewTimer(100 * time.Millisecond)
			defer settle.Stop()
		Drain:
			for {
				select {
				case message := <-packets:
					validate(message)
				case <-settle.C:
					break Drain
				}
			}
			if rawCount != int(reads.Load()) || qualifiedCount != wantQualified {
				t.Fatalf("extra collection or publication: reads=%d raw=%d qualified=%d", reads.Load(), rawCount, qualifiedCount)
			}
			if len(sink.observed) != 1 || sink.connectionReports.Load() != 0 {
				t.Fatalf("redundant DMI report: twins=%d connection=%d", len(sink.observed), sink.connectionReports.Load())
			}
			twin := <-sink.observed
			payload, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(twin.Reported.Value, "edgeai.measurement/v1:"))
			if err != nil {
				t.Fatal(err)
			}
			var value struct {
				Value       string `json:"value"`
				CollectedAt int64  `json:"collectedAt"`
			}
			if err := json.Unmarshal(payload, &value); err != nil || value.Value != "40" || value.CollectedAt != observedAt {
				t.Fatal("original source observation lost in DMI")
			}
		})
	}
}
