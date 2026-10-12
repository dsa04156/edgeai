package driver

import (
	"context"
	"fmt"
	"golang.org/x/sys/unix"
	"io"
	"time"
)

type hardwareSerial struct {
	fd  int
	ctx context.Context
}

func openHardwareSerial(ctx context.Context, c Connection) (io.ReadCloser, error) {
	fd, err := unix.Open(c.DevicePath, unix.O_RDWR|unix.O_NOCTTY|unix.O_NONBLOCK|unix.O_CLOEXEC, 0)
	if err != nil {
		return nil, err
	}
	fail := func(err error) (io.ReadCloser, error) { _ = unix.Close(fd); return nil, err }
	if err = unix.Flock(fd, unix.LOCK_EX|unix.LOCK_NB); err != nil {
		return fail(err)
	}
	a, err := unix.IoctlGetTermios(fd, unix.TCGETS)
	if err != nil {
		return fail(err)
	}
	baud := map[int]uint32{9600: unix.B9600, 19200: unix.B19200, 38400: unix.B38400, 57600: unix.B57600, 115200: unix.B115200}[c.BaudRate]
	// Raw 8N1; HUPCL remains cleared so closing the port does not reset the UNO.
	a.Iflag = 0
	a.Oflag = 0
	a.Lflag = 0
	a.Cflag = baud | unix.CS8 | unix.CREAD | unix.CLOCAL
	a.Ispeed = baud
	a.Ospeed = baud
	a.Cc[unix.VMIN] = 0
	a.Cc[unix.VTIME] = 0
	if err = unix.IoctlSetTermios(fd, unix.TCSETS, a); err != nil {
		return fail(err)
	}
	// Existing firmware's read-only command requests a sample after reconnection.
	if _, err = unix.Write(fd, []byte("READ_NOW\n")); err != nil {
		return fail(err)
	}
	return &hardwareSerial{fd, ctx}, nil
}
func (p *hardwareSerial) Close() error { return unix.Close(p.fd) }
func (p *hardwareSerial) Read(b []byte) (int, error) {
	deadline := time.Now().Add(10 * time.Second)
	for p.ctx.Err() == nil && time.Now().Before(deadline) {
		fds := []unix.PollFd{{Fd: int32(p.fd), Events: unix.POLLIN}}
		_, err := unix.Poll(fds, 100)
		if err == unix.EINTR {
			continue
		}
		if err != nil {
			return 0, err
		}
		if fds[0].Revents&(unix.POLLERR|unix.POLLHUP|unix.POLLNVAL) != 0 {
			return 0, fmt.Errorf("serial endpoint disconnected")
		}
		if fds[0].Revents&unix.POLLIN != 0 {
			n, err := unix.Read(p.fd, b)
			if err == unix.EAGAIN {
				continue
			}
			if n == 0 && err == nil {
				return 0, io.EOF
			}
			return n, err
		}
	}
	return 0, fmt.Errorf("serial read stopped or timed out")
}
