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
    if (req.url.endsWith("/live/frames/404")) {
      res.writeHead(404, { "Content-Type": "application/json", "Cache-Control": "max-age=60" });
      return res.end('{"message":"frame expired"}');
    }
    if (req.url.endsWith("/image") || /\/frames\/[0-9]+$/.test(req.url)) {
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

test("Hawkeye video reads, session detail and verified frame images reach the management API", async () => {
  await fixture(async (url, calls) => {
    for (const path of [
      "scenarios/hawkeye-safety/video",
      "scenarios/hawkeye-safety/video/sessions?limit=10&offset=0",
      `scenarios/hawkeye-safety/video/sessions/${run}`,
    ]) {
      const response = await fetch(`${url}/api/control-plane/${path}`, { headers: { Cookie: "EDGEAI_SESSION=s" } });
      assert.equal(response.status, 200, path);
    }
    const frame = await fetch(`${url}/api/control-plane/scenarios/hawkeye-safety/video/tasks/${run}/frames/12`, {
      headers: { Cookie: "EDGEAI_SESSION=s" },
    });
    assert.equal(frame.status, 200);
    assert.equal(frame.headers.get("content-type"), "image/jpeg");
    assert.deepEqual(Buffer.from(await frame.arrayBuffer()), jpeg);
    assert.deepEqual(calls.map((call) => call.path), [
      "/api/v1/scenarios/hawkeye-safety/video",
      "/api/v1/scenarios/hawkeye-safety/video/sessions?limit=10&offset=0",
      `/api/v1/scenarios/hawkeye-safety/video/sessions/${run}`,
      `/api/v1/scenarios/hawkeye-safety/video/tasks/${run}/frames/12`,
    ]);
    assert.ok(calls.every((call) => call.headers.cookie === "EDGEAI_SESSION=s"));
  });
});

test("Hawkeye live observation JSON and JPEG frames reach the management API uncached", async () => {
  await fixture(async (url, calls) => {
    const live = await fetch(`${url}/api/control-plane/scenarios/hawkeye-safety/video/sessions/${run}/live`, {
      headers: { Cookie: "EDGEAI_SESSION=s" },
    });
    assert.equal(live.status, 200);
    assert.match(live.headers.get("content-type"), /^application\/json/);
    assert.equal(live.headers.get("cache-control"), "no-store");
    const frame = await fetch(`${url}/api/control-plane/scenarios/hawkeye-safety/video/sessions/${run}/live/frames/123456789`, {
      headers: { Cookie: "EDGEAI_SESSION=s" },
    });
    assert.equal(frame.status, 200);
    assert.equal(frame.headers.get("content-type"), "image/jpeg");
    assert.equal(frame.headers.get("cache-control"), "no-store");
    assert.deepEqual(Buffer.from(await frame.arrayBuffer()), jpeg);
    const history = await fetch(`${url}/api/control-plane/scenarios/hawkeye-safety/video/tasks/${run}/frames/123456789`);
    assert.equal(history.status, 200);
    const expired = await fetch(`${url}/api/control-plane/scenarios/hawkeye-safety/video/sessions/${run}/live/frames/404`);
    assert.equal(expired.status, 404);
    assert.equal(expired.headers.get("cache-control"), "no-store");
    assert.deepEqual(await expired.json(), { message: "frame expired" });
    assert.deepEqual(calls.map((call) => call.path), [
      `/api/v1/scenarios/hawkeye-safety/video/sessions/${run}/live`,
      `/api/v1/scenarios/hawkeye-safety/video/sessions/${run}/live/frames/123456789`,
      `/api/v1/scenarios/hawkeye-safety/video/tasks/${run}/frames/123456789`,
      `/api/v1/scenarios/hawkeye-safety/video/sessions/${run}/live/frames/404`,
    ]);
    assert.equal(calls[0].headers.cookie, "EDGEAI_SESSION=s");
  });
});

test("Hawkeye video start and stop forward CSRF, Idempotency-Key and the JSON body", async () => {
  await fixture(async (url, calls) => {
    const body = `{"workflowVersionId":"${run}","camera":{"id":"hawkeye-forklift-video","uid":"${run}"}}`;
    const started = await fetch(`${url}/api/control-plane/scenarios/hawkeye-safety/video/sessions`, {
      method: "POST",
      headers: { Origin: url, "Content-Type": "application/json", "X-CSRF-TOKEN": "t", "Idempotency-Key": run },
      body,
    });
    assert.equal(started.status, 200);
    const stopped = await fetch(`${url}/api/control-plane/scenarios/hawkeye-safety/video/sessions/${run}/stop`, {
      method: "POST",
      headers: { Origin: url, "Content-Type": "application/json", "X-CSRF-TOKEN": "t" },
      body: "{}",
    });
    assert.equal(stopped.status, 200);
    assert.deepEqual(calls.map((c) => [c.method, c.path, c.body, c.headers["x-csrf-token"]]), [
      ["POST", "/api/v1/scenarios/hawkeye-safety/video/sessions", body, "t"],
      ["POST", `/api/v1/scenarios/hawkeye-safety/video/sessions/${run}/stop`, "{}", "t"],
    ]);
    assert.equal(calls[0].headers["idempotency-key"], run);
  });
});

test("Hawkeye video routes are exact and method-bound", async () => {
  await fixture(async (url, calls) => {
    for (const [path, method] of [
      ["scenarios/hawkeye-safety/video", "POST"],
      [`scenarios/hawkeye-safety/video/sessions/${run}`, "POST"],
      [`scenarios/hawkeye-safety/video/sessions/${run}/stop`, "GET"],
      ["scenarios/hawkeye-safety/video/sessions/x", "GET"],
      [`scenarios/hawkeye-safety/video/tasks/${run}/frames/12`, "POST"],
      [`scenarios/hawkeye-safety/video/tasks/${run}/frames/-1`, "GET"],
      [`scenarios/hawkeye-safety/video/tasks/${run}/frames/12/image`, "GET"],
      ["scenarios/hawkeye-safety/video/tasks/x/frames/1", "GET"],
      [`scenarios/hawkeye-safety/video/tasks/${run}/frames/1234567890`, "GET"],
      [`scenarios/hawkeye-safety/video/sessions/${run}/live`, "POST"],
      [`scenarios/hawkeye-safety/video/sessions/${run}/live`, "DELETE"],
      [`scenarios/hawkeye-safety/video/sessions/${run}/live/frames/1`, "POST"],
      [`scenarios/hawkeye-safety/video/sessions/${run}/live/frames/-1`, "GET"],
      [`scenarios/hawkeye-safety/video/sessions/${run}/live/frames/1234567890`, "GET"],
      [`scenarios/hawkeye-safety/video/sessions/${run}/live/frames`, "GET"],
      [`scenarios/hawkeye-safety/video/sessions/${run}/live/frames/1/image`, "GET"],
      ["scenarios/hawkeye-safety/video/sessions/x/live", "GET"],
    ]) {
      const response = await fetch(`${url}/api/control-plane/${path}`, {
        method,
        headers: { "Content-Type": "application/json" },
        ...(method === "GET" ? {} : { body: "{}" }),
      });
      assert.ok([404, 405].includes(response.status), `${method} ${path}: ${response.status}`);
    }
    assert.deepEqual(calls, []);
  });
});
