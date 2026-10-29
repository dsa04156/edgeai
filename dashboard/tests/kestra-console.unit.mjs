import assert from "node:assert/strict";
import { createServer } from "node:http";
import { test } from "node:test";
import { createDeviceRegistrationMiddleware } from "../kestra-native/device-registration.ts";

const id = "00000000-0000-4000-8000-000000000001";
async function fixture(action) {
  const calls = [];
  const backend = createServer(async (req, res) => {
    let body = "";
    for await (const part of req) body += part;
    calls.push({
      path: req.url,
      method: req.method,
      body,
      headers: req.headers,
    });
    res.setHeader("Content-Type", "application/json");
    res.setHeader("X-EdgeAI-Audit-ID", id);
    if (req.url.includes("conflict")) {
      res.statusCode = 409;
      return res.end('{"message":"revision conflict"}');
    }
    if (req.method === "DELETE") {
      res.statusCode = 204;
      return res.end();
    }
    res.end(body || '{"items":[],"nextOffset":null}');
  });
  await new Promise((resolve) => backend.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${backend.address().port}`;
  const middleware = createDeviceRegistrationMiddleware({
    managementUrl: baseUrl,
    platformUrl: baseUrl,
    platformToken: "platform-secret",
  });
  const preview = createServer((req, res) =>
    middleware(req, res, () => {
      res.statusCode = 404;
      res.end();
    }),
  );
  await new Promise((resolve) => preview.listen(0, "127.0.0.1", resolve));
  try {
    await action(`http://127.0.0.1:${preview.address().port}`, calls);
  } finally {
    await Promise.all([
      new Promise((resolve) => preview.close(resolve)),
      new Promise((resolve) => backend.close(resolve)),
    ]);
  }
}

test("native console connects profile, VD, workflow, task, result and audit read paths with pagination", async () => {
  await fixture(async (url, calls) => {
    const paths = [
      "profiles/SERVICE",
      "profiles/SERVICE/example/versions/1.0.0",
      "data-store",
      "workflow-data-sources",
      "workflow-csrf",
      `data-store/sensors/${id}/temperature/history`,
      `data-store/sensors/${id}/temperature/chart`,
      `virtual-devices/${id}/processing-versions`,
      "nodes",
      `nodes/${id}`,
      "virtual-devices",
      "virtual-devices/continuous-catalog",
      `virtual-devices/${id}`,
      `virtual-devices/${id}/execution`,
      `virtual-devices/${id}/outputs`,
      `virtual-devices/${id}/outputs/value/history`,
      "workflows",
      `workflows/${id}`,
      "workflow-runs",
      `workflow-runs/${id}`,
      `workflow-runs/${id}/placements`,
      `workflow-runs/${id}/streams`,
      `tasks/${id}`,
      `tasks/${id}/results`,
      `tasks/${id}/results/output/content`,
      `operations/${id}`,
      "audit-requests",
      `audit-requests/${id}`,
    ];
    for (const path of paths) {
      const response = await fetch(
        `${url}/api/control-plane/${path}?limit=20&offset=40`,
      );
      assert.equal(response.status, 200, path);
      assert.equal(response.headers.get("x-edgeai-audit-id"), id);
    }
    assert.equal(calls.length, paths.length);
    assert.ok(calls.every((call) => call.path.endsWith("?limit=20&offset=40")));
  });
});

