// Display model of the Hawkeye video workflow graph; every value comes from the API, nothing is inferred.
import catalog from "../../hawkeye-runtime/video-replay/scenes.json" with { type: "json" };

type Dag = {
  tasks: { key: string }[];
  dependencies: { fromTask: string; fromPort?: string; toTask: string; toPort?: string }[];
};
type Placement = {
  taskKey: string;
  taskState: string;
  attempt: { mode: string; number: number } | null;
  runtime: {
    nodeName: string | null;
    podUid: string | null;
    namespace: string;
    jobName: string | null;
    observedState: string;
    failureReason: string | null;
  } | null;
};
type Source = { name: string; state: string; nodeName: string | null; details: [string, string][] };
type Accelerator = { nodeName: string | null; podUid: string | null; processor: string | null; status: string; basis?: string };
type Result = { state: string; verified: number } | null;

export type Tone = "success" | "danger" | "warning" | "info";
export type GraphNode = {
  id: string;
  kind: "external" | "task" | "result";
  kindLabel: string;
  label: string;
  status: string;
  tone: Tone;
  location: string | null;
  column: number;
  row: number;
  details: [string, string][];
};
export type GraphEdge = { from: string; to: string; kind: "data" | "call" };

export const tone = (state: string): Tone =>
  ["SUCCEEDED", "ONLINE", "READY", "RUNNING", "VERIFIED", "ANSWERED", "COMPLETED", "RECORDED"].includes(state)
    ? state === "RUNNING"
      ? "info"
      : "success"
    : ["FAILED", "OFFLINE", "UNAVAILABLE", "LOST", "INTERRUPTED"].includes(state)
      ? "danger"
      : ["CANCELLED", "STOPPED", "NOT_READY", "UNKNOWN"].includes(state)
        ? "warning"
        : "info";

const STATUS: Record<string, string> = {
  QUEUED: "대기",
  PENDING: "대기",
  DISPATCHING: "배치 중",
  RUNNING: "실행 중",
  SUCCEEDED: "완료",
  FAILED: "실패",
  CANCELLING: "취소 중",
  CANCELLED: "취소됨",
  STOPPING: "중지 중",
  STOPPED: "중지됨",
  INTERRUPTED: "중단됨",
  ONLINE: "연결됨",
  OFFLINE: "연결 끊김",
  UNKNOWN: "확인 안 됨",
  READY: "준비됨",
  NOT_READY: "준비 안 됨",
  MISMATCH: "설정과 다름",
  UNAVAILABLE: "연결 불가",
  PINNED: "실행 시 고정",
  ANSWERED: "판정함",
  VERIFIED: "검증됨",
  COMPLETED: "완료",
  USER_STOPPED: "사용자 중지",
  MAX_WINDOWS: "최대 구간 도달",
  WINDOW_FAILED: "구간 실패",
  WINDOW_REJECTED: "구간 생성 거부",
  RECORDED: "기록됨",
  STARTING: "시작 중",
  EXPIRED: "만료됨",
};
export const statusLabel = (state: string) => STATUS[state] ?? state;
export const sessionLabel = statusLabel;

