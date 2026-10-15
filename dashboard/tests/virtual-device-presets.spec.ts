import { test, expect, type Page } from "@playwright/test";

const sensors = [
  { deviceId: "temperature-a", property: "temp_humidity", unit: "°C" },
  { deviceId: "temperature-b", property: "temp_pressure", unit: "°C" },
  { deviceId: "temperature-f", property: "temperature", unit: "°F" },
  { deviceId: "humidity", property: "humidity", unit: "%" },
  { deviceId: "bool-temperature", property: "temperature", unit: "°C", type: "bool" },
  ...["x", "y", "z"].map(axis => ({ deviceId: `gyro-${axis}`, property: `gyro_${axis}`, unit: "rad/s" })),
  ...["x", "y", "z"].map(axis => ({ deviceId: `acceleration-${axis}`, property: `acceleration_${axis}_raw`, unit: "raw" })),
].map(sensor => ({ deviceUid: `uid-${sensor.deviceId}`, deviceModel: "measurements", namespace: "kubeedge", type: "float64", targetNode: "edge-a", ...sensor }));

async function catalog(page: Page, inputs = sensors) {
  await page.route("**/api/control-plane/**", route => {
    const path = new URL(route.request().url()).pathname.replace("/api/control-plane/", "");
    if (path === "csrf") return route.fulfill({ json: { token: "test" } });
    if (path === "virtual-devices/continuous-catalog") return route.fulfill({ json: { enabled: true, inputs } });
    return route.fulfill({ json: { items: [], nextOffset: null } });
  });
}

test("combined temperature registers two selected inputs without writing an expression", async ({ page }, info) => {
  await catalog(page);
  let submitted: unknown;
  await page.route("**/api/control-plane/virtual-devices/continuous", route => {
    submitted = route.request().postDataJSON();
    return route.fulfill({ status: 201, json: { id: "11111111-1111-4111-8111-111111111111", displayName: "통합 온도", state: "REGISTERED" } });
  });
  await page.goto("/virtual-devices?create=1");
  const form = page.getByRole("form", { name: "연속 처리 장치 등록", exact: true });
  await form.getByLabel("조합 종류", { exact: true }).selectOption({ label: "통합 온도" });
  await form.getByLabel("온도 1 센서", { exact: true }).selectOption("temperature-a");
  await form.getByLabel("온도 2 센서", { exact: true }).selectOption("temperature-b");
  await expect(form.getByLabel("장치 이름", { exact: true })).toHaveValue("통합 온도");
  await expect(form.getByLabel("출력 1 계산식", { exact: true })).toHaveCount(0);
  await page.screenshot({ path: info.outputPath("temperature-preset.png") });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await form.getByRole("button", { name: "연속 처리 장치 등록", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "등록했습니다" })).toBeVisible();
  expect(submitted).toMatchObject({ displayName: "통합 온도", sources: [
    { sourceKey: "a", deviceId: "temperature-a", properties: ["temp_humidity"], maxAgeSeconds: 30 },
    { sourceKey: "b", deviceId: "temperature-b", properties: ["temp_pressure"], maxAgeSeconds: 30 },
  ], continuous: { intervalSeconds: 1, retentionDays: 7, outputs: [{ name: "value", operation: "scalar", expression: "(a + b) / 2", unit: "°C" }] } });
});

const combinations = [
  { label: "3축 회전 제곱합", slots: ["회전 X", "회전 Y", "회전 Z"], devices: ["gyro-x", "gyro-y", "gyro-z"], expression: "a * a + b * b + c * c", unit: "(rad/s)²" },
  { label: "3축 가속도 제곱합", slots: ["가속도 X", "가속도 Y", "가속도 Z"], devices: ["acceleration-x", "acceleration-y", "acceleration-z"], expression: "a * a + b * b + c * c", unit: "raw²" },
  { label: "회전 XY 평면 제곱합", slots: ["회전 X", "회전 Y"], devices: ["gyro-x", "gyro-y"], expression: "a * a + b * b", unit: "(rad/s)²" },
  { label: "회전 XZ 평면 제곱합", slots: ["회전 X", "회전 Z"], devices: ["gyro-x", "gyro-z"], expression: "a * a + b * b", unit: "(rad/s)²" },
  { label: "회전 YZ 평면 제곱합", slots: ["회전 Y", "회전 Z"], devices: ["gyro-y", "gyro-z"], expression: "a * a + b * b", unit: "(rad/s)²" },
  { label: "가속도 XY 평면 제곱합", slots: ["가속도 X", "가속도 Y"], devices: ["acceleration-x", "acceleration-y"], expression: "a * a + b * b", unit: "raw²" },
  { label: "가속도 XZ 평면 제곱합", slots: ["가속도 X", "가속도 Z"], devices: ["acceleration-x", "acceleration-z"], expression: "a * a + b * b", unit: "raw²" },
  { label: "가속도 YZ 평면 제곱합", slots: ["가속도 Y", "가속도 Z"], devices: ["acceleration-y", "acceleration-z"], expression: "a * a + b * b", unit: "raw²" },
  { label: "온도 편차", slots: ["온도 1", "온도 2"], devices: ["temperature-a", "temperature-b"], expression: "a - b", unit: "°C" },
];

