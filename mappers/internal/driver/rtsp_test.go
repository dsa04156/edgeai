package driver

import (
	"bytes"
	"context"
	"image/jpeg"
	"net"
	"os/exec"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"github.com/bluenviron/gortsplib/v4"
	"github.com/bluenviron/gortsplib/v4/pkg/base"
	"github.com/bluenviron/gortsplib/v4/pkg/description"
	"github.com/bluenviron/gortsplib/v4/pkg/format"
	"github.com/bluenviron/gortsplib/v4/pkg/format/rtph264"
	"github.com/bluenviron/mediacommon/v2/pkg/codecs/h264"
)

type rtspFixture struct {
	stream atomic.Pointer[gortsplib.ServerStream]
}

func (f *rtspFixture) OnDescribe(*gortsplib.ServerHandlerOnDescribeCtx) (*base.Response, *gortsplib.ServerStream, error) {
	return &base.Response{StatusCode: base.StatusOK}, f.stream.Load(), nil
}
func (f *rtspFixture) OnSetup(*gortsplib.ServerHandlerOnSetupCtx) (*base.Response, *gortsplib.ServerStream, error) {
	return &base.Response{StatusCode: base.StatusOK}, f.stream.Load(), nil
}
func (f *rtspFixture) OnPlay(*gortsplib.ServerHandlerOnPlayCtx) (*base.Response, error) {
	return &base.Response{StatusCode: base.StatusOK}, nil
}

func cameraFixture(t *testing.T, media bool) Connection {
	t.Helper()
	return cameraSourceFixture(t, media, "color=c=red:s=64x48:r=10")
}

func cameraSourceFixture(t *testing.T, media bool, source string) Connection {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	data, err := exec.CommandContext(ctx, "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", source, "-frames:v", "1", "-c:v", "libx264", "-threads", "1", "-preset", "ultrafast", "-tune", "zerolatency", "-f", "h264", "pipe:1").Output()
	if err != nil {
		t.Fatal("FFmpeg test encoder required:", err)
	}
	var nalus h264.AnnexB
	if err = nalus.Unmarshal(data); err != nil {
		t.Fatal(err)
	}
	var sps, pps []byte
	for _, n := range nalus {
		if n[0]&31 == 7 {
			sps = n
		}
		if n[0]&31 == 8 {
			pps = n
		}
	}
	codec := &format.H264{PayloadTyp: 96, PacketizationMode: 1, SPS: sps, PPS: pps}
	track := &description.Media{Type: description.MediaTypeVideo, Formats: []format.Format{codec}}
	f := &rtspFixture{}
	var address string
	server := &gortsplib.Server{Handler: f, RTSPAddress: "127.0.0.1:0", Listen: func(network, addr string) (net.Listener, error) {
		l, e := net.Listen(network, addr)
		if e == nil {
			address = l.Addr().String()
		}
		return l, e
	}}
	if err = server.Start(); err != nil {
		t.Fatal(err)
	}
	stream := &gortsplib.ServerStream{Server: server, Desc: &description.Session{Medias: []*description.Media{track}}}
	if err = stream.Initialize(); err != nil {
		t.Fatal(err)
	}
	f.stream.Store(stream)
	done := make(chan struct{})
	t.Cleanup(func() { cancel(); <-done; server.Close(); stream.Close() })
	if media {
		go func() {
			defer close(done)
			encoder := &rtph264.Encoder{PayloadType: 96, PacketizationMode: 1}
			if encoder.Init() != nil {
				return
			}
			ticker := time.NewTicker(100 * time.Millisecond)
			defer ticker.Stop()
			var timestamp uint32
			for {
				select {
				case <-ctx.Done():
					return
				case <-ticker.C:
					packets, e := encoder.Encode(nalus)
					if e != nil {
						return
					}
					for _, p := range packets {
						p.Timestamp = timestamp
						_ = stream.WritePacketRTP(track, p)
					}
					timestamp += 9000
				}
			}
		}()
	} else {
		close(done)
	}
	host, port, _ := net.SplitHostPort(address)
	n, _ := strconv.Atoi(port)
	return Connection{Host: host, Port: n, Path: "/camera", TimeoutMs: 3000}
}

