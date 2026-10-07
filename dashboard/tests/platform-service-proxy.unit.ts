import assert from "node:assert/strict";
import { test } from "node:test";
import { NextRequest } from "next/server";
import { GET, POST, DELETE } from "../app/api/platform-service/[...path]/route";

test("Platform-Service proxy preserves original methods, bodies and server-only credentials", async () => {
  const saved = { ...process.env }; const fetch = globalThis.fetch;
  try {
    process.env.EDGEAI_WORKFLOW_ENABLED = "true";
    process.env.EDGEAI_PLATFORM_SERVICE_URL = "http://127.0.0.1:18081";
    process.env.EDGEAI_PLATFORM_SERVICE_TOKEN = "server-only-secret";
    const calls: { url: string; init?: RequestInit }[] = [];
    globalThis.fetch = async (url, init) => { calls.push({url: String(url), init}); return Response.json({status:"fixture"}); };
    const context = (path: string) => ({params:Promise.resolve({path:path.split("/")})});
    const make = (path: string, method: string, origin = "http://192.168.0.56:13080", body = "{}") => new NextRequest(`http://192.168.0.56:13080/api/platform-service/${path}`, {method, headers:{origin,"Content-Type":"application/json"}, ...(method!=="GET"?{body}:{})});
    for (const [path, method, handler] of [["config","GET",GET],["workflow/build-image","POST",POST],["workflow/save-and-push","POST",POST],["workflow/delete","DELETE",DELETE]] as const) {
      const response = await handler(make(path,method),context(path));
      assert.equal(response.status,200);
      assert.doesNotMatch(await response.text(),/server-only-secret/);
      const call=calls.at(-1)!;
      assert.equal(call.url,`http://127.0.0.1:18081/${path}`);
      assert.equal(call.init?.method,method);
      assert.equal(new Headers(call.init?.headers).get("X-Platform-Token"),"server-only-secret");
      if(method!=="GET") assert.equal(call.init?.body,"{}");
      assert.equal(call.init?.redirect,"error");
    }
    calls.length=0;
    assert.equal((await POST(make("workflow/save-and-push","POST","http://other.test"),context("workflow/save-and-push"))).status,403);
    assert.equal((await POST(make("workflow/save-and-push","POST",""),context("workflow/save-and-push"))).status,403);
    assert.equal((await POST(make("admin","POST"),context("admin"))).status,404);
    assert.equal((await POST(make("workflow/save-and-push","POST",undefined,"x".repeat(1_100_001)),context("workflow/save-and-push"))).status,413);
    assert.equal(calls.length,0);
    process.env.EDGEAI_WORKFLOW_ENABLED="false";
    assert.equal((await GET(make("config","GET"),context("config"))).status,404);
    process.env.EDGEAI_WORKFLOW_ENABLED="true";
    delete process.env.EDGEAI_PLATFORM_SERVICE_TOKEN;
    assert.equal((await GET(make("config","GET"),context("config"))).status,503);
    assert.equal(calls.length,0);
  } finally {
    globalThis.fetch=fetch;
    for(const key of ["EDGEAI_WORKFLOW_ENABLED","EDGEAI_PLATFORM_SERVICE_URL","EDGEAI_PLATFORM_SERVICE_TOKEN"]) {
      if(saved[key]===undefined) delete process.env[key]; else process.env[key]=saved[key];
    }
  }
});
