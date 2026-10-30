import { test, expect, type Page } from "@playwright/test";

// Every control-plane call is answered in the browser; the console's API origins should point at unused local ports.
const now = "2026-10-29T15:00:00Z";
const serviceId = "00000000-0000-4000-8000-000000000011";
const analysisId = "00000000-0000-4000-8000-0000000000a1";
const profileId = "00000000-0000-4000-8000-0000000000b1";
const service = { id: serviceId, key: "detector", version: "1.2.3", kind: "SERVICE", digest: "service-digest", createdAt: now,
  spec: { platform: { os: "linux", architectures: ["amd64", "arm64"] }, inputs: {}, outputs: {} } };
const capabilities = { namespaces: ["edgeai"], prometheus: true, krr: true, krrVersion: "v1.31.1", maxWindowHours: 336, minWindowMinutes: 5,
  maxHistoryHours: 336, workloadKinds: ["Deployment", "StatefulSet", "DaemonSet", "Job", "CronJob", "Pod"] };
const nodeMetrics = { status: "AVAILABLE", observedAt: now, maxAgeSeconds: 90, unmappedSamples: 0,
  items: [{ nodeName: "gpu-node-1", measurements: [], readiness: null,
    accelerators: [{ device: "pci:0000:02:00.0", kind: "GPU", vendor: "NVIDIA", model: "GeForce RTX 5080", observedAt: now, fresh: true }] }] };
const usage = (unit: string, values: number[] | null, status = "AVAILABLE", reason: string | null = null) => ({
  status, unit, min: values?.[0] ?? null, average: values?.[1] ?? null, p95: values?.[2] ?? null, max: values?.[3] ?? null,
  samples: values ? 240 : 0, firstSample: values ? "2026-10-29T14:00:00Z" : null, lastSample: values ? now : null, reason });
const target = { namespace: "edgeai", kind: "Deployment", name: "detector", container: "model", pod: null, podUid: null };
const measuredAnalysis = {
  id: analysisId, serviceProfileVersionId: serviceId, service: { profileVersionId: serviceId, key: "detector", version: "1.2.3", digest: "service-digest" },
  createdAt: now, target, executionRef: null, window: { start: "2026-10-29T14:00:00Z", end: now },
  measurement: { method: "cadvisor", aggregation: "PER_POD_CONTAINER", stepSeconds: 15,
    pods: [{ name: "detector-7d9-abc", uid: "pod-uid", node: "gpu-node-1" }, { name: "detector-7d9-def", uid: "pod-uid-2", node: "gpu-node-1" }],
    cpu: usage("millicores", [12, 80.5, 150.2, 190]), memory: usage("bytes", [3.0e8, 4.0e8, 4.5e8, 4.6e8]),
    perPod: [{ pod: "detector-7d9-abc", samples: 240, cpuP95Millicores: 150.2, cpuMaxMillicores: 190, memoryMaxBytes: 4.6e8 },
      { pod: "detector-7d9-def", samples: 240, cpuP95Millicores: 60, cpuMaxMillicores: 75, memoryMaxBytes: 3.1e8 }] },
  devices: [{ node: "gpu-node-1", kind: "GPU", device: "pci:0000:02:00.0", exporter: "dcgm-exporter", model: "NVIDIA GeForce RTX 5080",
    attribution: "NODE_DEVICE_UNKNOWN_OWNER", perWorkload: false, status: "AVAILABLE", utilization: usage("percent", [3, 41.5, 88, 97]),
    memoryUsed: usage("bytes", [1e9, 2e9, 3.5e9, 3.9e9]), memoryTotalBytes: 1.7e10, reason: "exporter가 Pod 소유 정보를 제공하지 않아 장치 전체 사용량만 표시합니다." }],
  recommendation: { engine: "KRR", status: "AVAILABLE", reason: null, window: { historyHours: 24, start: "2026-10-28T15:00:00Z", end: now },
    cpu: { requestMillicores: 177, limitMillicores: null, info: null }, memory: { requestBytes: 530579456, limitBytes: 530579456, info: null },
    podCount: 1, warnings: [], provenance: { tool: "krr", version: "v1.31.1" } },
  limitations: [],
};
const emptyAnalysis = { ...measuredAnalysis, devices: [],
  measurement: { ...measuredAnalysis.measurement, pods: [], perPod: [], cpu: usage("millicores", null, "NO_DATA", "관측 구간에 해당 지표 시계열이 없습니다."),
    memory: usage("bytes", null, "NO_DATA", "관측 구간에 해당 지표 시계열이 없습니다.") },
  recommendation: { ...measuredAnalysis.recommendation, status: "UNAVAILABLE", reason: "KRR bridge에 연결할 수 없습니다.", window: null,
    cpu: { requestMillicores: null, limitMillicores: null, info: null }, memory: { requestBytes: null, limitBytes: null, info: null } } };
