package processing

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	mqtt "github.com/eclipse/paho.mqtt.golang"
	"math"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

type brokerState struct {
	client      mqtt.Client
	connected   bool
	connectedAt int64
	topics      map[string]bool
}

func inputKey(input Input) string { return input.Broker + "\n" + input.Topic }
func brokerOptions(address, caFile string) (*mqtt.ClientOptions, error) {
	uri, err := url.Parse(address)
	if err != nil || uri.Hostname() == "" || uri.User != nil || uri.RawQuery != "" || uri.Fragment != "" || uri.Path != "" && uri.Path != "/" || uri.Scheme != "tcp" && uri.Scheme != "ssl" && uri.Scheme != "tls" && uri.Scheme != "mqtt" && uri.Scheme != "mqtts" {
		return nil, fmt.Errorf("invalid MQTT output broker")
	}
	if uri.Scheme == "mqtt" {
		uri.Scheme = "tcp"
	}
	if uri.Scheme == "mqtts" || uri.Scheme == "tls" {
		uri.Scheme = "ssl"
	}
	uri.Path = ""
	options := mqtt.NewClientOptions().AddBroker(uri.String()).SetClientID("edgeai-processing-" + strings.ReplaceAll(randomSession(), "-", "")).SetCleanSession(true).SetAutoReconnect(true).SetConnectRetry(true).SetConnectRetryInterval(time.Second).SetConnectTimeout(2 * time.Second).SetKeepAlive(10 * time.Second).SetWriteTimeout(time.Second).SetOrderMatters(true)
	if uri.Scheme == "ssl" {
		tlsConfig := &tls.Config{MinVersion: tls.VersionTLS12}
		if caFile != "" {
			data, err := os.ReadFile(caFile)
			if err != nil {
				return nil, fmt.Errorf("broker CA unavailable")
			}
			roots, err := x509.SystemCertPool()
			if err != nil {
				roots = x509.NewCertPool()
			}
			if !roots.AppendCertsFromPEM(data) {
				return nil, fmt.Errorf("invalid broker CA")
			}
			tlsConfig.RootCAs = roots
		}
		options.SetTLSConfig(tlsConfig)
	}
	return options, nil
}
func (a *Agent) syncBrokers(ctx context.Context) {
	a.mu.Lock()
	needed := map[string]map[string]bool{}
	for _, input := range a.catalog {
		if needed[input.Broker] == nil {
			needed[input.Broker] = map[string]bool{}
		}
		needed[input.Broker][input.Topic] = true
	}
	removed := []*brokerState{}
	for address, broker := range a.brokers {
		if needed[address] == nil {
			delete(a.brokers, address)
			removed = append(removed, broker)
		}
	}
	created := []*brokerState{}
	for address, topics := range needed {
		broker := a.brokers[address]
		if broker == nil {
			options, err := brokerOptions(address, a.config.BrokerCAFile)
			if err != nil {
				continue
			}
			broker = &brokerState{topics: topics}
			a.brokers[address] = broker
			endpoint, state := address, broker
			options.SetOnConnectHandler(func(client mqtt.Client) {
				a.mu.Lock()
				if a.brokers[endpoint] != state || ctx.Err() != nil {
					a.mu.Unlock()
					client.Disconnect(0)
					return
				}
				state.connected = true
				state.connectedAt = time.Now().UnixMilli()
				subscriptions := map[string]byte{}
				for topic := range state.topics {
					subscriptions[topic] = 1
				}
				a.invalidateBrokerLocked(endpoint)
				a.mu.Unlock()
				go func() {
					token := client.SubscribeMultiple(subscriptions, func(_ mqtt.Client, message mqtt.Message) { a.observation(endpoint, message) })
					if !token.WaitTimeout(2*time.Second) || token.Error() != nil {
						a.mu.Lock()
						if a.brokers[endpoint] == state {
							state.connected = false
							a.invalidateBrokerLocked(endpoint)
						}
						a.mu.Unlock()
						client.Disconnect(0)
					}
				}()
			})
			options.SetConnectionLostHandler(func(_ mqtt.Client, _ error) {
				a.mu.Lock()
				if a.brokers[endpoint] == state {
					state.connected = false
					a.invalidateBrokerLocked(endpoint)
				}
				a.mu.Unlock()
			})
			broker.client = mqtt.NewClient(options)
			created = append(created, broker)
		} else {
			removedTopics := []string{}
			added := map[string]byte{}
			for topic := range broker.topics {
				if !topics[topic] {
					removedTopics = append(removedTopics, topic)
				}
			}
			for topic := range topics {
				if !broker.topics[topic] {
					added[topic] = 1
				}
			}
			broker.topics = topics
			if broker.connected {
				client, endpoint := broker.client, address
				if len(removedTopics) > 0 {
					client.Unsubscribe(removedTopics...)
				}
				if len(added) > 0 {
					client.SubscribeMultiple(added, func(_ mqtt.Client, message mqtt.Message) { a.observation(endpoint, message) })
				}
			}
		}
	}
	a.mu.Unlock()
	for _, broker := range removed {
		broker.client.Disconnect(0)
	}
	for _, broker := range created {
		if ctx.Err() == nil {
			broker.client.Connect()
		}
	}
}
func (a *Agent) invalidateBrokerLocked(address string) {
	for key, input := range a.catalog {
		if input.Broker == address {
			if previous, ok := a.observations[key]; ok {
				previous.observation.ObservedAt = 0
				a.observations[key] = previous
			}
		}
	}
	for _, r := range a.runtimes {
		if !r.present || !r.desired.Running {
			continue
		}
		for _, source := range r.desired.Sources {
			if source.Broker == address && r.warmTicks > 0 {
				a.paused(r, "INPUT_MISSING")
				break
			}
		}
	}
}
func (a *Agent) observation(address string, message mqtt.Message) {
	if message.Retained() || len(message.Payload()) > 16384 {
		a.metrics.invalidObservations.Add(1)
		return
	}
	var value Observation
	if decode(message.Payload(), &value) != nil {
		a.metrics.invalidObservations.Add(1)
		return
	}
	now := time.Now().UnixMilli()
	a.mu.Lock()
	defer a.mu.Unlock()
	key := address + "\n" + message.Topic()
	source, ok := a.catalog[key]
	broker := a.brokers[address]
	valid := ok && broker != nil && broker.connected && value.APIVersion == "edgeai.observation/v1" && value.Namespace == source.Namespace && value.DeviceName == source.DeviceID && value.Property == source.Property && value.SourceGeneration == source.SourceGeneration && value.ConfigurationSHA256 == source.ConfigurationSHA256 && value.Type == source.Type && value.Unit == source.Unit && value.SessionID != "" && len(value.SessionID) <= 64 && value.Sequence > 0 && value.Sequence <= 9007199254740991 && value.ObservedAt > 0 && value.ObservedAt <= now+1000 && value.ReceivedAt >= broker.connectedAt && value.ReceivedAt <= now+1000 && value.ReceivedAt >= now-2000 && value.ObservedAt <= value.ReceivedAt+1000 && (value.TimeBasis == "SOURCE" || value.TimeBasis == "RECEIVED" || value.TimeBasis == "COLLECTED")
	if !valid {
		a.metrics.invalidObservations.Add(1)
		return
	}
	if value.Type == "bool" || value.Type == "boolean" {
		_, valid = value.Value.(bool)
	} else {
		number, isNumber := value.Value.(json.Number)
		numeric, err := number.Float64()
		valid = isNumber && err == nil && !math.IsNaN(numeric) && !math.IsInf(numeric, 0)
		if valid {
			value.Value = numeric
		}
	}
	if !valid {
		a.metrics.invalidObservations.Add(1)
		return
	}
	previous, exists := a.observations[key]
	// Qualified v1 mappers persist a monotonically increasing incarnation
	// counter in canonical base36, independently of clock steps. Keep only the
	// monotonic high-water mark; an
	// unlimited number of restarts cannot fill a retired-session cache.
	started, err := strconv.ParseInt(value.SessionID, 36, 64)
	previousStart, _ := strconv.ParseInt(previous.observation.SessionID, 36, 64)
	if err != nil || started <= 0 || strconv.FormatInt(started, 36) != value.SessionID || exists && (started < previousStart || previous.observation.SessionID == value.SessionID && (value.Sequence <= previous.observation.Sequence || value.ObservedAt < previous.observation.ObservedAt)) {
		a.metrics.invalidObservations.Add(1)
		return
	}
	if exists && previous.observation.SessionID != value.SessionID {
		for _, r := range a.runtimes {
			for _, source := range r.desired.Sources {
				if inputKey(source) == key && r.warmTicks > 0 {
					a.paused(r, "INPUT_CHANGED")
					break
				}
			}
		}
	}
	a.observations[key] = observedInput{observation: value}
	a.metrics.observations.Add(1)
}
