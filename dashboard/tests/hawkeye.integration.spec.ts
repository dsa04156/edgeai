import { test, expect, type Page } from "@playwright/test";

// Every control-plane call is answered in the browser by a small stateful stand-in for the Hawkeye API;
// no model runs here, so this proves rendered UI behavior only. Real inference is recorded separately.
const DIGEST = "edac48dc7e13c76d23710b693552d2422f599c3c7112f45561888dec48f2d280";
const RUN_ID = "4f1c2b9e-7a1d-4c55-9a0e-0b5d2e6f8a11";
const capability = (activeRunId: string | null, runtime: unknown = {
  status: "READY", error: null,
  reported: { nodeName: "etri-ser0001-cg0msb", model: "hf.co/Jsohal174/hawkeye-warehouse-safety-gguf:latest", modelDigest: DIGEST,
    ollamaVersion: "0.32.5", processor: "GPU", sizeBytes: 1618623855, sizeVramBytes: 1618623855 },
}) => ({
  id: "hawkeye-warehouse-safety", enabled: runtime != null, activeRunId,
  categories: ["spill", "forklift_violation", "improper_stacking", "obstacle", "safe"],
  model: { name: "Hawkeye Warehouse Safety VLM (Qwen3.5-2B LoRA, Q4_K_M + F16 vision encoder)",
    ollamaName: "hf.co/Jsohal174/hawkeye-warehouse-safety-gguf:latest", repository: "https://huggingface.co/Jsohal174/hawkeye-warehouse-safety-gguf",
    revision: "9aea324874878ab464fa42e6abd952e178893f79", license: "CC-BY-4.0", manifestDigest: DIGEST,
    languageSha256: "beef633ddb0671b90cc90f94e254537ae7e7e5c718ed0e73c88c95c8d8d740f3",
    visionSha256: "e1ae17404211c36acc087f15e2615a8d617afa5bafeadc5d2b484ecbe7edb2c2" },
  dataset: { name: "Warehouse Safety Hazard Dataset (합성 이미지, held-out test split)",
    repository: "https://huggingface.co/datasets/Jsohal174/warehouse-safety-hazard-dataset", revision: "b23808ec287ad4aa377f661f9cbda840e76865e1",
    license: "CC-BY-4.0", split: "test", synthetic: true, samplesSha256: "9c4ae8925a63fcea3ce3ee241fceefbef1d0129295ff11b7a07391f108e11e69" },
  samples: [
    { id: "forklift_0004", file: "test/forklift_violation/forklift_0004.jpg", referenceLabel: "forklift_violation", sha256: "417f2b6c".padEnd(64, "0") },
    { id: "forklift_0024", file: "test/safe/forklift_0024.jpg", referenceLabel: "safe", sha256: "eaff9ccb".padEnd(64, "0") },
  ],
  runtime,
});
function run(state: string, sampleId = "forklift_0024", output: { category: string | null; text: string } | null = null) {
  const finished = ["SUCCEEDED", "FAILED", "INTERRUPTED"].includes(state);
  return {
    id: RUN_ID, sampleId, referenceLabel: sampleId === "forklift_0004" ? "forklift_violation" : "safe", state,
    createdAt: "2026-10-23T07:30:00Z", startedAt: state === "QUEUED" ? null : "2026-10-23T07:30:01Z",
    finishedAt: finished ? "2026-10-23T07:30:02Z" : null,
    failureCode: output && output.category == null ? "UNPARSEABLE_OUTPUT" : null,
    failureMessage: output && output.category == null ? "모델 출력 첫 줄에서 범주 하나를 읽지 못했습니다. 안전으로 간주하지 않습니다." : null,
    target: state === "QUEUED" ? null : { nodeName: "etri-ser0001-cg0msb", model: "hf.co/Jsohal174/hawkeye-warehouse-safety-gguf:latest",
      modelDigest: DIGEST, ollamaVersion: "0.32.5", promptSha256: "f406389c47e1c1f8b2af740c4aa22b7e1061020faa569523712fc45da678835b" },
    result: output && finished ? {
      category: output.category, explanation: output.category ? "A yellow forklift is operating in the center aisle between pallet racks." : null,
      matchesReference: output.category == null ? null : output.category === (sampleId === "forklift_0004" ? "forklift_violation" : "safe"),
      rawOutput: output.text, doneReason: "stop", inputSha256: "eaff9ccb".padEnd(64, "0"), processor: "GPU", sizeBytes: 1618623855,
      sizeVramBytes: 1618623855, inferenceMs: 784.96, loadMs: 219.4, roundTripMs: 790.12, promptEvalCount: 627, evalCount: 71,
    } : null,
  };
}
const JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAAA//EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AN//Z",
  "base64");

