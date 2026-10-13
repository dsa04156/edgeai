import { test, expect, type Page } from "@playwright/test";

async function catalog(page: Page) {
  await page.route("**/api/health", route => route.fulfill({ json: { status: "UP" } }));
  await page.route("**/api/control-plane/**", route => {
    const path = new URL(route.request().url()).pathname.replace("/api/control-plane/", "");
    if (path === "csrf") return route.fulfill({ json: { token: "fixture" } });
    if (path === "infrastructure") return route.fulfill({ json: { fetchedAt: new Date().toISOString(), nodesStatus: "AVAILABLE", sensors: [], nodes: [{ name: "edge-a", kind: "EDGE_DEVICE" }] } });
    if (path === "virtual-devices/continuous-catalog") return route.fulfill({ json: { enabled: true, inputs: [{ deviceId: "temperature", deviceUid: "sensor-1", deviceModel: "temperature-model", namespace: "edgeai-devices", property: "temperature", type: "float64", unit: "°C", targetNode: "edge-a" }] } });
    return route.fulfill({ json: { items: [], nextOffset: null } });
  });
}

test("virtual registration opens one modal without moving the page and restores focus with its draft", async ({ page }, info) => {
  await catalog(page); await page.goto("/virtual-devices");
  const trigger = page.getByRole("button", { name: "가상 장치 등록", exact: true });
  await expect(trigger).toBeVisible();
  const position = await page.evaluate(() => scrollY);
  await trigger.click();
  const dialog = page.getByRole("dialog", { name: "가상 장치 등록", exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByLabel("장치 키", { exact: true })).toBeFocused();
  await dialog.getByLabel("장치 키", { exact: true }).fill("temperature-average");
  await page.keyboard.press("Shift+Tab");
  expect(await dialog.evaluate(element => element.contains(document.activeElement))).toBe(true);
  expect(await page.evaluate(() => scrollY)).toBe(position);
  expect(await page.evaluate(() => getComputedStyle(document.body).overflow)).toBe("hidden");
  await page.keyboard.press("Escape");
  await expect(dialog).not.toBeVisible(); await expect(trigger).toBeFocused();
  expect(await page.evaluate(() => getComputedStyle(document.body).overflow)).not.toBe("hidden");
  await trigger.click();
  await expect(dialog.getByLabel("장치 키", { exact: true })).toHaveValue("temperature-average");
  await page.screenshot({ path: info.outputPath("virtual-registration-modal.png"), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  const box = await dialog.boundingBox(); expect(box!.x).toBeGreaterThanOrEqual(0); expect(box!.y).toBeGreaterThanOrEqual(0);
  await dialog.getByRole("button", { name: "취소", exact: true }).click();
  await expect(dialog).not.toBeVisible(); await expect(trigger).toBeFocused();
});

test("sensor registration retains errors inside its modal and closes only after successful save", async ({ page }, info) => {
  await catalog(page); let attempts = 0;
  await page.route("**/api/control-plane/managed-devices", route => {
    if (route.request().method() !== "POST") return route.fulfill({ json: { items: [] } });
    const input = route.request().postDataJSON();
    expect(input.name).toBe("temperature-test"); expect(input.properties).toHaveLength(1);
    return ++attempts === 1 ? route.fulfill({ status: 503, json: { message: "등록 실패: 입력을 유지하세요." } }) : route.fulfill({ status: 201, json: { id: input.name } });
  });
  await page.goto("/devices?view=sensors");
  await page.getByRole("button", { name: "새 장치 등록", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "센서 등록", exact: true });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel("센서 이름", { exact: true }).fill("temperature-test");
  await dialog.getByLabel("Host", { exact: true }).fill("192.0.2.3");
  await dialog.getByRole("combobox", { name: /^대상 Edge Node/ }).selectOption("edge-a");
  await dialog.getByLabel("항목 1 이름", { exact: true }).fill("temperature");
  await dialog.getByRole("button", { name: "장치 등록", exact: true }).click();
  await expect(dialog.getByRole("alert")).toContainText("등록 실패");
  await expect(dialog.getByLabel("센서 이름", { exact: true })).toHaveValue("temperature-test");
  await page.screenshot({ path: info.outputPath("sensor-registration-modal.png"), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await dialog.getByRole("button", { name: "장치 등록", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole("status").filter({ hasText: "temperature-test 설정을 적용했습니다" })).toBeVisible();
  expect(attempts).toBe(2);
});

test("execution module publication keeps the draft in one modal and focuses the saved version", async ({ page }) => {
  await catalog(page); let saved: Record<string, unknown> | null = null;
  await page.route("**/api/control-plane/profiles/SERVICE**", route => {
    if (route.request().method() === "POST") {
      saved = { ...route.request().postDataJSON(), id: "22222222-2222-4222-8222-222222222222", kind: "SERVICE", digest: "sha256:" + "a".repeat(64), createdAt: "2026-10-13T00:00:00Z" };
      return route.fulfill({ status: 201, json: saved });
    }
    return route.fulfill({ json: { items: saved ? [saved] : [], nextOffset: null } });
  });
  await page.goto("/profiles");
  await page.getByRole("button", { name: "실행 모듈 등록", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "실행 모듈 등록", exact: true });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel("모듈 키", { exact: true }).fill("temperature-average");
  await dialog.getByLabel("버전", { exact: true }).fill("1.0.0");
  await dialog.getByLabel("JSON 규격", { exact: true }).fill('{"apiVersion":"edgeai/v1","type":"fixture"}');
  await page.keyboard.press("Escape"); await expect(dialog).not.toBeVisible();
  await page.getByRole("button", { name: "새 버전 등록", exact: true }).click();
  await expect(dialog.getByLabel("모듈 키", { exact: true })).toHaveValue("temperature-average");
  await dialog.getByRole("button", { name: "버전 발행", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole("heading", { name: "temperature-average · 1.0.0", exact: true })).toBeFocused();
  await page.getByRole("button", { name: "새 버전으로 수정", exact: true }).click();
  await expect(dialog).toBeVisible(); await expect(dialog.getByLabel("버전", { exact: true })).toHaveValue("1.0.1");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("workflow creation reports failures in its modal and retains the key for retry", async ({ page }) => {
  await catalog(page); let attempts = 0; let saved: Record<string, unknown> | null = null;
  await page.route("**/api/control-plane/workflows**", route => {
    if (route.request().method() === "POST") {
      if (++attempts === 1) return route.fulfill({ status: 409, json: { message: "워크플로 등록 재시도" } });
      saved = { ...route.request().postDataJSON(), id: "11111111-1111-4111-8111-111111111111", createdAt: "2026-10-13T00:00:00Z" };
      return route.fulfill({ status: 201, json: saved });
    }
    const path = new URL(route.request().url()).pathname;
    return path.endsWith("/workflows") ? route.fulfill({ json: { items: saved ? [saved] : [], nextOffset: null } }) : route.fulfill({ json: { workflow: saved, versions: [], nextOffset: null } });
  });
  await page.goto("/workflows");
  const trigger = page.getByRole("button", { name: "새 워크플로 만들기", exact: true });
  await expect(trigger).toBeVisible(); await trigger.click();
  const dialog = page.getByRole("dialog", { name: "워크플로 등록", exact: true });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel("워크플로 키", { exact: true }).fill("factory-inspection");
  await dialog.getByLabel("워크플로 이름", { exact: true }).fill("공장 검사");
  await dialog.getByRole("button", { name: "워크플로 생성", exact: true }).click();
  await expect(dialog.getByRole("alert")).toHaveText("워크플로 등록 재시도");
  await expect(dialog.getByLabel("워크플로 키", { exact: true })).toHaveValue("factory-inspection");
  await dialog.getByRole("button", { name: "워크플로 생성", exact: true }).click();
  await expect(dialog).not.toBeVisible(); await expect(page.getByRole("heading", { name: "공장 검사", exact: true })).toBeVisible();
  expect(attempts).toBe(2);
});

test("adding a measurement uses one modal and preserves its selected source on failure", async ({ page }) => {
  await catalog(page);
  const device = { id: "temperature", targetNode: "edge-a", state: "ONLINE", profile: { name: "temperature", protocol: "modbus-tcp", sourceName: "board-a", properties: [{ name: "temperature", dataType: "float32", register: 40001, scale: 1, offset: 0 }] }, properties: { temperature: { value: "21", timestamp: Date.now() } } };
  await page.route("**/api/control-plane/managed-devices", route => route.fulfill({ json: { items: [device] } }));
  await page.route("**/api/control-plane/managed-devices/temperature/properties", route => {
    expect(route.request().postDataJSON().name).toBe("humidity");
    return route.fulfill({ status: 409, json: { message: "같은 연결의 항목을 확인하세요." } });
  });
  await page.goto("/devices?view=sensors");
  const trigger = page.getByRole("button", { name: "temperature 항목 추가", exact: true });
  await trigger.click();
  const dialog = page.getByRole("dialog", { name: "측정 항목 추가", exact: true });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel("항목 1 이름", { exact: true }).fill("humidity");
  await dialog.getByRole("button", { name: "항목 추가", exact: true }).click();
  await expect(dialog.getByRole("alert")).toHaveText("같은 연결의 항목을 확인하세요.");
  await expect(dialog.getByLabel("항목 1 이름", { exact: true })).toHaveValue("humidity");
  await dialog.getByRole("button", { name: "취소", exact: true }).click();
  await expect(dialog).not.toBeVisible(); await expect(trigger).toBeFocused();
});

test("DAG version publication opens a modal and closes after a successful retry", async ({ page }) => {
  await catalog(page); let attempts = 0; let version: Record<string, unknown> | null = null;
  const workflow = { id: "11111111-1111-4111-8111-111111111111", key: "factory-inspection", displayName: "공장 검사", createdAt: "2026-10-13T00:00:00Z" };
  await page.route("**/api/control-plane/workflows**", route => {
    const path = new URL(route.request().url()).pathname;
    if (route.request().method() === "POST") {
      expect(route.request().postDataJSON().version).toBe("1.2.0");
      if (++attempts === 1) return route.fulfill({ status: 409, json: { message: "DAG 버전을 확인하세요." } });
      version = { ...route.request().postDataJSON(), id: "dag-1", workflowId: workflow.id, dag: { tasks: [], dependencies: [] }, digest: "sha256:" + "a".repeat(64), createdAt: workflow.createdAt };
      return route.fulfill({ status: 201, json: version });
    }
    return path.endsWith("/workflows") ? route.fulfill({ json: { items: [workflow], nextOffset: null } }) : route.fulfill({ json: { workflow, versions: version ? [version] : [], nextOffset: null } });
  });
  await page.goto("/workflows");
  await page.getByRole("button", { name: /공장 검사\s*factory-inspection/ }).click();
  const trigger = page.getByRole("button", { name: "새 DAG 버전 발행", exact: true });
  await expect(trigger).toBeVisible(); await trigger.click();
  const dialog = page.getByRole("dialog", { name: "DAG 버전 발행", exact: true });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel("DAG 버전", { exact: true }).fill("1.2.0");
  await dialog.getByRole("button", { name: "DAG 발행", exact: true }).click();
  await expect(dialog.getByRole("alert")).toHaveText("DAG 버전을 확인하세요.");
  await expect(dialog.getByLabel("DAG 버전", { exact: true })).toHaveValue("1.2.0");
  await dialog.getByRole("button", { name: "DAG 발행", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole("heading", { name: "선택한 DAG · 1.2.0", exact: true })).toBeVisible();
  expect(attempts).toBe(2);
});

test("workflow registration stays saved when the following detail lookup fails", async ({ page }) => {
  await catalog(page); let saves = 0;
  await page.route("**/api/control-plane/workflows**", route => {
    if (route.request().method() === "POST") {
      saves++;
      return route.fulfill({ status: 201, json: { ...route.request().postDataJSON(), id: "workflow-1", createdAt: "2026-10-13T00:00:00Z" } });
    }
    return new URL(route.request().url()).pathname.endsWith("/workflows") ? route.fulfill({ json: { items: [], nextOffset: null } }) : route.fulfill({ status: 503, json: { message: "상세 조회 실패" } });
  });
  await page.goto("/workflows");
  await page.getByRole("button", { name: "새 워크플로 만들기", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "워크플로 등록", exact: true });
  await dialog.getByLabel("워크플로 키", { exact: true }).fill("factory-inspection");
  await dialog.getByLabel("워크플로 이름", { exact: true }).fill("공장 검사");
  await dialog.getByRole("button", { name: "워크플로 생성", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole("alert").filter({ hasText: "등록은 완료" })).toBeVisible();
  expect(saves).toBe(1);
});
