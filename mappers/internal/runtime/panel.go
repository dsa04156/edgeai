package runtime

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"strconv"
	"strings"
	"sync"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
	api "github.com/kubeedge/api/apis/dmi/v1beta1"
	"github.com/kubeedge/mapper-framework/pkg/common"
	"github.com/kubeedge/mapper-framework/pkg/global"
	"github.com/kubeedge/mapper-framework/pkg/util/parse"
	"io.edgeai/mapper/internal/dmi"
	"io.edgeai/mapper/internal/driver"
)

type Device struct {
	Instance common.DeviceInstance
	State    string `json:"state"`
	LastSeen int64  `json:"lastSeen"`
	Error    string `json:"error,omitempty"`
}

type managed struct {
	value   Device
	cancel  context.CancelFunc
	preview interface {
		Preview(context.Context) ([]byte, time.Time, error)
	}
	done chan struct{}
}

type Panel struct {
	lifecycle           sync.Mutex
	mu                  sync.RWMutex
	Namespace, Protocol string
	DMI                 dmi.Client
	devices             map[string]*managed
	models              map[string]common.DeviceModel
}

var _ global.DevPanel = (*Panel)(nil)

func New(namespace, protocol string, c dmi.Client) *Panel {
	return &Panel{Namespace: namespace, Protocol: protocol, DMI: c, devices: map[string]*managed{}, models: map[string]common.DeviceModel{}}
}
func (p *Panel) DevStart() {}
func (p *Panel) DevInit(devices []*api.Device, models []*api.DeviceModel) error {
	p.lifecycle.Lock()
	defer p.lifecycle.Unlock()
	for _, m := range models {
		if m.Namespace == p.Namespace {
			v := parse.GetDeviceModelFromGrpc(m)
			p.UpdateModel(&v)
		}
	}
	seen := map[string]bool{}
	for _, d := range devices {
		if d.Namespace != p.Namespace {
			continue
		}
		m, err := p.GetModel(parse.GetResourceID(d.Namespace, d.Spec.DeviceModelReference))
		if err != nil {
			return err
		}
		v, err := parse.GetDeviceFromGrpc(d, &m)
		if err != nil {
			return err
		}
		v.PProtocol, err = parse.BuildProtocolFromGrpc(d)
		if err != nil {
			return err
		}
		seen[v.ID] = true
		p.updateDev(&m, v)
	}
	p.mu.RLock()
	var removed []string
	for id := range p.devices {
		if !seen[id] {
			removed = append(removed, id)
		}
	}
	p.mu.RUnlock()
	for _, id := range removed {
		_ = p.removeDevice(id)
	}
	return nil
}

func (p *Panel) UpdateDev(model *common.DeviceModel, d *common.DeviceInstance) {
	p.lifecycle.Lock()
	defer p.lifecycle.Unlock()
	p.updateDev(model, d)
}
func (p *Panel) updateDev(_ *common.DeviceModel, d *common.DeviceInstance) {
	if d.Namespace != p.Namespace || (d.PProtocol.ProtocolName != "" && d.PProtocol.ProtocolName != p.Protocol) {
		return
	}
	p.mu.RLock()
	old := p.devices[d.ID]
	same := false
	if old != nil {
		a, _ := json.Marshal(old.value.Instance.PProtocol)
		b, _ := json.Marshal(d.PProtocol)
		x, _ := json.Marshal(old.value.Instance.Properties)
		y, _ := json.Marshal(d.Properties)
		same = string(a) == string(b) && string(x) == string(y)
	}
	p.mu.RUnlock()
	if same {
		return
	}
	if old != nil {
		_ = p.removeDevice(d.ID)
	}
	ctx, cancel := context.WithCancel(context.Background())
	m := &managed{value: Device{Instance: *d, State: "unknown"}, cancel: cancel, done: make(chan struct{})}
	p.mu.Lock()
	p.devices[d.ID] = m
	p.mu.Unlock()
	go func() { defer close(m.done); p.collect(ctx, m) }()
}

