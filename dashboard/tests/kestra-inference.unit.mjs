import assert from "node:assert/strict";
import { createServer } from "node:http";
import { test } from "node:test";
import { createDeviceRegistrationMiddleware } from "../kestra-native/device-registration.ts";

async function fixture(action) {
  const calls = [];
  const backend = createServer(async (req, res) => {
    let body = "";
    for await (const part of req) body += part;
    calls.push({ path: req.url, method: req.method, body, headers: req.headers });
    res.setHeader("Content-Type", "application/json");
    res.end(req.method === "GET" ? '{"items":[]}' : '{"detections":[]}');
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
const predictBody = (bytes) =>
  JSON.stringify({ modelId: "detector", imageBase64: "A".repeat(bytes) });

test("inference model list and prediction reach the management API with its session and CSRF", async () => {
  await fixture(async (url, calls, baseUrl) => {
    const models = await fetch(`${url}/api/control-plane/inference/models`, {
      headers: { Cookie: "EDGEAI_SESSION=fixture-session; other=discard" },
    });
    assert.equal(models.status, 200);
    assert.equal(calls[0].path, "/api/v1/inference/models");
    assert.equal(calls[0].headers.cookie, "EDGEAI_SESSION=fixture-session");
    // A 4 MiB image is ~5.6 MiB of base64, far above the 64 KiB management default.
    const body = predictBody(5_600_000);
    const predicted = await fetch(`${url}/api/control-plane/inference/predict`, {
      method: "POST",
      headers: {
        Origin: url,
        "Content-Type": "application/json",
        "X-CSRF-TOKEN": "fixture-token",
        Cookie: "EDGEAI_SESSION=fixture-session",
      },
      body,
    });
    assert.equal(predicted.status, 200);
    assert.equal(calls[1].path, "/api/v1/inference/predict");
    assert.equal(calls[1].method, "POST");
    assert.equal(calls[1].headers["x-csrf-token"], "fixture-token");
    assert.equal(calls[1].headers.origin, baseUrl);
    assert.equal(calls[1].body, body);
  });
});

test("only inference/predict gets the larger body limit; other management writes keep 64 KiB", async () => {
  await fixture(async (url, calls) => {
    const post = (path, body) =>
      fetch(`${url}/api/control-plane/${path}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body,
      });
    assert.equal((await post("inference/predict", predictBody(6_300_000))).status, 413);
    assert.equal((await post("managed-devices", predictBody(70_000))).status, 413);
    assert.equal((await post("workflow-runs", predictBody(70_000))).status, 413);
    assert.deepEqual(calls, []);
  });
});

test("inference routes are exact, method-bound and same-origin", async () => {
  await fixture(async (url, calls) => {
    for (const [path, method] of [
      ["inference/models", "POST"],
      ["inference/predict", "GET"],
      ["inference/predict/extra", "POST"],
      ["inference/models/x", "GET"],
      ["inference", "GET"],
    ]) {
      const response = await fetch(`${url}/api/control-plane/${path}`, {
        method,
        headers: { "Content-Type": "application/json" },
        ...(method === "GET" ? {} : { body: "{}" }),
      });
      assert.ok([404, 405].includes(response.status), `${method} ${path}: ${response.status}`);
    }
    const crossSite = await fetch(`${url}/api/control-plane/inference/predict`, {
      method: "POST",
      headers: { Origin: "http://evil.example", "Content-Type": "application/json" },
      body: predictBody(10),
    });
    assert.equal(crossSite.status, 403);
    assert.deepEqual(calls, []);
  });
});
