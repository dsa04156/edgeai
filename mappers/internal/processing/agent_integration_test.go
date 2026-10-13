package processing

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	mqtt "github.com/eclipse/paho.mqtt.golang"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func realBroker(t *testing.T) string {
	t.Helper()
	root, err := filepath.Abs("../../..")
	if err != nil {
		t.Fatal(err)
	}
	binary := filepath.Join(root, ".tools/mosquitto/usr/sbin/mosquitto")
	if path := os.Getenv("MAPPER_TEST_MOSQUITTO"); path != "" {
		binary = path
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	file := filepath.Join(t.TempDir(), "mosquitto.conf")
	if err = os.WriteFile(file, []byte(fmt.Sprintf("listener %d 127.0.0.1\nallow_anonymous true\npersistence false\n", port)), 0600); err != nil {
		t.Fatal(err)
	}
	command := exec.Command(binary, "-c", file)
	command.Env = append(os.Environ(), "LD_LIBRARY_PATH="+filepath.Join(root, ".tools/mosquitto/usr/lib/x86_64-linux-gnu"))
	var logs bytes.Buffer
	command.Stdout = &logs
	command.Stderr = &logs
	if err = command.Start(); err != nil {
		t.Fatalf("real MQTT broker required: %v", err)
	}
	t.Cleanup(func() { command.Process.Kill(); command.Wait() })
	address := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		connection, e := net.DialTimeout("tcp", address, 100*time.Millisecond)
		if e == nil {
			connection.Close()
			return "tcp://" + address
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("MQTT broker did not start: %s", logs.String())
	return ""
}

func TestAgentRunsFromRealMqttThroughEngineCallbackToBackendResults(t *testing.T) {
	testAgentEndToEnd(t, false)
}

func TestAgentWithRealEkuiper242(t *testing.T) {
	testAgentEndToEnd(t, true)
}
func TestAgentAcceptsMoreThan64MapperSessionsWithoutReplayingOldSessions(t *testing.T) {
	testAgentEndToEnd(t, false, 70)
}

func testAgentEndToEnd(t *testing.T, actualEngine bool, restartCounts ...int) {
	restartCount := 0
	if len(restartCounts) > 0 {
		restartCount = restartCounts[0]
	}
	broker := realBroker(t)
	pod := "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	vd := "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
	generation := "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
	sourceGeneration := "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
	uid := "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
	input := Input{DeviceID: "temperature", DeviceUID: uid, DeviceModel: "temperature-model", Namespace: "kubeedge", Property: "temperature", SourceGeneration: sourceGeneration, ConfigurationSHA256: "sha256:" + strings.Repeat("a", 64), Type: "float64", Unit: "°C", Broker: broker, Topic: "edgeai/observations/v1/kubeedge/temperature/temperature", Alias: "a", Column: "x0", MaxAgeSeconds: 30}
	ruleID := "edgeai_vd_" + strings.ReplaceAll(vd, "-", "") + "_" + strings.ReplaceAll(generation, "-", "") + "_value"
	execution := Execution{VDID: vd, Generation: generation, Running: true, Sources: []Input{input}, IntervalSeconds: 1, RetentionDays: 7, Rules: []Rule{{ID: ruleID, OutputID: "value", SQL: "SELECT vdId,generation,evaluationId,evaluationAt,sequence,cast(x0,\"float\") AS value FROM edgeai_vd_frames WHERE vdId='" + vd + "' AND generation='" + generation + "'", Type: "number", Unit: "°C", WarmupTicks: 1}}}
	if actualEngine {
		metadata := []string{}
		for _, field := range []string{"vdId", "generation", "evaluationId", "evaluationAt", "sequence"} {
			alias := field
			if field == "vdId" {
				alias = "frameVdId"
			}
			if field == "generation" {
				alias = "frameGeneration"
			}
			metadata = append(metadata, "LAST_VALUE("+field+",false) AS "+alias)
		}
		for _, operation := range []string{"AVG", "MIN", "MAX"} {
			name := strings.ToLower(operation)
			execution.Rules = append(execution.Rules, Rule{ID: ownedID(execution, name), OutputID: name, SQL: "SELECT " + strings.Join(metadata, ",") + "," + operation + "(cast(x0,\"float\")) AS value FROM edgeai_vd_frames GROUP BY COUNTWINDOW(3,1) FILTER(WHERE vdId='" + vd + "' AND generation='" + generation + "') HAVING COUNT(*)=3", Type: "number", Unit: "°C", WarmupTicks: 3})
		}
		execution.Rules = append(execution.Rules, Rule{ID: ownedID(execution, "threshold"), OutputID: "threshold", SQL: "SELECT vdId,generation,evaluationId,evaluationAt,sequence,(cast(x0,\"float\") > 20) AS value FROM edgeai_vd_frames WHERE vdId='" + vd + "' AND generation='" + generation + "'", Type: "boolean", WarmupTicks: 1}, Rule{ID: ownedID(execution, "filtered"), OutputID: "filtered", SQL: "SELECT vdId,generation,evaluationId,evaluationAt,sequence,cast(x0,\"float\") AS value FROM edgeai_vd_frames WHERE vdId='" + vd + "' AND generation='" + generation + "' AND cast(x0,\"float\")>100", Type: "number", WarmupTicks: 1, Conditional: true})
	}
	results := make(chan Result, 16)
	var restartReady atomic.Bool
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer processing-test-credential-0123456789" || r.Header.Get("X-EdgeAI-Pod-Token") != "projected-test-token" {
			w.WriteHeader(401)
			return
		}
		var body map[string]json.RawMessage
		if json.NewDecoder(r.Body).Decode(&body) != nil {
			w.WriteHeader(400)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/internal/v1/processing/poll":
			var session string
			json.Unmarshal(body["sessionId"], &session)
			planned := execution
			if restartCount > 0 && !restartReady.Load() {
				planned.Running = false
				planned.Rules = []Rule{}
				planned.Sources = []Input{}
			}
			json.NewEncoder(w).Encode(Plan{APIVersion: "edgeai.processing-plan/v1", EngineID: "central", EnginePodUID: pod, SessionID: session, LeaseExpiresAt: time.Now().Add(15 * time.Second), ServerTime: time.Now(), Inputs: []Input{input}, Executions: []Execution{planned}})
		case "/internal/v1/processing/results":
			var batch []Result
			json.Unmarshal(body["results"], &batch)
			for _, result := range batch {
				select {
				case results <- result:
				default:
				}
			}
			json.NewEncoder(w).Encode(map[string]any{"accepted": len(batch), "newResults": len(batch), "historyStored": true, "replay": false})
		default:
			w.WriteHeader(409)
		}
	}))
	defer backend.Close()
	var rulesMu sync.Mutex
	rules := map[string]map[string]any{}
	kuiper := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		rulesMu.Lock()
		defer rulesMu.Unlock()
		if r.URL.Path == "/streams" && r.Method == "GET" {
			fmt.Fprint(w, `[]`)
			return
		}
		if r.URL.Path == "/rules" {
			if r.Method == "GET" {
				ids := []string{}
				for id := range rules {
					ids = append(ids, id)
				}
				json.NewEncoder(w).Encode(ids)
				return
			}
			var rule map[string]any
			json.NewDecoder(r.Body).Decode(&rule)
			rules[rule["id"].(string)] = rule
			w.WriteHeader(201)
			fmt.Fprint(w, `{}`)
			return
		}
		if strings.HasPrefix(r.URL.Path, "/rules/") {
			id := strings.Split(strings.TrimPrefix(r.URL.Path, "/rules/"), "/")[0]
			rule, ok := rules[id]
			if !ok {
				w.WriteHeader(404)
				return
			}
			if r.Method == "DELETE" {
				delete(rules, id)
				fmt.Fprint(w, `{}`)
				return
			}
			if strings.HasSuffix(r.URL.Path, "/status") {
				fmt.Fprint(w, `{"status":"running"}`)
				return
			}
			json.NewEncoder(w).Encode(rule)
			return
		}
		if r.URL.Path == "/streams/edgeai_vd_frames" && r.Method == "GET" {
			w.WriteHeader(404)
			return
		}
		fmt.Fprint(w, `{}`)
	}))
	defer kuiper.Close()
	push := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var frame map[string]any
		json.NewDecoder(r.Body).Decode(&frame)
		rulesMu.Lock()
		rule := rules[ruleID]
		rulesMu.Unlock()
		if rule == nil {
			w.WriteHeader(503)
			return
		}
		action := rule["actions"].([]any)[0].(map[string]any)["rest"].(map[string]any)
		frame["value"] = frame["x0"]
		delete(frame, "x0")
		payload, _ := json.Marshal(frame)
		request, _ := http.NewRequest("POST", action["url"].(string), bytes.NewReader(payload))
		request.Header.Set("Content-Type", "application/json")
		for key, value := range action["headers"].(map[string]any) {
			request.Header.Set(key, value.(string))
		}
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			w.WriteHeader(503)
			return
		}
		io.Copy(io.Discard, response.Body)
		response.Body.Close()
		w.WriteHeader(200)
	}))
	defer push.Close()
	credential := filepath.Join(t.TempDir(), "credential")
	os.WriteFile(credential, []byte("processing-test-credential-0123456789"), 0600)
	token := filepath.Join(t.TempDir(), "token")
	os.WriteFile(token, []byte("projected-test-token"), 0600)
	kuiperURL, pushURL := kuiper.URL, push.URL+"/edgeai/evaluate"
	if actualEngine {
		kuiperURL, pushURL = realEngine(t)
	}
	agent, err := New(Config{BackendURL: backend.URL, KuiperURL: kuiperURL, PushURL: pushURL, CallbackAddress: "127.0.0.1:0", HealthAddress: "127.0.0.1:0", CredentialFile: credential, PodTokenFile: token, PodUID: pod})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	finished := make(chan error, 1)
	go func() { finished <- agent.Run(ctx) }()
	publisher := mqtt.NewClient(mqtt.NewClientOptions().AddBroker(broker).SetClientID("processing-test-publisher"))
	connected := publisher.Connect()
	if !connected.WaitTimeout(time.Second) || connected.Error() != nil {
		t.Fatal("MQTT publisher failed")
	}
	defer publisher.Disconnect(100)
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	timeout := time.NewTimer(8 * time.Second)
	defer timeout.Stop()
	sequence := uint64(0)
	seen := map[string]bool{}
	noiseFrames := 0
	session := strconv.FormatInt(time.Now().UnixNano(), 36)
	var restartedAt time.Time
	for {
		select {
		case <-ticker.C:
			if restartCount > 0 && sequence == 10 {
				oldSession := session
				for restart := 0; restart < restartCount; restart++ {
					session = strconv.FormatInt(time.Now().UnixNano(), 36)
					now := time.Now().UnixMilli()
					payload, _ := json.Marshal(Observation{APIVersion: "edgeai.observation/v1", Namespace: input.Namespace, DeviceName: input.DeviceID, Property: input.Property, SourceGeneration: sourceGeneration, ConfigurationSHA256: input.ConfigurationSHA256, ObservedAt: now, ReceivedAt: now, TimeBasis: "SOURCE", SessionID: session, Sequence: 1, Type: input.Type, Unit: input.Unit, Value: 21.5})
					published := publisher.Publish(input.Topic, 1, false, payload)
					if !published.WaitTimeout(time.Second) || published.Error() != nil {
						t.Fatal("restart sample publish failed")
					}
				}
				now := time.Now().UnixMilli()
				replay, _ := json.Marshal(Observation{APIVersion: "edgeai.observation/v1", Namespace: input.Namespace, DeviceName: input.DeviceID, Property: input.Property, SourceGeneration: sourceGeneration, ConfigurationSHA256: input.ConfigurationSHA256, ObservedAt: now, ReceivedAt: now, TimeBasis: "SOURCE", SessionID: oldSession, Sequence: 999, Type: input.Type, Unit: input.Unit, Value: 999})
				publisher.Publish(input.Topic, 1, false, replay).WaitTimeout(time.Second)
				restartedAt = time.Now()
				restartReady.Store(true)
			}
			if actualEngine {
				body, _ := json.Marshal(map[string]any{"vdId": "ffffffff-ffff-4fff-8fff-ffffffffffff", "generation": generation, "sequence": time.Now().Unix(), "evaluationId": "noise", "evaluationAt": time.Now().UTC().Format(time.RFC3339Nano), "x0": 999})
				response, err := http.Post(pushURL, "application/json", bytes.NewReader(body))
				if err == nil {
					if response.StatusCode == 200 {
						noiseFrames++
					}
					response.Body.Close()
				}
			}
			sequence++
			now := time.Now().UnixMilli()
			payload, _ := json.Marshal(Observation{APIVersion: "edgeai.observation/v1", Namespace: input.Namespace, DeviceName: input.DeviceID, Property: input.Property, SourceGeneration: sourceGeneration, ConfigurationSHA256: input.ConfigurationSHA256, ObservedAt: now, ReceivedAt: now, TimeBasis: "SOURCE", SessionID: session, Sequence: sequence, Type: input.Type, Unit: input.Unit, Value: 21.5})
			published := publisher.Publish(input.Topic, 1, false, payload)
			if !published.WaitTimeout(time.Second) || published.Error() != nil {
				t.Fatal("MQTT sample publish failed")
			}
		case result := <-results:
			if restartCount > 0 {
				at, _ := time.Parse(time.RFC3339Nano, result.EvaluationAt)
				if restartedAt.IsZero() || !at.After(restartedAt) {
					continue
				}
			}
			expectedValue := any(21.5)
			if result.OutputID == "threshold" {
				expectedValue = true
			}
			if result.VDID != vd || result.Generation != generation || result.Value != expectedValue || result.OutputID == "filtered" || len(result.Sources) != 1 || result.Sources[0].DeviceUID != uid {
				t.Fatalf("wrong result or source provenance: %+v", result)
			}
			seen[result.OutputID] = true
			if actualEngine && len(seen) < 5 {
				continue
			}
			if actualEngine && noiseFrames == 0 {
				t.Fatal("no cross-VD frames exercised the isolated count windows")
			}
			cancel()
			select {
			case err := <-finished:
				if err != nil {
					t.Fatal(err)
				}
			case <-time.After(3 * time.Second):
				t.Fatal("agent did not stop")
			}
			return
		case err := <-finished:
			t.Fatalf("agent stopped before a result: %v", err)
		case <-timeout.C:
			if actualEngine {
				for _, rule := range execution.Rules {
					response, e := http.Get(kuiperURL + "/rules/" + rule.ID + "/status")
					if e == nil {
						missing := response.StatusCode == 404
						response.Body.Close()
						if missing {
							body, _ := json.Marshal(agent.ruleDocument(execution, rule))
							raw, e := agent.request(ctx, "POST", kuiperURL+"/rules", body, false, time.Second)
							_ = raw
							if status, ok := e.(statusError); ok {
								t.Logf("rule %s apply HTTP%d %s", rule.OutputID, status.status, strings.ReplaceAll(status.body, agent.callbackToken, "[redacted]"))
							}
						}
					}
				}
			}
			if actualEngine {
				if err := agent.stream(ctx); err != nil {
					if e, ok := err.(statusError); ok {
						t.Logf("engine ingress initialization HTTP%d: %s", e.status, strings.ReplaceAll(e.body, agent.callbackToken, "[redacted]"))
					} else {
						t.Logf("engine initialization: %v", err)
					}
				}
			}
			agent.mu.Lock()
			for _, runtime := range agent.runtimes {
				t.Logf("runtime installed=%v state=%s reason=%s warm=%d pending=%d", runtime.installed, runtime.state, runtime.reason, runtime.warmTicks, len(runtime.pending))
			}
			t.Logf("lease=%v ready=%v inputs=%d observations=%d frames=%d callbacks=%d invalidCallbacks=%d", agent.lease.After(time.Now()), agent.engineReady, len(agent.catalog), len(agent.observations), agent.metrics.frames.Load(), agent.metrics.callbacks.Load(), agent.metrics.invalidCallbacks.Load())
			agent.mu.Unlock()
			for _, path := range []string{"/streams/edgeai_vd_frames", "/rules/" + ruleID + "/status", "/metadata/sources/httppush/confKeys/vd_frames"} {
				response, e := http.Get(kuiperURL + path)
				if e == nil {
					raw, _ := io.ReadAll(io.LimitReader(response.Body, 4000))
					response.Body.Close()
					t.Logf("engine %s HTTP%d %s", path, response.StatusCode, raw)
				}
			}
			t.Fatal("no computed result from fresh MQTT input")
		}
	}
}