test("named vector and difference combinations build their specified input order and units", async ({ page }, info) => {
  await catalog(page);
  for (const combination of combinations) {
    let submitted: { sources: { sourceKey: string; deviceId: string }[]; continuous: { outputs: unknown[] } } | undefined;
    await page.route("**/api/control-plane/virtual-devices/continuous", route => {
      submitted = route.request().postDataJSON();
      return route.fulfill({ status: 201, json: { id: "11111111-1111-4111-8111-111111111111", displayName: combination.label, state: "REGISTERED" } });
    });
    await page.goto("/virtual-devices?create=1");
    const form = page.getByRole("form", { name: "연속 처리 장치 등록", exact: true });
    await expect(form.getByLabel("조합 종류", { exact: true }).getByRole("option", { name: combination.label, exact: true })).toHaveCount(1);
    await form.getByLabel("조합 종류", { exact: true }).selectOption({ label: combination.label });
    for (let i = 0; i < combination.slots.length; i++) await form.getByLabel(`${combination.slots[i]} 센서`, { exact: true }).selectOption(combination.devices[i]);
    await expect(form.getByText(`결과 단위: ${combination.unit}`, { exact: true })).toBeVisible();
    if (combination.label === "3축 가속도 제곱합") await page.screenshot({ path: info.outputPath("acceleration-preset.png") });
    await form.getByRole("button", { name: "연속 처리 장치 등록", exact: true }).click();
    await expect(page.getByRole("status").filter({ hasText: "등록했습니다" })).toBeVisible();
    expect(submitted?.sources.map(source => source.deviceId)).toEqual(combination.devices);
    expect(submitted?.sources.map(source => source.sourceKey)).toEqual(combination.slots.length === 3 ? ["a", "b", "c"] : ["a", "b"]);
    expect(submitted?.continuous.outputs).toEqual([{ name: "value", operation: "scalar", expression: combination.expression, unit: combination.unit }]);
    await page.unroute("**/api/control-plane/virtual-devices/continuous");
  }
});

test("catalog refresh uses the current compatible unit when submitting a preset", async ({ page }) => {
  await catalog(page);
  await page.clock.install();
  let refreshed = false;
  await page.route("**/api/control-plane/virtual-devices/continuous-catalog", route => route.fulfill({ json: { enabled: true, inputs: sensors.map(sensor => refreshed && sensor.unit === "°C" ? { ...sensor, unit: "°F" } : sensor) } }));
  let submitted: { continuous: { outputs: { unit: string }[] } } | undefined;
  await page.route("**/api/control-plane/virtual-devices/continuous", route => {
    submitted = route.request().postDataJSON();
    return route.fulfill({ status: 201, json: { id: "11111111-1111-4111-8111-111111111111", displayName: "통합 온도", state: "REGISTERED" } });
  });
  await page.goto("/virtual-devices?create=1");
  const form = page.getByRole("form", { name: "연속 처리 장치 등록", exact: true });
  await form.getByLabel("조합 종류", { exact: true }).selectOption({ label: "통합 온도" });
  await form.getByLabel("온도 1 센서", { exact: true }).selectOption("temperature-a");
  await form.getByLabel("온도 2 센서", { exact: true }).selectOption("temperature-b");
  refreshed = true; await page.clock.fastForward(11000);
  await expect(form.getByText("결과 단위: °F", { exact: true })).toBeVisible();
  await form.getByRole("button", { name: "연속 처리 장치 등록", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "등록했습니다" })).toBeVisible();
  expect(submitted?.continuous.outputs[0].unit).toBe("°F");
});

