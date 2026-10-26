import assert from "node:assert/strict";
import { test } from "node:test";
import {
  liveStatus,
  motionOverlay,
  pipelineGraph,
  readObservations,
  riskView,
  versionMode,
  type Observation,
} from "../kestra-native/hawkeye-video.ts";

const observer = {
  epoch: "e1", nodeName: "etri-ser0001-cg0msb", pod: "hawkeye-motion-0", provider: "CUDAExecutionProvider",
  model: { name: "rtdetr_warehouse", version: "1.0.2", sha256: "sha256:" + "c".repeat(64) },
};
/** The identity shape the TensorRT observer actually reports (forklift-motion-20261026 observer smoke). */
const runtimeObserver = {
  nodeName: "local-host", pod: "smoke", provider: "TensorRT 11.4.0.106 GPU (NVIDIA GeForce RTX 5060 Ti, sm120)",
  model: {
    provider: "TensorRT 11.4.0.106 GPU (NVIDIA GeForce RTX 5060 Ti, sm120)", model: "nvidia/tao/rtdetr_2d_warehouse:deployable_rn50_v1.0.2",
    modelDigest: "sha256:7b57da65ea42d0ed835da7ffee0656a7ba32337402ba8bff3dd1d0dd77b17084",
    engineDigest: "sha256:781a42cee4980ca7f804599c2bd822dc3eefb584fbbc2809c704184ce45b1477", precision: "fp32", input: [3, 640, 640], threshold: 0.5,
  },
};
const frame = (seq: number, over: Partial<Observation> = {}): Observation => ({
  seq, frameTime: `2026-10-26T08:00:0${seq}Z`, receivedAt: `2026-10-26T08:00:0${seq}.05Z`, width: 1280, height: 720, sha256: `${seq}`,
  detectMs: 41.2, gapMs: seq > 1 ? 500 : null, reset: seq === 1 ? "START" : null, status: "NEAR_PATH",
  tracks: [
    { id: "F3", class: "forklift", visible: true, confidence: 0.91, box: [100, 200, 300, 400], point: [200, 400],
      path: [[-1, 160, 380], [-0.5, 180, 390], [0, 200, 400]], velocity: [40, 20], predicted: [[0.5, 220, 410], [1, 240, 420]] },
    { id: "P7", class: "person", visible: true, confidence: 0.83, box: [600, 300, 660, 480], point: [630, 480], path: [[0, 630, 480]], velocity: null, predicted: null },
  ],
  pairs: [{ forklift: "F3", person: "P7", level: "NEAR_PATH", reason: null, distance: 2.4, minDistance: 0.6, timeToClosest: 1.5,
    closest: [[260, 430], [620, 470]] }],
  ...over,
});

test("observations are read only from the motion contract and keep the recording observer", () => {
  const doc = readObservations({
    apiVersion: "edgeai.hawkeye.motion/v1", source: {}, observer, horizonSeconds: 3,
    window: { seq: 2, fromSeq: 10, toSeq: 11, startedAt: "2026-10-26T08:00:00Z", finishedAt: "2026-10-26T08:00:03Z", evicted: 1 },
    units: { position: "image px", distance: "person bbox heights" }, frames: [frame(1), frame(2)],
  });
  assert.equal(doc.observer!.pod, "hawkeye-motion-0");
  assert.equal(doc.window!.evicted, 1);
  assert.deepEqual(doc.frames.map((f) => f.seq), [1, 2]);
  assert.throws(() => readObservations({ apiVersion: "edgeai.hawkeye.detections/v1", frames: [] }), /형식/);
});

test("overlay uses image coordinates: boxes, solid history ending at the box point, dashed future starting there", () => {
  const o = motionOverlay(frame(1));
  assert.deepEqual(o.viewBox, "0 0 1280 720");
  assert.deepEqual(o.boxes.map((b) => [b.id, b.cls, b.x, b.y, b.w, b.h, b.label]), [
    ["F3", "forklift", 100, 200, 200, 200, "F3 지게차 · 탐지 91%"],
    ["P7", "person", 600, 300, 60, 180, "P7 사람 · 탐지 83%"],
  ]);
  assert.deepEqual(o.history.map((h) => [h.id, h.points]), [["F3", "160,380 180,390 200,400"]]);
  // The future path starts at the current point so it continues the history line; a track without prediction has none.
  assert.deepEqual(o.future.map((h) => [h.id, h.points, h.steps]), [["F3", "200,400 220,410 240,420", [[220, 410], [240, 420]]]]);
  assert.deepEqual(o.pairs.map((p) => [p.key, p.level, p.from, p.to, p.label]), [["F3-P7", "NEAR_PATH", [260, 430], [620, 470], "1.5초 후 최근접"]]);
  assert.equal(motionOverlay(frame(1, { pairs: [{ ...frame(1).pairs[0], closest: null, timeToClosest: null }] })).pairs.length, 0);
});

