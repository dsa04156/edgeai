# InfluxDB OSS v2.9.1 (tag commit d4fa1941fd4adb29a556e774de426dfbab72f346) with one fix:
# TSI orphan cleanup removes files in the partition directory, not the process cwd (tsi-orphan-cleanup-path.patch).
# The build follows upstream scripts/ci/build.sh for linux_amd64 (static musl, same tags, embedded UI) and
# replaces only influxd in the pinned official image.
FROM golang:1.25.10-alpine3.23@sha256:8d22e29d960bc50cd025d93d5b7c7d220b1ee9aa7a239b3c8f55a57e987e8d45 AS go

# flux v0.200.0 pins Rust 1.83 (rust-toolchain.toml; `strict` denies newer-compiler warnings). RUSTUP_TOOLCHAIN uses the
# preinstalled toolchain instead of downloading the file's extra targets; musl rustc needs a larger stack for flux-core.
FROM rust:1.83.0-alpine3.21@sha256:0ac946ed7597a9f053a1be2ce38c09aa88b3d7079a91ea491493615294b1f699 AS build
COPY --from=go /usr/local/go /usr/local/go
ENV PATH=/usr/local/go/bin:$PATH GOTOOLCHAIN=local CGO_ENABLED=1 RUSTUP_TOOLCHAIN=1.83.0 RUST_MIN_STACK=67108864
RUN apk add --no-cache build-base pkgconf
WORKDIR /src
ADD --checksum=sha256:0d6d47516cd385285e878dac92e04b534667788449cf42e7bf6a96bb050c368f https://github.com/influxdata/influxdb/archive/d4fa1941fd4adb29a556e774de426dfbab72f346.tar.gz /tmp/influxdb.tar.gz
RUN tar -xzf /tmp/influxdb.tar.gz --strip-components=1 -C /src && grep -qx 'go 1.25.10' go.mod
COPY tsi-orphan-cleanup-path.patch /tmp/
RUN patch -p1 < /tmp/tsi-orphan-cleanup-path.patch
# UI and swagger pins from upstream scripts/fetch-ui-assets.sh (OSS-v2.9.0) and scripts/fetch-swagger.sh.
ADD --checksum=sha256:9cb2df818f1f5badc23e051f51eef7e8b143540233947606eadad309f395bb0e https://github.com/influxdata/ui/releases/download/OSS-v2.9.0/build.tar.gz /tmp/ui.tar.gz
ADD --checksum=sha256:47f91c7d44de1ce56c02390fde14d13ca82060b3e74d018de8c1e4b86b27e264 https://raw.githubusercontent.com/influxdata/openapi/8b5f1bbb2cd388eb454dc9da19e3d2c4061cdf5f/contracts/oss.json static/data/swagger.json
RUN tar -xzf /tmp/ui.tar.gz -C static/data && go generate -tags assets ./static
RUN go build -o /usr/local/bin/pkg-config github.com/influxdata/pkg-config
# musl 1.2.4+ hides pread64/off64_t used by go-sqlite3 unless _LARGEFILE64_SOURCE is defined.
# Alpine gcc cannot combine libflux's -static with -static-pie (upstream links with clang), so this is static non-PIE.
RUN PKG_CONFIG=/usr/local/bin/pkg-config CGO_CFLAGS='-O2 -g -D_LARGEFILE64_SOURCE' go build \
      -tags assets,sqlite_foreign_keys,sqlite_json,static_build,noasm \
      -ldflags "-s -w -X main.version=v2.9.1 -X main.commit=d4fa1941fd-edgeai-tsi -X main.date=2026-05-11T20:49:03Z -linkmode=external -extldflags '-static -Wl,-z,stack-size=8388608'" \
      -o /out/influxd ./cmd/influxd \
    && /out/influxd version

FROM public.ecr.aws/docker/library/influxdb@sha256:f551adeb94b6f97626d2043ec90ed17f81de18d15bb5412d2101e0965c93c797
LABEL org.opencontainers.image.source=https://github.com/dsa04156/edgeai
COPY --from=build /out/influxd /usr/local/bin/influxd
RUN influxd version | grep -q 'git: d4fa1941fd-edgeai-tsi'
