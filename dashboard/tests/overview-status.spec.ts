import { test, expect, type Page } from "@playwright/test";

const ids = ["11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444"];
async function overview(page: Page, options: { detailFailure?: boolean; listFailure?: boolean; empty?: boolean; managedGate?: Promise<void>; virtualGate?: Promise<void> } = {}) {
  await page.route("**/api/health", route => route.fulfill({ json: { status: "UP" } }));
  await page.route("**/api/control-plane/**", async route => {
    const url = new URL(route.request().url()), path = url.pathname.replace("/api/control-plane/", "");
    if (path === "infrastructure") return route.fulfill({ json: { fetchedAt: new Date().toISOString(), nodesStatus: "AVAILABLE", sensorsStatus: "AVAILABLE", nodes: options.empty ? [] : [
      { name: "server-a", kind: "EDGE_AI_SERVER", status: "READY" }, { name: "server-b", kind: "EDGE_AI_SERVER", status: "NOT_READY" },
      { name: "edge-a", kind: "EDGE_DEVICE", status: "READY" }, { name: "edge-b", kind: "EDGE_DEVICE", status: "UNKNOWN" },
    ], sensors: options.empty ? [] : ["one", "two", "three"].map(name => ({ name })) } });
    if (path === "managed-devices") {
      await options.managedGate;
      return route.fulfill({ json: { items: options.empty ? [] : ["ONLINE", "OFFLINE", "UNKNOWN"].map((state, index) => ({ id: ["one", "two", "three"][index], state })) } });
    }
    if (path === "virtual-devices") {
      if (options.listFailure) return route.fulfill({ status: 503, json: { message: "목록 확인 필요" } });
      const offset = url.searchParams.get("offset");
      return route.fulfill({ json: options.empty ? { items: [], nextOffset: null } : { items: (offset === "0" ? ids.slice(0, 2) : ids.slice(2)).map(id => ({ id, state: id === ids[3] ? "RELEASED" : "REGISTERED" })), nextOffset: offset === "0" ? 2 : null } });
    }
    if (path.startsWith("virtual-devices/")) {
      await options.virtualGate;
      const index = ids.findIndex(id => path.includes(id));
      if (options.detailFailure && index === 1) return route.fulfill({ status: 503, json: { message: "실행 상태 확인 필요" } });
      return route.fulfill({ json: { continuous: { enabled: true, state: ["RUNNING", "WAITING_INPUT", "STOPPED"][index] } } });
    }
    if (path === "node-metrics") return route.fulfill({ json: { status: "DISABLED", fetchedAt: new Date().toISOString(), items: [] } });
    if (path === "workflow-runs") return route.fulfill({ json: { items: [{ id: ids[0], state: "RUNNING", mode: "AUTO", createdAt: new Date().toISOString() }], nextOffset: null } });
    return route.fulfill({ json: { items: [], nextOffset: null } });
  });
  await page.goto("/");
}

test("overview shows all four status summaries using readiness, sensor connection and actual virtual execution", async ({ page }) => {
  await overview(page);
  const strip = page.getByLabel("자원 현황", { exact: true });
  await expect(strip.locator(".resource-status-card")).toHaveCount(4);
  await expect(strip.getByRole("group", { name: "엣지 AI 서버: 전체 2개, 준비됨 1개, 준비 안 됨 1개, 미확인 0개", exact: true })).toBeVisible();
  await expect(strip.getByRole("group", { name: "엣지 디바이스: 전체 2개, 준비됨 1개, 준비 안 됨 0개, 미확인 1개", exact: true })).toBeVisible();
  await expect(strip.getByRole("group", { name: "센서: 전체 3개, 온라인 1개, 오프라인 1개, 관측 만료 0개, 미확인 1개", exact: true })).toBeVisible();
  await expect(strip.getByRole("group", { name: "가상디바이스: 전체 3개, 계산 중 1개, 입력 대기 1개, 전환 중 0개, 중지·비활성 1개, 오류 0개, 미확인 0개", exact: true })).toBeVisible();
  await expect(strip.getByRole("link", { name: "가상디바이스 상태 상세", exact: true })).toHaveAttribute("href", "/devices?view=virtual");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(await strip.locator(".resource-status-card").evaluateAll(cards => cards.every(card => {
    const heading = card.querySelector("h2")!.getBoundingClientRect();
    const total = card.querySelector(".resource-status-number")!.getBoundingClientRect();
    const legend = card.querySelector(".resource-status-legend")!.getBoundingClientRect();
    const bounds = card.getBoundingClientRect();
    return heading.width >= 110 && heading.bottom <= total.top && total.left >= bounds.left && total.right <= bounds.right &&
      (legend.top >= total.bottom || legend.left >= total.right);
  }))).toBe(true);
});

