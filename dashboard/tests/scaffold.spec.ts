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
