package processing

import (
	"context"
	"encoding/json"
	"fmt"
	"hash/fnv"
	"io"
	"math"
	"net/http"
	"strings"
	"time"
)

func (a *Agent) tickLoop(ctx context.Context) {
	ticker := time.NewTicker(50 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			a.tick()
		}
	}
}
func (a *Agent) tick() {
	now := time.Now()
	a.mu.Lock()
	defer a.mu.Unlock()
	if !a.lease.After(now) {
		return
	}
	for _, runtime := range a.runtimes {
		if !runtime.present || !runtime.desired.Running || !runtime.installed || runtime.resetReason != "" {
			continue
		}
		for key, pending := range runtime.pending {
			if pending.deadline.After(now) {
				continue
			}
			missing := false
			for output, rule := range pending.expected {
				if !pending.received[output] && !rule.Conditional {
					missing = true
					break
				}
			}
			delete(runtime.pending, key)
			if missing {
				a.paused(runtime, "EVALUATION_TIMEOUT")
				break
			}
		}
		if runtime.resetReason != "" {
			continue
		}
		interval := int64(runtime.desired.IntervalSeconds) * 1000
		slot := now.UnixMilli() / interval
		if slot == runtime.lastSlot {
			continue
		}
		if runtime.lastSlot != 0 && slot != runtime.lastSlot+1 {
			a.paused(runtime, "TICK_GAP")
			continue
		}
		frame := map[string]any{"vdId": runtime.desired.VDID, "generation": runtime.desired.Generation, "sequence": slot, "evaluationId": runtime.desired.Generation + ":" + fmt.Sprint(slot), "evaluationAt": time.UnixMilli(slot * interval).UTC().Format(time.RFC3339Nano)}
		proofs := []Proof{}
		valid := true
		reason := "INPUT_MISSING"
		for _, source := range runtime.desired.Sources {
			observed, ok := a.observations[inputKey(source)]
			broker := a.brokers[source.Broker]
			if !ok || broker == nil || !broker.connected {
				valid = false
				break
			}
			value := observed.observation
			if value.ObservedAt > slot*interval+1000 || value.ObservedAt < slot*interval-int64(source.MaxAgeSeconds)*1000 {
				valid = false
				reason = "INPUT_STALE"
				break
			}
			frame[source.Column] = value.Value
			proofs = append(proofs, Proof{Alias: source.Alias, DeviceUID: source.DeviceUID, SourceGeneration: source.SourceGeneration, ConfigurationSHA256: source.ConfigurationSHA256, ObservedAt: value.ObservedAt})
		}
		if !valid {
			runtime.state = "WAITING_INPUT"
			runtime.reason = reason
			if runtime.warmTicks > 0 {
				a.paused(runtime, reason)
			}
			continue
		}
		runtime.lastSlot = slot
		runtime.warmTicks++
		expected := map[string]Rule{}
		warming := false
		for _, rule := range runtime.desired.Rules {
			if runtime.warmTicks >= rule.WarmupTicks {
				expected[rule.OutputID] = rule
			} else {
				warming = true
			}
		}
		runtime.state = "RUNNING"
		runtime.reason = "HEALTHY"
		if warming {
			runtime.state = "WAITING_INPUT"
			runtime.reason = "WINDOW_WARMING"
		}
		id := frame["evaluationId"].(string)
		if len(expected) > 0 {
			runtime.pending[id] = &evaluation{sequence: slot, at: frame["evaluationAt"].(string), proofs: proofs, expected: expected, received: map[string]bool{}, deadline: now.Add(2 * time.Second)}
		}
		hash := fnv.New32a()
		hash.Write([]byte(runtime.desired.VDID))
		shard := hash.Sum32() % uint32(len(a.pushes))
		select {
		case a.pushes[shard] <- pushJob{id: runtime.desired.VDID, generation: runtime.desired.Generation, evaluationID: id, frame: frame}:
		default:
			a.metrics.backpressure.Add(1)
			a.paused(runtime, "ENGINE_BACKPRESSURE")
		}
	}
}

