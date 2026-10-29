import { test, expect, type Page } from "@playwright/test";

// Every control-plane call is answered in the browser; the console's API origins should point at unused local ports.
const now = "2026-10-29T00:00:00Z";
const workflowId = "00000000-0000-4000-8000-000000000001", streamWorkflowId = "00000000-0000-4000-8000-000000000002";
const versionId = "00000000-0000-4000-8000-000000000010", streamVersionId = "00000000-0000-4000-8000-000000000020";
const prepareId = "00000000-0000-4000-8000-000000000101", detectId = "00000000-0000-4000-8000-000000000102", streamServiceId = "00000000-0000-4000-8000-000000000103";
const edge = "00000000-0000-4000-8000-000000000201", server = "00000000-0000-4000-8000-000000000202";
const profileId = "00000000-0000-4000-8000-000000000301";
const service = (id: string, key: string, spec: unknown) => ({ id, key, version: "1.0.0", kind: "SERVICE", digest: "fixture", createdAt: now, spec });
const profiles = [
  service(prepareId, "frame-prepare", { inputs: {}, outputs: { frames: { mediaType: "application/json" } } }),
  // "hint" is an optional input that no DAG edge connects, so it is never downloaded.
  service(detectId, "defect-detect", { inputs: { frames: { mediaType: "application/json" }, hint: { mediaType: "application/json" } }, outputs: { result: { mediaType: "application/json" }, log: { mediaType: "text/plain" } } }),
  service(streamServiceId, "line-stream", { inputs: {}, outputs: {}, stream: { inputs: {}, outputs: { samples: { mediaType: "application/json", maxPayloadBytes: 4096 } } } }),
];
const dag = { tasks: [{ key: "prepare", serviceProfileVersionId: prepareId, parameters: {} }, { key: "detect", serviceProfileVersionId: detectId, parameters: {} }],
  dependencies: [{ fromTask: "prepare", fromPort: "frames", toTask: "detect", toPort: "frames", mode: "BATCH" }] };
const workflows = [{ id: workflowId, key: "quality", displayName: "품질 판별", createdAt: now }, { id: streamWorkflowId, key: "line", displayName: "라인 스트림", createdAt: now }];
const versions: Record<string, unknown[]> = {
  [workflowId]: [{ id: versionId, workflowId, version: "1.0.0", dag, digest: "dag-digest", createdAt: now }],
  [streamWorkflowId]: [{ id: streamVersionId, workflowId: streamWorkflowId, version: "2.0.0", digest: "stream-digest", createdAt: now,
    dag: { tasks: [{ key: "line", serviceProfileVersionId: streamServiceId, parameters: {} }], dependencies: [] } }],
};
const nodes = [
  { id: edge, name: "edge-a", architecture: "arm64", operatingSystem: "linux", status: "READY", cpu: "4", memory: "8Gi", allocatable: {}, labels: {}, observedAt: now },
  { id: server, name: "server-b", architecture: "amd64", operatingSystem: "linux", status: "READY", cpu: "32", memory: "128Gi", allocatable: {}, labels: {}, observedAt: now },
];
// The registration document an operator should produce from the measurements typed in below (independent literal).
const measured = {
  workflowVersionId: versionId, measuredAt: "2026-10-28T00:00:00Z", validUntil: "2026-11-28T00:00:00Z",
  provenance: { kind: "MEASURED_BATCH_ARTIFACT", evidenceId: "bench-20261028", sampleCount: 30 },
  parameters: { threshold: 0.5 },
  tasks: {
    prepare: { computeMs: { [edge]: 120, [server]: 40 }, downloadMs: {}, uploadMs: { frames: { [edge]: 15, [server]: 0 } } },
    detect: { computeMs: { [server]: 80 }, downloadMs: { frames: { [server]: 22 } }, uploadMs: { result: { [server]: 3 }, log: { [server]: 1 } } },
  },
};
const stored = { ...measured, id: profileId, digest: "profile-digest", createdAt: now };
const preview = { profileId, profileDigest: "profile-digest", workflowVersionId: versionId, method: "ARTIFACT_PROFILE_MIN_COST", predicted: true,
  predictedCriticalPathMs: 157, criticalPath: ["prepare", "detect"], predictedCommitMs: { prepare: 55, detect: 157 },
  placements: { prepare: server, detect: server },
  candidates: { prepare: [{ nodeId: edge, eligible: false, costMs: 135, reasons: ["NOT_READY"] }, { nodeId: server, eligible: true, costMs: 40, reasons: [] }],
    detect: [{ nodeId: server, eligible: true, costMs: 106, reasons: [] }] },
  costModel: "sequential download+compute+upload per Task through the artifact store; no queueing, contention, Pod start or image pull",
  admission: "kube-scheduler", evaluatedAt: now };

