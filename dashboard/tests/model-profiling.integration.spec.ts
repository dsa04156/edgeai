import { test, expect, type Page } from "@playwright/test";
import { backendRunRefusal } from "./model-profiling-contract";

// The model measurement flow against an in-browser API (same pattern as the AI model spec). Nothing reaches a cluster.
const now = "2026-10-30T06:00:00Z";
const host = "10.254.192.47:5000";
const digest = "sha256:" + "a".repeat(64);
const modelId = "00000000-0000-4000-8000-0000000000d1";
const runId = "00000000-0000-4000-8000-0000000000e1";
const usage = (unit: string, average: number, p95: number, max: number) =>
  ({ status: "AVAILABLE", unit, min: average / 2, average, p95, max, samples: 20, firstSample: now, lastSample: now, reason: null });
const model = (availability: unknown) => ({
  id: modelId, digest: "sha256:" + "c".repeat(64), createdAt: now, measurementStatus: "UNMEASURED", resourceProfileId: null,
  modelKey: "digits-model", displayName: "숫자 인식", modelVersion: "1.0.0", configName: "default", description: null,
  image: { registry: host, repository: "digits-model-arm64", digest, reference: `${host}/digits-model-arm64@${digest}`, mediaType: "m", kind: "MANIFEST",
    sourceTag: "1.0.0", platform: { os: "linux", architecture: "arm64", variant: null, label: "linux/arm64", runnable: true },
    platformManifestDigest: digest, configDigest: "sha256:" + "f".repeat(64), layersSize: 1000, verifiedAt: now },
  execution: { command: null, args: null }, inputConditions: {}, runtime: null, requirements: null, resourceProfile: null, evidence: null, limitations: [],
  availability,
});
const options = {
  enabled: true, disabledReason: null, image: { reference: `${host}/digits-model-arm64@${digest}`, platform: "linux/arm64" },
  execution: { command: null, args: null },
  limits: {
    durationSeconds: { min: 300, default: 300, max: 1800 }, concurrency: { min: 1, default: 2, max: 16 },
    requestTimeoutSeconds: { min: 1, default: 10, max: 60 }, readinessTimeoutSeconds: { min: 30, default: 300, max: 900 },
    bodyMaxBytes: 65536, methods: ["GET", "POST"], contentTypes: ["application/json", "text/plain", "application/octet-stream"],
    cpuMillicores: { min: 100, max: 8000 }, memoryBytes: { min: 67108864, max: 17179869184 }, acceleratorCount: { min: 1, max: 4 },
    loadTypes: ["HTTP", "COMMAND"],
  },
  nodes: [
    { name: "jetson-01", architecture: "arm64", status: "READY", compatible: true, incompatibleReason: null,
      allocatable: { cpuMillicores: 6000, memoryBytes: 8589934592 }, accelerators: [{ resourceName: "nvidia.com/gpu.shared", allocatableCount: 1 }], busy: false },
    { name: "server-01", architecture: "amd64", status: "READY", compatible: false, incompatibleReason: "ARCHITECTURE_MISMATCH",
      allocatable: { cpuMillicores: 16000, memoryBytes: 34359738368 }, accelerators: [], busy: false },
  ],
};
const expectedRequest = {
  nodeName: "jetson-01", allocation: { cpuMillicores: 2000, memoryBytes: 4294967296, accelerator: { resourceName: "nvidia.com/gpu.shared", count: 1 } },
  durationSeconds: 300,
  load: { type: "HTTP", port: 8080, path: "/predict", method: "POST", contentType: "application/json", body: '{"pixels":[0,1]}',
    concurrency: 2, requestTimeoutSeconds: 10, readinessTimeoutSeconds: 300 },
};
const run = (state: string, extra: Record<string, unknown> = {}) => ({
  id: runId, modelId, state, message: state === "MEASURING" ? "부하 2/2 동시 요청 실행 중" : null, nodeName: "jetson-01", nodeArchitecture: "arm64",
  allocation: expectedRequest.allocation, durationSeconds: 300,
  load: { ...expectedRequest.load, body: undefined, bodyBytes: 16, bodyDigest: "sha256:" + "b".repeat(64) },
  kubernetes: { namespace: "edgeai-model-profiling", jobName: `edgeai-mp-${runId}`, podName: null, image: options.image.reference },
  window: { start: state === "STARTING" ? null : now, end: ["SUCCEEDED", "FAILED"].includes(state) ? "2026-10-30T06:05:00Z" : null },
  progress: state === "MEASURING" ? { requests: 120, succeeded: 120, failed: 0 } : null,
  createdAt: now, updatedAt: now, finishedAt: ["SUCCEEDED", "FAILED", "CANCELLED"].includes(state) ? "2026-10-30T06:06:00Z" : null, cancelRequestedAt: null,
  // Ended runs here are already cleaned up unless a test says otherwise.
  cleanup: ["SUCCEEDED", "FAILED", "CANCELLED"].includes(state) ? { state: "DONE", completedAt: "2026-10-30T06:07:00Z" } : { state: "NOT_STARTED", completedAt: null },
  failure: null, result: null, ...extra,
});
const succeeded = run("SUCCEEDED", {
  result: {
    loadEvidence: { type: "HTTP", requests: 1500, succeeded: 1500, failed: 0, statusCounts: { "200": 1500 }, latencyMillis: { p50: 40, p95: 88, max: 210 },
      throughputPerSecond: 5, successRatio: 1 },
    measurement: { cpu: usage("millicores", 300, 400, 600), memory: usage("bytes", 400_000_000, 450_000_000, 470_000_000), stepSeconds: 15, restarts: 0 },
    devices: [{ node: "jetson-01", kind: "GPU", device: "0", model: "Orin", exporter: "dcgm-exporter", attribution: "NODE_DEVICE_SHARED", perWorkload: false,
      status: "AVAILABLE", utilization: usage("percent", 30, 50, 70), memoryUsed: null, memoryTotalBytes: null, reason: null }],
    recommendation: { policy: "edgeai-model-profiling-recommendation-v1", cpu: { requestMillicores: 460, maximumMillicores: 900 },
      memory: { requestBytes: 536870912, maximumBytes: 1073741824 }, accelerators: [{ resourceName: "nvidia.com/gpu.shared", count: 1, basis: "TEST_ALLOCATION" }],
      rationale: ["CPU 요청=p95×1.15(10m 단위 올림)"] },
    limitations: ["단일 시험의 관측값이며 이론적 최소 요구량이 아닙니다."],
  },
});
const profile = {
  id: "00000000-0000-4000-8000-0000000000f1", modelId, revision: 1, runId, digest: "sha256:" + "9".repeat(64), createdAt: now,
  requirements: { cpu: { minimumMillicores: null, requestMillicores: 460, maximumMillicores: 900, basis: "RECOMMENDED" },
    memory: { minimumBytes: null, requestBytes: 805306368, maximumBytes: 1073741824, basis: "MANUAL" },
    accelerators: [{ resourceName: "nvidia.com/gpu.shared", count: 1, basis: "TEST_ALLOCATION" }] },
  scope: { image: options.image.reference, platform: "linux/arm64", loadType: "HTTP", nodeName: "jetson-01", nodeArchitecture: "arm64",
    allocation: expectedRequest.allocation, window: { start: now, end: "2026-10-30T06:05:00Z" } },
  limitations: ["시험 노드·할당 범위 안에서만 의미가 있습니다."],
};
const notReady = (reason: string, active: string | null = null) => ({ status: "NOT_READY", reason, activeRunId: active, latestRunId: active, profile: null });
const ready = { status: "READY", reason: null, activeRunId: null, latestRunId: runId,
  profile: { id: profile.id, revision: 1, runId, createdAt: now, requirements: profile.requirements, scope: profile.scope } };