test("risk text names the pair, distance unit and closest-approach time; NO_PAIR and UNKNOWN are never shown as safe", () => {
  const near = riskView(frame(2));
  assert.equal(near.tone, "danger");
  assert.equal(near.label, "경로 근접");
  assert.equal(near.safe, false);
  assert.deepEqual(near.lines, ["F3 ↔ P7 · 경로 근접 · 현재 2.4 → 최근접 0.6 (사람 키 배) · 1.5초 후"]);
  assert.equal(riskView(frame(2, { status: "APPROACHING" })).tone, "warning");
  const none = riskView(frame(2, { status: "NO_PAIR", pairs: [] }));
  assert.equal(none.safe, false);
  assert.match(none.label, /안전 판정 아님/);
  assert.match(riskView(frame(2, { status: "UNKNOWN" })).label, /판단 불가/);
  assert.equal(riskView(frame(2, { status: "UNKNOWN" })).safe, false);
  assert.equal(riskView(frame(2, { status: "NO_CONFLICT", pairs: [] })).safe, true);
  const young = riskView(frame(2, { status: "UNKNOWN", pairs: [{ ...frame(2).pairs[0], level: "UNKNOWN", reason: "INSUFFICIENT_HISTORY", minDistance: null, timeToClosest: null, closest: null }] }));
  assert.deepEqual(young.lines, ["F3 ↔ P7 · 이동 이력 부족 · 현재 2.4 (사람 키 배)"]);
  assert.equal(riskView(frame(1)).reset, "추적 시작");
  assert.equal(riskView(frame(2, { reset: "TIME_GAP" })).reset, "영상 시간 간격으로 추적 재시작");
  assert.equal(riskView(null).label, "관측 없음");
});

test("live is claimed only for a running session whose frames keep arriving", () => {
  const at = Date.parse("2026-10-26T08:00:10Z");
  assert.deepEqual(liveStatus({ state: "RUNNING", lastFrameAt: at - 1000, error: "", now: at }), { text: "실시간", tone: "success", live: true });
  assert.equal(liveStatus({ state: "RUNNING", lastFrameAt: at - 6000, error: "", now: at }).live, false);
  assert.match(liveStatus({ state: "RUNNING", lastFrameAt: at - 6000, error: "", now: at }).text, /지연/);
  assert.match(liveStatus({ state: "RUNNING", lastFrameAt: at - 500, error: "관측기 연결 불가", now: at }).text, /관측기 연결 불가/);
  assert.equal(liveStatus({ state: "RUNNING", lastFrameAt: null, error: "", now: at }).text, "첫 프레임 대기");
  assert.equal(liveStatus({ state: "STARTING", lastFrameAt: null, error: "", now: at }).text, "관측 시작 중");
  assert.deepEqual(liveStatus({ state: "FAILED", lastFrameAt: at, error: "", now: at, failure: { code: "MODEL_LOAD", message: "x" } }),
    { text: "관측 실패 · MODEL_LOAD", tone: "danger", live: false });
  assert.equal(liveStatus({ state: "STOPPED", lastFrameAt: at, error: "", now: at }).live, false);
});

/** P7 was missed in this frame: the contract keeps it for 1.5 s with no box or confidence, and its pair cannot be judged. */
const lostPerson = (seq: number) => {
  const base = frame(seq);
  return frame(seq, {
    status: "UNKNOWN",
    tracks: [base.tracks[0], { ...base.tracks[1], visible: false, confidence: null, box: null, velocity: null, predicted: null }],
    pairs: [{ ...base.pairs[0], level: "UNKNOWN", reason: "TRACK_LOST", minDistance: null, timeToClosest: null, closest: null }],
  });
};