test("native console forwards revisions, stable idempotency keys and exact JSON numeric literals", async () => {
  await fixture(async (url, calls) => {
    const paths = [
      ["POST", "profiles/SERVICE"],
      ["POST", "virtual-devices/continuous"],
      ["PATCH", `virtual-devices/${id}/continuous`],
      ...["provision", "drain"].map((action) => [
        "POST",
        `virtual-devices/${id}/${action}`,
      ]),
      ["POST", "workflows"],
      ["POST", `workflows/${id}/versions`],
      ["POST", "workflow-runs"],
      ["POST", `workflow-runs/${id}/cancel`],
      ["POST", `workflow-runs/${id}/drain`],
      ["POST", `tasks/${id}/cancel`],
      ["POST", `tasks/${id}/offload`],
      ["POST", "offload-lab/sessions"],
      ...["stop", "load", "load/stop"].map((action) => [
        "POST",
        `offload-lab/sessions/${id}/${action}`,
      ]),
      ["POST", "managed-devices/sensor-a/properties"],
      ["POST", "device-profiles"],
      ["POST", "device-connections/test"],
    ];
    const body = '{"revision":7,"parameters":{"exact":9007199254740993}}';
    for (const [method, path] of paths) {
      const response = await fetch(`${url}/api/control-plane/${path}`, {
        method,
        headers: {
          Origin: url,
          "Content-Type": "application/json",
          "Idempotency-Key": id,
          "X-CSRF-TOKEN": "csrf",
          Cookie: "other=discard; EDGEAI_SESSION=session",
        },
        body,
      });
      assert.equal(response.status, 200, path);
      assert.equal(await response.text(), body);
    }
    for (const call of calls) {
      assert.equal(call.body, body);
      assert.equal(call.headers["idempotency-key"], id);
      assert.equal(call.headers["x-csrf-token"], "csrf");
      assert.equal(call.headers.cookie, "EDGEAI_SESSION=session");
    }
  });
});

test("native delete paths retain empty responses and backend conflicts", async () => {
  await fixture(async (url, calls) => {
    for (const path of [
      `profiles/SERVICE/test/versions/1.0.0`,
      `virtual-devices/${id}`,
      "managed-devices/sensor-a",
      "managed-devices/sensor-a/properties/value",
    ]) {
      const response = await fetch(`${url}/api/control-plane/${path}`, {
        method: "DELETE",
      });
      assert.equal(response.status, 204, path);
      assert.equal(await response.text(), "");
    }
    const response = await fetch(
      `${url}/api/control-plane/profiles/SERVICE/conflict/versions/1.0.0`,
      { method: "DELETE" },
    );
    assert.equal(response.status, 409);
    assert.deepEqual(await response.json(), { message: "revision conflict" });
    assert.equal(calls.length, 5);
  });
});

test("DDS build/deploy/status/delete call Platform-Service with the server token and preserve DELETE bodies", async () => {
  await fixture(async (url, calls) => {
    for (const path of ["config", "workflow/status/flow-project"])
      assert.equal(
        (await fetch(`${url}/api/platform-service/${path}`)).status,
        200,
      );
    for (const [method, path] of [
      ["POST", "workflow/build-image"],
      ["POST", "workflow/save-and-push"],
      ["DELETE", "workflow/delete"],
    ]) {
      const body = JSON.stringify({
        content: "kind: Deployment\n# " + "x".repeat(70000),
        repo_path: "flow-project/deployment.yaml",
      });
      const response = await fetch(`${url}/api/platform-service/${path}`, {
        method,
        headers: { Origin: url, "Content-Type": "application/json" },
        body,
      });
      assert.equal(response.status, method === "DELETE" ? 204 : 200);
      assert.equal(calls.at(-1).body, body);
      assert.equal(calls.at(-1).path, `/${path}`);
      assert.equal(calls.at(-1).headers["x-platform-token"], "platform-secret");
      assert.equal(calls.at(-1).headers.cookie, undefined);
    }
    assert.equal(
      (
        await fetch(`${url}/api/platform-service/workflow/save-and-push`, {
          method: "POST",
          headers: {
            Origin: "https://evil.example",
            "Content-Type": "application/json",
          },
          body: "{}",
        })
      ).status,
      403,
    );
    // The server token only accompanies browser writes that prove the console origin.
    assert.equal(
      (
        await fetch(`${url}/api/platform-service/workflow/save-and-push`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: "{}",
        })
      ).status,
      403,
    );
    assert.equal(
      (
        await fetch(`${url}/api/platform-service/workflow/save-and-push`, {
          method: "POST",
          headers: { Origin: url, "Content-Type": "application/json" },
          body: JSON.stringify({ content: "x".repeat(1_100_001) }),
        })
      ).status,
      413,
    );
    assert.equal(calls.length, 5);
  });
});

