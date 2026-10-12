import { test, expect, type Page } from "@playwright/test";

const properties = [
  { name: "temperature_raw", sensor: "temperature", jsonPath: "raw", dataType: "int32", unit: "raw", scale: 1, offset: 0 },
  { name: "light_raw", sensor: "light", jsonPath: "value", dataType: "int32", unit: "raw", scale: 1, offset: 0 },
];
const device = {
  id: "arduino-test", state: "ONLINE", observedState: "ONLINE", stateReason: "", lastSeen: "2026-10-08T08:00:00Z",
  model: "test-model", resourceVersion: "1", targetNode: "etri-dev0001-jetorn", mapper: { name: "test-mapper", ready: true },
  profile: { name: "arduino-test", kind: "physical", protocol: "serial-json", targetNode: "etri-dev0001-jetorn", collectionInterval: 1000,
    protocolConfig: { devicePath: "/dev/edgeai/arduino-test", deviceId: "arduino-test", baudRate: 115200, timeoutMs: 4000 }, properties },
  properties: { temperature_raw: { value: "0", timestamp: "2026-10-08T08:00:00Z" }, light_raw: { value: "128", timestamp: "2026-10-08T08:00:00Z" } },
};

async function inventory(page: Page, items: () => unknown[] = () => [device]) {
  await page.route("**/api/control-plane/**", async route => {
    const path = new URL(route.request().url()).pathname;
    if (route.request().method() !== "GET") return route.fulfill({ status: 405, json: { message: "Test writes must be intercepted" } });
    if (path === "/api/control-plane/managed-devices") return route.fulfill({ json: { items: items() } });
    if (path === "/api/control-plane/device-profiles") return route.fulfill({ json: { items: [] } });
    if (path === "/api/control-plane/csrf") return route.fulfill({ json: { token: "browser-test-token" } });
    return route.continue();
  });
  await page.goto("/devices?view=sensors");
}

test("device list opens measurement rows by mouse and keyboard and stays open during refresh", async ({ page }) => {
  await inventory(page);
  const summary = page.getByLabel("arduino-test 장치 상세", { exact: true });
  const readings = page.getByRole("table", { name: "arduino-test 측정 항목별 최신값", exact: true });
  await expect(summary).toBeVisible();
  await expect(readings).toBeHidden();
  await summary.click();
  await expect(readings).toBeVisible();
  await expect(readings.getByRole("row", { name: /temperature_raw/ })).toContainText("0 raw");
  await summary.press("Enter");
  await expect(readings).toBeHidden();
  await summary.press("Space");
  await expect(readings).toBeVisible();
  await page.waitForResponse(response => response.url().endsWith("/api/control-plane/managed-devices") && response.request().method() === "GET");
  await expect(readings).toBeVisible();
  await expect(summary).toBeFocused();
});

