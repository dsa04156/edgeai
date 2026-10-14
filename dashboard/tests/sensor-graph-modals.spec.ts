import { test, expect, type Page } from "@playwright/test";

const device = { id: "temperature-sensor", uid: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", targetNode: "edge-a", state: "ONLINE", profile: { name: "temperature-model", protocol: "mqtt", sourceName: "environment", properties: [{ name: "temperature", dataType: "float64", unit: "°C", scale: 1, offset: 0 }] }, properties: { temperature: { value: "21.5", timestamp: Date.now() } } };


function chartFixture(query: URLSearchParams, count = 2, uid = device.uid) {
  const from = query.get("from")!, to = query.get("to")!, start = Date.parse(from), duration = Date.parse(to) - start;
  return { deviceUid: uid, property: "temperature", from, to, retentionDays: 30, aggregation: { bucketSeconds: 1, method: "MEAN_MIN_MAX" }, truncated: false, items: Array.from({ length: count }, (_, index) => ({ from: new Date(start + duration * (index + 1) / (count + 1)).toISOString(), to: new Date(start + duration * (index + 1) / (count + 1) + 1000).toISOString(), average: 21.5 + index, min: 21.5 + index, max: 21.5 + index, count: 1 })) };
}

async function catalog(page: Page) {
  await page.route("**/api/health", route => route.fulfill({ json: { status: "UP" } }));
  await page.route("**/api/control-plane/**", route => {
    const path = new URL(route.request().url()).pathname.replace("/api/control-plane/", "");
    if (path === "infrastructure") return route.fulfill({ json: { fetchedAt: new Date().toISOString(), nodesStatus: "AVAILABLE", sensors: [], nodes: [] } });
    if (path === "managed-devices") return route.fulfill({ json: { items: [device] } });
    if (path.endsWith("/temperature/chart")) return route.fulfill({ json: chartFixture(new URL(route.request().url()).searchParams, path.includes(device.uid) ? 2 : 1, path.split("/")[2]) });
    if (path === `data-store/sensors/${device.uid}/temperature/history`) {
      const query = new URL(route.request().url()).searchParams;
      return route.fulfill({ json: { deviceUid: device.uid, property: "temperature", from: query.get("from"), to: query.get("to"), retentionDays: 30, truncated: false, items: [21.5, 22.5].map((value, index) => ({ deviceUid: device.uid, deviceId: device.id, property: "temperature", unit: "°C", type: "float64", quality: "UNVERIFIED", observedAt: new Date(Date.now() - 60000 + index * 1000).toISOString(), value })) } });
    }
    if (path === "csrf") return route.fulfill({ json: { token: "fixture" } });
    return route.fulfill({ json: { items: [], nextOffset: null } });
  });
}

test("clicking a sensor opens its stored original graph in a bounded dialog and restores focus", async ({ page }, info) => {
  await catalog(page); await page.goto("/devices?view=sensors");
  const trigger = page.getByRole("button", { name: "temperature-sensor temperature 그래프", exact: true });
  await expect(trigger).toBeVisible(); await trigger.click();
  const dialog = page.getByRole("dialog", { name: "temperature · 센서 그래프", exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("img", { name: "temperature 기간 전체 그래프, 원본 2개", exact: true })).toBeVisible();
  await dialog.getByText("센서·수집 정보", { exact: true }).click();
  await expect(dialog.getByText(/InfluxDB에 저장된 센서 원본/)).toBeVisible();
  await expect(dialog.getByText(/이전 Mapper의 기록/)).toBeVisible();
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

test("stored sensor records remain available after closing and reloading the page", async ({ page }) => {
  await catalog(page);
  await page.goto("/devices?view=sensors");
  const trigger = page.getByRole("button", { name: "temperature-sensor temperature 그래프", exact: true }); await trigger.click();
  const dialog = page.getByRole("dialog", { name: "temperature · 센서 그래프", exact: true });
  await expect(dialog.getByRole("img", { name: "temperature 기간 전체 그래프, 원본 2개", exact: true })).toBeVisible();
  await page.keyboard.press("Escape"); await trigger.click();
  await expect(dialog.getByRole("img", { name: "temperature 기간 전체 그래프, 원본 2개", exact: true })).toBeVisible();
  await page.reload(); await trigger.click();
  await expect(dialog.getByRole("img", { name: "temperature 기간 전체 그래프, 원본 2개", exact: true })).toBeVisible();
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

test("sensor history periods, pagination, store failures and empty records stay within the modal", async ({ page }) => {
  await catalog(page); let fail = false, empty = false;
  const queries: URLSearchParams[] = [];
  await page.route("**/api/control-plane/data-store/sensors/**/chart?**", route => route.fulfill({ json: chartFixture(new URL(route.request().url()).searchParams, empty ? 0 : 1) }));
  await page.route("**/api/control-plane/data-store/sensors/**/history?**", route => {
    const query = new URL(route.request().url()).searchParams; queries.push(query);
    if (fail) return route.fulfill({ status: 503, json: { message: "InfluxDB 저장소를 확인하세요." } });
    return route.fulfill({ json: { deviceUid: device.uid, property: "temperature", from: query.get("from"), to: query.get("to"), retentionDays: 30, truncated: !empty && !query.has("cursor"), nextCursor: empty || query.has("cursor") ? undefined : "cursor-a", items: empty ? [] : [{ deviceUid: device.uid, property: "temperature", unit: "°C", type: "float64", quality: "QUALIFIED", observedAt: new Date().toISOString(), value: query.has("cursor") ? 22.5 : 21.5 }] } });
  });
  await page.goto("/devices?view=sensors"); await page.getByRole("button", { name: "temperature-sensor temperature 그래프", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "temperature · 센서 그래프", exact: true });
  await expect(dialog.getByRole("img", { name: "temperature 기간 전체 그래프, 원본 1개", exact: true })).toBeVisible();
  expect(Date.parse(queries[0].get("to")!) - Date.parse(queries[0].get("from")!)).toBe(300000);
  await dialog.getByRole("checkbox", { name: "자동 갱신 · 5초", exact: true }).uncheck();
  await dialog.getByLabel("조회 기간", { exact: true }).selectOption("60");
  await expect.poll(() => Date.parse(queries.at(-1)!.get("to")!) - Date.parse(queries.at(-1)!.get("from")!)).toBe(3600000);
  await expect(dialog.getByText("원본 이력 조회 중…", { exact: true })).not.toBeVisible();
  const period = queries.at(-1)!; expect(Date.parse(period.get("to")!) - Date.parse(period.get("from")!)).toBe(3600000);
  await dialog.getByRole("button", { name: "이전 기록", exact: true }).click();
  await expect(dialog.getByRole("img", { name: "temperature 기간 전체 그래프, 원본 1개", exact: true })).toBeVisible();
  expect(queries.at(-1)!.get("cursor")).toBe("cursor-a"); expect(queries.at(-1)!.get("from")).toBe(period.get("from")); expect(queries.at(-1)!.get("to")).toBe(period.get("to"));
  await dialog.getByText("원본 기록 표 · 2개", { exact: true }).click();
  await expect(dialog.getByRole("table").getByRole("row")).toHaveCount(3);
  fail = true; await dialog.getByRole("button", { name: "그래프 조회", exact: true }).click();
  await expect(dialog.getByRole("alert")).toContainText("InfluxDB 저장소"); await expect(dialog.getByRole("img")).toHaveCount(0);
  fail = false; empty = true; await dialog.getByRole("button", { name: "그래프 조회", exact: true }).click();
  await expect(dialog.getByText("이 기간에 저장된 센서 원본이 없습니다.", { exact: true })).toBeVisible();
  await expect(dialog.getByRole("alert")).toHaveCount(0);
});

test("a recreated sensor name keeps the open graph on its original UID", async ({ page }) => {
  await page.clock.install({ time: new Date() }); await catalog(page);
  const replacement = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"; let replaced = false;
  await page.route("**/api/control-plane/managed-devices", route => route.fulfill({ json: { items: [{ ...device, uid: replaced ? replacement : device.uid }] } }));
  await page.route(`**/api/control-plane/data-store/sensors/${replacement}/temperature/history?**`, route => {
    const query = new URL(route.request().url()).searchParams;
    return route.fulfill({ json: { deviceUid: replacement, property: "temperature", from: query.get("from"), to: query.get("to"), retentionDays: 30, truncated: false, items: [{ deviceUid: replacement, property: "temperature", unit: "°C", type: "float64", quality: "QUALIFIED", observedAt: new Date().toISOString(), value: 99 }] } });
  });
  await page.goto("/devices?view=sensors"); const trigger = page.getByRole("button", { name: "temperature-sensor temperature 그래프", exact: true }); await trigger.click();
  const dialog = page.getByRole("dialog", { name: "temperature · 센서 그래프", exact: true });
  await expect(dialog.getByRole("img", { name: "temperature 기간 전체 그래프, 원본 2개", exact: true })).toBeVisible();
  replaced = true; await page.clock.runFor(5100);
  await expect(dialog.getByText(/현재 등록에서 이 센서 UID를 찾지 못했습니다/)).toBeVisible();
  await expect(dialog.getByRole("img", { name: "temperature 기간 전체 그래프, 원본 2개", exact: true })).toBeVisible();
  await page.keyboard.press("Escape"); await trigger.click();
  await expect(dialog.getByRole("img", { name: "temperature 기간 전체 그래프, 원본 1개", exact: true })).toBeVisible();
});

test("automatic sensor history refresh retains the open record table and recovers from a temporary outage", async ({ page }) => {
  await page.clock.install({ time: new Date() }); await catalog(page); let failed = false, count = 2;
  await page.route("**/api/control-plane/data-store/sensors/**/chart?**", route => route.fulfill({ json: chartFixture(new URL(route.request().url()).searchParams, count) }));
  await page.route("**/api/control-plane/data-store/sensors/**/history?**", route => {
    if (failed) return route.fulfill({ status: 503, json: { message: "일시 저장소 오류" } });
    const query = new URL(route.request().url()).searchParams;
    return route.fulfill({ json: { deviceUid: device.uid, property: "temperature", from: query.get("from"), to: query.get("to"), retentionDays: 30, truncated: false, items: Array.from({ length: count }, (_, index) => ({ deviceUid: device.uid, property: "temperature", unit: "°C", type: "float64", quality: "QUALIFIED", observedAt: new Date(Date.now() - 60000 + index * 1000).toISOString(), value: 21.5 + index })) } });
  });
  await page.goto("/devices?view=sensors"); await page.getByRole("button", { name: "temperature-sensor temperature 그래프", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "temperature · 센서 그래프", exact: true });
  await expect(dialog.getByRole("img", { name: "temperature 기간 전체 그래프, 원본 2개", exact: true })).toBeVisible();
  await dialog.getByText("원본 기록 표 · 2개", { exact: true }).click();
  failed = true; await page.clock.runFor(5100);
  await expect(dialog.getByRole("alert")).toContainText("일시 저장소 오류");
  await expect(dialog.getByRole("img", { name: "temperature 기간 전체 그래프, 원본 2개", exact: true })).toBeVisible();
  await expect(dialog.getByRole("table")).toBeVisible();
  failed = false; count = 3; await page.clock.runFor(5100);
  await expect(dialog.getByRole("img", { name: "temperature 기간 전체 그래프, 원본 3개", exact: true })).toBeVisible();
  await expect(dialog.getByRole("alert")).toHaveCount(0); await expect(dialog.getByRole("table")).toBeVisible();
});