test("sensor loading stays pending until its connection status response settles", async ({ page }) => {
  let release!: () => void;
  const managedGate = new Promise<void>(resolve => { release = resolve; });
  try {
    await overview(page, { managedGate });
    await expect(page.getByRole("group", { name: /^엣지 AI 서버: 전체 2개,/ })).toBeVisible();
    await expect(page.getByRole("group", { name: "센서: 불러오는 중", exact: true })).toBeVisible();
    release();
    await expect(page.getByRole("group", { name: /^센서: 전체 3개, 온라인 1개,/ })).toBeVisible();
  } finally { release(); }
});

test("slow virtual execution reads do not block ordinary status or manual refresh", async ({ page }) => {
  let release!: () => void;
  const virtualGate = new Promise<void>(resolve => { release = resolve; });
  try {
    await overview(page, { virtualGate });
    await expect(page.getByRole("group", { name: /^센서: 전체 3개, 온라인 1개,/ })).toBeVisible();
    await expect(page.getByRole("button", { name: "새로고침", exact: true })).toBeEnabled();
    await expect(page.getByRole("group", { name: "가상디바이스: 불러오는 중", exact: true })).toBeVisible();
    release();
    await expect(page.getByRole("group", { name: /^가상디바이스: 전체 3개, 계산 중 1개,/ })).toBeVisible();
  } finally { release(); }
});

test("an unavailable virtual execution stays unknown without discarding registered counts", async ({ page }) => {
  await overview(page, { detailFailure: true });
  await expect(page.getByRole("group", { name: "가상디바이스: 전체 3개, 계산 중 1개, 입력 대기 0개, 전환 중 0개, 중지·비활성 1개, 오류 0개, 미확인 1개", exact: true })).toBeVisible();
  await expect(page.getByRole("main").getByRole("alert")).toContainText("가상디바이스 실행 상태 1개");
});

test("status card headings, totals and legends stay separate across responsive widths", async ({ page }) => {
  await overview(page);
  await expect(page.getByRole("group", { name: /^가상디바이스: 전체 3개,/ })).toBeVisible();
  for (const width of [320, 390, 768, 1150, 1280, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.locator(".resource-status-card").evaluateAll(cards => cards.every(card => {
      const heading = card.querySelector("h2")!.getBoundingClientRect();
      const total = card.querySelector(".resource-status-number")!.getBoundingClientRect();
      const legend = card.querySelector(".resource-status-legend")!.getBoundingClientRect();
      const bounds = card.getBoundingClientRect();
      return bounds.height <= 260 && heading.width >= 110 && heading.bottom <= total.top && total.left >= bounds.left && total.right <= bounds.right &&
        legend.left >= bounds.left && legend.right <= bounds.right && (legend.top >= total.bottom || legend.left >= total.right);
    })), `No overlapping status contents at ${width}px`).toBe(true);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `No page overflow at ${width}px`).toBe(true);
  }
});

test("failed virtual inventory is unknown while a successfully empty inventory is zero", async ({ page }) => {
  await overview(page, { listFailure: true });
  await expect(page.getByRole("group", { name: "가상디바이스: 상태를 확인할 수 없습니다", exact: true })).toBeVisible();
  await page.unroute("**/api/control-plane/**");
  await overview(page, { empty: true });
  await expect(page.getByRole("group", { name: /^가상디바이스: 전체 0개,/ })).toBeVisible();
  await expect(page.getByRole("group", { name: /^센서: 전체 0개,/ })).toBeVisible();
});

test("GPU inventory without usage does not report collection as fully healthy", async ({ page }) => {
  await overview(page);
  let hasUsage = false;
  await page.route("**/api/control-plane/node-metrics", route => route.fulfill({ json: {
    status: "AVAILABLE", fetchedAt: new Date().toISOString(), maxAgeSeconds: 90,
    items: [{ nodeName: "server-a", accelerators: [{ device: "pci:0000:01:00.0", kind: "GPU", vendor: "NVIDIA", model: "NVIDIA GPU", fresh: true, observedAt: new Date().toISOString() }],
      measurements: [{ key: "CPU_USAGE", device: "", value: 10, unit: "PERCENT", fresh: true, observedAt: new Date().toISOString() }, ...(hasUsage ? [{ key: "GPU_USAGE", device: "pci:0000:01:00.0", value: 0, unit: "PERCENT", fresh: true, observedAt: new Date().toISOString() }] : [])] }],
  } }));
  await page.reload();
  const row = page.getByRole("row").filter({ has: page.getByRole("rowheader", { name: "server-a" }) });
  await expect(row).toContainText("NVIDIA GPU");
  await expect(row).toContainText("사용률 미수집");
  await expect(row).toContainText("일부 미수집");
  await expect(row).not.toContainText("수집 정상");
  hasUsage = true;
  await page.getByRole("button", { name: "새로고침", exact: true }).click();
  await expect(row.locator('[data-label="GPU / NPU"]')).toContainText("0.0%");
  await expect(row).toContainText("수집 정상");
  await expect(row).not.toContainText("사용률 미수집");
});
