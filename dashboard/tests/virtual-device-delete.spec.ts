import { test, expect, type Page } from "@playwright/test";

const id = "11111111-1111-4111-8111-111111111111";
const target = { id, key: "temperature", displayName: "온도 평균", revision: 0, state: "REGISTERED", executionMode: "CONTINUOUS_EKUIPER" };
const other = { ...target, id: "22222222-2222-4222-8222-222222222222", key: "pressure", displayName: "기압 평균" };
async function fixture(page: Page, options: { failedReadback?: boolean; lastPage?: boolean; csrfFailed?: boolean; waitDelete?: Promise<void> } = {}) {
  let attempts = 0, removed = false;
  await page.route("**/api/health", route => route.fulfill({ json: { status: "UP" } }));
  await page.route("**/api/control-plane/**", async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname.replace("/api/control-plane/", "");
    if (path === "csrf") return options.csrfFailed ? route.fulfill({ status: 503, json: { message: "요청 권한 조회 실패" } }) : route.fulfill({ json: { token: "fixture-token" } });
    if (path === "infrastructure") return route.fulfill({ json: { fetchedAt: new Date().toISOString(), nodesStatus: "AVAILABLE", sensorsStatus: "AVAILABLE", nodes: [], sensors: [] } });
    if (path === "virtual-devices/continuous-catalog") return route.fulfill({ json: { enabled: true, inputs: [] } });
    if (path === "virtual-devices") {
      if (removed && options.failedReadback && url.searchParams.get("limit") === "20") return route.fulfill({ status: 503, json: { message: "목록 조회 실패" } });
      const tombstone = { ...target, id: "old-release", key: "old-release", displayName: "이전 삭제 장치", state: "RELEASED" };
      const current = { ...target, state: removed ? "RELEASED" : "REGISTERED" };
      if (options.lastPage && url.searchParams.get("limit") === "20") return route.fulfill({ json: url.searchParams.get("offset") === "20" ? { items: [current], nextOffset: null } : { items: [other], nextOffset: 20 } });
      return route.fulfill({ json: { items: [current, other, tombstone], nextOffset: null } });
    }
    if (path === `virtual-devices/${id}` && request.method() === "DELETE") {
      expect(request.headers()["x-csrf-token"]).toBe("fixture-token");
      attempts++;
      if (attempts === 1 && !options.lastPage && !options.failedReadback && !options.waitDelete) return route.fulfill({ status: 503, json: { message: "삭제 요청 재시도" } });
      if (options.waitDelete) await options.waitDelete;
      removed = true; return route.fulfill({ json: { ...target, state: "RELEASED", revision: 1 } });
    }
    if (path.endsWith("/execution")) return route.fulfill({ json: { continuous: { enabled: true, desiredRunning: true, state: "RUNNING", reason: "HEALTHY" } } });
    return route.fulfill({ json: { items: [], nextOffset: null } });
  });
  return () => attempts;
}

test("management deletion requires confirmation, preserves retry and hides released history", async ({ page }, info) => {
  const attempts = await fixture(page); await page.goto("/devices?view=virtual");
  const list = page.getByRole("table", { name: "가상디바이스 목록", exact: true });
  await expect(list).not.toContainText("이전 삭제 장치");
  const trigger = list.getByRole("button", { name: "온도 평균 삭제", exact: true });
  await trigger.click(); const dialog = page.getByRole("dialog", { name: "온도 평균 · 가상디바이스 삭제", exact: true });
  await expect(dialog).toBeVisible(); await expect(dialog).toContainText("원본 센서와 저장된 결과 이력은 보존됩니다.");
  expect(attempts()).toBe(0); await page.keyboard.press("Escape"); await expect(trigger).toBeFocused();
  await trigger.click(); await page.screenshot({ path: info.outputPath("delete-confirmation.png") });
  await dialog.getByRole("button", { name: "삭제 확정", exact: true }).click(); await expect(dialog.getByRole("alert")).toHaveText("삭제 요청 재시도");
  await expect(list.getByRole("button", { name: "온도 평균 삭제", exact: true })).toHaveCount(1);
  await dialog.getByRole("button", { name: "삭제 확정", exact: true }).click(); await expect(dialog).not.toBeVisible();
  await expect(list).not.toContainText("온도 평균"); await expect(list).toContainText("기압 평균");
  await expect(page.getByRole("status").filter({ hasText: "목록에서 삭제했습니다" })).toBeFocused();
  await expect(page.getByRole("group", { name: "인프라 개수", exact: true }).getByRole("button", { name: "가상디바이스", exact: true })).toContainText("1개");
  expect(attempts()).toBe(2); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("a successful deletion remains removed when the following list read fails", async ({ page }) => {
  const attempts = await fixture(page, { failedReadback: true }); await page.goto("/devices?view=virtual");
  await page.getByRole("button", { name: "온도 평균 삭제", exact: true }).click();
  await page.getByRole("dialog", { name: "온도 평균 · 가상디바이스 삭제", exact: true }).getByRole("button", { name: "삭제 확정", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "온도 평균 · 가상디바이스 삭제", exact: true })).not.toBeVisible();
  await expect(page.getByRole("alert").filter({ hasText: "삭제는 완료됐지만" })).toContainText("삭제는 완료됐지만 목록 조회에 실패했습니다");
  await expect(page.getByRole("table", { name: "가상디바이스 목록", exact: true })).not.toContainText("온도 평균"); expect(attempts()).toBe(1);
});

test("deleting the last row on a later page returns to the previous page", async ({ page }) => {
  const attempts = await fixture(page, { lastPage: true }); await page.goto("/devices?view=virtual");
  await page.getByRole("button", { name: "다음", exact: true }).click(); await expect(page.getByText("2 페이지", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "온도 평균 삭제", exact: true }).click();
  await page.getByRole("dialog", { name: "온도 평균 · 가상디바이스 삭제", exact: true }).getByRole("button", { name: "삭제 확정", exact: true }).click();
  await expect(page.getByText("1 페이지", { exact: true })).toBeVisible(); await expect(page.getByRole("table", { name: "가상디바이스 목록", exact: true })).toContainText("기압 평균"); expect(attempts()).toBe(1);
});

test("deletion is disabled until the CSRF request succeeds", async ({ page }) => {
  const attempts = await fixture(page, { csrfFailed: true }); await page.goto("/devices?view=virtual");
  await expect(page.getByRole("button", { name: "온도 평균 삭제", exact: true })).toBeDisabled(); expect(attempts()).toBe(0);
});

test("pending deletion blocks duplicate submission and dialog dismissal", async ({ page }) => {
  let finish!: () => void;
  const waitDelete = new Promise<void>(resolve => { finish = resolve; });
  const attempts = await fixture(page, { waitDelete }); await page.goto("/devices?view=virtual");
  await page.getByRole("button", { name: "온도 평균 삭제", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "온도 평균 · 가상디바이스 삭제", exact: true });
  try {
    await dialog.getByRole("button", { name: "삭제 확정", exact: true }).evaluate((button: HTMLButtonElement) => { button.click(); button.click(); });
    await expect.poll(attempts).toBe(1); await expect(dialog.getByRole("button", { name: "삭제 중…", exact: true })).toBeDisabled();
    await page.keyboard.press("Escape"); await expect(dialog).toBeVisible(); await expect(dialog.getByRole("button", { name: "취소", exact: true })).toBeDisabled();
  } finally { finish(); }
  await expect(dialog).not.toBeVisible(); expect(attempts()).toBe(1);
});