export type VideoMode = "MOTION" | "FRAMES";
/** Versions published before the motion workflow carry no mode and judge sampled frames. */
export const versionMode = (v: { mode?: VideoMode | null }): VideoMode => v.mode ?? "FRAMES";
export type ObserverIdentity = {
  epoch?: string | number | null;
  nodeName: string | null;
  pod: string | null;
  provider: string | null;
  /** The runtime reports {model, modelDigest, engineDigest, precision, ...}; {name, version, sha256} is the earlier fixture shape. */
  model: {
    model?: string;
    modelDigest?: string;
    engineDigest?: string;
    precision?: string;
    name?: string;
    version?: string;
    sha256?: string;
  } | null;
};
const modelLabel = (m: ObserverIdentity["model"]) => m?.model ?? ([m?.name, m?.version].filter(Boolean).join(" ") || null);
const modelDigest = (m: ObserverIdentity["model"]) => m?.modelDigest ?? m?.sha256 ?? null;
export type VideoCapability = {
  enabled: boolean;
  executes: boolean;
  workflowKey: string;
  workflow: { id: string; key: string; displayName: string } | null;
  versions: { id: string; version: string; createdAt: string; mode?: VideoMode }[];
  maxWindows: number;
  activeSessionId: string | null;
  observer?: (ObserverIdentity & { status: string; error?: string | null }) | null;
};
export type VideoWindow = { seq: number; runId: string; runState: string; createdAt: string; updatedAt: string };
export type VideoSession = {
  id: string;
  workflowVersionId: string;
  camera: { id: string; uid: string };
  maxWindows: number;
  state: string;
  endReason: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  mode?: VideoMode;
  createdAt: string;
  updatedAt: string;
  endedAt: string | null;
  windowCount: number;
  /** Newest first; empty in list responses. */
  windows: VideoWindow[];
};
export type Camera = {
  id: string;
  uid: string;
  targetNode: string;
  state: string;
  stateReason?: string;
  mapper: { ready: boolean };
  configurationSha256?: string;
  profile: { protocol: string; host?: string; port?: number; sourceName?: string; protocolConfig?: { path?: string } };
};
export type Frame = {
  seq: number;
  frameTime: string | null;
  sha256: string;
  category: string | null;
  explanation: string | null;
  processor: string | null;
  inferenceMs: number | null;
  roundTripMs: number | null;
};
type Detections = {
  apiVersion: string;
  window?: { requestedFrames: number; distinctFrames: number; startedAt: string; finishedAt: string };
  accelerator?: { nodeName: string | null; pod?: { uid?: string | null } } | null;
  frames: (Frame & Record<string, unknown>)[];
};
/** The infer Task's `detections` output (edgeai.hawkeye.detections/v1); one accelerator answered the whole window. */
export function readDetections(value: unknown) {
  const doc = value as Detections;
  if (doc?.apiVersion !== "edgeai.hawkeye.detections/v1" || !Array.isArray(doc.frames))
    throw new Error("판정 결과 형식을 읽을 수 없습니다.");
  const frames: Frame[] = doc.frames.map((f) => ({
    seq: f.seq,
    frameTime: f.frameTime ?? null,
    sha256: f.sha256,
    category: f.category ?? null,
    explanation: f.explanation ?? null,
    processor: f.processor ?? null,
    inferenceMs: f.inferenceMs ?? null,
    roundTripMs: f.roundTripMs ?? null,
  }));
  return {
    frames,
    accelerator: doc.accelerator
      ? { nodeName: doc.accelerator.nodeName ?? null, podUid: doc.accelerator.pod?.uid || null, processor: frames[0]?.processor ?? null }
      : null,
    window: doc.window
      ? { requestedFrames: doc.window.requestedFrames, distinctFrames: doc.window.distinctFrames, startedAt: doc.window.startedAt, finishedAt: doc.window.finishedAt }
      : null,
  };
}
export type DetectionsView = ReturnType<typeof readDetections>;

