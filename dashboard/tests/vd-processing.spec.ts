import { test, expect } from "@playwright/test";

test("VD processing editor publishes an ordered reusable pipeline and keeps input after failure", async ({ page }) => {
  let requests = 0;
  await page.route("**/api/control-plane/**", async route => {
    const path = new URL(route.request().url()).pathname.replace("/api/control-plane/", "");
    if (path === "csrf") return route.fulfill({ json: { token: "test" } });
    if (path === "managed-devices") return route.fulfill({ json: { items: [{ id: "sensor", model: "temperature-model", profile: { properties: [{ name: "temperature" }] } }] } });
    if (path === "profiles/SERVICE") return route.fulfill({ json: { items: [{ id: "22222222-2222-4222-8222-222222222222", kind: "SERVICE", key: "processing", version: "1.0.0" }], nextOffset: null } });
    if (path === "profiles/VD" && route.request().method() === "POST") {
      const body = route.request().postDataJSON();
      expect(body.spec.processing.sampleCount).toBe(4);
      expect(body.spec.processing.pipelines[0].steps.map((step: { op: string }) => step.op)).toEqual(["movingAverage", "threshold"]);
      if (++requests === 1) return route.fulfill({ status: 503, json: { message: "저장 재시도" } });
      return route.fulfill({ status: 201, json: { ...body, id: "11111111-1111-4111-8111-111111111111", kind: "VD" } });
    }
    return route.fulfill({ json: { items: [], nextOffset: null } });
  });
  await page.goto("/profiles?kind=VD");
  await page.getByRole("button", { name: "새 버전 등록", exact: true }).click();
  await page.getByRole("button", { name: "VD 기본 양식 채우기" }).click();
  await page.getByLabel("가상 장치 종류").selectOption("processing");
  await page.getByLabel("Profile 키", { exact: true }).fill("temperature-processing");
  await page.getByLabel("버전", { exact: true }).fill("1.0.0");
  await page.getByLabel("처리 SERVICE 규격 버전").selectOption("22222222-2222-4222-8222-222222222222");
  await page.getByLabel("센서 DeviceModel").selectOption("temperature-model");
  await page.getByRole("button", { name: "공통 처리 설정 추가" }).click();
  await page.getByLabel("처리 표본 수").fill("4");
  await page.getByLabel("처리 1 · 측정 항목").selectOption("temperature");
  await page.getByLabel("처리 1 · 단계 1 · 기능").selectOption("movingAverage");
  await page.getByLabel("처리 1 · 단계 1 · 창 크기").fill("2");
  await page.getByRole("button", { name: "처리 1 · 단계 추가", exact: true }).click();
  await page.getByLabel("처리 1 · 단계 2 · 기능").selectOption("threshold");
  await page.getByLabel("처리 1 · 단계 2 · 기준값").fill("30");
  await page.getByRole("button", { name: "버전 발행", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "저장 재시도" })).toBeVisible();
  await expect(page.getByLabel("처리 표본 수")).toHaveValue("4");
  await page.getByRole("button", { name: "버전 발행", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "발행" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
