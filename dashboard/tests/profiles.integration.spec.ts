import { test, expect } from "@playwright/test";
import { randomUUID } from "node:crypto";

test("real Profile publication, replay, conflict, version lookup and kind isolation", async ({ page }, testInfo) => {
  const username = process.env.EDGEAI_API_USER;
  const password = process.env.EDGEAI_API_PASSWORD;
  if (!username || !password) throw new Error("Profile E2E requires the project development environment");
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto("/profiles");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  const anonymous = await page.request.get("/api/control-plane/profiles/DEVICE");
  expect(anonymous.status()).toBe(401);
  await page.getByLabel("사용자 이름", { exact: true }).fill(username);
  await page.getByLabel("비밀번호", { exact: true }).fill("intentionally-wrong-test-password");
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: /.+/ })).toContainText("계정 정보를 확인");
  await page.getByLabel("비밀번호", { exact: true }).fill(password);
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await expect(page.getByRole("button", { name: "연결 해제" })).toBeVisible();

  for (const kind of ["DEVICE", "SERVICE", "VD"]) {
    await page.getByRole("radio", { name: new RegExp(kind + "$" ) }).check();
    await expect(page.getByRole("button", { name: "버전 발행" })).toBeEnabled();
    const key = `browser${randomUUID().replaceAll("-", "")}${"a".repeat(61)}`;
    await page.getByLabel("키로 찾기", { exact: true }).fill(key);
    await page.getByRole("button", { name: "조회", exact: true }).click();
    await expect(page.getByText("일치하는 Profile이 없습니다.")).toBeVisible();
    await page.getByLabel("Profile 키", { exact: true }).fill(key);
    await page.getByLabel("버전", { exact: true }).fill("1.0.0");
    await page.getByLabel("JSON 규격", { exact: true }).fill("{broken");
    await page.getByRole("button", { name: "버전 발행" }).click();
    await expect(page.getByRole("alert").filter({ hasText: /.+/ })).toContainText("JSON 문법");
    await page.getByLabel("JSON 규격", { exact: true }).fill('{"protocol":"mqtt","range":{"min":0,"max":100},"serial":9007199254740993}');
    await page.getByRole("button", { name: "버전 발행" }).click();
    await expect(page.getByText("새 버전을 발행했습니다.")).toBeVisible();
    await expect(page.getByLabel("발행된 JSON 규격", { exact: true })).toContainText('"mqtt"');
    await page.getByRole("button", { name: "버전 발행" }).click();
    await expect(page.getByText("같은 내용의 버전이 이미 발행되어 있습니다.")).toBeVisible();
    await page.getByLabel("JSON 규격", { exact: true }).fill('{"protocol":"http"}');
    await page.getByRole("button", { name: "버전 발행" }).click();
    await expect(page.getByRole("alert").filter({ hasText: /.+/ })).toContainText("새 버전을 등록");
    await page.getByLabel("버전", { exact: true }).fill("1.1.0");
    await page.getByRole("button", { name: "버전 발행" }).click();
    await expect(page.getByText("새 버전을 발행했습니다.")).toBeVisible();
    await page.getByRole("button", { name: `${key} 1.0.0`, exact: true }).click();
    await expect(page.getByLabel("발행된 JSON 규격", { exact: true })).toContainText('"mqtt"');
    await expect(page.getByLabel("발행된 JSON 규격", { exact: true })).toContainText("9007199254740993");
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (kind === "VD") await page.screenshot({ path: testInfo.outputPath("profile-registry.png"), fullPage: true });
  }
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
  await page.getByRole("button", { name: "연결 해제" }).click();
  await expect(page.getByRole("button", { name: "연결", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "발행된 Profile" })).toHaveCount(0);
  expect((await page.request.get("/api/control-plane/profiles/DEVICE")).status()).toBe(401);
  expect(errors).toEqual([]);
});