test("expanded device keeps add input on conflict and shows the newly added measurement", async ({ page }) => {
  let current = structuredClone(device);
  let conflict = true;
  await inventory(page, () => [current]);
  await page.route("**/api/control-plane/managed-devices/arduino-test/properties", async route => {
    if (conflict) {
      conflict = false;
      return route.fulfill({ status: 409, json: { message: "다른 변경이 있습니다. 다시 시도하세요." } });
    }
    const added = route.request().postDataJSON() as typeof properties[number];
    current = { ...current, profile: { ...current.profile, properties: [...current.profile.properties, added] },
      properties: { ...current.properties, [added.name]: { value: "42", timestamp: "2026-10-08T08:00:00Z" } } };
    return route.fulfill({ status: 201, json: current });
  });
  await page.getByLabel("arduino-test 장치 상세", { exact: true }).click();
  const add = page.getByRole("button", { name: "arduino-test 항목 추가", exact: true });
  await add.click();
  let form = page.getByRole("form", { name: "arduino-test 측정 항목 추가", exact: true });
  await expect(form.getByRole("combobox", { name: "측정 항목 선택", exact: true })).toBeFocused();
  await form.getByRole("button", { name: "취소", exact: true }).click();
  await expect(add).toBeFocused();
  await add.click();
  form = page.getByRole("form", { name: "arduino-test 측정 항목 추가", exact: true });
  await form.getByLabel("항목 1 이름", { exact: true }).fill("extra_raw");
  await form.getByLabel("단위", { exact: true }).fill("raw");
  await form.getByRole("button", { name: "항목 추가", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "다른 변경이 있습니다." })).toBeFocused();
  await expect(form.getByLabel("항목 1 이름", { exact: true })).toHaveValue("extra_raw");
  await form.getByRole("button", { name: "항목 추가", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "extra_raw 항목을 추가했습니다." })).toBeFocused();
  await expect(page.getByRole("row", { name: /extra_raw/ })).toContainText("42 raw");
  await expect(page.getByLabel("arduino-test 장치 상세", { exact: true })).toContainText("측정 항목 3개");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("expanded device keeps item and whole-device deletion confirmation and focus", async ({ page }) => {
  let current = [structuredClone(device)];
  await inventory(page, () => current);
  await page.route("**/api/control-plane/managed-devices/arduino-test", route => {
    current = [];
    return route.fulfill({ status: 204, body: "" });
  });
  await page.getByLabel("arduino-test 장치 상세", { exact: true }).click();
  const itemDelete = page.getByRole("button", { name: "arduino-test light_raw 항목 삭제", exact: true });
  await itemDelete.click();
  await expect(page.getByRole("button", { name: "arduino-test light_raw 삭제 확인", exact: true })).toBeFocused();
  await page.getByRole("button", { name: "취소", exact: true }).click();
  await expect(itemDelete).toBeFocused();
  const remove = page.getByRole("button", { name: "arduino-test 장치 삭제", exact: true });
  await remove.click();
  await expect(page.getByRole("button", { name: "arduino-test 장치 삭제 확인", exact: true })).toBeFocused();
  await page.getByRole("button", { name: "취소", exact: true }).click();
  await expect(remove).toBeFocused();
  await remove.click();
  await page.getByRole("button", { name: "arduino-test 장치 삭제 확인", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "arduino-test 장치 등록을 삭제했습니다." })).toBeFocused();
  await expect(page.getByLabel("arduino-test 장치 상세", { exact: true })).toHaveCount(0);
  await expect(page.getByText("등록된 센서가 없습니다. 새 장치 등록에서 연결 설정을 입력하세요.")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("search keeps pending measurement input and cancellation returns focus to a visible control", async ({ page }) => {
  await inventory(page);
  await page.getByLabel("arduino-test 장치 상세", { exact: true }).click();
  await page.getByRole("button", { name: "arduino-test 항목 추가", exact: true }).click();
  const form = page.getByRole("form", { name: "arduino-test 측정 항목 추가", exact: true });
  await form.getByLabel("항목 1 이름", { exact: true }).fill("pending_raw");
  await page.getByRole("searchbox", { name: "현재 페이지에서 검색", exact: true }).fill("unmatched-device");
  await expect(form).toBeVisible();
  await expect(form.getByLabel("항목 1 이름", { exact: true })).toHaveValue("pending_raw");
  await form.getByRole("button", { name: "취소", exact: true }).click();
  await expect(page.getByLabel("arduino-test 장치 상세", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "새 장치 등록", exact: true })).toBeFocused();
});


test("each measurement edits only its settings and retains input on refresh, validation and conflict", async ({ page }) => {
  let current = structuredClone(device);
  let conflict = true;
  const submitted: unknown[] = [];
  await inventory(page, () => [current]);
  await page.route("**/api/control-plane/managed-devices/arduino-test/properties/temperature_raw", async route => {
    expect(route.request().method()).toBe("PUT");
    const changed = route.request().postDataJSON() as typeof properties[number];
    submitted.push(changed);
    if (conflict) {
      conflict = false;
      return route.fulfill({ status: 409, json: { message: "동시 변경 충돌입니다. 다시 시도하세요." } });
    }
    current = { ...current, profile: { ...current.profile, properties: current.profile.properties.map(p => p.name === changed.name ? changed : p) } };
    return route.fulfill({ json: current });
  });
  await page.getByLabel("arduino-test 장치 상세", { exact: true }).click();
  await expect(page.getByRole("button", { name: "수정", exact: true })).toHaveCount(0);
  const edit = page.getByRole("button", { name: "arduino-test temperature_raw 항목 수정", exact: true });
  await edit.click();
  const form = page.getByRole("form", { name: "arduino-test temperature_raw 측정 항목 수정", exact: true });
  await expect(form.getByLabel("항목 1 이름", { exact: true })).toHaveAttribute("readonly", "");
  await expect(form.getByLabel("프레임 sensor", { exact: true })).toBeFocused();
  await expect(form.getByLabel("프레임 sensor", { exact: true })).toHaveValue("temperature");
  await expect(form.getByLabel("항목 2 이름", { exact: true })).toHaveCount(0);
  await form.getByLabel("단위", { exact: true }).fill("C");
  await form.getByLabel("배율", { exact: true }).fill("0.1");
  await form.getByLabel("보정값", { exact: true }).fill("2");
  await form.getByRole("combobox", { name: "자료형", exact: true }).selectOption("float32");
  await page.waitForResponse(response => response.url().endsWith("/api/control-plane/managed-devices") && response.request().method() === "GET");
  await expect(form.getByLabel("단위", { exact: true })).toHaveValue("C");
  await form.getByLabel("프레임 sensor", { exact: true }).fill("");
  await form.getByRole("button", { name: "항목 저장", exact: true }).click();
  await expect(form.getByLabel("프레임 sensor", { exact: true })).toBeFocused();
  expect(submitted).toHaveLength(0);
  await form.getByLabel("프레임 sensor", { exact: true }).fill("temperature");
  await form.getByRole("button", { name: "항목 저장", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "동시 변경 충돌" })).toBeFocused();
  await expect(form.getByLabel("배율", { exact: true })).toHaveValue("0.1");
  await form.getByRole("button", { name: "항목 저장", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "temperature_raw 항목을 수정했습니다." })).toBeFocused();
  await expect(form).toHaveCount(0);
  expect(submitted).toEqual(Array(2).fill({ ...properties[0], dataType: "float32", unit: "C", scale: 0.1, offset: 2 }));
  expect(current.profile.properties[1]).toEqual(properties[1]);
  expect(current.profile.protocolConfig).toEqual(device.profile.protocolConfig);
  await expect(page.getByRole("row", { name: /temperature_raw/ })).toContainText("0 C");
  await expect(page.getByRole("row", { name: /light_raw/ })).toContainText("128 raw");
  await expect(page.getByLabel("arduino-test 장치 상세", { exact: true })).toContainText("측정 항목 2개");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("individual edit cancellation restores its row focus and preserves pending input through search", async ({ page }) => {
  await inventory(page);
  await page.getByLabel("arduino-test 장치 상세", { exact: true }).click();
  const edit = page.getByRole("button", { name: "arduino-test light_raw 항목 수정", exact: true });
  await edit.click();
  const form = page.getByRole("form", { name: "arduino-test light_raw 측정 항목 수정", exact: true });
  await expect(form.getByLabel("프레임 sensor", { exact: true })).toHaveValue("light");
  await form.getByRole("button", { name: "취소", exact: true }).click();
  await expect(edit).toBeFocused();
  await edit.click();
  await form.getByLabel("단위", { exact: true }).fill("pending");
  await page.getByRole("searchbox", { name: "현재 페이지에서 검색", exact: true }).fill("unmatched-device");
  await expect(form).toBeVisible();
  await expect(form.getByLabel("단위", { exact: true })).toHaveValue("pending");
  await form.getByRole("button", { name: "취소", exact: true }).click();
  await expect(page.getByLabel("arduino-test 장치 상세", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "새 장치 등록", exact: true })).toBeFocused();
});


