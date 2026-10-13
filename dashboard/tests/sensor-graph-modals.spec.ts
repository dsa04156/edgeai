import { test, expect, type Page } from "@playwright/test";

const device = { id: "temperature-sensor", targetNode: "edge-a", state: "ONLINE", profile: { name: "temperature-model", protocol: "mqtt", sourceName: "environment", properties: [{ name: "temperature", dataType: "float64", unit: "°C", scale: 1, offset: 0 }] }, properties: { temperature: { value: "21.5", timestamp: Date.now() } } };

async function catalog(page: Page) {
  await page.route("**/api/health", route => route.fulfill({ json: { status: "UP" } }));
  await page.route("**/api/control-plane/**", route => {
    const path = new URL(route.request().url()).pathname.replace("/api/control-plane/", "");
    if (path === "infrastructure") return route.fulfill({ json: { fetchedAt: new Date().toISOString(), nodesStatus: "AVAILABLE", sensors: [], nodes: [] } });
    if (path === "managed-devices") return route.fulfill({ json: { items: [device] } });
    if (path === "csrf") return route.fulfill({ json: { token: "fixture" } });
    return route.fulfill({ json: { items: [], nextOffset: null } });
  });
}

test("clicking a sensor opens its live graph in a bounded dialog and restores focus", async ({ page }, info) => {
  await catalog(page); await page.goto("/devices?view=sensors");
  const trigger = page.getByRole("button", { name: "temperature-sensor temperature 그래프", exact: true });
  await expect(trigger).toBeVisible(); await trigger.click();
  const dialog = page.getByRole("dialog", { name: "temperature · 센서 그래프", exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("img", { name: "temperature 실시간 그래프, 1개 관측", exact: true })).toBeVisible();
  await expect(dialog.getByText(/화면을 연 뒤 수신한 최신값/)).toBeVisible();
  await expect(dialog.getByText(/서버에 저장된 센서 이력이 아닙니다/)).toBeVisible();
  expect(await page.evaluate(() => getComputedStyle(document.body).overflow)).toBe("hidden");
  await page.screenshot({ path: info.outputPath("sensor-graph-modal.png"), fullPage: true });
  const box = await dialog.boundingBox(); expect(box!.x).toBeGreaterThanOrEqual(0); expect(box!.y).toBeGreaterThanOrEqual(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.keyboard.press("Escape");
  await expect(dialog).not.toBeVisible(); await expect(trigger).toBeFocused();
  expect(await page.evaluate(() => getComputedStyle(document.body).overflow)).not.toBe("hidden");
});

test("clicking a virtual device opens its saved output graph and switches outputs in the same dialog", async ({ page }, info) => {
  await catalog(page);
  const vd = { id: "11111111-1111-4111-8111-111111111111", key: "environment-summary", displayName: "환경 요약", revision: 1, state: "REGISTERED", executionMode: "CONTINUOUS_EKUIPER" };
  await page.route("**/api/control-plane/virtual-devices**", route => {
    const url = new URL(route.request().url()), path = url.pathname;
    if (path.endsWith("/continuous-catalog")) return route.fulfill({ json: { enabled: true, inputs: [] } });
    if (path.endsWith("/virtual-devices")) return route.fulfill({ json: { items: [vd], nextOffset: null } });
    if (path.endsWith("/execution")) return route.fulfill({ json: { continuous: { enabled: true, desiredRunning: true, state: "RUNNING", reason: "HEALTHY" } } });
    if (path.endsWith("/outputs")) return route.fulfill({ json: { intervalSeconds: 1, retentionDays: 7, historyEnabled: true, items: ["average", "maximum"].map((name, index) => ({ outputId: name, name, expression: "a", unit: "°C", type: "number", quality: "VALID", latest: { value: index ? 24 : 22, evaluationAt: new Date().toISOString(), historyStored: true } })) } });
    if (path.endsWith("/history")) {
      const from = url.searchParams.get("from")!, to = url.searchParams.get("to")!, start = Date.parse(from), outputId = path.includes("/maximum/") ? "maximum" : "average";
      return route.fulfill({ json: { outputId, from, to, items: [{ evaluationAt: new Date(start + 10000).toISOString(), value: 21, generation: "g1" }, { evaluationAt: new Date(start + 11000).toISOString(), value: 22, generation: "g1" }], gaps: [{ from: new Date(start + 12000).toISOString(), to: new Date(start + 15000).toISOString(), reason: "HISTORY_UNAVAILABLE" }], truncated: false } });
    }
    return route.fulfill({ json: { vd, activeSources: [], sourceHistory: [] } });
  });
  await page.goto("/virtual-devices");
  const trigger = page.getByRole("button", { name: /^환경 요약\s*environment-summary$/ });
  await trigger.click();
  const dialog = page.getByRole("dialog", { name: "환경 요약 · 가상 장치 그래프", exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("img", { name: "average 결과 그래프, 2개 기록", exact: true })).toBeVisible();
  await expect(dialog.getByText(/기록 저장 중단 ·/)).toBeVisible();
  await expect(dialog.getByRole("button", { name: "계산 시작", exact: true })).toHaveCount(0);
  await dialog.getByRole("button", { name: "maximum 그래프", exact: true }).click();
  await expect(dialog.getByRole("img", { name: "maximum 결과 그래프, 2개 기록", exact: true })).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(1);
  await page.screenshot({ path: info.outputPath("virtual-device-graph-modal.png"), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await dialog.getByRole("button", { name: "닫기", exact: true }).click();
  await expect(dialog).not.toBeVisible(); await expect(trigger).toBeFocused();
  await page.getByRole("button", { name: "환경 요약 설정", exact: true }).click();
  await expect(page.getByRole("heading", { name: "현재 원본 연결", exact: true })).toBeVisible();
});

test("live sensor observations deduplicate, preserve interruptions and survive closing the dialog", async ({ page }) => {
  const now = Date.now(); await page.clock.install({ time: new Date(now) }); await catalog(page);
  let failed = false, at = now, value = "21.5";
  await page.route("**/api/control-plane/managed-devices", route => failed
    ? route.fulfill({ status: 503, json: { message: "센서 수신 중단" } })
    : route.fulfill({ json: { items: [{ ...device, properties: { temperature: { value, timestamp: at } } }] } }));
  await page.goto("/devices?view=sensors");
  const trigger = page.getByRole("button", { name: "temperature-sensor temperature 그래프", exact: true }); await trigger.click();
  const dialog = page.getByRole("dialog", { name: "temperature · 센서 그래프", exact: true });
  await expect(dialog.getByRole("img", { name: "temperature 실시간 그래프, 1개 관측", exact: true })).toBeVisible();
  await page.clock.runFor(5100);
  await expect(dialog.getByRole("img", { name: "temperature 실시간 그래프, 1개 관측", exact: true })).toBeVisible();
  at = now + 6000; value = "22.5"; await page.clock.runFor(5100);
  await expect(dialog.getByRole("img", { name: "temperature 실시간 그래프, 2개 관측", exact: true })).toBeVisible();
  failed = true; await page.clock.runFor(5100);
  await expect(dialog.getByRole("alert")).toContainText("마지막 관측");
  await expect(dialog.getByRole("img", { name: "temperature 실시간 그래프, 2개 관측", exact: true })).toBeVisible();
  failed = false; at = now + 16000; value = "23.5"; await page.clock.runFor(5100);
  const graph = dialog.getByRole("img", { name: "temperature 실시간 그래프, 3개 관측", exact: true });
  await expect(graph).toBeVisible(); await expect(dialog.getByRole("alert")).toHaveCount(0);
  // A visible line joins the first two readings; the recovered reading is a separate dot.
  await expect(graph.locator("polyline")).toHaveCount(1);
  await expect(graph.locator("polyline")).toHaveAttribute("points", /^[^ ]+ [^ ]+$/);
  await page.keyboard.press("Escape"); await trigger.click();
  await expect(dialog.getByRole("img", { name: "temperature 실시간 그래프, 3개 관측", exact: true })).toBeVisible();
});

test("inactive virtual processing and unavailable history are explained inside the graph dialog", async ({ page }) => {
  await catalog(page);
  const vd = { id: "inactive-vd", key: "inactive", displayName: "중지된 계산", revision: 1, state: "REGISTERED", executionMode: "CONTINUOUS_EKUIPER" };
  await page.route("**/api/control-plane/virtual-devices**", route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/continuous-catalog")) return route.fulfill({ json: { enabled: false, inputs: [] } });
    if (path.endsWith("/virtual-devices")) return route.fulfill({ json: { items: [vd], nextOffset: null } });
    if (path.endsWith("/execution")) return route.fulfill({ json: { continuous: { enabled: false, desiredRunning: false, state: "STOPPED", reason: "STOPPED" } } });
    if (path.endsWith("/outputs")) return route.fulfill({ json: { intervalSeconds: 1, retentionDays: 7, historyEnabled: false, items: [{ outputId: "average", name: "average", expression: "a", unit: "°C", type: "number", quality: "STALE", latest: { value: 22, evaluationAt: new Date().toISOString(), historyStored: false } }] } });
    throw new Error(`Unexpected request: ${path}`);
  });
  await page.goto("/virtual-devices"); await page.getByRole("button", { name: /^중지된 계산\s*inactive$/ }).click();
  const dialog = page.getByRole("dialog", { name: "중지된 계산 · 가상 장치 그래프", exact: true });
  await expect(dialog.getByText(/연속 처리가 비활성 상태/)).toBeVisible();
  await expect(dialog.getByText(/기록 저장소가 연결되지 않았습니다/)).toBeVisible();
  await expect(dialog.getByRole("cell").filter({ hasText: "마지막 값" })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "average 그래프", exact: true })).toBeDisabled();
  await expect(dialog.getByRole("img")).toHaveCount(0);
});
