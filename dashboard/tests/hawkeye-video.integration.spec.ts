import { test, expect, type Page } from "@playwright/test";

// A stateful stand-in for the management API answers every control-plane call in the browser; this proves the
// rendered console only. The live workflow, Runner placement and GPU inference are recorded separately.
const WORKFLOW = "8b0f3c51-2f7e-4d0b-9a64-1f3f7b0d5a10";
const VERSION = "0d6c7a4e-9a5b-4c1e-8f3d-2b7e6a9c1d20";
const SESSION = "5e2d1c0b-7a6f-4e3d-9c8b-1a2b3c4d5e60";
const CAMERA_UID = "2dffb17e-028d-43ed-a992-b854b9d4c8ef";
const RUNS = ["a1000000-0000-4000-8000-000000000001", "a1000000-0000-4000-8000-000000000002"];
const task = (run: number, key: string) => `b${run}${key === "capture" ? "c" : "f"}00000-0000-4000-8000-000000000000`;
const JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAAA//EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AN//Z",
  "base64");
const camera = (id: string, uid: string, state: string, stateReason = "DMI") => ({
  id, uid, state, stateReason, targetNode: "etri-dev0002-raspi5", model: "m", lastSeen: 0, resourceVersion: "1",
  mapper: { name: "edgeai-rtsp", ready: true }, properties: {},
  profile: { name: id, protocol: "rtsp", host: "10.254.192.47", port: 18555, protocolConfig: { path: "/forklift-replay" }, properties: [],
    ...(id === "hawkeye-forklift-video" ? { sourceName: "hawkeye-forklift-replay" } : {}) },
});
const dag = {
  tasks: [
    { key: "capture", serviceProfileVersionId: "p1", parameters: {} },
    { key: "infer", serviceProfileVersionId: "p2", parameters: {} },
  ],
  dependencies: [{ fromTask: "capture", fromPort: "frames", toTask: "infer", toPort: "frames", mode: "BATCH" }],
};
const detections = {
  apiVersion: "edgeai.hawkeye.detections/v1",
  window: { requestedFrames: 2, distinctFrames: 2, startedAt: "2026-10-23T08:00:01Z", finishedAt: "2026-10-23T08:00:02Z" },
  accelerator: { nodeName: "etri-ser0001-cg0msb", pod: { name: "hawkeye-safety-0", uid: "c0ffee00-0000-4000-8000-000000000000" } },
  frames: [
    { seq: 1, frameTime: "2026-10-23T08:00:01Z", sha256: "aa", category: "forklift_violation", explanation: "A forklift crosses the pedestrian aisle.",
      processor: "GPU", inferenceMs: 612.3, roundTripMs: 640.8 },
    { seq: 2, frameTime: "2026-10-23T08:00:01.5Z", sha256: "bb", category: null, explanation: null, processor: "GPU", inferenceMs: 590, roundTripMs: 611 },
  ],
};

