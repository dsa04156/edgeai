import { test, expect, type Page } from "@playwright/test";

// Every control-plane call is answered in the browser (same pattern as the resource profile spec).
const now = "2026-10-30T05:00:00Z";
const host = "10.254.192.47:5000";
const digest = "sha256:f1e97a098403e830f18a39ad92f94b29dab057e38c11da6961a8170c45a6697f";
const otherDigest = "sha256:" + "e".repeat(64);
const modelId = "00000000-0000-4000-8000-0000000000d1";
const single = (reference: string, value = digest) => ({
  registry: host, repository: "edgeai/detector", reference, digest: value, mediaType: "application/vnd.oci.image.manifest.v1+json", kind: "MANIFEST",
  imageReference: `${host}/edgeai/detector@${value}`,
  platforms: [{ os: "linux", architecture: "amd64", variant: null, label: "linux/amd64", runnable: true, manifestDigest: value, mediaType: "application/vnd.oci.image.manifest.v1+json", size: 1829 }],
});
const expected = {
  modelKey: "forklift-detector", displayName: "지게차 탐지", modelVersion: "1.0.0", configName: "default", description: null,
  image: { repository: "edgeai/detector", tag: "1.0.0", digest, platform: { os: "linux", architecture: "amd64", variant: null } },
  execution: { command: null, args: null }, inputConditions: { resolution: "640x640" }, runtime: { name: "onnxruntime", version: "1.20.1" },
  resources: { cpu: { minMillicores: 100, requestMillicores: 250, maxMillicores: 2000, basis: "MANUAL" },
    memory: { minBytes: 268435456, requestBytes: 536870912, maxBytes: 1073741824, basis: "MANUAL" }, accelerators: [] },
  resourceProfileId: null,
};
const entry = {
  id: modelId, digest: "sha256:" + "c".repeat(64), createdAt: now, measurementStatus: "UNMEASURED", resourceProfileId: null,
  modelKey: expected.modelKey, displayName: expected.displayName, modelVersion: "1.0.0", configName: "default", description: null,
  image: { registry: host, repository: "edgeai/detector", digest, reference: `${host}/edgeai/detector@${digest}`, mediaType: "application/vnd.oci.image.manifest.v1+json",
    kind: "MANIFEST", sourceTag: "1.0.0", platform: { os: "linux", architecture: "amd64", variant: null, label: "linux/amd64", runnable: true },
    platformManifestDigest: digest, platformReference: `${host}/edgeai/detector@${digest}`, platformManifestMediaType: "application/vnd.oci.image.manifest.v1+json",
    configDigest: "sha256:" + "f".repeat(64), configMediaType: "application/vnd.oci.image.config.v1+json", layersSize: 46000000, created: null, verifiedAt: now },
  execution: expected.execution, inputConditions: expected.inputConditions, runtime: expected.runtime,
  requirements: { source: "MANUAL", measurementStatus: "UNMEASURED", resources: expected.resources },
  resourceProfile: null, evidence: null, limitations: ["자원 요구량은 실행 측에 전달할 선언이며 자원 예약·분할·증강이나 실행 보장이 아닙니다."],
};