const REASONS: Record<string, string> = {
  STALE_DMI_REPORT: "상태 보고가 오래되었습니다",
  MAPPER_NOT_READY: "카메라 Mapper가 준비되지 않았습니다",
};
/** Only an ONLINE camera with a ready Mapper can start a session; anything else carries its reason. */
export function cameraState(c: Pick<Camera, "state" | "stateReason" | "mapper">) {
  const online = c.state === "ONLINE" && c.mapper.ready;
  const reason = online ? null : (REASONS[c.stateReason ?? ""] ?? (c.mapper.ready ? "카메라가 응답하지 않습니다" : REASONS.MAPPER_NOT_READY));
  return { online, label: online ? "연결됨" : c.state === "OFFLINE" ? "연결 끊김" : "사용 불가", reason };
}
/** Registered replays loop a recorded clip (sourceName from hawkeye-runtime/video-replay/source*.json); other cameras carry no such label. */
const REPLAY_LABELS = new Map([
  ["hawkeye-forklift-replay", "녹화 영상 반복 재생"],
  ["hawkeye-youtube-turn-replay", "지게차 급회전 · 녹화 영상 반복 재생"],
  ["hawkeye-youtube-accidents-replay", "지게차 사고 CCTV · 녹화 영상 반복 재생"],
]);
/** One recorded CCTV scene cut from a source clip (hawkeye-runtime/video-replay/scenes.json); its device reports `sourceName`. */
export type Scene = {
  id: string;
  title: string;
  sourceName: string;
  sourceVideoId: string;
  /** Half-open frame range of the original clip. */
  startFrame: number;
  endFrame: number;
  fps: number;
  durationSeconds: number;
  /** Size of the replayed clip; the Mapper downsizes decoded frames to fit 640×640 before analysis. */
  width: number;
  height: number;
  /** Original clip page and frame rate (startFrame/endFrame count its frames), from the catalog's sources. */
  sourceUrl: string;
  sourceFps: number;
};
const SOURCES: Record<string, { url: string; fps: number }> = catalog.sources;
export const SCENES: Scene[] = catalog.scenes.map((s: Omit<Scene, "sourceUrl" | "sourceFps">) => ({ ...s, sourceUrl: SOURCES[s.sourceVideoId].url, sourceFps: SOURCES[s.sourceVideoId].fps }));
const SCENE_GROUPS: [string, string][] = [
  ["hse-", "HSE 실제 사고"],
  ["turn-", "지게차 급회전"],
  ["accidents-", "지게차 사고 CCTV"],
];
const sceneGroup = (s: Scene) => SCENE_GROUPS.find(([prefix]) => s.id.startsWith(prefix))?.[1] ?? "녹화 장면";
/** Only devices whose sourceName is in the catalog are scenes. */
export const sceneOf = (c: Pick<Camera, "profile">, scenes = SCENES) => scenes.find((s) => s.sourceName === c.profile.sourceName) ?? null;
const seconds = (n: number) => `${Number(n.toFixed(1))}초`;
export const replayLabel = (c: Pick<Camera, "profile">, scenes = SCENES) => {
  const scene = sceneOf(c, scenes);
  return scene ? `${sceneGroup(scene)} · ${scene.title} · 녹화 장면 반복 재생` : (REPLAY_LABELS.get(c.profile.sourceName ?? "") ?? null);
};
/**
 * How a source is offered for selection: scenes by their clip with title and length, the earlier full-clip replays,
 * then every other RTSP device as a camera. Groups keep catalog order.
 */