// A saved profile whose parameter has more digits than a JS number keeps; served as raw JSON text.
const preciseId = "00000000-0000-4000-8000-000000000303";
const preciseText = JSON.stringify({ ...stored, id: preciseId, provenance: { ...stored.provenance, evidenceId: "bench-precise" } })
  .replace('"threshold":0.5', '"threshold":0.1234567890123456789');
type Reply = { status: number; json: unknown };
// A function answer stays pending until the test resolves it.
type Answer = Reply | "abort" | (() => Promise<Reply>);
function mock(page: Page, registrations: Answer[] = [], previews: Answer[] = []) {
  const posts: { path: string; key: string | undefined; csrf: string | undefined; body: unknown; raw: string }[] = [];
  void page.route("**/api/control-plane/**", async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname.replace("/api/control-plane/", "");
    if (path === "workflow-csrf") return route.fulfill({ json: { token: "workflow-token" } });
    if (path === "csrf") return route.fulfill({ json: { token: "management-token" } });
    if (path === "profiles/SERVICE") return route.fulfill({ json: { items: profiles, nextOffset: null } });
    if (path === "nodes") return route.fulfill({ json: { items: nodes, nextOffset: null } });
    if (path === "workflows") return route.fulfill({ json: { items: workflows, nextOffset: null } });
    const workflow = workflows.find(w => path === `workflows/${w.id}`);
    if (workflow) return route.fulfill({ json: { workflow, versions: versions[workflow.id], nextOffset: null } });
    if (request.method() === "POST") {
      posts.push({ path, key: request.headers()["idempotency-key"], csrf: request.headers()["x-csrf-token"], body: request.postDataJSON(), raw: request.postData() ?? "" });
      const answer = (path === "scheduling-profiles" ? registrations : previews).shift();
      if (!answer) return route.fulfill({ status: 500, json: { message: `unexpected ${path}` } });
      return answer === "abort" ? route.abort("connectionreset") : route.fulfill(typeof answer === "function" ? await answer() : answer);
    }
    if (path === `scheduling-profiles/${profileId}`) return route.fulfill({ json: stored });
    if (path === `scheduling-profiles/${preciseId}`) return route.fulfill({ contentType: "application/json", body: preciseText });
    return route.fulfill({ status: 404, json: { message: `unmocked ${path}` } });
  });
  return posts;
}
// Options are read from the listbox the named combobox controls, so a closing dropdown of another select never matches.
async function options(page: Page, name: string) {
  const combobox = page.getByRole("combobox", { name, exact: true });
  await page.locator(".kel-form-item").filter({ has: combobox }).locator(".kel-select").click();
  return page.locator(`[id="${await combobox.getAttribute("aria-controls")}"]`);
}
async function pick(page: Page, label: string, option: string) {
  await (await options(page, label)).getByRole("option", { name: option, exact: true }).click();
}
async function pickNodes(page: Page, task: string, names: string[]) {
  const list = await options(page, `${task} 측정 노드`);
  for (const name of names) await list.getByRole("option", { name, exact: true }).click();
  await page.keyboard.press("Escape");
}
const cost = (page: Page, group: string, label: string) => page.getByRole("group", { name: group, exact: true }).getByLabel(label, { exact: true });
async function fillMeasurement(page: Page) {
  await page.getByLabel("측정 시각", { exact: true }).fill("2026-10-28T00:00:00Z");
  await page.getByLabel("유효 종료 시각", { exact: true }).fill("2026-11-28T00:00:00Z");
  await page.getByLabel("측정 증빙 ID", { exact: true }).fill("bench-20261028");
  await page.getByLabel("표본 수", { exact: true }).fill("30");
  await page.getByLabel("측정 실행 매개변수 JSON", { exact: true }).fill('{"threshold":0.5}');
  await pickNodes(page, "prepare", ["edge-a", "server-b"]);
  await pickNodes(page, "detect", ["server-b"]);
  await cost(page, "prepare · edge-a", "계산 시간 (ms)").fill("120");
  await cost(page, "prepare · edge-a", "출력 frames 업로드 (ms)").fill("15");
  await cost(page, "prepare · server-b", "계산 시간 (ms)").fill("40");
  await cost(page, "prepare · server-b", "출력 frames 업로드 (ms)").fill("0");
  await cost(page, "detect · server-b", "계산 시간 (ms)").fill("80");
  await cost(page, "detect · server-b", "입력 frames 다운로드 (ms)").fill("22");
  await cost(page, "detect · server-b", "출력 result 업로드 (ms)").fill("3");
}
// .native-page scrolls inside the shell, so evidence shots use a viewport tall enough for the whole page.
async function shot(page: Page, path: string) {
  const viewport = page.viewportSize()!;
  await page.setViewportSize({ width: viewport.width, height: 3200 });
  await page.screenshot({ path });
  await page.setViewportSize(viewport);
}
const register = (page: Page) => page.getByRole("button", { name: "성능 프로파일 등록", exact: true });