const cleaningUp = { ...succeeded, cleanup: { state: "PENDING", completedAt: null } };

type State = { model: unknown; runs: unknown[]; profiles: unknown[]; runSteps: unknown[]; options: unknown; starts: { status: number; json: unknown }[] };
function mock(page: Page, state: State) {
  const posts: { path: string; key?: string; body: unknown }[] = [];
  void page.route("**/api/control-plane/**", async route => {
    const request = route.request(), path = new URL(request.url()).pathname.replace("/api/control-plane/", "");
    const base = `ai-models/${modelId}`;
    if (path === "csrf") return route.fulfill({ json: { token: "management-token" } });
    if (path === "ai-models/registry") return route.fulfill({ json: { configured: true, host } });
    if (path === "ai-models") return route.fulfill({ json: { items: [state.model], nextOffset: null } });
    if (path === base) return route.fulfill({ json: state.model });
    if (path === `${base}/profiling/options`) return route.fulfill({ json: state.options });
    if (request.method() === "POST") posts.push({ path, key: request.headers()["idempotency-key"], body: request.postDataJSON() });
    if (path === `${base}/profiling-runs` && request.method() === "POST") {
      // Answer as the server's strict parser would, so a malformed shape fails the test instead of being accepted.
      const refusal = backendRunRefusal(request.postDataJSON());
      if (refusal) return route.fulfill({ status: 400, json: { code: "INVALID_REQUEST", message: refusal } });
      const answer = state.starts.shift()!;
      if (answer.status === 201) state.runs = [answer.json];
      return route.fulfill(answer);
    }
    if (path === `${base}/profiling-runs`) return route.fulfill({ json: { items: state.runs, nextOffset: null } });
    if (path === `${base}/profiling-runs/${runId}`) {
      const next = state.runSteps.length ? state.runSteps.shift()! : state.runs[0];
      state.runs = [next];
      if ((next as { state: string }).state === "SUCCEEDED") state.model = model(notReady("AWAITING_CONFIRMATION"));
      return route.fulfill({ json: next });
    }
    if (path === `${base}/resource-profiles` && request.method() === "POST") {
      state.profiles = [profile];
      state.model = model(ready);
      return route.fulfill({ status: 201, json: profile });
    }
    if (path === `${base}/resource-profiles`) return route.fulfill({ json: { items: state.profiles } });
    return route.fulfill({ status: 404, json: { message: `unmocked ${path}` } });
  });
  return posts;
}
const field = (page: Page, label: string) => page.getByLabel(label, { exact: true });
async function choose(page: Page, label: string, option: string) {
  const box = page.getByRole("combobox", { name: label, exact: true });
  await page.locator(".kel-form-item").filter({ has: box }).locator(".kel-select").click();
  await page.locator(`[id="${await box.getAttribute("aria-controls")}"]`).getByRole("option", { name: option }).click();
}
async function fillRun(page: Page) {
  await choose(page, "시험 노드", "jetson-01 · arm64 · CPU 6000m · 메모리 8Gi · nvidia.com/gpu.shared 용량 1 공유 슬롯");
  await field(page, "CPU 할당").fill("2");
  await field(page, "메모리 할당").fill("4Gi");
  await choose(page, "가속기", "nvidia.com/gpu.shared · 노드 용량 1 공유 슬롯");
  await field(page, "포트").fill("8080");
  await field(page, "요청 경로").fill("/predict");
  await field(page, "추론 요청 본문").fill('{"pixels":[0,1]}');
}