export function sourceView(c: Pick<Camera, "id" | "profile">, scenes = SCENES) {
  const scene = sceneOf(c, scenes);
  if (scene)
    return {
      group: `녹화 장면 · ${sceneGroup(scene)}`,
      option: `${scene.title} · ${seconds(scene.durationSeconds)}`,
      kind: "녹화 영상 · 실시간 분석 가능",
      scene,
      details: [
        ["원본 영상", scene.sourceUrl],
        [
          "원본 구간",
          `원본 ${scene.sourceFps} fps 프레임 ${scene.startFrame}–${scene.endFrame - 1} · ${(scene.startFrame / scene.sourceFps).toFixed(2)}–${(scene.endFrame / scene.sourceFps).toFixed(2)}초`,
        ],
        ["재생", `${seconds(scene.durationSeconds)} 반복 · ${scene.fps} fps`],
        ["해상도", `재생 영상 ${scene.width}×${scene.height} · Mapper 분석 프레임은 640×640 이내로 축소`],
      ] as [string, string][],
    };
  const replay = REPLAY_LABELS.get(c.profile.sourceName ?? "");
  return replay
    ? { group: "기존 녹화 영상", option: c.id, kind: replay, scene: null, details: [] as [string, string][] }
    : { group: "카메라", option: c.id, kind: "카메라", scene: null, details: [] as [string, string][] };
}
/** The source a fresh page selects: the first online scene in catalog order, else any online camera, else the first one. */
export function defaultSource<C extends Pick<Camera, "id" | "profile" | "state" | "stateReason" | "mapper">>(cameras: C[], scenes = SCENES) {
  const online = cameras.filter((c) => cameraState(c).online);
  const scene = scenes.map((s) => online.find((c) => sceneOf(c, [s]))).find(Boolean);
  return scene ?? online[0] ?? cameras[0] ?? null;
}
/** Sources grouped for the selector; scene groups follow the catalog, then earlier replays, then cameras. */
export function sourceGroups<C extends Pick<Camera, "id" | "profile">>(cameras: C[], scenes = SCENES) {
  const rank = (c: C) => {
    const s = sceneOf(c, scenes);
    return s ? scenes.indexOf(s) : scenes.length + (sourceView(c, scenes).group === "카메라" ? 1 : 0);
  };
  const groups = new Map<string, { label: string; items: { camera: C; option: string }[] }>();
  for (const c of [...cameras].sort((a, b) => rank(a) - rank(b))) {
    const v = sourceView(c, scenes);
    if (!groups.has(v.group)) groups.set(v.group, { label: v.group, items: [] });
    groups.get(v.group)!.items.push({ camera: c, option: v.option });
  }
  return [...groups.values()];
}
/** parameters.cameraSource: the camera identity and location the server pinned when the window's Run was created. */
export type CameraBinding = {
  deviceId: string;
  deviceUid: string;
  nodeName: string;
  mapperUrl: string;
  configurationSha256: string;
  sourceGeneration: string;
  pinnedAt: string;
};
/**
 * A window's source as pinned in its Run; the managed camera's live state is shown only as the current
 * connection, so a camera that later moved or changed does not rewrite past windows.
 */
export function pinnedSource(pinned: CameraBinding | null | undefined, current: Camera | undefined) {
  if (!pinned) return null;
  let now = "등록 해제됨";
  if (current && current.uid === pinned.deviceUid) {
    const changed = current.targetNode !== pinned.nodeName || (current.configurationSha256 != null && current.configurationSha256 !== pinned.configurationSha256);
    now = `${cameraState(current).label} · ${current.targetNode}${changed ? " · 실행 이후 노드·설정 변경" : ""}`;
  }
  const kind = current && current.uid === pinned.deviceUid ? replayLabel(current) : null;
  return {
    name: pinned.deviceId,
    state: "PINNED",
    nodeName: pinned.nodeName,
    details: [
      ["장치", pinned.deviceId],
      ...(kind ? [["영상 종류", kind] as [string, string]] : []),
      ["실행 시 Mapper 노드", pinned.nodeName],
      ["설정 digest", pinned.configurationSha256],
      ["Source generation", pinned.sourceGeneration],
      ["고정 시각", pinned.pinnedAt],
      ["현재 연결 (CURRENT)", now],
    ] as [string, string][],
  };
}
export const sourceAddress = (c: Camera) =>
  c.profile.host ? `rtsp://${c.profile.host}${c.profile.port ? `:${c.profile.port}` : ""}${c.profile.protocolConfig?.path ?? ""}` : "—";

