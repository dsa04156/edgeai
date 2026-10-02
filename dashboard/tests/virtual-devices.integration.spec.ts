import { test, expect } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";

test("real VD source replacement preserves identity, fences stale edits and protects active Devices", async ({ page }, testInfo) => {
  const username = process.env.EDGEAI_API_USER, password = process.env.EDGEAI_API_PASSWORD;
  if (!username || !password) throw new Error("VD E2E requires the project development environment");
  const errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
  const authorization = `Basic ${Buffer.from(`${username}:${password}`).toString("base64")}`;
  const tokenResponse = await page.request.get("/api/control-plane/csrf", { headers: { authorization } });
  expect(tokenResponse.status()).toBe(200);
  const headers = { authorization, "X-CSRF-TOKEN": (await tokenResponse.json()).token };
  const key = `browser-vd-${randomUUID()}`;
  async function profile(kind: string, spec: object) {
    const response = await page.request.post(`/api/control-plane/profiles/${kind}`, { headers, data: { key, version: "1.0.0", spec } });
    expect(response.status()).toBe(201); return response.json();
  }
  const dp = await profile("DEVICE", { source: "browser-test", protocol: "synthetic" });
  const sp = await profile("SERVICE", JSON.parse(await readFile("../contracts/profiles/service-execution.example.json", "utf8")));
  const vp = await profile("VD", { apiVersion: "edgeai.vd/v1", type: "processing", serviceProfileVersionId: sp.id,
    sources: { input: { deviceProfileVersionId: dp.id, required: true, sourceModes: ["SYNTHETIC"] } },
    state: { mode: "STATELESS" }, runtime: { maxConcurrentTasks: 1, startupTimeoutSeconds: 60, drainTimeoutSeconds: 60 } });
  async function device(suffix: string) {
    const response = await page.request.post("/api/control-plane/devices", { headers, data: { key: `${key}-${suffix}`, displayName: suffix, profileVersionId: dp.id, sourceMode: "SYNTHETIC" } });
    expect(response.status()).toBe(201); return response.json();
  }
  const a = await device("a"), b = await device("b");
  await page.goto("/virtual-devices");
  expect((await page.request.get("/api/control-plane/virtual-devices")).status()).toBe(401);
  await page.getByLabel("사용자 이름", { exact: true }).fill(username);
  await page.getByLabel("비밀번호", { exact: true }).fill(password);
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await expect(page.getByRole("button", { name: "연결 해제", exact: true })).toBeVisible();
  const create = page.getByRole("form", { name: "VD 등록", exact: true });
  await create.getByLabel("VD 키", { exact: true }).fill(key);
  await create.getByLabel("VD 이름", { exact: true }).fill("브라우저 가상 장치");
  await create.getByLabel("VD Profile 버전 ID", { exact: true }).fill(vp.id);
  await create.getByRole("button", { name: "원본 추가", exact: true }).click();
  await create.getByLabel("원본 키 1", { exact: true }).fill("input");
  await create.getByLabel("원본 Device ID 1", { exact: true }).fill(a.id);
  const created = page.waitForResponse(r => r.request().method() === "POST" && r.url().endsWith("/api/control-plane/virtual-devices"));
  await create.getByRole("button", { name: "VD 등록", exact: true }).click();
  const response = await created; expect(response.status()).toBe(201); const vd = await response.json();
  const path = `/api/control-plane/virtual-devices/${vd.id}`;
  await expect(page.getByText("가상 장치와 원본 연결을 등록했습니다.", { exact: true })).toBeVisible();
  const detail = page.locator('[aria-labelledby="vd-detail-title"]');
  await expect(detail).toContainText(`VD ID ${vd.id}`); await expect(detail).toContainText("등록됨");
  expect(vd.state).toBe("REGISTERED");
  for (const method of ["POST", "PATCH", "DELETE"]) {
    const denied = await page.request.fetch(method === "POST" ? "/api/control-plane/virtual-devices" : path,
      { method, headers: { authorization }, ...(method === "DELETE" ? {} : { data: {} }) });
    expect(denied.status()).toBe(403);
  }
  const blocked = await page.request.delete(`/api/control-plane/devices/${a.id}`, { headers });
  expect(blocked.status()).toBe(409); expect((await blocked.json()).code).toBe("DEVICE_IN_USE");
  const update = page.getByRole("form", { name: "VD 설정 수정", exact: true });
  await update.getByLabel("원본 Device ID 1", { exact: true }).fill(b.id);
  await update.getByRole("button", { name: "VD 설정 저장", exact: true }).click();
  await expect(page.getByText("VD 설정을 저장했습니다. 기존 원본 연결 이력은 보존됩니다.", { exact: true })).toBeVisible();
  const changed = await (await page.request.get(path, { headers })).json();
  expect(changed.vd.id).toBe(vd.id); expect(changed.vd.revision).toBe(1); expect(changed.sourceHistory).toHaveLength(2);
  expect(changed.activeSources[0].deviceId).toBe(b.id);
  expect(changed.sourceHistory.find((source: { deviceId: string }) => source.deviceId === a.id).closedRevision).toBe(1);
  expect((await page.request.delete(`/api/control-plane/devices/${a.id}`, { headers })).status()).toBe(200);
  // A concurrent writer advances the actual server revision. The user's rejected form is retained.
  expect((await page.request.patch(path, { headers, data: { revision: 1, displayName: "경쟁 변경", sources: [{ sourceKey: "input", deviceId: b.id }], placement: { mode: "AUTO" } } })).status()).toBe(200);
  await update.getByLabel("표시 이름", { exact: true }).fill("내 변경");
  await update.getByRole("button", { name: "VD 설정 저장", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: /.+/ })).toContainText("다시 조회한 revision");
  await expect(update.getByLabel("표시 이름", { exact: true })).toHaveValue("내 변경");
  await page.getByRole("button", { name: "VD 새로고침", exact: true }).click();
  await expect(detail.getByRole("heading", { name: "경쟁 변경", exact: true })).toBeVisible();
  await expect(update.getByLabel("표시 이름", { exact: true })).toHaveValue("경쟁 변경");
  await page.screenshot({ path: testInfo.outputPath("vd-source-replaced.png"), fullPage: true });
  await detail.getByRole("button", { name: "VD 해제", exact: true }).click();
  await expect(detail.getByText("이 VD의 원본 연결을 종료합니다. VD ID와 연결 이력, 원본 장치는 보존됩니다.", { exact: true })).toBeVisible();
  await detail.getByRole("button", { name: "VD 해제 확정", exact: true }).click();
  await expect(page.getByText("가상 장치를 해제했습니다.", { exact: true })).toBeVisible();
  await expect(detail).toContainText("해제됨"); await expect(update).toHaveCount(0);
  const released = await (await page.request.get(path, { headers })).json();
  expect(released.vd.id).toBe(vd.id); expect(released.activeSources).toEqual([]); expect(released.sourceHistory).toHaveLength(2);
  expect((await page.request.get(`/api/control-plane/devices/${b.id}`, { headers })).status()).toBe(200);
  expect((await page.request.delete(`/api/control-plane/devices/${b.id}`, { headers })).status()).toBe(200);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
  await page.screenshot({ path: testInfo.outputPath("vd-released.png"), fullPage: true });
  await page.getByRole("button", { name: "연결 해제", exact: true }).click();
  await expect(page.getByRole("heading", { name: "등록된 가상 장치", exact: true })).toHaveCount(0);
  expect(errors).toEqual([]);
});
