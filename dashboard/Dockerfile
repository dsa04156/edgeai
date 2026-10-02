FROM node:22.23.2-bookworm-slim@sha256:48e4b67d85f87bd551df43704e24d252f56cc5f8e9718841aace50f19948f0f9 AS build
WORKDIR /workspace
ENV NEXT_TELEMETRY_DISABLED=1
RUN corepack enable && corepack prepare pnpm@10.34.6 --activate
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY dashboard/package.json dashboard/package.json
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile
COPY dashboard dashboard
RUN mkdir -p dashboard/public && pnpm --filter @edgeai/dashboard build

FROM node:22.23.2-bookworm-slim@sha256:48e4b67d85f87bd551df43704e24d252f56cc5f8e9718841aace50f19948f0f9
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 HOSTNAME=0.0.0.0 PORT=13080
COPY --from=build --chown=node:node /workspace/dashboard/.next/standalone ./
COPY --from=build --chown=node:node /workspace/dashboard/.next/static ./dashboard/.next/static
COPY --from=build --chown=node:node /workspace/dashboard/public ./dashboard/public
USER node
EXPOSE 13080
CMD ["node", "dashboard/server.js"]