export type Point = [number, number];
export type Track = {
  id: string;
  class: "forklift" | "person";
  /** false: not detected in this frame but tracked within the last 1.5 s; box and confidence are then null. */
  visible: boolean;
  confidence: number | null;
  box: [number, number, number, number] | null;
  /** Bottom centre of the box; for a lost track, its last known position. */
  point: Point;
  /** [t, x, y] with t <= 0 seconds relative to this frame. */
  path: [number, number, number][];
  velocity: Point | null;
  /** [t, x, y] with t > 0, estimated in image pixels. */
  predicted: [number, number, number][] | null;
};
export type RiskLevel = "NEAR_PATH" | "APPROACHING" | "NO_CONFLICT" | "UNKNOWN" | "NO_PAIR";
export type Pair = {
  forklift: string;
  person: string;
  level: RiskLevel;
  reason: "INSUFFICIENT_HISTORY" | "TRACK_LOST" | null;
  /** In person bounding-box heights. */
  distance: number;
  minDistance: number | null;
  /** Seconds until the estimated closest approach; not a physical time-to-collision. */
  timeToClosest: number | null;
  closest: [Point, Point] | null;
};
export type Observation = {
  seq: number;
  frameTime: string | null;
  receivedAt: string;
  width: number;
  height: number;
  sha256: string;
  detectMs: number | null;
  gapMs: number | null;
  reset: null | "START" | "TIME_GAP" | "TIME_REGRESSED" | "SCENE_CUT";
  status: RiskLevel;
  tracks: Track[];
  pairs: Pair[];
};
export type LiveView = {
  sessionId: string;
  state: "STARTING" | "RUNNING" | "FAILED" | "EXPIRED" | "STOPPED";
  failure: { code: string; message: string } | null;
  /** Latest source read error; the session keeps RUNNING (and its last frame) until repeated errors fail it. */
  lastError: string | null;
  observer: ObserverIdentity | null;
  stats: { processed: number; resets: number; sceneCuts?: number; lastDetectMs: number | null };
  latest: Observation | null;
};
type Observations = {
  apiVersion: string;
  observer?: ObserverIdentity | null;
  window?: { seq: number; fromSeq: number; toSeq: number; startedAt: string; finishedAt: string; evicted: number } | null;
  horizonSeconds?: number;
  frames: Observation[];
};
/** The snapshot Task's `observations` output (edgeai.hawkeye.motion/v1). */
export function readObservations(value: unknown) {
  const doc = value as Observations;
  if (doc?.apiVersion !== "edgeai.hawkeye.motion/v1" || !Array.isArray(doc.frames)) throw new Error("관측 결과 형식을 읽을 수 없습니다.");
  return { observer: doc.observer ?? null, window: doc.window ?? null, horizonSeconds: doc.horizonSeconds ?? null, frames: doc.frames };
}
export type ObservationsView = ReturnType<typeof readObservations>;

const CLASS: Record<string, string> = { forklift: "지게차", person: "사람" };
const pts = (list: (number[])[]) => list.map(([x, y]) => `${x},${y}`).join(" ");
/** SVG geometry in the frame's own pixel space, so it stays on the same image at any displayed size. */
export function motionOverlay(o: Observation) {
  return {
    viewBox: `0 0 ${o.width} ${o.height}`,
    boxes: o.tracks.flatMap((t) =>
      t.visible && t.box
        ? [
            {
              id: t.id,
              cls: t.class,
              x: t.box[0],
              y: t.box[1],
              w: t.box[2] - t.box[0],
              h: t.box[3] - t.box[1],
              label: `${t.id} ${CLASS[t.class] ?? t.class}${t.confidence == null ? "" : ` · 탐지 ${Math.round(t.confidence * 100)}%`}`,
            },
          ]
        : [],
    ),
    /** Tracks missed in this frame: only their last known point, never a box that would read as a current detection. */
    lost: o.tracks
      .filter((t) => !t.visible || !t.box)
      .map((t) => ({ id: t.id, cls: t.class, x: t.point[0], y: t.point[1], label: `${t.id} ${CLASS[t.class] ?? t.class} · 추적 끊김` })),
    history: o.tracks.filter((t) => t.path.length > 1).map((t) => ({ id: t.id, cls: t.class, points: pts(t.path.map(([, x, y]) => [x, y])) })),
    future: o.tracks
      .filter((t) => t.predicted?.length)
      .map((t) => {
        const steps = t.predicted!.map(([, x, y]) => [x, y] as Point);
        return { id: t.id, cls: t.class, points: pts([t.point, ...steps]), steps };
      }),
    pairs: o.pairs
      .filter((p) => p.closest)
      .map((p) => ({
        key: `${p.forklift}-${p.person}`,
        level: p.level,
        from: p.closest![0],
        to: p.closest![1],
        label: p.timeToClosest == null ? "최근접" : `${p.timeToClosest.toFixed(1)}초 후 최근접`,
      })),
  };
}