func (p *Panel) collect(ctx context.Context, m *managed) {
	var connection struct {
		ConfigData driver.Connection `json:"configData"`
	}
	if err := json.Unmarshal(m.value.Instance.PProtocol.ConfigData, &connection); err != nil {
		p.failed(m, "invalid connection settings")
		return
	}
	client, err := driver.New(p.Protocol, connection.ConfigData)
	if err != nil {
		p.failed(m, "invalid protocol configuration")
		return
	}
	defer client.Close()
	if preview, ok := client.(interface {
		Preview(context.Context) ([]byte, time.Time, error)
	}); ok {
		p.mu.Lock()
		m.preview = preview
		p.mu.Unlock()
	}
	inst := m.value.Instance
	type item struct {
		name, typ string
		visitor   driver.Property
		push      *api.PushMethodMQTT
	}
	props := []item{}
	interval := time.Duration(0)
	reportInterval := 5 * time.Second
	for _, prop := range inst.Properties {
		if prop.Name == "connection" {
			continue
		}
		var visitor struct {
			ConfigData driver.Property `json:"configData"`
		}
		if err := json.Unmarshal(prop.Visitors, &visitor); err != nil {
			p.failed(m, "invalid property settings")
			return
		}
		if prop.CollectCycle < 100 || prop.CollectCycle > 60000 {
			p.failed(m, "invalid collection interval")
			return
		}
		cycle := time.Duration(prop.CollectCycle) * time.Millisecond
		if interval == 0 || cycle < interval {
			interval = cycle
		}
		if prop.ReportCycle >= 2000 {
			reportInterval = time.Duration(prop.ReportCycle) * time.Millisecond
		}
		var push api.PushMethodMQTT
		if prop.PushMethod.MethodName != "mqtt" || json.Unmarshal(prop.PushMethod.MethodConfig, &push) != nil {
			p.failed(m, "MQTT data plane is required")
			return
		}
		typ := strings.ToLower(prop.PProperty.DataType)
		if visitor.ConfigData.ConfigurationSha256 != "" {
			switch visitor.ConfigData.DataType {
			case "float32":
				typ = "float"
			case "float64":
				typ = "double"
			case "bool":
				typ = "boolean"
			case "string":
				typ = "string"
			default:
				typ = "int"
			}
		}
		props = append(props, item{prop.Name, typ, visitor.ConfigData, &push})
	}
	if len(props) == 0 {
		p.failed(m, "no data properties")
		return
	}
	var sessions <-chan string
	for _, prop := range props {
		if prop.visitor.SourceGeneration != "" && prop.visitor.ConfigurationSha256 != "" {
			sessions = observationSession(ctx, p.DMI.BudgetPath)
			break
		}
	}
	// One MQTT client per managed device; all devices share this protocol Mapper.
	opts := mqtt.NewClientOptions().AddBroker(props[0].push.Address).SetClientID("edgeai-" + inst.Name + "-" + strconv.FormatInt(time.Now().UnixNano(), 36)).SetConnectTimeout(2 * time.Second).SetAutoReconnect(true).SetConnectRetry(false)
	publisher := mqtt.NewClient(opts)
	defer publisher.Disconnect(100)
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	lastReport := time.Time{}
	sessionID := ""
	var sequence uint64
	previousState := "unknown"
	type report struct {
		state string
		twins []*api.Twin
	}
	reports := make(chan report, 1)
	var reportMu sync.RWMutex
	var latest report
	reporterDone := make(chan struct{})
	go func() {
		defer close(reporterDone)
		for {
			var value report
			select {
			case <-ctx.Done():
				return
			case value = <-reports:
			}
			if e := p.DMI.ReportLatestContext(ctx, inst.Namespace, inst.Name, value.state, value.twins, func(name string) *api.Twin {
				reportMu.RLock()
				defer reportMu.RUnlock()
				if latest.state == "online" {
					for _, twin := range latest.twins {
						if twin.PropertyName == name {
							return twin
						}
					}
				}
				return nil
			}); e != nil && ctx.Err() == nil {
				log.Printf("DMI report failed device=%s: %v", inst.Name, e)
			}
		}
	}()
	defer func() { <-reporterDone }()
	for {
		now := time.Now()
		state := "online"
		values := map[string]string{}
		observations := map[string]driver.Observation{}
		lastError := ""
		if stream, ok := client.(interface {
			BeginCycle(context.Context, []driver.Property) error
		}); ok {
			bindings := make([]driver.Property, 0, len(props))
			for _, prop := range props {
				bindings = append(bindings, prop.visitor)
			}
			if stream.BeginCycle(ctx, bindings) != nil {
				state = "offline"
				lastError = "fresh hardware sample unavailable"
			}
		}
		for _, prop := range props {
			if state == "offline" {
				break
			}
			observation, e := driver.ReadObservation(ctx, client, prop.visitor)
			if e != nil {
				state = "offline"
				lastError = "device property read failed"
				break
			}
			values[prop.name] = observation.Value
			observations[prop.name] = observation
		}
		if ctx.Err() != nil {
			return
		}
		if state == "online" {
			p.state(m, state, now.UnixMilli(), "")
			p.mu.Lock()
			for i := range m.value.Instance.Twins {
				if v, ok := values[m.value.Instance.Twins[i].PropertyName]; ok {
					m.value.Instance.Twins[i].Reported = common.TwinProperty{Value: v, Metadata: common.Metadata{Type: m.value.Instance.Twins[i].ObservedDesired.Metadata.Type, Timestamp: strconv.FormatInt(observations[m.value.Instance.Twins[i].PropertyName].ObservedAt, 10)}}
				}
			}
			p.mu.Unlock()
			if !publisher.IsConnected() {
				t := publisher.Connect()
				if !t.WaitTimeout(2*time.Second) || t.Error() != nil {
					lastError = "MQTT data plane unavailable"
				}
			}
			if publisher.IsConnected() {
				for _, prop := range props {
					observation := observations[prop.name]
					model := common.NewDataModel(inst.Name, prop.name, inst.Namespace, common.WithType(prop.typ), common.WithValue(values[prop.name]), common.WithTimeStamp(observation.ObservedAt))
					payload, _ := json.Marshal(model)
					t := publisher.Publish(prop.push.Topic, byte(prop.push.Qos), prop.push.Retained, payload)
					if !t.WaitTimeout(2*time.Second) || t.Error() != nil {
						lastError = "MQTT delivery failed"
					}
					if prop.visitor.SourceGeneration != "" && prop.visitor.ConfigurationSha256 != "" {
						if sessionID == "" {
							select {
							case sessionID = <-sessions:
							default:
							}
						}
						if sessionID == "" {
							lastError = "durable observation session unavailable"
							continue
						}
						sequence++
						var typed any = observation.Value
						var typeError error
						if prop.visitor.DataType == "bool" {
							typed, typeError = strconv.ParseBool(observation.Value)
						} else if prop.visitor.DataType != "string" {
							typed, typeError = strconv.ParseFloat(observation.Value, 64)
						}
						if typeError != nil {
							lastError = "invalid qualified observation type"
							continue
						}
						qualified, marshalError := json.Marshal(map[string]any{
							"apiVersion": "edgeai.observation/v1", "namespace": inst.Namespace, "deviceName": inst.Name, "property": prop.name,
							"sourceGeneration": prop.visitor.SourceGeneration, "configurationSha256": prop.visitor.ConfigurationSha256,
							"observedAt": observation.ObservedAt, "receivedAt": time.Now().UnixMilli(), "timeBasis": observation.TimeBasis,
							"sessionId": sessionID, "sequence": sequence, "type": prop.visitor.DataType, "unit": prop.visitor.Unit, "value": typed,
						})
						if marshalError != nil {
							lastError = "invalid qualified observation"
						} else {
							qualifiedToken := publisher.Publish("edgeai/observations/v1/"+inst.Namespace+"/"+inst.Name+"/"+prop.name, 1, false, qualified)
							if !qualifiedToken.WaitTimeout(2*time.Second) || qualifiedToken.Error() != nil {
								lastError = "qualified observation delivery failed"
							}
						}
					}
				}
			}
		} else {
			p.state(m, state, 0, lastError)
		}
		if lastError != "" && state == "online" {
			p.mu.Lock()
			m.value.Error = lastError
			p.mu.Unlock()
		}
		var twins []*api.Twin
		if state == "online" {
			for _, prop := range props {
				metadata := map[string]string{"timestamp": strconv.FormatInt(observations[prop.name].ObservedAt, 10), "type": prop.typ}
				value := values[prop.name]
				if prop.visitor.ConfigurationSha256 != "" {
					// KubeEdge 1.23 only preserves Reported.Value; it drops the
					// supplied metadata and generates its own timestamp.
					observation, _ := json.Marshal(map[string]any{"apiVersion": "edgeai.measurement/v1", "value": value, "configurationSha256": prop.visitor.ConfigurationSha256, "collectedAt": observations[prop.name].ObservedAt})
					// Twin values accept a restricted alphabet. Raw URL base64
					// avoids forbidden JSON punctuation and '=' padding.
					value = "edgeai.measurement/v1:" + base64.RawURLEncoding.EncodeToString(observation)
					metadata["type"] = "string"
					if len(value) > 512 {
						state = "offline"
						p.state(m, state, 0, "DMI observation exceeds 512 characters")
						twins = nil
						break
					}
				}
				twins = append(twins, &api.Twin{PropertyName: prop.name, ObservedDesired: &api.TwinProperty{Value: ""}, Reported: &api.TwinProperty{Value: value, Metadata: metadata}})
			}
		}
		value := report{state, twins}
		reportMu.Lock()
		latest = value
		reportMu.Unlock()
		if now.Sub(lastReport) >= reportInterval || state != previousState {
			// Coalesce latest reports while the node DMI budget is busy. Collection and
			// MQTT telemetry keep running independently of the control-plane queue.
			select {
			case reports <- value:
			default:
				select {
				case <-reports:
				default:
				}
				select {
				case reports <- value:
				default:
				}
			}
			lastReport = now
			previousState = state
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (p *Panel) failed(m *managed, message string) {
	p.state(m, "offline", 0, message)
	_ = p.DMI.Report(m.value.Instance.Namespace, m.value.Instance.Name, "offline", nil)
}
func (p *Panel) state(m *managed, state string, last int64, err string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	m.value.State = state
	m.value.Error = err
	if last > 0 {
		m.value.LastSeen = last
	}
}
func (p *Panel) Preview(ctx context.Context, name, configuration string) ([]byte, time.Time, error) {
	p.mu.RLock()
	var preview interface {
		Preview(context.Context) ([]byte, time.Time, error)
	}
	for _, m := range p.devices {
		if m.value.Instance.Name != name {
			continue
		}
		for _, property := range m.value.Instance.Properties {
			var visitor struct {
				ConfigData driver.Property `json:"configData"`
			}
			if json.Unmarshal(property.Visitors, &visitor) == nil && configuration != "" && visitor.ConfigData.ConfigurationSha256 == configuration {
				preview = m.preview
				break
			}
		}
	}
	p.mu.RUnlock()
	if preview == nil {
		return nil, time.Time{}, fmt.Errorf("camera preview configuration unavailable")
	}
	return preview.Preview(ctx)
}

func (p *Panel) GetDevice(id string) (interface{}, error) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	m := p.devices[id]
	if m == nil {
		return nil, fmt.Errorf("device not found")
	}
	b, _ := json.Marshal(m.value)
	var v Device
	_ = json.Unmarshal(b, &v)
	return v, nil
}
func (p *Panel) RemoveDevice(id string) error {
	p.lifecycle.Lock()
	defer p.lifecycle.Unlock()
	return p.removeDevice(id)
}
func (p *Panel) removeDevice(id string) error {
	p.mu.Lock()
	m := p.devices[id]
	if m != nil {
		delete(p.devices, id)
		m.cancel()
	}
	p.mu.Unlock()
	if m == nil {
		return fmt.Errorf("device not found")
	}
	<-m.done
	return nil
}
func (p *Panel) GetModel(id string) (common.DeviceModel, error) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	m, ok := p.models[id]
	if !ok {
		return m, fmt.Errorf("model not found")
	}
	return m, nil
}
func (p *Panel) UpdateModel(m *common.DeviceModel) {
	if m.Namespace != p.Namespace {
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	p.models[m.ID] = *m
}
func (p *Panel) RemoveModel(id string) { p.mu.Lock(); defer p.mu.Unlock(); delete(p.models, id) }
func (p *Panel) GetTwinResult(id, name string) (string, string, error) {
	v, err := p.GetDevice(id)
	if err != nil {
		return "", "", err
	}
	d := v.(Device)
	for _, t := range d.Instance.Twins {
		if t.PropertyName == name {
			return t.Reported.Value, t.Reported.Metadata.Type, nil
		}
	}
	return "", "", fmt.Errorf("property not found")
}
func (p *Panel) DealDeviceTwinGet(id, name string) (interface{}, error) {
	v, t, e := p.GetTwinResult(id, name)
	return map[string]string{"value": v, "type": t}, e
}
func (p *Panel) UpdateDevTwins(_ string, _ []common.Twin) error {
	return fmt.Errorf("read-only protocol driver")
}
func (p *Panel) WriteDevice(_, _, _, _ string) error { return fmt.Errorf("read-only protocol driver") }
func (p *Panel) GetDeviceMethod(id string) (map[string][]string, map[string]string, error) {
	_, e := p.GetDevice(id)
	return map[string][]string{}, map[string]string{}, e
}
func (p *Panel) Snapshot() []Device {
	p.mu.RLock()
	defer p.mu.RUnlock()
	result := []Device{}
	for _, m := range p.devices {
		b, _ := json.Marshal(m.value)
		var v Device
		_ = json.Unmarshal(b, &v)
		result = append(result, v)
	}
	return result
}
