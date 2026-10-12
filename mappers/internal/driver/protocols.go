package driver

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
	"github.com/gopcua/opcua"
	"github.com/gopcua/opcua/ua"
)

// New is the only protocol switch. The runtime, DMI and property data model are shared.
func New(protocol string, c Connection) (Reader, error) {
	if protocol == "edgeai-serial-json" || protocol == "edgeai-sensehat" {
		return newHardware(protocol, c)
	}
	if c.Host == "" || c.Port < 1 || c.Port > 65535 || c.TimeoutMs < 100 || c.TimeoutMs > 5000 {
		return nil, fmt.Errorf("invalid connection")
	}
	switch protocol {
	case "edgeai-modbus-tcp":
		return NewModbus(c)
	case "edgeai-opc-ua":
		if c.SecurityMode != "None" {
			return nil, fmt.Errorf("only explicit anonymous/None OPC-UA is currently supported")
		}
		return &OPCUA{config: c}, nil
	case "edgeai-mqtt":
		if c.StaleMs < 100 || c.StaleMs > 60000 {
			return nil, fmt.Errorf("invalid message freshness")
		}
		m := &MQTT{config: c, topics: map[string]bool{}, messages: map[string]message{}}
		opts := mqtt.NewClientOptions().AddBroker("tcp://" + net.JoinHostPort(c.Host, strconv.Itoa(c.Port))).SetClientID("edgeai-input-" + strconv.FormatInt(time.Now().UnixNano(), 36)).SetConnectTimeout(time.Duration(c.TimeoutMs) * time.Millisecond).SetAutoReconnect(false)
		m.client = mqtt.NewClient(opts)
		return m, nil
	case "edgeai-virtual":
		if !strings.HasPrefix(c.Path, "/") || strings.ContainsAny(c.Path, "?#") {
			return nil, fmt.Errorf("invalid runtime property path")
		}
		return &Virtual{config: c, client: &http.Client{Timeout: time.Duration(c.TimeoutMs) * time.Millisecond, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}, nil
	default:
		return nil, fmt.Errorf("protocol driver not installed")
	}
}

type OPCUA struct {
	config Connection
	client *opcua.Client
}

func (o *OPCUA) Close() error {
	if o.client == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	err := o.client.Close(ctx)
	o.client = nil
	return err
}
func (o *OPCUA) Read(ctx context.Context, p Property) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, time.Duration(o.config.TimeoutMs)*time.Millisecond)
	defer cancel()
	id, err := ua.ParseNodeID(p.NodeID)
	if err != nil {
		return "", err
	}
	if o.client == nil {
		o.client, err = opcua.NewClient("opc.tcp://"+net.JoinHostPort(o.config.Host, strconv.Itoa(o.config.Port))+o.config.Path, opcua.SecurityMode(ua.MessageSecurityModeNone), opcua.SecurityPolicy("None"), opcua.AuthAnonymous(), opcua.RequestTimeout(time.Duration(o.config.TimeoutMs)*time.Millisecond), opcua.AutoReconnect(false))
		if err != nil {
			return "", err
		}
		if err = o.client.Connect(ctx); err != nil {
			_ = o.Close()
			return "", err
		}
	}
	response, err := o.client.Read(ctx, &ua.ReadRequest{NodesToRead: []*ua.ReadValueID{{NodeID: id, AttributeID: ua.AttributeIDValue}}, TimestampsToReturn: ua.TimestampsToReturnBoth})
	if err != nil {
		_ = o.Close()
		return "", err
	}
	if len(response.Results) != 1 || response.Results[0].Status != ua.StatusOK || response.Results[0].Value == nil {
		return "", fmt.Errorf("OPC-UA property unavailable")
	}
	return normalize(response.Results[0].Value.Value(), p)
}

type message struct {
	payload  []byte
	received time.Time
}
type MQTT struct {
	config   Connection
	client   mqtt.Client
	mu       sync.Mutex
	topics   map[string]bool
	messages map[string]message
}

