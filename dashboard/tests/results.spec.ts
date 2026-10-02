import { test, expect } from "@playwright/test";

test("result view distinguishes pending, verified metadata and unavailable storage", async ({ page }, testInfo) => {
  // UI-only fixture. Real PostgreSQL/S3 and Kubernetes execution have separate gates.
  const runId = "11111111-1111-4111-8111-111111111111", taskId = "22222222-2222-4222-8222-222222222222";
  const attemptId = "33333333-3333-4333-8333-333333333333", resultId = "44444444-4444-4444-8444-444444444444";
  let phase: "pending" | "retry" | "verified" | "remote" | "unavailable" = "pending";
  const now = "2026-10-02T08:00:00Z";
  await page.route("**/api/control-plane/**", async route => {
    const path = new URL(route.request().url()).pathname.replace("/api/control-plane/", "");
    const run = { id: runId, workflowVersionId: runId, state: "RUNNING", mode: "AUTO", parameters: {}, retry: { maxAttempts: 3, backoffSeconds: 5, maxElapsedSeconds: 600, retryOn: ["RUNTIME_LOST"] }, createdAt: now, updatedAt: now };
    const task = { id: taskId, runId, key: "analyze", state: phase === "verified" ? "SUCCEEDED" : phase === "retry" ? "RETRY_WAIT" : "RUNNING", createdAt: now, updatedAt: now };
    let body: unknown;
    if (path === "csrf") body = { token: "ui-fixture" };
    else if (path === "workflows" || path === "profiles/SERVICE" || path === "nodes") body = { items: [], nextOffset: null };
    else if (path === "workflow-runs") body = { items: [run], nextOffset: null };
    else if (path === `workflow-runs/${runId}`) body = { run, tasks: [task] };
    else if (path === `tasks/${taskId}`) body = { task, attempts: [{ id: attemptId, taskId, number: 1, epoch: 1, state: phase === "retry" ? "FAILED" : task.state }] };
    else if (path === `tasks/${taskId}/results`) {
      if (phase === "unavailable") return route.fulfill({ status: 503, json: { code: "RESULT_STORE_UNAVAILABLE", message: "결과 저장소에 연결할 수 없습니다. 복구 후 다시 시도하세요." } });
      body = { taskId, items: phase === "pending" || phase === "retry" ? [] : [{ id: resultId, taskId, attemptId, runtimeId: runId, epoch: 1, producerPodUid: phase === "remote" ? null : attemptId,
        remoteAllocationId: phase === "remote" ? "55555555-5555-4555-8555-555555555555" : null, remoteSourceMode: phase === "remote" ? "SYNTHETIC" : null,
        manifestDigest: "sha256:" + "a".repeat(64), createdAt: now, artifacts: [{ port: "output", bucket: "edgeai-artifacts", objectKey: `tasks/${taskId}/attempts/${attemptId}/output`,
          objectVersion: "fixture-version", bytes: 1234, mediaType: "application/json", sha256: "b".repeat(64) }] }] };
    } else return route.fulfill({ status: 404, json: { message: "Unknown fixture path" } });
    await route.fulfill({ status: 200, json: body });
  });
  await page.goto("/workflows");
  await page.getByLabel("사용자 이름", { exact: true }).fill("fixture");
  await page.getByLabel("비밀번호", { exact: true }).fill("fixture");
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await page.getByRole("button", { name: runId, exact: true }).click();
  await page.getByRole("button", { name: "analyze", exact: true }).click();
  const results = page.getByRole("region", { name: "검증된 결과 · analyze", exact: true });
  await expect(results).toContainText("아직 확정된 결과가 없습니다.");
  await expect(results.getByRole("table")).toHaveCount(0);
  phase = "retry";
  await page.getByRole("button", { name: "실행 새로고침", exact: true }).click();
  await expect(page.getByRole("row").filter({ has: page.getByRole("button", { name: "analyze", exact: true }) })).toContainText("재시도 대기");
  await page.getByRole("button", { name: "analyze", exact: true }).click();
  await expect(page.getByRole("region", { name: "선택한 실행", exact: true })).toContainText("Attempt #1 · epoch 1 · 실패");
  await expect(page.getByRole("button", { name: "analyze 작업 취소", exact: true })).toBeEnabled();
  await page.screenshot({ path: testInfo.outputPath("retry-wait.png"), fullPage: true });
  phase = "verified";
  await results.getByRole("button", { name: "결과 새로고침", exact: true }).click();
  await expect(results).toContainText("파일 검증 완료");
  await expect(results.getByRole("row").filter({ hasText: "output" })).toContainText("1,234 bytes");
  await results.getByText("결과 ID·체크섬·파일 버전", { exact: true }).click();
  await expect(results).toContainText("b".repeat(64));
  await expect(results).toContainText("fixture-version");
  await expect(results).toContainText(resultId);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await results.screenshot({ path: testInfo.outputPath("verified-result.png") });
  phase = "remote";
  await results.getByRole("button", { name: "결과 새로고침", exact: true }).click();
  await expect(results).toContainText("Remote 참조 계산 · 실장비 검증 아님");
  await results.getByText("결과 ID·체크섬·파일 버전", { exact: true }).click();
  await expect(results.getByText("RemoteAllocation 55555555-5555-4555-8555-555555555555", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await results.screenshot({ path: testInfo.outputPath("remote-reference-result.png") });
  phase = "unavailable";
  await results.getByRole("button", { name: "결과 새로고침", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "결과 저장소에 연결할 수 없습니다." })).toBeVisible();
  await expect(results).toHaveCount(0);
  await page.getByRole("button", { name: "연결 해제", exact: true }).click();
  await expect(page.getByText("fixture-version", { exact: false })).toHaveCount(0);
});
