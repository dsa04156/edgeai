import { test, expect, type Page } from "@playwright/test";

const ids = ["11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444"];
async function overview(page: Page, options: { detailFailure?: boolean; listFailure?: boolean; empty?: boolean } = {}) {
  await page.route("**/api/health", route => route.fulfill({ json: { status: "UP" } }));
  await page.route("**/api/control-plane/**", async route => {
    const url = new URL(route.request().url()), path = url.pathname.replace("/api/control-plane/", "");
    if (path === "infrastructure") return route.fulfill({ json: { fetchedAt: new Date().toISOString(), nodesStatus: "AVAILABLE", sensorsStatus: "AVAILABLE", nodes: options.empty ? [] : [
      { name: "server-a", kind: "EDGE_AI_SERVER", status: "READY" }, { name: "server-b", kind: "EDGE_AI_SERVER", status: "NOT_READY" },
      { name: "edge-a", kind: "EDGE_DEVICE", status: "READY" }, { name: "edge-b", kind: "EDGE_DEVICE", status: "UNKNOWN" },
    ], sensors: options.empty ? [] : ["one", "two", "three"].map(name => ({ name })) } });
    if (path === "managed-devices") return route.fulfill({ json: { items: options.empty ? [] : ["ONLINE", "OFFLINE", "UNKNOWN"].map((state, index) => ({ id: ["one", "two", "three"][index], state })) } });
    if (path === "virtual-devices") {
      if (options.listFailure) return route.fulfill({ status: 503, json: { message: "목록 확인 필요" } });
      const offset = url.searchParams.get("offset");
      return route.fulfill({ json: options.empty ? { items: [], nextOffset: null } : { items: (offset === "0" ? ids.slice(0, 2) : ids.slice(2)).map(id => ({ id, state: id === ids[3] ? "RELEASED" : "REGISTERED" })), nextOffset: offset === "0" ? 2 : null } });
    }
    if (path.startsWith("virtual-devices/")) {
      const index = ids.findIndex(id => path.includes(id));
      if (options.detailFailure && index === 1) return route.fulfill({ status: 503, json: { message: "실행 상태 확인 필요" } });
      return route.fulfill({ json: { continuous: { enabled: true, state: ["RUNNING", "WAITING_INPUT", "STOPPED"][index] } } });
    }
    if (path === "node-metrics") return route.fulfill({ json: { status: "DISABLED", fetchedAt: new Date().toISOString(), items: [] } });
    return route.fulfill({ json: { items: [], nextOffset: null } });
  });
  await page.goto("/");
}

test("overview shows all four status donuts using readiness, sensor connection and actual virtual execution", async ({ page }) => {
  await overview(page);
  const strip = page.getByLabel("자원 현황", { exact: true });
  await expect(strip.locator(".metric")).toHaveCount(4);
  await expect(strip.getByRole("img", { name: "엣지 AI 서버: 전체 2개, 준비됨 1개, 준비 안 됨 1개, 미확인 0개", exact: true })).toBeVisible();
  await expect(strip.getByRole("img", { name: "엣지 디바이스: 전체 2개, 준비됨 1개, 준비 안 됨 0개, 미확인 1개", exact: true })).toBeVisible();
  await expect(strip.getByRole("img", { name: "센서: 전체 3개, 온라인 1개, 오프라인 1개, 관측 만료 0개, 미확인 1개", exact: true })).toBeVisible();
  await expect(strip.getByRole("img", { name: "가상 디바이스: 전체 3개, 계산 중 1개, 입력 대기 1개, 전환 중 0개, 중지·비활성 1개, 오류 0개, 미확인 0개", exact: true })).toBeVisible();
  await expect(strip.getByRole("link", { name: "가상 디바이스 상태 상세", exact: true })).toHaveAttribute("href", "/virtual-devices");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("an unavailable virtual execution stays unknown without discarding registered counts", async ({ page }) => {
  await overview(page, { detailFailure: true });
  await expect(page.getByRole("img", { name: "가상 디바이스: 전체 3개, 계산 중 1개, 입력 대기 0개, 전환 중 0개, 중지·비활성 1개, 오류 0개, 미확인 1개", exact: true })).toBeVisible();
  await expect(page.getByRole("main").getByRole("alert")).toContainText("가상 디바이스 실행 상태 1개");
});

test("failed virtual inventory is unknown while a successfully empty inventory is zero", async ({ page }) => {
  await overview(page, { listFailure: true });
  await expect(page.getByRole("img", { name: "가상 디바이스: 상태를 확인할 수 없습니다", exact: true })).toBeVisible();
  await page.unroute("**/api/control-plane/**");
  await overview(page, { empty: true });
  await expect(page.getByRole("img", { name: /^가상 디바이스: 전체 0개,/ })).toBeVisible();
  await expect(page.getByRole("img", { name: /^센서: 전체 0개,/ })).toBeVisible();
});