test("measured task-node costs register as the exact profile, retry keeps the key, and the saved profile previews placement", async ({ page }, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  const posts = mock(page, [{ status: 503, json: { message: "작업 저장소를 사용할 수 없습니다." } }, { status: 201, json: stored }, { status: 201, json: { ...stored, id: "00000000-0000-4000-8000-000000000302" } }],
    [{ status: 200, json: preview }]);
  if (testInfo.project.name === "desktop") {
    await page.goto("/ui/main/workflows");
    await page.getByRole("link", { name: "성능 프로파일" }).first().click();
    await expect(page).toHaveURL(/\/ui\/main\/scheduling-profiles/);
  } else await page.goto("/ui/main/scheduling-profiles");
  await pick(page, "워크플로", "품질 판별 · quality");
  await pick(page, "DAG 버전", "1.0.0");
  await expect(page.getByText(versionId)).toBeVisible();
  await fillMeasurement(page);
  // The unmeasured log upload stays empty and blocks the request instead of becoming 0.
  await expect(cost(page, "detect · server-b", "출력 log 업로드 (ms)")).toHaveValue("");
  await register(page).click();
  await expect(page.getByRole("alert").filter({ hasText: "detect · server-b의 출력 log 업로드 (ms)를 입력하세요." })).toBeVisible();
  expect(posts).toEqual([]);
  await cost(page, "detect · server-b", "출력 log 업로드 (ms)").fill("1");
  await shot(page, testInfo.outputPath("form.png"));

  await register(page).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("alert").filter({ hasText: "작업 저장소를 사용할 수 없습니다." })).toBeVisible();
  await expect(cost(page, "prepare · server-b", "출력 frames 업로드 (ms)")).toHaveValue("0");
  await register(page).click();
  await expect(page.getByText("성능 프로파일을 등록했습니다.")).toBeVisible();
  expect(posts.map(p => p.path)).toEqual(["scheduling-profiles", "scheduling-profiles"]);
  expect(posts[0].body).toEqual(measured);
  expect(posts[1].body).toEqual(measured);
  expect(posts[1].key).toBe(posts[0].key);
  expect(posts[0].csrf).toBe("workflow-token");
  await expect(page).toHaveURL(new RegExp(`profile=${profileId}`));
  const saved = page.getByRole("region", { name: "등록된 성능 프로파일" });
  await expect(saved).toContainText(profileId);
  await expect(saved).toContainText("bench-20261028");

  await saved.getByRole("button", { name: "배치 미리보기", exact: true }).click();
  const result = page.getByRole("region", { name: "배치 미리보기 결과" });
  await expect(result).toContainText("157 ms");
  await expect(result).toContainText("prepare → detect");
  await expect(result.getByRole("row", { name: /prepare.*server-b/ })).toBeVisible();
  expect(posts[2]).toMatchObject({ path: `scheduling-profiles/${profileId}/placement-preview`, body: { parameters: { threshold: 0.5 } }, csrf: "workflow-token" });
  await shot(page, testInfo.outputPath("preview.png"));

  // A changed measurement is a new immutable profile, so it gets a new key.
  await page.getByLabel("측정 증빙 ID", { exact: true }).fill("bench-20261028-b");
  await register(page).click();
  await expect(page).toHaveURL(/profile=00000000-0000-4000-8000-000000000302/);
  expect(posts[3].key).not.toBe(posts[0].key);
  expect(errors).toEqual([]);
});