test("a lost track keeps only its last point: no box or confidence is drawn as a current detection, and its pair is not safe", () => {
  const o = motionOverlay(lostPerson(3));
  assert.deepEqual(o.boxes.map((b) => b.id), ["F3"]);
  assert.deepEqual(o.lost.map((t) => [t.id, t.cls, t.x, t.y, t.label]), [["P7", "person", 630, 480, "P7 사람 · 추적 끊김"]]);
  assert.equal(o.pairs.length, 0);
  const risk = riskView(lostPerson(3));
  assert.equal(risk.safe, false);
  assert.match(risk.label, /판단 불가/);
  assert.deepEqual(risk.lines, ["F3 ↔ P7 · 추적 끊김 · 판단 불가 (마지막 위치 기준 2.4, 사람 키 배)"]);
  // Seen again: the same id is a detection with a box once more.
  assert.deepEqual(motionOverlay(frame(4)).lost, []);
  assert.deepEqual(motionOverlay(frame(4)).boxes.map((b) => b.id), ["F3", "P7"]);
  const doc = readObservations({ apiVersion: "edgeai.hawkeye.motion/v1", frames: [frame(2), lostPerson(3), frame(4)] });
  assert.deepEqual(doc.frames.map((f) => f.tracks.map((t) => `${t.id}:${t.visible}`)), [["F3:true", "P7:true"], ["F3:true", "P7:false"], ["F3:true", "P7:true"]]);
});

test("an observer source error on a running session withdraws the live claim but keeps the session running", () => {
  const at = Date.parse("2026-10-26T08:00:10Z");
  assert.deepEqual(liveStatus({ state: "RUNNING", lastFrameAt: at - 500, error: "", now: at, lastError: "RTSP read timeout" }),
    { text: "원본 읽기 오류 · RTSP read timeout", tone: "warning", live: false });
  assert.equal(liveStatus({ state: "RUNNING", lastFrameAt: at - 500, error: "", now: at, lastError: null }).live, true);
});

test("versions without a mode are the frame-judging v1 workflow", () => {
  assert.equal(versionMode({ mode: "MOTION" }), "MOTION");
  assert.equal(versionMode({}), "FRAMES");
});

test("motion graph puts the continuous observer between the camera and the snapshot Task, with its own reported location", () => {
  const dag = { tasks: [{ key: "snapshot" }], dependencies: [] };
  const graph = pipelineGraph({
    dag, placements: [], source: { name: "hawkeye-forklift-video", state: "PINNED", nodeName: "etri-dev0002-raspi5", details: [] },
    accelerator: null, sourceTask: "snapshot", acceleratorTask: "", result: null,
    observer: { status: "READY", basis: "현재 관측기 보고", ...observer },
  });
  assert.deepEqual(graph.nodes.map((n) => [n.id, n.kind, n.column]), [
    ["source", "external", 0], ["observer", "external", 1], ["task:snapshot", "task", 2], ["result", "result", 3],
  ]);
  assert.deepEqual(graph.edges.map((e) => [e.from, e.to]), [["source", "observer"], ["observer", "task:snapshot"], ["task:snapshot", "result"]]);
  const node = graph.nodes.find((n) => n.id === "observer")!;
  assert.equal(node.location, "etri-ser0001-cg0msb");
  const details = Object.fromEntries(node.details);
  assert.equal(details["Pod"], "hawkeye-motion-0");
  assert.equal(details["실행 제공자"], "CUDAExecutionProvider");
  assert.equal(details["모델"], "rtdetr_warehouse 1.0.2");
  // The snapshot Task is not placed on the observer's node by inference.
  assert.equal(graph.nodes.find((n) => n.id === "task:snapshot")!.location, null);
  const unknown = pipelineGraph({ dag, placements: [], source: null, accelerator: null, sourceTask: "snapshot", acceleratorTask: "", result: null, observer: null });
  assert.deepEqual(unknown.nodes.find((n) => n.id === "observer")!.status, "관측값 없음");
  assert.equal(unknown.nodes.find((n) => n.id === "observer")!.location, null);
});

test("motion graph shows the model identity the runtime observer actually reports", () => {
  const graph = pipelineGraph({
    dag: { tasks: [{ key: "snapshot" }], dependencies: [] }, placements: [], source: null, accelerator: null,
    sourceTask: "snapshot", acceleratorTask: "", result: null, observer: { status: "READY", ...runtimeObserver },
  });
  const details = Object.fromEntries(graph.nodes.find((n) => n.id === "observer")!.details);
  assert.equal(details["모델"], "nvidia/tao/rtdetr_2d_warehouse:deployable_rn50_v1.0.2");
  assert.equal(details["모델 digest"], runtimeObserver.model.modelDigest);
  assert.equal(details["엔진 digest"], runtimeObserver.model.engineDigest);
  assert.equal(details["정밀도"], "fp32");
});