test("unregistered console paths and wrong methods cannot tunnel arbitrary API writes", async () => {
  await fixture(async (url, calls) => {
    for (const [path, method] of [
      ["control-plane/profiles/DEVICE", "POST"],
      ["control-plane/tasks/not-a-uuid/offload", "POST"],
      ["control-plane/offload-lab/sessions/not-a-uuid/load", "POST"],
      [`control-plane/offload-lab/sessions/${id}/load/../../stop`, "POST"],
      [`control-plane/offload-lab/sessions/${id}`, "DELETE"],
      ["control-plane/offload-lab", "POST"],
      ["platform-service/workflow/save-and-push", "GET"],
      ["platform-service/workflow/status/../delete", "POST"],
      [`control-plane/tasks/${id}`, "DELETE"],
    ]) {
      const response = await fetch(`${url}/api/${path}`, {
        method,
        headers: { "Content-Type": "application/json" },
        ...(method === "GET" ? {} : { body: "{}" }),
      });
      assert.ok(
        [404, 405].includes(response.status),
        `${method} ${path}: ${response.status}`,
      );
    }
    assert.deepEqual(calls, []);
  });
});

test("management and workflow services keep independent sessions, CSRF and upstream paths", async () => {
  const calls = [];
  const upstream = (label) =>
    createServer(async (req, res) => {
      let body = "";
      for await (const chunk of req) body += chunk;
      calls.push({
        label,
        path: req.url,
        cookie: req.headers.cookie,
        csrf: req.headers["x-csrf-token"],
        body,
      });
      res.setHeader("Content-Type", "application/json");
      if (req.url === "/api/v1/csrf")
        res.setHeader(
          "Set-Cookie",
          `EDGEAI_${label === "workflow" ? "WORKFLOW_" : ""}SESSION=new-session; Path=/; HttpOnly; SameSite=Lax`,
        );
      res.end('{"token":"csrf","items":[]}');
    });
  const management = upstream("management"),
    workflow = upstream("workflow");
  const listen = async (server) => {
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    return `http://127.0.0.1:${server.address().port}`;
  };
  const managementUrl = await listen(management),
    workflowUrl = await listen(workflow);
  const middleware = createDeviceRegistrationMiddleware({
    managementUrl,
    workflowUrl,
  });
  const preview = createServer((req, res) =>
    middleware(req, res, () => {
      res.statusCode = 404;
      res.end();
    }),
  );
  const url = await listen(preview);
  try {
    const headers = {
      Cookie:
        "EDGEAI_SESSION=management-session; EDGEAI_WORKFLOW_SESSION=workflow-session; other=discard",
      Origin: url,
      "X-CSRF-TOKEN": "workflow-token",
      "Content-Type": "application/json",
    };
    const csrf = await fetch(`${url}/api/control-plane/workflow-csrf`, {
      headers,
    });
    assert.equal(csrf.status, 200);
    assert.match(csrf.headers.get("set-cookie"), /EDGEAI_WORKFLOW_SESSION=/);
    await fetch(`${url}/api/control-plane/workflow-runs`, {
      method: "POST",
      headers,
      body: '{"workflowId":"test"}',
    });
    await fetch(
      `${url}/api/control-plane/managed-devices/camera-a/preview?uid=${id}`,
      { headers },
    );
    await fetch(`${url}/api/control-plane/data-store`, { headers });
    assert.deepEqual(
      calls.map(({ label, path, cookie }) => ({ label, path, cookie })),
      [
        {
          label: "workflow",
          path: "/api/v1/csrf",
          cookie: "EDGEAI_WORKFLOW_SESSION=workflow-session",
        },
        {
          label: "workflow",
          path: "/api/v1/workflow-runs",
          cookie: "EDGEAI_WORKFLOW_SESSION=workflow-session",
        },
        {
          label: "management",
          path: `/api/devices/camera-a/preview?uid=${id}`,
          cookie: "EDGEAI_SESSION=management-session",
        },
        {
          label: "management",
          path: "/api/v1/data-store",
          cookie: "EDGEAI_SESSION=management-session",
        },
      ],
    );
    assert.equal(calls[1].csrf, "workflow-token");
    for (const path of [
      "profiles/VD",
      "virtual-devices/processing-catalog",
      `virtual-devices/${id}/registration`,
    ]) {
      assert.equal(
        (await fetch(`${url}/api/control-plane/${path}`)).status,
        404,
      );
    }
    assert.equal(
      (
        await fetch(
          `${url}/api/control-plane/managed-devices/camera-a/preview?uid=wrong`,
          { headers },
        )
      ).status,
      400,
    );
  } finally {
    await Promise.all(
      [preview, management, workflow].map(
        (server) => new Promise((resolve) => server.close(resolve)),
      ),
    );
  }
});

