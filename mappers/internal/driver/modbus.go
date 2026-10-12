package driver

import (
	"context"
	"encoding/binary"
	"fmt"
	"math"
	"net"
	"strconv"
	"time"

	"github.com/goburrow/modbus"
)

type Connection struct {
	Host         string `json:"host"`
	Port         int    `json:"port"`
	UnitID       int    `json:"unitId"`
	TimeoutMs    int    `json:"timeoutMs"`
	Path         string `json:"path"`
	StaleMs      int    `json:"staleMs"`
	SecurityMode string `json:"securityMode"`
	DevicePath   string `json:"devicePath"`
	DeviceID     string `json:"deviceId"`
	BaudRate     int    `json:"baudRate"`
}

type Property struct {
	Address             uint16  `json:"address"`
	Function            string  `json:"function"`
	DataType            string  `json:"dataType"`
	ByteOrder           string  `json:"byteOrder"`
	Scale               float64 `json:"scale"`
	Offset              float64 `json:"offset"`
	NodeID              string  `json:"nodeId"`
	Topic               string  `json:"topic"`
	JSONPath            string  `json:"jsonPath"`
	Sensor              string  `json:"sensor"`
	ConfigurationSha256 string  `json:"configurationSha256,omitempty"`
}

type Reader interface {
	Read(context.Context, Property) (string, error)
	Close() error
}

type Modbus struct {
	handler *modbus.TCPClientHandler
	client  modbus.Client
}

func NewModbus(c Connection) (*Modbus, error) {
	if c.Host == "" || c.Port < 1 || c.Port > 65535 || c.UnitID < 1 || c.UnitID > 247 {
		return nil, fmt.Errorf("invalid Modbus connection")
	}
	if c.TimeoutMs == 0 {
		c.TimeoutMs = 1000
	}
	if c.TimeoutMs < 100 || c.TimeoutMs > 5000 {
		return nil, fmt.Errorf("invalid Modbus timeout")
	}
	h := modbus.NewTCPClientHandler(net.JoinHostPort(c.Host, strconv.Itoa(c.Port)))
	h.SlaveId = byte(c.UnitID)
	h.Timeout = time.Duration(c.TimeoutMs) * time.Millisecond
	h.IdleTimeout = 30 * time.Second
	return &Modbus{h, modbus.NewClient(h)}, nil
}

func (m *Modbus) Close() error { return m.handler.Close() }

func (m *Modbus) Read(ctx context.Context, p Property) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}
	n := uint16(1)
	switch p.DataType {
	case "float32", "int32", "uint32":
		n = 2
	case "float64":
		n = 4
	case "int16", "uint16", "bool":
	default:
		return "", fmt.Errorf("unsupported Modbus data type")
	}
	if uint32(p.Address)+uint32(n) > 65536 {
		return "", fmt.Errorf("register range overflow")
	}
	var data []byte
	var err error
	switch p.Function {
	case "holding":
		data, err = m.client.ReadHoldingRegisters(p.Address, n)
	case "input":
		data, err = m.client.ReadInputRegisters(p.Address, n)
	case "coil":
		if p.DataType != "bool" {
			return "", fmt.Errorf("coil requires bool")
		}
		data, err = m.client.ReadCoils(p.Address, 1)
		if err != nil {
			_ = m.handler.Close()
			return "", err
		}
		if len(data) < 1 {
			return "", fmt.Errorf("short coil response")
		}
		return strconv.FormatBool(data[0]&1 == 1), nil
	default:
		return "", fmt.Errorf("unsupported Modbus function")
	}
	if err != nil {
		// goburrow keeps a failed TCP connection. Reset it so the next collection reconnects.
		_ = m.handler.Close()
		return "", err
	}
	return Decode(data, p)
}

func Decode(data []byte, p Property) (string, error) {
	n := 2
	switch p.DataType {
	case "float32", "int32", "uint32":
		n = 4
	case "float64":
		n = 8
	}
	if len(data) != n {
		return "", fmt.Errorf("invalid register response size")
	}
	b := append([]byte(nil), data...)
	switch p.ByteOrder {
	case "", "ABCD":
	case "BADC":
		for i := 0; i < len(b); i += 2 {
			b[i], b[i+1] = b[i+1], b[i]
		}
	case "CDAB":
		for i := 0; i < len(b)/2; i += 2 {
			j := len(b) - 2 - i
			b[i], b[j] = b[j], b[i]
			b[i+1], b[j+1] = b[j+1], b[i+1]
		}
	case "DCBA":
		for i, j := 0, len(b)-1; i < j; i, j = i+1, j-1 {
			b[i], b[j] = b[j], b[i]
		}
	default:
		return "", fmt.Errorf("invalid byte order")
	}
	var v float64
	switch p.DataType {
	case "float32":
		v = float64(math.Float32frombits(binary.BigEndian.Uint32(b)))
	case "float64":
		v = math.Float64frombits(binary.BigEndian.Uint64(b))
	case "int16":
		v = float64(int16(binary.BigEndian.Uint16(b)))
	case "uint16":
		v = float64(binary.BigEndian.Uint16(b))
	case "int32":
		v = float64(int32(binary.BigEndian.Uint32(b)))
	case "uint32":
		v = float64(binary.BigEndian.Uint32(b))
	case "bool":
		return strconv.FormatBool(binary.BigEndian.Uint16(b) != 0), nil
	default:
		return "", fmt.Errorf("unsupported data type")
	}
	// The compiler supplies an explicit scale, including an intentional zero.
	v = v*p.Scale + p.Offset
	if math.IsNaN(v) || math.IsInf(v, 0) {
		return "", fmt.Errorf("non-finite sensor value")
	}
	return strconv.FormatFloat(v, 'g', -1, 64), nil
}
