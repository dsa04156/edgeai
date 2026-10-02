import { test, expect } from "@playwright/test";

test("execution measurements show units, missing values, expiry and new attempt isolation", async ({ page }, testInfo) => {
  const now = "2026-10-02T10:00:00Z";
  await page.clock.install({ time: new Date(now) });
  const runId = "11111111-1111-4111-8111-111111111111", taskId = "22222222-2222-4222-8222-222222222222";
  let available = true;
  await page.route("**/api/control-plane/**", async route => {
    const path = new URL(route.request().url()).pathname.replace("/api/control-plane/", "");
    const run = { id: runId, workflowVersionId: runId, mode: "AUTO", state: "RUNNING", parameters: {}, createdAt: now, updatedAt: now };
    const task = { id: taskId, runId, key: "analyze", state: "RUNNING", createdAt: now, updatedAt: now };
    let body: unknown;
    if (path === "csrf") body = { token: "fixture" };
    else if (path === "workflows" || path === "profiles/SERVICE" || path === "nodes") body = { items: [], nextOffset: null };
    else if (path === "workflow-runs") body = { items: [run], nextOffset: null };
    else if (path === `workflow-runs/${runId}`) body = { run, tasks: [task] };
    else if (path === `tasks/${taskId}/results`) body = { taskId, items: [] };
    else if (path === `tasks/${taskId}`) body = { task, offloads: [], attempts: [{ id: taskId, taskId, state: "RUNNING", number: 1, epoch: 1 }],
      telemetry: available ? { attemptId: taskId, sequence: 2, observedAt: now, receivedAt: now, intervalMillis: 5000,
        resourceSource: "CGROUP_V2", cpuUsageMicros: 1000000, cpuLimitMillicores: 500, memoryBytes: 8388608, memoryLimitBytes: null,
        latencySource: null, latencyMicros: null, latencyObservedAt: null } : null };
    else return route.fulfill({ status: 404, json: {} });
    await route.fulfill({ status: 200, json: body });
  });
  await page.goto("/workflows");
  await page.getByLabel("사용자 이름", { exact: true }).fill("fixture");
  await page.getByLabel("비밀번호", { exact: true }).fill("fixture");
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await page.getByRole("button", { name: runId, exact: true }).click();
  await page.getByRole("button", { name: "analyze", exact: true }).click();
  const region = page.getByRole("region", { name: "실행 측정", exact: true });
  await expect(region).toContainText("40.0% · 설정된 CPU 제한 기준");
  await expect(region).toContainText("8.0 MiB · 제한 미확인");
  await expect(region).toContainText("서비스 지연: 미수집");
  await expect(region).toContainText("최근 수집된 측정");
  await page.clock.fastForward(62000);
  await expect(region).toContainText("측정 만료");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await region.screenshot({ path: testInfo.outputPath("telemetry-expired.png") });
  available = false;
  await page.getByRole("button", { name: "결과 새로고침", exact: true }).click();
  await expect(region).toContainText("현재 시도의 측정값이 아직 없습니다.");
  await expect(region).not.toContainText("40.0%");
});
