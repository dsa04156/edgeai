# EdgeAI console: the original Kestra v2.0.6 Vue app with EdgeAI pages, served by kestra-native/server.mjs.
# The former Next.js dashboard is no longer packaged; the console calls the management API directly.
FROM node:24.22.0-bookworm-slim@sha256:a5f4366e834695d5f363b920c104953a535d3a9864d1bdf74566a0ebe01daee5 AS build
ARG KESTRA_REVISION=b59444f09be2e44a3b0738452b2e02fc4fe89505
RUN apt-get update \
  && apt-get install -y --no-install-recommends ca-certificates git \
  && rm -rf /var/lib/apt/lists/* \
  && npm install -g npm@11.16.0 \
  && corepack enable && corepack prepare pnpm@10.34.6 --activate
WORKDIR /workspace
# Shared console sources import dashboard/lib and its pnpm dependencies (lossless-json, icons).
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY dashboard/package.json dashboard/package.json
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile --filter @edgeai/dashboard
RUN git clone --depth 1 --branch v2.0.6 --filter=blob:none --sparse https://github.com/kestra-io/kestra.git output/kestra-native/upstream \
  && git -C output/kestra-native/upstream sparse-checkout set ui \
  && test "$(git -C output/kestra-native/upstream rev-parse HEAD)" = "$KESTRA_REVISION"
COPY scripts/dev/record-kestra-lock-hash.mjs /workspace/scripts/dev/record-kestra-lock-hash.mjs
WORKDIR /workspace/output/kestra-native/upstream/ui
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund \
  && node /workspace/scripts/dev/record-kestra-lock-hash.mjs . && npm run predev
COPY dashboard/lib /workspace/dashboard/lib
COPY dashboard/kestra-native /workspace/dashboard/kestra-native
# hawkeye-video.ts imports the scene catalog the replay image is built from.
COPY hawkeye-runtime/video-replay/scenes.json /workspace/hawkeye-runtime/video-replay/scenes.json
# Same packaging as the 13083 dev server: no federation/PWA service worker.
RUN STORYBOOK=1 EDGEAI_KESTRA_OUT_DIR=/workspace/release/dist \
    npm exec -- vite build --config /workspace/dashboard/kestra-native/vite.config.mjs \
  && cp /workspace/dashboard/kestra-native/server.mjs /workspace/dashboard/kestra-native/shell-api.ts \
    /workspace/dashboard/kestra-native/overview.ts /workspace/dashboard/kestra-native/device-registration.ts \
    /workspace/release/ \
  && echo '{"type":"module"}' > /workspace/release/package.json

FROM node:22.23.2-bookworm-slim@sha256:48e4b67d85f87bd551df43704e24d252f56cc5f8e9718841aace50f19948f0f9
WORKDIR /app
# An empty workflow URL keeps workflows on the management API unless a separate one is configured.
ENV NODE_ENV=production EDGEAI_CONSOLE_HOST=0.0.0.0 EDGEAI_CONSOLE_PORT=13080 \
    EDGEAI_KESTRA_DIST=/app/dist EDGEAI_WORKFLOW_API_BASE_URL=""
COPY --from=build --chown=node:node /workspace/release ./
USER node
EXPOSE 13080
CMD ["node", "--experimental-strip-types", "--disable-warning=ExperimentalWarning", "server.mjs"]