test("scheduling profile and decision routes use the workflow session with exact methods and a 1 MiB profile limit", async () => {
  const calls = [];
  const upstream = (label) =>
    createServer(async (req, res) => {
      let body = "";
      for await (const chunk of req) body += chunk;
      calls.push({
        label,
        method: req.method,
        path: req.url,
        cookie: req.headers.cookie,
        csrf: req.headers["x-csrf-token"],
        idempotency: req.headers["idempotency-key"],
        length: body.length,
      });
      res.setHeader("Content-Type", "application/json");
      res.statusCode = req.method === "POST" && req.url === "/api/v1/scheduling-profiles" ? 201 : 200;
      res.end('{"id":"profile"}');
    });
  const management = upstream("management"),
    workflow = upstream("workflow");
  const listen = async (server) => {
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    return `http://127.0.0.1:${server.address().port}`;
  };
  const managementUrl = await listen(management),
    workflowUrl = await listen(workflow);
  const proxy = (options) => {
    const middleware = createDeviceRegistrationMiddleware(options);
    return createServer((req, res) =>
      middleware(req, res, () => {
        res.statusCode = 404;
        res.end();
      }),
    );
  };
  const enabled = proxy({ managementUrl, workflowUrl }),
    disabled = proxy({ managementUrl, workflowUrl, workflowsEnabled: false });
  const url = await listen(enabled),
    disabledUrl = await listen(disabled);
  try {
    const headers = {
      Cookie: "EDGEAI_SESSION=management-session; EDGEAI_WORKFLOW_SESSION=workflow-session",
      Origin: url,
      "X-CSRF-TOKEN": "workflow-token",
      "Idempotency-Key": id,
      "Content-Type": "application/json",
    };
    const profile = JSON.stringify({ padding: "x".repeat(100_000) });
    assert.equal(
      (await fetch(`${url}/api/control-plane/scheduling-profiles`, { method: "POST", headers, body: profile })).status,
      201,
    );
    assert.equal((await fetch(`${url}/api/control-plane/scheduling-profiles/${id}`, { headers })).status, 200);
    assert.equal(
      (await fetch(`${url}/api/control-plane/scheduling-profiles/${id}/placement-preview`, { method: "POST", headers, body: '{"parameters":{}}' })).status,
      200,
    );
    assert.equal((await fetch(`${url}/api/control-plane/workflow-runs/${id}/scheduling-decision`, { headers })).status, 200);
    assert.deepEqual(calls, [
      { label: "workflow", method: "POST", path: "/api/v1/scheduling-profiles", cookie: "EDGEAI_WORKFLOW_SESSION=workflow-session", csrf: "workflow-token", idempotency: id, length: profile.length },
      { label: "workflow", method: "GET", path: `/api/v1/scheduling-profiles/${id}`, cookie: "EDGEAI_WORKFLOW_SESSION=workflow-session", csrf: "workflow-token", idempotency: id, length: 0 },
      { label: "workflow", method: "POST", path: `/api/v1/scheduling-profiles/${id}/placement-preview`, cookie: "EDGEAI_WORKFLOW_SESSION=workflow-session", csrf: "workflow-token", idempotency: id, length: 17 },
      { label: "workflow", method: "GET", path: `/api/v1/workflow-runs/${id}/scheduling-decision`, cookie: "EDGEAI_WORKFLOW_SESSION=workflow-session", csrf: "workflow-token", idempotency: id, length: 0 },
    ]);
    const oversized = JSON.stringify({ padding: "x".repeat(1024 * 1024) });
    const previewOversized = JSON.stringify({ padding: "x".repeat(66_000) });
    for (const [path, method, body, expected] of [
      ["scheduling-profiles", "POST", oversized, 413],
      [`scheduling-profiles/${id}/placement-preview`, "POST", previewOversized, 413],
      ["scheduling-profiles", "GET", undefined, 405],
      [`scheduling-profiles/${id}`, "DELETE", undefined, 405],
      [`scheduling-profiles/${id}/placement-preview`, "GET", undefined, 405],
      [`workflow-runs/${id}/scheduling-decision`, "POST", "{}", 405],
    ])
      assert.equal(
        (await fetch(`${url}/api/control-plane/${path}`, { method, headers, body })).status,
        expected,
        `${method} ${path}`,
      );
    assert.equal(
      (await fetch(`${url}/api/control-plane/scheduling-profiles`, { method: "POST", headers: { ...headers, Origin: "https://another.example" }, body: "{}" })).status,
      403,
    );
    assert.equal(
      (await fetch(`${disabledUrl}/api/control-plane/scheduling-profiles/${id}`, { headers: { ...headers, Origin: disabledUrl } })).status,
      404,
    );
    assert.equal(calls.length, 4);
  } finally {
    await Promise.all(
      [enabled, disabled, management, workflow].map(
        (server) => new Promise((resolve) => server.close(resolve)),
      ),
    );
  }
});