test("register → measure under a real inference load → confirm the recommendation → usable", async ({ page }, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  const state: State = { model: model(notReady("NO_MEASUREMENT")), runs: [], profiles: [], options,
    runSteps: [run("MEASURING"), cleaningUp, succeeded], starts: [{ status: 409, json: { code: "PROFILING_NODE_BUSY", message: "jetson-01 노드에서 다른 측정이 진행 중입니다." } }, { status: 201, json: run("STARTING") }] };
  const posts = mock(page, state);
  await page.goto(`/ui/main/ai-models?model=${modelId}`);
  const panel = page.getByRole("region", { name: "자원 요구량" });
  await expect(panel).toContainText("측정 전");
  await expect(panel).toContainText("측정이 성공하고 요구량을 확정하면 사용 가능합니다.");

  const startButton = panel.getByRole("button", { name: "측정 시작" });
  await startButton.click();
  const drawer = page.getByRole("dialog", { name: "자원 측정 시작" });
  await expect(drawer).toBeVisible();
  await expect(drawer).toContainText(options.image.reference);
  // Allocation and load are not invented; only the server's timing defaults are filled.
  for (const label of ["CPU 할당", "메모리 할당", "포트"]) await expect(field(page, label)).toHaveValue("");
  await expect(field(page, "측정 시간(초)")).toHaveValue("300");
  // A node whose architecture differs from the image is offered but cannot be chosen.
  const nodeBox = page.getByRole("combobox", { name: "시험 노드", exact: true });
  await page.locator(".kel-form-item").filter({ has: nodeBox }).locator(".kel-select").click();
  await expect(page.locator(`[id="${await nodeBox.getAttribute("aria-controls")}"]`).getByRole("option", { name: /server-01 .* 이미지 아키텍처와 다름/ }))
    .toHaveAttribute("aria-disabled", "true");
  await page.locator(".kel-form-item").filter({ has: nodeBox }).locator(".kel-select").click();
  await fillRun(page);
  await shot(page, testInfo.outputPath("start.png"));
  const submit = drawer.getByRole("button", { name: "측정 시작" });
  await expect(submit).toBeInViewport();
  await submit.click();
  await expect(drawer.getByRole("alert")).toContainText("다른 측정이 진행 중입니다.");
  await expect(field(page, "추론 요청 본문")).toHaveValue('{"pixels":[0,1]}');
  await submit.click();
  await expect(drawer).toBeHidden();
  const starts = posts.filter(p => p.path.endsWith("/profiling-runs"));
  expect(starts).toHaveLength(2);
  expect(starts[1].body).toEqual(expectedRequest);
  expect(starts[1].key).toBe(starts[0].key); // the unchanged request is retried with the same Idempotency-Key

  const progress = page.getByRole("status", { name: "진행 중인 측정" });
  await expect(progress).toContainText("부하 측정 중", { timeout: 8000 });
  await expect(progress).toContainText("120건 · 성공 120 · 실패 0");
  await expect(panel.getByRole("button", { name: /측정 시작|다시 측정/ })).toHaveCount(0);

  const confirmForm = page.getByRole("form", { name: "측정 결과 확인과 요구량 확정" });
  await expect(confirmForm).toBeVisible({ timeout: 8000 });
  // Until the temporary Pod is confirmed gone, a new run cannot start; polling then releases it.
  const again = panel.getByRole("button", { name: "다시 측정" });
  await expect(panel.getByRole("status").filter({ hasText: "임시 Pod를 정리하는 중" })).toBeVisible();
  await expect(again).toBeDisabled();
  await expect(again).toBeEnabled({ timeout: 8000 });
  await expect(panel.getByText("임시 Pod를 정리하는 중")).toHaveCount(0);
  // A shared GPU is counted in slots of that resource, not physical GPUs.
  await expect(confirmForm).toContainText("nvidia.com/gpu.shared 1 공유 슬롯");
  await expect(panel).toContainText("확정 대기");
  await expect(confirmForm).toContainText("성공률 100%");
  await expect(field(page, "CPU 요청")).toHaveValue("460m");
  await expect(field(page, "CPU 최소")).toHaveValue("");
  await expect(field(page, "메모리 최대")).toHaveValue("1Gi");
  await expect(confirmForm.locator("fieldset").first()).toContainText("측정 추천");
  await field(page, "메모리 요청").fill("768Mi");
  await expect(confirmForm.locator("fieldset").nth(1)).toContainText("사용자 지정");
  await shot(page, testInfo.outputPath("confirm.png"));
  await confirmForm.getByRole("button", { name: "요구량 확정" }).click();

  await expect(panel).toContainText("사용 가능");
  const table = page.getByRole("table", { name: "확정 자원 요구량" });
  await expect(table).toContainText("460m");
  await expect(table).toContainText("768Mi");
  await expect(table).toContainText("시험 할당");
  await expect(table).toContainText("1 공유 슬롯");
  const confirmed = posts.find(p => p.path.endsWith("/resource-profiles"))!;
  expect(confirmed.body).toEqual({
    runId,
    cpu: { minimumMillicores: null, requestMillicores: 460, maximumMillicores: 900, basis: "RECOMMENDED" },
    memory: { minimumBytes: null, requestBytes: 805306368, maximumBytes: 1073741824, basis: "MANUAL" },
    accelerators: [{ resourceName: "nvidia.com/gpu.shared", count: 1, basis: "TEST_ALLOCATION" }],
  });
  // The list shows the confirmed requirement of a model registered without one.
  const list = page.locator(".ks-data-table-wrapper");
  await expect(list).toContainText("사용 가능");
  await expect(list).toContainText("460m · 768Mi");
  await expect(list).toContainText("nvidia.com/gpu.shared");
  await expect(list).toContainText("1 공유 슬롯");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true);
  await shot(page, testInfo.outputPath("ready.png"));
  expect(errors).toEqual([]);
});

