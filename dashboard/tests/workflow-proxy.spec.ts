import { test, expect } from "@playwright/test";
import { NextRequest } from "next/server";
import { GET, POST } from "../app/api/control-plane/[...path]/route";

test("optional workflow API keeps its CSRF session separate from the existing sensor API", async () => {
  const previous = { ...process.env }, originalFetch = globalThis.fetch;
  const calls: { url: string; headers: Headers }[] = [];
  try {
    process.env.EDGEAI_WORKFLOW_ENABLED = "true";
    process.env.EDGEAI_API_BASE_URL = "http://sensor.test:18080";
    process.env.EDGEAI_WORKFLOW_API_BASE_URL = "http://workflow.test:18082";
    globalThis.fetch = async (url, init) => { calls.push({ url: String(url), headers: new Headers(init?.headers) }); return Response.json({ token: "fixture" }); };
    const context = (path: string) => ({ params: Promise.resolve({ path: path.split("/") }) });
    const request = (path: string, method = "GET") => new NextRequest(`http://dashboard.test/api/control-plane/${path}`, {
      method, headers: { Cookie: "EDGEAI_SESSION=sensor-session; EDGEAI_WORKFLOW_SESSION=workflow-session", Origin: "http://dashboard.test",
        "Content-Type": "application/json", "X-CSRF-TOKEN": "fixture" }, ...(method === "POST" ? { body: "{}" } : {}),
    });
    expect((await GET(request("workflow-csrf"), context("workflow-csrf"))).status).toBe(200);
    expect(calls.at(-1)?.url).toBe("http://workflow.test:18082/api/v1/csrf");
    expect(calls.at(-1)?.headers.get("Cookie")).toBe("EDGEAI_WORKFLOW_SESSION=workflow-session");
    const drain = "workflow-runs/66666666-6666-4666-8666-666666666666/drain";
    expect((await POST(request(drain, "POST"), context(drain))).status).toBe(200);
    expect(calls.at(-1)?.url).toBe(`http://workflow.test:18082/api/v1/${drain}`);
    expect(calls.at(-1)?.headers.get("X-CSRF-TOKEN")).toBe("fixture");
    expect((await GET(request("managed-devices"), context("managed-devices"))).status).toBe(200);
    expect(calls.at(-1)?.url).toBe("http://sensor.test:18080/api/devices");
    expect(calls.at(-1)?.headers.get("Cookie")).toBe("EDGEAI_SESSION=sensor-session");
    process.env.EDGEAI_WORKFLOW_ENABLED = "false";
    expect((await GET(request("workflows"), context("workflows"))).status).toBe(404);
    expect((await GET(request("workflow-csrf"), context("workflow-csrf"))).status).toBe(200);
    expect(calls.at(-1)?.url).toBe("http://workflow.test:18082/api/v1/csrf");
    expect((await GET(request("profiles/SERVICE"), context("profiles/SERVICE"))).status).toBe(200);
    expect(calls.at(-1)?.url).toBe("http://workflow.test:18082/api/v1/profiles/SERVICE");
    delete process.env.EDGEAI_WORKFLOW_API_BASE_URL;
    expect((await GET(request("workflow-csrf"), context("workflow-csrf"))).status).toBe(200);
    expect(calls.at(-1)?.url).toBe("http://sensor.test:18080/api/v1/csrf");
    expect(calls.at(-1)?.headers.get("Cookie")).toBe("EDGEAI_SESSION=sensor-session");
  } finally {
    globalThis.fetch = originalFetch;
    for (const name of ["EDGEAI_WORKFLOW_ENABLED", "EDGEAI_API_BASE_URL", "EDGEAI_WORKFLOW_API_BASE_URL"]) {
      if (previous[name] === undefined) delete process.env[name]; else process.env[name] = previous[name];
    }
  }
});
