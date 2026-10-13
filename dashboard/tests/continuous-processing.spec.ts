import { test, expect, type Page } from "@playwright/test";

const id = "11111111-1111-4111-8111-111111111111";
const generation = "33333333-3333-4333-8333-333333333333";
const profile = "22222222-2222-4222-8222-222222222222";
const sensors = ["temperature", "humidity"].map((property, index) => ({ deviceId: property, deviceUid: `sensor-${index}`, deviceModel: `${property}-model`, namespace: "kubeedge", property, type: "float64", unit: index ? "%" : "°C", targetNode: "edge-a" }));
const configuration = { intervalSeconds: 1, retentionDays: 7, outputs: [
  { name: "average", operation: "movingAverage", expression: "a", sampleCount: 3, unit: "°C" },
  { name: "maximum", operation: "maximum", expression: "a", sampleCount: 3, unit: "°C" },
  { name: "alarm", operation: "threshold", expression: "a > 30", unit: "" },
] };
const vd = { id, key: "temperature-calculations", displayName: "온도 계산", revision: 1, state: "REGISTERED", executionMode: "CONTINUOUS_EKUIPER", profileVersionId: profile, serviceProfileVersionId: null, placement: { mode: "AUTO" } };

async function fixture(page: Page, detail = false, catalog: "enabled" | "disabled" | "unavailable" = "enabled") {
  let attempts = 0;
  await page.route("**/api/control-plane/**", async route => {
    const request = route.request(), path = new URL(request.url()).pathname.replace("/api/control-plane/", "");
    if (path === "csrf") return route.fulfill({ json: { token: "test" } });
    if (path === "virtual-devices/continuous-catalog") return catalog === "unavailable" ? route.fulfill({ status: 503, json: { message: "연속 처리 서버 확인 필요" } }) : route.fulfill({ json: { enabled: catalog === "enabled", inputs: sensors } });
    if (path === "virtual-devices/processing-catalog") return route.fulfill({ json: { ready: false, architectures: [], operations: [] } });
    if (path === "profiles/VD") return route.fulfill({ json: { items: [{ id: profile, key: "continuous", version: "1.0.0", kind: "VD", spec: { apiVersion: "edgeai.vd/v3", continuous: configuration } }], nextOffset: null } });
    if (path === "virtual-devices") return route.fulfill({ json: { items: detail ? [vd] : [], nextOffset: null } });
    if (path === "virtual-devices/continuous" && request.method() === "POST") {
      const body = request.postDataJSON();
      expect(body.sources).toEqual([{ sourceKey: "a", deviceId: "temperature", properties: ["temperature"], maxAgeSeconds: 30 }, { sourceKey: "b", deviceId: "humidity", properties: ["humidity"], maxAgeSeconds: 30 }]);
      expect(body.continuous.outputs.map((output: { expression: string }) => output.expression)).toEqual(["(a + b) / 2", "a > 30"]);
      expect(body).not.toHaveProperty("image"); expect(body).not.toHaveProperty("serviceProfileVersionId");
      if (++attempts === 1) return route.fulfill({ status: 503, json: { message: "등록 재시도" } });
      return route.fulfill({ status: 201, json: vd });
    }
    if (path === `virtual-devices/${id}`) return detail ? route.fulfill({ json: { vd, activeSources: [{ id: "source", sourceKey: "a", deviceId: "temperature", properties: ["temperature"], maxAgeSeconds: 30, sourceKind: "physical" }], sourceHistory: [] } }) : route.fulfill({ status: 503, json: { message: "상세 조회 실패" } });
    if (path === `virtual-devices/${id}/execution`) return route.fulfill({ json: { continuous: { enabled: true, desiredRunning: true, generation, state: "RUNNING", reason: "HEALTHY" } } });
    if (path === `virtual-devices/${id}/outputs`) return route.fulfill({ json: { intervalSeconds: 1, retentionDays: 7, historyEnabled: true, items: configuration.outputs.map((output, index) => ({ ...output, outputId: output.name, type: index === 2 ? "boolean" : "number", quality: "VALID", latest: { value: index === 2 ? true : 21.5, evaluationAt: new Date().toISOString(), historyStored: index !== 1 } })) } });
    if (path === `virtual-devices/${id}/outputs/average/history`) {
      const query = new URL(request.url()).searchParams, from = query.get("from")!, to = query.get("to")!;
      const start = Date.parse(from), end = Date.parse(to);
      return route.fulfill({ json: { outputId: "average", from, to, aggregation: { bucketSeconds: 4 }, items: [0.2, 0.3, 0.7, 0.8].map((position, index) => ({ evaluationAt: new Date(start + (end - start) * position).toISOString(), average: 20 + index, min: 19 + index, max: 21 + index, count: 4, generation })), gaps: [{ from: new Date(start + (end - start) * 0.4).toISOString(), to: new Date(start + (end - start) * 0.6).toISOString(), reason: "HISTORY_UNAVAILABLE" }], truncated: false } });
    }
    return route.fulfill({ json: { items: [], nextOffset: null } });
  });
  return () => attempts;
}

