import { test, expect } from "@playwright/test";
import { randomUUID } from "node:crypto";

test("real profile mutation audit ID survives proxy and is searchable in the audit screen", async ({ page }, testInfo) => {
  const username = process.env.EDGEAI_API_USER, password = process.env.EDGEAI_API_PASSWORD;
  if (!username || !password) throw new Error("Audit E2E requires a configured real API");
  const authorization = `Basic ${Buffer.from(`${username}:${password}`).toString("base64")}`;
  const tokenResponse = await page.request.get("/api/control-plane/csrf", { headers: { authorization } });
  expect(tokenResponse.status()).toBe(200);
  const token = (await tokenResponse.json()).token;
  const forged = randomUUID();
  const response = await page.request.post("/api/control-plane/profiles/DEVICE", { headers: { authorization, "X-CSRF-TOKEN": token, "X-EdgeAI-Audit-Id": forged },
    data: { key: `audit-browser-${randomUUID()}`, version: "1.0.0", spec: { source: "SYNTHETIC" } } });
  expect(response.status()).toBe(201);
  const auditId = response.headers()["x-edgeai-audit-id"];
  expect(auditId).toMatch(/^[a-f0-9-]{36}$/); expect(auditId).not.toBe(forged);
  await expect.poll(async () => {
    const recorded = await page.request.get(`/api/control-plane/audit-requests/${auditId}`, { headers: { authorization } });
    return (await recorded.json()).state;
  }).toBe("OUTCOME_RECORDED");
  await page.goto("/audit");
  await page.getByLabel("사용자 이름", { exact: true }).fill(username); await page.getByLabel("비밀번호", { exact: true }).fill(password);
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await expect(page.getByRole("heading", { name: "최근 관리 요청", exact: true })).toBeVisible();
  await page.getByLabel("감사 ID", { exact: true }).fill(auditId); await page.getByRole("button", { name: "감사 조회", exact: true }).click();
  const detail = page.getByRole("region", { name: "감사 요청 상세", exact: true });
  await expect(detail).toContainText("프로필 버전 발행"); await expect(detail).toContainText("HTTP 201 · 응답 기록됨");
  await expect(detail).toContainText(username); await expect(detail).toContainText(auditId);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
  await page.screenshot({ path: testInfo.outputPath("audit-real-profile.png"), fullPage: true });
  await page.getByRole("button", { name: "연결 해제", exact: true }).click();
  await expect(detail).toHaveCount(0);
});