function api(page: Page) {
  const state = { phase: "idle" as "idle" | "first" | "second" | "stopped", reads: 0, starts: [] as { key: string; body: unknown }[], stops: 0 };
  const windows = () => {
    const w = [];
    if (state.phase !== "idle")
      w.push({ seq: 1, runId: RUNS[0], runState: state.phase === "first" ? "RUNNING" : "SUCCEEDED", createdAt: "2026-10-23T08:00:00Z",
        updatedAt: state.phase === "first" ? "2026-10-23T08:00:01Z" : "2026-10-23T08:00:10Z" });
    if (state.phase === "second" || state.phase === "stopped")
      w.push({ seq: 2, runId: RUNS[1], runState: state.phase === "second" ? "RUNNING" : "CANCELLED", createdAt: "2026-10-23T08:00:12Z",
        updatedAt: "2026-10-23T08:00:14Z" });
    return w.reverse();
  };
  const session = (detail = true) => ({
    id: SESSION, workflowVersionId: VERSION, camera: { id: "hawkeye-forklift-video", uid: CAMERA_UID }, maxWindows: 3,
    state: state.phase === "stopped" ? "STOPPED" : "RUNNING", endReason: state.phase === "stopped" ? "USER_STOPPED" : null,
    failureCode: null, failureMessage: null, createdAt: "2026-10-23T08:00:00Z", updatedAt: "2026-10-23T08:00:00Z",
    endedAt: state.phase === "stopped" ? "2026-10-23T08:00:15Z" : null, windowCount: windows().length, windows: detail ? windows() : [],
  });
  void page.route("**/api/control-plane/**", async (route) => {
    const request = route.request(), path = new URL(request.url()).pathname.replace("/api/control-plane/", "");
    const running = state.phase === "first" || state.phase === "second";
    if (path === "csrf") return route.fulfill({ json: { token: "fixture" } });
    if (path === "scenarios/hawkeye-safety/video")
      return route.fulfill({ json: { enabled: true, executes: true, workflowKey: "hawkeye-video-safety",
        workflow: { id: WORKFLOW, key: "hawkeye-video-safety", displayName: "Hawkeye 영상 안전 점검" },
        versions: [{ id: VERSION, version: "1.0.0", createdAt: "2026-10-23T07:00:00Z" }], maxWindows: 3, activeSessionId: running ? SESSION : null } });
    if (path === "scenarios/hawkeye-safety")
      return route.fulfill({ json: { runtime: { status: "READY", error: null, reported: { nodeName: "etri-ser0001-cg0msb", processor: "GPU" } } } });
    if (path === "managed-devices")
      return route.fulfill({ json: { items: [camera("hawkeye-forklift-video", CAMERA_UID, "ONLINE"),
        camera("rtsp-demo-test-pattern", "3e000000-0000-4000-8000-000000000000", "OFFLINE", "STALE_DMI_REPORT")] } });
    if (/^managed-devices\/[a-z0-9-]+\/preview$/.test(path)) return route.fulfill({ contentType: "image/jpeg", body: JPEG });
    if (path === `workflows/${WORKFLOW}`)
      return route.fulfill({ json: { workflow: { id: WORKFLOW }, versions: [{ id: VERSION, workflowId: WORKFLOW, version: "1.0.0", dag }], nextOffset: null } });
    if (path === "scenarios/hawkeye-safety/video/sessions" && request.method() === "GET")
      return route.fulfill({ json: { items: state.phase === "idle" ? [] : [session(false)], nextOffset: null } });
    if (path === "scenarios/hawkeye-safety/video/sessions" && request.method() === "POST") {
      expect(request.headers()["x-csrf-token"]).toBe("fixture");
      state.starts.push({ key: request.headers()["idempotency-key"], body: request.postDataJSON() });
      state.phase = "first";
      return route.fulfill({ status: 202, json: session() });
    }
    if (path === `scenarios/hawkeye-safety/video/sessions/${SESSION}`) {
      if (state.phase === "first" && ++state.reads > 2) state.phase = "second";
      return route.fulfill({ json: session() });
    }
    if (path === `scenarios/hawkeye-safety/video/sessions/${SESSION}/stop`) {
      state.stops++;
      state.phase = "stopped";
      return route.fulfill({ json: session() });
    }
    const runMatch = /^workflow-runs\/([0-9a-f-]+)(\/placements)?$/.exec(path);
    if (runMatch) {
      const n = RUNS.indexOf(runMatch[1]) + 1;
      const done = n === 1 && state.phase !== "first";
      const runState = done ? "SUCCEEDED" : state.phase === "stopped" ? "CANCELLED" : "RUNNING";
      const tasks = ["capture", "infer"].map((key) => ({ id: task(n, key), key, state: done ? "SUCCEEDED" : key === "capture" ? runState : "PENDING" }));
      const cameraSource = { deviceId: "hawkeye-forklift-video", deviceUid: CAMERA_UID, nodeName: "etri-dev0002-raspi5", mapperUrl: "http://10.254.192.30:7783",
        configurationSha256: `sha256:${"a".repeat(64)}`, sourceGeneration: "1", pinnedAt: "2026-10-23T08:00:00Z" };
      if (!runMatch[2]) return route.fulfill({ json: { run: { id: runMatch[1], state: runState, parameters: { cameraSource } }, tasks } });
      return route.fulfill({ json: tasks.map((t) => ({
        taskId: t.id, taskKey: t.key, taskState: t.state,
        attempt: t.state === "PENDING" ? null : { mode: "AUTO", number: 1 },
        runtime: t.state === "PENDING" ? null : { id: t.id, namespace: "edgeai-runtimes", jobName: `edgeai-${t.key}-${n}`,
          nodeName: t.key === "capture" ? "etri-ser0002-runner" : "etri-ser0003-runner", podUid: `${t.key}-pod-${n}`,
          desiredState: "RUNNING", observedState: done ? "SUCCEEDED" : "RUNNING", failureReason: null, updatedAt: "2026-10-23T08:00:05Z" },
      })) });
    }
    if (path === `tasks/${task(1, "infer")}/results`)
      return route.fulfill({ json: { taskId: task(1, "infer"), items: state.phase === "first" ? [] : [{ artifacts: [{ port: "detections", mediaType: "application/json" }] }] } });
    if (path === `tasks/${task(2, "infer")}/results`) return route.fulfill({ json: { taskId: task(2, "infer"), items: [] } });
    if (path === `tasks/${task(1, "infer")}/results/detections/content`) return route.fulfill({ json: detections });
    if (/^scenarios\/hawkeye-safety\/video\/tasks\/[0-9a-f-]+\/frames\/[0-9]+$/.test(path))
      return route.fulfill({ contentType: "image/jpeg", body: JPEG });
    return route.fulfill({ status: 404, json: { message: `unmocked ${path}` } });
  });
  return state;
}
const noOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

