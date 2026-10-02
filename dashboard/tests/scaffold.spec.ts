import { test, expect } from "@playwright/test";

test("shows the real implementation scope without horizontal overflow", async ({ page }, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("실행의 시작점을 준비합니다.");
  await expect(page.getByText("아직 검증하지 않음")).toBeVisible();
  await expect(page.getByText("연결 대기 중")).toBeVisible();
  const health = await page.request.get("/api/health");
  expect(health.status()).toBe(503);
  expect(await health.json()).toEqual({ status: "DOWN" });
  await expect(page.getByText("이 화면은 클러스터의 실시간 상태를 표시하지 않습니다.")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  expect(errors).toEqual([]);
  await page.screenshot({ path: testInfo.outputPath("scaffold.png"), fullPage: true });
});


test("Profile connection fails visibly when the API is offline", async ({ page }) => {
  await page.goto("/profiles");
  await page.getByLabel("사용자 이름", { exact: true }).fill("offline-test");
  await page.getByLabel("비밀번호", { exact: true }).fill("offline-test-only");
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: /.+/ })).toContainText("Control Plane에 연결할 수 없습니다");
  await expect(page.getByRole("button", { name: "버전 발행" })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("Device connection fails visibly without presenting invented node data", async ({ page }) => {
  await page.goto("/devices");
  await page.getByLabel("사용자 이름", { exact: true }).fill("offline-test");
  await page.getByLabel("비밀번호", { exact: true }).fill("offline-test-only");
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: /.+/ })).toContainText("Control Plane에 연결할 수 없습니다");
  await expect(page.getByRole("heading", { name: "관측된 실행 노드" })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("Control Plane proxy rejects unlisted routes and write methods before forwarding", async ({ request }) => {
  const id = "00000000-0000-0000-0000-000000000000";
  for (const [method, path] of [["POST", "nodes"], ["DELETE", `nodes/${id}`], ["PATCH", "devices"],
    ["GET", `devices/${id}/sessions`], ["PUT", `devices/${id}/attachments/not-a-uuid`], ["GET", "actuator/env"],
    ["POST", `tasks/${id}/attempts`], ["DELETE", `workflows/${id}`], ["PATCH", `workflow-runs/${id}`], ["GET", "tasks"]]) {
    expect((await request.fetch(`/api/control-plane/${path}`, { method })).status()).toBe(404);
  }
});

test("Workflow connection failure does not display invented runs", async ({ page }) => {
  await page.goto("/workflows");
  await page.getByLabel("사용자 이름", { exact: true }).fill("offline-test");
  await page.getByLabel("비밀번호", { exact: true }).fill("offline-test-only");
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: /.+/ })).toContainText("Control Plane에 연결할 수 없습니다");
  await expect(page.getByRole("heading", { name: "실행 이력" })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
