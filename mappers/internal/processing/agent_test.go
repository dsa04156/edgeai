package processing

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestCallbackRequiresTheLocalCredentialAndAnActiveEvaluation(t *testing.T) {
	agent, err := New(Config{BackendURL: "http://127.0.0.1:18080", KuiperURL: "http://127.0.0.1:9081", PushURL: "http://127.0.0.1:10081/edgeai/evaluate", CallbackAddress: "127.0.0.1:10082", HealthAddress: "127.0.0.1:10083", PodUID: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", CredentialFile: "/test/credential", PodTokenFile: "/test/pod-token"})
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(agent.CallbackHandler())
	defer server.Close()
	response, err := http.Post(server.URL+"/callbacks/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb/cccccccc-cccc-4ccc-8ccc-cccccccccccc/value", "application/json", strings.NewReader(`{"value":21}`))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("callback without local credential returned %d", response.StatusCode)
	}
}

func TestAgentRejectsRemoteEngineEndpointsAndUnverifiedBackendTransport(t *testing.T) {
	for _, change := range []func(*Config){
		func(c *Config) { c.BackendURL = "http://control-plane.example:18080" },
		func(c *Config) { c.KuiperURL = "http://worker.example:9081" },
		func(c *Config) { c.CallbackAddress = "0.0.0.0:10082" },
		func(c *Config) { c.PushURL = "http://worker.example:10081/edgeai/evaluate" },
	} {
		config := Config{BackendURL: "http://127.0.0.1:18080", KuiperURL: "http://127.0.0.1:9081", PushURL: "http://127.0.0.1:10081/edgeai/evaluate", CallbackAddress: "127.0.0.1:10082", HealthAddress: "0.0.0.0:10083", PodUID: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", CredentialFile: "/test/credential", PodTokenFile: "/test/pod-token"}
		change(&config)
		if _, err := New(config); err == nil {
			t.Fatalf("unsafe endpoint accepted: %s %s %s", config.BackendURL, config.KuiperURL, config.CallbackAddress)
		}
	}
}
