// Production server for the built console: the same middleware chain as the 13083 Vite dev server,
// talking to the management/workflow/Platform-Service APIs directly (no Next.js BFF).
import { createReadStream, readFileSync } from "node:fs";
import { stat } from "node:fs/promises";
import { createServer } from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createDeviceRegistrationMiddleware } from "./device-registration.ts";
import { createOverviewMiddleware } from "./overview.ts";
import { apiOrigins, createShellApiMiddleware } from "./shell-api.ts";

const types = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".ico": "image/x-icon",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".map": "application/json; charset=utf-8",
  ".md": "text/markdown; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".ttf": "font/ttf",
  ".wasm": "application/wasm",
  ".webmanifest": "application/manifest+json",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
};
const home = "/ui/main/dashboards/default";

function serveStatic(distDir) {
  const root = path.resolve(distDir);
  const index = path.join(root, "index.html");
  return async (request, response) => {
    const url = new URL(request.url ?? "/", "http://console.local");
    if (request.method !== "GET" && request.method !== "HEAD") {
      response.statusCode = 405;
      return response.end();
    }
    if (url.pathname === "/" || url.pathname === "/ui" || url.pathname === "/ui/") {
      response.statusCode = 302;
      response.setHeader("Location", home);
      return response.end();
    }
    if (!url.pathname.startsWith("/ui/")) {
      response.statusCode = 404;
      return response.end();
    }
    let relative;
    try {
      relative = decodeURIComponent(url.pathname.slice("/ui/".length));
    } catch {
      response.statusCode = 400;
      return response.end();
    }
    const file = path.resolve(root, relative);
    if (file !== root && !file.startsWith(root + path.sep)) {
      response.statusCode = 404;
      return response.end();
    }
    let target = file;
    const info = await stat(file).catch(() => undefined);
    if (!info?.isFile()) {
      // Missing hashed assets must fail instead of returning the app shell as JavaScript.
      if (path.extname(relative)) {
        response.statusCode = 404;
        return response.end();
      }
      target = index;
    }
    response.setHeader(
      "Content-Type",
      types[path.extname(target)] ?? "application/octet-stream",
    );
    response.setHeader(
      "Cache-Control",
      target.startsWith(path.join(root, "assets") + path.sep)
        ? "public, max-age=31536000, immutable"
        : "no-cache",
    );
    if (request.method === "HEAD") return response.end();
    createReadStream(target)
      .on("error", () => {
        if (!response.headersSent) response.statusCode = 500;
        response.end();
      })
      .pipe(response);
  };
}

// Probes: /api/live is the process, /api/health also needs the management API to be ready.
function probes({ managementUrl }) {
  return (request, response, next) => {
    const { pathname } = new URL(request.url ?? "/", "http://console.local");
    if (pathname !== "/api/live" && pathname !== "/api/health") return next();
    const send = (status) => {
      response.writeHead(status, {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "no-store",
      });
      response.end(JSON.stringify({ status: status === 200 ? "UP" : "DOWN" }));
    };
    if (pathname === "/api/live") return send(200);
    fetch(new URL("/actuator/health/readiness", managementUrl), {
      cache: "no-store",
      signal: AbortSignal.timeout(2000),
    })
      .then(async (upstream) =>
        send(upstream.ok && (await upstream.json()).status === "UP" ? 200 : 503),
      )
      .catch(() => send(503));
  };
}

export function createConsoleServer({ distDir, origins }) {
  const shell = JSON.parse(
    readFileSync(path.join(distDir, "edgeai-shell.json"), "utf8"),
  );
  const chain = [
    probes(origins),
    createDeviceRegistrationMiddleware(origins),
    createOverviewMiddleware(origins),
    createShellApiMiddleware({ definitions: () => shell.definitions }),
  ];
  const files = serveStatic(distDir);
  return createServer((request, response) => {
    const run = (position) => {
      if (position === chain.length)
        return files(request, response).catch(() => {
          if (!response.headersSent) response.statusCode = 500;
          response.end();
        });
      chain[position](request, response, () => run(position + 1));
    };
    run(0);
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const nativeRoot = path.dirname(fileURLToPath(import.meta.url));
  const host = process.env.EDGEAI_CONSOLE_HOST ?? "0.0.0.0";
  const port = Number(process.env.EDGEAI_CONSOLE_PORT ?? 13083);
  const server = createConsoleServer({
    distDir: process.env.EDGEAI_KESTRA_DIST ?? path.join(nativeRoot, "dist"),
    origins: apiOrigins(process.env),
  });
  server.listen(port, host, () =>
    console.log(`EdgeAI console http://${host}:${port}${home}`),
  );
  for (const signal of ["SIGINT", "SIGTERM"])
    process.on(signal, () => server.close(() => process.exit(0)));
}
