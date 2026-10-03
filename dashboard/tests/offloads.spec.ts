import { test, expect } from "@playwright/test";

for (const [destination, stream, vdPeer] of [["NODE", false, false], ["REMOTE", false, false], ["NODE", true, false], ["NODE", true, true]] as const) test(`running ${stream ? "STREAM " : ""}${destination}${vdPeer ? " with VD peer" : ""} offload retains task identity and distinguishes transfer success from result success`, async ({ page }, testInfo) => {
  // Presentation/HTTP request fixture; actual PG and Kubernetes transfer have separate gates.
  const runId = "11111111-1111-4111-8111-111111111111", taskId = "22222222-2222-4222-8222-222222222222";
  const source = "33333333-3333-4333-8333-333333333333", target = "44444444-4444-4444-8444-444444444444";
  const nodeId = "55555555-5555-4555-8555-555555555555", operationId = "66666666-6666-4666-8666-666666666666";
  const peerId = "77777777-7777-4777-8777-777777777777", checkpointId = "88888888-8888-4888-8888-888888888888";
  let phase: "running" | "draining" | "starting" | "transferred" = "running";
  let calls = 0; const keys: string[] = [];
  const now = "2026-10-02T09:00:00Z";
  await page.route("**/api/control-plane/**", async route => {
    const path = new URL(route.request().url()).pathname.replace("/api/control-plane/", "");
    const run = { id: runId, workflowVersionId: runId, state: "RUNNING", mode: "AUTO", parameters: {}, createdAt: now, updatedAt: now };
    const task = { id: taskId, runId, key: "analyze", state: phase === "draining" ? "OFFLOADING" : "RUNNING", createdAt: now, updatedAt: now };
    const operation = { id: operationId, kind: "TASK_OFFLOAD", taskId, sourceAttemptId: source, targetAttemptId: phase === "draining" ? null : target,
      members: stream ? [
        { taskId, sourceAttemptId: source, targetAttemptId: phase === "draining" ? null : target, checkpointId, targetNodeId: nodeId, targetVdId: null, excludedNodeNames: [] },
        { taskId: peerId, sourceAttemptId: peerId, targetAttemptId: phase === "draining" ? null : operationId, checkpointId: peerId, targetNodeId: null, targetVdId: vdPeer ? peerId : null, excludedNodeNames: [] },
      ] : [],
      targetNodeId: destination === "NODE" ? nodeId : null, remoteTarget: destination === "REMOTE" ? { providerKey: "reference", sourceMode: "SYNTHETIC", configurationDigest: "sha256:" + "a".repeat(64) } : null, state: phase === "draining" ? "DRAINING" : phase === "starting" ? "STARTING" : "SUCCEEDED", failureReason: null, createdAt: now, updatedAt: now };
    if (path === `tasks/${taskId}/offload`) {
      const request = route.request(); expect(request.method()).toBe("POST");
      expect(request.postDataJSON()).toEqual({ sourceAttemptId: source, ...(destination === "NODE" ? { targetNodeId: nodeId } : { targetProviderKey: "reference" }), drainTimeoutSeconds: 60, startTimeoutSeconds: 120 });
      keys.push(request.headers()["idempotency-key"]); expect(request.headers()["x-csrf-token"]).toBe("ui-fixture");
      if (++calls === 1) return route.fulfill({ status: 503, json: { message: "전환 저장소 복구 후 동일 요청을 재전송하세요." } });
      phase = "draining"; return route.fulfill({ status: 202, json: { ...operation, state: "DRAINING" } });
    }
    let body: unknown;
    if (path === "csrf") body = { token: "ui-fixture" };
    else if (path === "workflows" || path === "profiles/SERVICE") body = { items: [], nextOffset: null };
    else if (path === "nodes") body = { items: [{ id: nodeId, name: "target-node", status: "READY", architecture: "amd64" }], nextOffset: null };
    else if (path === "workflow-runs") body = { items: [run], nextOffset: null };
    else if (path === `workflow-runs/${runId}`) body = { run, tasks: [task, ...(stream ? [{ ...task, id: peerId, key: "aggregate" }] : [])] };
    else if (path === `tasks/${taskId}/results`) body = { taskId, items: [] };
    else if (path === `tasks/${taskId}`) body = { task, attempts: [
      ...(phase === "starting" || phase === "transferred" ? [{ id: target, taskId, number: 2, epoch: 2, cause: "OFFLOAD", mode: destination, nodeId: destination === "NODE" ? nodeId : null, remoteTarget: operation.remoteTarget, state: phase === "starting" ? "DISPATCHING" : "RUNNING" }] : []),
      { id: source, taskId, number: 1, epoch: 1, cause: "INITIAL", mode: "AUTO", nodeId: null, state: phase === "running" ? "RUNNING" : "OFFLOADED" },
    ], offloads: phase === "running" ? [] : [operation] };
    else return route.fulfill({ status: 404, json: { message: "Unknown fixture path" } });
    await route.fulfill({ status: 200, json: body });
  });
  await page.goto("/workflows");
  await page.getByLabel("사용자 이름", { exact: true }).fill("fixture");
  await page.getByLabel("비밀번호", { exact: true }).fill("fixture");
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await page.getByRole("button", { name: runId, exact: true }).click();
  await page.getByRole("button", { name: "analyze", exact: true }).click();
  const region = page.getByRole("region", { name: "실행 위치 전환", exact: true });
  await region.getByRole("button", { name: "실행 위치 전환", exact: true }).click();
  await region.getByRole("combobox", { name: "전환 대상 종류", exact: true }).selectOption(destination);
  if (destination === "NODE") await region.getByLabel("전환할 노드", { exact: true }).fill(nodeId);
  else await region.getByLabel("전환할 Remote 제공자 key", { exact: true }).fill("reference");
  await region.screenshot({ path: testInfo.outputPath("offload-request.png") });
  await region.getByRole("button", { name: "전환 요청", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "동일 요청을 재전송" })).toBeVisible();
  await region.getByRole("button", { name: "전환 요청", exact: true }).click();
  await expect(region).toContainText("이전 실행 종료 중");
  if (stream) {
    await region.getByText("함께 전환하는 스트리밍 작업 2개", { exact: true }).click();
    const group = region.locator("details").filter({ hasText: "함께 전환하는 스트리밍 작업 2개" });
    await expect(group).toContainText(vdPeer ? `aggregate · 기존 VD 유지 · ${peerId}` : "aggregate · 자동 배치");
    await expect(group).toContainText("analyze · target-node");
    await expect(group).toContainText(`체크포인트 ${checkpointId}`);
    await expect(group.getByText(`체크포인트 ${checkpointId}`, { exact: true })).toBeVisible();
    await expect(group).toContainText("이전 실행 종료 대기");
  }
  expect(keys[0]).toMatch(/^[a-f0-9-]{36}$/); expect(keys[1]).toBe(keys[0]);
  await expect(region.getByRole("button", { name: "실행 위치 전환", exact: true })).toHaveCount(0);
  phase = "starting";
  await page.getByRole("button", { name: "결과 새로고침", exact: true }).click();
  await expect(region).toContainText("새 실행 확인 중");
  await expect(page.getByRole("region", { name: "선택한 실행", exact: true })).toContainText("Attempt #2 · epoch 2 · 배치 중");
  phase = "transferred";
  await page.getByRole("button", { name: "결과 새로고침", exact: true }).click();
  await expect(region).toContainText("전환 성공 · 새 실행 시작됨");
  if (stream) {
    const group = region.locator("details").filter({ hasText: "함께 전환하는 스트리밍 작업 2개" });
    if (await group.getAttribute("open") === null) await group.locator("summary").click();
    await expect(group.getByText(`새 Attempt ${target}`, { exact: true })).toBeVisible();
  }
  if (destination === "REMOTE") await expect(region).toContainText("Remote reference · SYNTHETIC");
  await expect(page.getByRole("region", { name: "검증된 결과 · analyze", exact: true })).toContainText("아직 확정된 결과가 없습니다.");
  await expect(page.getByRole("region", { name: "선택한 실행", exact: true }).locator(".stage")).toHaveText("실행 중");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await region.screenshot({ path: testInfo.outputPath("offload-started.png") });
});