// The save document an operator should produce from the actions below (independent literal).
const expectedMeasured = {
  serviceProfileVersionId: serviceId, analysisId, inputConditions: { batchSize: "1" },
  compatibility: { architectures: ["amd64"], runtime: { name: "onnxruntime", version: "1.20.1" } },
  resources: {
    cpu: { minMillicores: 100, requestMillicores: 177, maxMillicores: 1000, basis: "RECOMMENDED" },
    memory: { minBytes: 268435456, requestBytes: 530579456, maxBytes: 1073741824, basis: "RECOMMENDED" },
    accelerators: [{ kind: "GPU", resourceName: "nvidia.com/gpu", unit: "COUNT", min: 1, request: 1, max: 1, deviceMemoryBytes: 4294967296, vendor: "NVIDIA", models: ["RTX 5080"] }],
  },
  note: null,
};
const saved = (body: typeof expectedMeasured, measurementStatus: string) => ({ ...body, id: profileId, revision: 1, measurementStatus, digest: "sha256:" + "a".repeat(64), createdAt: now,
  service: measuredAnalysis.service, compatibility: { os: "linux", ...body.compatibility }, evidence: null });
const handoff = { apiVersion: "edgeai.io/resource-profile-handoff/v1", kind: "ResourceProfileHandoff",
  profile: { id: profileId, revision: 1, digest: "sha256:" + "a".repeat(64), createdAt: now, measurementStatus: "MEASURED" },
  service: measuredAnalysis.service, compatibility: {}, inputConditions: {},
  resources: { cpu: { unit: "millicores", min: 100, request: 177, max: 1000, basis: "RECOMMENDED", kubernetes: { min: "100m", request: "177m", max: "1000m" } },
    memory: { unit: "bytes", min: 268435456, request: 530579456, max: 1073741824, basis: "RECOMMENDED", kubernetes: { min: "256Mi", request: "506Mi", max: "1Gi" } }, accelerators: [] },
  mappings: { verticalPodAutoscaler: { controlledResources: ["cpu", "memory"], recommendedUpdateMode: "Off" } }, evidence: {}, limitations: [] };

