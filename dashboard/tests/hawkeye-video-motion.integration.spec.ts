import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { test, expect, type Page, type TestInfo } from "@playwright/test";

// A stateful stand-in for the management API (public BFF seam) drives the motion workflow UI in the browser.
// It proves the rendered console only; the observer, snapshot Task placement and model output are verified live elsewhere.
const WORKFLOW = "8b0f3c51-2f7e-4d0b-9a64-1f3f7b0d5a10";
const V2 = "0d6c7a4e-9a5b-4c1e-8f3d-2b7e6a9c1d22";
const V1 = "0d6c7a4e-9a5b-4c1e-8f3d-2b7e6a9c1d20";
const SESSION = "5e2d1c0b-7a6f-4e3d-9c8b-1a2b3c4d5e61";
const OLD = "5e2d1c0b-7a6f-4e3d-9c8b-1a2b3c4d5e60";
const CAMERA_UID = "2dffb17e-028d-43ed-a992-b854b9d4c8ef";
const RUNS = ["c1000000-0000-4000-8000-000000000001", "c1000000-0000-4000-8000-000000000002"];
const SNAP = ["d1000000-0000-4000-8000-000000000001", "d1000000-0000-4000-8000-000000000002"];
const TINY = Buffer.from(
  "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAAA//EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AN//Z",
  "base64");
// The recorded forklift clip frame makes screenshots readable when it is present locally; geometry checks do not depend on it.
const SAMPLE = `${process.cwd()}/../output/verification/hawkeye-video-20261023/forklift-frame.jpg`;
const JPEG = existsSync(SAMPLE) ? readFileSync(SAMPLE) : TINY;
const EVIDENCE = `${process.cwd()}/../output/verification/forklift-motion-20261026/ui`;
const W = 960, H = 540;
const observer = { epoch: "obs-1", nodeName: "etri-ser0001-cg0msb", pod: "hawkeye-motion-0", provider: "CUDAExecutionProvider",
  model: { name: "rtdetr_warehouse", version: "1.0.2", sha256: `sha256:${"c".repeat(64)}` } };

/** The forklift moves 20 px right per frame toward a standing person; frame 3 has no person in view. */
/** The same frame with the person missed by the detector: kept as a recent track with no box, and the pair is undecidable. */
function lostObservation(seq: number) {
  const o = observation(seq % 2 ? 1 : 2);
  return { ...o, seq, status: "UNKNOWN",
    tracks: [o.tracks[0], { ...o.tracks[1], visible: false, confidence: null, box: null }],
    pairs: [{ ...o.pairs[0], level: "UNKNOWN", reason: "TRACK_LOST", minDistance: null, timeToClosest: null, closest: null }] };
}
function observation(seq: number) {
  const fx = 120 + 20 * seq;
  const forklift = { id: "F3", class: "forklift", visible: true, confidence: 0.91, box: [fx, 220, fx + 180, 400], point: [fx + 90, 400],
    path: [[-1, fx + 50, 400], [-0.5, fx + 70, 400], [0, fx + 90, 400]], velocity: [40, 0],
    predicted: [[0.5, fx + 110, 400], [1, fx + 130, 401], [1.5, fx + 150, 402]] };
  const person = { id: "P7", class: "person", visible: true, confidence: 0.83, box: [620, 250, 680, 430] as number[] | null, point: [650, 430],
    path: [[0, 650, 430]], velocity: null, predicted: null };
  const noPerson = seq === 3;
  return {
    seq, frameTime: new Date(Date.parse("2026-10-26T08:00:00Z") + seq * 500).toISOString(),
    receivedAt: new Date(Date.parse("2026-10-26T08:00:00.05Z") + seq * 500).toISOString(), width: W, height: H, sha256: `${seq}`,
    detectMs: 38.4, gapMs: seq > 1 ? 500 : null, reset: seq === 1 ? "START" : null, status: noPerson ? "NO_PAIR" : "NEAR_PATH",
    tracks: noPerson ? [forklift] : [forklift, person],
    pairs: noPerson ? [] : [{ forklift: "F3", person: "P7", level: "NEAR_PATH", reason: null, distance: 2.4, minDistance: 0.6, timeToClosest: 1.5,
      closest: [[fx + 150, 402], [640, 425]] }],
  };
}
const observations = { apiVersion: "edgeai.hawkeye.motion/v1", source: {}, observer, horizonSeconds: 3,
  window: { seq: 1, fromSeq: 1, toSeq: 4, startedAt: "2026-10-26T08:00:00.5Z", finishedAt: "2026-10-26T08:00:02Z", evicted: 0 },
  units: { position: "image px", distance: "person bbox heights" }, frames: [1, 2, 3, 4].map(observation) };
