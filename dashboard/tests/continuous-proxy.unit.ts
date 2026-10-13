import assert from "node:assert/strict";
import { test } from "node:test";
import { NextRequest } from "next/server";
import { GET, POST, PATCH } from "../app/api/control-plane/[...path]/route";

test("continuous registration, edit, outputs and history use only exact public routes", async () => {
  const original = global.fetch, previous = process.env.EDGEAI_API_BASE_URL;
  process.env.EDGEAI_API_BASE_URL = "http://127.0.0.1:18080";
  const calls: string[] = [];
  global.fetch = (async input => { calls.push(String(input)); return new Response("{}", { status: 200 }); }) as typeof fetch;
  const id = "11111111-1111-4111-8111-111111111111";
  try {
    for (const [path, method, handler] of [["virtual-devices/continuous-catalog", "GET", GET], ["virtual-devices/continuous", "POST", POST], [`virtual-devices/${id}/continuous`, "PATCH", PATCH], [`virtual-devices/${id}/outputs`, "GET", GET], [`virtual-devices/${id}/outputs/average/history`, "GET", GET]] as const) {
      const response = await handler(new NextRequest(`http://dashboard.test/api/control-plane/${path}`, { method, headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": "test-token" }, ...(method !== "GET" ? { body: "{}" } : {}) }), { params: Promise.resolve({ path: path.split("/") }) });
      assert.equal(response.status, 200);
      assert.equal(calls.at(-1), `http://127.0.0.1:18080/api/v1/${path}`);
    }
    for (const path of [`virtual-devices/${id}/outputs/average/history/extra`, "internal/v1/processing/poll"]) assert.equal((await GET(new NextRequest(`http://dashboard.test/api/control-plane/${path}`), { params: Promise.resolve({ path: path.split("/") }) })).status, 404);
    assert.equal(calls.length, 5);
  } finally { global.fetch = original; if (previous === undefined) delete process.env.EDGEAI_API_BASE_URL; else process.env.EDGEAI_API_BASE_URL = previous; }
});
