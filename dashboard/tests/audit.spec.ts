import { test, expect } from "@playwright/test";

test("audit view separates unknown outcomes, denied requests and HTTP acceptance, and clears stale data", async ({ page }, testInfo) => {
  // UI fixtures only; real API/PostgreSQL fault tests prove the persisted boundaries.
  const first = "11111111-1111-4111-8111-111111111111", second = "22222222-2222-4222-8222-222222222222";
  const timestamp = "2026-10-04T04:00:00Z"; let mode = "normal";
  const unknown = { id: first, startedAt: timestamp, method: "POST", operation: "createWorkflowRun", routeTemplate: "/api/v1/workflow-runs", targetId: null, relatedId: null, state: "OUTCOME_UNKNOWN", outcome: null };
  const denied = { ...unknown, id: second, method: "GET", operation: "listDevices", routeTemplate: "/api/v1/devices", state: "OUTCOME_RECORDED",
    outcome: { completedAt: timestamp, httpStatus: 401, disposition: "HTTP_COMPLETED", actor: { type: "UNAUTHENTICATED", subject: null, subjectFormat: "NONE" } } };
  const errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
  await page.route("**/api/control-plane/audit-requests**", async route => {
    const url = new URL(route.request().url());
    expect(route.request().method()).toBe("GET");
    if (mode === "error") return route.fulfill({ status: 503, json: { message: "감사 저장소에 연결할 수 없습니다." } });
    if (url.pathname.endsWith(first)) return route.fulfill({ json: unknown });
    if (url.pathname.endsWith(second)) return route.fulfill({ json: denied });
    if (url.pathname.endsWith("audit-requests")) return route.fulfill({ json: { items: mode === "empty" ? [] : url.searchParams.get("offset") === "20" ? [denied] : [unknown, denied], nextOffset: mode === "empty" || url.searchParams.get("offset") === "20" ? null : 20 } });
    return route.fulfill({ status: 404, json: {} });
  });
  await page.goto("/audit");
  await page.getByLabel("사용자 이름", { exact: true }).fill("fixture-operator"); await page.getByLabel("비밀번호", { exact: true }).fill("fixture-only");
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await expect(page.getByRole("table", { name: "관리 요청 감사 목록" })).toContainText("결과 미확인");
  await expect(page.getByRole("table", { name: "관리 요청 감사 목록" })).toContainText("미인증 요청");
  await page.getByRole("button", { name: "워크플로 실행 요청 11111111", exact: true }).click();
  const detail = page.getByRole("region", { name: "감사 요청 상세", exact: true });
  await expect(detail).toContainText("요청은 접수됐지만 결과 기록이 없습니다.");
  await expect(detail).toContainText("기록 없음");
  await page.screenshot({ path: testInfo.outputPath("audit-unknown.png"), fullPage: true });
  await page.getByLabel("감사 ID", { exact: true }).fill(second);
  await page.getByRole("button", { name: "감사 조회", exact: true }).click();
  await expect(detail).toContainText("HTTP 401 · 거절"); await expect(detail).toContainText("미인증 요청");
  await page.getByRole("button", { name: "다음 감사", exact: true }).click();
  await expect(page.getByText("2 페이지", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "다음 감사", exact: true })).toBeDisabled();
  mode = "error"; await page.getByRole("button", { name: "감사 목록 새로고침", exact: true }).click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText("감사 저장소에 연결할 수 없습니다");
  await expect(page.getByRole("table", { name: "관리 요청 감사 목록" })).toHaveCount(0);
  await expect(detail).toHaveCount(0);
  mode = "empty"; await page.getByRole("button", { name: "감사 목록 새로고침", exact: true }).click();
  await expect(page.getByText("아직 저장된 감사 요청이 없습니다.", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "연결 해제", exact: true }).click();
  await expect(page.getByRole("heading", { name: "최근 관리 요청", exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(errors).toEqual([]);
});

test("audit proxy rejects writes and invalid IDs before forwarding", async ({ request }) => {
  for (const [method, path] of [["POST", "audit-requests"], ["DELETE", "audit-requests/11111111-1111-4111-8111-111111111111"], ["GET", "audit-requests/invalid"]])
    expect((await request.fetch(`/api/control-plane/${path}`, { method })).status()).toBe(404);
  expect((await request.get("/api/control-plane/audit-requests")).status()).toBe(401);
});