test("combined registration excludes incompatible and duplicate sensors and retains a missing selection", async ({ page }) => {
  await catalog(page);
  await page.clock.install();
  let removed = false;
  await page.route("**/api/control-plane/virtual-devices/continuous-catalog", route => route.fulfill({ json: { enabled: true, inputs: sensors.filter(sensor => !removed || sensor.deviceId !== "temperature-b") } }));
  let writes = 0;
  await page.route("**/api/control-plane/virtual-devices/continuous", route => { writes++; return route.fulfill({ status: 400 }); });
  await page.goto("/virtual-devices?create=1");
  const form = page.getByRole("form", { name: "연속 처리 장치 등록", exact: true });
  await form.getByLabel("조합 종류", { exact: true }).selectOption({ label: "통합 온도" });
  const first = form.getByLabel("온도 1 센서", { exact: true }), second = form.getByLabel("온도 2 센서", { exact: true });
  await expect(first.locator('option[value="humidity"], option[value="bool-temperature"]')).toHaveCount(0);
  await first.selectOption("temperature-a");
  await expect(second.locator('option[value="temperature-a"], option[value="temperature-f"]')).toHaveCount(0);
  const submit = form.getByRole("button", { name: "연속 처리 장치 등록", exact: true });
  await expect(submit).toBeDisabled();
  await second.selectOption("temperature-b"); await expect(submit).toBeEnabled();
  removed = true; await page.clock.fastForward(11000);
  await expect(second.getByRole("option", { name: "temperature-b · 연결 확인 필요", exact: true })).toHaveCount(1);
  await expect(second).toHaveValue("temperature-b"); await expect(submit).toBeDisabled();
  expect(writes).toBe(0);
});

test("missing vector axes block quick registration and axis selectors exclude other axes", async ({ page }) => {
  await catalog(page, sensors.filter(sensor => sensor.deviceId !== "gyro-z"));
  await page.goto("/virtual-devices?create=1");
  const form = page.getByRole("form", { name: "연속 처리 장치 등록", exact: true });
  await form.getByLabel("조합 종류", { exact: true }).selectOption({ label: "3축 회전 제곱합" });
  const x = form.getByLabel("회전 X 센서", { exact: true });
  await expect(x.locator('option[value="gyro-y"], option[value="acceleration-x"]')).toHaveCount(0);
  await x.selectOption("gyro-x"); await form.getByLabel("회전 Y 센서", { exact: true }).selectOption("gyro-y");
  await expect(form.getByText("같은 단위의 서로 다른 회전 Z 측정 항목이 필요합니다.", { exact: true })).toBeVisible();
  await expect(form.getByRole("button", { name: "연속 처리 장치 등록", exact: true })).toBeDisabled();
});

test("combination windows send the named expression with user-selected sample and storage periods", async ({ page }) => {
  await catalog(page);
  for (const operation of ["movingAverage", "minimum", "maximum"]) {
    let submitted: { continuous: unknown } | undefined;
    await page.route("**/api/control-plane/virtual-devices/continuous", route => {
      submitted = route.request().postDataJSON();
      return route.fulfill({ status: 201, json: { id: "11111111-1111-4111-8111-111111111111", displayName: "온도 편차", state: "REGISTERED" } });
    });
    await page.goto("/virtual-devices?create=1");
    const form = page.getByRole("form", { name: "연속 처리 장치 등록", exact: true });
    await form.getByLabel("조합 종류", { exact: true }).selectOption({ label: "온도 편차" });
    await form.getByLabel("온도 1 센서", { exact: true }).selectOption("temperature-a");
    await form.getByLabel("온도 2 센서", { exact: true }).selectOption("temperature-b");
    await form.getByLabel("계산 방식", { exact: true }).selectOption(operation);
    await expect(form.getByLabel("계산 방식", { exact: true }).getByRole("option", { name: "기준값 비교", exact: true })).toHaveCount(0);
    await form.getByText("고급 설정 · 표본 수·주기·보관 기간", { exact: true }).click();
    await form.getByLabel("최근 표본 수", { exact: true }).fill("0");
    await form.getByRole("button", { name: "연속 처리 장치 등록", exact: true }).click();
    expect(submitted).toBeUndefined();
    await form.getByLabel("최근 표본 수", { exact: true }).fill("7");
    await form.getByLabel("계산 간격(초)", { exact: true }).fill("2");
    await form.getByLabel("결과 보관 기간(일)", { exact: true }).fill("3");
    await form.getByRole("button", { name: "연속 처리 장치 등록", exact: true }).click();
    await expect(page.getByRole("status").filter({ hasText: "등록했습니다" })).toBeVisible();
    expect(submitted?.continuous).toEqual({ intervalSeconds: 2, retentionDays: 3, outputs: [{ name: "value", operation, expression: "a - b", unit: "°C", sampleCount: 7 }] });
    await page.unroute("**/api/control-plane/virtual-devices/continuous");
  }
});

