import assert from "node:assert/strict";
import { test } from "node:test";
import { cameraState, defaultSource, pinnedSource, pipelineGraph, readDetections, replayLabel, SCENES, sourceGroups, sourceView, windowStartIntervals } from "../kestra-native/hawkeye-video.ts";

const dag = {
  tasks: [{ key: "infer" }, { key: "capture" }],
  dependencies: [{ fromTask: "capture", fromPort: "frames", toTask: "infer", toPort: "frames" }],
};
const placement = (taskKey: string, nodeName: string | null, extra = {}) => ({
  taskId: `${taskKey}-id`, taskKey, taskState: "RUNNING",
  attempt: { mode: "AUTO", number: 1 },
  runtime: nodeName === null ? null : {
    nodeName, podUid: `${taskKey}-pod`, namespace: "edgeai-runtimes", jobName: `${taskKey}-job`, observedState: "RUNNING", failureReason: null,
  },
  ...extra,
});
const source = { name: "hawkeye-forklift-video", state: "PINNED", nodeName: "etri-dev0002-raspi5", details: [] };
const accelerator = { nodeName: "etri-ser0001-cg0msb", podUid: "adapter-pod", processor: "GPU", status: "READY" };

test("external source and accelerator are separate from DAG tasks, in topological order", () => {
  const graph = pipelineGraph({ dag, placements: [], source, accelerator, sourceTask: "capture", acceleratorTask: "infer", result: null });
  assert.deepEqual(graph.nodes.map((n) => [n.id, n.kind, n.column]), [
    ["source", "external", 0], ["task:capture", "task", 1], ["task:infer", "task", 2], ["accelerator", "external", 2], ["result", "result", 3],
  ]);
  assert.deepEqual(graph.edges.map((e) => [e.from, e.to, e.kind]), [
    ["source", "task:capture", "data"], ["task:capture", "task:infer", "data"], ["task:infer", "accelerator", "call"], ["task:infer", "result", "data"],
  ]);
});

test("Task placement comes only from the Run placement, never from the accelerator node", () => {
  const before = pipelineGraph({ dag, placements: [], source, accelerator, sourceTask: "capture", acceleratorTask: "infer", result: null });
  const infer = before.nodes.find((n) => n.id === "task:infer")!;
  assert.equal(infer.location, null);
  assert.equal(infer.status, "실행 전");
  const placed = pipelineGraph({
    dag, placements: [placement("capture", "etri-ser0002"), placement("infer", "etri-ser0003")],
    source, accelerator, sourceTask: "capture", acceleratorTask: "infer", result: null,
  });
  assert.equal(placed.nodes.find((n) => n.id === "task:infer")!.location, "etri-ser0003");
  assert.equal(placed.nodes.find((n) => n.id === "accelerator")!.location, "etri-ser0001-cg0msb");
  const waiting = pipelineGraph({
    dag, placements: [placement("capture", null, { taskState: "PENDING", attempt: null })],
    source, accelerator, sourceTask: "capture", acceleratorTask: "infer", result: null,
  });
  const capture = waiting.nodes.find((n) => n.id === "task:capture")!;
  assert.equal(capture.location, null);
  assert.equal(capture.status, "PENDING");
});

test("unknown source or accelerator is shown as unobserved, not as ready", () => {
  const graph = pipelineGraph({ dag, placements: [], source: null, accelerator: null, sourceTask: "capture", acceleratorTask: "infer", result: null });
  const external = graph.nodes.filter((n) => n.kind === "external");
  assert.deepEqual(external.map((n) => [n.status, n.tone, n.location]), [["관측값 없음", "warning", null], ["관측값 없음", "warning", null]]);
});

test("window intervals are measured start to start, never from Run.updatedAt", () => {
  assert.deepEqual(windowStartIntervals(["2026-10-23T08:00:00Z", "2026-10-23T08:00:15.5Z", "2026-10-23T08:00:29Z"]), [null, 15500, 13500]);
  assert.deepEqual(windowStartIntervals(["2026-10-23T08:00:00Z"]), [null]);
  assert.deepEqual(windowStartIntervals([]), []);
});

