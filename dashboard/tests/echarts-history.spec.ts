import { test, expect, type Page } from "@playwright/test";

const uid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
async function sensor(page: Page, values = [20, 21, 22]) {
  await page.route("**/api/health", route => route.fulfill({ json: { status: "UP" } }));
  await page.route("**/api/control-plane/**", route => {
    const url = new URL(route.request().url()), path = url.pathname.replace("/api/control-plane/", "");
    if (path === "managed-devices") return route.fulfill({ json: { items: [{ id: "temperature-sensor", uid, targetNode: "edge-a", state: "ONLINE", profile: { name: "temperature-model", protocol: "mqtt", properties: [{ name: "temperature", dataType: "float64", unit: "°C", scale: 1, offset: 0 }] }, properties: { temperature: { value: "21", timestamp: Date.now() } } }] } });
    if (path === `data-store/sensors/${uid}/temperature/chart`) {
      const from = url.searchParams.get("from")!, to = url.searchParams.get("to")!, start = Date.parse(from), duration = Date.parse(to) - start;
      return route.fulfill({ json: { deviceUid: uid, property: "temperature", from, to, retentionDays: 30, aggregation: { bucketSeconds: 1, method: "MEAN_MIN_MAX" }, truncated: false, items: [0.1, 0.5, 0.9].map((position, index) => ({ from: new Date(start + duration * position - 500).toISOString(), to: new Date(start + duration * position + 500).toISOString(), average: values[index], min: values[index], max: values[index], count: 1 })) } });
    }
    if (path === `data-store/sensors/${uid}/temperature/history`) {
      const from = url.searchParams.get("from")!, to = url.searchParams.get("to")!, start = Date.parse(from), duration = Date.parse(to) - start;
      return route.fulfill({ json: { deviceUid: uid, property: "temperature", from, to, retentionDays: 30, truncated: false, items: [0.1, 0.5, 0.9].map((position, index) => ({ deviceUid: uid, property: "temperature", unit: "°C", type: "float64", quality: "QUALIFIED", observedAt: new Date(start + duration * position).toISOString(), value: values[index] })) } });
    }
    return route.fulfill({ json: { items: [], sensors: [], nodes: [], nextOffset: null } });
  });
  await page.goto("/devices?view=sensors");
  await page.getByRole("button", { name: "temperature-sensor temperature 그래프", exact: true }).click();
  return page.getByRole("dialog", { name: "temperature · 센서 그래프", exact: true });
}

test("sensor history uses an interactive chart with value tooltips and keyboard zoom controls", async ({ page }, info) => {
  const errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
  const dialog = await sensor(page), chart = dialog.getByRole("img", { name: "temperature 기간 전체 그래프, 원본 3개", exact: true });
  await expect(chart.locator("canvas")).toBeVisible();
  const box = (await chart.boundingBox())!;
  await chart.hover({ position: { x: box.width / 2, y: 100 } });
  await expect(dialog.getByRole("tooltip")).toContainText("21 °C");
  const body = (await dialog.locator(".registration-dialog-body").boundingBox())!;
  await expect.poll(async () => { const tip = (await dialog.getByRole("tooltip").boundingBox())!; return tip.y + tip.height; }).toBeLessThanOrEqual(body.y + body.height);
  await dialog.getByRole("button", { name: "확대", exact: true }).click();
  await expect(dialog.getByRole("status", { name: "그래프 표시 구간", exact: true })).toContainText("50%");
  await chart.hover({ position: { x: 98, y: 100 } });
  await expect(dialog.getByRole("tooltip")).toContainText("21 °C");
  await dialog.getByRole("button", { name: "전체 구간", exact: true }).click();
  await expect(dialog.getByRole("status", { name: "그래프 표시 구간", exact: true })).toContainText("100%");
  await chart.hover({ position: { x: 98, y: 100 } });
  await expect(dialog.getByRole("tooltip")).toContainText("20 °C");
  await expect(chart.locator("canvas")).toHaveCount(1);
  await page.screenshot({ path: info.outputPath("echarts-sensor.png"), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "temperature-sensor temperature 그래프", exact: true }).click();
  await expect(dialog.getByRole("img").locator("canvas")).toHaveCount(1);
  expect(errors).toEqual([]);
});