type Hold = { pods?: Promise<void>; workloads?: Promise<void> };
function mock(page: Page, analyses: unknown[], saves: { status: number; json: unknown }[], hold: Hold = {}) {
  const posts: { path: string; key?: string; csrf?: string; body: unknown }[] = [];
  const discoveries: number[] = [];
  const revisions: unknown[] = [];
  void page.route("**/api/control-plane/**", async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname.replace("/api/control-plane/", "");
    if (path === "csrf") return route.fulfill({ json: { token: "management-token" } });
    if (path === "workflow-csrf") return route.fulfill({ json: { token: "workflow-token" } });
    if (path === "profiles/SERVICE") return route.fulfill({ json: { items: [service], nextOffset: null } });
    if (path === "node-metrics") return route.fulfill({ json: nodeMetrics });
    if (path === "resource-profiles/capabilities") return route.fulfill({ json: capabilities });
    if (path === "resource-profiles/workloads") {
      expect(url.searchParams.get("namespace")).toBe("edgeai");
      // Discovery follows the selected observation window; a Job that ended days ago only exists in a long window.
      const hours = (Date.parse(url.searchParams.get("end")!) - Date.parse(url.searchParams.get("start")!)) / 3600_000;
      discoveries.push(Math.round(hours));
      await hold.workloads;
      const items = [{ namespace: "edgeai", kind: "Deployment", name: "detector", containers: ["model"] }];
      if (hours > 48) items.push({ namespace: "edgeai", kind: "Job", name: "nightly-batch", containers: ["batch"] });
      return route.fulfill({ json: { items } });
    }
    if (path === "resource-profiles/pods") {
      await hold.pods;
      return route.fulfill({ json: { items: [{ name: "detector-7d9-abc", uid: "pod-uid", node: "gpu-node-1" }] } });
    }
    if (request.method() === "POST") {
      posts.push({ path, key: request.headers()["idempotency-key"], csrf: request.headers()["x-csrf-token"], body: request.postDataJSON() });
      if (path === "resource-profiles/analyses") return route.fulfill({ status: 201, json: analyses.shift() });
      const answer = saves.shift()!;
      if (answer.status === 201) revisions.unshift(answer.json);
      return route.fulfill(answer);
    }
    if (path === "resource-profiles") return route.fulfill({ json: { items: revisions, nextOffset: null } });
    if (path === `resource-profiles/${profileId}/handoff`) return route.fulfill({ json: handoff });
    return route.fulfill({ status: 404, json: { message: `unmocked ${path}` } });
  });
  return Object.assign(posts, { discoveries });
}
async function options(page: Page, name: string) {
  const combobox = page.getByRole("combobox", { name, exact: true });
  await page.locator(".kel-form-item").filter({ has: combobox }).locator(".kel-select").click();
  return page.locator(`[id="${await combobox.getAttribute("aria-controls")}"]`);
}
async function pick(page: Page, label: string, option: string) {
  await (await options(page, label)).getByRole("option", { name: option, exact: true }).click();
}
async function shot(page: Page, path: string) {
  const viewport = page.viewportSize()!;
  await page.setViewportSize({ width: viewport.width, height: 3600 });
  await page.screenshot({ path });
  await page.setViewportSize(viewport);
}
async function chooseTarget(page: Page) {
  await pick(page, "서비스 실행 모듈", "detector");
  await expect(page.getByText(`버전 ID ${serviceId}`)).toBeVisible();
  await pick(page, "워크로드", "Deployment · detector");
  await expect(page.getByRole("combobox", { name: "컨테이너", exact: true })).toBeVisible();
}