test("detections keep the window's single accelerator identity and unreadable frames as failures", () => {
  const read = readDetections({
    apiVersion: "edgeai.hawkeye.detections/v1",
    window: { requestedFrames: 2, startedAt: "2026-10-23T08:00:00Z", finishedAt: "2026-10-23T08:00:01.5Z", distinctFrames: 2 },
    accelerator: { nodeName: "etri-ser0001-cg0msb", pod: { name: "hawkeye-safety-0", uid: "adapter-pod" }, modelDigest: "d" },
    frames: [
      { seq: 1, frameTime: "2026-10-23T08:00:00.2Z", sha256: "a", category: "forklift_violation", explanation: "Forklift in aisle.", processor: "GPU", inferenceMs: 512.4, roundTripMs: 530.1 },
      { seq: 2, frameTime: "2026-10-23T08:00:01Z", sha256: "b", category: null, explanation: null, processor: "GPU", inferenceMs: 498, roundTripMs: 510 },
    ],
  });
  assert.deepEqual(read.accelerator, { nodeName: "etri-ser0001-cg0msb", podUid: "adapter-pod", processor: "GPU" });
  assert.deepEqual(read.window, { requestedFrames: 2, distinctFrames: 2, startedAt: "2026-10-23T08:00:00Z", finishedAt: "2026-10-23T08:00:01.5Z" });
  assert.deepEqual(read.frames.map((f) => [f.seq, f.category, f.sha256]), [[1, "forklift_violation", "a"], [2, null, "b"]]);
  assert.throws(() => readDetections({ apiVersion: "other/v1", frames: [] }), /형식/);
});

test("a camera can start only when ONLINE with a ready Mapper; otherwise the reason is given", () => {
  assert.deepEqual(cameraState({ state: "ONLINE", mapper: { ready: true } }), { online: true, label: "연결됨", reason: null });
  assert.deepEqual(cameraState({ state: "OFFLINE", stateReason: "STALE_DMI_REPORT", mapper: { ready: true } }),
    { online: false, label: "연결 끊김", reason: "상태 보고가 오래되었습니다" });
  assert.equal(cameraState({ state: "ONLINE", mapper: { ready: false } }).reason, "카메라 Mapper가 준비되지 않았습니다");
});

test("only the registered replays are labelled as looped recordings", () => {
  assert.equal(replayLabel({ profile: { protocol: "rtsp", sourceName: "hawkeye-forklift-replay" } }), "녹화 영상 반복 재생");
  assert.equal(replayLabel({ profile: { protocol: "rtsp", sourceName: "hawkeye-youtube-turn-replay" } }), "지게차 급회전 · 녹화 영상 반복 재생");
  assert.equal(replayLabel({ profile: { protocol: "rtsp", sourceName: "hawkeye-youtube-accidents-replay" } }), "지게차 사고 CCTV · 녹화 영상 반복 재생");
  assert.equal(replayLabel({ profile: { protocol: "rtsp", sourceName: "warehouse-cam-1" } }), null);
  assert.equal(replayLabel({ profile: { protocol: "rtsp" } }), null);
});

const scene = (id: string, title: string, sourceVideoId: string, durationSeconds: number) => ({
  id, title, sourceName: `hawkeye-scene-${id}-replay`, sourceVideoId, startFrame: 3, endFrame: 94, fps: 24, durationSeconds,
  sourceUrl: `https://www.youtube.com/watch?v=${sourceVideoId}`, sourceFps: 30,
});
const scenes = [scene("turn-01", "실내 랙 통로 회전", "tYYNdqPooMI", 3.04), scene("accidents-01", "야외 통로 보행자", "lSGMB3h41ug", 12.2)];
const device = (id: string, sourceName?: string) => ({ id, profile: { protocol: "rtsp", sourceName } });

test("scene devices are grouped by source clip with title and length; replays and cameras keep their own groups", () => {
  const cams = [device("cam-1"), device("hawkeye-forklift-video", "hawkeye-forklift-replay"), device("hawkeye-scene-accidents-01", "hawkeye-scene-accidents-01-replay"),
    device("hawkeye-scene-turn-01", "hawkeye-scene-turn-01-replay"), device("unknown-scene", "hawkeye-scene-turn-09-replay")];
  assert.deepEqual(sourceGroups(cams, scenes).map((g) => [g.label, g.items.map((i) => i.option)]), [
    ["녹화 장면 · 지게차 급회전", ["실내 랙 통로 회전 · 3초"]],
    ["녹화 장면 · 지게차 사고 CCTV", ["야외 통로 보행자 · 12.2초"]],
    ["기존 녹화 영상", ["hawkeye-forklift-video"]],
    ["카메라", ["cam-1", "unknown-scene"]],
  ]);
  const view = sourceView(cams[3], scenes);
  assert.equal(view.kind, "녹화 영상 · 실시간 분석 가능");
  assert.deepEqual(Object.fromEntries(view.details), {
    "원본 영상": "https://www.youtube.com/watch?v=tYYNdqPooMI", "원본 구간": "원본 30 fps 프레임 3–93 · 0.10–3.13초", "재생": "3초 반복 · 24 fps" });
  assert.equal(sourceView(cams[0], scenes).kind, "카메라");
  assert.equal(sourceView(cams[1], scenes).kind, "녹화 영상 반복 재생");
  assert.equal(replayLabel(cams[3], scenes), "지게차 급회전 · 실내 랙 통로 회전 · 녹화 장면 반복 재생");
  assert.equal(replayLabel(cams[0], scenes), null);
});