test("a failed re-measurement is reported while the confirmed revision stays in use", async ({ page }) => {
  const failed = run("FAILED", { id: "00000000-0000-4000-8000-0000000000e2", failure: { code: "OOM_KILLED", message: "시험 메모리 4Gi를 넘었습니다." } });
  mock(page, { model: model(ready), runs: [failed, succeeded], profiles: [profile], options, runSteps: [], starts: [] });
  await page.goto(`/ui/main/ai-models?model=${modelId}`);
  const panel = page.getByRole("region", { name: "자원 요구량" });
  await expect(panel).toContainText("사용 가능");
  await expect(panel).toContainText("메모리 할당 초과(OOMKilled) — 시험 메모리 4Gi를 넘었습니다.");
  await expect(panel).toContainText("확정된 r1 요구량은 그대로 유지됩니다.");
  await expect(page.getByRole("table", { name: "확정 자원 요구량" })).toContainText("460m");
  await expect(page.getByRole("form", { name: "측정 결과 확인과 요구량 확정" })).toHaveCount(0);
  await expect(panel.getByRole("button", { name: "다시 측정" })).toBeVisible();
});

test("when the server cannot measure, the reason is shown and nothing can be started", async ({ page }) => {
  mock(page, { model: model(notReady("NO_MEASUREMENT")), runs: [], profiles: [], runSteps: [], starts: [],
    options: { ...options, enabled: false, disabledReason: "PROMETHEUS_DISABLED" } });
  await page.goto(`/ui/main/ai-models?model=${modelId}`);
  const panel = page.getByRole("region", { name: "자원 요구량" });
  await panel.getByRole("button", { name: "측정 시작" }).click();
  const drawer = page.getByRole("dialog", { name: "자원 측정 시작" });
  await expect(drawer.getByRole("alert")).toContainText("Prometheus 연결이 설정되지 않았습니다.");
  await expect(drawer.getByRole("button", { name: "측정 시작" })).toBeDisabled();
  await page.keyboard.press("Escape");
  await expect(drawer).toBeHidden();
  await expect(panel.getByRole("button", { name: "측정 시작" })).toBeFocused();
});

