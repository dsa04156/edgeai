import { test, expect } from "@playwright/test";

const workflowId = "11111111-1111-4111-8111-111111111111";
const versionId = "22222222-2222-4222-8222-222222222222";
const workflow = { id: workflowId, key: "remaining", displayName: "일반 워크플로", createdAt: "2026-10-12T00:00:00Z" };

test("ordinary features and execution policies remain independent from continuous sensors", async ({ page }, testInfo) => {
  const errors: string[] = [], requested: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/api/control-plane/**", async route => {
    const path = new URL(route.request().url()).pathname.replace("/api/control-plane/", ""); requested.push(path);
    let body: unknown = { items: [], nextOffset: null };
    if (path === "platform") body = { capabilities: ["profiles", "devices", "nodes", "workflows", "runs", "tasks", "results"] };
    if (path === "csrf") body = { token: "fixture" };
    if (path === "workflows") body = { items: [workflow], nextOffset: null };
    if (path === `workflows/${workflowId}`) body = { workflow, versions: [{ id: versionId, workflowId, version: "1.0.0", digest: "a".repeat(64), createdAt: workflow.createdAt,
      dag: { tasks: [{ key: "root", serviceProfileVersionId: versionId, parameters: {} }], dependencies: [] } }], nextOffset: null };
    await route.fulfill({ status: 200, json: body });
  });
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "운영 현황", exact: true })).toBeVisible();
  await expect(page.locator('.metric-strip .metric')).toHaveCount(4);
  await expect(page.locator('.metric-strip')).toHaveAttribute('aria-busy', 'false');
  if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "메뉴 열기", exact: true }).click();
  await expect(page.getByRole("link", { name: "가상 센서", exact: true })).toHaveCount(1);
  await page.locator('.metric-strip').screenshot({ path: testInfo.outputPath("overview.png"), animations: "disabled" });
  await page.goto("/profiles?kind=DEVICE");
  await expect(page.getByRole("heading", { name: "실행 모듈 관리", exact: true })).toBeVisible();
  await expect(page.getByRole("radio")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "실행 모듈 등록", exact: true })).toBeVisible();
  expect(requested.some(path => path.startsWith("profiles/DEVICE"))).toBe(false);
  await page.goto("/workflows");
  await page.getByRole("button", { name: "일반 워크플로 remaining", exact: true }).click();
  await page.getByRole("button", { name: "1.0.0", exact: true }).click();
  const mode = page.getByRole("combobox", { name: "실행 위치 정책", exact: true });
  await expect(mode.locator("option")).toHaveCount(3);
  expect(await mode.locator("option").evaluateAll(options => options.map(option => (option as HTMLOptionElement).value))).toEqual(["AUTO", "NODE", "REMOTE"]);
  await expect(page.locator('option[value="VD"]')).toHaveCount(0);
  expect(requested.some(path => path.startsWith("profiles/VD"))).toBe(false);
  expect(requested.filter(path => path.startsWith("virtual-devices")).every(path => path === "virtual-devices" || path.endsWith("/execution"))).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.getByRole("group", { name: "작업별 실행 위치", exact: true }).screenshot({ path: testInfo.outputPath("workflow.png"), animations: "disabled" });
  expect(errors).toEqual([]);
});

test("continuous page exists while retired profile and batch management routes stay unavailable", async ({ page, request }) => {
  const response = await page.goto("/virtual-devices");
  expect(response?.status()).toBe(200);
  for (const path of ["virtual-devices/processing-catalog", "profiles/VD", "internal/vd/poll"]) {
    expect((await request.get(`/api/control-plane/${path}`)).status()).toBe(404);
    expect((await request.post(`/api/control-plane/${path}`, { headers: { Origin: "http://127.0.0.1:13081" }, data: {} })).status()).toBe(404);
  }
  expect((await request.post("/api/control-plane/virtual-devices", { headers: { Origin: "http://127.0.0.1:13081" }, data: {} })).status()).toBe(404);
});

test("service modules publish, retain old versions and protect referenced versions", async ({ page }, testInfo) => {
  type Module = { id: string; kind: "SERVICE"; key: string; version: string; spec: Record<string, unknown>; digest: string; createdAt: string };
  let modules: Module[] = [];
  const requested: string[] = [], errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/api/health", route => route.fulfill({ json: { status: "UP" } }));
  await page.route("**/api/control-plane/**", async route => {
    const request = route.request(), url = new URL(request.url());
    const path = url.pathname.replace("/api/control-plane/", "");
    requested.push(path);
    if (path === "csrf") return route.fulfill({ json: { token: "fixture" } });
    if (path.startsWith("profiles/SERVICE") && request.method() === "POST") {
      const data = request.postDataJSON();
      const saved: Module = { ...data, kind: "SERVICE", id: data.version === "1.0.0" ? workflowId : versionId,
        digest: "sha256:" + "a".repeat(64), createdAt: "2026-10-13T00:00:00Z" };
      modules.push(saved);
      return route.fulfill({ status: 201, json: saved });
    }
    if (path.startsWith("profiles/SERVICE/") && request.method() === "DELETE") {
      if (path.endsWith("1.0.0")) return route.fulfill({ status: 409, json: { code: "PROFILE_IN_USE", message: "워크플로에서 사용 중인 버전입니다." } });
      modules = modules.filter(module => !path.endsWith(module.version));
      return route.fulfill({ status: 204 });
    }
    return route.fulfill({ json: { items: path === "profiles/SERVICE" ? modules : [], nextOffset: null } });
  });
  await page.goto("/profiles");
  await page.getByRole("button", { name: "실행 모듈 등록", exact: true }).click();
  await page.getByLabel("모듈 키", { exact: true }).fill("temperature-average");
  await page.getByLabel("버전", { exact: true }).fill("1.0.0");
  await page.getByRole("button", { name: "실행 모듈 기본 양식 채우기", exact: true }).click();
  await page.getByLabel("실행 이미지", { exact: true }).fill("registry.example/temperature-average@sha256:" + "1".repeat(64));
  await page.getByRole("button", { name: "버전 발행", exact: true }).click();
  await expect(page.getByRole("heading", { name: "temperature-average · 1.0.0", exact: true })).toBeFocused();
  await expect(page.getByRole("link", { name: "워크플로에서 사용 →", exact: true })).toHaveAttribute("href", `/workflows?service=${workflowId}`);
  await page.getByRole("button", { name: "새 버전으로 수정", exact: true }).click();
  await expect(page.getByLabel("버전", { exact: true })).toHaveValue("1.0.1");
  await page.getByLabel("실행 제한 시간(초)", { exact: true }).fill("300");
  await page.getByRole("button", { name: "버전 발행", exact: true }).click();
  await expect(page.getByRole("heading", { name: "temperature-average · 1.0.1", exact: true })).toBeFocused();
  expect(modules.map(module => module.spec.timeoutSeconds)).toEqual([120, 300]);
  await expect(page.getByRole("table").getByRole("row")).toHaveCount(3);
  await page.screenshot({ path: testInfo.outputPath("service-modules.png"), fullPage: true });
  const oldRow = page.getByRole("row").filter({ has: page.getByRole("button", { name: "temperature-average 1.0.0", exact: true }) });
  await oldRow.getByRole("button", { name: "temperature-average 1.0.0 삭제", exact: true }).click();
  await oldRow.getByRole("button", { name: "삭제 확인", exact: true }).click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText("워크플로에서 사용 중");
  await expect(oldRow).toBeVisible();
  await oldRow.getByRole("button", { name: "취소", exact: true }).click();
  const newRow = page.getByRole("row").filter({ has: page.getByRole("button", { name: "temperature-average 1.0.1", exact: true }) });
  await newRow.getByRole("button", { name: "temperature-average 1.0.1 삭제", exact: true }).click();
  await newRow.getByRole("button", { name: "삭제 확인", exact: true }).click();
  await expect(page.getByRole("button", { name: "temperature-average 1.0.1", exact: true })).toHaveCount(0);
  await expect(oldRow).toBeVisible();
  expect(requested.some(path => path.startsWith("profiles/DEVICE"))).toBe(false);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  expect(errors).toEqual([]);
});