test("imported JSON fills the draft only when it is a valid profile for the selected version", async ({ page }) => {
  const posts = mock(page, [{ status: 201, json: stored }]);
  // Entered from the selected published version on the workflow page; the source is preselected.
  await page.goto(`/ui/main/workflows?workflow=${workflowId}`);
  await page.getByRole("button", { name: "1.0.0", exact: true }).click();
  await page.getByRole("link", { name: "이 버전의 성능 프로파일 등록", exact: true }).click();
  await expect(page.getByText(`버전 ID ${versionId}`)).toBeVisible();
  await page.getByLabel("측정 증빙 ID", { exact: true }).fill("draft-evidence");
  await page.getByText("측정 JSON 가져오기", { exact: true }).click();
  const file = page.getByLabel("측정 JSON 파일", { exact: true });
  const reject = async (name: string, content: string | Buffer, message: string) => {
    await file.setInputFiles({ name, mimeType: "application/json", buffer: Buffer.isBuffer(content) ? content : Buffer.from(content) });
    await expect(page.getByRole("alert").filter({ hasText: message })).toBeVisible();
    await expect(page.getByLabel("측정 증빙 ID", { exact: true })).toHaveValue("draft-evidence");
  };
  await reject("broken.json", "{", "측정 JSON을 해석할 수 없습니다.");
  await reject("large.json", Buffer.alloc(1048577, 32), "측정 JSON은 1 MiB 이하여야 합니다.");
  await reject("other.json", JSON.stringify({ ...measured, workflowVersionId: streamVersionId }), "선택한 DAG 버전과 다른 측정 프로파일입니다.");
  await reject("string.json", JSON.stringify({ ...measured, tasks: { ...measured.tasks, detect: { ...measured.tasks.detect, computeMs: { [server]: "80" } } } }),
    "detect의 계산 시간 (ms)은 0 이상의 숫자여야 합니다.");
  await reject("ports.json", JSON.stringify({ ...measured, tasks: { ...measured.tasks, detect: { ...measured.tasks.detect, uploadMs: { result: { [server]: 3 } } } } }),
    "detect의 uploadMs 포트는 log, result와 정확히 일치해야 합니다.");
  // Metadata the API would refuse never replaces the draft either.
  const provenance = measured.provenance;
  await reject("samples.json", JSON.stringify({ ...measured, provenance: { ...provenance, sampleCount: 0 } }), "표본 수는 1 이상의 정수여야 합니다.");
  await reject("time.json", JSON.stringify({ ...measured, measuredAt: "invalid" }), "측정 시각은 시간대를 포함한 ISO 8601 시각이어야 합니다");
  await reject("order.json", JSON.stringify({ ...measured, measuredAt: measured.validUntil, validUntil: measured.measuredAt }), "유효 종료 시각은 측정 시각 이후여야 합니다.");
  await reject("evidence.json", JSON.stringify({ ...measured, provenance: { ...provenance, evidenceId: " " } }), "측정 증빙 ID를 입력하세요.");
  await reject("long-evidence.json", JSON.stringify({ ...measured, provenance: { ...provenance, evidenceId: "a".repeat(201) } }), "측정 증빙 ID는 200자 이하이며 제어 문자를 포함할 수 없습니다.");
  await reject("control-evidence.json", JSON.stringify({ ...measured, provenance: { ...provenance, evidenceId: "bench\u0000x" } }), "측정 증빙 ID는 200자 이하이며 제어 문자를 포함할 수 없습니다.");

  // Timestamps and decimal measurements are kept exactly as written; a one-microsecond validity interval is still ordered.
  await page.getByLabel("측정 JSON 붙여넣기", { exact: true }).fill(JSON.stringify({ ...measured, measuredAt: "2026-10-28T00:00:00.123456Z", validUntil: "2026-10-28T00:00:00.123457Z" }).replace('"computeMs":{"' + server + '":80}', '"computeMs":{"' + server + '":80.250}'));
  await page.getByRole("button", { name: "붙여넣은 JSON 가져오기", exact: true }).click();
  await expect(page.getByLabel("측정 증빙 ID", { exact: true })).toHaveValue("bench-20261028");
  await expect(page.getByLabel("측정 시각", { exact: true })).toHaveValue("2026-10-28T00:00:00.123456Z");
  await expect(cost(page, "detect · server-b", "계산 시간 (ms)")).toHaveValue("80.250");
  await expect(cost(page, "prepare · server-b", "출력 frames 업로드 (ms)")).toHaveValue("0");
  await expect(cost(page, "detect · server-b", "출력 log 업로드 (ms)")).toHaveValue("1");
  expect(posts).toEqual([]);
  await register(page).click();
  await expect(page.getByText("성능 프로파일을 등록했습니다.")).toBeVisible();
  expect(posts[0].raw).toContain('"measuredAt":"2026-10-28T00:00:00.123456Z","validUntil":"2026-10-28T00:00:00.123457Z"');
  expect(posts[0].raw).toContain(`"computeMs":{"${server}":80.250}`);
});