test("on a phone the three requirement fields of the confirmation stay readable side by side", async ({ page }) => {
  mock(page, { model: model(notReady("AWAITING_CONFIRMATION")), runs: [succeeded], profiles: [], options, runSteps: [], starts: [] });
  await page.goto(`/ui/main/ai-models?model=${modelId}`);
  const width = page.viewportSize()!.width;
  for (const label of ["CPU 최소", "CPU 요청", "CPU 최대", "메모리 최소", "메모리 요청", "메모리 최대"]) {
    const box = (await field(page, label).boundingBox())!;
    expect(box.x + box.width).toBeLessThanOrEqual(width);
    expect(box.width).toBeGreaterThanOrEqual(64);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true);
});

// The HTTP modes other than JSON, driven through the form; each outgoing request must pass the server's parser.
const started = { status: 201, json: run("STARTING") };
const httpBase = { type: "HTTP", port: 8080, path: "/predict", concurrency: 2, requestTimeoutSeconds: 10, readinessTimeoutSeconds: 300 };
for (const mode of [
  { name: "GET sends no content type or body", fill: async (page: Page) => {
      await choose(page, "요청 방식", "GET");
      await expect(field(page, "추론 요청 본문")).toHaveCount(0);
    }, load: { ...httpBase, method: "GET" } },
  { name: "text/plain sends the typed text as body", fill: async (page: Page) => {
      await choose(page, "본문 형식", "text/plain");
      await field(page, "추론 요청 본문").fill("3 4 5");
    }, load: { ...httpBase, method: "POST", contentType: "text/plain", body: "3 4 5" } },
  { name: "application/octet-stream sends a picked file as bodyBase64", fill: async (page: Page) => {
      await choose(page, "본문 형식", "application/octet-stream");
      await expect(field(page, "추론 요청 본문")).toHaveCount(0);
      await page.getByLabel("추론 입력 파일").setInputFiles({ name: "digit.bin", mimeType: "application/octet-stream", buffer: Buffer.from([0, 1, 2, 255]) });
      await expect(field(page, "추론 요청 본문(Base64)")).toHaveValue("AAEC/w==");
      await expect(page.locator("#profiling-binary-note")).toHaveText("digit.bin · 디코딩 후 4 bytes");
    }, load: { ...httpBase, method: "POST", contentType: "application/octet-stream", bodyBase64: "AAEC/w==" } },
]) {
  test(`HTTP ${mode.name}`, async ({ page }) => {
    const state: State = { model: model(notReady("NO_MEASUREMENT")), runs: [], profiles: [], options, runSteps: [run("STARTING")], starts: [started] };
    const posts = mock(page, state);
    await page.goto(`/ui/main/ai-models?model=${modelId}`);
    await page.getByRole("region", { name: "자원 요구량" }).getByRole("button", { name: "측정 시작" }).click();
    const drawer = page.getByRole("dialog", { name: "자원 측정 시작" });
    await expect(drawer).toBeVisible();
    await choose(page, "시험 노드", "jetson-01 · arm64 · CPU 6000m · 메모리 8Gi · nvidia.com/gpu.shared 용량 1 공유 슬롯");
    await field(page, "CPU 할당").fill("2");
    await field(page, "메모리 할당").fill("4Gi");
    await field(page, "포트").fill("8080");
    await field(page, "요청 경로").fill("/predict");
    await mode.fill(page);
    await drawer.getByRole("button", { name: "측정 시작" }).click();
    await expect(drawer).toBeHidden();
    const starts = posts.filter(p => p.path.endsWith("/profiling-runs"));
    expect(starts).toHaveLength(1);
    expect(starts[0].body).toEqual({ nodeName: "jetson-01", allocation: { cpuMillicores: 2000, memoryBytes: 4294967296, accelerator: null }, durationSeconds: 300, load: mode.load });
  });
}

