// Deterministic Modbus TCP test equipment. Never deployed as a production sensor.
package main

import (
	"encoding/binary"
	"io"
	"log"
	"math"
	"net"
)

func main() {
	l, e := net.Listen("tcp", ":1502")
	if e != nil {
		log.Fatal(e)
	}
	log.Print("Modbus fixture listening on 1502; units 1 and 2")
	for {
		c, e := l.Accept()
		if e != nil {
			log.Fatal(e)
		}
		go serve(c)
	}
}
func serve(c net.Conn) {
	defer c.Close()
	for {
		header := make([]byte, 7)
		if _, e := io.ReadFull(c, header); e != nil {
			return
		}
		n := int(binary.BigEndian.Uint16(header[4:6]))
		if n < 2 || n > 254 {
			return
		}
		pdu := make([]byte, n-1)
		if _, e := io.ReadFull(c, pdu); e != nil {
			return
		}
		if len(pdu) != 5 {
			return
		}
		fn := pdu[0]
		address := int(binary.BigEndian.Uint16(pdu[1:3]))
		count := int(binary.BigEndian.Uint16(pdu[3:5]))
		reply := []byte{fn | 0x80, 2}
		if (header[6] == 1 || header[6] == 2) && (fn == 3 || fn == 4) && count > 0 && count <= 125 && address+count <= 6 {
			registers := make([]byte, 12)
			values := []float32{23.5 + float32(header[6]-1)*10, 0.125 * float32(header[6]), 0.25 * float32(header[6])}
			for i, v := range values {
				binary.BigEndian.PutUint32(registers[i*4:], math.Float32bits(v))
			}
			reply = append([]byte{fn, byte(count * 2)}, registers[address*2:(address+count)*2]...)
		}
		binary.BigEndian.PutUint16(header[4:6], uint16(len(reply)+1))
		if _, e := c.Write(append(header, reply...)); e != nil {
			return
		}
	}
}
