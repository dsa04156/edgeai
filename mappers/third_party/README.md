# KubeEdge Mapper Framework source pin

Source: https://github.com/kubeedge/kubeedge/tree/v1.23.0/staging/src/github.com/kubeedge
Commit: `8e9e9fb155d9dee3fd8b6c00463b1b2be7b3e3b4`.

The upstream `mapper-framework/pkg` and DMI `api/apis/dmi/v1beta1` are copied unchanged
under their original module paths and Apache-2.0 license. The api module manifest
contains only dependencies needed by these generated DMI bindings. No Kubernetes
server source or generated-project FFmpeg/database integrations are included.

EdgeAI implements the Framework `DevPanel` contract, uses its parsing and gRPC
server, and supplies bounded DMI client calls and protocol drivers. Device settings
arrive through EdgeCore DMI; the mapper has no Kubernetes API credentials.