func (a *Agent) pushLoop(ctx context.Context, queue <-chan pushJob) {
	for {
		select {
		case <-ctx.Done():
			return
		case job := <-queue:
			a.mu.Lock()
			valid := a.activeLocked(job.id, job.generation)
			a.mu.Unlock()
			if !valid {
				continue
			}
			data, err := json.Marshal(job.frame)
			if err == nil {
				_, err = a.request(ctx, http.MethodPost, a.config.PushURL, data, false, 1*time.Second)
			}
			if err != nil {
				a.mu.Lock()
				if a.activeLocked(job.id, job.generation) {
					a.paused(a.runtimes[job.id], "EVALUATION_TIMEOUT")
				}
				a.mu.Unlock()
			} else {
				a.metrics.frames.Add(1)
			}
		}
	}
}

func (a *Agent) complete(w http.ResponseWriter, request *http.Request) {
	path := strings.Split(strings.TrimPrefix(request.URL.Path, "/callbacks/"), "/")
	if len(path) != 3 || !strings.HasPrefix(request.URL.Path, "/callbacks/") {
		w.WriteHeader(404)
		return
	}
	data, err := io.ReadAll(io.LimitReader(request.Body, 65537))
	if err != nil || len(data) > 65536 {
		w.WriteHeader(413)
		return
	}
	decoder := json.NewDecoder(strings.NewReader(string(data)))
	decoder.UseNumber()
	var row map[string]any
	if decoder.Decode(&row) != nil {
		w.WriteHeader(400)
		return
	}
	var trailing any
	if decoder.Decode(&trailing) != io.EOF || len(row) != 6 {
		w.WriteHeader(400)
		return
	}
	if id, ok := row["frameVdId"]; ok {
		if _, duplicate := row["vdId"]; duplicate {
			w.WriteHeader(400)
			return
		}
		row["vdId"] = id
		delete(row, "frameVdId")
	}
	if generation, ok := row["frameGeneration"]; ok {
		if _, duplicate := row["generation"]; duplicate {
			w.WriteHeader(400)
			return
		}
		row["generation"] = generation
		delete(row, "frameGeneration")
	}
	number, ok := row["sequence"].(json.Number)
	if !ok {
		w.WriteHeader(400)
		return
	}
	sequence, err := number.Int64()
	if err != nil {
		w.WriteHeader(400)
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if !a.activeLocked(path[0], path[1]) {
		a.metrics.invalidCallbacks.Add(1)
		w.WriteHeader(409)
		return
	}
	runtime := a.runtimes[path[0]]
	id, ok := row["evaluationId"].(string)
	if !ok {
		w.WriteHeader(400)
		return
	}
	pending := runtime.pending[id]
	if pending == nil || !pending.deadline.After(time.Now()) || pending.sequence != sequence || row["vdId"] != path[0] || row["generation"] != path[1] || row["evaluationAt"] != pending.at {
		a.metrics.invalidCallbacks.Add(1)
		w.WriteHeader(409)
		return
	}
	rule, ok := pending.expected[path[2]]
	if !ok {
		w.WriteHeader(409)
		return
	}
	if pending.received[path[2]] {
		w.WriteHeader(200)
		return
	}
	value := row["value"]
	if rule.Type == "boolean" {
		if _, ok := value.(bool); !ok {
			w.WriteHeader(400)
			return
		}
	} else {
		number, ok := value.(json.Number)
		if !ok {
			w.WriteHeader(400)
			return
		}
		numeric, err := number.Float64()
		if err != nil || math.IsNaN(numeric) || math.IsInf(numeric, 0) {
			w.WriteHeader(400)
			return
		}
		value = numeric
	}
	result := Result{VDID: path[0], OutputID: path[2], Generation: path[1], Sequence: sequence, EvaluationID: id, EvaluationAt: pending.at, Value: value, Sources: append([]Proof(nil), pending.proofs...)}
	encoded, err := json.Marshal(result)
	if err != nil {
		w.WriteHeader(400)
		return
	}
	size := int64(len(encoded))
	if a.resultBytes.Load()+size > 16*1024*1024 {
		a.metrics.backpressure.Add(1)
		a.paused(runtime, "ENGINE_BACKPRESSURE")
		w.WriteHeader(503)
		return
	}
	a.resultBytes.Add(size)
	select {
	case a.results <- queuedResult{result: result, size: size}:
		pending.received[path[2]] = true
		a.metrics.callbacks.Add(1)
		w.WriteHeader(200)
	default:
		a.resultBytes.Add(-size)
		a.metrics.backpressure.Add(1)
		a.paused(runtime, "ENGINE_BACKPRESSURE")
		w.WriteHeader(503)
	}
}
