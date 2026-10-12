import { test, expect } from "@playwright/test";

const workflowId = "11111111-1111-4111-8111-111111111111";
const versionId = "22222222-2222-4222-8222-222222222222";
const workflow = { id: workflowId, key: "remaining", displayName: "일반 워크플로", createdAt: "2026-10-12T00:00:00Z" };

test("overview, profiles and workflow retain ordinary features without virtual devices", async ({ page }, testInfo) => {
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
  await expect(page.locator('.metric-strip .metric')).toHaveCount(3);
  await expect(page.locator('.metric-strip')).toHaveAttribute('aria-busy', 'false');
  await expect(page.locator('a[href^="/virtual-devices"]')).toHaveCount(0);
  await page.locator('.metric-strip').screenshot({ path: testInfo.outputPath("overview.png"), animations: "disabled" });
  await page.goto("/profiles");
  await expect(page.getByRole("radio")).toHaveCount(2);
  await expect(page.getByRole("radio", { name: /장치/ })).toBeVisible();
  await expect(page.getByRole("radio", { name: /실행 모듈/ })).toBeVisible();
  await page.goto("/workflows");
  await page.getByRole("button", { name: "일반 워크플로 remaining", exact: true }).click();
  await page.getByRole("button", { name: "1.0.0", exact: true }).click();
  const mode = page.getByRole("combobox", { name: "실행 위치 정책", exact: true });
  await expect(mode.locator("option")).toHaveCount(3);
  expect(await mode.locator("option").evaluateAll(options => options.map(option => (option as HTMLOptionElement).value))).toEqual(["AUTO", "NODE", "REMOTE"]);
  await expect(page.locator('option[value="VD"]')).toHaveCount(0);
  expect(requested.some(path => path.startsWith("virtual-devices") || path.startsWith("profiles/VD"))).toBe(false);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.getByRole("group", { name: "작업별 실행 위치", exact: true }).screenshot({ path: testInfo.outputPath("workflow.png"), animations: "disabled" });
  expect(errors).toEqual([]);
});

test("removed page and management routes return 404", async ({ page, request }) => {
  const response = await page.goto("/virtual-devices");
  expect(response?.status()).toBe(404);
  for (const path of ["virtual-devices", "profiles/VD", "virtual-devices/11111111-1111-4111-8111-111111111111/execution"]) {
    expect((await request.get(`/api/control-plane/${path}`)).status()).toBe(404);
    expect((await request.post(`/api/control-plane/${path}`, { headers: { Origin: "http://127.0.0.1:13081" }, data: {} })).status()).toBe(404);
  }
});
