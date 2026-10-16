package main

import (
	"context"
	"encoding/json"
	"io.edgeai/mapper/internal/driver"
	"log"
	"net/http"
	"os"
	"sync/atomic"
	"time"

	"github.com/kubeedge/mapper-framework/pkg/config"
	"github.com/kubeedge/mapper-framework/pkg/grpcserver"
	"io.edgeai/mapper/internal/dmi"
	runtime "io.edgeai/mapper/internal/runtime"
)

func main() {
	cfg, err := config.Parse()
	if err != nil {
		log.Fatal(err)
	}
	c := dmi.Client{Socket: cfg.Common.EdgeCoreSock, MapperSocket: cfg.GrpcServer.SocketPath, Protocol: cfg.Common.Protocol, Name: cfg.Common.Name, BudgetPath: os.Getenv("DMI_BUDGET_FILE")}
	namespace := os.Getenv("DEVICE_NAMESPACE")
	if namespace == "" {
		namespace = "edgeai-devices"
	}
	panel := runtime.New(namespace, c.Protocol, c)
	server := grpcserver.NewServer(grpcserver.Config{SockPath: c.MapperSocket, Protocol: c.Protocol}, panel)
	go func() {
		if err := server.Start(); err != nil {
			log.Fatal(err)
		}
	}()
	var ready atomic.Bool
	var health dmi.RecoveryHealth
	mux := http.NewServeMux()
	mux.HandleFunc("GET /devices/{id}/preview", func(w http.ResponseWriter, r *http.Request) {
		frame, at, err := panel.Preview(r.Context(), r.PathValue("id"), r.URL.Query().Get("configurationSha256"))
		if err != nil {
			http.Error(w, "fresh camera frame unavailable", 503)
			return
		}
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-EdgeAI-Frame-Time", at.UTC().Format(time.RFC3339Nano))
		_, _ = w.Write(frame)
	})
	mux.HandleFunc("GET /capabilities", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(map[string]any{"apiVersion": "edgeai.mapper-capabilities/v1", "protocol": c.Protocol, "observationApiVersion": "edgeai.observation/v1", "configurationHash": true, "originalObservedAt": true, "sessionOrdering": "PERSISTED_MONOTONIC", "qualifiedObservationEnabled": c.BudgetPath != ""})
	})
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		if !ready.Load() {
			http.Error(w, "DMI unavailable", 503)
			return
		}
		w.WriteHeader(200)
	})
	mux.HandleFunc("POST /connections/test", func(w http.ResponseWriter, r *http.Request) {
		var input struct {
			Connection driver.Connection `json:"connection"`
			Property   driver.Property   `json:"property"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, 16384)
		if json.NewDecoder(r.Body).Decode(&input) != nil {
			http.Error(w, "invalid request", 400)
			return
		}
		reader, err := driver.New(c.Protocol, input.Connection)
		if err != nil {
			http.Error(w, "invalid configuration", 400)
			return
		}
		defer reader.Close()
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		value, err := reader.Read(ctx, input.Property)
		w.Header().Set("Content-Type", "application/json")
		if err != nil {
			w.WriteHeader(422)
			_ = json.NewEncoder(w).Encode(map[string]any{"connected": false, "error": "device property read failed"})
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"connected": true, "value": value})
	})
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		if !health.Healthy(time.Now()) {
			http.Error(w, "DMI socket connection stalled; container recycle required", 503)
			return
		}
		w.WriteHeader(200)
		_, _ = w.Write([]byte("ok"))
	})
	mux.HandleFunc("GET /devices", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(panel.Snapshot())
	})
	go func() {
		log.Fatal((&http.Server{Addr: ":" + cfg.Common.HTTPPort, Handler: mux, ReadHeaderTimeout: 3 * time.Second}).ListenAndServe())
	}()
	// DMI callbacks deliver changes. Periodic authoritative registration also recovers
	// after EdgeCore restarts or a callback loss without creating a Mapper per sensor.
	for {
		devices, models, err := c.Register()
		if err == nil {
			err = panel.DevInit(devices, models)
		}
		health.Record(err, time.Now())
		ready.Store(err == nil)
		if err != nil {
			log.Printf("DMI registration/synchronization unavailable: %v", err)
		} else {
			log.Printf("DMI synchronized devices=%d models=%d", len(devices), len(models))
		}
		time.Sleep(30 * time.Second)
	}
}
