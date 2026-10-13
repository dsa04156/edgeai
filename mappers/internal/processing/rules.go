package processing

import (
	"context"
	"encoding/json"
	"fmt"
	"reflect"
	"regexp"
	"strings"
	"time"
)

var ownedRule = regexp.MustCompile(`^edgeai_vd_[a-f0-9]{32}_[a-f0-9]{32}_[A-Za-z][A-Za-z0-9_]{0,62}$`)

func ownedID(execution Execution, output string) string {
	return "edgeai_vd_" + strings.ReplaceAll(execution.VDID, "-", "") + "_" + strings.ReplaceAll(execution.Generation, "-", "") + "_" + output
}
func (a *Agent) ruleDocument(execution Execution, rule Rule) map[string]any {
	return map[string]any{"id": rule.ID, "sql": rule.SQL, "actions": []any{map[string]any{"rest": map[string]any{"url": "http://" + a.config.CallbackAddress + "/callbacks/" + execution.VDID + "/" + execution.Generation + "/" + rule.OutputID, "method": "POST", "bodyType": "json", "sendSingle": true, "headers": map[string]string{"X-EdgeAI-Callback": a.callbackToken}, "timeout": 1000, "enableCache": false}}}, "options": map[string]any{"qos": 0, "concurrency": 1, "bufferLength": 1024, "sendError": false}}
}
func (a *Agent) engineRequest(ctx context.Context, method, path string, value any) ([]byte, error) {
	var body []byte
	var err error
	if value != nil {
		body, err = json.Marshal(value)
		if err != nil {
			return nil, err
		}
	}
	return a.request(ctx, method, a.config.KuiperURL+path, body, false, 2*time.Second)
}
func (a *Agent) stream(ctx context.Context) error {
	if _, err := a.engineRequest(ctx, "PUT", "/metadata/sources/httppush/confKeys/vd_frames", map[string]any{"method": "POST"}); err != nil {
		return err
	}
	listed, err := a.engineRequest(ctx, "GET", "/streams", nil)
	if err != nil {
		return err
	}
	var streams []string
	if json.Unmarshal(listed, &streams) != nil {
		return fmt.Errorf("invalid stream list")
	}
	present := false
	for _, name := range streams {
		if name == "edgeai_vd_frames" {
			present = true
		}
	}
	sql := `CREATE STREAM edgeai_vd_frames () WITH (TYPE="httppush", DATASOURCE="/edgeai/evaluate", FORMAT="json", CONF_KEY="vd_frames")`
	if !present {
		_, err = a.engineRequest(ctx, "POST", "/streams", map[string]string{"sql": sql})
		return err
	}
	data, err := a.engineRequest(ctx, "GET", "/streams/edgeai_vd_frames", nil)
	if err != nil {
		return err
	}
	var current map[string]any
	if json.Unmarshal(data, &current) != nil {
		return fmt.Errorf("invalid owned stream")
	}
	// Existing streams must retain the server-owned ingress; never modify another stream.
	encoded := strings.ToLower(string(data))
	if !strings.Contains(encoded, "httppush") || !strings.Contains(encoded, "/edgeai/evaluate") {
		return fmt.Errorf("owned stream configuration changed")
	}
	return nil
}
func (a *Agent) reconcileLoop(ctx context.Context) {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	initialized := false
	var initializedAt time.Time
	for {
		if !initialized || time.Since(initializedAt) >= 10*time.Second {
			if a.stream(ctx) == nil {
				initialized = true
				initializedAt = time.Now()
			} else {
				initialized = false
				a.mu.Lock()
				a.engineReady = false
				for _, r := range a.runtimes {
					if r.present && r.desired.Running && r.installed {
						a.paused(r, "RULE_UNAVAILABLE")
					}
				}
				a.mu.Unlock()
			}
		}
		if initialized {
			if err := a.reconcile(ctx); err != nil {
				initialized = false
				a.mu.Lock()
				a.engineReady = false
				for _, r := range a.runtimes {
					if r.present && r.desired.Running && r.installed {
						a.paused(r, "RULE_UNAVAILABLE")
					}
				}
				a.mu.Unlock()
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}
func (a *Agent) reconcile(ctx context.Context) error {
	data, err := a.engineRequest(ctx, "GET", "/rules", nil)
	if err != nil {
		return err
	}
	var ids []string
	if json.Unmarshal(data, &ids) != nil {
		var rows []struct {
			ID string `json:"id"`
		}
		if json.Unmarshal(data, &rows) != nil {
			return fmt.Errorf("invalid rule list")
		}
		for _, row := range rows {
			if row.ID == "" {
				return fmt.Errorf("invalid rule identity")
			}
			ids = append(ids, row.ID)
		}
	}
	a.mu.Lock()
	desired := []Execution{}
	expected := map[string]bool{}
	if a.lease.After(time.Now()) {
		for _, r := range a.runtimes {
			if r.present {
				desired = append(desired, r.desired)
				if r.desired.Running {
					for _, rule := range r.desired.Rules {
						expected[rule.ID] = true
					}
				}
			}
		}
	}
	a.mu.Unlock()
	actual := map[string]bool{}
	for _, id := range ids {
		actual[id] = true
		if ownedRule.MatchString(id) && !expected[id] {
			if _, err = a.engineRequest(ctx, "DELETE", "/rules/"+id, nil); err != nil {
				return err
			}
			delete(actual, id)
		}
	}
	for _, execution := range desired {
		healthy := true
		if execution.Running {
			for _, rule := range execution.Rules {
				a.mu.Lock()
				current := a.runtimes[execution.VDID]
				currentGeneration := current != nil && current.present && current.desired.Generation == execution.Generation && current.desired.Running && a.lease.After(time.Now())
				wasInstalled := currentGeneration && current.installed
				a.mu.Unlock()
				if !currentGeneration {
					healthy = false
					break
				}
				document := a.ruleDocument(execution, rule)
				if !actual[rule.ID] {
					if wasInstalled {
						a.mu.Lock()
						if a.activeLocked(execution.VDID, execution.Generation) {
							a.paused(a.runtimes[execution.VDID], "RULE_UNAVAILABLE")
						}
						a.mu.Unlock()
						healthy = false
						break
					}
					if _, err = a.engineRequest(ctx, "POST", "/rules", document); err != nil {
						healthy = false
						break
					}
					actual[rule.ID] = true
				}
				raw, err := a.engineRequest(ctx, "GET", "/rules/"+rule.ID, nil)
				if err != nil {
					healthy = false
					break
				}
				var installed map[string]any
				if json.Unmarshal(raw, &installed) != nil {
					healthy = false
					break
				}
				// eKuiper supplies default options, so compare only SQL and the owned sink contract.
				expectedJSON, _ := json.Marshal(document["actions"])
				actualJSON, _ := json.Marshal(installed["actions"])
				var expectedAction, actualAction any
				json.Unmarshal(expectedJSON, &expectedAction)
				json.Unmarshal(actualJSON, &actualAction)
				if installed["sql"] != rule.SQL || !reflect.DeepEqual(expectedAction, actualAction) {
					// A restarted agent has a new callback token; recreate only its own generation.
					if wasInstalled {
						healthy = false
						break
					}
					if _, err = a.engineRequest(ctx, "DELETE", "/rules/"+rule.ID, nil); err != nil {
						healthy = false
						break
					}
					if _, err = a.engineRequest(ctx, "POST", "/rules", document); err != nil {
						healthy = false
						break
					}
				}
				status, err := a.engineRequest(ctx, "GET", "/rules/"+rule.ID+"/status", nil)
				if err != nil {
					healthy = false
					break
				}
				var state map[string]any
				if json.Unmarshal(status, &state) != nil || state["status"] != "running" {
					healthy = false
					break
				}
			}
		}
		a.mu.Lock()
		r := a.runtimes[execution.VDID]
		if r != nil && r.present && r.desired.Generation == execution.Generation {
			if healthy {
				if !r.installed {
					r.installed = true
					if execution.Running {
						r.state = "WAITING_INPUT"
						r.reason = "INPUT_MISSING"
					} else {
						r.state = "STOPPED"
						r.reason = "STOPPED"
					}
				}
			} else if execution.Running {
				if r.installed {
					a.paused(r, "RULE_UNAVAILABLE")
				}
				r.state = "ERROR"
				r.reason = "RULE_UNAVAILABLE"
			}
		}
		a.mu.Unlock()
	}
	a.mu.Lock()
	a.engineReady = true
	for id, r := range a.runtimes {
		if !r.present {
			delete(a.runtimes, id)
		}
	}
	a.mu.Unlock()
	return nil
}
