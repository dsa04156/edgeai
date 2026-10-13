package processing

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"net/http"
	"sync"
	"sync/atomic"
	"time"
)

type Agent struct {
	config        Config
	client        *http.Client
	callbackToken string
	mu            sync.Mutex
	sessionID     string
	lease         time.Time
	runtimes      map[string]*executionState
	catalog       map[string]Input
	observations  map[string]observedInput
	brokers       map[string]*brokerState
	results       chan queuedResult
	resultBytes   atomic.Int64
	pushes        [8]chan pushJob
	metrics       counters
	engineReady   bool
}

type executionState struct {
	desired       Execution
	present       bool
	installed     bool
	resetting     bool
	resetReason   string
	lastSlot      int64
	warmTicks     int
	state, reason string
	pending       map[string]*evaluation
}
type evaluation struct {
	sequence int64
	at       string
	proofs   []Proof
	expected map[string]Rule
	received map[string]bool
	deadline time.Time
}
type observedInput struct {
	observation Observation
}
type queuedResult struct {
	result Result
	size   int64
}
type pushJob struct {
	id, generation, evaluationID string
	frame                        map[string]any
}
type counters struct {
	observations, invalidObservations, frames, callbacks, invalidCallbacks, results, resets, backendErrors, backpressure atomic.Uint64
	latency                                                                                                              [8]atomic.Uint64
	latencyMicros                                                                                                        atomic.Uint64
}

func New(config Config) (*Agent, error) {
	if err := config.validate(); err != nil {
		return nil, err
	}
	client, err := httpClient(config.CAFile)
	if err != nil {
		return nil, err
	}
	token := make([]byte, 32)
	if _, err = rand.Read(token); err != nil {
		return nil, err
	}
	a := &Agent{config: config, client: client, callbackToken: base64.RawURLEncoding.EncodeToString(token), sessionID: randomSession(), runtimes: map[string]*executionState{}, catalog: map[string]Input{}, observations: map[string]observedInput{}, brokers: map[string]*brokerState{}, results: make(chan queuedResult, 4096)}
	for index := range a.pushes {
		a.pushes[index] = make(chan pushJob, 64)
	}
	return a, nil
}

func (a *Agent) CallbackHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		values := r.Header.Values("X-EdgeAI-Callback")
		if len(values) != 1 || subtle.ConstantTimeCompare([]byte(values[0]), []byte(a.callbackToken)) != 1 {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		a.complete(w, r)
	})
}

func (a *Agent) paused(r *executionState, reason string) {
	if r.resetReason == "" {
		r.resetReason = reason
		a.metrics.resets.Add(1)
	}
	r.state = "WAITING_INPUT"
	r.reason = reason
	r.pending = map[string]*evaluation{}
}
func (a *Agent) activeLocked(id, generation string) bool {
	r := a.runtimes[id]
	return r != nil && r.present && r.desired.Running && r.desired.Generation == generation && r.resetReason == "" && a.lease.After(time.Now())
}
func (a *Agent) reportLocked() []Report {
	reports := []Report{}
	for _, r := range a.runtimes {
		if !r.present || r.state == "" || !r.installed && r.state != "ERROR" {
			continue
		}
		ids := []string{}
		if r.desired.Running {
			for _, rule := range r.desired.Rules {
				ids = append(ids, rule.ID)
			}
		}
		reason := r.reason
		if reason == "INPUT_CHANGED" || reason == "TICK_GAP" {
			reason = "INPUT_MISSING"
		}
		reports = append(reports, Report{VDID: r.desired.VDID, Generation: r.desired.Generation, State: r.state, Reason: reason, RuleIDs: ids})
	}
	return reports
}
func (a *Agent) HealthHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		a.mu.Lock()
		ready := a.engineReady && a.lease.After(time.Now())
		a.mu.Unlock()
		switch r.URL.Path {
		case "/healthz":
			w.WriteHeader(200)
		case "/readyz":
			if !ready {
				w.WriteHeader(503)
			} else {
				w.WriteHeader(200)
			}
		case "/metrics":
			a.writeMetrics(w)
		default:
			w.WriteHeader(404)
		}
	})
}
func (a *Agent) expireLease() {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.lease.After(time.Now()) {
		return
	}
	for _, r := range a.runtimes {
		r.pending = map[string]*evaluation{}
		r.state = "ERROR"
		r.reason = "RULE_UNAVAILABLE"
	}
}
func (a *Agent) startLoop(ctx context.Context, group *sync.WaitGroup, loop func(context.Context)) {
	group.Add(1)
	go func() { defer group.Done(); loop(ctx) }()
}
