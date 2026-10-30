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
};
const manualResources = { cpu: { minMillicores: 100, requestMillicores: 250, maxMillicores: 2000, basis: "MANUAL" },
  memory: { minBytes: 268435456, requestBytes: 536870912, maxBytes: 1073741824, basis: "MANUAL" }, accelerators: [] };
const entry = {
  id: modelId, digest: "sha256:" + "c".repeat(64), createdAt: now, measurementStatus: "UNMEASURED", resourceProfileId: null,
  modelKey: expected.modelKey, displayName: expected.displayName, modelVersion: "1.0.0", configName: "default", description: null,
  image: { registry: host, repository: "edgeai/detector", digest, reference: `${host}/edgeai/detector@${digest}`, mediaType: "application/vnd.oci.image.manifest.v1+json",
    kind: "MANIFEST", sourceTag: "1.0.0", platform: { os: "linux", architecture: "amd64", variant: null, label: "linux/amd64", runnable: true },
    platformManifestDigest: digest, platformReference: `${host}/edgeai/detector@${digest}`, platformManifestMediaType: "application/vnd.oci.image.manifest.v1+json",
    configDigest: "sha256:" + "f".repeat(64), configMediaType: "application/vnd.oci.image.config.v1+json", layersSize: 46000000, created: null, verifiedAt: now },
  execution: expected.execution, inputConditions: expected.inputConditions, runtime: expected.runtime,
  // Registered without requirements: they come from a later measurement.
  requirements: null,
  resourceProfile: null, evidence: null, limitations: ["자원 요구량은 실행 측에 전달할 선언이며 자원 예약·분할·증강이나 실행 보장이 아닙니다."],
};
/** A model registered before requirements became optional still shows its declared values. */
const declared = { ...entry, requirements: { source: "MANUAL", measurementStatus: "UNMEASURED", resources: manualResources } };

type Hold = { manifest?: Record<string, Promise<void>>; detail?: Record<string, Promise<void>> };
function mock(page: Page, saves: { status: number; json: unknown }[], options: { list?: { status: number; json: unknown }; hold?: Hold; detail?: unknown } = {}) {
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
    const detailHold = Object.entries(options.hold?.detail ?? {}).find(([id]) => path === `ai-models/${id}`);
    if (detailHold) { await detailHold[1]; return route.fulfill({ json: { ...entry, id: detailHold[0], displayName: "이전 모델" } }); }
    if (path === `ai-models/${modelId}`) return route.fulfill({ json: options.detail ?? entry });
    // No measurement has run yet for these models.
    if (/^ai-models\/[0-9a-f-]{36}\/(profiling-runs|resource-profiles)$/.test(path)) return route.fulfill({ json: { items: [], nextOffset: null } });
    return route.fulfill({ status: 404, json: { message: `unmocked ${path}` } });
  });
  return Object.assign(posts, { models });
}
// Screenshots keep the real viewport so a cramped or clipped layout is not hidden by a taller page.
async function shot(page: Page, path: string) {
  await page.screenshot({ path });
}
async function noPageOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true);
}
const drawer = (page: Page) => page.getByRole("dialog", { name: "모델 등록" });
const openRegister = (page: Page) => page.getByRole("button", { name: "모델 등록", exact: true }).first().click();
/** Types a value into a filterable select that accepts new entries and confirms it with the keyboard. */
async function typeOption(page: Page, name: string, value: string) {
  const box = page.getByRole("combobox", { name, exact: true });
  await box.click();
  await box.fill(value);
  await box.press("Enter");
}
async function chooseImage(page: Page, reference = "1.0.0") {
  await typeOption(page, "저장소", "edgeai/detector");
  await typeOption(page, "태그 또는 digest", reference);
}
async function openOptional(page: Page) {
  const details = page.locator("details").filter({ hasText: "실행 조건·설명(선택)" });
  if (!(await details.evaluate(d => (d as HTMLDetailsElement).open))) await details.locator("summary").click();
}
async function fillModel(page: Page) {
  await page.getByLabel("모델 키", { exact: true }).fill("forklift-detector");
  await page.getByLabel("표시 이름", { exact: true }).fill("지게차 탐지");
  await page.getByLabel("모델 버전", { exact: true }).fill("1.0.0");
  await openOptional(page);
  await page.getByLabel("입력 조건(한 줄에 key=value)", { exact: true }).fill("resolution=640x640");
  await page.getByLabel("런타임 이름", { exact: true }).fill("onnxruntime");
  await page.getByLabel("런타임 버전", { exact: true }).fill("1.20.1");
}