const camera = { id: "hawkeye-forklift-video", uid: CAMERA_UID, state: "ONLINE", stateReason: "DMI", targetNode: "etri-dev0002-raspi5",
  model: "m", lastSeen: 0, resourceVersion: "1", mapper: { name: "edgeai-rtsp", ready: true }, properties: {},
  profile: { name: "hawkeye-forklift-video", protocol: "rtsp", host: "10.254.192.47", port: 18555, protocolConfig: { path: "/forklift-replay" },
    properties: [], sourceName: "hawkeye-forklift-replay" } };

// Scene devices as the replay catalog registers them: one per scene, named by the catalog's own sourceName.
const SCENES: { id: string; title: string; sourceName: string; deviceName: string; rtspPath: string; sourceVideoId: string }[] =
  JSON.parse(readFileSync(`${process.cwd()}/../hawkeye-runtime/video-replay/scenes.json`, "utf8")).scenes;
const sceneCamera = (s: (typeof SCENES)[number], n: number) => ({ ...camera, id: s.deviceName, uid: `5c000000-0000-4000-8000-00000000000${n}`,
  targetNode: "etri-dev0008-raspi5", profile: { ...camera.profile, name: s.deviceName, protocolConfig: { path: s.rtspPath }, sourceName: s.sourceName } });
const scenes = [SCENES[0], SCENES.find((s) => s.sourceVideoId !== SCENES[0].sourceVideoId)!].map(sceneCamera);