const sourceProfile = {
  id: "00000000-0000-4000-8000-0000000000b2", revision: 2, measurementStatus: "MEASURED", analysisId: null, digest: "sha256:" + "9".repeat(64), createdAt: now,
  serviceProfileVersionId: "00000000-0000-4000-8000-0000000000a2",
  service: { profileVersionId: "00000000-0000-4000-8000-0000000000a2", key: "detector-svc", version: "1.0.0", digest: "sha256:" + "8".repeat(64) },
  inputConditions: { resolution: "640x640" }, compatibility: { os: "linux", architectures: ["amd64"], runtime: { name: "onnxruntime", version: "1.20.1" } },
  resources: { cpu: { minMillicores: 100, requestMillicores: 177, maxMillicores: 2000, basis: "RECOMMENDED" },
    memory: { minBytes: 268435456, requestBytes: 450000000, maxBytes: 1073741824, basis: "MEASURED" }, accelerators: [] },
  note: null, evidence: {},
};
type Hold = { manifest?: Record<string, Promise<void>>; detail?: Record<string, Promise<void>>; service?: Promise<void> };
function mock(page: Page, saves: { status: number; json: unknown }[], options: { list?: { status: number; json: unknown }; hold?: Hold } = {}) {
  const posts: { key?: string; csrf?: string; body: unknown }[] = [];
  const models: unknown[] = [];
  void page.route("**/api/control-plane/**", async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname.replace("/api/control-plane/", "");
    if (path === "csrf") return route.fulfill({ json: { token: "management-token" } });
    if (path === "ai-models/registry") return route.fulfill({ json: { configured: true, host } });
    if (path === "ai-models/registry/repositories")
      return route.fulfill({ json: url.searchParams.get("last") ? { items: ["edgeai/detector"], next: null } : { items: ["digits-model-amd64"], next: "digits-model-amd64" } });
    if (path === "ai-models/registry/tags") {
      expect(url.searchParams.get("repository")).toBe("edgeai/detector");
      return route.fulfill({ json: { repository: "edgeai/detector", items: ["1.0.0", "slow"], next: null } });
    }
    if (path === "ai-models/registry/manifest") {
      const reference = url.searchParams.get("reference")!;
      await options.hold?.manifest?.[reference];
      if (reference === "missing") return route.fulfill({ status: 404, json: { code: "IMAGE_NOT_FOUND", message: "Registry에 해당 저장소·태그·digest가 없습니다." } });
      return route.fulfill({ json: single(reference, reference === "slow" ? otherDigest : digest) });
    }
    if (path === "ai-models" && request.method() === "POST") {
      posts.push({ key: request.headers()["idempotency-key"], csrf: request.headers()["x-csrf-token"], body: request.postDataJSON() });
      const answer = saves.shift()!;
      if (answer.status === 201) models.unshift(answer.json);
      return route.fulfill(answer);
    }
    if (path === "ai-models") return route.fulfill(options.list ?? { json: { items: models, nextOffset: null } });
    if (path === "resource-profiles") return route.fulfill({ json: { items: [sourceProfile], nextOffset: null } });
    if (path === "profiles/SERVICE/detector-svc/versions/1.0.0") {
      await options.hold?.service;
      return route.fulfill({ json: { id: sourceProfile.serviceProfileVersionId, key: "detector-svc", version: "1.0.0", kind: "SERVICE",
        spec: { image: `${host}/edgeai/detector@${digest}`, command: ["python3", "/app/serve.py"], args: [] } } });
    }
    const detailHold = Object.entries(options.hold?.detail ?? {}).find(([id]) => path === `ai-models/${id}`);
    if (detailHold) { await detailHold[1]; return route.fulfill({ json: { ...entry, id: detailHold[0], displayName: "이전 모델" } }); }
    if (path === `ai-models/${modelId}`) return route.fulfill({ json: entry });
    return route.fulfill({ status: 404, json: { message: `unmocked ${path}` } });
  });
  return Object.assign(posts, { models });
}
async function shot(page: Page, path: string) {
  const viewport = page.viewportSize()!;
  await page.setViewportSize({ width: viewport.width, height: 3200 });
  await page.screenshot({ path });
  await page.setViewportSize(viewport);
}
async function noPageOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true);
}
async function chooseImage(page: Page, reference = "1.0.0") {
  await page.getByLabel("저장소 직접 입력(목록에 없을 때)", { exact: true }).fill("edgeai/detector");
  await page.getByRole("button", { name: "입력한 저장소 사용" }).click();
  await page.getByLabel("태그 또는 digest 직접 입력", { exact: true }).fill(reference);
  await page.getByRole("button", { name: "이미지 확인" }).click();
}
async function fillModel(page: Page) {
  await page.getByLabel("모델 키", { exact: true }).fill("forklift-detector");
  await page.getByLabel("표시 이름", { exact: true }).fill("지게차 탐지");
  await page.getByLabel("모델 버전", { exact: true }).fill("1.0.0");
  await page.getByLabel("입력 조건(한 줄에 key=value)", { exact: true }).fill("resolution=640x640");
  await page.getByLabel("런타임 이름", { exact: true }).fill("onnxruntime");
  await page.getByLabel("런타임 버전", { exact: true }).fill("1.20.1");
  for (const [label, value] of [["CPU 최소", "100m"], ["CPU 요청", "250m"], ["CPU 최대", "2"], ["메모리 최소", "256Mi"], ["메모리 요청", "512Mi"], ["메모리 최대", "1Gi"]])
    await page.getByLabel(label, { exact: true }).fill(value);
}

