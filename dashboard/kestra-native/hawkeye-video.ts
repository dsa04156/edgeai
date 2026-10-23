// Display model of the Hawkeye video workflow graph; every value comes from the API, nothing is inferred.
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
  ["SUCCEEDED", "ONLINE", "READY", "RUNNING", "VERIFIED", "ANSWERED", "COMPLETED"].includes(state)
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
};
export const statusLabel = (state: string) => STATUS[state] ?? state;
export const sessionLabel = statusLabel;

export type VideoCapability = {
  enabled: boolean;
  executes: boolean;
  workflowKey: string;
  workflow: { id: string; key: string; displayName: string } | null;
  versions: { id: string; version: string; createdAt: string }[];
  maxWindows: number;
  activeSessionId: string | null;
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
/** The registered forklift replay loops a recorded CCTV clip; other cameras carry no such label. */
export const replayLabel = (c: Pick<Camera, "profile">) =>
  c.profile.sourceName === "hawkeye-forklift-replay" ? "녹화 영상 반복 재생" : null;
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
}): { nodes: GraphNode[]; edges: GraphEdge[] } {
  const { dag, placements, source, accelerator, sourceTask, acceleratorTask, result } = input;
  const level = levels(dag);
  const tasks = [...dag.tasks].sort((a, b) => (level.get(a.key) ?? 0) - (level.get(b.key) ?? 0));
  const last = Math.max(0, ...level.values()) + 2;
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
  for (const task of tasks) {
    const column = (level.get(task.key) ?? 0) + 1;
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
    label: "판정 결과",
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
    ...(keys.has(sourceTask) ? [{ from: "source", to: `task:${sourceTask}`, kind: "data" as const }] : []),
    ...dag.dependencies.map((d) => ({ from: `task:${d.fromTask}`, to: `task:${d.toTask}`, kind: "data" as const })),
    ...(keys.has(acceleratorTask) ? [{ from: `task:${acceleratorTask}`, to: "accelerator", kind: "call" as const }] : []),
    ...sinks.map((t) => ({ from: `task:${t.key}`, to: "result", kind: "data" as const })),
  ];
  return { nodes, edges };
}

/** Milliseconds between a window's end and the next window's start; unknown when either side is missing. */
export function windowGaps(windows: { createdAt: string; finishedAt: string | null }[]) {
  return windows.map((w, i) => {
    const before = windows[i - 1]?.finishedAt;
    return before ? Date.parse(w.createdAt) - Date.parse(before) : null;
  });
}