const RISK: Record<RiskLevel, { label: string; tone: Tone; safe: boolean }> = {
  NEAR_PATH: { label: "경로 근접", tone: "danger", safe: false },
  APPROACHING: { label: "접근 중", tone: "warning", safe: false },
  NO_CONFLICT: { label: "3초 내 근접 예측 없음", tone: "success", safe: true },
  UNKNOWN: { label: "판단 불가 · 안전 판정 아님", tone: "warning", safe: false },
  NO_PAIR: { label: "지게차·사람 동시 탐지 없음 · 안전 판정 아님", tone: "info", safe: false },
};
const RESET: Record<string, string> = {
  START: "추적 시작",
  TIME_GAP: "영상 시간 간격으로 추적 재시작",
  TIME_REGRESSED: "영상 시각 역행으로 추적 재시작",
  SCENE_CUT: "화면 전환으로 추적 재시작",
};
const d1 = (n: number) => n.toFixed(1);
/** One line per forklift–person pair; distances are person-height multiples in the image, not meters. */
export function riskView(o: Observation | null) {
  if (!o) return { label: "관측 없음", tone: "info" as Tone, safe: false, lines: [] as string[], reset: null as string | null };
  const r = RISK[o.status] ?? RISK.UNKNOWN;
  return {
    ...r,
    lines: o.pairs.map((p) => {
      const head = `${p.forklift} ↔ ${p.person} · `;
      if (p.reason === "INSUFFICIENT_HISTORY") return `${head}이동 이력 부족 · 현재 ${d1(p.distance)} (사람 키 배)`;
      if (p.reason === "TRACK_LOST") return `${head}추적 끊김 · 판단 불가 (마지막 위치 기준 ${d1(p.distance)}, 사람 키 배)`;
      const closest = p.minDistance == null ? "" : ` → 최근접 ${d1(p.minDistance)}`;
      const when = p.timeToClosest == null ? "" : ` · ${d1(p.timeToClosest)}초 후`;
      return `${head}${(RISK[p.level] ?? RISK.UNKNOWN).label} · 현재 ${d1(p.distance)}${closest} (사람 키 배)${when}`;
    }),
    reset: o.reset ? RESET[o.reset] ?? o.reset : null,
  };
}

/** Milliseconds from one recorded frame to the next at their decode spacing, bounded to 20 ms–1 s (500 ms without times). */
export function playbackDelay(now: { frameTime: string | null }, next: { frameTime: string | null }) {
  const spacing = now.frameTime && next.frameTime ? Date.parse(next.frameTime) - Date.parse(now.frameTime) : NaN;
  return Number.isFinite(spacing) && spacing > 0 ? Math.min(Math.max(spacing, 20), 1000) : 500;
}
/** Frames per second over the timestamps (ms) of the last two seconds, up to now; null with fewer than two of them. */
export function frameRate(times: number[], now: number) {
  const recent = times.filter((t) => now - t <= 2000);
  return recent.length < 2 || now <= recent[0] ? null : ((recent.length - 1) * 1000) / (now - recent[0]);
}

/** A frame older than this is no longer called live. */
export const LIVE_STALE_MS = 5000;
export function liveStatus(input: {
  state: string;
  lastFrameAt: number | null;
  error: string;
  now: number;
  failure?: { code: string; message: string } | null;
  /** The observer's own source read error while the session is still RUNNING. */
  lastError?: string | null;
}): { text: string; tone: Tone; live: boolean } {
  const { state, lastFrameAt, error, now, failure, lastError } = input;
  if (state === "FAILED") return { text: `관측 실패${failure ? ` · ${failure.code}` : ""}`, tone: "danger", live: false };
  if (state === "STARTING") return { text: "관측 시작 중", tone: "info", live: false };
  if (state !== "RUNNING") return { text: `관측 종료 · ${statusLabel(state)}`, tone: "warning", live: false };
  if (error) return { text: `지연 · ${error}`, tone: "warning", live: false };
  if (lastError) return { text: `원본 읽기 오류 · ${lastError}`, tone: "warning", live: false };
  if (lastFrameAt == null) return { text: "첫 프레임 대기", tone: "info", live: false };
  if (now - lastFrameAt > LIVE_STALE_MS) return { text: `지연 · ${Math.round((now - lastFrameAt) / 1000)}초 전 프레임`, tone: "warning", live: false };
  return { text: "실시간", tone: "success", live: true };
}

