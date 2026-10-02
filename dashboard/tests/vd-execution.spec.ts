import { test, expect } from "@playwright/test";

for (const enabled of [true, false]) test(`VD execution ${enabled ? "requests, replay and generations" : "disabled history"}`, async ({ page }, testInfo) => {
  await page.addInitScript(() => Object.defineProperty(globalThis.crypto, "randomUUID", { value: undefined }));
  const id = "11111111-1111-4111-8111-111111111111", runtimeId = "22222222-2222-4222-8222-222222222222";
  let phase = "empty", generation = 1, available = true, first = true, responseDelay = 0;
  const errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
  const history: Record<string, unknown>[] = [];
  const keys: string[] = [], operations: { id: string; vdId: string; kind: string; state: string; reason: null; requestedRevision: number }[] = [];
  const vd = { id, key: "vd-ui", displayName: "실행 시험 장치", profileVersionId: id, serviceProfileVersionId: id, state: "REGISTERED", revision: 0, placement: { mode: "AUTO" }, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() };
  await page.route("**/api/control-plane/**", async route => {
    const path = new URL(route.request().url()).pathname.replace("/api/control-plane/", "");
    const now = new Date().toISOString();
    if (path === "csrf") return route.fulfill({ json: { token: "fixture" } });
    if (["profiles/VD", "devices"].includes(path)) return route.fulfill({ json: { items: [], nextOffset: null } });
    if (path === "virtual-devices") return route.fulfill({ json: { items: [vd], nextOffset: null } });
    if (path === `virtual-devices/${id}`) return route.fulfill({ json: { vd, activeSources: [], sourceHistory: [], sourceHistoryTruncated: false } });
    if (path === `virtual-devices/${id}/execution`) {
      if (!available) return route.fulfill({ status: 503, json: { message: "실행 저장소 복구 대기" } });
      const current = phase === "empty" || phase === "stopped" ? null : { id: generation === 1 ? runtimeId : "33333333-3333-4333-8333-333333333333", vdId: id, generation, requestedRevision: 0, desiredState: phase === "draining" ? "DRAINING" : "RUNNING",
        observedState: phase === "ready" ? "READY" : "PENDING", ready: phase === "ready", nodeName: phase === "ready" ? "worker-a" : null,
        leaseUntil: new Date(Date.now() + 1500).toISOString(), failureReason: null };
      if (current) {
        const index = history.findIndex(runtime => runtime.generation === generation);
        if (index >= 0) history[index] = current; else { history.forEach(runtime => { runtime.observedState = "TERMINATED"; }); history.unshift(current); }
      }
      if (phase === "stopped") history.forEach(runtime => { runtime.observedState = "TERMINATED"; runtime.desiredState = "STOPPED"; runtime.ready = false; });
      if (responseDelay) await new Promise(resolve => setTimeout(resolve, responseDelay));
      return route.fulfill({ json: { vdId: id, enabled, asOf: now, current, pendingOperation: null, runtimeHistory: history, runtimeHistoryTruncated: false,
        bindings: history.map(runtime => ({ id: runtime.id, vdId: id, runtimeId: runtime.id, openedRevision: 0, closedRevision: runtime.observedState === "TERMINATED" ? 0 : null, openedAt: now, closedAt: runtime.observedState === "TERMINATED" ? now : null })), bindingsTruncated: false, operations, operationsTruncated: false } });
    }
    if (route.request().method() === "POST") {
      const action = path.split("/").at(-1); expect(enabled).toBe(true);
      expect(route.request().postDataJSON()).toEqual({ revision: 0 });expect(route.request().headers()["x-csrf-token"]).toBe("fixture");
      keys.push(route.request().headers()["idempotency-key"]);
      if (first) { first = false; return route.fulfill({ status: 503, json: { message: "응답 불확실 · 재전송" } }); }
      phase = action === "drain" ? "draining" : "pending"; if (action === "replace") generation++;
      const operation = { id: `00000000-0000-4000-8000-${String(operations.length + 1).padStart(12, "0")}`, vdId: id, kind: `VD_${action?.toUpperCase()}`, state: "RUNNING", reason: null, requestedRevision: 0 };
      operations.forEach(previous => { if (previous.state === "RUNNING") previous.state = "SUPERSEDED"; });
      operations.unshift(operation);return route.fulfill({ status: 202, json: operation });
    }
    return route.fulfill({ status: 404, json: { message: "Unknown fixture path" } });
  });
  await page.goto("/virtual-devices");
  await page.getByLabel("사용자 이름", { exact: true }).fill("fixture");await page.getByLabel("비밀번호", { exact: true }).fill("fixture");
  await page.getByRole("button", { name: "연결", exact: true }).click();await page.getByRole("button", { name: "실행 시험 장치 vd-ui", exact: true }).click();
  const panel = page.getByRole("region", { name: "VD 실행", exact: true });
  await expect(panel).toContainText("활성 실행 없음");
  if (!enabled) {
    await expect(panel).toContainText("VD 실행 기능이 비활성화되어 있습니다.");
    for (const action of ["실행 시작", "실행 교체", "실행 종료"]) await expect(panel.getByRole("button", { name: action, exact: true })).toBeDisabled();
    await panel.screenshot({ path: testInfo.outputPath("vd-disabled.png") });return;
  }
  await panel.getByRole("button", { name: "실행 시작", exact: true }).click();await panel.getByRole("button", { name: "실행 시작 요청", exact: true }).click();
  await expect(panel.getByRole("alert")).toContainText("재전송");await panel.getByRole("button", { name: "동일 요청 재전송", exact: true }).click();
  await expect(panel).toContainText("생성 대기");expect(keys[1]).toBe(keys[0]);expect(keys[0]).toMatch(/^[a-f0-9-]{36}$/);
  operations[0].state = "SUCCEEDED"; phase = "ready";await panel.getByRole("button", { name: "실행 상태 새로고침", exact: true }).click();
  await expect(panel.locator("p.stage")).toHaveText("실행 준비됨");await expect(panel).toContainText("worker-a");
  await panel.screenshot({ path: testInfo.outputPath("vd-ready.png") });
  // Lease expires before the next periodic poll. Do not keep displaying stale readiness.
  await expect(panel.locator("p.stage")).toHaveText("준비 권한 만료 · 확인 대기", { timeout: 2500 });
  phase = "pending";await panel.getByRole("button", { name: "실행 상태 새로고침", exact: true }).click();await expect(panel.locator("p.stage")).toHaveText("생성 대기");
  responseDelay = 1800;phase = "ready";
  const delayed = page.waitForResponse(response => response.url().endsWith("/execution"));
  await panel.getByRole("button", { name: "실행 상태 새로고침", exact: true }).click();await delayed;
  await expect(panel.locator("p.stage")).toHaveText("준비 권한 만료 · 확인 대기");responseDelay = 0;
  available = false;await panel.getByRole("button", { name: "실행 상태 새로고침", exact: true }).click();
  await expect(panel.locator("p.stage")).toHaveText("상태 확인 필요");await expect(panel.getByRole("button", { name: "실행 교체", exact: true })).toBeDisabled();
  available = true;await panel.getByRole("button", { name: "실행 상태 새로고침", exact: true }).click();
  await expect(panel.getByRole("button", { name: "실행 교체", exact: true })).toBeEnabled();
  await panel.getByRole("button", { name: "실행 교체", exact: true }).click();await expect(panel).toContainText("이전 실행 종료 후 새 세대를 시작합니다.");
  await panel.getByRole("button", { name: "실행 교체 요청", exact: true }).click();await expect(panel).toContainText("2세대");expect(keys[2]).not.toBe(keys[1]);
  await panel.getByRole("button", { name: "실행 종료", exact: true }).click();await panel.getByRole("button", { name: "실행 종료 요청", exact: true }).click();
  await expect(panel.locator("p.stage")).toHaveText("실행 종료 중");
  operations[0].state = "SUCCEEDED"; phase = "stopped";await panel.getByRole("button", { name: "실행 상태 새로고침", exact: true }).click();await expect(panel.locator("p.stage")).toHaveText("활성 실행 없음");
  await expect(page.locator('[aria-labelledby="vd-detail-title"] > .toolbar .stage')).toHaveText("등록됨");
  await panel.getByText("실행 세대와 연결 이력", { exact: true }).click();await expect(panel).toContainText("1세대 · 종료 확인됨");await expect(panel).toContainText("2세대 · 종료 확인됨");await panel.screenshot({ path: testInfo.outputPath("vd-drained.png") });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
  expect(errors).toEqual([]);
});
