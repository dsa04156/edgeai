import assert from "node:assert/strict";
import { test } from "node:test";
import { NextRequest } from "next/server";
import { DELETE, GET, POST } from "../app/api/control-plane/[...path]/route";

test("sensor history, commands and VD registration deletion use the control-plane proxy", async () => {
  const original = global.fetch, previous = process.env.EDGEAI_API_BASE_URL;
  process.env.EDGEAI_API_BASE_URL = "http://127.0.0.1:18080";
  const calls: { url: string; init: RequestInit }[] = [];
  global.fetch = (async (input, init) => { calls.push({ url: String(input), init: init || {} }); return new Response('{"ok":true}', { status: 200 }); }) as typeof fetch;
  try {
    for (const [path, handler, method] of [["sensors/readings", GET, "GET"], ["sensors/commands", GET, "GET"], ["sensors/command", POST, "POST"], ["virtual-devices/11111111-1111-4111-8111-111111111111/registration", DELETE, "DELETE"]] as const) {
      const request = new NextRequest(`http://dashboard.test/api/control-plane/${path}?device=sensor-1`, { method,
        headers: { Origin: "http://dashboard.test", "Content-Type": "application/json", "X-CSRF-TOKEN": "test-token" },
        ...(method === "POST" ? { body: '{"device":"sensor-1","command":"temp","method":"GET","values":{}}' } : {}) });
      assert.equal((await handler(request, { params: Promise.resolve({ path: path.split("/") }) })).status, 200);
      const call = calls[calls.length - 1];
      assert.equal(call.url, `http://127.0.0.1:18080/api/v1/${path}?device=sensor-1`);
      assert.equal(call.init.method, method);
      assert.equal(new Headers(call.init.headers).get("X-CSRF-TOKEN"), "test-token");
      if (method === "POST") assert.equal(JSON.parse(String(call.init.body)).command, "temp");
    }
    const result = await POST(new NextRequest("http://dashboard.test/api/control-plane/sensors/command", { method: "POST", headers: { Origin: "http://other.test" } }), { params: Promise.resolve({ path: ["sensors", "command"] }) });
    assert.equal(result.status, 403); assert.equal(calls.length, 4);
  } finally { global.fetch = original; if (previous === undefined) delete process.env.EDGEAI_API_BASE_URL; else process.env.EDGEAI_API_BASE_URL = previous; }
});

test("sensor registration forwards only exact routes, JSON, session and CSRF from the same origin", async () => {
  const original = global.fetch, previous = process.env.EDGEAI_API_BASE_URL;
  process.env.EDGEAI_API_BASE_URL = "http://127.0.0.1:18080";
  const calls: { url: string; init: RequestInit }[] = [];
  global.fetch = (async (input, init) => { calls.push({ url: String(input), init: init || {} }); return new Response('{"created":true}', { status: 201 }); }) as typeof fetch;
  const context = { params: Promise.resolve({ path: ["sensors", "registrations"] }) };
  const url = "http://dashboard.test/api/control-plane/sensors/registrations";
  const payload = { name: "temperature-002", templateId: "etri-arduino-temperature", endpoint: "/dev/edgeai/arduino-002", physicalDeviceId: "arduino-002", baudRate: 115200 };
  try {
    await GET(new NextRequest("http://dashboard.test/api/control-plane/sensors/registration-options"), { params: Promise.resolve({ path: ["sensors", "registration-options"] }) });
    assert.equal(calls[0].url, "http://127.0.0.1:18080/api/v1/sensors/registration-options");
    const result = await POST(new NextRequest(url, { method: "POST", headers: { Origin: "http://dashboard.test", "Content-Type": "application/json", Cookie: "EDGEAI_SESSION=test-session", "X-CSRF-TOKEN": "test-token" }, body: JSON.stringify(payload) }), context);
    assert.equal(result.status, 201);
    assert.deepEqual(JSON.parse(String(calls[1].init.body)), payload);
    assert.equal(new Headers(calls[1].init.headers).get("Cookie"), "EDGEAI_SESSION=test-session");
    assert.equal(new Headers(calls[1].init.headers).get("X-CSRF-TOKEN"), "test-token");
    assert.equal((await POST(new NextRequest(url, { method: "POST", headers: { Origin: "http://other.test" } }), context)).status, 403);
    assert.equal((await POST(new NextRequest(url, { method: "POST", headers: { "Content-Type": "text/plain" }, body: "{}" }), context)).status, 415);
    assert.equal((await POST(new NextRequest(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: "x".repeat(65537) }), context)).status, 413);
    assert.equal((await POST(new NextRequest(url, { method: "POST" }), { params: Promise.resolve({ path: ["sensors", "registrations", "delete-all"] }) })).status, 404);
    assert.equal(calls.length, 2);
  } finally { global.fetch = original; if (previous === undefined) delete process.env.EDGEAI_API_BASE_URL; else process.env.EDGEAI_API_BASE_URL = previous; }
});