/** Topological columns so a join waits for its slowest input, independent of array order. */
function levels(dag: Dag) {
  const level = new Map(dag.tasks.map((t) => [t.key, 0]));
  for (let i = 0; i < dag.tasks.length; i++)
    for (const e of dag.dependencies)
      level.set(e.toTask, Math.max(level.get(e.toTask) ?? 0, (level.get(e.fromTask) ?? 0) + 1));
  return level;
}

export function pipelineGraph(input: {
  dag: Dag;
  placements: Placement[];
  source: Source | null;
  accelerator: Accelerator | null;
  sourceTask: string;
  acceleratorTask: string;
  result: Result;
  /** Present only for the motion workflow: the continuous observer between the camera and the snapshot Task. */
  observer?: (ObserverIdentity & { status: string; basis?: string }) | null;
}): { nodes: GraphNode[]; edges: GraphEdge[] } {
  const { dag, placements, source, accelerator, sourceTask, acceleratorTask, result, observer } = input;
  const motion = observer !== undefined;
  const shift = motion ? 1 : 0;
  const level = levels(dag);
  const tasks = [...dag.tasks].sort((a, b) => (level.get(a.key) ?? 0) - (level.get(b.key) ?? 0));
  const last = Math.max(0, ...level.values()) + 2 + shift;
  const rows = new Map<number, number>();
  const nodes: GraphNode[] = [
    {
      id: "source",
      kind: "external",
      kindLabel: "외부 입력 · RTSP 카메라와 Mapper",
      label: source?.name ?? "카메라",
      status: source ? source.state : "관측값 없음",
      tone: source ? tone(source.state) : "warning",
      location: source?.nodeName ?? null,
      column: 0,
      row: 0,
      details: source?.details ?? [],
    },
  ];
  if (motion)
    nodes.push({
      id: "observer",
      kind: "external",
      kindLabel: "외부 연속 관측 서비스 · DAG 작업 아님",
      label: "지게차·사람 추적 관측기",
      status: observer ? observer.status : "관측값 없음",
      tone: observer ? tone(observer.status) : "warning",
      location: observer?.nodeName ?? null,
      column: 1,
      row: 0,
      details: observer
        ? [
            ["처리", "프레임 수신 → 탐지 → 추적 → 경로 예측 (한 서비스)"],
            ["노드", observer.nodeName ?? "관측값 없음"],
            ["Pod", observer.pod ?? "관측값 없음"],
            ["실행 제공자", observer.provider ?? "관측값 없음"],
            ["모델", modelLabel(observer.model) ?? "관측값 없음"],
            ...(modelDigest(observer.model) ? [["모델 digest", modelDigest(observer.model)!] as [string, string]] : []),
            ...(observer.model?.engineDigest ? [["엔진 digest", observer.model.engineDigest] as [string, string]] : []),
            ...(observer.model?.precision ? [["정밀도", observer.model.precision] as [string, string]] : []),
            ...(observer.epoch != null ? [["관측 epoch", String(observer.epoch)] as [string, string]] : []),
            ...(observer.basis ? [["근거", observer.basis] as [string, string]] : []),
          ]
        : [],
    });
  for (const task of tasks) {
    const column = (level.get(task.key) ?? 0) + 1 + shift;
    const row = rows.get(column) ?? 0;
    rows.set(column, row + 1);
    const p = placements.find((x) => x.taskKey === task.key);
    const state = p?.taskState ?? "실행 전";
    nodes.push({
      id: `task:${task.key}`,
      kind: "task",
      kindLabel: "DAG 작업 · Kubernetes Runner",
      label: task.key,
      status: state,
      tone: p ? tone(state) : "info",
      location: p?.runtime?.nodeName ?? null,
      column,
      row,
      details: p
        ? [
            ["작업 상태", p.taskState],
            ["실행 시도", p.attempt ? `${p.attempt.mode} · ${p.attempt.number}회차` : "선행 작업 대기"],
            ["실제 노드", p.runtime?.nodeName ?? "배치 관측 대기"],
            ["Pod 관측", p.runtime ? p.runtime.observedState + (p.runtime.failureReason ? ` · ${p.runtime.failureReason}` : "") : "Runtime 생성 전"],
            ["Job", p.runtime ? `${p.runtime.namespace} / ${p.runtime.jobName ?? "—"}` : "—"],
            ["Pod UID", p.runtime?.podUid ?? "관측 전"],
          ]
        : [],
    });
    if (task.key === acceleratorTask) {
      rows.set(column, row + 2);
      nodes.push({
        id: "accelerator",
        kind: "external",
        kindLabel: "외부 GPU 추론 · DAG 작업 아님",
        label: "Hawkeye 추론 서버",
        status: accelerator ? accelerator.status : "관측값 없음",
        tone: accelerator ? tone(accelerator.status) : "warning",
        location: accelerator?.nodeName ?? null,
        column,
        row: row + 1,
        details: accelerator
          ? [
              ["노드", accelerator.nodeName ?? "관측값 없음"],
              ["처리 장치", accelerator.processor ?? "관측값 없음"],
              ["Pod UID", accelerator.podUid ?? "관측값 없음"],
              ...(accelerator.basis ? [["근거", accelerator.basis] as [string, string]] : []),
            ]
          : [],
      });
    }
  }
  nodes.push({
    id: "result",
    kind: "result",
    kindLabel: "결과 저장 · MinIO 검증 결과",
    label: motion ? "관측 기록" : "판정 결과",
    status: result ? result.state : "결과 없음",
    tone: result ? tone(result.state) : "info",
    location: null,
    column: last,
    row: 0,
    details: result ? [["검증된 프레임", `${result.verified}장`]] : [],
  });
  const keys = new Set(dag.tasks.map((t) => t.key));
  const sinks = dag.tasks.filter((t) => !dag.dependencies.some((d) => d.fromTask === t.key));
  const edges: GraphEdge[] = [
    ...(motion ? [{ from: "source", to: "observer", kind: "data" as const }] : []),
    ...(keys.has(sourceTask) ? [{ from: motion ? "observer" : "source", to: `task:${sourceTask}`, kind: "data" as const }] : []),
    ...dag.dependencies.map((d) => ({ from: `task:${d.fromTask}`, to: `task:${d.toTask}`, kind: "data" as const })),
    ...(keys.has(acceleratorTask) ? [{ from: `task:${acceleratorTask}`, to: "accelerator", kind: "call" as const }] : []),
    ...sinks.map((t) => ({ from: `task:${t.key}`, to: "result", kind: "data" as const })),
  ];
  return { nodes, edges };
}

/**
 * Milliseconds from the previous window's start to each window's start. Run.updatedAt keeps changing during runtime
 * cleanup, so it is not an end time and is not used.
 */
export function windowStartIntervals(createdAts: string[]) {
  return createdAts.map((at, i) => (i ? Date.parse(at) - Date.parse(createdAts[i - 1]) : null));
}

/** One frame on the history timeline: a motion observation, or a v1 frame with its judged category. */
export type HistoryFrame = {
  seq: number;
  frameTime: string | null;
  observation: Observation | null;
  tone: Tone | "none";
  caption?: string;
  note?: string | null;
  unread?: boolean;
};
export const motionFrames = (frames: Observation[]): HistoryFrame[] =>
  frames.map((o) => ({ seq: o.seq, frameTime: o.frameTime ?? o.receivedAt, observation: o, tone: riskView(o).tone }));