test("DDS without a configured token fails closed before any upstream call", async () => {
  let called = false;
  const backend = createServer((req, res) => {
    called = true;
    res.end("{}");
  });
  await new Promise((resolve) => backend.listen(0, "127.0.0.1", resolve));
  const managementUrl = `http://127.0.0.1:${backend.address().port}`;
  const middleware = createDeviceRegistrationMiddleware({
    managementUrl,
    platformUrl: managementUrl,
  });
  const preview = createServer((req, res) =>
    middleware(req, res, () => {
      res.statusCode = 404;
      res.end();
    }),
  );
  await new Promise((resolve) => preview.listen(0, "127.0.0.1", resolve));
  try {
    const url = `http://127.0.0.1:${preview.address().port}`;
    assert.equal((await fetch(`${url}/api/platform-service/config`)).status, 503);
    assert.equal(called, false);
  } finally {
    await Promise.all([
      new Promise((resolve) => preview.close(resolve)),
      new Promise((resolve) => backend.close(resolve)),
    ]);
  }
});

test("without a separate workflow API, workflow paths use the management API session", async () => {
  const calls = [];
  const backend = createServer((req, res) => {
    calls.push({ path: req.url, cookie: req.headers.cookie });
    res.setHeader("Content-Type", "application/json");
    res.end('{"items":[],"nextOffset":null}');
  });
  await new Promise((resolve) => backend.listen(0, "127.0.0.1", resolve));
  const managementUrl = `http://127.0.0.1:${backend.address().port}`;
  const middleware = createDeviceRegistrationMiddleware({ managementUrl });
  const preview = createServer((req, res) =>
    middleware(req, res, () => {
      res.statusCode = 404;
      res.end();
    }),
  );
  await new Promise((resolve) => preview.listen(0, "127.0.0.1", resolve));
  try {
    const url = `http://127.0.0.1:${preview.address().port}`;
    const response = await fetch(`${url}/api/control-plane/workflow-runs`, {
      headers: {
        Cookie: "EDGEAI_SESSION=management; EDGEAI_WORKFLOW_SESSION=stale",
      },
    });
    assert.equal(response.status, 200);
    assert.deepEqual(calls, [
      { path: "/api/v1/workflow-runs", cookie: "EDGEAI_SESSION=management" },
    ]);
  } finally {
    await Promise.all([
      new Promise((resolve) => preview.close(resolve)),
      new Promise((resolve) => backend.close(resolve)),
    ]);
  }
});
