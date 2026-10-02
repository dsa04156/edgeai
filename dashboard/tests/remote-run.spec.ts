import { test, expect } from "@playwright/test";

test("Remote selection sends a provider key and displays the pinned synthetic binding", async ({ page }, testInfo) => {
  // Browser request/presentation fixture. Actual HTTP/provider/DB/S3 is covered by RemoteWorkerIntegrationTest.
  const workflowId = "11111111-1111-4111-8111-111111111111", versionId = "22222222-2222-4222-8222-222222222222";
  const runId = "33333333-3333-4333-8333-333333333333", taskId = "44444444-4444-4444-8444-444444444444";
  const now = "2026-10-02T13:00:00Z", binding = { providerKey: "reference", configurationDigest: "sha256:" + "a".repeat(64), sourceMode: "SYNTHETIC" };
  let posts = 0; const keys: string[] = [];
  await page.route("**/api/control-plane/**", async route => {
    const req = route.request(), path = new URL(req.url()).pathname.replace("/api/control-plane/", "");
    const run = { id: runId, workflowVersionId: versionId, mode: "REMOTE", nodeId: null, remoteTarget: binding, state: "RUNNING", parameters: {}, offload: null, createdAt: now, updatedAt: now };
    const task = { id: taskId, runId, key: "analyze", state: "RUNNING", createdAt: now, updatedAt: now };
    if (path === "workflow-runs" && req.method() === "POST") {
      posts++; keys.push(req.headers()["idempotency-key"]);
      expect(req.postDataJSON()).toEqual({ workflowVersionId: versionId, execution: { mode: "REMOTE", providerKey: "reference" }, parameters: {} });
      if (posts === 1) return route.fulfill({ status: 503, json: { message: "Remote 연결을 확인한 뒤 같은 요청을 재전송하세요." } });
      return route.fulfill({ status: 201, json: run });
    }
    let body: unknown;
    if (path === "csrf") body = { token: "fixture" };
    else if (path === "profiles/SERVICE" || path === "nodes") body = { items: [], nextOffset: null };
    else if (path === "workflows") body = { items: [{ id: workflowId, key: "remote-fixture", displayName: "원격 실행 시험", createdAt: now }], nextOffset: null };
    else if (path === `workflows/${workflowId}`) body = { workflow: { id: workflowId, key: "remote-fixture", displayName: "원격 실행 시험" }, versions: [{ id: versionId, workflowId, version: "1.0.0", digest: "fixture", dag: { tasks: [], dependencies: [] }, createdAt: now }], nextOffset: null };
    else if (path === "workflow-runs") body = { items: posts > 1 ? [run] : [], nextOffset: null };
    else if (path === `workflow-runs/${runId}`) body = { run, tasks: [task] };
    else if (path === `tasks/${taskId}/results`) body = { taskId, items: [] };
    else if (path === `tasks/${taskId}`) body = { task, telemetry: null, offloads: [], attempts: [{ id: taskId, taskId, state: "RUNNING", mode: "REMOTE", nodeId: null, remoteTarget: binding, cause: "INITIAL", number: 1, epoch: 1 }] };
    else return route.fulfill({ status: 404, json: {} });
    await route.fulfill({ status: 200, json: body });
  });
  await page.goto("/workflows");
  await page.getByLabel("사용자 이름", { exact: true }).fill("fixture"); await page.getByLabel("비밀번호", { exact: true }).fill("fixture");
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await page.getByRole("button", { name: "원격 실행 시험 remote-fixture", exact: true }).click();
  await page.getByRole("button", { name: "1.0.0", exact: true }).click();
  await page.getByLabel("자동 노드 전환 사용", { exact: true }).check();
  await page.getByRole("combobox", { name: "실행 위치 정책", exact: true }).selectOption("REMOTE");
  await expect(page.getByLabel("자동 노드 전환 사용", { exact: true })).toHaveCount(0);
  await expect(page.getByLabel("실행 노드 ID", { exact: true })).toHaveCount(0);
  await page.getByLabel("Remote 제공자 key", { exact: true }).fill("reference");
  await page.getByRole("region", { name: "선택한 DAG · 1.0.0", exact: true }).screenshot({ path: testInfo.outputPath("remote-request.png") });
  await page.getByRole("button", { name: "실행 요청 저장", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "같은 요청을 재전송" })).toBeVisible();
  await page.getByRole("button", { name: "실행 요청 저장", exact: true }).click();
  await expect(page.getByRole("region", { name: "선택한 실행", exact: true })).toBeVisible();
  expect(keys[0]).toMatch(/^[a-f0-9-]{36}$/); expect(keys[1]).toBe(keys[0]);
  await page.getByRole("button", { name: "analyze", exact: true }).click();
  const region = page.getByRole("region", { name: "선택한 실행", exact: true });
  await expect(region).toContainText("REMOTE · reference · SYNTHETIC");
  await expect(region).toContainText("현재 Remote 실행은 자원·지연 측정을 지원하지 않습니다.");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await region.screenshot({ path: testInfo.outputPath("remote-running.png") });
});