func TestRTSPReadsActualVideoAndReturnsBoundedJPEG(t *testing.T) {
	config := cameraFixture(t, true)
	reader, err := New("edgeai-rtsp", config)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	observation, err := ReadObservation(context.Background(), reader, Property{DataType: "bool", JSONPath: "connected", Scale: 1})
	if err != nil || observation.Value != "true" || observation.TimeBasis != "COLLECTED" {
		t.Fatalf("actual decoded video required: %+v %v", observation, err)
	}
	preview, ok := reader.(interface {
		Preview(context.Context) ([]byte, time.Time, error)
	})
	if !ok {
		t.Fatal("camera preview unavailable")
	}
	frame, at, err := preview.Preview(context.Background())
	if err != nil || time.Since(at) > 3*time.Second {
		t.Fatalf("fresh preview required: %v", err)
	}
	image, err := jpeg.Decode(bytes.NewReader(frame))
	if err != nil {
		t.Fatal(err)
	}
	bounds := image.Bounds()
	if bounds.Dx() > 640 || bounds.Dy() > 640 {
		t.Fatal("preview exceeds bound", bounds)
	}
	red, green, blue, _ := image.At(bounds.Dx()/2, bounds.Dy()/2).RGBA()
	if red < 50000 || green > 10000 || blue > 10000 {
		t.Fatal("preview is not the real red fixture frame")
	}
}

// Detectors consume the preview: the encoder's default 200 kb/s rate control raised the quantizer on the continuous
// 640x360 warehouse replay (mean luminance table value ~100), which erased people and most forklifts.
func TestRTSPPreviewKeepsDetailedFramesAtInferenceQuality(t *testing.T) {
	reader, err := New("edgeai-rtsp", cameraSourceFixture(t, true, "mandelbrot=s=640x360:r=10"))
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	preview := reader.(interface {
		Preview(context.Context) ([]byte, time.Time, error)
	})
	deadline := time.Now().Add(6 * time.Second)
	for time.Now().Before(deadline) {
		frame, _, err := preview.Preview(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		if table := luminanceTable(t, frame); table > 8 {
			t.Fatal("preview JPEG quantization too coarse for inference, mean luminance table value", table)
		}
		time.Sleep(500 * time.Millisecond)
	}
}

func luminanceTable(t *testing.T, frame []byte) float64 {
	t.Helper()
	for i := 2; i+4 < len(frame) && frame[i] == 0xff; i += 2 + (int(frame[i+2])<<8 | int(frame[i+3])) {
		if frame[i+1] == 0xdb && frame[i+4]>>4 == 0 && i+5+64 <= len(frame) {
			sum := 0
			for _, v := range frame[i+5 : i+5+64] {
				sum += int(v)
			}
			return float64(sum) / 64
		}
	}
	t.Fatal("preview JPEG without 8-bit quantization table")
	return 0
}

func TestRTSPRejectsSDPWithoutFramesAndCancelledPreview(t *testing.T) {
	config := cameraFixture(t, false)
	config.TimeoutMs = 300
	reader, err := New("edgeai-rtsp", config)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	if _, err = reader.Read(context.Background(), Property{DataType: "bool", JSONPath: "connected", Scale: 1}); err == nil {
		t.Fatal("SDP without actual video reported connected")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err = reader.Read(ctx, Property{DataType: "bool", JSONPath: "connected", Scale: 1}); err == nil {
		t.Fatal("cancelled probe accepted")
	}
}

func TestRTSPClosedReaderNeverServesCachedFrames(t *testing.T) {
	reader, err := New("edgeai-rtsp", cameraFixture(t, true))
	if err != nil {
		t.Fatal(err)
	}
	p := Property{DataType: "bool", JSONPath: "connected", Scale: 1}
	if _, err = reader.Read(context.Background(), p); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err = reader.Read(ctx, p); err == nil {
		t.Fatal("cancelled reader served cached preview")
	}
	if err = reader.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err = reader.Read(context.Background(), p); err == nil {
		t.Fatal("closed camera reported connected")
	}
}