test("switching from pending edit or addition to deletion keeps search-pinned confirmation and cancel focus", async ({ page }) => {
  await inventory(page);
  const summary = page.getByLabel("arduino-test 장치 상세", { exact: true });
  const search = page.getByRole("searchbox", { name: "현재 페이지에서 검색", exact: true });
  for (const action of ["edit", "add"] as const) {
    await search.fill("");
    await summary.click();
    await page.getByRole("button", { name: action === "edit" ? "arduino-test temperature_raw 항목 수정" : "arduino-test 항목 추가", exact: true }).click();
    await search.fill("unmatched-device");
    await page.getByRole("button", { name: action === "edit" ? "arduino-test light_raw 항목 삭제" : "arduino-test 장치 삭제", exact: true }).click();
    const confirm = page.getByRole("button", { name: action === "edit" ? "arduino-test light_raw 삭제 확인" : "arduino-test 장치 삭제 확인", exact: true });
    await expect(confirm).toBeVisible();
    await expect(confirm).toBeFocused();
    await expect(page.getByRole("form", { name: /측정 항목 (수정|추가)/ })).toHaveCount(0);
    await page.getByRole("button", { name: "취소", exact: true }).click();
    await expect(summary).toHaveCount(0);
    await expect(page.getByRole("button", { name: "새 장치 등록", exact: true })).toBeFocused();
  }
});