test("measured usage and the KRR recommendation are reviewed, confirmed and saved as a revision with its 2-1 handoff", async ({ page }, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  const posts = mock(page, [measuredAnalysis], [{ status: 201, json: saved(expectedMeasured, "MEASURED") }]);
  if (testInfo.project.name === "desktop") {
    // Reached from AI 모델 관리 now that it is no longer a menu item.
    await page.goto("/ui/main/ai-models");
    await page.getByRole("link", { name: "서비스 자원 프로파일" }).click();
    await expect(page).toHaveURL(/\/ui\/main\/resource-profiles/);
  } else await page.goto("/ui/main/resource-profiles");
  await chooseTarget(page);
  await pick(page, "장치 관측(선택)", "gpu-node-1 · GPU · NVIDIA GeForce RTX 5080");
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "분석", exact: true }).click();
  await expect(page.getByRole("heading", { name: "실측·추천 확인과 요구량 확정" })).toBeFocused();
  const analysisPost = posts.find(p => p.path === "resource-profiles/analyses")!;
  expect(analysisPost.csrf).toBe("management-token");
  expect(analysisPost.body).toMatchObject({ serviceProfileVersionId: serviceId, target: { namespace: "edgeai", kind: "Deployment", name: "detector", container: "model", pod: null, podUid: null },
    devices: [{ node: "gpu-node-1", kind: "GPU", device: "pci:0000:02:00.0" }], recommendation: { historyHours: 24 } });

  const table = page.getByRole("table", { name: "CPU·메모리 실측·추천·확정값" });
  await expect(table.getByRole("row", { name: /CPU/ })).toContainText("12m / 80.5m / 150.2m / 190m");
  await expect(table.getByRole("row", { name: /CPU/ })).toContainText("177m / 제한 없음");
  await expect(table.getByRole("row", { name: /메모리/ })).toContainText("506 MiB / 506 MiB");
  // Two replicas: the table shows the largest single Pod (190m), not 190m+75m, and each replica is listed.
  await page.getByText("복제본별 실측(2개 Pod)").click();
  await expect(page.getByText("detector-7d9-def · CPU p95 60m / 최대 75m")).toBeVisible();
  const devices = page.getByRole("table", { name: "GPU·NPU 장치 관측" });
  await expect(devices).toContainText("장치 전체 · 소유 미확인");
  await expect(devices).toContainText("41.5% / 88% / 97%");

  await page.getByRole("button", { name: "CPU KRR 추천을 요청값으로 사용" }).click();
  await page.getByRole("button", { name: "메모리 KRR 추천을 요청값으로 사용" }).click();
  await expect(page.getByLabel("CPU 요청", { exact: true })).toHaveValue("177m");
  await page.getByLabel("CPU 최소", { exact: true }).fill("100m");
  await page.getByLabel("CPU 최대", { exact: true }).fill("1");
  await page.getByLabel("메모리 최소", { exact: true }).fill("256Mi");
  await page.getByLabel("메모리 최대", { exact: true }).fill("1Gi");
  await expect(table.getByRole("row", { name: /CPU/ })).toContainText("KRR 추천");
  await page.getByRole("button", { name: "가속기 요구량 추가" }).click();
  for (const [label, value] of [["가속기 1 최소", "1"], ["가속기 1 요청", "1"], ["가속기 1 최대", "1"], ["가속기 1 장치 메모리 요구량", "4Gi"], ["가속기 1 벤더", "NVIDIA"], ["가속기 1 호환 모델", "RTX 5080"]])
    await page.getByLabel(label, { exact: true }).fill(value);
  // The original Kestra checkbox hides its input; the visible label toggles it.
  await page.locator(".kel-checkbox").filter({ hasText: "arm64" }).click();
  await expect(page.getByRole("checkbox", { name: "arm64" })).not.toBeChecked();
  await page.getByLabel("런타임 이름", { exact: true }).fill("onnxruntime");
  await page.getByLabel("런타임 버전", { exact: true }).fill("1.20.1");
  await page.getByLabel("입력 조건 (한 줄에 key=value)", { exact: true }).fill("batchSize=1");
  await shot(page, testInfo.outputPath("review.png"));
  await page.getByRole("button", { name: "새 revision 저장" }).click();

  await expect(page.getByRole("heading", { name: "revision 1 · 2-1 전달 계약" })).toBeFocused();
  const save = posts.find(p => p.path === "resource-profiles")!;
  expect(save.body).toEqual(expectedMeasured);
  expect(save.key).toMatch(/^[0-9a-f-]{36}$/);
  await expect(page.getByRole("table", { name: "저장된 자원 프로파일 revision" })).toContainText("100m / 177m / 1000m · KRR 추천");
  await expect(page.getByLabel("2-1 전달 계약 JSON")).toContainText('"apiVersion": "edgeai.io/resource-profile-handoff/v1"');
  await expect(page.getByText("/api/v1/resource-profiles/services/detector/versions/1.2.3/revisions/1/handoff")).toBeVisible();
  await shot(page, testInfo.outputPath("saved.png"));
  expect(errors).toEqual([]);
});

