package main

import (
	"context"
	"io.edgeai/mapper/internal/processing"
	"log"
	"os/signal"
	"syscall"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	agent, err := processing.New(processing.ConfigFromEnvironment())
	if err != nil {
		log.Fatal(err)
	}
	if err = agent.Run(ctx); err != nil {
		log.Fatal(err)
	}
}
