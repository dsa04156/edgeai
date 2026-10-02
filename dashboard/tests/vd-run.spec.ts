import { test, expect } from "@playwright/test";

test("VD workflow selection preserves retries and displays its actual result generation", async ({ page }, testInfo) => {
  // Browser request/presentation fixture. Actual Task/Pod/S3 acceptance is scripts/vd_acceptance.py --tasks.
  const workflowId = "11111111-1111-4111-8111-111111111111", versionId = "22222222-2222-4222-8222-222222222222";
  const runId = "33333333-3333-4333-8333-333333333333", taskId = "44444444-4444-4444-8444-444444444444";
  const vdId = "55555555-5555-4555-8555-555555555555", runtimeId = "66666666-6666-4666-8666-666666666666", pod = "77777777-7777-4777-8777-777777777777";
  const now = "2026-10-03T04:00:00Z"; let posts = 0; const keys: string[] = [];
  await page.route("**/api/control-plane/**", async route => {
    const req = route.request(), path = new URL(req.url()).pathname.replace("/api/control-plane/", "");
    const run = { id: runId, workflowVersionId: versionId, mode: "VD", vdId, nodeId: null, remoteTarget: null, state: "SUCCEEDED", parameters: {}, offload: null, createdAt: now, updatedAt: now };
    const task = { id: taskId, runId, key: "analyze", state: "SUCCEEDED", createdAt: now, updatedAt: now };
    if (path === "workflow-runs" && req.method() === "POST") {
      posts++; keys.push(req.headers()["idempotency-key"]);
      expect(req.postDataJSON()).toEqual({ workflowVersionId: versionId, execution: { mode: "VD", vdId }, parameters: {} });
      if (posts === 1) return route.fulfill({ status: 409, json: { message: "현재 namespace의 Ready VD만 실행 대상으로 선택할 수 있습니다." } });
      return route.fulfill({ status: 201, json: run });
    }
    let body: unknown;
    if (path === "csrf") body = { token: "fixture" };
    else if (path === "profiles/SERVICE" || path === "nodes") body = { items: [], nextOffset: null };
    else if (path === "workflows") body = { items: [{ id: workflowId, key: "vd-fixture", displayName: "VD 실행 시험", createdAt: now }], nextOffset: null };
    else if (path === `workflows/${workflowId}`) body = { workflow: { id: workflowId, key: "vd-fixture", displayName: "VD 실행 시험" }, versions: [{ id: versionId, workflowId, version: "1.0.0", digest: "fixture", dag: { tasks: [], dependencies: [] }, createdAt: now }], nextOffset: null };
    else if (path === "workflow-runs") body = { items: posts > 1 ? [run] : [], nextOffset: null };
    else if (path === `workflow-runs/${runId}`) body = { run, tasks: [task] };
    else if (path === `tasks/${taskId}`) body = { task, telemetry: null, offloads: [], attempts: [{ id: taskId, taskId, state: "SUCCEEDED", mode: "VD", vdId, nodeId: null, remoteTarget: null, cause: "INITIAL", number: 1, epoch: 1 }] };
    else if (path === `tasks/${taskId}/results`) body = { taskId, items: [{ id: runId, taskId, attemptId: taskId, runtimeId, epoch: 1, producerPodUid: pod, vdRuntimeId: runtimeId, remoteAllocationId: null, remoteSourceMode: null, manifestDigest: "sha256:" + "a".repeat(64), createdAt: now,
      artifacts: [{ port: "output", bucket: "fixture", objectKey: "output", objectVersion: "fixed-version", sha256: "a".repeat(64), bytes: 32, mediaType: "application/json" }] }] };
    else return route.fulfill({ status: 404, json: {} });
    await route.fulfill({ status: 200, json: body });
  });
  await page.goto("/workflows");
  await page.getByLabel("사용자 이름", { exact: true }).fill("fixture"); await page.getByLabel("비밀번호", { exact: true }).fill("fixture");
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await page.getByRole("button", { name: "VD 실행 시험 vd-fixture", exact: true }).click();
  await page.getByRole("button", { name: "1.0.0", exact: true }).click();
  await page.getByLabel("자동 노드 전환 사용", { exact: true }).check();
  await page.getByRole("combobox", { name: /실행 위치 정책/ }).selectOption("VD");
  await expect(page.getByLabel("자동 노드 전환 사용", { exact: true })).toHaveCount(0);
  await page.getByLabel("실행할 가상 장치 ID", { exact: true }).fill(vdId);
  await page.getByRole("button", { name: "실행 요청 저장", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: /.+/ })).toContainText("Ready VD");
  await page.getByRole("button", { name: "실행 요청 저장", exact: true }).click();
  const selected = page.getByRole("region", { name: "선택한 실행", exact: true });
  await expect(selected).toContainText(`실행 대상 VD ${vdId}`);
  await selected.getByRole("button", { name: "analyze", exact: true }).click();
  await expect(selected).toContainText("CPU·메모리는 VD 컨테이너 전체의 공유 측정값입니다.");
  const result = page.getByRole("region", { name: "검증된 결과 · analyze", exact: true });
  await expect(result).toContainText(`VD Runtime ${runtimeId}`); await expect(result).toContainText(`Pod ${pod}`);
  expect(keys).toHaveLength(2); expect(keys[0]).toBe(keys[1]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
  await selected.screenshot({ path: testInfo.outputPath("vd-task-result.png") });
});
