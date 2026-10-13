package processing

import (
	"context"
	"errors"
	"net"
	"net/http"
	"sync"
	"time"
)

func (a *Agent) Run(parent context.Context) error {
	callback, err := net.Listen("tcp", a.config.CallbackAddress)
	if err != nil {
		return err
	}
	defer callback.Close()
	health, err := net.Listen("tcp", a.config.HealthAddress)
	if err != nil {
		return err
	}
	defer health.Close()
	a.config.CallbackAddress = callback.Addr().String()
	a.config.HealthAddress = health.Addr().String()
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	servers := []*http.Server{
		{Handler: a.CallbackHandler(), ReadHeaderTimeout: time.Second, ReadTimeout: 2 * time.Second, WriteTimeout: 2 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 4096},
		{Handler: a.HealthHandler(), ReadHeaderTimeout: time.Second, ReadTimeout: 2 * time.Second, WriteTimeout: 2 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 4096},
	}
	stopped := make(chan error, 2)
	go func() { stopped <- servers[0].Serve(callback) }()
	go func() { stopped <- servers[1].Serve(health) }()
	var group sync.WaitGroup
	for _, loop := range []func(context.Context){a.pollLoop, a.reconcileLoop, a.resetLoop, a.tickLoop, a.resultLoop} {
		a.startLoop(ctx, &group, loop)
	}
	for _, queue := range a.pushes {
		queue := queue
		a.startLoop(ctx, &group, func(ctx context.Context) { a.pushLoop(ctx, queue) })
	}
	select {
	case <-parent.Done():
	case err = <-stopped:
		if errors.Is(err, http.ErrServerClosed) {
			err = nil
		}
	}
	cancel()
	a.mu.Lock()
	a.lease = time.Time{}
	clients := []*brokerState{}
	for _, broker := range a.brokers {
		clients = append(clients, broker)
	}
	a.mu.Unlock()
	for _, broker := range clients {
		broker.client.Disconnect(0)
	}
	shutdown, stop := context.WithTimeout(context.Background(), 2*time.Second)
	defer stop()
	for _, server := range servers {
		server.Shutdown(shutdown)
	}
	group.Wait()
	a.client.CloseIdleConnections()
	return err
}
