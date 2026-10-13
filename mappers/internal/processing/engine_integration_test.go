package processing

import (
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func testPort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	return listener.Addr().(*net.TCPAddr).Port
}
func realEngine(t *testing.T) (string, string) {
	t.Helper()
	root, err := filepath.Abs("../../../.tools/ekuiper-test-2.4.2-full/kuiper-2.4.2-linux-amd64")
	if err != nil {
		t.Fatal(err)
	}
	if override := os.Getenv("PROCESSING_TEST_EKUIPER_HOME"); override != "" {
		root = override
	}
	directory := t.TempDir()
	err = filepath.WalkDir(filepath.Join(root, "etc"), func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		relative, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		target := filepath.Join(directory, relative)
		if entry.IsDir() {
			return os.MkdirAll(target, 0700)
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		return os.WriteFile(target, data, 0600)
	})
	if err != nil {
		t.Fatalf("real pinned eKuiper 2.4.2 required: %v", err)
	}
	rest, push, cli := testPort(t), testPort(t), testPort(t)
	file := filepath.Join(directory, "etc/kuiper.yaml")
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	configuration := string(data)
	for old, replacement := range map[string]string{"restIp: 0.0.0.0": "restIp: 127.0.0.1", "restPort: 9081": fmt.Sprintf("restPort: %d", rest), "port: 20498": fmt.Sprintf("port: %d", cli), "ip: 0.0.0.0": "ip: 127.0.0.1", "enablePrivateNet: false": "enablePrivateNet: true", "pprof: true": "pprof: false", "httpServerIp: 0.0.0.0": "httpServerIp: 127.0.0.1", "httpServerPort: 10081": fmt.Sprintf("httpServerPort: %d", push)} {
		configuration = strings.ReplaceAll(configuration, old, replacement)
	}
	if err = os.WriteFile(file, []byte(configuration), 0600); err != nil {
		t.Fatal(err)
	}
	for _, folder := range []string{"data", "log", "plugins/sources", "plugins/sinks", "plugins/functions", "plugins/portable", "bin"} {
		if err = os.MkdirAll(filepath.Join(directory, folder), 0700); err != nil {
			t.Fatal(err)
		}
	}
	// eKuiper resolves assets relative to the executable's installation directory.
	binaryData, err := os.ReadFile(filepath.Join(root, "bin/kuiperd"))
	if err != nil {
		t.Fatal(err)
	}
	binary := filepath.Join(directory, "bin/kuiperd")
	if err = os.WriteFile(binary, binaryData, 0700); err != nil {
		t.Fatal(err)
	}
	logs, err := os.Create(filepath.Join(directory, "process.log"))
	if err != nil {
		t.Fatal(err)
	}
	command := exec.Command(binary)
	command.Dir = directory
	command.Stdout = logs
	command.Stderr = logs
	if err = command.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		command.Process.Signal(os.Interrupt)
		done := make(chan error, 1)
		go func() { done <- command.Wait() }()
		select {
		case <-done:
		case <-time.After(time.Second):
			command.Process.Kill()
			<-done
		}
		logs.Close()
	})
	endpoint := fmt.Sprintf("http://127.0.0.1:%d", rest)
	client := &http.Client{Timeout: 200 * time.Millisecond}
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		response, err := client.Get(endpoint + "/rules")
		if err == nil {
			response.Body.Close()
			if response.StatusCode == 200 {
				return endpoint, fmt.Sprintf("http://127.0.0.1:%d/edgeai/evaluate", push)
			}
		}
		time.Sleep(30 * time.Millisecond)
	}
	output, _ := os.ReadFile(filepath.Join(directory, "process.log"))
	t.Fatalf("eKuiper did not start: %s", output)
	return "", ""
}