/** Server-side state of the stand-in: a started run becomes RUNNING, then finishes with the configured model output. */
function api(page: Page, output = { category: "spill" as string | null, text: "Category: spill\n\nDescription: A yellow forklift is operating." }) {
  const state = { phase: "idle" as "idle" | "running" | "done", reads: 0, sample: "", starts: [] as { key: string; body: unknown }[] };
  void page.route("**/api/control-plane/**", async (route) => {
    const request = route.request(), path = new URL(request.url()).pathname.replace("/api/control-plane/", "");
    const current = () => run(state.phase === "running" ? "RUNNING" : output.category == null ? "FAILED" : "SUCCEEDED", state.sample, output);
    if (path === "csrf") return route.fulfill({ json: { token: "fixture" } });
    if (path === "scenarios/hawkeye-safety") return route.fulfill({ json: capability(state.phase === "running" ? RUN_ID : null) });
    if (/^scenarios\/hawkeye-safety\/samples\/forklift_00(04|24)\/image$/.test(path))
      return route.fulfill({ contentType: "image/jpeg", body: JPEG });
    if (path === "scenarios/hawkeye-safety/runs" && request.method() === "GET")
      return route.fulfill({ json: { items: state.phase === "idle" ? [] : [current()], nextOffset: null } });
    if (path === "scenarios/hawkeye-safety/runs" && request.method() === "POST") {
      expect(request.headers()["x-csrf-token"]).toBe("fixture");
      state.starts.push({ key: request.headers()["idempotency-key"], body: request.postDataJSON() });
      state.sample = (request.postDataJSON() as { sampleId: string }).sampleId;
      state.phase = "running";
      return route.fulfill({ status: 202, json: run("QUEUED", state.sample) });
    }
    if (path === `scenarios/hawkeye-safety/runs/${RUN_ID}`) {
      if (state.phase === "running" && ++state.reads > 2) state.phase = "done";
      return route.fulfill({ json: current() });
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${path}` } });
  });
  return state;
}
const noOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

test("the menu opens the page; start, progress and the stored result survive a refresh", async ({ page }, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const state = api(page);
  await page.goto("/ui/main/scenarios");
  if (testInfo.project.name === "desktop") {
    await page.getByRole("link", { name: "창고 지게차 안전 점검" }).click();
    await expect(page).toHaveURL(/\/ui\/main\/scenarios\/hawkeye$/);
  } else await page.goto("/ui/main/scenarios/hawkeye");
  await expect(page.getByText("Hawkeye Warehouse Safety VLM (Qwen3.5-2B LoRA, Q4_K_M + F16 vision encoder)")).toBeVisible();
  await expect(page.getByText("준비됨", { exact: true })).toBeVisible();
  await expect(page.getByText("etri-ser0001-cg0msb", { exact: true })).toBeVisible();
  await expect(page.getByText(/실제 현장 CCTV가 아닙니다/)).toBeVisible();
  await expect(page.getByAltText("forklift_0004 합성 창고 이미지")).toBeVisible();
  await expect(page.getByText("참조 라벨 safe · 합성 이미지")).toBeVisible();
  expect(await noOverflow(page)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("hawkeye-ready.png"), fullPage: true });

  await page.getByText("forklift_0024", { exact: true }).click();
  await page.getByRole("button", { name: "안전 점검 시작" }).click();
  await expect(page.getByRole("status").filter({ hasText: "추론 중" })).toBeVisible();
  await expect(page.getByRole("button", { name: "안전 점검 시작" })).toBeDisabled();
  expect(state.starts).toHaveLength(1);
  expect(state.starts[0].body).toEqual({ sampleId: "forklift_0024" });
  expect(state.starts[0].key).toMatch(/^[0-9a-f-]{36}$/);

  const result = page.locator(".hawkeye-run");
  await expect(result).toContainText("바닥 누출 (spill)", { timeout: 15000 });
  await expect(result).toContainText("A yellow forklift is operating in the center aisle between pallet racks.");
  await expect(result).toContainText("참조 라벨 safe과 다름");
  await expect(result).toContainText("785.0 ms (Ollama 처리, 로드 219.4 ms 포함) · API 왕복 790.1 ms");
  await expect(result).toContainText("etri-ser0001-cg0msb · GPU (VRAM 1.62 GB / 1.62 GB)");
  await expect(page.locator(".hawkeye-history")).toContainText("forklift_0024");
  expect(await noOverflow(page)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("hawkeye-result.png"), fullPage: true });

  await page.reload();
  await expect(page.locator(".hawkeye-run")).toContainText("바닥 누출 (spill)");
  await expect(page.locator(".hawkeye-history")).toContainText("forklift_0024");
  expect(state.starts).toHaveLength(1);
  expect(errors).toEqual([]);
});

test("unreadable output is a failure and never shown as safe", async ({ page }) => {
  api(page, { category: null, text: "The aisle looks fine." });
  await page.goto("/ui/main/scenarios/hawkeye");
  await page.getByRole("button", { name: "안전 점검 시작" }).click();
  const result = page.locator(".hawkeye-run");
  await expect(result).toContainText("범주 없음 · 판정 실패", { timeout: 15000 });
  await expect(page.getByRole("alert")).toContainText("UNPARSEABLE_OUTPUT");
  await expect(result).not.toContainText("위험 없음");
  await result.getByText("모델 원문 출력").click();
  await expect(result).toContainText("The aisle looks fine.");
});

test("an unready or undeployed runtime is explicit and cannot start a run", async ({ page }) => {
  const state = api(page);
  await page.unroute("**/api/control-plane/**");
  await page.route("**/api/control-plane/**", (route) => {
    const path = new URL(route.request().url()).pathname.replace("/api/control-plane/", "");
    if (path === "scenarios/hawkeye-safety")
      return route.fulfill({ json: capability(null, { status: "NOT_READY", error: "RuntimeError: warm-up produced no output",
        reported: { nodeName: "etri-ser0001-cg0msb", model: null, modelDigest: null, ollamaVersion: "0.32.5", processor: null, sizeBytes: null, sizeVramBytes: null } }) });
    if (path === "scenarios/hawkeye-safety/runs") return route.fulfill({ json: { items: [], nextOffset: null } });
    return route.fulfill({ status: 404, json: { message: `unmocked ${path}` } });
  });
  await page.goto("/ui/main/scenarios/hawkeye");
  await expect(page.getByText("준비 안 됨", { exact: true })).toBeVisible();
  await expect(page.getByText("RuntimeError: warm-up produced no output")).toBeVisible();
  await expect(page.getByRole("button", { name: "안전 점검 시작" })).toBeDisabled();

  await page.unroute("**/api/control-plane/**");
  await page.route("**/api/control-plane/**", (route) => {
    const path = new URL(route.request().url()).pathname.replace("/api/control-plane/", "");
    if (path === "scenarios/hawkeye-safety") return route.fulfill({ json: capability(null, null) });
    if (path === "scenarios/hawkeye-safety/runs") return route.fulfill({ json: { items: [], nextOffset: null } });
    return route.fulfill({ status: 404, json: { message: `unmocked ${path}` } });
  });
  await page.reload();
  await expect(page.getByText("Hawkeye 런타임이 아직 배포·설정되지 않았습니다.")).toBeVisible();
  await expect(page.getByText("미리보기 없음").first()).toBeVisible();
  await expect(page.getByRole("button", { name: "안전 점검 시작" })).toBeDisabled();
  expect(state.starts).toHaveLength(0);
  expect(await noOverflow(page)).toBe(true);
});
