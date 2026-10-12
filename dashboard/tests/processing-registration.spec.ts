import { test, expect } from "@playwright/test";

test("sensor and processing selection registers without image or template input and preserves a failed request", async ({ page }) => {
  let attempts = 0;
  await page.route("**/api/control-plane/**", async route => {
    const path = new URL(route.request().url()).pathname.replace("/api/control-plane/", "");
    if (path === "csrf") return route.fulfill({ json: { token: "test" } });
    if (path === "virtual-devices/processing-catalog") return route.fulfill({ json: { ready: true, architectures: ["amd64"], operations: ["filter", "movingAverage", "rms", "fft", "threshold"] } });
    if (path === "managed-devices") return route.fulfill({ json: { items: [{ id: "sensor", model: "temperature-model", state: "ONLINE", profile: { properties: [{ name: "temperature", dataType: "float64", unit: "°C" }, { name: "label", dataType: "string" }] } }] } });
    if (path === "virtual-devices/processing" && route.request().method() === "POST") {
      const body = route.request().postDataJSON();
      expect(body.deviceId).toBe("sensor");
      expect(body).not.toHaveProperty("image"); expect(body).not.toHaveProperty("profileVersionId");
      expect(body.processing.pipelines[0].input).toEqual({ sourceKey: "input", property: "temperature" });
      expect(body.processing.pipelines[0].steps).toEqual([{ op: "movingAverage", window: 3 }]);
      if (++attempts === 1) return route.fulfill({ status: 503, json: { message: "등록 재시도" } });
      return route.fulfill({ status: 201, json: { id: "11111111-1111-4111-8111-111111111111", displayName: body.displayName } });
    }
    if (path === "virtual-devices/11111111-1111-4111-8111-111111111111") return route.fulfill({ status: 503, json: { message: "상세 조회 실패" } });
    return route.fulfill({ json: { items: [], nextOffset: null } });
  });
  await page.goto("/virtual-devices?create=1");
  const form = page.getByRole("form", { name: "센서 처리 장치 등록" });
  await expect(form).toBeVisible();
  await form.getByLabel("장치 키", { exact: true }).fill("temperature-average");
  await form.getByLabel("장치 이름", { exact: true }).fill("온도 평균");
  await form.getByLabel("센서 선택", { exact: true }).selectOption("sensor");
  await form.getByLabel("처리 1 · 측정 항목").selectOption("temperature");
  await expect(form.getByLabel("처리 1 · 측정 항목").getByRole("option", { name: "label", exact: true })).toHaveCount(0);
  await form.getByLabel("처리 표본 수").fill("3");
  await form.getByLabel("처리 1 · 단계 1 · 기능").selectOption("movingAverage");
  await form.getByLabel("처리 1 · 단계 1 · 창 크기").fill("3");
  await form.getByRole("button", { name: "센서 처리 장치 등록", exact: true }).click();
  await expect(form.getByRole("alert")).toHaveText("등록 재시도");
  await expect(form.getByLabel("센서 선택", { exact: true })).toHaveValue("sensor");
  await form.getByRole("button", { name: "센서 처리 장치 등록", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "등록했습니다" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("unconfigured common image clearly disables simple registration", async ({ page }) => {
  await page.route("**/api/control-plane/**", route => {
    const path = new URL(route.request().url()).pathname.replace("/api/control-plane/", "");
    return route.fulfill({ json: path === "csrf" ? { token: "test" } : path.endsWith("processing-catalog") ? { ready: false, architectures: [], operations: [] } : { items: [], nextOffset: null } });
  });
  await page.goto("/virtual-devices?create=1");
  await expect(page.getByText("공통 처리 실행 이미지가 아직 준비되지 않았습니다.")).toBeVisible();
  await expect(page.getByRole("button", { name: "센서 처리 장치 등록", exact: true })).toBeDisabled();
});