test("without measurements the profile is entered manually; invalid ranges are named and a failed save keeps the key", async ({ page }, testInfo) => {
  const manual = { ...expectedMeasured, analysisId: null, inputConditions: {}, compatibility: { architectures: ["amd64", "arm64"], runtime: null },
    resources: { cpu: { minMillicores: 100, requestMillicores: 250, maxMillicores: 2000, basis: "MANUAL" },
      memory: { minBytes: 268435456, requestBytes: 536870912, maxBytes: 1073741824, basis: "MANUAL" }, accelerators: [] } };
  const posts = mock(page, [], [{ status: 503, json: { message: "자원 프로파일 저장소를 사용할 수 없습니다." } }, { status: 201, json: saved(manual as typeof expectedMeasured, "UNMEASURED") }]);
  await page.goto("/ui/main/resource-profiles");
  await pick(page, "서비스 실행 모듈", "detector");
  await page.getByRole("button", { name: "측정 없이 직접 입력" }).click();
  await expect(page.getByText("분석 없이 직접 입력합니다.")).toBeVisible();
  await expect(page.getByRole("button", { name: "CPU KRR 추천을 요청값으로 사용" })).toHaveCount(0);
  await page.getByLabel("CPU 최소", { exact: true }).fill("300m");
  await page.getByLabel("CPU 요청", { exact: true }).fill("250m");
  await page.getByLabel("CPU 최대", { exact: true }).fill("2");
  await page.getByLabel("메모리 최소", { exact: true }).fill("256Mi");
  await page.getByLabel("메모리 요청", { exact: true }).fill("512Mi");
  await page.getByLabel("메모리 최대", { exact: true }).fill("1Gi");
  const save = page.getByRole("button", { name: "새 revision 저장" });
  await save.click();
  await expect(page.getByRole("alert").filter({ hasText: "CPU은 최소 ≤ 요청 ≤ 최대여야 합니다." })).toBeVisible();
  await page.getByLabel("CPU 최소", { exact: true }).fill("NaN");
  await save.click();
  await expect(page.getByRole("alert").filter({ hasText: "CPU 최소값을" })).toBeVisible();
  expect([...posts]).toEqual([]);
  await page.getByLabel("CPU 최소", { exact: true }).fill("100m");
  await save.click();
  await expect(page.getByRole("alert").filter({ hasText: "자원 프로파일 저장소를 사용할 수 없습니다." })).toBeVisible();
  await expect(page.getByLabel("CPU 요청", { exact: true })).toHaveValue("250m");
  await save.click();
  await expect(page.getByRole("heading", { name: "revision 1 · 2-1 전달 계약" })).toBeFocused();
  expect(posts.map(p => p.body)).toEqual([manual, manual]);
  expect(posts[0].key).toBe(posts[1].key);
  await expect(page.getByRole("table", { name: "저장된 자원 프로파일 revision" })).toContainText("미측정");
  await shot(page, testInfo.outputPath("manual.png"));
});

test("missing measurements and an unavailable KRR show their reasons and offer nothing to adopt", async ({ page }) => {
  mock(page, [emptyAnalysis], []);
  await page.goto("/ui/main/resource-profiles");
  await chooseTarget(page);
  await page.getByRole("button", { name: "분석", exact: true }).click();
  const table = page.getByRole("table", { name: "CPU·메모리 실측·추천·확정값" });
  await expect(table.getByRole("row", { name: /CPU/ })).toContainText("데이터 없음 — / — / — / —");
  await expect(table.getByRole("row", { name: /CPU/ })).toContainText("관측 구간에 해당 지표 시계열이 없습니다.");
  await expect(table.getByRole("row", { name: /CPU/ })).toContainText("조회 불가");
  await expect(table).toContainText("KRR bridge에 연결할 수 없습니다.");
  await expect(page.getByRole("button", { name: /p95를 요청값으로 사용|KRR 추천을 요청값으로 사용/ })).toHaveCount(0);
  await expect(table.getByRole("row", { name: /CPU/ })).toContainText("사용자 지정");
});