function api(page: Page) {
  const state = {
    phase: "idle" as "idle" | "running" | "stopped", starts: [] as unknown[], liveSeq: 0, liveFail: 0, evict: new Set<number>(), latency: 120,
    sourceFps: 0, sourceAt: 0, sourceBase: 0, frameReads: [] as number[], live: [] as number[], lost: false, lastError: null as string | null, inFlight: 0, maxInFlight: 0, historyReads: new Map<string, number>(), historyInFlight: 0, historyMax: 0,
    /** A finished session of the replay camera is already stored; the scene devices are registered. */
    stored: false, started: camera as { id: string; uid: string },
  };
  const old = (detail = true) => ({
    id: OLD, workflowVersionId: V2, mode: "MOTION", camera: { id: camera.id, uid: CAMERA_UID }, maxWindows: 5, state: "STOPPED", endReason: "USER_STOPPED",
    failureCode: null, failureMessage: null, createdAt: "2026-10-26T07:00:00Z", updatedAt: "2026-10-26T07:00:20Z", endedAt: "2026-10-26T07:00:20Z", windowCount: 1,
    windows: detail ? [{ seq: 1, runId: RUNS[0], runState: "SUCCEEDED", createdAt: "2026-10-26T07:00:00Z", updatedAt: "2026-10-26T07:00:10Z" }] : [],
  });
  const windows = () => state.phase === "idle" ? [] : [
    { seq: 2, runId: RUNS[1], runState: state.phase === "running" ? "RUNNING" : "CANCELLED", createdAt: "2026-10-26T08:00:03Z", updatedAt: "2026-10-26T08:00:04Z" },
    { seq: 1, runId: RUNS[0], runState: "SUCCEEDED", createdAt: "2026-10-26T08:00:00Z", updatedAt: "2026-10-26T08:00:03Z" },
  ];
  const session = (detail = true) => ({
    id: SESSION, workflowVersionId: V2, mode: "MOTION", camera: { id: state.started.id, uid: state.started.uid }, maxWindows: 5,
    state: state.phase === "stopped" ? "STOPPED" : "RUNNING", endReason: state.phase === "stopped" ? "USER_STOPPED" : null,
    failureCode: null, failureMessage: null, createdAt: "2026-10-26T08:00:00Z", updatedAt: "2026-10-26T08:00:00Z",
    endedAt: state.phase === "stopped" ? "2026-10-26T08:00:20Z" : null, windowCount: windows().length, windows: detail ? windows() : [],
  });
  const track = async <T>(work: () => Promise<T>) => {
    state.maxInFlight = Math.max(state.maxInFlight, ++state.inFlight);
    try {
      await new Promise((resolve) => setTimeout(resolve, state.latency));
      return await work();
    } finally {
      state.inFlight--;
    }
  };
  void page.route("**/api/control-plane/**", async (route) => {
    const request = route.request(), path = new URL(request.url()).pathname.replace("/api/control-plane/", "");
    const base = "scenarios/hawkeye-safety/video";
    if (path === "csrf") return route.fulfill({ json: { token: "fixture" } });
    if (path === base)
      return route.fulfill({ json: { enabled: true, executes: true, workflowKey: "hawkeye-video-safety",
        workflow: { id: WORKFLOW, key: "hawkeye-video-safety", displayName: "Hawkeye 영상 안전 점검" },
        versions: [{ id: V2, version: "2.0.0", createdAt: "2026-10-26T07:00:00Z", mode: "MOTION" },
          { id: V1, version: "1.0.0", createdAt: "2026-10-23T07:00:00Z", mode: "FRAMES" }],
        maxWindows: 5, activeSessionId: state.phase === "running" ? SESSION : null, observer: { status: "READY", ...observer } } });
    if (path === "scenarios/hawkeye-safety")
      return route.fulfill({ json: { runtime: { status: "READY", error: null, reported: { nodeName: "etri-ser0001-cg0msb", processor: "GPU" } } } });
    if (path === "managed-devices") return route.fulfill({ json: { items: [camera, ...(state.stored ? scenes : [])] } });
    if (/^managed-devices\/[a-z0-9-]+\/preview$/.test(path)) return route.fulfill({ contentType: "image/jpeg", body: JPEG });
    if (path === `workflows/${WORKFLOW}`)
      return route.fulfill({ json: { workflow: { id: WORKFLOW }, nextOffset: null, versions: [
        { id: V2, workflowId: WORKFLOW, version: "2.0.0", dag: { tasks: [{ key: "snapshot", serviceProfileVersionId: "p3", parameters: {} }], dependencies: [] } },
        { id: V1, workflowId: WORKFLOW, version: "1.0.0", dag: { tasks: [{ key: "capture" }, { key: "infer" }],
          dependencies: [{ fromTask: "capture", fromPort: "frames", toTask: "infer", toPort: "frames" }] } },
      ] } });
    if (path === `${base}/sessions` && request.method() === "GET")
      return route.fulfill({ json: { items: [...(state.phase === "idle" ? [] : [session(false)]), ...(state.stored ? [old(false)] : [])], nextOffset: null } });
    if (path === `${base}/sessions/${OLD}`) return route.fulfill({ json: old() });
    if (path === `${base}/sessions` && request.method() === "POST") {
      state.starts.push(request.postDataJSON());
      state.started = request.postDataJSON().camera;
      state.phase = "running";
      return route.fulfill({ status: 202, json: session() });
    }
    if (path === `${base}/sessions/${SESSION}`) return route.fulfill({ json: session() });
    if (path === `${base}/sessions/${SESSION}/stop`) {
      state.phase = "stopped";
      return route.fulfill({ json: session() });
    }
    if (path === `${base}/sessions/${SESSION}/live`) {
      state.live.push(Date.now());
      return track(async () => {
        if (state.phase !== "running") return route.fulfill({ status: 409, json: { code: "SESSION_NOT_LIVE", message: "ended" } });
        if (state.liveFail) return route.fulfill({ status: state.liveFail, json: { code: "OBSERVER_UNAVAILABLE", message: "unavailable" } });
        // With sourceFps set, the observer's latest frame advances on the source clock rather than on every read.
        if (state.sourceFps && !state.sourceAt) [state.sourceAt, state.sourceBase] = [Date.now(), state.liveSeq];
        const seq = (state.liveSeq = state.sourceFps
          ? state.sourceBase + Math.floor(((Date.now() - state.sourceAt) * state.sourceFps) / 1000)
          : state.liveSeq + 1);
        return route.fulfill({ json: { sessionId: SESSION, state: "RUNNING", failure: null, lastError: state.lastError, observer,
          stats: { processed: seq, resets: 1, lastDetectMs: 38.4 }, latest: state.lost ? lostObservation(seq) : { ...observation(seq % 2 ? 1 : 2), seq } } });
      });
    }
    const liveFrame = new RegExp(`^${base}/sessions/${SESSION}/live/frames/(\\d+)$`).exec(path);
    if (liveFrame) state.frameReads.push(Number(liveFrame[1]));
    if (liveFrame)
      return track(async () => state.evict.has(Number(liveFrame[1]))
        ? route.fulfill({ status: 404, json: { code: "FRAME_EVICTED", message: "evicted" } })
        : route.fulfill({ contentType: "image/jpeg", headers: { "cache-control": "no-store" }, body: JPEG }));
    const runMatch = /^workflow-runs\/([0-9a-f-]+)(\/placements)?$/.exec(path);
    if (runMatch) {
      const n = RUNS.indexOf(runMatch[1]);
      const done = n === 0;
      const tasks = [{ id: SNAP[n], key: "snapshot", state: done ? "SUCCEEDED" : "RUNNING" }];
      const cameraSource = { deviceId: camera.id, deviceUid: CAMERA_UID, nodeName: "etri-dev0002-raspi5", mapperUrl: "http://10.254.192.30:7783",
        configurationSha256: `sha256:${"a".repeat(64)}`, sourceGeneration: "1", pinnedAt: "2026-10-26T08:00:00Z" };
      if (!runMatch[2]) return route.fulfill({ json: { run: { id: runMatch[1], state: done ? "SUCCEEDED" : "RUNNING", parameters: { cameraSource } }, tasks } });
      return route.fulfill({ json: tasks.map((t) => ({ taskId: t.id, taskKey: t.key, taskState: t.state, attempt: { mode: "AUTO", number: 1 },
        runtime: { id: t.id, namespace: "edgeai-runtimes", jobName: `edgeai-snapshot-${n + 1}`, nodeName: "etri-ser0002-runner", podUid: `snapshot-pod-${n + 1}`,
          desiredState: "RUNNING", observedState: done ? "SUCCEEDED" : "RUNNING", failureReason: null, updatedAt: "2026-10-26T08:00:05Z" } })) });
    }
    if (path === `tasks/${SNAP[0]}/results`)
      return route.fulfill({ json: { taskId: SNAP[0], items: [{ artifacts: [{ port: "frames", mediaType: "application/x-tar" }, { port: "observations", mediaType: "application/json" }] }] } });
    if (path === `tasks/${SNAP[1]}/results`) return route.fulfill({ json: { taskId: SNAP[1], items: [] } });
    if (path === `tasks/${SNAP[0]}/results/observations/content`) return route.fulfill({ json: observations });
    if (/^scenarios\/hawkeye-safety\/video\/tasks\/[0-9a-f-]+\/frames\/[0-9]+$/.test(path)) {
      state.historyReads.set(path, (state.historyReads.get(path) ?? 0) + 1);
      state.historyMax = Math.max(state.historyMax, ++state.historyInFlight);
      await new Promise((resolve) => setTimeout(resolve, 150));
      state.historyInFlight--;
      return route.fulfill({ contentType: "image/jpeg", body: JPEG });
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${path}` } });
  });
  return state;
}
const noOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
/** The console scrolls inside its own container, so evidence is captured per section. */
async function shot(page: Page, info: TestInfo, name: string, selector = ".hv-control") {
  mkdirSync(EVIDENCE, { recursive: true });
  await page.locator(selector).first().evaluate((el) => el.scrollIntoView({ block: "start" }));
  await page.screenshot({ path: `${EVIDENCE}/${name}-${info.project.name}.png` });
}
/** The overlay box as a fraction of the displayed frame, which must equal the observation's pixel box over the frame size. */
async function boxFraction(page: Page, id: string) {
  return page.locator(".hv-stage").evaluate((stage, track) => {
    const frame = stage.querySelector(".ms-frame")!.getBoundingClientRect();
    const box = stage.querySelector(`[data-track="${track}"]`)!.getBoundingClientRect();
    return [(box.left - frame.left) / frame.width, (box.top - frame.top) / frame.height, box.width / frame.width, box.height / frame.height];
  }, id);
}
const near = (actual: number[], expected: number[]) => actual.forEach((v, i) => expect(v).toBeCloseTo(expected[i], 2));
const stageAlt = (page: Page) => page.locator(".hv-stage img").getAttribute("alt");

test("live motion: one compact clip with synced boxes, history/future paths and closest-approach marker; DAG shows observer and snapshot placement", async ({ page }, info) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const state = api(page);
  await page.goto("/ui/main/scenarios/hawkeye");
  await expect(page.getByText(/3초 앞 경로를 예측합니다/)).toBeVisible();
  await expect(page.getByText("녹화 영상 반복 재생", { exact: true })).toBeVisible();
  await expect(page.getByTestId("stage-badge")).toHaveText("카메라 미리보기");
  await page.getByRole("button", { name: "점검 시작" }).click();
  await expect(page.getByRole("button", { name: "실시간 관측" })).toHaveAttribute("aria-pressed", "true");
  expect(state.starts).toEqual([{ workflowVersionId: V2, camera: { id: camera.id, uid: CAMERA_UID } }]);
  await expect(page.getByTestId("stage-badge")).toHaveText("실시간");
  await expect(page.locator(".hv-stage img")).toHaveCount(1);
  // Overlay and image always come from the same observation: the alt names the frame whose boxes are drawn.
  const seq = Number((await stageAlt(page))!.replace("실시간 프레임 ", ""));
  const shown = { ...observation(seq % 2 ? 1 : 2), seq };
  const [x1, y1, x2, y2] = shown.tracks[0].box;
  near(await boxFraction(page, "F3"), [x1 / W, y1 / H, (x2 - x1) / W, (y2 - y1) / H]);
  const overlay = page.locator(".hv-stage svg");
  const style = (selector: string) => overlay.locator(selector).evaluate((el) => getComputedStyle(el).strokeDasharray);
  expect(await style('[data-history="F3"]')).toBe("none");
  expect(await style('[data-future="F3"]')).not.toBe("none");
  await expect(overlay.locator('[data-future="F3"]')).toHaveAttribute("points", new RegExp(`^${shown.tracks[0].point.join(",")} `));
  await expect(overlay.locator('[data-pair="F3-P7"]')).toContainText("1.5초 후 최근접");
  const risk = page.getByRole("region", { name: "충돌 위험 예측" });
  await expect(risk).toContainText("경로 근접");
  await expect(risk).toContainText("F3 ↔ P7 · 경로 근접 · 현재 2.4 → 최근접 0.6 (사람 키 배) · 1.5초 후");
  await expect(risk).toContainText("실제 충돌 시각·미터 거리가 아닙니다");

  const flow = page.getByRole("list", { name: "워크플로 단계" });
  await expect(flow.getByRole("button", { name: /외부 입력/ })).toContainText("etri-dev0002-raspi5");
  await expect(flow.getByRole("button", { name: /지게차·사람 추적 관측기/ })).toContainText("etri-ser0001-cg0msb");
  await expect(flow.getByRole("button", { name: /snapshot/ })).toContainText("etri-ser0002-runner");
  await expect(flow.getByRole("button", { name: /관측 기록/ })).toBeVisible();
  await flow.getByRole("button", { name: /지게차·사람 추적 관측기/ }).click();
  const detail = page.getByRole("complementary", { name: "지게차·사람 추적 관측기 상세" });
  await expect(detail).toContainText("hawkeye-motion-0");
  await expect(detail).toContainText("CUDAExecutionProvider");
  await expect(detail).toContainText("rtdetr_warehouse 1.0.2");

  const height = await page.locator(".hv-stage .ms-frame").evaluate((el) => el.getBoundingClientRect().height);
  expect(height).toBeLessThanOrEqual(info.project.name === "desktop" ? 440 : 260);
  expect(await noOverflow(page)).toBe(true);
  await page.waitForTimeout(600);
  await shot(page, info, "live");
  await shot(page, info, "dag", "section:has(#hv-graph-title)");

  // Reads slower than 1/24 s (two 120 ms reads per tick) run back to back, never overlapping: about 4 ticks per second.
  let from = state.live.length;
  await page.waitForTimeout(3000);
  expect(state.live.length - from).toBeGreaterThanOrEqual(9);
  expect(state.live.length - from).toBeLessThanOrEqual(14);
  // Fast reads sample a 24 fps source at most 48 times a second; each new frame's JPEG is read once, and the shown rate is the measured one.
  state.latency = 5;
  state.sourceFps = 24;
  await page.waitForTimeout(500);
  from = state.live.length;
  const reads = state.frameReads.length;
  await page.waitForTimeout(3000);
  expect(state.live.length - from).toBeGreaterThanOrEqual(60);
  expect(state.live.length - from).toBeLessThanOrEqual(3 * 48 + 2);
  expect(state.maxInFlight).toBe(1);
  expect(new Set(state.frameReads).size).toBe(state.frameReads.length);
  expect(state.frameReads.length - reads).toBeGreaterThanOrEqual(3 * 20);
  const displayed = Number(/표시 ([0-9.]+) fps/.exec((await page.locator(".hv-stage figcaption").textContent()) ?? "")?.[1]);
  expect(displayed).toBeGreaterThan(10);
  expect(displayed).toBeLessThanOrEqual(25);
  await expect(page.locator(".hv-stage figcaption")).toContainText(/관측 [0-9.]+ fps/);

  await page.getByRole("button", { name: "점검 중지" }).click();
  await expect(page.getByRole("button", { name: "점검 시작" })).toBeVisible();
  await expect(page.getByRole("button", { name: "구간 기록" })).toHaveAttribute("aria-pressed", "true");
  const after = state.live.length;
  await page.waitForTimeout(2500);
  expect(state.live.length).toBe(after);
  expect(errors).toEqual([]);
});

test("live errors keep the last frame labelled as delayed, evicted frames are skipped, and leaving live stops polling", async ({ page }) => {
  const state = api(page);
  await page.goto("/ui/main/scenarios/hawkeye");
  await page.getByRole("button", { name: "점검 시작" }).click();
  await expect(page.getByTestId("stage-badge")).toHaveText("실시간");
  state.liveFail = 503;
  await expect(page.getByTestId("stage-badge")).toHaveText("지연 · 관측기 연결 불가");
  await expect(page.locator(".hv-stage img")).toHaveAttribute("src", /^blob:/);
  await expect(page.getByText("실시간", { exact: true })).toHaveCount(0);
  state.evict.add(state.liveSeq + 1);
  state.liveFail = 0;
  await expect(page.locator(".hv-stage figcaption")).toContainText("보관 범위를 벗어나 건너뜀");
  await expect(page.getByTestId("stage-badge")).toHaveText("실시간");
  await page.getByRole("button", { name: "구간 기록" }).click();
  await expect(page.getByRole("heading", { name: "구간 2 관측 기록" })).toBeVisible();
  const after = state.live.length;
  await page.waitForTimeout(2500);
  expect(state.live.length).toBe(after);
});

test("history: one frame at a time with its own overlay, play/pause and scrubbing; polling never moves the reviewed frame", async ({ page }, info) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const state = api(page);
  await page.goto("/ui/main/scenarios/hawkeye");
  await page.getByRole("button", { name: "점검 시작" }).click();
  await page.getByRole("button", { name: /구간 1/ }).click();
  await expect(page.getByRole("heading", { name: "구간 1 관측 기록" })).toBeVisible();
  await expect(page.getByText(/검증된 관측 기록 · 프레임 4장/)).toBeVisible();
  await expect(page.locator(".hv-stage img")).toHaveCount(1);
  await expect(page.getByAltText("구간 1 프레임 1")).toHaveAttribute("src", /^blob:/);
  const box = (seq: number) => { const [a, b, c, d] = observation(seq).tracks[0].box; return [a / W, b / H, (c - a) / W, (d - b) / H]; };
  near(await boxFraction(page, "F3"), box(1));
  await expect(page.getByTestId("stage-badge")).toHaveText("기록");

  await page.getByRole("button", { name: "다음 프레임" }).click();
  await expect(page.getByAltText("구간 1 프레임 2")).toBeVisible();
  near(await boxFraction(page, "F3"), box(2));
  // The session is still running; its 2 s poll re-reads the window but must not move the reviewed frame or window.
  await page.waitForTimeout(4500);
  await expect(page.getByAltText("구간 1 프레임 2")).toBeVisible();
  await expect(page.getByRole("button", { name: /구간 1/ })).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("slider", { name: "프레임 위치" }).fill("2");
  await expect(page.getByAltText("구간 1 프레임 3")).toBeVisible();
  await expect(page.getByRole("region", { name: "충돌 위험 예측" })).toContainText("지게차·사람 동시 탐지 없음 · 안전 판정 아님");
  await expect(page.locator(".hv-stage [data-track='P7']")).toHaveCount(0);
  await page.getByRole("button", { name: "이전 프레임" }).click();
  await expect(page.getByAltText("구간 1 프레임 2")).toBeVisible();
  await shot(page, info, "history");
  await page.getByRole("button", { name: "재생" }).click();
  await expect(page.getByAltText("구간 1 프레임 4")).toBeVisible({ timeout: 4000 });
  await expect(page.getByRole("button", { name: "재생" })).toBeVisible();
  expect([...state.historyReads.values()].every((n) => n === 1)).toBe(true);
  expect(state.historyReads.size).toBe(4);
  expect(state.historyMax).toBeLessThanOrEqual(2);

  // Window 2 has no observations yet: nothing of window 1 may stay under its label.
  await page.getByRole("button", { name: /구간 2/ }).click();
  await expect(page.getByRole("heading", { name: "구간 2 관측 기록" })).toBeVisible();
  await expect(page.getByText("관측 기록이 확정되면 표시합니다.").first()).toBeVisible();
  await expect(page.getByAltText(/구간 1 프레임/)).toHaveCount(0);
  await expect(page.locator(".hv-stage [data-track]")).toHaveCount(0);
  expect(await noOverflow(page)).toBe(true);
  expect(errors).toEqual([]);
});

test("live: a person lost by the detector is shown as an undecidable last position, not a box; seen again restores the box", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const state = api(page);
  await page.goto("/ui/main/scenarios/hawkeye");
  await page.getByRole("button", { name: "점검 시작" }).click();
  await expect(page.getByTestId("stage-badge")).toHaveText("실시간");
  const overlay = page.locator(".hv-stage svg"), risk = page.getByRole("region", { name: "충돌 위험 예측" });
  state.lost = true;
  await expect(overlay.locator('[data-lost="P7"]')).toHaveCount(1);
  await expect(overlay.locator('[data-track="P7"]')).toHaveCount(0);
  await expect(overlay.locator('[data-track="F3"]')).toHaveCount(1);
  await expect(overlay).toContainText("P7 사람 · 추적 끊김");
  await expect(risk).toContainText("판단 불가 · 안전 판정 아님");
  await expect(risk).toContainText("F3 ↔ P7 · 추적 끊김 · 판단 불가");
  await expect(risk).not.toContainText("3초 내 근접 예측 없음");
  // A source read error on a still-running session keeps the last frame but is not called live.
  state.lastError = "RTSP read timeout";
  await expect(page.getByTestId("stage-badge")).toHaveText("원본 읽기 오류 · RTSP read timeout");
  await expect(page.locator(".hv-stage img")).toHaveAttribute("src", /^blob:/);
  state.lastError = null;
  state.lost = false;
  await expect(overlay.locator('[data-track="P7"]')).toHaveCount(1);
  await expect(overlay.locator("[data-lost]")).toHaveCount(0);
  await expect(page.getByTestId("stage-badge")).toHaveText("실시간");
  expect(errors).toEqual([]);
});

test("choosing another scene shows that source's preview, not a stored session's history; starting goes live on it", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const state = api(page);
  state.stored = true;
  // A plain load does not reopen the newest stored session; it previews the first online scene of the catalog.
  const [turn, accident] = scenes, title = (id: string) => SCENES.find((s) => s.deviceName === id)!.title;
  await page.goto("/ui/main/scenarios/hawkeye");
  await expect(page.getByTestId("stage-badge")).toHaveText("카메라 미리보기");
  await expect(page.getByAltText(`${turn.id} 최신 영상`)).toBeVisible();
  await expect(page.locator(".hv-camera")).toContainText("녹화 영상 · 실시간 분석 가능");
  await expect(page.getByRole("button", { name: "구간 기록" })).toHaveCount(0);
  await expect(page.locator(".hv-history")).toContainText(camera.id);
  // An explicit session link still opens that session's records.
  await page.goto(`/ui/main/scenarios/hawkeye?session=${OLD}`);
  await expect(page.getByRole("heading", { name: "구간 1 관측 기록" })).toBeVisible();
  await expect(page.locator(".hv-stage [data-track]").first()).toBeVisible();

  await page.locator(".kel-form-item").filter({ has: page.getByRole("combobox", { name: "카메라" }) }).locator(".kel-select").click();
  await expect(page.getByText("녹화 장면 · 지게차 급회전", { exact: true })).toBeVisible();
  await expect(page.getByText("녹화 장면 · 지게차 사고 CCTV", { exact: true })).toBeVisible();
  await expect(page.getByRole("option", { name: new RegExp(`^${title(accident.id)} · [0-9.]+초 · 연결됨$`) })).toBeVisible();
  await page.getByRole("option", { name: new RegExp(`^${title(turn.id)} · `) }).click();
  await expect(page.getByTestId("stage-badge")).toHaveText("카메라 미리보기");
  await expect(page.getByAltText(`${turn.id} 최신 영상`)).toBeVisible();
  await expect(page.getByRole("button", { name: "구간 기록" })).toHaveCount(0);
  await expect(page.locator(".hv-session")).toHaveCount(0);
  await expect(page.locator(".hv-stage [data-track]")).toHaveCount(0);
  await expect(page.getByRole("region", { name: "충돌 위험 예측" })).toHaveCount(0);
  await expect(page).not.toHaveURL(/session=/);
  await expect(page.locator(".hv-camera")).toContainText("녹화 영상 · 실시간 분석 가능");
  await expect(page.locator(".hv-camera")).toContainText(title(turn.id));
  await expect(page.getByText(/10초 구간은 관측 기록을 저장하는 단위입니다/)).toBeVisible();
  // The 10 s refresh must not bring the stored session back.
  await page.waitForTimeout(11000);
  await expect(page.getByAltText(`${turn.id} 최신 영상`)).toBeVisible();

  // Browsing a stored record still works and names its own source.
  await page.locator(".hv-history").getByRole("button").first().click();
  await expect(page.getByRole("heading", { name: "구간 1 관측 기록" })).toBeVisible();
  await expect(page.locator(".hv-camera")).toContainText("녹화 영상 반복 재생");
  await page.locator(".kel-form-item").filter({ has: page.getByRole("combobox", { name: "카메라" }) }).locator(".kel-select").click();
  await page.getByRole("option", { name: new RegExp(`^${title(turn.id)} · `) }).click();
  await expect(page.getByTestId("stage-badge")).toHaveText("카메라 미리보기");

  await page.getByRole("button", { name: "점검 시작" }).click();
  await expect(page.getByRole("button", { name: "실시간 관측" })).toHaveAttribute("aria-pressed", "true");
  expect(state.starts).toEqual([{ workflowVersionId: V2, camera: { id: turn.id, uid: turn.uid } }]);
  await expect(page.getByTestId("stage-badge")).toHaveText("실시간");
  await expect(page.locator(".hv-summary")).toContainText(turn.id);
  expect(await noOverflow(page)).toBe(true);
  expect(errors).toEqual([]);
});