test("an invalid binary body is refused in the form before anything is sent", async ({ page }) => {
  const posts = mock(page, { model: model(notReady("NO_MEASUREMENT")), runs: [], profiles: [], options, runSteps: [], starts: [started] });
  await page.goto(`/ui/main/ai-models?model=${modelId}`);
  await page.getByRole("region", { name: "자원 요구량" }).getByRole("button", { name: "측정 시작" }).click();
  const drawer = page.getByRole("dialog", { name: "자원 측정 시작" });
  await choose(page, "시험 노드", "jetson-01 · arm64 · CPU 6000m · 메모리 8Gi · nvidia.com/gpu.shared 용량 1 공유 슬롯");
  await field(page, "CPU 할당").fill("2");
  await field(page, "메모리 할당").fill("4Gi");
  await field(page, "포트").fill("8080");
  await choose(page, "본문 형식", "application/octet-stream");
  await field(page, "추론 요청 본문(Base64)").fill("not base64!");
  await expect(page.locator("#profiling-binary-note")).toHaveText("표준 Base64가 아닙니다.");
  await drawer.getByRole("button", { name: "측정 시작" }).click();
  await expect(drawer.getByRole("alert")).toContainText("표준 Base64");
  expect(posts.filter(p => p.path.endsWith("/profiling-runs"))).toHaveLength(0);
});

