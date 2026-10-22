import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { createConsoleServer } from "../kestra-native/server.mjs";

async function listen(server) {
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return `http://127.0.0.1:${server.address().port}`;
}

async function withConsole(action, ready = true) {
  const root = mkdtempSync(path.join(tmpdir(), "edgeai-console-"));
  const dist = path.join(root, "dist");
  mkdirSync(path.join(dist, "assets"), { recursive: true });
  writeFileSync(path.join(dist, "index.html"), "<!doctype html><title>shell</title>");
  writeFileSync(path.join(dist, "assets", "app-1.js"), "export {};");
  writeFileSync(path.join(root, "secret.txt"), "outside");
  writeFileSync(
    path.join(dist, "edgeai-shell.json"),
    JSON.stringify({ definitions: { main: "id: main" } }),
  );
  const management = createServer((req, res) => {
    res.writeHead(req.url === "/actuator/health/readiness" ? 200 : 404, {
      "Content-Type": "application/json",
    });
    res.end(JSON.stringify({ status: ready ? "UP" : "DOWN" }));
  });
  const managementUrl = await listen(management);
  const server = createConsoleServer({
    distDir: dist,
    origins: { managementUrl, workflowsEnabled: true },
  });
  const url = await listen(server);
  try {
    await action(url);
  } finally {
    await Promise.all([
      new Promise((resolve) => server.close(resolve)),
      new Promise((resolve) => management.close(resolve)),
    ]);
    rmSync(root, { recursive: true, force: true });
  }
}

test("console server redirects home, falls back to the app shell and caches hashed assets", async () => {
  await withConsole(async (url) => {
    const home = await fetch(`${url}/`, { redirect: "manual" });
    assert.equal(home.status, 302);
    assert.equal(home.headers.get("location"), "/ui/main/dashboards/default");
    const route = await fetch(`${url}/ui/main/devices`);
    assert.equal(route.status, 200);
    assert.equal(await route.text(), "<!doctype html><title>shell</title>");
    assert.equal(route.headers.get("cache-control"), "no-cache");
    const asset = await fetch(`${url}/ui/assets/app-1.js`);
    assert.equal(asset.status, 200);
    assert.match(asset.headers.get("content-type"), /javascript/);
    assert.match(asset.headers.get("cache-control"), /immutable/);
  });
});

test("console server never returns the shell for missing files or paths outside the bundle", async () => {
  await withConsole(async (url) => {
    for (const target of [
      "/ui/assets/missing-2.js",
      "/ui/..%2fsecret.txt",
      "/ui/%2e%2e/secret.txt",
      "/docs",
    ])
      assert.equal((await fetch(`${url}${target}`)).status, 404, target);
    assert.equal((await fetch(`${url}/ui/%E0%A4%A`)).status, 400);
    assert.equal(
      (await fetch(`${url}/ui/main/devices`, { method: "POST" })).status,
      405,
    );
  });
});

test("console server answers the bundled shell API and probes", async () => {
  await withConsole(async (url) => {
    const definitions = await fetch(
      `${url}/api/v1/main/dashboards/defaults/definitions`,
    );
    assert.deepEqual(await definitions.json(), { main: "id: main" });
    assert.equal((await fetch(`${url}/api/live`)).status, 200);
    assert.equal((await fetch(`${url}/api/health`)).status, 200);
  });
  await withConsole(async (url) => {
    assert.equal((await fetch(`${url}/api/live`)).status, 200);
    assert.equal((await fetch(`${url}/api/health`)).status, 503);
  }, false);
});
