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
    if (path === "virtual-devices") return !detail && attempts >= 2 ? route.fulfill({ status: 503, json: { message: "목록 조회 실패" } }) : route.fulfill({ json: { items: detail ? [vd] : [], nextOffset: null } });
    if (path === "virtual-devices/continuous" && request.method() === "POST") {
      const body = request.postDataJSON();
      expect(body.sources).toEqual([{ sourceKey: "a", deviceId: "temperature", properties: ["temperature"], maxAgeSeconds: 30 }, { sourceKey: "b", deviceId: "humidity", properties: ["humidity"], maxAgeSeconds: 30 }]);
      expect(body.continuous.outputs.map((output: { expression: string }) => output.expression)).toEqual(["(a + b) / 2", "a > 30"]);
      expect(body.continuous.resultStorage).toEqual({ mode: "SELECTED", outputs: [{ name: "value", condition: "value >= 25" }] });
      expect(body).not.toHaveProperty("image"); expect(body).not.toHaveProperty("serviceProfileVersionId");
      if (++attempts === 1) return route.fulfill({ status: 503, json: { message: "등록 재시도" } });
      return route.fulfill({ status: 201, json: vd });
    }
    if (path === `virtual-devices/${id}`) return detail ? route.fulfill({ json: { vd, activeSources: [{ id: "source", sourceKey: "a", deviceId: "temperature", properties: ["temperature"], maxAgeSeconds: 30, sourceKind: "physical" }], sourceHistory: [] } }) : route.fulfill({ status: 503, json: { message: "상세 조회 실패" } });
    if (path === `virtual-devices/${id}/processing-versions`) return route.fulfill({ json: { nextOffset: null, items: [{ revision: 2, profileVersionId: profile, configuration, sources: [{ alias: "a", deviceId: "temperature" }], rules: [{ id: "rule-2", outputId: "average", sql: "SELECT AVG(x0) FROM edgeai_vd_frames", type: "number", unit: "°C", warmupTicks: 3, conditional: false }], recordedAt: new Date().toISOString() }, { revision: 1, profileVersionId: profile, configuration: { ...configuration, intervalSeconds: 5 }, sources: [{ alias: "a", deviceId: "temperature" }], rules: [{ id: "rule-1", outputId: "average", sql: "SELECT x0 FROM edgeai_vd_frames", type: "number", unit: "°C", warmupTicks: 1, conditional: false }], recordedAt: new Date().toISOString() }] } });
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
  await form.getByRole("button", { name: "여러 센서·직접 계산식 설정", exact: true }).click();
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
  await form.getByLabel("결과 저장 정책", { exact: true }).selectOption("SELECTED");
  await form.getByLabel("출력 1 저장", { exact: true }).check();
  await form.getByLabel("출력 1 저장 조건", { exact: true }).fill("value >= 25");
  await form.getByRole("button", { name: "연속 처리 장치 등록", exact: true }).click();
  await expect(form.getByRole("alert")).toHaveText("등록 재시도");
  await expect(form.getByLabel("출력 1 저장 조건", { exact: true })).toHaveValue("value >= 25");
  await expect(form.getByLabel("입력 2 센서", { exact: true })).toHaveValue("humidity");
  await expect(form.getByLabel("출력 1 계산식", { exact: true })).toHaveValue("(a + b) / 2");
  await form.getByRole("button", { name: "연속 처리 장치 등록", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "등록했습니다" })).toBeVisible();
  await expect(page.getByRole("alert").filter({ hasText: "등록은 완료됐지만" })).toBeVisible();
  expect(attempts()).toBe(2);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("quick registration creates a sensor average without typing keys or expressions", async ({ page }, info) => {
  await fixture(page);
  let submitted = false;
  await page.route("**/api/control-plane/virtual-devices/continuous", route => {
    const body = route.request().postDataJSON();
    expect(body.key).toMatch(/^vd-[a-f0-9]{32}$/);
    expect(body.displayName).toBe("온도 평균");
    expect(body.sources).toEqual([{ sourceKey: "a", deviceId: "temperature", properties: ["temperature"], maxAgeSeconds: 30 }]);
    expect(body.continuous).toEqual({ intervalSeconds: 1, retentionDays: 7, outputs: [{ name: "value", expression: "a", operation: "movingAverage", sampleCount: 5, unit: "°C" }] });
    submitted = true;
    return route.fulfill({ status: 201, json: vd });
  });
  await page.goto("/virtual-devices?create=1");
  const form = page.getByRole("form", { name: "연속 처리 장치 등록", exact: true });
  await expect(form.getByLabel("장치 키", { exact: true })).not.toBeVisible();
  await expect(form.getByLabel("입력 1 별칭", { exact: true })).not.toBeVisible();
  await form.getByLabel("센서", { exact: true }).selectOption("temperature");
  await form.getByLabel("계산 방식", { exact: true }).selectOption("movingAverage");
  await form.getByLabel("장치 이름", { exact: true }).fill("온도 평균");
  await page.screenshot({ path: info.outputPath("quick-registration.png"), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await form.getByRole("button", { name: "연속 처리 장치 등록", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "등록했습니다" })).toBeVisible();
  expect(submitted).toBe(true);
});

test("quick comparison preserves its draft and automatic key when registration is retried", async ({ page }) => {
  await fixture(page);
  const keys: string[] = [];
  await page.route("**/api/control-plane/virtual-devices/continuous", route => {
    const body = route.request().postDataJSON(); keys.push(body.key);
    expect(body.displayName).toBe("온도 경보");
    expect(body.continuous.outputs).toEqual([{ name: "value", expression: "a > 45.5", operation: "threshold", unit: "" }]);
    return keys.length === 1 ? route.fulfill({ status: 503, json: { message: "등록 재시도" } }) : route.fulfill({ status: 201, json: vd });
  });
  // getRandomValues also works on the LAN's HTTP origin, where randomUUID is absent.
  await page.addInitScript(() => Object.defineProperty(crypto, "randomUUID", { value: undefined }));
  await page.goto("/virtual-devices?create=1");
  const form = page.getByRole("form", { name: "연속 처리 장치 등록", exact: true });
  await form.getByLabel("센서", { exact: true }).selectOption("temperature");
  await form.getByLabel("계산 방식", { exact: true }).selectOption("threshold");
  await form.getByLabel("비교 조건", { exact: true }).selectOption(">");
  await form.getByLabel("기준값", { exact: true }).fill("45.5");
  await form.getByLabel("장치 이름", { exact: true }).fill("온도 경보");
  const submit = form.getByRole("button", { name: "연속 처리 장치 등록", exact: true });
  await submit.click();
  await expect(form.getByRole("alert")).toHaveText("등록 재시도");
  await expect(form.getByLabel("기준값", { exact: true })).toHaveValue("45.5");
  await form.getByRole("button", { name: "여러 센서·직접 계산식 설정", exact: true }).click();
  await expect(form.getByLabel("장치 이름", { exact: true })).toHaveValue("온도 경보");
  await expect(form.getByLabel("장치 키", { exact: true })).toHaveValue(keys[0]);
  await expect(form.getByLabel("출력 1 계산식", { exact: true })).toHaveValue("a > 45.5");
  await submit.click();
  await expect(page.getByRole("status").filter({ hasText: "등록했습니다" })).toBeVisible();
  expect(keys).toHaveLength(2); expect(keys[0]).toMatch(/^vd-[a-f0-9]{32}$/); expect(keys[1]).toBe(keys[0]);
});

test("quick averaging shows an invalid sample count and blocks registration", async ({ page }) => {
  await fixture(page);
  let submitted = false;
  await page.route("**/api/control-plane/virtual-devices/continuous", route => { submitted = true; return route.fulfill({ status: 400 }); });
  await page.goto("/virtual-devices?create=1");
  const form = page.getByRole("form", { name: "연속 처리 장치 등록", exact: true });
  await form.getByLabel("센서", { exact: true }).selectOption("temperature");
  await form.getByLabel("계산 방식", { exact: true }).selectOption("movingAverage");
  await form.getByLabel("장치 이름", { exact: true }).fill("온도 평균");
  await form.getByText("고급 설정 · 표본 수·주기·보관 기간", { exact: true }).click();
  const count = form.getByLabel("최근 표본 수", { exact: true });
  await count.fill("0");
  await expect(count).toHaveValue("0");
  expect(await count.evaluate((element: HTMLInputElement) => element.validity.rangeUnderflow)).toBe(true);
  await form.getByRole("button", { name: "연속 처리 장치 등록", exact: true }).click();
  expect(submitted).toBe(false);
});

test("settings open in a modal while start and stop stay in the list management cell", async ({ page }, info) => {
  await fixture(page, true); await page.goto("/virtual-devices");
  const list = page.getByRole("table", { name: "가상 장치 목록", exact: true });
  const management = list.getByRole("row").filter({ hasText: "온도 계산" }).getByRole("cell").last();
  await expect(management.getByRole("button", { name: "온도 계산 계산 시작", exact: true })).toBeDisabled();
  await expect(management.getByRole("button", { name: "온도 계산 계산 중지", exact: true })).toBeEnabled();
  await management.scrollIntoViewIfNeeded();
  expect(await list.evaluate(element => element.scrollWidth <= element.parentElement!.clientWidth)).toBe(true);
  await page.screenshot({ path: info.outputPath("management-list.png") });
  const trigger = management.getByRole("button", { name: "온도 계산 설정", exact: true });
  await trigger.click();
  const dialog = page.getByRole("dialog", { name: "온도 계산 · 가상 장치 설정", exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("heading", { name: "현재 원본 연결", exact: true })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "계산 시작", exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => getComputedStyle(document.body).overflow)).toBe("hidden");
  await page.screenshot({ path: info.outputPath("settings-modal.png") });
  await page.keyboard.press("Escape");
  await expect(dialog).not.toBeVisible(); await expect(trigger).toBeFocused();
  await expect(page.getByRole("heading", { name: "현재 원본 연결", exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("management confirms stop and start, retaining request identity after a failed stop", async ({ page }) => {
  await fixture(page, true);
  let running = true;
  const requests: { action: string; key: string }[] = [];
  await page.route(`**/api/control-plane/virtual-devices/${id}/execution`, route => route.fulfill({ json: { continuous: { enabled: true, desiredRunning: running, generation, state: running ? "RUNNING" : "STOPPED", reason: running ? "HEALTHY" : "STOPPED" } } }));
  for (const action of ["drain", "provision"]) await page.route(`**/api/control-plane/virtual-devices/${id}/${action}`, route => {
    expect(route.request().method()).toBe("POST");
    expect(route.request().postDataJSON()).toEqual({ revision: 1 });
    expect(route.request().headers()["x-csrf-token"]).toBe("test");
    requests.push({ action, key: route.request().headers()["idempotency-key"] });
    if (requests.length === 1) return route.fulfill({ status: 503, json: { message: "중지 요청 재시도" } });
    running = action === "provision";
    return route.fulfill({ status: 202, json: {} });
  });
  await page.goto("/virtual-devices");
  const management = page.getByRole("region", { name: "온도 계산 실행 관리", exact: true });
  await management.getByRole("button", { name: "온도 계산 계산 중지", exact: true }).click();
  expect(requests).toHaveLength(0);
  await management.getByRole("button", { name: "온도 계산 중지 확인", exact: true }).click();
  await expect(management.getByRole("status")).toHaveText("중지 요청 재시도");
  await management.getByRole("button", { name: "온도 계산 중지 확인", exact: true }).click();
  await expect(management.getByRole("button", { name: "온도 계산 계산 시작", exact: true })).toBeEnabled();
  await expect(management.getByRole("button", { name: "온도 계산 계산 중지", exact: true })).toBeDisabled();
  expect(requests[1].key).toBe(requests[0].key);
  await management.getByRole("button", { name: "온도 계산 계산 시작", exact: true }).click();
  await management.getByRole("button", { name: "온도 계산 시작 확인", exact: true }).click();
  await expect(management.getByRole("button", { name: "온도 계산 계산 중지", exact: true })).toBeEnabled();
  expect(requests.map(request => request.action)).toEqual(["drain", "drain", "provision"]);
  expect(requests[2].key).not.toBe(requests[0].key);
});

test("a failed execution read disables even a pending management confirmation", async ({ page }) => {
  await fixture(page, true); await page.clock.install(); let fail = false;
  await page.route(`**/api/control-plane/virtual-devices/${id}/execution`, route => fail ? route.fulfill({ status: 503, json: { message: "실행 상태 조회 실패" } }) : route.fulfill({ json: { continuous: { enabled: true, desiredRunning: true, generation, state: "RUNNING", reason: "HEALTHY" } } }));
  await page.goto("/virtual-devices");
  const management = page.getByRole("region", { name: "온도 계산 실행 관리", exact: true });
  await management.getByRole("button", { name: "온도 계산 계산 중지", exact: true }).click();
  const confirm = management.getByRole("button", { name: "온도 계산 중지 확인", exact: true });
  await expect(confirm).toBeEnabled(); fail = true;
  await page.clock.fastForward(11000);
  await expect(management.getByRole("alert")).toHaveText("실행 상태 조회 실패");
  await expect(confirm).toBeDisabled();
});

test("one VD displays three separate output rows and a chart with an explicit storage gap", async ({ page }) => {
  await fixture(page, true);
  await page.goto("/virtual-devices");
  await page.getByRole("button", { name: "온도 계산 설정", exact: true }).click();
  await page.getByText("계산 결과·처리 버전", { exact: true }).click();
  const results = page.getByRole("region", { name: "연속 처리 결과", exact: true });
  await expect(results.getByRole("table").getByRole("row")).toHaveCount(4);
  await expect(results.getByText("기록 저장 누락", { exact: true })).toBeVisible();
  await expect(results.getByRole("button", { name: "계산 시작", exact: true })).toHaveCount(0);
  await expect(results.getByRole("button", { name: "계산 중지", exact: true })).toHaveCount(0);
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
  await page.goto("/virtual-devices");
  const flow = page.getByRole("region", { name: "연속 처리 흐름", exact: true });
  await expect(flow).toBeVisible();
  await expect(flow.getByText("연속 처리 비활성", { exact: true })).toBeVisible();
  await expect(flow.getByRole("list", { name: "센서부터 결과까지" })).not.toBeVisible();
  await flow.locator("summary").click();
  await expect(flow.getByRole("list", { name: "센서부터 결과까지" }).getByRole("listitem")).toHaveCount(4);
  await expect(flow.getByText("Jetson 센서 → Mapper → NanoMQ", { exact: true })).toBeVisible();
  await expect(flow.getByText("Pi 센서 → Mapper → NanoMQ", { exact: true })).toBeVisible();
  await expect(flow.getByText("처리 agent ↔ eKuiper", { exact: true })).toBeVisible();
  await expect(flow.getByText(/결과는 항목마다 별도 행과 그래프로/)).toBeVisible();
  await expect(flow.getByText(/계산 중|연결 완료/)).toHaveCount(0);
  await page.getByRole("button", { name: "가상 장치 등록", exact: true }).click();
  await expect(page.getByRole("form", { name: "연속 처리 장치 등록", exact: true }).getByRole("button", { name: "연속 처리 장치 등록", exact: true })).toBeDisabled();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("an unavailable catalog stays unknown and retired batch registration stays absent", async ({ page }) => {
  await fixture(page, false, "unavailable");
  await page.goto("/virtual-devices");
  const flow = page.getByRole("region", { name: "연속 처리 흐름", exact: true });
  await expect(flow.getByText("연속 처리 상태 확인 필요", { exact: true })).toBeVisible();
  await expect(flow.getByText("연속 처리 비활성", { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "가상 장치 등록", exact: true }).click();
  await expect(page.getByRole("form", { name: "연속 처리 장치 등록", exact: true }).getByRole("button", { name: "연속 처리 장치 등록", exact: true })).toBeDisabled();
  await expect(page.getByRole("form", { name: "센서 처리 장치 등록", exact: true })).toHaveCount(0);
  await expect(page.getByText("기존 표본·FFT 처리 장치 등록", { exact: true })).toHaveCount(0);
  await page.screenshot({ path: `../output/continuous-vd/web-flow-${test.info().project.name}.png`, fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("processing versions expose the previous definition and generated SQL", async ({ page }) => {
  await fixture(page, true); await page.goto("/virtual-devices");
  await page.getByRole("button", { name: "온도 계산 설정", exact: true }).click();
  await page.getByText("계산 결과·처리 버전", { exact: true }).click();
  await page.getByRole("button", { name: "처리 정의·버전 조회", exact: true }).click();
  const versions = page.getByRole("region", { name: "처리 정의 버전", exact: true });
  await expect(versions.getByText("revision 2", { exact: true })).toBeVisible();
  await expect(versions.getByText("revision 1", { exact: true })).toBeVisible();
  await versions.getByText("revision 1", { exact: true }).click();
  await expect(versions.getByText("SELECT x0 FROM edgeai_vd_frames", { exact: true })).toBeVisible();
});
