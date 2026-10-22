# Build from repository root. Publish a multi-platform image and configure its digest.
FROM --platform=$BUILDPLATFORM golang:1.24.2-bookworm AS build
ARG TARGETOS
ARG TARGETARCH
WORKDIR /src
COPY mappers/ ./
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath -o /mapper ./cmd/mapper

FROM debian:bookworm-slim
LABEL org.opencontainers.image.source=https://github.com/dsa04156/edgeai
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=build /mapper /mapper
EXPOSE 7783
ENTRYPOINT ["/mapper"]
