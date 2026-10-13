package processing

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

func (a *Agent) resultLoop(ctx context.Context) {
	var carry *queuedResult
	for {
		batch := []queuedResult{}
		bytes := 0
		if carry != nil {
			batch = append(batch, *carry)
			bytes += int(carry.size)
			carry = nil
		} else {
			select {
			case <-ctx.Done():
				return
			case result := <-a.results:
				batch = append(batch, result)
				bytes += int(result.size)
			}
		}
		timer := time.NewTimer(50 * time.Millisecond)
	gather:
		for len(batch) < 64 {
			select {
			case <-ctx.Done():
				timer.Stop()
				return
			case result := <-a.results:
				if bytes+int(result.size) > 240*1024 {
					carry = &result
					break gather
				}
				batch = append(batch, result)
				bytes += int(result.size)
			case <-timer.C:
				break gather
			}
		}
		timer.Stop()
		for {
			a.mu.Lock()
			session := a.sessionID
			rows := []Result{}
			keep := []queuedResult{}
			for _, item := range batch {
				at, err := time.Parse(time.RFC3339Nano, item.result.EvaluationAt)
				if err == nil && a.activeLocked(item.result.VDID, item.result.Generation) && time.Since(at) <= 15*time.Second {
					rows = append(rows, item.result)
					keep = append(keep, item)
				} else {
					a.resultBytes.Add(-item.size)
					if a.activeLocked(item.result.VDID, item.result.Generation) {
						a.paused(a.runtimes[item.result.VDID], "ENGINE_BACKPRESSURE")
					}
				}
			}
			batch = keep
			a.mu.Unlock()
			if len(rows) == 0 {
				break
			}
			body, _ := json.Marshal(map[string]any{"sessionId": session, "results": rows})
			response, err := a.request(ctx, http.MethodPost, a.config.BackendURL+"/internal/v1/processing/results", body, true, 2*time.Second)
			if err == nil {
				err = resultAcknowledgement(response, len(rows))
			}
			if err == nil {
				for _, item := range batch {
					a.resultBytes.Add(-item.size)
					a.recordLatency(item.result)
				}
				a.metrics.results.Add(uint64(len(batch)))
				break
			}
			a.metrics.backendErrors.Add(1)
			if status, ok := err.(statusError); ok && (status.status == 400 || status.status == 409) {
				// A source edit can race one VD in an otherwise valid batch. Retry each
				// VD independently so that the rejected VD cannot reset its neighbours.
				groups := map[string][]queuedResult{}
				for _, item := range batch {
					groups[item.result.VDID] = append(groups[item.result.VDID], item)
				}
				if len(groups) > 1 {
					for _, group := range groups {
						a.forwardIsolated(ctx, session, group)
					}
					break
				}
				a.mu.Lock()
				for _, item := range batch {
					a.resultBytes.Add(-item.size)
					if a.activeLocked(item.result.VDID, item.result.Generation) {
						a.paused(a.runtimes[item.result.VDID], "INPUT_CHANGED")
					}
				}
				a.mu.Unlock()
				break
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(200 * time.Millisecond):
			}
		}
	}
}

func resultAcknowledgement(data []byte, count int) error {
	var acknowledgement struct {
		Accepted      *int  `json:"accepted"`
		NewResults    *int  `json:"newResults"`
		HistoryStored *bool `json:"historyStored"`
		Replay        *bool `json:"replay"`
	}
	if json.Unmarshal(data, &acknowledgement) != nil || acknowledgement.Accepted == nil || *acknowledgement.Accepted != count || acknowledgement.NewResults == nil || *acknowledgement.NewResults < 0 || *acknowledgement.NewResults > count || acknowledgement.HistoryStored == nil || acknowledgement.Replay == nil || *acknowledgement.Replay {
		return fmt.Errorf("invalid processing result acknowledgement")
	}
	return nil
}