test("the list is the first screen; registration verifies a registry digest, keeps the draft on failure and opens the saved detail", async ({ page }, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  const posts = mock(page, [{ status: 503, json: { code: "AI_MODEL_STORE_UNAVAILABLE", message: "AI 모델 저장소를 사용할 수 없습니다. 복구 후 다시 시도하세요." } }, { status: 201, json: entry }]);
  if (testInfo.project.name === "desktop") {
    await page.goto("/ui/main/resource-profiles");
    await page.getByRole("link", { name: "AI 모델 관리" }).first().click();
    await expect(page).toHaveURL(/\/ui\/main\/ai-models/);
  } else await page.goto("/ui/main/ai-models");
  await expect(page.getByRole("heading", { name: "등록된 모델" })).toBeVisible();
  await expect(page.getByText("등록된 모델이 없습니다.")).toBeVisible();
  await expect(page.getByRole("heading", { name: "모델 등록" })).toBeHidden();

  await page.getByRole("button", { name: "모델 등록" }).click();
  await expect(page.getByRole("heading", { name: "모델 등록" })).toBeFocused();
  // New models start empty: no requirement is pre-filled as if measured.
  for (const label of ["CPU 최소", "CPU 요청", "CPU 최대", "메모리 최소", "메모리 요청", "메모리 최대"])
    await expect(page.getByLabel(label, { exact: true })).toHaveValue("");
  await expect(page.getByRole("status").filter({ hasText: "요구량 출처" })).toContainText("사용자 지정(미측정)");
  await page.getByText("기존 Registry로 이미지 올리는 방법").click();
  await expect(page.getByText(`docker push ${host}/edgeai/<모델 이름>:<태그>`)).toBeVisible();

  await chooseImage(page);
  await expect(page.getByLabel("확인한 이미지")).toContainText(digest);
  await expect(page.getByRole("combobox", { name: "플랫폼", exact: true })).toBeVisible();
  await fillModel(page);
  await shot(page, testInfo.outputPath("register-form.png"));
  await noPageOverflow(page);
  const submit = page.getByRole("button", { name: "등록", exact: true });
  await submit.dblclick();
  await expect(page.getByRole("alert").filter({ hasText: "AI 모델 저장소를 사용할 수 없습니다." })).toBeVisible();
  expect(posts).toHaveLength(1); // the save lock lets a double click send once
  await expect(page.getByLabel("모델 키", { exact: true })).toHaveValue("forklift-detector");
  await expect(page.getByLabel("CPU 요청", { exact: true })).toHaveValue("250m");
  await expect(page.getByLabel("확인한 이미지")).toContainText(digest);

  await submit.click();
  await expect(page.getByRole("heading", { name: "지게차 탐지 · 1.0.0 · default" })).toBeFocused();
  expect(posts).toHaveLength(2);
  expect(posts[1].body).toEqual(expected);
  expect(posts[1].csrf).toBe("management-token");
  expect(posts[1].key).toBe(posts[0].key); // unchanged payload retried with the same Idempotency-Key
  await expect(page).toHaveURL(new RegExp(`model=${modelId}`));
  await expect(page.getByRole("heading", { name: "모델 등록" })).toBeHidden();
  const table = page.getByRole("table", { name: "등록된 AI 모델 실행 구성" });
  await expect(table).toContainText("forklift-detector");
  await expect(table).toContainText("sha256:f1e97a098403…");
  await expect(table).toContainText("미측정");
  const detail = page.locator("section").filter({ has: page.getByRole("heading", { name: "지게차 탐지 · 1.0.0 · default" }) });
  await expect(detail).toContainText(`${host}/edgeai/detector@${digest}`);
  await expect(detail).toContainText("사용자 지정(미측정)");
  await expect(page.getByRole("table", { name: "자원 요구량" })).toContainText("250m");
  await noPageOverflow(page);
  await shot(page, testInfo.outputPath("detail.png"));
  expect(errors).toEqual([]);
});

test("registry errors are shown without losing input and a slower earlier lookup never replaces a newer choice", async ({ page }) => {
  let release!: () => void;
  const slow = new Promise<void>(resolve => (release = resolve));
  mock(page, [], { hold: { manifest: { slow } } });
  await page.goto("/ui/main/ai-models");
  await page.getByRole("button", { name: "모델 등록" }).click();
  await page.getByLabel("모델 키", { exact: true }).fill("kept-key");
  await chooseImage(page, "missing");
  await expect(page.getByRole("alert").filter({ hasText: "Registry에 해당 저장소·태그·digest가 없습니다." })).toBeVisible();
  await expect(page.getByLabel("모델 키", { exact: true })).toHaveValue("kept-key");
  await page.getByLabel("태그 또는 digest 직접 입력", { exact: true }).fill("slow");
  await page.getByRole("button", { name: "이미지 확인" }).click();
  await page.getByLabel("태그 또는 digest 직접 입력", { exact: true }).fill("1.0.0");
  await page.getByRole("button", { name: "이미지 확인" }).click();
  await expect(page.getByLabel("확인한 이미지")).toContainText(digest);
  release();
  await page.waitForTimeout(300);
  await expect(page.getByLabel("확인한 이미지")).toContainText(digest);
  await expect(page.getByLabel("확인한 이미지")).not.toContainText(otherDigest);
  // An invalid repository name is refused before any request.
  await page.getByLabel("저장소 직접 입력(목록에 없을 때)", { exact: true }).fill("http://evil/x");
  await page.getByRole("button", { name: "입력한 저장소 사용" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "저장소 이름은" })).toBeVisible();
});

