import assert from "node:assert/strict";
import { test } from "node:test";
import { NextRequest } from "next/server";
import { DELETE, GET, PATCH, POST, PUT } from "../app/api/control-plane/[...path]/route";

test("sensor history, commands and device registration deletion use the control-plane proxy", async () => {
  const original = global.fetch, previous = process.env.EDGEAI_API_BASE_URL;
  process.env.EDGEAI_API_BASE_URL = "http://127.0.0.1:18080";
  const calls: { url: string; init: RequestInit }[] = [];
  global.fetch = (async (input, init) => { calls.push({ url: String(input), init: init || {} }); return new Response('{"ok":true}', { status: 200 }); }) as typeof fetch;
  try {
    for (const [path, handler, method] of [["sensors/readings", GET, "GET"], ["sensors/commands", GET, "GET"], ["sensors/command", POST, "POST"], ["devices/11111111-1111-4111-8111-111111111111/registration", DELETE, "DELETE"]] as const) {
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

test("individual property deletion forwards the exact managed route and rejects other methods and origins", async () => {
  const original = global.fetch, previous = process.env.EDGEAI_API_BASE_URL;
  process.env.EDGEAI_API_BASE_URL = "http://127.0.0.1:18080";
  const calls: { url: string; init: RequestInit }[] = [];
  global.fetch = (async (input, init) => { calls.push({ url: String(input), init: init || {} }); return new Response(null, { status: 204 }); }) as typeof fetch;
  const path = "managed-devices/arduino-001/properties/temperature_raw";
  const url = `http://dashboard.test/api/control-plane/${path}`;
  const context = { params: Promise.resolve({ path: path.split("/") }) };
  try {
    const response = await DELETE(new NextRequest(url, { method: "DELETE", headers: { Origin: "http://dashboard.test", Cookie: "EDGEAI_SESSION=test-session", "X-CSRF-TOKEN": "test-token" } }), context);
    assert.equal(response.status, 204);
    assert.equal(calls[0].url, "http://127.0.0.1:18080/api/devices/arduino-001/properties/temperature_raw");
    assert.equal(calls[0].init.method, "DELETE");
    assert.equal(new Headers(calls[0].init.headers).get("Cookie"), "EDGEAI_SESSION=test-session");
    assert.equal(new Headers(calls[0].init.headers).get("X-CSRF-TOKEN"), "test-token");
    assert.equal((await DELETE(new NextRequest(url, { method: "DELETE", headers: { Origin: "http://other.test" } }), context)).status, 403);
    assert.equal((await GET(new NextRequest(url), context)).status, 404);
    assert.equal((await POST(new NextRequest(url, { method: "POST" }), context)).status, 404);
    assert.equal((await DELETE(new NextRequest(url, { method: "DELETE" }), { params: Promise.resolve({ path: [...path.split("/"), "extra"] }) })).status, 404);
    assert.equal(calls.length, 1);
  } finally { global.fetch = original; if (previous === undefined) delete process.env.EDGEAI_API_BASE_URL; else process.env.EDGEAI_API_BASE_URL = previous; }
});

test("single property addition forwards only its JSON body, session and CSRF to the managed API", async () => {
  const original = global.fetch, previous = process.env.EDGEAI_API_BASE_URL;
  process.env.EDGEAI_API_BASE_URL = "http://127.0.0.1:18080";
  const calls: { url: string; init: RequestInit }[] = [];
  global.fetch = (async (input, init) => { calls.push({ url: String(input), init: init || {} }); return new Response('{"id":"arduino-001"}', { status: 201 }); }) as typeof fetch;
  const path = "managed-devices/arduino-001/properties", url = `http://dashboard.test/api/control-plane/${path}`;
  const context = { params: Promise.resolve({ path: path.split("/") }) };
  const payload = { name: "acceleration_x_raw", dataType: "int32", sensor: "acceleration", jsonPath: "x", unit: "raw", scale: 1, offset: 0 };
  try {
    const response = await POST(new NextRequest(url, { method: "POST", headers: { Origin: "http://dashboard.test", "Content-Type": "application/json", Cookie: "EDGEAI_SESSION=test-session", "X-CSRF-TOKEN": "test-token" }, body: JSON.stringify(payload) }), context);
    assert.equal(response.status, 201); assert.equal(calls[0].url, "http://127.0.0.1:18080/api/devices/arduino-001/properties");
    assert.deepEqual(JSON.parse(String(calls[0].init.body)), payload);
    assert.equal(new Headers(calls[0].init.headers).get("Cookie"), "EDGEAI_SESSION=test-session");
    assert.equal(new Headers(calls[0].init.headers).get("X-CSRF-TOKEN"), "test-token");
    assert.equal((await POST(new NextRequest(url, { method: "POST", headers: { Origin: "http://other.test" } }), context)).status, 403);
    assert.equal((await POST(new NextRequest(url, { method: "POST", headers: { "Content-Type": "text/plain" }, body: "{}" }), context)).status, 415);
    assert.equal((await POST(new NextRequest(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: "x".repeat(65537) }), context)).status, 413);
    assert.equal((await GET(new NextRequest(url), context)).status, 404);
    assert.equal((await DELETE(new NextRequest(url, { method: "DELETE" }), context)).status, 404);
    assert.equal(calls.length, 1);
  } finally { global.fetch = original; if (previous === undefined) delete process.env.EDGEAI_API_BASE_URL; else process.env.EDGEAI_API_BASE_URL = previous; }
});


test("single property update forwards only the selected property and enforces mutation guards", async () => {
  const original = global.fetch, previous = process.env.EDGEAI_API_BASE_URL;
  process.env.EDGEAI_API_BASE_URL = "http://127.0.0.1:18080";
  const calls: { url: string; init: RequestInit }[] = [];
  global.fetch = (async (input, init) => { calls.push({ url: String(input), init: init || {} }); return new Response('{"id":"arduino-001"}', { status: 200 }); }) as typeof fetch;
  const path = "managed-devices/arduino-001/properties/temperature_raw", url = `http://dashboard.test/api/control-plane/${path}`;
  const context = { params: Promise.resolve({ path: path.split("/") }) };
  const payload = { name: "temperature_raw", dataType: "float32", sensor: "temperature", jsonPath: "raw", unit: "C", scale: 0.1, offset: 2 };
  try {
    const response = await PUT(new NextRequest(url, { method: "PUT", headers: { Origin: "http://dashboard.test", "Content-Type": "application/json", Cookie: "EDGEAI_SESSION=test-session; ignored=secret", "X-CSRF-TOKEN": "test-token" }, body: JSON.stringify(payload) }), context);
    assert.equal(response.status, 200); assert.equal(calls[0].url, "http://127.0.0.1:18080/api/devices/arduino-001/properties/temperature_raw");
    assert.equal(calls[0].init.method, "PUT"); assert.deepEqual(JSON.parse(String(calls[0].init.body)), payload);
    assert.equal(new Headers(calls[0].init.headers).get("Cookie"), "EDGEAI_SESSION=test-session");
    assert.equal(new Headers(calls[0].init.headers).get("X-CSRF-TOKEN"), "test-token");
    assert.equal((await PUT(new NextRequest(url, { method: "PUT", headers: { Origin: "http://other.test" } }), context)).status, 403);
    assert.equal((await PUT(new NextRequest(url, { method: "PUT", headers: { "Content-Type": "text/plain" }, body: "{}" }), context)).status, 415);
    assert.equal((await PUT(new NextRequest(url, { method: "PUT", headers: { "Content-Type": "application/json" }, body: "x".repeat(65537) }), context)).status, 413);
    assert.equal((await PUT(new NextRequest(url, { method: "PUT" }), { params: Promise.resolve({ path: [...path.split("/"), "extra"] }) })).status, 404);
    assert.equal((await GET(new NextRequest(url), context)).status, 404);
    assert.equal((await POST(new NextRequest(url, { method: "POST" }), context)).status, 404);
    assert.equal(calls.length, 1);
  } finally { global.fetch = original; if (previous === undefined) delete process.env.EDGEAI_API_BASE_URL; else process.env.EDGEAI_API_BASE_URL = previous; }
});

test("retired batch, supervisor and VD profile routes are never forwarded", async () => {
  const original = global.fetch;
  let calls = 0;
  global.fetch = (async () => { calls++; throw new Error("Unexpected upstream request"); }) as typeof fetch;
  try {
    for (const path of ["virtual-devices/processing-catalog", "virtual-devices/11111111-1111-4111-8111-111111111111/runtime", "profiles/VD", "internal/vd/poll"]) {
      for (const [method, handler] of [["GET", GET], ["POST", POST], ["PATCH", PATCH], ["DELETE", DELETE]] as const) {
        const response = await handler(new NextRequest(`http://dashboard.test/api/control-plane/${path}`, { method,
          headers: { Origin: "http://dashboard.test", "Content-Type": "application/json" } }), { params: Promise.resolve({ path: path.split("/") }) });
        assert.equal(response.status, 404, `${method} ${path}`);
      }
    }
    assert.equal(calls, 0);
  } finally { global.fetch = original; }
});
