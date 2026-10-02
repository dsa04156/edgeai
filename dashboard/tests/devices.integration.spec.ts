import { test, expect } from "@playwright/test";
import { randomUUID } from "node:crypto";

test("real device registration, revision, session fencing, observation and release", async ({ page }, testInfo) => {
  const username = process.env.EDGEAI_API_USER;
  const password = process.env.EDGEAI_API_PASSWORD;
  if (!username || !password) throw new Error("Device E2E requires the project development environment");
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  const authorization = `Basic ${Buffer.from(`${username}:${password}`).toString("base64")}`;
  const tokenResponse = await page.request.get("/api/control-plane/csrf", { headers: { authorization } });
  expect(tokenResponse.status()).toBe(200);
  const token = (await tokenResponse.json()).token;
  const headers = { authorization, "X-CSRF-TOKEN": token };
  const key = `browser-device-${randomUUID()}`;
  const profileResponse = await page.request.post("/api/control-plane/profiles/DEVICE", {
    headers, data: { key, version: "1.0.0", spec: { source: "browser-test", protocol: "synthetic" } },
  });
  expect(profileResponse.status()).toBe(201);
  const profile = await profileResponse.json();
  await page.goto("/devices");
  expect((await page.request.get("/api/control-plane/devices")).status()).toBe(401);
  await page.getByLabel("사용자 이름", { exact: true }).fill(username);
  await page.getByLabel("비밀번호", { exact: true }).fill(password);
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await expect(page.getByRole("button", { name: "연결 해제", exact: true })).toBeVisible();
  await page.getByLabel("장치 키", { exact: true }).fill(key);
  await page.getByLabel("장치 이름", { exact: true }).fill("브라우저 합성 장치");
  await page.getByLabel("DEVICE Profile 버전 ID", { exact: true }).fill(profile.id);
  await page.getByRole("combobox", { name: "데이터 출처", exact: true }).selectOption("SYNTHETIC");
  const created = page.waitForResponse(r => r.request().method() === "POST" && r.url().endsWith("/api/control-plane/devices"));
  await page.getByRole("button", { name: "장치 등록", exact: true }).click();
  const response = await created;
  expect(response.status()).toBe(201);
  const device = await response.json();
  const devicePath = `/api/control-plane/devices/${device.id}`;
  await expect(page.getByText("장치를 등록했습니다. 세션의 상태 보고를 기다립니다.")).toBeVisible();
  const detail = page.getByRole("region", { name: "브라우저 합성 장치", exact: true });
  await expect(detail).toContainText("보고 없음 · 합성 데이터");

  // Every write method must keep the server's CSRF boundary through the proxy.
  for (const method of ["PATCH", "PUT", "DELETE"]) {
    const path = method === "PUT" ? `${devicePath}/attachments/${randomUUID()}` : devicePath;
    const denied = await page.request.fetch(path, { method, headers: { authorization },
      ...(method === "DELETE" ? {} : { data: method === "PATCH" ? { revision: 0, displayName: "denied" } : { port: "test" } }) });
    expect(denied.status()).toBe(403);
  }
  await page.getByLabel("표시 이름", { exact: true }).fill("변경된 합성 장치");
  await page.getByRole("button", { name: "이름 저장", exact: true }).click();
  await expect(page.getByText("표시 이름을 수정했습니다.")).toBeVisible();
  expect((await page.request.patch(devicePath, { headers, data: { displayName: "stale writer", revision: device.revision } })).status()).toBe(409);

  const bootId = randomUUID();
  const sessionResponse = await page.request.post(`${devicePath}/sessions`, { headers, data: { bootId } });
  expect(sessionResponse.status()).toBe(201);
  const session = await sessionResponse.json();
  expect((await page.request.post(`${devicePath}/sessions`, { headers, data: { bootId } })).status()).toBe(200);
  // Send exact JSON text so the browser test also verifies integer precision in attributes.
  const observation = `{"sessionId":"${session.id}","sequence":0,"observedAt":"${new Date().toISOString()}","status":"ONLINE","attributes":{"serial":9007199254740993}}`;
  const observationHeaders = { ...headers, "Content-Type": "application/json" };
  expect((await page.request.post(`${devicePath}/observations`, { headers: observationHeaders, data: observation })).status()).toBe(201);
  expect((await page.request.post(`${devicePath}/observations`, { headers: observationHeaders, data: observation })).status()).toBe(200);
  await page.getByRole("button", { name: "장치 새로고침", exact: true }).click();
  const renamed = page.getByRole("region", { name: "변경된 합성 장치", exact: true });
  await expect(renamed).toContainText("온라인 · 합성 데이터");
  await renamed.getByText("세션·관측 데이터 상세", { exact: true }).click();
  await expect(page.getByLabel("장치 상세 JSON", { exact: true })).toContainText("9007199254740993");
  await page.screenshot({ path: testInfo.outputPath("device-online.png"), fullPage: true });

  expect((await page.request.post(`${devicePath}/sessions`, { headers, data: { bootId: randomUUID() } })).status()).toBe(201);
  expect((await page.request.post(`${devicePath}/observations`, { headers: observationHeaders, data: observation })).status()).toBe(409);
  await page.getByRole("button", { name: "장치 새로고침", exact: true }).click();
  await expect(renamed).toContainText("보고 없음 · 합성 데이터");
  await page.getByRole("button", { name: "장치 해제", exact: true }).click();
  await expect(page.getByText("이 장치를 해제하면 현재 연결과 세션이 종료됩니다. 기존 이력은 보존됩니다.")).toBeVisible();
  await page.getByRole("button", { name: "해제 확정", exact: true }).click();
  await expect(page.getByText("장치를 해제했습니다.", { exact: true })).toBeVisible();
  await expect(renamed).toContainText("해제됨 · 합성 데이터");
  await expect(renamed).toContainText("sequence 0");
  await expect(page.getByRole("button", { name: "이름 저장", exact: true })).toHaveCount(0);
  expect((await page.request.delete(devicePath, { headers })).status()).toBe(200);
  expect((await page.request.post(`${devicePath}/sessions`, { headers, data: { bootId: randomUUID() } })).status()).toBe(409);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
  await page.screenshot({ path: testInfo.outputPath("device-released.png"), fullPage: true });
  await page.getByRole("button", { name: "연결 해제", exact: true }).click();
  await expect(page.getByRole("heading", { name: "등록된 장치", exact: true })).toHaveCount(0);
  expect((await page.request.get(devicePath)).status()).toBe(401);
  expect(errors).toEqual([]);
});