test("list loading failure is distinct from an empty list, and details open by keyboard", async ({ page }) => {
  mock(page, [], { list: { status: 503, json: { code: "AI_MODEL_STORE_UNAVAILABLE", message: "AI 모델 저장소를 사용할 수 없습니다. 복구 후 다시 시도하세요." } } });
  await page.goto("/ui/main/ai-models");
  await expect(page.getByRole("alert").filter({ hasText: "AI 모델 저장소를 사용할 수 없습니다." })).toBeVisible();
  await expect(page.getByText("등록된 모델이 없습니다.")).toHaveCount(0);

  await page.unrouteAll({ behavior: "ignoreErrors" });
  mock(page, [], { list: { status: 200, json: { items: [entry], nextOffset: null } } });
  await page.goto("/ui/main/ai-models");
  const open = page.getByRole("button", { name: "forklift-detector 1.0.0 default 상세" });
  await open.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "지게차 탐지 · 1.0.0 · default" })).toBeFocused();
  await expect(page.getByRole("table", { name: "자원 요구량" })).toContainText("512Mi");
  await noPageOverflow(page);
});

test("a registration that succeeds while an older detail is still loading shows the new detail", async ({ page }) => {
  const olderId = "00000000-0000-4000-8000-0000000000d0";
  let release!: () => void;
  const older = new Promise<void>(resolve => (release = resolve));
  mock(page, [{ status: 201, json: entry }], { hold: { detail: { [olderId]: older } } });
  await page.goto(`/ui/main/ai-models?model=${olderId}`);
  await expect(page.getByRole("status", { name: "모델 상세를 불러오는 중…" })).toBeVisible();
  await page.getByRole("button", { name: "모델 등록" }).click();
  await chooseImage(page);
  await expect(page.getByLabel("확인한 이미지")).toContainText(digest);
  await fillModel(page);
  await page.getByRole("button", { name: "등록", exact: true }).click();
  const heading = page.getByRole("heading", { name: "지게차 탐지 · 1.0.0 · default" });
  await expect(heading).toBeFocused();
  await expect(page.getByRole("status", { name: "모델 상세를 불러오는 중…" })).toHaveCount(0);
  await expect(page.getByRole("table", { name: "자원 요구량" })).toContainText("250m");
  release();
  await page.waitForTimeout(300);
  await expect(heading).toBeVisible();
  await expect(page.getByText("이전 모델")).toHaveCount(0);
});

test("while a chosen resource profile loads, the fields it fills are read-only and then hold the profile values", async ({ page }) => {
  let release!: () => void;
  const service = new Promise<void>(resolve => (release = resolve));
  mock(page, [], { hold: { service } });
  await page.goto("/ui/main/ai-models");
  await page.getByRole("button", { name: "모델 등록" }).click();
  const combobox = page.getByRole("combobox", { name: "기존 자원 프로파일 가져오기(선택)", exact: true });
  await page.locator(".kel-form-item").filter({ has: combobox }).locator(".kel-select").click();
  await page.locator(`[id="${await combobox.getAttribute("aria-controls")}"]`).getByRole("option", { name: "detector-svc@1.0.0 r2 · 실측" }).click();
  for (const label of ["CPU 요청", "메모리 최대", "실행 명령(선택, 한 줄에 하나)", "입력 조건(한 줄에 key=value)", "런타임 이름"])
    await expect(page.getByLabel(label, { exact: true })).toBeDisabled();
  await expect(page.getByRole("button", { name: "가속기 요구량 추가" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "등록", exact: true })).toBeDisabled();
  // Unrelated identity fields stay editable during the load.
  await page.getByLabel("모델 키", { exact: true }).fill("still-editable");
  release();
  await expect(page.getByLabel("CPU 요청", { exact: true })).toBeEnabled();
  await expect(page.getByLabel("CPU 요청", { exact: true })).toHaveValue("177m");
  await expect(page.getByLabel("실행 명령(선택, 한 줄에 하나)", { exact: true })).toHaveValue("python3\n/app/serve.py");
  await expect(page.getByLabel("모델 키", { exact: true })).toHaveValue("still-editable");
  await expect(page.getByRole("status").filter({ hasText: "요구량 출처" })).toContainText("자원 프로파일 그대로");
});
