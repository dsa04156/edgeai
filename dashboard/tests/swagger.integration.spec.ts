import { test, expect } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";

test.use({ httpCredentials: { username: process.env.EDGEAI_API_USER || "", password: process.env.EDGEAI_API_PASSWORD || "" } });

test("Swagger renders the exact contract and publishes with automatic CSRF", async ({ page }, testInfo) => {
  if (!process.env.EDGEAI_API_USER || !process.env.EDGEAI_API_PASSWORD) throw new Error("Development credentials required");
  const api = `http://127.0.0.1:${process.env.EDGEAI_API_PORT || "18080"}`;
  const errors: string[] = [];
  const externalRequests: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("request", request => {
    if (request.url().startsWith("http") && new URL(request.url()).origin !== api) externalRequests.push(new URL(request.url()).origin);
  });
  await page.goto(`${api}/swagger-ui.html`);
  await expect(page.getByRole("heading", { name: /EdgeAI Control Plane/ })).toBeVisible();
  const contract = await page.request.get(`${api}/openapi.yaml`);
  expect(await contract.text()).toBe(await readFile("../contracts/openapi/platform-api.yaml", "utf8"));
  await page.screenshot({ path: testInfo.outputPath("swagger-ui.png"), fullPage: true });

  const operation = page.locator("#operations-Profile-publishProfile");
  await expect(operation.locator(".opblock-summary-description")).toHaveText("프로필 새 버전 등록");
  await operation.locator(".opblock-summary-control").click();
  await expect(operation.locator(".opblock-description-wrapper").filter({ hasText: "실제 장치/런타임 호환성 검증은 후속 개발 범위" })).toBeVisible();
  await operation.getByRole("button", { name: "Try it out" }).click();
  await operation.locator(".parameters select").selectOption("DEVICE");
  const key = `swagger-${randomUUID()}`;
  await operation.locator("textarea.body-param__text").fill(JSON.stringify({ key, version: "1.0.0", spec: { protocol: "mqtt" } }));
  const published = page.waitForResponse(response => response.request().method() === "POST" && response.url() === `${api}/api/v1/profiles/DEVICE`);
  await operation.getByRole("button", { name: "Execute", exact: true }).click();
  const response = await published;
  expect(response.status()).toBe(201);
  expect(response.request().headers()["x-csrf-token"]).toBeTruthy();
  expect((await response.json()).key).toBe(key);
  expect((await page.request.get(`${api}/api/v1/profiles/DEVICE/${key}/versions/1.0.0`)).status()).toBe(200);
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
  expect(errors).toEqual([]);
  expect(externalRequests).toEqual([]);
});