test("a GPU run whose devices the exporters do not report shows the server's UNSUPPORTED reason and the notices", async ({ page }, testInfo) => {
  const reason = "시험 노드에서 기존 exporter가 보고하는 가속기 장치를 찾지 못했습니다. 가속기 수량은 시험 할당값만 기록합니다.";
  const notice = "CPU p95가 시험 할당량의 90% 이상입니다. CPU limit에 걸려 수요가 잘렸을 수 있으니 더 큰 CPU 할당량으로 재측정하세요.";
  const unobserved = { ...succeeded, result: { ...succeeded.result, devices: [], deviceStatus: { status: "UNSUPPORTED", reason }, notices: [notice] } };
  mock(page, { model: model(notReady("AWAITING_CONFIRMATION")), runs: [unobserved], profiles: [], options, runSteps: [], starts: [] });
  await page.goto(`/ui/main/ai-models?model=${modelId}`);
  const form = page.getByRole("form", { name: "측정 결과 확인과 요구량 확정" });
  await expect(form).toContainText(`가속기 장치 지표 없음 — ${reason}`);
  await expect(form.getByRole("list", { name: "측정 결과 주의" })).toContainText(notice);
  await expect(form.getByText(notice)).toBeVisible();
  await expect(form.getByText("단일 시험의 관측값이며 이론적 최소 요구량이 아닙니다.")).toBeVisible();
  await expect(form).not.toContainText("장치 관측 0개");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true);
  await form.screenshot({ path: testInfo.outputPath("unsupported-device.png") });
});

test("a CPU-only run does not suggest a failed accelerator measurement", async ({ page }) => {
  const cpuOnly = { ...succeeded, allocation: { ...expectedRequest.allocation, accelerator: null },
    result: { ...succeeded.result, devices: [], deviceStatus: { status: "NOT_REQUESTED", reason: "시험에 가속기를 할당하지 않았습니다." },
      recommendation: { ...succeeded.result.recommendation, accelerators: [] } } };
  mock(page, { model: model(notReady("AWAITING_CONFIRMATION")), runs: [cpuOnly], profiles: [], options, runSteps: [], starts: [] });
  await page.goto(`/ui/main/ai-models?model=${modelId}`);
  const form = page.getByRole("form", { name: "측정 결과 확인과 요구량 확정" });
  await expect(form).toBeVisible();
  await expect(form).not.toContainText("가속기");
  await expect(form).not.toContainText("시험에 가속기를 할당하지 않았습니다.");
});

async function shot(page: Page, path: string) {
  await page.screenshot({ path });
}
