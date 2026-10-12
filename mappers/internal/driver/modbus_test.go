package driver

import (
	"context"
	"encoding/binary"
	"io"
	"math"
	"net"
	"testing"
)

func TestDecodeOrdersAndScaling(t *testing.T) {
	for _, tt := range []struct {
		order string
		bytes []byte
	}{{"ABCD", []byte{0x41, 0xbc, 0, 0}}, {"BADC", []byte{0xbc, 0x41, 0, 0}}, {"CDAB", []byte{0, 0, 0x41, 0xbc}}, {"DCBA", []byte{0, 0, 0xbc, 0x41}}} {
		v, e := Decode(tt.bytes, Property{DataType: "float32", ByteOrder: tt.order, Scale: 2, Offset: 1})
		if e != nil || v != "48" {
			t.Fatalf("%s got %s %v", tt.order, v, e)
		}
	}
	if _, e := Decode([]byte{0, 1}, Property{DataType: "float32", Scale: 1}); e == nil {
		t.Fatal("accepted short response")
	}
	b := make([]byte, 4)
	binary.BigEndian.PutUint32(b, math.Float32bits(float32(math.Inf(1))))
	if _, e := Decode(b, Property{DataType: "float32", Scale: 1}); e == nil {
		t.Fatal("accepted non-finite measurement")
	}
}

func TestRealModbusTCPReadAndException(t *testing.T) {
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer l.Close()
	go func() {
		c, e := l.Accept()
		if e != nil {
			return
		}
		defer c.Close()
		for i := 0; i < 2; i++ {
			b := make([]byte, 12)
			if _, e := io.ReadFull(c, b); e != nil {
				return
			}
			if b[6] != 7 || b[7] != 3 || binary.BigEndian.Uint16(b[8:10]) != 0 || binary.BigEndian.Uint16(b[10:12]) != 2 {
				return
			}
			response := []byte{3, 4, 0x41, 0xbc, 0, 0}
			if i == 1 {
				response = []byte{0x83, 2}
			}
			header := b[:7]
			binary.BigEndian.PutUint16(header[4:6], uint16(len(response)+1))
			_, _ = c.Write(append(header, response...))
		}
	}()
	addr := l.Addr().(*net.TCPAddr)
	m, e := NewModbus(Connection{Host: "127.0.0.1", Port: addr.Port, UnitID: 7, TimeoutMs: 500})
	if e != nil {
		t.Fatal(e)
	}
	defer m.Close()
	p := Property{Function: "holding", DataType: "float32", Scale: 1}
	v, e := m.Read(context.Background(), p)
	if e != nil || v != "23.5" {
		t.Fatalf("got %s %v", v, e)
	}
	if _, e := m.Read(context.Background(), p); e == nil {
		t.Fatal("device exception was ignored")
	}
}

func TestReconnectAfterDeviceDisconnect(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	go func() {
		for attempt := 0; attempt < 2; attempt++ {
			c, e := l.Accept()
			if e != nil {
				return
			}
			b := make([]byte, 12)
			if _, e = io.ReadFull(c, b); e != nil {
				c.Close()
				return
			}
			if attempt == 1 {
				response := []byte{3, 4, 0x41, 0xbc, 0, 0}
				binary.BigEndian.PutUint16(b[4:6], uint16(len(response)+1))
				_, _ = c.Write(append(b[:7], response...))
			}
			c.Close()
		}
	}()
	m, err := NewModbus(Connection{Host: "127.0.0.1", Port: l.Addr().(*net.TCPAddr).Port, UnitID: 1, TimeoutMs: 500})
	if err != nil {
		t.Fatal(err)
	}
	defer m.Close()
	p := Property{Function: "holding", DataType: "float32", Scale: 1}
	if _, err = m.Read(context.Background(), p); err == nil {
		t.Fatal("disconnect was not detected")
	}
	value, err := m.Read(context.Background(), p)
	if err != nil || value != "23.5" {
		t.Fatalf("did not reconnect: %s %v", value, err)
	}
}