test("Analyze works while the Pod lookup is still pending and never locks the form", async ({ page }) => {
  let release!: () => void;
  const posts = mock(page, [measuredAnalysis], [], { pods: new Promise<void>((resolve) => (release = resolve)) });
  await page.goto("/ui/main/resource-profiles");
  await chooseTarget(page);
  // The single container is auto-selected, which starts the (held) Pod lookup; analyze before it answers.
  await page.getByRole("button", { name: "분석", exact: true }).click();
  await expect(page.getByRole("heading", { name: "실측·추천 확인과 요구량 확정" })).toBeFocused();
  expect(posts.filter((p) => p.path === "resource-profiles/analyses")).toHaveLength(1);
  release();
  await expect(page.getByRole("button", { name: "분석", exact: true })).toBeEnabled();
});

test("workload discovery follows the selected window so a Job seen only days ago can be analysed", async ({ page }) => {
  const posts = mock(page, [{ ...measuredAnalysis, target: { ...target, kind: "Job", name: "nightly-batch", container: "batch" } }], []);
  await page.goto("/ui/main/resource-profiles");
  await pick(page, "서비스 실행 모듈", "detector");
  await expect.poll(() => posts.discoveries.at(-1)).toBe(1);
  const workloadOptions = await options(page, "워크로드");
  await expect(workloadOptions.getByRole("option", { name: "Job · nightly-batch", exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await pick(page, "관측 구간", "최근 168시간");
  await expect.poll(() => posts.discoveries.at(-1)).toBe(168);
  await pick(page, "워크로드", "Job · nightly-batch");
  await page.getByRole("button", { name: "분석", exact: true }).click();
  await expect(page.getByRole("heading", { name: "실측·추천 확인과 요구량 확정" })).toBeVisible();
  const body = posts.find((p) => p.path === "resource-profiles/analyses")!.body as { target: unknown; window: { start: string; end: string } };
  expect(body.target).toMatchObject({ kind: "Job", name: "nightly-batch", container: "batch" });
  expect((Date.parse(body.window.end) - Date.parse(body.window.start)) / 3600_000).toBe(168);
});

test("changing the window drops the chosen Pod at once, even while discovery is still pending", async ({ page }) => {
  const hold: Hold = {};
  const posts = mock(page, [measuredAnalysis], [], hold);
  await page.goto("/ui/main/resource-profiles");
  await chooseTarget(page);
  await pick(page, "관측 구간", "최근 168시간");
  await expect.poll(() => posts.discoveries.at(-1)).toBe(168);
  await pick(page, "Pod 실행(선택)", "detector-7d9-abc · gpu-node-1");
  let release!: () => void;
  hold.workloads = new Promise<void>((resolve) => (release = resolve));
  await pick(page, "관측 구간", "최근 1시간");
  await expect.poll(() => posts.discoveries.at(-1)).toBe(1);
  // Discovery for the new window has not answered yet; analysing now must not carry the old Pod/UID.
  await page.getByRole("button", { name: "분석", exact: true }).click();
  await expect(page.getByRole("heading", { name: "실측·추천 확인과 요구량 확정" })).toBeVisible();
  const body = posts.find((p) => p.path === "resource-profiles/analyses")!.body as { target: { pod: unknown; podUid: unknown }; window: { start: string; end: string } };
  expect(body.target.pod).toBeNull();
  expect(body.target.podUid).toBeNull();
  expect((Date.parse(body.window.end) - Date.parse(body.window.start)) / 3600_000).toBe(1);
  release();
});
