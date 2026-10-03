import { test, expect } from "@playwright/test";

test("automatic offload is opt-in, preserves request identity and exposes measured decision evidence", async ({ page }, testInfo) => {
  // Browser interaction fixture; real measurements and placement have a separate kind gate.
  const workflowId = "11111111-1111-4111-8111-111111111111", versionId = "22222222-2222-4222-8222-222222222222";
  const runId = "33333333-3333-4333-8333-333333333333", taskId = "44444444-4444-4444-8444-444444444444";
  const now = "2026-10-02T10:00:00Z";
  const peerId = "55555555-5555-4555-8555-555555555555", peerNode = "66666666-6666-4666-8666-666666666666";
  let policy: Record<string, number | null> | null = null;
  let posts = 0; const keys: string[] = [];
  await page.route("**/api/control-plane/**", async route => {
    const req = route.request(), path = new URL(req.url()).pathname.replace("/api/control-plane/", "");
    const run = { id: runId, workflowVersionId: versionId, mode: "AUTO", state: "RUNNING", parameters: {}, offload: policy, createdAt: now, updatedAt: now };
    const task = { id: taskId, runId, key: "analyze", state: "RUNNING", createdAt: now, updatedAt: now };
    if (path === "workflow-runs" && req.method() === "POST") {
      posts++; keys.push(req.headers()["idempotency-key"]); expect(req.headers()["x-csrf-token"]).toBe("fixture");
      policy = req.postDataJSON().offload;
      expect(policy).toMatchObject({ cpuPercent: null, memoryPercent: 85, latencyMicros: null, consecutiveSamples: 3, maxTransfers: 1 });
      if (posts === 1) return route.fulfill({ status: 503, json: { message: "잠시 후 같은 요청을 재전송하세요." } });
      return route.fulfill({ status: 201, json: { ...run, offload: policy } });
    }
    let body: unknown;
    if (path === "csrf") body = { token: "fixture" };
    else if (path === "profiles/SERVICE") body = { items: [], nextOffset: null };
    else if (path === "nodes") body = { items: [{ id: peerNode, name: "peer-worker", status: "READY", architecture: "amd64", operatingSystem: "linux" }], nextOffset: null };
    else if (path === "workflows") body = { items: [{ id: workflowId, key: "automatic-fixture", displayName: "자동 전환 시험", createdAt: now }], nextOffset: null };
    else if (path === `workflows/${workflowId}`) body = { workflow: { id: workflowId, key: "automatic-fixture", displayName: "자동 전환 시험" }, versions: [{ id: versionId, workflowId, version: "1.0.0", digest: "fixture", dag: { tasks: [], dependencies: [] }, createdAt: now }], nextOffset: null };
    else if (path === "workflow-runs") body = { items: posts > 1 ? [run] : [], nextOffset: null };
    else if (path === `workflow-runs/${runId}`) body = { run, tasks: [task, { ...task, id: peerId, key: "sink" }] };
    else if (path === `tasks/${taskId}/results`) body = { taskId, items: [] };
    else if (path === `tasks/${taskId}`) body = { task, telemetry: null, attempts: [{ id: taskId, taskId, state: "RUNNING", mode: "AUTO", cause: "OFFLOAD", number: 2, epoch: 2 }],
      offloads: [{ id: runId, kind: "TASK_OFFLOAD", taskId, sourceAttemptId: versionId, targetAttemptId: taskId, targetNodeId: null, trigger: "MEMORY", state: "SUCCEEDED", excludedNodeNames: ["old-worker"],
        members: [{ taskId, sourceAttemptId: versionId, targetAttemptId: taskId, checkpointId: versionId, targetNodeId: null, excludedNodeNames: ["old-worker"] },
          { taskId: peerId, sourceAttemptId: peerId, targetAttemptId: peerId, checkpointId: peerId, targetNodeId: peerNode, excludedNodeNames: [] }],
        decision: { policy, evaluatedAt: now, eligibleSince: now, samples: [{ sequence: 3, memoryBytes: 950, memoryLimitBytes: 1000 }, { sequence: 2 }, { sequence: 1 }] } }] };
    else return route.fulfill({ status: 404, json: {} });
    await route.fulfill({ status: 200, json: body });
  });
  await page.goto("/workflows");
  await page.getByLabel("사용자 이름", { exact: true }).fill("fixture"); await page.getByLabel("비밀번호", { exact: true }).fill("fixture");
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await page.getByRole("button", { name: "자동 전환 시험 automatic-fixture", exact: true }).click();
  await page.getByRole("button", { name: "1.0.0", exact: true }).click();
  await expect(page.getByLabel("자동 노드 전환 사용", { exact: true })).not.toBeChecked();
  await page.getByLabel("자동 노드 전환 사용", { exact: true }).check();
  await page.getByLabel("CPU 사용률 기준(%)", { exact: true }).fill("");
  await page.getByLabel("메모리 사용률 기준(%)", { exact: true }).fill("");
  await page.getByRole("button", { name: "실행 요청 저장", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "자동 전환 기준을 한 개 이상" })).toBeVisible(); expect(posts).toBe(0);
  await page.getByLabel("메모리 사용률 기준(%)", { exact: true }).fill("85");
  await page.getByText("판단·반복 전환 제한 설정", { exact: true }).click();
  const region = page.getByRole("region", { name: "선택한 DAG · 1.0.0", exact: true });
  await region.screenshot({ path: testInfo.outputPath("automatic-settings.png") });
  await page.getByRole("button", { name: "실행 요청 저장", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "같은 요청을 재전송" })).toBeVisible();
  await page.getByRole("button", { name: "실행 요청 저장", exact: true }).click();
  await expect(page.getByRole("region", { name: "선택한 실행", exact: true })).toContainText("자동 전환 켜짐 · 메모리 85%");
  expect(keys[0]).toMatch(/^[a-f0-9-]{36}$/); expect(keys[1]).toBe(keys[0]);
  await page.getByRole("button", { name: "analyze", exact: true }).click();
  const history = page.getByRole("region", { name: "실행 위치 전환", exact: true });
  await expect(history).toContainText("메모리 사용률 기준 충족"); await expect(history).toContainText("다른 호환 노드 자동 배치");
  await history.getByText("함께 전환하는 스트리밍 작업 2개", { exact: true }).click();
  await expect(history).toContainText("analyze · 자동 배치"); await expect(history).toContainText("sink · peer-worker");
  await expect(history).toContainText(`체크포인트 ${peerId}`);
  await history.getByText("자동 판단 근거 · 측정 3개", { exact: true }).click();
  await expect(history).toContainText("제외 노드: old-worker"); await expect(history.getByLabel("자동 전환 판단 근거")).toContainText('"memoryBytes": 950');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await history.screenshot({ path: testInfo.outputPath("automatic-evidence.png") });
});
