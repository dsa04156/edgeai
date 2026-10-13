package processing

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/google/uuid"
	"reflect"
	"regexp"
	"time"
)

var outputName = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]{0,62}$`)
var columnName = regexp.MustCompile(`^x([0-9]|1[0-5])$`)
var configHash = regexp.MustCompile(`^sha256:[a-f0-9]{64}$`)

func canonicalUUID(value string) bool {
	id, err := uuid.Parse(value)
	return err == nil && id.String() == value
}
func (a *Agent) validatePlan(plan Plan, session string) error {
	if plan.APIVersion != "edgeai.processing-plan/v1" || plan.EngineID != "central" || plan.EnginePodUID != a.config.PodUID || plan.SessionID != session || len(plan.Inputs) > 500 || len(plan.Executions) > 200 {
		return fmt.Errorf("invalid engine plan identity or size")
	}
	duration := plan.LeaseExpiresAt.Sub(plan.ServerTime)
	if duration <= 0 || duration > 16*time.Second || time.Since(plan.ServerTime) > 2*time.Second || time.Until(plan.ServerTime) > time.Second {
		return fmt.Errorf("invalid engine clock or lease")
	}
	ids := map[string]bool{}
	total := 0
	for _, execution := range plan.Executions {
		if !canonicalUUID(execution.VDID) || !canonicalUUID(execution.Generation) || ids[execution.VDID] {
			return fmt.Errorf("invalid execution identity")
		}
		ids[execution.VDID] = true
		if !execution.Running {
			continue
		}
		if execution.IntervalSeconds < 1 || execution.IntervalSeconds > 3600 || len(execution.Sources) < 1 || len(execution.Sources) > 16 || len(execution.Rules) < 1 || len(execution.Rules) > 16 {
			return fmt.Errorf("invalid execution limits")
		}
		columns, aliases, outputs := map[string]bool{}, map[string]bool{}, map[string]bool{}
		for _, source := range execution.Sources {
			if !canonicalUUID(source.DeviceUID) || !canonicalUUID(source.SourceGeneration) || !configHash.MatchString(source.ConfigurationSHA256) || !columnName.MatchString(source.Column) || !outputName.MatchString(source.Alias) || columns[source.Column] || aliases[source.Alias] || source.MaxAgeSeconds < 1 || source.MaxAgeSeconds > 300 || source.Reason != "" || source.Broker == "" || source.Topic != "edgeai/observations/v1/"+source.Namespace+"/"+source.DeviceID+"/"+source.Property {
				return fmt.Errorf("invalid input route")
			}
			if _, err := brokerOptions(source.Broker, a.config.BrokerCAFile); err != nil {
				return err
			}
			columns[source.Column] = true
			aliases[source.Alias] = true
		}
		for _, rule := range execution.Rules {
			if !outputName.MatchString(rule.OutputID) || rule.ID != ownedID(execution, rule.OutputID) || outputs[rule.OutputID] || len(rule.SQL) > 65536 || rule.SQL == "" || rule.Type != "number" && rule.Type != "boolean" || rule.WarmupTicks < 1 || rule.WarmupTicks > 1024 {
				return fmt.Errorf("invalid owned rule")
			}
			outputs[rule.OutputID] = true
			total++
		}
	}
	if total > 500 {
		return fmt.Errorf("too many rules")
	}
	return nil
}
func (a *Agent) pollLoop(ctx context.Context) {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	sequence := uint64(1)
	var pending []byte
	for {
		a.mu.Lock()
		session := a.sessionID
		if pending == nil {
			pending, _ = json.Marshal(map[string]any{"sessionId": session, "sequence": sequence, "reports": a.reportLocked()})
		}
		a.mu.Unlock()
		start := time.Now()
		data, err := a.request(ctx, "POST", a.config.BackendURL+"/internal/v1/processing/poll", pending, true, 4*time.Second)
		var plan Plan
		if err == nil {
			err = decode(data, &plan)
		}
		if err == nil {
			err = a.validatePlan(plan, session)
		}
		if err == nil {
			a.mu.Lock()
			a.lease = time.Now().Add(plan.LeaseExpiresAt.Sub(plan.ServerTime) - time.Since(start))
			a.applyPlanLocked(plan)
			a.mu.Unlock()
			a.syncBrokers(ctx)
			sequence++
			pending = nil
		} else {
			a.metrics.backendErrors.Add(1)
			if status, ok := err.(statusError); ok && status.status == 409 {
				a.mu.Lock()
				a.lease = time.Time{}
				a.sessionID = randomSession()
				for _, r := range a.runtimes {
					r.installed = false
					r.pending = map[string]*evaluation{}
				}
				a.mu.Unlock()
				sequence = 1
				pending = nil
			}
			a.expireLease()
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}
func (a *Agent) applyPlanLocked(plan Plan) {
	next := map[string]Input{}
	for _, input := range plan.Inputs {
		if input.Reason == "" && input.Broker != "" {
			next[inputKey(input)] = input
		}
	}
	for key, old := range a.catalog {
		value, ok := next[key]
		if !ok || old.SourceGeneration != value.SourceGeneration || old.ConfigurationSHA256 != value.ConfigurationSHA256 || old.DeviceUID != value.DeviceUID {
			delete(a.observations, key)
		}
	}
	a.catalog = next
	for _, r := range a.runtimes {
		r.present = false
	}
	for _, execution := range plan.Executions {
		r := a.runtimes[execution.VDID]
		if r == nil || r.desired.Generation != execution.Generation || r.desired.Running != execution.Running {
			r = &executionState{desired: execution, present: true, pending: map[string]*evaluation{}}
			a.runtimes[execution.VDID] = r
		} else {
			if !reflect.DeepEqual(r.desired.Sources, execution.Sources) || !reflect.DeepEqual(r.desired.Rules, execution.Rules) {
				a.paused(r, "INPUT_CHANGED")
			}
			r.desired = execution
			r.present = true
		}
	}
}
func (a *Agent) resetLoop(ctx context.Context) {
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			a.mu.Lock()
			session := a.sessionID
			requests := []map[string]any{}
			if a.lease.After(time.Now()) {
				for _, r := range a.runtimes {
					if r.present && r.desired.Running && r.resetReason != "" && !r.resetting {
						r.resetting = true
						requests = append(requests, map[string]any{"sessionId": session, "vdId": r.desired.VDID, "generation": r.desired.Generation, "reason": r.resetReason})
					}
				}
			}
			a.mu.Unlock()
			for _, request := range requests {
				body, _ := json.Marshal(request)
				_, err := a.request(ctx, "POST", a.config.BackendURL+"/internal/v1/processing/reset", body, true, 2*time.Second)
				a.mu.Lock()
				r := a.runtimes[request["vdId"].(string)]
				if r != nil && r.desired.Generation == request["generation"] && err != nil {
					r.resetting = false
				}
				a.mu.Unlock()
				if err != nil {
					a.metrics.backendErrors.Add(1)
				}
			}
		}
	}
}