func (a *Agent) forwardIsolated(ctx context.Context, session string, batch []queuedResult) {
	defer func() {
		for _, item := range batch {
			a.resultBytes.Add(-item.size)
		}
	}()
	for {
		a.mu.Lock()
		valid := a.sessionID == session
		rows := make([]Result, 0, len(batch))
		for _, item := range batch {
			at, err := time.Parse(time.RFC3339Nano, item.result.EvaluationAt)
			valid = valid && err == nil && time.Since(at) <= 15*time.Second && a.activeLocked(item.result.VDID, item.result.Generation)
			rows = append(rows, item.result)
		}
		a.mu.Unlock()
		if !valid {
			a.mu.Lock()
			if a.activeLocked(batch[0].result.VDID, batch[0].result.Generation) {
				a.paused(a.runtimes[batch[0].result.VDID], "ENGINE_BACKPRESSURE")
			}
			a.mu.Unlock()
			return
		}
		body, _ := json.Marshal(map[string]any{"sessionId": session, "results": rows})
		response, err := a.request(ctx, http.MethodPost, a.config.BackendURL+"/internal/v1/processing/results", body, true, 2*time.Second)
		if err == nil {
			err = resultAcknowledgement(response, len(rows))
		}
		if err == nil {
			a.metrics.results.Add(uint64(len(rows)))
			for _, row := range rows {
				a.recordLatency(row)
			}
			return
		}
		a.metrics.backendErrors.Add(1)
		if status, ok := err.(statusError); ok && (status.status == 400 || status.status == 409) {
			a.mu.Lock()
			if a.activeLocked(rows[0].VDID, rows[0].Generation) {
				a.paused(a.runtimes[rows[0].VDID], "INPUT_CHANGED")
			}
			a.mu.Unlock()
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(200 * time.Millisecond):
		}
	}
}

var latencyBounds = [...]float64{0.1, 0.25, 0.5, 1, 2, 5, 15}

func (a *Agent) recordLatency(result Result) {
	at, err := time.Parse(time.RFC3339Nano, result.EvaluationAt)
	if err != nil {
		return
	}
	elapsed := time.Since(at)
	if elapsed < 0 {
		elapsed = 0
	}
	a.metrics.latencyMicros.Add(uint64(elapsed.Microseconds()))
	for index, bound := range latencyBounds {
		if elapsed.Seconds() <= bound {
			a.metrics.latency[index].Add(1)
		}
	}
	a.metrics.latency[len(latencyBounds)].Add(1)
}
func (a *Agent) writeMetrics(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "text/plain; version=0.0.4")
	fmt.Fprintf(w, "edgeai_processing_observations_total %d\nedgeai_processing_invalid_observations_total %d\nedgeai_processing_frames_total %d\nedgeai_processing_callbacks_total %d\nedgeai_processing_invalid_callbacks_total %d\nedgeai_processing_results_total %d\nedgeai_processing_resets_total %d\nedgeai_processing_backend_errors_total %d\nedgeai_processing_backpressure_total %d\nedgeai_processing_result_queue %d\nedgeai_processing_result_bytes %d\n", a.metrics.observations.Load(), a.metrics.invalidObservations.Load(), a.metrics.frames.Load(), a.metrics.callbacks.Load(), a.metrics.invalidCallbacks.Load(), a.metrics.results.Load(), a.metrics.resets.Load(), a.metrics.backendErrors.Load(), a.metrics.backpressure.Load(), len(a.results), a.resultBytes.Load())
	fmt.Fprintln(w, "# TYPE edgeai_processing_result_latency_seconds histogram")
	for index, bound := range latencyBounds {
		fmt.Fprintf(w, "edgeai_processing_result_latency_seconds_bucket{le=\"%g\"} %d\n", bound, a.metrics.latency[index].Load())
	}
	fmt.Fprintf(w, "edgeai_processing_result_latency_seconds_bucket{le=\"+Inf\"} %d\nedgeai_processing_result_latency_seconds_count %d\nedgeai_processing_result_latency_seconds_sum %g\n", a.metrics.latency[len(latencyBounds)].Load(), a.metrics.latency[len(latencyBounds)].Load(), float64(a.metrics.latencyMicros.Load())/1e6)
	for index, queue := range a.pushes {
		fmt.Fprintf(w, "edgeai_processing_push_queue{shard=\"%d\"} %d\n", index, len(queue))
	}
}
