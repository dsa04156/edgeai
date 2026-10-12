import { test, expect } from "@playwright/test";

test("stream retry submission preserves policy and request identity and displays paginated route provenance", async ({ page }, testInfo) => {
  // UI fixture only. StreamRunIntegrationTest covers public MVC/PG; TLS/S3 tests cover real sources and Runner.
  const workflowId = "11111111-1111-4111-8111-111111111111", versionId = "22222222-2222-4222-8222-222222222222";
  const runId = "33333333-3333-4333-8333-333333333333", taskId = "44444444-4444-4444-8444-444444444444";
  const deviceId = "55555555-5555-4555-8555-555555555555", sessionId = "66666666-6666-4666-8666-666666666666";
  const now = "2026-10-03T09:00:00Z", keys: string[] = []; let posts = 0, reads = 0;
  const retry = { maxAttempts: 3, backoffSeconds: 2, maxElapsedSeconds: 300, retryOn: ["RUNTIME_LOST"] };
  const run = { id: runId, workflowVersionId: versionId, mode: "AUTO", state: "RUNNING", parameters: {}, retry, createdAt: now, updatedAt: now };
  const task = { id: taskId, runId, key: "sum", state: "RUNNING", createdAt: now, updatedAt: now };
  await page.route("**/api/control-plane/**", async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname.replace("/api/control-plane/", "");
    if (path === "workflow-runs" && req.method() === "POST") {
      keys.push(req.headers()["idempotency-key"]); posts++;
      expect(req.postDataJSON()).toEqual({ workflowVersionId: versionId, execution: { mode: "AUTO" }, parameters: {}, retry, streamInputs: [
        { deviceId, sourcePort: "samples", toTask: "sum", toPort: "a", maxPayloadBytes: 4096 },
      ] });
      if (posts === 1) return route.fulfill({ status: 409, json: { message: "원본 장치의 활성 세션이 필요합니다." } });
      return route.fulfill({ status: 201, json: run });
    }
    if (path === `workflow-runs/${runId}/streams`) {
      reads++;
      if (reads === 1) return route.fulfill({ status: 503, json: { message: "스트림 경로 저장소를 다시 확인하세요." } });
      const next = url.searchParams.get("offset") === "20";
      const value = { id: sessionId, runId, componentId: taskId, sourceTaskId: null, sourceDeviceId: deviceId,
        sourceProfileVersionId: versionId, sourceMode: "SYNTHETIC", sourcePort: "samples", consumerTaskId: taskId,
        consumerPort: "a", mediaType: "application/json", maxPayloadBytes: 4096, sourceSessionId: sessionId, sourceEpoch: 3,
        generation: next ? null : { id: versionId, number: 1, state: "ACTIVE", producerId: sessionId, producerEpoch: 3,
          consumerAttemptId: taskId, consumerEpoch: 1, leaseUntil: now, fenceReason: null, closedAt: null } };
      return route.fulfill({ status: 200, json: { items: [value], nextOffset: next ? null : 20 } });
    }
    let body: unknown;
    if (path === "csrf") body = { token: "fixture" };
    else if (path === "profiles/SERVICE" || path === "nodes") body = { items: [], nextOffset: null };
    else if (path === "workflows") body = { items: [{ id: workflowId, key: "stream-fixture", displayName: "스트림 실행 시험", createdAt: now }], nextOffset: null };
    else if (path === `workflows/${workflowId}`) body = { workflow: { id: workflowId, key: "stream-fixture", displayName: "스트림 실행 시험" }, versions: [{ id: versionId, workflowId, version: "1.0.0", digest: "fixture", dag: { tasks: [], dependencies: [] }, createdAt: now }], nextOffset: null };
    else if (path === "workflow-runs") body = { items: posts > 1 ? [run] : [], nextOffset: null };
    else if (path === `workflow-runs/${runId}`) body = { run, tasks: [task] };
    else return route.fulfill({ status: 404, json: {} });
    await route.fulfill({ status: 200, json: body });
  });
  await page.goto("/workflows");
  await page.getByLabel("사용자 이름", { exact: true }).fill("fixture"); await page.getByLabel("비밀번호", { exact: true }).fill("fixture");
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await page.getByRole("button", { name: "스트림 실행 시험 stream-fixture", exact: true }).click();
  await page.getByRole("button", { name: "1.0.0", exact: true }).click();
  await page.getByLabel("최대 실행 횟수", { exact: true }).fill("3");
  await page.getByLabel("재시도 대기 시간(초)", { exact: true }).fill("2");
  await page.getByLabel("재시도 허용 기간(초)", { exact: true }).fill("300");
  await page.getByLabel("파일 저장소 오류", { exact: true }).uncheck();
  await page.getByRole("button", { name: "장치 스트림 입력 추가", exact: true }).click();
  await page.getByLabel("원본 장치 ID", { exact: true }).fill(deviceId);
  await page.getByLabel("받는 작업 키", { exact: true }).fill("sum"); await page.getByLabel("받는 스트림 포트", { exact: true }).fill("a");
  await page.getByRole("button", { name: "장치 스트림 입력 추가", exact: true }).click();
  await page.getByRole("button", { name: "스트림 입력 2 삭제", exact: true }).click();
  await page.getByRole("group", { name: "스트림 입력 1", exact: true }).screenshot({ path: testInfo.outputPath("stream-input.png") });
  await page.getByRole("button", { name: "실행 요청 저장", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "활성 세션" })).toBeVisible();
  await expect(page.getByLabel("원본 장치 ID", { exact: true })).toHaveValue(deviceId);
  await expect(page.getByLabel("최대 실행 횟수", { exact: true })).toHaveValue("3");
  await page.getByRole("button", { name: "실행 요청 저장", exact: true }).click();
  await expect(page.getByText("작업별 최대 3회 · 실패 후 2초 대기 · 최초 시도부터 300초 동안 재시도 가능", { exact: true })).toBeVisible();
  const region = page.getByRole("region", { name: "스트림 경로", exact: true });
  await region.getByRole("button", { name: "스트림 경로 조회", exact: true }).click();
  await expect(region.getByRole("alert")).toHaveText("스트림 경로 저장소를 다시 확인하세요.");
  await region.getByRole("button", { name: "스트림 경로 조회", exact: true }).click();
  await expect(region).toContainText("연결 활성"); await expect(region).toContainText("합성 데이터"); await expect(region).toContainText("sum · a");
  await region.getByText("경로 상세", { exact: true }).click(); await expect(region).toContainText(`${sessionId} / 3`);
  await region.screenshot({ path: testInfo.outputPath("stream-routes.png") });
  await region.getByRole("button", { name: "다음 경로", exact: true }).click(); await expect(region).toContainText("작업 배정 대기");
  await expect(region.getByRole("button", { name: "다음 경로", exact: true })).toBeDisabled();
  await region.getByRole("button", { name: "이전 경로", exact: true }).click(); await expect(region).toContainText("연결 활성");
  expect(keys).toHaveLength(2); expect(keys[0]).toBe(keys[1]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
});

test("stream metadata proxy permits only the authenticated GET route", async ({ request }) => {
  const path = "/api/control-plane/workflow-runs/33333333-3333-4333-8333-333333333333/streams";
  expect((await request.get(path)).status()).toBe(401);
  // The configured test upstream is offline: 503 proves this route passed the whitelist.
  expect((await request.get(path, { headers: { Authorization: "Basic Zml4dHVyZTpmaXh0dXJl" } })).status()).toBe(503);
  expect((await request.post(path, { data: {} })).status()).toBe(404);
  expect((await request.get(`${path}/credentials`)).status()).toBe(404);
});