test("multiple sensors and outputs preserve a failed registration without requiring a SERVICE", async ({ page }) => {
  const attempts = await fixture(page);
  await page.goto("/virtual-devices?create=1");
  const form = page.getByRole("form", { name: "연속 처리 장치 등록", exact: true });
  await expect(form).toBeVisible();
  await form.getByLabel("장치 키", { exact: true }).fill(vd.key);
  await form.getByLabel("장치 이름", { exact: true }).fill(vd.displayName);
  await form.getByLabel("입력 1 센서", { exact: true }).selectOption("temperature");
  await form.getByRole("button", { name: "입력 센서 추가", exact: true }).click();
  await form.getByLabel("입력 2 별칭", { exact: true }).fill("b");
  await form.getByLabel("입력 2 센서", { exact: true }).selectOption("humidity");
  await form.getByLabel("출력 1 계산식", { exact: true }).fill("(a + b) / 2");
  await form.getByRole("button", { name: "출력 항목 추가", exact: true }).click();
  await form.getByLabel("출력 2 처리", { exact: true }).selectOption("threshold");
  await form.getByLabel("출력 2 계산식", { exact: true }).fill("a > 30");
  await form.getByRole("button", { name: "연속 처리 장치 등록", exact: true }).click();
  await expect(form.getByRole("alert")).toHaveText("등록 재시도");
  await expect(form.getByLabel("입력 2 센서", { exact: true })).toHaveValue("humidity");
  await expect(form.getByLabel("출력 1 계산식", { exact: true })).toHaveValue("(a + b) / 2");
  await form.getByRole("button", { name: "연속 처리 장치 등록", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "등록했습니다" })).toBeVisible();
  await expect(page.getByRole("alert").filter({ hasText: "등록은 완료됐지만" })).toBeVisible();
  expect(attempts()).toBe(2);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("one VD displays three separate output rows and a chart with an explicit storage gap", async ({ page }) => {
  await fixture(page, true);
  await page.goto("/virtual-devices");
  await page.getByRole("button", { name: /^온도 계산\s*temperature-calculations$/ }).click();
  const results = page.getByRole("region", { name: "연속 처리 결과", exact: true });
  await expect(results.getByRole("table").getByRole("row")).toHaveCount(4);
  await expect(results.getByText("기록 저장 누락", { exact: true })).toBeVisible();
  await expect(results.getByRole("button", { name: "계산 시작", exact: true })).toBeDisabled();
  await expect(results.getByRole("button", { name: "계산 중지", exact: true })).toBeEnabled();
  await results.getByRole("button", { name: "average 그래프", exact: true }).click();
  await expect(results.getByRole("img", { name: "average 결과 그래프, 4개 기록", exact: true })).toBeVisible();
  await expect(results.getByText(/기록 저장 중단 ·/)).toBeVisible();
  await results.getByText("기록 표 · 4개", { exact: true }).click();
  await expect(results.getByRole("table").last().getByRole("row")).toHaveCount(5);
  await page.screenshot({ path: `../output/continuous-vd/ui-${test.info().project.name}.png`, fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("sensor-to-central flow explains separate outputs without claiming an inactive engine is running", async ({ page }) => {
  await fixture(page, false, "disabled");
  await page.goto("/virtual-devices?create=1");
  const flow = page.getByRole("region", { name: "연속 처리 흐름", exact: true });
  await expect(flow).toBeVisible();
  await expect(flow.getByText("연속 처리 비활성", { exact: true })).toBeVisible();
  await expect(flow.getByRole("list", { name: "센서부터 결과까지" }).getByRole("listitem")).toHaveCount(4);
  await expect(flow.getByText("Jetson 센서 → Mapper → NanoMQ", { exact: true })).toBeVisible();
  await expect(flow.getByText("Pi 센서 → Mapper → NanoMQ", { exact: true })).toBeVisible();
  await expect(flow.getByText("처리 agent ↔ eKuiper", { exact: true })).toBeVisible();
  await expect(flow.getByText(/결과는 항목마다 별도 행과 그래프로/)).toBeVisible();
  await expect(flow.getByText(/계산 중|연결 완료/)).toHaveCount(0);
  await expect(page.getByRole("form", { name: "연속 처리 장치 등록", exact: true }).getByRole("button", { name: "연속 처리 장치 등록", exact: true })).toBeDisabled();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("an unavailable catalog stays unknown rather than inactive and preserves the legacy registration", async ({ page }) => {
  await fixture(page, false, "unavailable");
  await page.goto("/virtual-devices?create=1");
  const flow = page.getByRole("region", { name: "연속 처리 흐름", exact: true });
  await expect(flow.getByText("연속 처리 상태 확인 필요", { exact: true })).toBeVisible();
  await expect(flow.getByText("연속 처리 비활성", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("form", { name: "연속 처리 장치 등록", exact: true }).getByRole("button", { name: "연속 처리 장치 등록", exact: true })).toBeDisabled();
  await expect(page.getByRole("form", { name: "센서 처리 장치 등록", exact: true })).toBeVisible();
  await page.screenshot({ path: `../output/continuous-vd/web-flow-${test.info().project.name}.png`, fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