test("tooltips preserve the stored measurement precision", async ({ page }) => {
  const dialog = await sensor(page, [20, 20.00000001, 22]);
  const chart = dialog.getByRole("img");
  await expect(chart.locator("canvas")).toBeVisible();
  await chart.hover({ position: { x: (await chart.boundingBox())!.width / 2, y: 100 } });
  await expect(dialog.getByRole("tooltip")).toContainText("20.00000001 °C");
  await dialog.getByText("원본 기록 표 · 3개", { exact: true }).click();
  await expect(dialog.getByRole("cell", { name: "20.00000001", exact: true })).toBeVisible();
});

test("automatic history updates keep the zoomed time range and expanded record table", async ({ page }) => {
  await page.clock.install({ time: new Date() });
  const dialog = await sensor(page), chart = dialog.getByRole("img", { name: "temperature 기간 전체 그래프, 원본 3개", exact: true });
  await expect(chart.locator("canvas")).toBeVisible();
  await dialog.getByText("원본 기록 표 · 3개", { exact: true }).click();
  await dialog.getByRole("button", { name: "확대", exact: true }).click();
  await page.clock.runFor(5100);
  await expect(dialog.getByRole("status", { name: "그래프 표시 구간", exact: true })).toContainText("50%");
  await expect(dialog.getByRole("table")).toBeVisible();
  await chart.hover({ position: { x: 98, y: 100 } });
  await expect(dialog.getByRole("tooltip")).toContainText("21 °C");
});

test("virtual output charts keep storage gaps and generation boundaries while adding zoom", async ({ page }, info) => {
  const id = "11111111-1111-4111-8111-111111111111";
  await page.route("**/api/control-plane/**", route => {
    const url = new URL(route.request().url()), path = url.pathname;
    if (path.endsWith("/virtual-devices")) return route.fulfill({ json: { items: [{ id, key: "temperature", displayName: "온도 계산", state: "REGISTERED", executionMode: "CONTINUOUS_EKUIPER", revision: 1 }], nextOffset: null } });
    if (path.endsWith("/continuous-catalog")) return route.fulfill({ json: { enabled: true, inputs: [] } });
    if (path.endsWith("/execution")) return route.fulfill({ json: { continuous: { enabled: true, state: "RUNNING", reason: "HEALTHY", desiredRunning: true } } });
    if (path.endsWith("/outputs")) return route.fulfill({ json: { intervalSeconds: 1, retentionDays: 7, historyEnabled: true, items: [{ outputId: "average", name: "average", expression: "a", unit: "°C", type: "number", quality: "VALID" }] } });
    if (path.endsWith("/history")) {
      const from = url.searchParams.get("from")!, to = url.searchParams.get("to")!, start = Date.parse(from), duration = Date.parse(to) - start;
      return route.fulfill({ json: { outputId: "average", from, to, items: [0, 1, 2, 3].map(index => ({ evaluationAt: new Date(start + (index < 2 ? duration * .25 : duration * .75) + index % 2 * 1000).toISOString(), value: index === 0 ? 4.0001 : 20 + index, generation: index < 2 ? "first" : "second" })), gaps: [{ from: new Date(start + duration * .4).toISOString(), to: new Date(start + duration * .6).toISOString(), reason: "HISTORY_UNAVAILABLE" }], truncated: false } });
    }
    return route.fulfill({ json: { items: [], nextOffset: null } });
  });
  await page.goto("/virtual-devices"); await page.getByRole("button", { name: /^온도 계산\s*temperature$/ }).click();
  const dialog = page.getByRole("dialog", { name: "온도 계산 · 가상 장치 그래프", exact: true });
  await expect(dialog.getByRole("img", { name: "average 결과 그래프, 4개 기록", exact: true }).locator("canvas")).toBeVisible();
  await expect(dialog.getByText("연속 기록 구간 2개 · 기록 누락 1개", { exact: true })).toBeVisible();
  await expect(dialog.getByText(/기록 저장 중단 ·/)).toBeVisible();
  await dialog.getByRole("button", { name: "확대", exact: true }).click();
  await expect(dialog.getByRole("status", { name: "그래프 표시 구간", exact: true })).toContainText("50%");
  await dialog.getByText("기록 표 · 4개", { exact: true }).click();
  await expect(dialog.getByRole("table").last().getByRole("row")).toHaveCount(5);
  await expect(dialog.getByRole("cell", { name: "4.0001", exact: true })).toBeVisible();
  await page.screenshot({ path: info.outputPath("echarts-virtual.png"), fullPage: true });
});
