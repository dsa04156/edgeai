package driver

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"testing"
)

func TestHTTPObservationPreservesSourceTimestampAcrossRepeatedPolls(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"temperature":21.5,"measured":1700000000123}`)
	}))
	defer server.Close()
	endpoint, _ := url.Parse(server.URL)
	port, _ := strconv.Atoi(endpoint.Port())
	reader, err := New("edgeai-virtual", Connection{Host: endpoint.Hostname(), Port: port, Path: "/", TimeoutMs: 1000})
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	property := Property{DataType: "float64", Scale: 1, JSONPath: "temperature", TimestampPath: "measured"}
	for range 2 {
		observation, err := ReadObservation(context.Background(), reader, property)
		if err != nil {
			t.Fatal(err)
		}
		if observation.Value != "21.5" || observation.ObservedAt != 1700000000123 || observation.TimeBasis != "SOURCE" {
			t.Fatalf("source observation was altered: %+v", observation)
		}
	}
}
