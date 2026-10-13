package driver

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

type Observation struct {
	Value      string
	ObservedAt int64
	TimeBasis  string
}

// Existing polling drivers remain compatible; cached and timestamped sources
// implement ReadObservation so a poll cannot refresh an old measurement.
func ReadObservation(ctx context.Context, reader Reader, property Property) (Observation, error) {
	if source, ok := reader.(interface {
		ReadObservation(context.Context, Property) (Observation, error)
	}); ok {
		return source.ReadObservation(ctx, property)
	}
	value, err := reader.Read(ctx, property)
	return Observation{value, time.Now().UnixMilli(), "COLLECTED"}, err
}

func jsonObservation(payload []byte, p Property, received time.Time) (Observation, error) {
	value, err := jsonValue(payload, p)
	if err != nil {
		return Observation{}, err
	}
	result := Observation{value, received.UnixMilli(), "RECEIVED"}
	if p.TimestampPath == "" {
		return result, nil
	}
	var decoded any
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.UseNumber()
	if err = decoder.Decode(&decoded); err != nil {
		return Observation{}, err
	}
	for _, part := range strings.Split(p.TimestampPath, ".") {
		object, ok := decoded.(map[string]any)
		if !ok {
			return Observation{}, fmt.Errorf("source timestamp missing")
		}
		decoded = object[part]
	}
	number, ok := decoded.(json.Number)
	if !ok {
		return Observation{}, fmt.Errorf("source timestamp must be Unix milliseconds")
	}
	observed, err := number.Int64()
	if err != nil || observed <= 0 {
		return Observation{}, fmt.Errorf("invalid source timestamp")
	}
	result.ObservedAt = observed
	result.TimeBasis = "SOURCE"
	return result, nil
}