test("the list is the first screen; registration verifies a registry digest, keeps the draft on failure and opens the saved detail", async ({ page }, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  const posts = mock(page, [{ status: 503, json: { code: "AI_MODEL_STORE_UNAVAILABLE", message: "AI 모델 저장소를 사용할 수 없습니다. 복구 후 다시 시도하세요." } }, { status: 201, json: entry }]);
  if (testInfo.project.name === "desktop") {
    await page.goto("/ui/main/resource-profiles");
    // 자원 프로파일 is no longer a menu item; it stays routable and is reached from AI 모델 관리.
    await expect(page.getByRole("link", { name: "자원 프로파일", exact: true })).toHaveCount(0);
    await page.getByRole("link", { name: "AI 모델 관리" }).first().click();
    await expect(page).toHaveURL(/\/ui\/main\/ai-models/);
    await expect(page.getByRole("link", { name: "서비스 자원 프로파일" })).toBeVisible();
  } else await page.goto("/ui/main/ai-models");
  await expect(page.getByText("등록된 모델이 없습니다.")).toBeVisible();
  await expect(drawer(page)).toBeHidden();
  await shot(page, testInfo.outputPath("empty.png"));

  await openRegister(page);
  await expect(drawer(page)).toBeVisible();
  await expect(page.getByLabel("모델 키", { exact: true })).toBeFocused();
  // Registration identifies the model and its image only; requirements are measured afterwards, never typed or imported here.
  for (const label of ["CPU 요청", "메모리 요청", "기존 자원 프로파일 가져오기(선택)"])
    await expect(page.getByLabel(label, { exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "가속기 요구량 추가" })).toHaveCount(0);
  await page.getByText(`Registry ${host}에 이미지 올리는 방법`).click();
  await expect(page.getByText(`docker push ${host}/edgeai/<모델 이름>:<태그>`)).toBeVisible();
  // The drawer can be closed and reopened without losing the draft; focus returns to the opening button.
  await page.getByLabel("모델 키", { exact: true }).fill("draft-kept");
  await page.keyboard.press("Escape");
  await expect(drawer(page)).toBeHidden();
  await expect(page.getByRole("button", { name: "모델 등록", exact: true }).first()).toBeFocused();
  await openRegister(page);
  await expect(page.getByLabel("모델 키", { exact: true })).toHaveValue("draft-kept");

  await chooseImage(page);
  await expect(page.getByLabel("확인한 이미지")).toContainText(digest);
  await expect(page.getByRole("combobox", { name: "플랫폼", exact: true })).toBeVisible();
  await fillModel(page);
  await shot(page, testInfo.outputPath("register-form.png"));
  await noPageOverflow(page);
  const submit = page.getByRole("button", { name: "등록", exact: true });
  // The footer stays reachable while the form body scrolls.
  await expect(submit).toBeInViewport();
  await submit.dblclick();
  await expect(page.getByRole("alert").filter({ hasText: "AI 모델 저장소를 사용할 수 없습니다." })).toBeVisible();
  expect(posts).toHaveLength(1); // the save lock lets a double click send once
  await expect(page.getByLabel("모델 키", { exact: true })).toHaveValue("forklift-detector");
  await expect(page.getByLabel("런타임 이름", { exact: true })).toHaveValue("onnxruntime");
  await expect(page.getByLabel("확인한 이미지")).toContainText(digest);

  await submit.click();
  await expect(page.getByRole("heading", { name: "지게차 탐지 · 1.0.0 · default" })).toBeFocused();
  expect(posts).toHaveLength(2);
  expect(posts[1].body).toEqual(expected);
  expect(posts[1].csrf).toBe("management-token");
  expect(posts[1].key).toBe(posts[0].key); // unchanged payload retried with the same Idempotency-Key
  await expect(page).toHaveURL(new RegExp(`model=${modelId}`));
  await expect(drawer(page)).toBeHidden();
  const table = page.locator(".ks-data-table-wrapper");
  await expect(table).toContainText("forklift-detector");
  await expect(table).toContainText("sha256:f1e97a098403…");
  await expect(table).toContainText("미측정");
  const detail = page.locator("section").filter({ has: page.getByRole("heading", { name: "지게차 탐지 · 1.0.0 · default" }) });
  await expect(detail).toContainText(`${host}/edgeai/detector@${digest}`);
  // A new model has no requirement until it is measured and confirmed.
  await expect(page.getByRole("region", { name: "자원 요구량" })).toContainText("측정이 성공하고 요구량을 확정하면 사용 가능합니다.");
  await expect(page.getByRole("region", { name: "자원 요구량" }).getByRole("button", { name: "측정 시작" })).toBeVisible();
  await expect(page.getByText("등록 시 선언한 요구량")).toHaveCount(0);
  await noPageOverflow(page);
  await shot(page, testInfo.outputPath("detail.png"));
  expect(errors).toEqual([]);
});

