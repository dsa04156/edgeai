import { test, expect, type Page } from "@playwright/test";

// Every control-plane call is answered in the browser by a small stateful stand-in for the scenario API;
// no runtime or device runs here, so this proves rendered UI behavior only.
const capability = (activeRunId: string | null, extra = {}) => ({
  id: "gpu-npu-image-comparison", enabled: true, maxImageCount: 100, activeRunId,
  dataset: {
    name: "고정 100장 비교 데이터셋 (Imagenette2-160 val)", version: "imagenette100-resnet50-tflite-qualification/v1",
    preprocessing: "tensorflow-2.20.0-rgb-bilinear-shortest256-center224-float32",
    labelSource: "Imagenette2-160 val 폴더 WNID → ImageNet-1k 클래스 번호",
    manifestSha256: "7740c3420918e1264024dd41dbe6c0db18a2f1d283789afc89ef3d0db522d30f",
    inputsSha256: "cb0d2db8020f4880f41e033e69bdd67f6591d16d41caf712a6eda651324150d5", imageCount: 100,
  },
  targets: [
    { id: "GPU", modelName: "ResNet-v1-50 (TFLite → ONNX opset 18)", expectedModelSha256: "1737a0c64aff1a923bd73b426fe40700bb7bf2e7c1449bad2c90eda03d93249d",
      expectedProvider: "CUDAExecutionProvider", status: "READY",
      reported: { nodeName: "etri-ser0002-cgnmsb", deviceName: "NVIDIA GeForce RTX 5080", provider: "CUDAExecutionProvider",
        precision: "FP32 ONNX (TF32 disabled)", runtime: "onnxruntime 1.23.2, CUDA driver 13000", modelSha256: "1737a0c64aff1a923bd73b426fe40700bb7bf2e7c1449bad2c90eda03d93249d" } },
    { id: "NPU", modelName: "ResNet-v1-50 (Hailo Model Zoo HEF)", expectedModelSha256: "a1d82e9121c66e772257490cb3af904d1e90fb4387ad5f683fbc5efe1a05f9f7",
      expectedProvider: "HailoRT", status: "READY",
      reported: { nodeName: "etri-dev0006-raspi5", deviceName: "Hailo-8 0001:01:00.0", provider: "HailoRT",
        precision: "HEF compiled (internal quantization not verified), float32 I/O", runtime: "HailoRT 4.23.0", modelSha256: "a1d82e9121c66e772257490cb3af904d1e90fb4387ad5f683fbc5efe1a05f9f7" } },
  ],
  ...extra,
});
const RUN_ID = "0b9d4f3e-5f0c-4f1a-9a33-2a7d2b1c9e10";
const summary = (target: string, n: number, correct: number) => ({
  target, processed: n, succeeded: n, failed: 0, meanInferenceMs: target === "GPU" ? 2.7 : 3.5, p50InferenceMs: 2.6, p95InferenceMs: 3.4,
  meanRoundTripMs: target === "GPU" ? 4.1 : 9.8, computeImagesPerSecond: target === "GPU" ? 370.4 : 285.7, callWallSeconds: n * 0.004,
  callWallImagesPerSecond: target === "GPU" ? 243.9 : 102.0, pairedLabeledCorrect: correct,
});
function run(state: string, dispatched: number, requested = 10) {
  const started = state !== "QUEUED";
  return {
    id: RUN_ID, state, requestedCount: requested, dispatchedCount: dispatched, createdAt: "2026-10-22T09:00:00Z",
    startedAt: started ? "2026-10-22T09:00:01Z" : null, stopRequestedAt: ["STOPPING", "STOPPED"].includes(state) ? "2026-10-22T09:00:02Z" : null,
    finishedAt: ["STOPPED", "SUCCEEDED"].includes(state) ? "2026-10-22T09:00:03Z" : null, failureCode: null, failureMessage: null,
    snapshot: started ? { dataset: { kind: "imagenette100-resnet50-tflite-qualification", preprocessing: "tf", manifestSha256: capability(null).dataset.manifestSha256,
      inputsSha256: capability(null).dataset.inputsSha256, imageCount: 100 },
      targets: capability(null).targets.map((t) => ({ id: t.id, modelName: t.modelName, modelSha256: t.expectedModelSha256, ...t.reported })) } : null,
    comparison: started ? { targets: [summary("GPU", dispatched, dispatched), summary("NPU", dispatched, dispatched - 1)], pairedCount: dispatched,
      top1AgreementCount: dispatched - 1, labelsTrusted: true, runWallSeconds: 1.5, pairedImagesPerRunWallSecond: dispatched / 1.5 } : null,
  };
}
const outcome = (top1: number) => ({ status: "SUCCEEDED", top1, topK: [{ classIndex: top1, score: 9.1 }], inferenceMs: 2.6, roundTripMs: 4.2, failureCode: null });
const results = (n: number) => Array.from({ length: n }, (_, index) => ({
  index, path: `imagenette2-160/val/n03028079/${index}.JPEG`, label: 497, referenceTop1: 497,
  tensorSha256: "3e7fe88618e2c1da74831e39b7d69a7730b379796c053eecd7ad67404f6b8a82",
  gpu: outcome(497), npu: outcome(index === 1 ? 406 : 497), top1Agreement: index !== 1,
}));

