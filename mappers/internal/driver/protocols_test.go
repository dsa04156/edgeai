package driver

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"github.com/gopcua/opcua/server"
	"github.com/gopcua/opcua/ua"
)

type timestampedNamespace struct{ *server.MapNamespace }

func (ns *timestampedNamespace) Attribute(id *ua.NodeID, attribute ua.AttributeID) *ua.DataValue {
	value := ns.MapNamespace.Attribute(id, attribute)
	if attribute == ua.AttributeIDValue && value.Status == ua.StatusOK {
		value.SourceTimestamp = time.UnixMilli(1700000000123)
		value.EncodingMask |= ua.DataValueSourceTimestamp
	}
	return value
}

func TestVirtualReadsActualHTTPRuntimeAndRejectsMissingWrongTypes(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"values":{"temperature":42.5},"enabled":true}`))
	}))
	defer s.Close()
	host, port, _ := net.SplitHostPort(s.Listener.Addr().String())
	n, _ := strconv.Atoi(port)
	reader, err := New("edgeai-virtual", Connection{Host: host, Port: n, Path: "/properties", TimeoutMs: 1000})
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	p := Property{DataType: "float32", JSONPath: "values.temperature", Scale: 2, Offset: 1}
	v, err := reader.Read(context.Background(), p)
	if err != nil || v != "86" {
		t.Fatalf("%s %v", v, err)
	}
	p.JSONPath = "missing"
	if _, err = reader.Read(context.Background(), p); err == nil {
		t.Fatal("missing property accepted")
	}
	p.JSONPath = "enabled"
	if _, err = reader.Read(context.Background(), p); err == nil {
		t.Fatal("wrong property type accepted")
	}
}
func TestOPCUAReadsRealServerAndReportsUnknownNode(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()
	s := server.New(server.EndPoint("127.0.0.1", port), server.EnableSecurity("None", ua.MessageSecurityModeNone), server.EnableAuthMode(ua.UserTokenTypeAnonymous))
	ns := server.NewMapNamespace(s, "test")
	ns.Data["temperature"] = float32(31.25)
	s.AddNamespace(&timestampedNamespace{ns})
	if err = s.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	reader, err := New("edgeai-opc-ua", Connection{Host: "127.0.0.1", Port: port, TimeoutMs: 2000, SecurityMode: "None"})
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	p := Property{DataType: "float32", NodeID: "ns=" + strconv.Itoa(int(ns.ID())) + ";s=temperature", Scale: 1}
	observation, err := ReadObservation(context.Background(), reader, p)
	if err != nil || observation.TimeBasis != "SOURCE" || observation.ObservedAt != 1700000000123 {
		t.Fatalf("native OPC-UA timestamp was not preserved: %+v %v", observation, err)
	}
	value, err := reader.Read(context.Background(), p)
	if err != nil || value != "31.25" {
		t.Fatalf("%s %v", value, err)
	}
	p.NodeID = "ns=1;s=missing"
	if _, err = reader.Read(context.Background(), p); err == nil {
		t.Fatal("unknown NodeID accepted")
	}
}
func TestJSONInputDoesNotTurnMalformedValuesIntoZero(t *testing.T) {
	for _, data := range []string{`{"v":"bad"}`, `{"v":1.5}`, `{"v":1e400}`, `{"v":2} {"v":3}`} {
		if _, err := jsonValue([]byte(data), Property{JSONPath: "v", DataType: "int16", Scale: 1}); err == nil {
			t.Fatal(data)
		}
	}
	if _, err := New("edgeai-opc-ua", Connection{Host: "host", Port: 4840, TimeoutMs: 1000, SecurityMode: "Sign"}); err == nil {
		t.Fatal("unsupported security silently downgraded")
	}
}