test("a fresh page selects the first online scene in catalog order, else an online camera", () => {
  const at = (id: string, state: string, sourceName?: string) => ({ ...device(id, sourceName), state, mapper: { ready: true } });
  const cam = at("cam-1", "ONLINE"), accident = at("a", "ONLINE", "hawkeye-scene-accidents-01-replay");
  assert.equal(defaultSource([cam, accident, at("t", "ONLINE", "hawkeye-scene-turn-01-replay")], scenes)?.id, "t");
  assert.equal(defaultSource([cam, accident, at("t", "OFFLINE", "hawkeye-scene-turn-01-replay")], scenes)?.id, "a");
  assert.equal(defaultSource([at("off", "OFFLINE"), cam], scenes)?.id, "cam-1");
  assert.equal(defaultSource([at("off", "OFFLINE")], scenes)?.id, "off");
  assert.equal(defaultSource([], scenes), null);
});

test("the bundled scene catalog names eight scenes in two clip groups, each with its own device source", () => {
  assert.equal(SCENES.length, 8);
  assert.equal(new Set(SCENES.map((s) => s.sourceName)).size, 8);
  const groups = sourceGroups(SCENES.map((s) => device(`dev-${s.id}`, s.sourceName)));
  assert.deepEqual(groups.map((g) => [g.label, g.items.length]), [["녹화 장면 · 지게차 급회전", 5], ["녹화 장면 · 지게차 사고 CCTV", 3]]);
  for (const s of SCENES) {
    assert.ok(s.title && s.durationSeconds > 0 && s.fps === 24 && s.endFrame > s.startFrame, s.id);
    assert.match(s.sourceUrl, /^https:\/\/www\.youtube\.com\//);
    assert.equal(s.sourceFps, 30, s.id);
  }
});

test("a past window shows the camera pinned in its Run, with the current connection marked as current", () => {
  const pinned = {
    deviceId: "hawkeye-forklift-video", deviceUid: "2dffb17e-028d-43ed-a992-b854b9d4c8ef", nodeName: "etri-dev0002-raspi5",
    mapperUrl: "http://10.254.192.30:7783", configurationSha256: "sha256:" + "a".repeat(64), sourceGeneration: "3", pinnedAt: "2026-10-23T08:00:00Z",
  };
  // After the run the camera moved to another node, changed configuration and went offline.
  const moved = { id: "hawkeye-forklift-video", uid: pinned.deviceUid, targetNode: "etri-dev0003-raspi5", state: "OFFLINE", stateReason: "DMI",
    mapper: { ready: true }, configurationSha256: "sha256:" + "b".repeat(64), profile: { protocol: "rtsp", sourceName: "hawkeye-forklift-replay" } };
  const graph = pipelineGraph({ dag, placements: [], source: pinnedSource(pinned, moved), accelerator, sourceTask: "capture", acceleratorTask: "infer", result: null });
  const source = graph.nodes.find((n) => n.id === "source")!;
  assert.equal(source.location, "etri-dev0002-raspi5");
  assert.equal(source.status, "PINNED");
  const details = Object.fromEntries(source.details);
  assert.equal(details["실행 시 Mapper 노드"], "etri-dev0002-raspi5");
  assert.equal(details["설정 digest"], pinned.configurationSha256);
  assert.equal(details["영상 종류"], "녹화 영상 반복 재생");
  assert.equal(details["현재 연결 (CURRENT)"], "연결 끊김 · etri-dev0003-raspi5 · 실행 이후 노드·설정 변경");
  assert.equal(pinnedSource(null, moved), null);
  assert.equal(Object.fromEntries(pinnedSource(pinned, undefined)!.details)["현재 연결 (CURRENT)"], "등록 해제됨");
});