test("a failed combination registration keeps its draft and key through advanced editing", async ({ page }) => {
  await catalog(page);
  const bodies: { key: string; displayName: string; sources: unknown; continuous: unknown }[] = [];
  let release!: () => void;
  const pending = new Promise<void>(resolve => { release = resolve; });
  await page.route("**/api/control-plane/virtual-devices/continuous", async route => {
    bodies.push(route.request().postDataJSON());
    if (bodies.length === 1) { await pending; return route.fulfill({ status: 503, json: { message: "조합 등록 재시도" } }); }
    return route.fulfill({ status: 201, json: { id: "11111111-1111-4111-8111-111111111111", displayName: "맞춤 회전", state: "REGISTERED" } });
  });
  await page.goto("/virtual-devices?create=1");
  const form = page.getByRole("form", { name: "연속 처리 장치 등록", exact: true });
  await form.getByLabel("조합 종류", { exact: true }).selectOption({ label: "회전 XZ 평면 제곱합" });
  await form.getByLabel("회전 X 센서", { exact: true }).selectOption("gyro-x");
  await form.getByLabel("회전 Z 센서", { exact: true }).selectOption("gyro-z");
  await form.getByLabel("계산 방식", { exact: true }).selectOption("movingAverage");
  await form.getByLabel("장치 이름", { exact: true }).fill("맞춤 회전");
  const submit = form.getByRole("button", { name: "연속 처리 장치 등록", exact: true });
  await submit.click();
  await expect(form.getByRole("button", { name: "저장 중…", exact: true })).toBeDisabled();
  await page.keyboard.press("Escape"); await expect(form).toBeVisible();
  expect(bodies).toHaveLength(1); release();
  await expect(form.getByRole("alert")).toHaveText("조합 등록 재시도"); await expect(form.getByRole("alert")).toBeFocused();
  await form.getByRole("button", { name: "여러 센서·직접 계산식 설정", exact: true }).click();
  await expect(form.getByLabel("장치 이름", { exact: true })).toHaveValue("맞춤 회전");
  await expect(form.getByLabel("입력 2 센서", { exact: true })).toHaveValue("gyro-z");
  await expect(form.getByLabel("출력 1 계산식", { exact: true })).toHaveValue("a * a + b * b");
  await expect(form.getByLabel("출력 1 단위", { exact: true })).toHaveValue("(rad/s)²");
  await expect(form.getByLabel("출력 1 창 표본 수", { exact: true })).toHaveValue("5");
  await submit.click(); await expect(page.getByRole("status").filter({ hasText: "등록했습니다" })).toBeVisible();
  expect(bodies).toHaveLength(2); expect(bodies[0].key).toMatch(/^vd-[a-f0-9]{32}$/); expect(bodies[1]).toEqual(bodies[0]);
});

test("changing combination resets input roles while keeping a custom name and modal draft", async ({ page }) => {
  await catalog(page); await page.goto("/virtual-devices?create=1");
  const form = page.getByRole("form", { name: "연속 처리 장치 등록", exact: true });
  await form.getByLabel("조합 종류", { exact: true }).selectOption({ label: "통합 온도" });
  await form.getByLabel("온도 1 센서", { exact: true }).selectOption("temperature-a");
  await form.getByLabel("장치 이름", { exact: true }).fill("내 장치");
  await form.getByLabel("조합 종류", { exact: true }).selectOption({ label: "3축 가속도 제곱합" });
  await expect(form.getByLabel("장치 이름", { exact: true })).toHaveValue("내 장치");
  await expect(form.getByLabel("가속도 X 센서", { exact: true })).toHaveValue("");
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "가상디바이스 등록", exact: true }).click();
  await expect(form.getByLabel("조합 종류", { exact: true })).toHaveValue("accelerationXYZ");
  await expect(form.getByLabel("장치 이름", { exact: true })).toHaveValue("내 장치");
  await form.getByLabel("조합 종류", { exact: true }).selectOption({ label: "단일 센서" });
  await expect(form.getByLabel("센서", { exact: true })).toBeVisible();
  await expect(form.getByLabel("가속도 X 센서", { exact: true })).toHaveCount(0);
});