func (m *MQTT) Close() error { m.client.Disconnect(100); return nil }
func (m *MQTT) Read(ctx context.Context, p Property) (string, error) {
	if p.Topic == "" || strings.ContainsAny(p.Topic, "#+") || strings.HasPrefix(p.Topic, "edgeai/devices/") {
		return "", fmt.Errorf("invalid input topic")
	}
	timeout := time.Duration(m.config.TimeoutMs) * time.Millisecond
	if !m.client.IsConnected() {
		t := m.client.Connect()
		if !t.WaitTimeout(timeout) || t.Error() != nil {
			return "", fmt.Errorf("MQTT broker unavailable")
		}
		m.mu.Lock()
		m.topics = map[string]bool{}
		m.messages = map[string]message{}
		m.mu.Unlock()
	}
	m.mu.Lock()
	subscribed := m.topics[p.Topic]
	m.mu.Unlock()
	if !subscribed {
		token := m.client.Subscribe(p.Topic, 1, func(_ mqtt.Client, msg mqtt.Message) {
			m.mu.Lock()
			m.messages[msg.Topic()] = message{append([]byte(nil), msg.Payload()...), time.Now()}
			m.mu.Unlock()
		})
		if !token.WaitTimeout(timeout) || token.Error() != nil {
			return "", fmt.Errorf("MQTT subscription failed")
		}
		m.mu.Lock()
		m.topics[p.Topic] = true
		m.mu.Unlock()
	}
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	poll := time.NewTicker(20 * time.Millisecond)
	defer poll.Stop()
	for {
		m.mu.Lock()
		v, ok := m.messages[p.Topic]
		m.mu.Unlock()
		if ok && time.Since(v.received) <= time.Duration(m.config.StaleMs)*time.Millisecond {
			return jsonValue(v.payload, p)
		}
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-deadline.C:
			return "", fmt.Errorf("MQTT property stale or missing")
		case <-poll.C:
		}
	}
}

// A container, emulator or AI service supplies a JSON property endpoint. It is
// registered as a virtual Device CRD and has the same twins and telemetry as physical devices.
type Virtual struct {
	config Connection
	client *http.Client
}

func (v *Virtual) Close() error { v.client.CloseIdleConnections(); return nil }
func (v *Virtual) Read(ctx context.Context, p Property) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://"+net.JoinHostPort(v.config.Host, strconv.Itoa(v.config.Port))+v.config.Path, nil)
	if err != nil {
		return "", err
	}
	resp, err := v.client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("runtime property endpoint unavailable")
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 65537))
	if err != nil || len(b) > 65536 {
		return "", fmt.Errorf("runtime response exceeds limit")
	}
	return jsonValue(b, p)
}
func jsonValue(payload []byte, p Property) (string, error) {
	if len(payload) > 65536 {
		return "", fmt.Errorf("property payload exceeds limit")
	}
	dec := json.NewDecoder(bytes.NewReader(payload))
	dec.UseNumber()
	var value any
	if err := dec.Decode(&value); err != nil {
		return "", err
	}
	var extra any
	if dec.Decode(&extra) != io.EOF {
		return "", fmt.Errorf("invalid JSON payload")
	}
	if p.JSONPath != "" {
		for _, part := range strings.Split(p.JSONPath, ".") {
			object, ok := value.(map[string]any)
			if !ok {
				return "", fmt.Errorf("property path missing")
			}
			value, ok = object[part]
			if !ok {
				return "", fmt.Errorf("property path missing")
			}
		}
	}
	return normalize(value, p)
}
func normalize(value any, p Property) (string, error) {
	if p.DataType == "string" {
		s, ok := value.(string)
		if !ok || len(s) > 4096 {
			return "", fmt.Errorf("string property required")
		}
		return s, nil
	}
	if p.DataType == "bool" {
		b, ok := value.(bool)
		if !ok {
			return "", fmt.Errorf("boolean property required")
		}
		return strconv.FormatBool(b), nil
	}
	var n float64
	switch x := value.(type) {
	case json.Number:
		n, _ = x.Float64()
	case float32:
		n = float64(x)
	case float64:
		n = x
	case int16:
		n = float64(x)
	case uint16:
		n = float64(x)
	case int32:
		n = float64(x)
	case uint32:
		n = float64(x)
	case int:
		n = float64(x)
	case int64:
		n = float64(x)
	case uint64:
		n = float64(x)
	default:
		return "", fmt.Errorf("numeric property required")
	}
	if !strings.HasPrefix(p.DataType, "float") {
		var lo, hi float64
		switch p.DataType {
		case "int16":
			lo, hi = -32768, 32767
		case "uint16":
			lo, hi = 0, 65535
		case "int32":
			lo, hi = -2147483648, 2147483647
		case "uint32":
			lo, hi = 0, 4294967295
		default:
			return "", fmt.Errorf("unsupported data type")
		}
		if n != math.Trunc(n) || n < lo || n > hi {
			return "", fmt.Errorf("integer property out of range")
		}
	}
	n = n*p.Scale + p.Offset
	if math.IsNaN(n) || math.IsInf(n, 0) {
		return "", fmt.Errorf("non-finite sensor value")
	}
	return strconv.FormatFloat(n, 'g', -1, 64), nil
}
