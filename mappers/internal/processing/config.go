package processing

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"github.com/google/uuid"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

type Config struct {
	BackendURL, KuiperURL, PushURL                     string
	CallbackAddress, HealthAddress                     string
	CredentialFile, PodTokenFile, CAFile, BrokerCAFile string
	PodUID                                             string
}

func ConfigFromEnvironment() Config {
	value := func(name, fallback string) string {
		if v := os.Getenv(name); v != "" {
			return v
		}
		return fallback
	}
	return Config{BackendURL: os.Getenv("EDGEAI_PROCESSING_BACKEND_URL"), KuiperURL: value("EDGEAI_PROCESSING_KUIPER_URL", "http://127.0.0.1:9081"), PushURL: value("EDGEAI_PROCESSING_PUSH_URL", "http://127.0.0.1:10081/edgeai/evaluate"), CallbackAddress: value("EDGEAI_PROCESSING_CALLBACK_ADDRESS", "127.0.0.1:10082"), HealthAddress: value("EDGEAI_PROCESSING_HEALTH_ADDRESS", "0.0.0.0:10083"), CredentialFile: os.Getenv("EDGEAI_PROCESSING_CREDENTIAL_FILE"), PodTokenFile: os.Getenv("EDGEAI_PROCESSING_POD_TOKEN_FILE"), CAFile: os.Getenv("EDGEAI_PROCESSING_CA_FILE"), BrokerCAFile: os.Getenv("EDGEAI_PROCESSING_BROKER_CA_FILE"), PodUID: os.Getenv("EDGEAI_PROCESSING_POD_UID")}
}

func loopback(host string) bool {
	return host == "localhost" || net.ParseIP(host) != nil && net.ParseIP(host).IsLoopback()
}
func endpoint(raw string, local bool, allowPath bool) (*url.URL, error) {
	value, err := url.Parse(raw)
	if err != nil || value.Hostname() == "" || value.User != nil || value.RawQuery != "" || value.Fragment != "" || !allowPath && value.Path != "" && value.Path != "/" {
		return nil, fmt.Errorf("invalid processing endpoint")
	}
	if local && !loopback(value.Hostname()) || value.Scheme != "https" && !(value.Scheme == "http" && loopback(value.Hostname())) {
		return nil, fmt.Errorf("processing endpoint requires loopback or verified HTTPS")
	}
	return value, nil
}
func (c Config) validate() error {
	if _, err := endpoint(c.BackendURL, false, false); err != nil {
		return err
	}
	if _, err := endpoint(c.KuiperURL, true, false); err != nil {
		return err
	}
	if _, err := endpoint(c.PushURL, true, true); err != nil {
		return err
	}
	host, _, err := net.SplitHostPort(c.CallbackAddress)
	if err != nil || !loopback(host) {
		return fmt.Errorf("callback address must be loopback")
	}
	if _, _, err = net.SplitHostPort(c.HealthAddress); err != nil {
		return fmt.Errorf("invalid processing health address")
	}
	id, err := uuid.Parse(c.PodUID)
	if err != nil || id.String() != c.PodUID {
		return fmt.Errorf("canonical Pod UID required")
	}
	if c.CredentialFile == "" || c.PodTokenFile == "" {
		return fmt.Errorf("processing credential files are required")
	}
	return nil
}
func httpClient(caFile string) (*http.Client, error) {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	transport.MaxIdleConns = 32
	transport.MaxIdleConnsPerHost = 16
	transport.ResponseHeaderTimeout = 3 * time.Second
	transport.TLSClientConfig = &tls.Config{MinVersion: tls.VersionTLS12}
	if caFile != "" {
		data, err := os.ReadFile(caFile)
		if err != nil {
			return nil, fmt.Errorf("cannot read processing CA")
		}
		roots, err := x509.SystemCertPool()
		if err != nil {
			roots = x509.NewCertPool()
		}
		if !roots.AppendCertsFromPEM(data) {
			return nil, fmt.Errorf("invalid processing CA")
		}
		transport.TLSClientConfig.RootCAs = roots
	}
	return &http.Client{Transport: transport, Timeout: 5 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}, nil
}
func readCredential(file string, max int) (string, error) {
	stat, err := os.Stat(file)
	if err != nil || stat.Size() > int64(max) {
		return "", fmt.Errorf("processing credential unavailable")
	}
	data, err := os.ReadFile(file)
	if err != nil {
		return "", fmt.Errorf("processing credential unavailable")
	}
	value := strings.TrimSpace(string(data))
	if value == "" || len(value) > max {
		return "", fmt.Errorf("invalid processing credential")
	}
	return value, nil
}