test("a profile reopens by ID, preview errors stay visible, and STREAM DAGs are refused before entry", async ({ page }, testInfo) => {
  const posts = mock(page, [], [{ status: 409, json: { code: "SCHEDULING_PROFILE_EXPIRED", message: "측정 프로파일 유효기간이 지났습니다. 새로 측정해 등록하세요." } }]);
  await page.goto("/ui/main/scheduling-profiles");
  await page.getByLabel("프로파일 ID", { exact: true }).fill(profileId);
  await page.getByRole("button", { name: "프로파일 열기", exact: true }).click();
  const saved = page.getByRole("region", { name: "등록된 성능 프로파일" });
  await expect(saved).toContainText("bench-20261028");
  await expect(page).toHaveURL(new RegExp(`profile=${profileId}`));
  await saved.getByRole("button", { name: "배치 미리보기", exact: true }).click();
  await expect(saved.getByRole("alert").filter({ hasText: "측정 프로파일 유효기간이 지났습니다." })).toBeVisible();
  await expect(page.getByRole("region", { name: "배치 미리보기 결과" })).toHaveCount(0);

  await pick(page, "워크플로", "라인 스트림 · line");
  await pick(page, "DAG 버전", "2.0.0");
  await expect(page.getByRole("alert").filter({ hasText: "STREAM" })).toBeVisible();
  await expect(register(page)).toHaveCount(0);
  await shot(page, testInfo.outputPath("stream-unsupported.png"));
  expect(posts.map(p => p.path)).toEqual([`scheduling-profiles/${profileId}/placement-preview`]);
});

test("preview sends saved parameters exactly and a pending preview never lands on another profile", async ({ page }) => {
  const gates: ((reply: Reply) => void)[] = [];
  const pending = () => new Promise<Reply>(resolve => gates.push(resolve));
  const posts = mock(page, [], [pending, pending]);
  // A failed version lookup is an error, not an empty published-version list.
  await page.route(new RegExp(`/api/control-plane/workflows/${workflowId}\\?`), route => route.fulfill({ status: 503, json: { message: "워크플로 저장소를 사용할 수 없습니다." } }));
  await page.goto("/ui/main/scheduling-profiles");
  await pick(page, "워크플로", "품질 판별 · quality");
  await expect(page.getByRole("alert").filter({ hasText: "워크플로 저장소를 사용할 수 없습니다." })).toBeVisible();
  await expect(page.getByText("발행된 DAG 버전이 없습니다.")).toHaveCount(0);

  const saved = page.getByRole("region", { name: "등록된 성능 프로파일" });
  const openProfile = async (id: string, evidence: string) => {
    await page.getByLabel("프로파일 ID", { exact: true }).fill(id);
    await page.getByRole("button", { name: "프로파일 열기", exact: true }).click();
    await expect(saved).toContainText(evidence);
  };
  for (const reply of [{ status: 200, json: { ...preview, profileId: preciseId } }, { status: 409, json: { message: "측정 프로파일 유효기간이 지났습니다." } }]) {
    await openProfile(preciseId, "bench-precise");
    await saved.getByRole("button", { name: "배치 미리보기", exact: true }).click();
    await expect.poll(() => gates.length).toBe(posts.length);
    await openProfile(profileId, "bench-20261028");
    gates.at(-1)!(reply);
    await expect(saved.getByRole("button", { name: "배치 미리보기", exact: true })).toBeEnabled();
    await expect(page.getByRole("region", { name: "배치 미리보기 결과" })).toHaveCount(0);
    await expect(saved.getByRole("alert")).toHaveCount(0);
  }
  expect(posts.map(p => p.raw)).toEqual(Array(2).fill('{"parameters":{"threshold":0.1234567890123456789}}'));
  await expect(page).toHaveURL(new RegExp(`profile=${profileId}`));
});
