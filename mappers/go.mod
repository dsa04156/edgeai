module io.edgeai/mapper

go 1.23.12

toolchain go1.24.2

replace github.com/kubeedge/api => ./third_party/api

replace github.com/kubeedge/mapper-framework => ./third_party/mapper-framework

require (
	github.com/bluenviron/gortsplib/v4 v4.16.2
	github.com/pion/rtp v1.8.21
	github.com/eclipse/paho.mqtt.golang v1.5.0
	github.com/goburrow/modbus v0.1.0
	github.com/gopcua/opcua v0.9.1
	github.com/kubeedge/api v0.0.0
	github.com/kubeedge/mapper-framework v0.0.0
	google.golang.org/grpc v1.67.1
)

require (
	github.com/avast/retry-go v3.0.0+incompatible // indirect
	github.com/bluenviron/mediacommon/v2 v2.4.1 // indirect
	github.com/go-logr/logr v1.4.2 // indirect
	github.com/goburrow/serial v0.1.0 // indirect
	github.com/golang/protobuf v1.5.4 // indirect
	github.com/google/uuid v1.6.0 // indirect
	github.com/gorilla/websocket v1.5.3 // indirect
	github.com/kr/text v0.2.0 // indirect
	github.com/pion/logging v0.2.3 // indirect
	github.com/pion/randutil v0.1.0 // indirect
	github.com/pion/rtcp v1.2.15 // indirect
	github.com/pion/sdp/v3 v3.0.15 // indirect
	github.com/pion/srtp/v3 v3.0.6 // indirect
	github.com/pion/transport/v3 v3.0.7 // indirect
	github.com/spf13/pflag v1.0.6-0.20210604193023-d5e0c0615ace // indirect
	github.com/stretchr/testify v1.12.1 // indirect
	golang.org/x/net v0.43.0 // indirect
	golang.org/x/sync v0.16.0 // indirect
	golang.org/x/sys v0.35.0 // indirect
	golang.org/x/text v0.28.0 // indirect
	google.golang.org/genproto/googleapis/rpc v0.0.0-20241007155032-5fefd90f89a9 // indirect
	google.golang.org/protobuf v1.35.2 // indirect
	gopkg.in/yaml.v2 v2.4.0 // indirect
	k8s.io/klog/v2 v2.130.1 // indirect
)