test("video is the default: start, follow windows on the DAG with real placement, read verified frames, stop, reload", async ({ page }, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const state = api(page);
  await page.goto("/ui/main/scenarios/hawkeye");
  await expect(page.getByRole("heading", { name: "영상 안전 점검" })).toBeVisible();
  await expect(page.getByAltText("hawkeye-forklift-video 최신 영상")).toBeVisible();
  await expect(page.getByText("연결됨", { exact: true })).toBeVisible();
  await expect(page.getByText("rtsp://10.254.192.47:18555/forklift-replay")).toBeVisible();
  await expect(page.getByText("녹화 영상 반복 재생", { exact: true })).toBeVisible();
  const startButton = page.getByRole("button", { name: "점검 시작" });
  await expect(startButton).toBeEnabled();
  expect(await noOverflow(page)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("video-ready.png"), fullPage: true });

  await startButton.click();
  await expect(page.getByRole("button", { name: "점검 중지" })).toBeVisible();
  expect(state.starts).toHaveLength(1);
  expect(state.starts[0].body).toEqual({ workflowVersionId: VERSION, camera: { id: "hawkeye-forklift-video", uid: CAMERA_UID } });
  expect(state.starts[0].key).toMatch(/^[0-9a-f-]{36}$/);
  await expect(page).toHaveURL(new RegExp(`session=${SESSION}`));

  const flow = page.getByRole("list", { name: "워크플로 단계" });
  await expect(flow.getByRole("button", { name: /capture/ })).toContainText("etri-ser0002-runner");
  await expect(flow.getByRole("button", { name: /Hawkeye 추론 서버/ })).toContainText("etri-ser0001-cg0msb");
  await expect(flow.getByRole("button", { name: /외부 입력/ })).toContainText("etri-dev0002-raspi5");
  await expect(flow.getByRole("button", { name: /외부 입력/ })).toContainText("실행 시 고정");
  // The infer Task has not been placed yet: no node is borrowed from the GPU server.
  await expect(flow.getByRole("button", { name: /infer/ })).toContainText("배치 관측 대기");
  await expect(flow.getByRole("button", { name: /infer/ })).not.toContainText("etri-ser0001");

  // The session advances to window 2; the earlier window keeps its verified result.
  await expect(page.getByRole("button", { name: /구간 2/ })).toContainText("이전 구간과 2.0초 간격", { timeout: 15000 });
  await page.getByRole("button", { name: /구간 1/ }).click();
  await expect(page.getByRole("link", { name: "실행 상세 열기" })).toHaveAttribute("href", new RegExp(`runs\\?run=${RUNS[0]}`));
  const inferNode = flow.getByRole("button", { name: /infer/ });
  await expect(inferNode).toContainText("etri-ser0003-runner");
  await inferNode.focus();
  await page.keyboard.press("Enter");
  const detail = page.getByRole("complementary", { name: "infer 상세" });
  await expect(detail).toContainText("etri-ser0003-runner");
  await expect(detail).toContainText("infer-pod-1");
  await flow.getByRole("button", { name: /Hawkeye 추론 서버/ }).click();
  await expect(page.getByRole("complementary", { name: "Hawkeye 추론 서버 상세" })).toContainText("이 구간의 판정 기록");

  const frames = page.getByRole("list").filter({ has: page.getByAltText("구간 1 프레임 1") });
  await expect(frames).toContainText("지게차 위반 (forklift_violation)");
  await expect(frames).toContainText("A forklift crosses the pedestrian aisle.");
  await expect(frames).toContainText("612 ms · 왕복 641 ms");
  await expect(frames).toContainText("범주 없음 · 판정 실패");
  await expect(page.getByText(/프레임 2장, 판정 실패 1장/)).toBeVisible();
  expect(await noOverflow(page)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("video-running.png"), fullPage: true });

  await page.getByRole("button", { name: "점검 중지" }).click();
  await expect(page.getByText("중지됨", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "점검 시작" })).toBeEnabled();
  expect(state.stops).toBe(1);

  await page.reload();
  await expect(page.getByText(/사용자 중지/)).toBeVisible();
  await expect(page.locator(".hv-history")).toContainText("hawkeye-forklift-video");
  expect(state.starts).toHaveLength(1);
  expect(errors).toEqual([]);
});

test("an offline camera cannot start and states why", async ({ page }) => {
  const state = api(page);
  await page.goto("/ui/main/scenarios/hawkeye");
  await page.locator(".kel-form-item").filter({ has: page.getByRole("combobox", { name: "카메라" }) }).locator(".kel-select").click();
  await page.getByRole("option", { name: /rtsp-demo-test-pattern/ }).click();
  await expect(page.getByText("카메라 연결이 끊겨 영상을 받을 수 없습니다.")).toBeVisible();
  await expect(page.getByText("녹화 영상 반복 재생", { exact: true })).toHaveCount(0);
  await expect(page.getByText(/카메라를 사용할 수 없습니다 · 상태 보고가 오래되었습니다/)).toBeVisible();
  await expect(page.getByRole("button", { name: "점검 시작" })).toBeDisabled();
  expect(state.starts).toHaveLength(0);
  expect(await noOverflow(page)).toBe(true);
});
