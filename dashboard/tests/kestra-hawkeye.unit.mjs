import assert from "node:assert/strict";
import { createServer } from "node:http";
import { test } from "node:test";
import { createDeviceRegistrationMiddleware } from "../kestra-native/device-registration.ts";

const run = "0b9d4f3e-5f0c-4f1a-9a33-2a7d2b1c9e10";
const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10]);

async function fixture(action) {
  const calls = [];
  const backend = createServer(async (req, res) => {
    let body = "";
    for await (const part of req) body += part;
    calls.push({ path: req.url, method: req.method, body, headers: req.headers });
    if (req.url.endsWith("/image")) {
      res.setHeader("Content-Type", "image/jpeg");
      return res.end(jpeg);
    }
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

test("Hawkeye reads, sample images and history reach the management API with the session", async () => {
  await fixture(async (url, calls) => {
    for (const path of [
      "scenarios/hawkeye-safety",
      "scenarios/hawkeye-safety/runs?limit=10&offset=0",
      `scenarios/hawkeye-safety/runs/${run}`,
    ]) {
      const response = await fetch(`${url}/api/control-plane/${path}`, { headers: { Cookie: "EDGEAI_SESSION=s" } });
      assert.equal(response.status, 200, path);
    }
    const image = await fetch(`${url}/api/control-plane/scenarios/hawkeye-safety/samples/forklift_0004/image`, {
      headers: { Cookie: "EDGEAI_SESSION=s" },
    });
    assert.equal(image.status, 200);
    assert.equal(image.headers.get("content-type"), "image/jpeg");
    assert.deepEqual(Buffer.from(await image.arrayBuffer()), jpeg);
    assert.deepEqual(calls.map((call) => call.path), [
      "/api/v1/scenarios/hawkeye-safety",
      "/api/v1/scenarios/hawkeye-safety/runs?limit=10&offset=0",
      `/api/v1/scenarios/hawkeye-safety/runs/${run}`,
      "/api/v1/scenarios/hawkeye-safety/samples/forklift_0004/image",
    ]);
    assert.ok(calls.every((call) => call.headers.cookie === "EDGEAI_SESSION=s"));
  });
});

test("Hawkeye start forwards CSRF, Idempotency-Key and the JSON body", async () => {
  await fixture(async (url, calls, baseUrl) => {
    const started = await fetch(`${url}/api/control-plane/scenarios/hawkeye-safety/runs`, {
      method: "POST",
      headers: { Origin: url, "Content-Type": "application/json", "X-CSRF-TOKEN": "t", "Idempotency-Key": run },
      body: '{"sampleId":"forklift_0024"}',
    });
    assert.equal(started.status, 200);
    assert.equal(calls[0].path, "/api/v1/scenarios/hawkeye-safety/runs");
    assert.equal(calls[0].headers["idempotency-key"], run);
    assert.equal(calls[0].headers["x-csrf-token"], "t");
    assert.equal(calls[0].headers.origin, baseUrl);
    assert.equal(calls[0].body, '{"sampleId":"forklift_0024"}');
  });
});

test("Hawkeye routes are exact, method-bound and same-origin", async () => {
  await fixture(async (url, calls) => {
    for (const [path, method] of [
      ["scenarios/hawkeye-safety", "POST"],
      [`scenarios/hawkeye-safety/runs/${run}`, "POST"],
      [`scenarios/hawkeye-safety/runs/${run}/stop`, "POST"],
      ["scenarios/hawkeye-safety/runs/x", "GET"],
      ["scenarios/hawkeye-safety/samples/forklift_0004/image", "POST"],
      ["scenarios/hawkeye-safety/samples/../image", "GET"],
      ["scenarios/hawkeye-safety/samples/Forklift%2F1/image", "GET"],
      ["scenarios/hawkeye-safety/samples/forklift_0004", "GET"],
    ]) {
      const response = await fetch(`${url}/api/control-plane/${path}`, {
        method,
        headers: { "Content-Type": "application/json" },
        ...(method === "GET" ? {} : { body: "{}" }),
      });
      assert.ok([404, 405].includes(response.status), `${method} ${path}: ${response.status}`);
    }
    const crossSite = await fetch(`${url}/api/control-plane/scenarios/hawkeye-safety/runs`, {
      method: "POST", headers: { Origin: "http://evil.example", "Content-Type": "application/json" }, body: "{}",
    });
    assert.equal(crossSite.status, 403);
    assert.deepEqual(calls, []);
  });
});
