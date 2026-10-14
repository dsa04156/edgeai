import { test, expect, type Page } from "@playwright/test";

const id = "11111111-1111-4111-8111-111111111111";
const vd = { id, key: "temperature", displayName: "온도 평균", revision: 0, state: "REGISTERED", executionMode: "CONTINUOUS_EKUIPER" };
async function fixture(page: Page, failed = false, empty = false) {
  await page.route("**/api/health", route => route.fulfill({ json: { status: "UP" } }));
  await page.route("**/api/control-plane/**", route => {
    const url = new URL(route.request().url()), path = url.pathname.replace("/api/control-plane/", "");
    if (path === "infrastructure") return failed ? route.fulfill({ status: 503 }) : route.fulfill({ json: { fetchedAt: new Date().toISOString(), nodesStatus: "AVAILABLE", sensorsStatus: "AVAILABLE", nodes: empty ? [] : [
      { name: "server-a", kind: "EDGE_AI_SERVER", status: "READY" }, { name: "server-b", kind: "EDGE_AI_SERVER", status: "READY" }, { name: "edge-a", kind: "EDGE_DEVICE", status: "READY" },
    ], sensors: empty ? [] : ["one", "two", "three"].map(name => ({ name })) } });
    if (path === "virtual-devices") return failed ? route.fulfill({ status: 503 }) : route.fulfill({ json: empty ? { items: [], nextOffset: null } : url.searchParams.get("limit") === "100" ? url.searchParams.get("offset") === "0" ? { items: [vd, { ...vd, id: "released", state: "RELEASED" }], nextOffset: 2 } : { items: [{ ...vd, id: "other", key: "pressure", displayName: "기압" }], nextOffset: null } : { items: [vd], nextOffset: null } });
    if (path === "csrf") return route.fulfill({ json: { token: "fixture" } });
    if (path === "virtual-devices/continuous-catalog") return route.fulfill({ json: { enabled: true, inputs: [] } });
    if (path.endsWith("/execution")) return route.fulfill({ json: { continuous: { enabled: true, desiredRunning: true, state: "RUNNING", reason: "HEALTHY" } } });
    if (path === "node-metrics") return route.fulfill({ json: { status: "DISABLED", fetchedAt: new Date().toISOString(), items: [] } });
    return route.fulfill({ json: { items: [], nextOffset: null } });
  });
}

test("infrastructure shows all four totals and opens virtual devices in the same screen", async ({ page }, info) => {
  await fixture(page); await page.goto("/devices");
  const totals = page.getByRole("group", { name: "인프라 개수", exact: true });
  for (const [name, count] of [["엣지 AI 서버", "2개"], ["엣지 디바이스", "1개"], ["센서", "3개"], ["가상디바이스", "2개"]]) await expect(totals.getByRole("button", { name, exact: true })).toContainText(count);
  await totals.getByRole("button", { name: "가상디바이스", exact: true }).click();
  await expect(page.getByRole("table", { name: "가상디바이스 목록", exact: true })).toContainText("온도 평균");
  await expect(page.getByRole("button", { name: "가상디바이스 등록", exact: true })).toBeVisible();
  await page.getByLabel("현재 페이지에서 검색", { exact: true }).fill("없음");
  await expect(totals.getByRole("button", { name: "가상디바이스", exact: true })).toContainText("2개");
  await expect(page.getByText("현재 페이지에서 일치하는 가상디바이스가 없습니다.", { exact: true })).toBeVisible();
  await page.getByLabel("현재 페이지에서 검색", { exact: true }).fill("");
  await page.screenshot({ path: info.outputPath("infrastructure-counts.png") });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  if (info.project.name === "mobile") await page.getByRole("button", { name: "메뉴 열기", exact: true }).click();
  await expect(page.getByRole("navigation", { name: "관리 메뉴", exact: true }).getByRole("link", { name: "인프라 관리", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "가상디바이스", exact: true })).toHaveCount(0);
  const management = page.getByRole("navigation", { name: "관리 메뉴", exact: true }).locator(".nav-group").filter({ has: page.locator("p").getByText("관리", { exact: true }) });
  await expect(management.getByRole("link")).toHaveCount(1);
  await expect(management.getByRole("link", { name: "프로젝트 문서", exact: true })).toBeVisible();
});

test("unavailable totals are unknown while a successfully empty inventory is zero", async ({ page }) => {
  await fixture(page, true); await page.goto("/devices");
  const totals = page.getByRole("group", { name: "인프라 개수", exact: true });
  for (const name of ["엣지 AI 서버", "엣지 디바이스", "센서", "가상디바이스"]) await expect(totals.getByRole("button", { name, exact: true })).toContainText("—");
  await fixture(page, false, true); await page.reload();
  for (const name of ["엣지 AI 서버", "엣지 디바이스", "센서", "가상디바이스"]) await expect(totals.getByRole("button", { name, exact: true })).toContainText("0개");
});

test("the previous virtual route keeps direct registration entry inside infrastructure", async ({ page }) => {
  await fixture(page); await page.goto("/virtual-devices?create=1");
  await expect(page).toHaveURL(/\/devices\?view=virtual&create=1$/);
  await expect(page.getByRole("dialog", { name: "가상디바이스 등록", exact: true })).toBeVisible();
});