/** Server-side state of the stand-in: progresses on each detail read, stops when asked. */
function api(page: Page, options: { start?: { status: number; json: unknown } } = {}) {
  const state = { phase: "idle" as "idle" | "running" | "stopping" | "stopped", dispatched: 0, stoppingReads: 0, starts: [] as { key: string; body: unknown }[], stops: 0 };
  void page.route("**/api/control-plane/**", async (route) => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname.replace("/api/control-plane/", "");
    const current = () => state.phase === "running" ? run("RUNNING", state.dispatched) : state.phase === "stopping" ? run("STOPPING", state.dispatched) : run("STOPPED", state.dispatched);
    if (path === "csrf") return route.fulfill({ json: { token: "fixture" } });
    if (path === "scenarios/image-comparison")
      return route.fulfill({ json: capability(["running", "stopping"].includes(state.phase) ? RUN_ID : null) });
    if (path === "scenarios/image-comparison/runs" && request.method() === "GET")
      return route.fulfill({ json: { items: state.phase === "idle" ? [] : [{ ...current(), snapshot: null, comparison: null }], nextOffset: null } });
    if (path === "scenarios/image-comparison/runs" && request.method() === "POST") {
      expect(request.headers()["x-csrf-token"]).toBe("fixture");
      state.starts.push({ key: request.headers()["idempotency-key"], body: request.postDataJSON() });
      if (options.start) return route.fulfill(options.start);
      state.phase = "running";
      return route.fulfill({ status: 202, json: run("QUEUED", 0) });
    }
    if (path === `scenarios/image-comparison/runs/${RUN_ID}`) {
      if (state.phase === "running" && state.dispatched < 3) state.dispatched++;
      // The image in flight finishes a little after Stop was accepted.
      else if (state.phase === "stopping" && ++state.stoppingReads > 2) state.phase = "stopped";
      return route.fulfill({ json: current() });
    }
    if (path === `scenarios/image-comparison/runs/${RUN_ID}/results`)
      return route.fulfill({ json: { items: results(state.dispatched), nextOffset: null } });
    if (path === `scenarios/image-comparison/runs/${RUN_ID}/stop` && request.method() === "POST") {
      state.stops++;
      if (state.phase === "running") state.phase = "stopping";
      return route.fulfill({ json: current() });
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${path}` } });
  });
  return state;
}
const noOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

test("start, follow progress, stop and refresh show only the server's run state", async ({ page }, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const state = api(page);
  await page.goto("/ui/main/scenarios");
  await expect(page.getByText("고정 100장 비교 데이터셋 (Imagenette2-160 val)")).toBeVisible();
  for (const text of ["NVIDIA GeForce RTX 5080", "etri-ser0002-cgnmsb", "ResNet-v1-50 (TFLite → ONNX opset 18)",
    "Hailo-8 0001:01:00.0", "etri-dev0006-raspi5", "ResNet-v1-50 (Hailo Model Zoo HEF)"])
    await expect(page.getByText(text, { exact: true }).first()).toBeVisible();
  await expect(page.getByText(/정밀도가 서로 다릅니다/)).toBeVisible();
  expect(await noOverflow(page)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("scenario-ready.png"), fullPage: true });

  const count = page.getByLabel("이미지 수");
  await count.fill("0");
  await expect(page.getByText("이미지 수는 1~100 사이의 정수로 입력하세요.")).toBeVisible();
  await expect(page.getByRole("button", { name: "비교 시작" })).toBeDisabled();
  await count.fill("10");
  await count.press("Enter");
  await expect(page.getByRole("status").filter({ hasText: "실행 중" })).toBeVisible();
  expect(state.starts).toHaveLength(1);
  expect(state.starts[0].body).toEqual({ imageCount: 10 });
  expect(state.starts[0].key).toMatch(/^[0-9a-f-]{36}$/);
  await expect(page.getByText("전송 3/10")).toBeVisible({ timeout: 15000 });
  await expect(page.getByRole("button", { name: "비교 시작" })).toBeDisabled();

  await page.getByRole("button", { name: "중지" }).click();
  await expect(page.getByText("중지 중 · 처리 중인 1장 완료 대기")).toBeVisible();
  await expect(page.getByText("중지됨", { exact: true })).toBeVisible({ timeout: 15000 });
  expect(state.stops).toBe(1);
  await expect(page.getByRole("button", { name: "중지" })).toHaveCount(0);
  await expect(page.getByText("중지로 일부만 처리됨 · 요청 10장 · 전송 3장 · 양쪽 성공(비교 기준) 3장")).toBeVisible();
  const devices = page.locator(".scenario-targets");
  await expect(devices).toContainText("4.10 ms");
  await expect(devices).toContainText("243.9 장/s");
  await expect(devices).toContainText("3/3 (100.0%)");
  await expect(devices).toContainText("2/3 (66.7%)");
  await expect(page.getByText("top-1 일치 2/3")).toBeVisible();
  const images = page.locator(".scenario-images");
  await expect(images).toContainText("497 church");
  await expect(images).toContainText("406");
  expect(await noOverflow(page)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("scenario-stopped.png"), fullPage: true });

  await page.reload();
  await expect(page.getByText("중지됨", { exact: true })).toBeVisible();
  await expect(page.getByText("중지로 일부만 처리됨 · 요청 10장 · 전송 3장 · 양쪽 성공(비교 기준) 3장")).toBeVisible();
  expect(state.starts).toHaveLength(1);
  expect(errors).toEqual([]);
});

test("rejections and unready targets are explicit and never start a run", async ({ page }) => {
  const state = api(page, { start: { status: 409, json: { code: "SCENARIO_RUN_ACTIVE", message: "이미 진행 중인 비교 실행이 있습니다." } } });
  await page.goto("/ui/main/scenarios");
  await page.getByLabel("이미지 수").fill("5");
  await page.getByRole("button", { name: "비교 시작" }).click();
  await expect(page.getByRole("alert")).toContainText("이미 진행 중인 비교 실행이 있습니다.");
  expect(state.starts).toHaveLength(1);

  await page.unroute("**/api/control-plane/**");
  const unready = capability(null);
  unready.targets[1] = { ...unready.targets[1], status: "UNAVAILABLE", reported: null } as never;
  await page.route("**/api/control-plane/scenarios/image-comparison", (route) => route.fulfill({ json: unready }));
  await page.route("**/api/control-plane/scenarios/image-comparison/runs?**", (route) => route.fulfill({ json: { items: [], nextOffset: null } }));
  await page.reload();
  await expect(page.getByText("연결 불가", { exact: true })).toBeVisible();
  await expect(page.getByText("ResNet-v1-50 (Hailo Model Zoo HEF)")).toBeVisible();
  await expect(page.getByRole("button", { name: "비교 시작" })).toBeDisabled();

  await page.unroute("**/api/control-plane/scenarios/image-comparison");
  await page.route("**/api/control-plane/scenarios/image-comparison", (route) =>
    route.fulfill({ json: { ...capability(null), enabled: false, targets: [] } }));
  await page.reload();
  await expect(page.getByText("GPU/NPU 비교 런타임이 아직 배포·설정되지 않았습니다.")).toBeVisible();
  await expect(page.getByRole("button", { name: "비교 시작" })).toBeDisabled();
  expect(await noOverflow(page)).toBe(true);
});

test("an image left without results by an interrupted run is never shown as processing", async ({ page }) => {
  const interrupted = { ...run("RUNNING", 1), state: "INTERRUPTED", finishedAt: "2026-10-22T09:00:03Z", failureCode: "PROCESS_STOPPED",
    failureMessage: null };
  const image = { ...results(1)[0], gpu: null, npu: null, top1Agreement: null };
  await page.route("**/api/control-plane/**", (route) => {
    const path = new URL(route.request().url()).pathname.replace("/api/control-plane/", "");
    if (path === "scenarios/image-comparison") return route.fulfill({ json: capability(null) });
    if (path === "scenarios/image-comparison/runs") return route.fulfill({ json: { items: [interrupted], nextOffset: null } });
    if (path === `scenarios/image-comparison/runs/${RUN_ID}`) return route.fulfill({ json: interrupted });
    if (path === `scenarios/image-comparison/runs/${RUN_ID}/results`) return route.fulfill({ json: { items: [image], nextOffset: null } });
    return route.fulfill({ status: 404, json: { message: `unmocked ${path}` } });
  });
  await page.goto("/ui/main/scenarios");
  const images = page.locator(".scenario-images");
  await expect(images).toContainText("결과 없음 · 실행 중단");
  await expect(images).not.toContainText("처리 중");
});

test("a results page that became empty keeps Previous so the user can go back", async ({ page }) => {
  const finished = run("SUCCEEDED", 3);
  await page.route("**/api/control-plane/**", (route) => {
    const url = new URL(route.request().url()), path = url.pathname.replace("/api/control-plane/", "");
    if (path === "scenarios/image-comparison") return route.fulfill({ json: capability(null) });
    if (path === "scenarios/image-comparison/runs") return route.fulfill({ json: { items: [finished], nextOffset: null } });
    if (path === `scenarios/image-comparison/runs/${RUN_ID}`) return route.fulfill({ json: finished });
    if (path === `scenarios/image-comparison/runs/${RUN_ID}/results`)
      // A stale next link leads to a page with nothing on it.
      return route.fulfill({ json: url.searchParams.get("offset") === "0" ? { items: results(3), nextOffset: 20 } : { items: [], nextOffset: null } });
    return route.fulfill({ status: 404, json: { message: `unmocked ${path}` } });
  });
  await page.goto("/ui/main/scenarios");
  const images = page.locator(".scenario-images");
  await expect(images).toContainText("497 church");
  await images.getByRole("button", { name: "다음" }).click();
  await expect(images.getByText("2 페이지")).toBeVisible();
  await expect(images.getByRole("button", { name: "이전" })).toBeEnabled();
  await images.getByRole("button", { name: "이전" }).click();
  await expect(images).toContainText("497 church");
});