test("registry errors are shown without losing input and a slower earlier lookup never replaces a newer choice", async ({ page }) => {
  let release!: () => void;
  const slow = new Promise<void>(resolve => (release = resolve));
  mock(page, [], { hold: { manifest: { slow } } });
  await page.goto("/ui/main/ai-models");
  await openRegister(page);
  await page.getByLabel("모델 키", { exact: true }).fill("kept-key");
  await chooseImage(page, "missing");
  await expect(page.getByRole("alert").filter({ hasText: "Registry에 해당 저장소·태그·digest가 없습니다." })).toBeVisible();
  await expect(page.getByLabel("모델 키", { exact: true })).toHaveValue("kept-key");
  await typeOption(page, "태그 또는 digest", "slow");
  await typeOption(page, "태그 또는 digest", "1.0.0");
  await expect(page.getByLabel("확인한 이미지")).toContainText(digest);
  release();
  await page.waitForTimeout(300);
  await expect(page.getByLabel("확인한 이미지")).toContainText(digest);
  await expect(page.getByLabel("확인한 이미지")).not.toContainText(otherDigest);
  // An invalid repository name is refused before any request.
  await typeOption(page, "저장소", "http://evil/x");
  await expect(page.getByRole("alert").filter({ hasText: "저장소 이름은" })).toBeVisible();
  await expect(page.getByLabel("확인한 이미지")).toContainText(digest); // the refused name changes nothing
});

test("list loading failure is distinct from an empty list, and details open by keyboard", async ({ page }) => {
  mock(page, [], { list: { status: 503, json: { code: "AI_MODEL_STORE_UNAVAILABLE", message: "AI 모델 저장소를 사용할 수 없습니다. 복구 후 다시 시도하세요." } } });
  await page.goto("/ui/main/ai-models");
  await expect(page.getByRole("alert").filter({ hasText: "AI 모델 저장소를 사용할 수 없습니다." })).toBeVisible();
  await expect(page.getByText("등록된 모델이 없습니다.")).toHaveCount(0);

  await page.unrouteAll({ behavior: "ignoreErrors" });
  mock(page, [], { list: { status: 200, json: { items: [declared], nextOffset: null } }, detail: declared });
  await page.goto("/ui/main/ai-models");
  const open = page.getByRole("button", { name: "forklift-detector 1.0.0 default 상세" });
  await open.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "지게차 탐지 · 1.0.0 · default" })).toBeFocused();
  // Values declared at registration remain readable as reference only, apart from the measured requirement.
  await page.getByText("등록 시 선언한 요구량").click();
  await expect(page.getByRole("table", { name: "등록 시 선언 요구량" })).toContainText("512Mi");
  await expect(page.getByText("사용자 지정(미측정)")).toBeVisible();
  await noPageOverflow(page);
  await page.getByRole("button", { name: "상세 닫기" }).click();
  await expect(page.getByRole("heading", { name: "지게차 탐지 · 1.0.0 · default" })).toHaveCount(0);
  await expect(page).not.toHaveURL(/model=/);
});

test("a registration that succeeds while an older detail is still loading shows the new detail", async ({ page }) => {
  const olderId = "00000000-0000-4000-8000-0000000000d0";
  let release!: () => void;
  const older = new Promise<void>(resolve => (release = resolve));
  mock(page, [{ status: 201, json: entry }], { hold: { detail: { [olderId]: older } } });
  await page.goto(`/ui/main/ai-models?model=${olderId}`);
  await expect(page.getByRole("status", { name: "모델 상세를 불러오는 중…" })).toBeVisible();
  await openRegister(page);
  await chooseImage(page);
  await expect(page.getByLabel("확인한 이미지")).toContainText(digest);
  await fillModel(page);
  await page.getByRole("button", { name: "등록", exact: true }).click();
  const heading = page.getByRole("heading", { name: "지게차 탐지 · 1.0.0 · default" });
  await expect(heading).toBeFocused();
  await expect(page.getByRole("status", { name: "모델 상세를 불러오는 중…" })).toHaveCount(0);
  await expect(page.getByRole("region", { name: "자원 요구량" })).toContainText("측정이 성공하고 요구량을 확정하면 사용 가능합니다.");
  release();
  await page.waitForTimeout(300);
  await expect(heading).toBeVisible();
  await expect(page.getByText("이전 모델")).toHaveCount(0);
});
