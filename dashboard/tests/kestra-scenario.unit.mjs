import assert from "node:assert/strict";
import { createServer } from "node:http";
import { test } from "node:test";
import { createDeviceRegistrationMiddleware } from "../kestra-native/device-registration.ts";

const run = "0b9d4f3e-5f0c-4f1a-9a33-2a7d2b1c9e10";

async function fixture(action) {
  const calls = [];
  const backend = createServer(async (req, res) => {
    let body = "";
    for await (const part of req) body += part;
    calls.push({ path: req.url, method: req.method, body, headers: req.headers });
    res.setHeader("Content-Type", "application/json");
    res.end("{}");
  });
  await new Promise((resolve) => backend.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${backend.address().port}`;
  const middleware = createDeviceRegistrationMiddleware({ managementUrl: baseUrl });
  const preview = createServer((req, res) =>
    middleware(req, res, () => {
      res.statusCode = 404;
      res.end();
    }),
  );
  await new Promise((resolve) => preview.listen(0, "127.0.0.1", resolve));
  try {
    await action(`http://127.0.0.1:${preview.address().port}`, calls, baseUrl);
  } finally {
    await Promise.all([
      new Promise((resolve) => preview.close(resolve)),
      new Promise((resolve) => backend.close(resolve)),
    ]);
  }
}

test("scenario reads reach the management API with the session and page query", async () => {
  await fixture(async (url, calls) => {
    for (const path of [
      "scenarios/image-comparison",
      "scenarios/image-comparison/runs?limit=5&offset=0",
      `scenarios/image-comparison/runs/${run}`,
      `scenarios/image-comparison/runs/${run}/results?limit=20&offset=40`,
    ]) {
      const response = await fetch(`${url}/api/control-plane/${path}`, { headers: { Cookie: "EDGEAI_SESSION=s" } });
      assert.equal(response.status, 200, path);
    }
    assert.deepEqual(calls.map((call) => call.path), [
      "/api/v1/scenarios/image-comparison",
      "/api/v1/scenarios/image-comparison/runs?limit=5&offset=0",
      `/api/v1/scenarios/image-comparison/runs/${run}`,
      `/api/v1/scenarios/image-comparison/runs/${run}/results?limit=20&offset=40`,
    ]);
    assert.ok(calls.every((call) => call.headers.cookie === "EDGEAI_SESSION=s"));
  });
});

test("start and stop forward CSRF, Idempotency-Key and the JSON body", async () => {
  await fixture(async (url, calls, baseUrl) => {
    const headers = { Origin: url, "Content-Type": "application/json", "X-CSRF-TOKEN": "t", "Idempotency-Key": run };
    const started = await fetch(`${url}/api/control-plane/scenarios/image-comparison/runs`, {
      method: "POST", headers, body: '{"imageCount":100}',
    });
    assert.equal(started.status, 200);
    const stopped = await fetch(`${url}/api/control-plane/scenarios/image-comparison/runs/${run}/stop`, {
      method: "POST", headers, body: "{}",
    });
    assert.equal(stopped.status, 200);
    assert.equal(calls[0].path, "/api/v1/scenarios/image-comparison/runs");
    assert.equal(calls[0].headers["idempotency-key"], run);
    assert.equal(calls[0].headers["x-csrf-token"], "t");
    assert.equal(calls[0].headers.origin, baseUrl);
    assert.equal(calls[0].body, '{"imageCount":100}');
    assert.equal(calls[1].path, `/api/v1/scenarios/image-comparison/runs/${run}/stop`);
  });
});

test("scenario routes are exact, method-bound, bounded and same-origin", async () => {
  await fixture(async (url, calls) => {
    for (const [path, method] of [
      ["scenarios/image-comparison", "POST"],
      ["scenarios/image-comparison/runs/x", "GET"],
      [`scenarios/image-comparison/runs/${run}/stop`, "GET"],
      [`scenarios/image-comparison/runs/${run}`, "POST"],
      [`scenarios/image-comparison/runs/${run}/results/1`, "GET"],
      ["scenarios/other", "GET"],
      ["scenarios", "GET"],
    ]) {
      const response = await fetch(`${url}/api/control-plane/${path}`, {
        method,
        headers: { "Content-Type": "application/json" },
        ...(method === "GET" ? {} : { body: "{}" }),
      });
      assert.ok([404, 405].includes(response.status), `${method} ${path}: ${response.status}`);
    }
    const crossSite = await fetch(`${url}/api/control-plane/scenarios/image-comparison/runs`, {
      method: "POST", headers: { Origin: "http://evil.example", "Content-Type": "application/json" }, body: "{}",
    });
    assert.equal(crossSite.status, 403);
    const large = await fetch(`${url}/api/control-plane/scenarios/image-comparison/runs`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ x: "a".repeat(70_000) }),
    });
    assert.equal(large.status, 413);
    assert.deepEqual(calls, []);
  });
});
