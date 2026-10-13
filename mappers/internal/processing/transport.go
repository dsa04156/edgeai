package processing

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"github.com/google/uuid"
	"io"
	"net/http"
	"time"
)

type statusError struct {
	status int
	body   string
}

func (e statusError) Error() string { return fmt.Sprintf("processing HTTP status %d", e.status) }
func randomSession() string         { return uuid.NewString() }
func (a *Agent) request(ctx context.Context, method, endpoint string, data []byte, authenticated bool, timeout time.Duration) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, method, endpoint, bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Content-Type", "application/json")
	if authenticated {
		credential, err := readCredential(a.config.CredentialFile, 1024)
		if err != nil {
			return nil, err
		}
		token, err := readCredential(a.config.PodTokenFile, 32768)
		if err != nil {
			return nil, err
		}
		request.Header.Set("Authorization", "Bearer "+credential)
		request.Header.Set("X-EdgeAI-Pod-Token", token)
	}
	response, err := a.client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 4*1024*1024+1))
	if err != nil {
		return nil, err
	}
	if len(body) > 4*1024*1024 {
		return nil, fmt.Errorf("processing response too large")
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return nil, statusError{status: response.StatusCode, body: string(body)}
	}
	return body, nil
}
func decode(data []byte, value any) error {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(value); err != nil {
		return err
	}
	var tail any
	if decoder.Decode(&tail) != io.EOF {
		return fmt.Errorf("trailing JSON")
	}
	return nil
}
