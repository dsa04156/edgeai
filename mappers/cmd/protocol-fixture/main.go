// Test-only runtime: real OPC-UA service, periodic MQTT input and virtual JSON endpoint.
package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
	"github.com/gopcua/opcua/server"
	"github.com/gopcua/opcua/ua"
)

func main() {
	s := server.New(server.EndPoint("0.0.0.0", 4841), server.EndPoint("10.77.0.5", 4841), server.EnableSecurity("None", ua.MessageSecurityModeNone), server.EnableAuthMode(ua.UserTokenTypeAnonymous))
	ns := server.NewMapNamespace(s, "edgeai-test")
	ns.Data["temperature"] = float32(31.25)
	if err := s.Start(context.Background()); err != nil {
		log.Fatal(err)
	}
	defer s.Close()
	mux := http.NewServeMux()
	mux.HandleFunc("GET /properties", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"temperature": 42.5, "vibration_x": 0.75})
	})
	go func() {
		log.Fatal((&http.Server{Addr: ":18090", Handler: mux, ReadHeaderTimeout: 3 * time.Second}).ListenAndServe())
	}()
	broker := os.Getenv("MQTT_BROKER")
	if broker == "" {
		broker = "tcp://10.77.0.4:1883"
	}
	c := mqtt.NewClient(mqtt.NewClientOptions().AddBroker(broker).SetClientID("edgeai-protocol-fixture").SetConnectTimeout(time.Second).SetAutoReconnect(true))
	for {
		if !c.IsConnected() {
			t := c.Connect()
			if !t.WaitTimeout(2*time.Second) || t.Error() != nil {
				time.Sleep(time.Second)
				continue
			}
		}
		payload, _ := json.Marshal(map[string]any{"temperature": 27.75})
		t := c.Publish("edgeai-test/input/sensor", 1, false, payload)
		t.WaitTimeout(time.Second)
		time.Sleep(500 * time.Millisecond)
	}
}
