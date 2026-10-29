// Draft ↔ registration document for measured artifact-path profiles (SchedulingService contract).
import { LosslessNumber, isLosslessNumber, parse } from "lossless-json";
import { objectJson, pretty, type Schema } from "./native-api";

export const MAX_PROFILE_BYTES = 1048576;
export type NodeCosts = {
  computeMs: string;
  downloadMs: Record<string, string>;
  uploadMs: Record<string, string>;
};
export type TaskDraft = { nodes: string[]; costs: Record<string, NodeCosts> };
export type ProfileDraft = {
  measuredAt: string;
  validUntil: string;
  evidenceId: string;
  sampleCount: string;
  parameters: string;
  tasks: Record<string, TaskDraft>;
};
export type TaskPorts = { key: string; inputs: string[]; outputs: string[] };
type Spec = { outputs?: Record<string, unknown>; stream?: unknown };

/** Ports whose transfer is measured: BATCH edges into the task and every SERVICE output. */
export function taskPorts(
  version: Schema["WorkflowVersion"],
  services: Map<string, Schema["ProfileVersion"]>,
): { tasks: TaskPorts[]; unsupported: string } {
  const streaming: string[] = [];
  const tasks = version.dag.tasks.map((task) => {
    const spec = services.get(task.serviceProfileVersionId)?.spec as
      | Spec
      | undefined;
    if (!spec || spec.stream != null) streaming.push(task.key);
    const inputs = new Set<string>();
    for (const edge of version.dag.dependencies)
      if (edge.mode !== "STREAM" && edge.toTask === task.key)
        inputs.add(edge.toPort);
    return {
      key: task.key,
      inputs: [...inputs].sort(),
      outputs: Object.keys(spec?.outputs ?? {}).sort(),
    };
  });
  const streamEdges = version.dag.dependencies.some((e) => e.mode === "STREAM");
  const unsupported =
    streaming.length || streamEdges
      ? `측정 기반 배치는 SERVICE BATCH artifact DAG만 지원합니다. 이 DAG에는 ${[
          ...(streamEdges ? ["STREAM 연결"] : []),
          ...(streaming.length
            ? [`STREAM·WORKFLOW_DATA 또는 확인할 수 없는 모듈(${streaming.join(", ")})`]
            : []),
        ].join("과 ")}이 있어 성능 프로파일을 등록할 수 없습니다.`
      : "";
  return { tasks, unsupported };
}

export const emptyCosts = (task: TaskPorts): NodeCosts => ({
  computeMs: "",
  downloadMs: Object.fromEntries(task.inputs.map((p) => [p, ""])),
  uploadMs: Object.fromEntries(task.outputs.map((p) => [p, ""])),
});
export const emptyDraft = (tasks: TaskPorts[]): ProfileDraft => ({
  measuredAt: "",
  validUntil: "",
  evidenceId: "",
  sampleCount: "",
  parameters: "{}",
  tasks: Object.fromEntries(tasks.map((t) => [t.key, { nodes: [], costs: {} }])),
});
export const costLabel = (direction: "computeMs" | "downloadMs" | "uploadMs", port = "") =>
  direction === "computeMs"
    ? "계산 시간 (ms)"
    : direction === "downloadMs"
      ? `입력 ${port} 다운로드 (ms)`
      : `출력 ${port} 업로드 (ms)`;

function milliseconds(text: string, where: string, label: string) {
  const value = text.trim();
  if (!value) throw new Error(`${where}의 ${label}를 입력하세요.`);
  try {
    if (!value.startsWith("-")) return new LosslessNumber(value);
  } catch {
    // reported below
  }
  throw new Error(`${where}의 ${label}은 0 이상의 숫자여야 합니다.`);
}
const INSTANT = /^(\d{4}-\d\d-\d\dT\d\d:\d\d(?::\d\d)?)(?:\.(\d{1,9}))?(Z|[+-]\d\d:\d\d)$/;
function instant(text: string, label: string) {
  const value = text.trim(),
    match = INSTANT.exec(value);
  // A fraction needs seconds (HH:MM:SS); Instant keeps at most nanoseconds.
  if (!match || (match[2] && match[1].length < 19) || Number.isNaN(Date.parse(match[1] + match[3])))
    throw new Error(`${label}은 시간대를 포함한 ISO 8601 시각이어야 합니다 (예: 2026-10-28T09:00:00+09:00).`);
  return value;
}
/** Instant order at nanosecond precision; Date.parse alone drops digits after milliseconds. */
function epoch(value: string) {
  const [, local, fraction = "", zone] = INSTANT.exec(value)!;
  return [Date.parse(local + zone), fraction.padEnd(9, "0")] as const;
}
const after = (later: string, earlier: string) => {
  const [a, an] = epoch(later),
    [b, bn] = epoch(earlier);
  return a !== b ? a > b : an > bn;
};
/** Profile metadata the API accepts; applied to typed and imported drafts alike. */
function metadata(draft: ProfileDraft) {
  const measuredAt = instant(draft.measuredAt, "측정 시각"),
    validUntil = instant(draft.validUntil, "유효 종료 시각");
  if (!after(validUntil, measuredAt)) throw new Error("유효 종료 시각은 측정 시각 이후여야 합니다.");
  const evidenceId = draft.evidenceId.trim();
  if (!evidenceId) throw new Error("측정 증빙 ID를 입력하세요.");
  // Same limits as the API text check: at most 200 UTF-16 units and no ISO control characters.
  const control = [...evidenceId].some((c) => {
    const code = c.charCodeAt(0);
    return code < 0x20 || (code >= 0x7f && code <= 0x9f);
  });
  if (evidenceId.length > 200 || control)
    throw new Error("측정 증빙 ID는 200자 이하이며 제어 문자를 포함할 수 없습니다.");
  const sampleCount = Number(draft.sampleCount);
  if (!/^\d+$/.test(draft.sampleCount.trim()) || !Number.isSafeInteger(sampleCount) || sampleCount < 1)
    throw new Error("표본 수는 1 이상의 정수여야 합니다.");
  return { measuredAt, validUntil, evidenceId, sampleCount };
}

/** The exact POST body; missing values are errors, never 0. */
export function profileDocument(
  versionId: string,
  tasks: TaskPorts[],
  draft: ProfileDraft,
  nodeName: (id: string) => string,
) {
  const { measuredAt, validUntil, evidenceId, sampleCount } = metadata(draft);
  const parameters = objectJson(draft.parameters);
  const body: Record<string, unknown> = {};
  for (const task of tasks) {
    const { nodes, costs } = draft.tasks[task.key];
    if (!nodes.length) throw new Error(`${task.key} 측정 노드를 한 개 이상 선택하세요.`);
    const computeMs: Record<string, LosslessNumber> = {},
      downloadMs: Record<string, Record<string, LosslessNumber>> = {},
      uploadMs: Record<string, Record<string, LosslessNumber>> = {};
    for (const node of nodes) {
      const where = `${task.key} · ${nodeName(node)}`,
        row = costs[node];
      computeMs[node] = milliseconds(row.computeMs, where, costLabel("computeMs"));
      for (const port of task.inputs)
        (downloadMs[port] ??= {})[node] = milliseconds(row.downloadMs[port] ?? "", where, costLabel("downloadMs", port));
      for (const port of task.outputs)
        (uploadMs[port] ??= {})[node] = milliseconds(row.uploadMs[port] ?? "", where, costLabel("uploadMs", port));
    }
    body[task.key] = { computeMs, downloadMs, uploadMs };
  }
  return {
    workflowVersionId: versionId,
    measuredAt,
    validUntil,
    provenance: { kind: "MEASURED_BATCH_ARTIFACT", evidenceId, sampleCount },
    parameters,
    tasks: body,
  };
}

const record = (value: unknown, what: string) => {
  if (!value || typeof value !== "object" || Array.isArray(value) || isLosslessNumber(value))
    throw new Error(`${what}은 JSON 객체여야 합니다.`);
  return value as Record<string, unknown>;
};
const sameKeys = (value: Record<string, unknown>, keys: string[], message: string) => {
  const actual = Object.keys(value).sort();
  if (actual.length !== keys.length || actual.some((k, i) => k !== [...keys].sort()[i]))
    throw new Error(message);
};
const listed = (keys: string[]) => (keys.length ? [...keys].sort().join(", ") : "(없음)");
function importedMs(value: unknown, task: string, label: string) {
  if (!isLosslessNumber(value) || value.value.startsWith("-"))
    throw new Error(`${task}의 ${label}은 0 이상의 숫자여야 합니다.`);
  return value.value;
}

/** A complete draft from an existing measurement document for the selected version; throws without partial effects. */
export function importDraft(
  text: string,
  versionId: string,
  tasks: TaskPorts[],
  knownNodes: Set<string>,
): ProfileDraft {
  if (new TextEncoder().encode(text).length > MAX_PROFILE_BYTES)
    throw new Error("측정 JSON은 1 MiB 이하여야 합니다.");
  let value: unknown;
  try {
    value = parse(text);
  } catch {
    throw new Error("측정 JSON을 해석할 수 없습니다.");
  }
  const root = record(value, "측정 JSON");
  const fields = ["workflowVersionId", "measuredAt", "validUntil", "provenance", "parameters", "tasks"];
  sameKeys(root, fields, `측정 JSON 최상위 필드는 ${fields.join(", ")}여야 합니다.`);
  if (root.workflowVersionId !== versionId)
    throw new Error("선택한 DAG 버전과 다른 측정 프로파일입니다. 버전을 바꿔 붙이지 않습니다.");
  const provenance = record(root.provenance, "provenance");
  sameKeys(provenance, ["kind", "evidenceId", "sampleCount"], "provenance는 kind, evidenceId, sampleCount만 포함해야 합니다.");
  if (provenance.kind !== "MEASURED_BATCH_ARTIFACT" || typeof provenance.evidenceId !== "string" || !isLosslessNumber(provenance.sampleCount))
    throw new Error("provenance는 MEASURED_BATCH_ARTIFACT 종류·증빙 ID·표본 수여야 합니다.");
  if (typeof root.measuredAt !== "string" || typeof root.validUntil !== "string")
    throw new Error("measuredAt·validUntil은 문자열 시각이어야 합니다.");
  const parameters = record(root.parameters, "parameters"),
    measured = record(root.tasks, "tasks");
  sameKeys(measured, tasks.map((t) => t.key), `tasks는 DAG 작업 키 ${listed(tasks.map((t) => t.key))}와 정확히 일치해야 합니다.`);
  const draftTasks: Record<string, TaskDraft> = {};
  for (const task of tasks) {
    const costs = record(measured[task.key], `${task.key} 측정값`);
    sameKeys(costs, ["computeMs", "downloadMs", "uploadMs"], `${task.key}는 computeMs, downloadMs, uploadMs만 포함해야 합니다.`);
    const compute = record(costs.computeMs, `${task.key}의 computeMs`),
      nodes = Object.keys(compute);
    if (!nodes.length) throw new Error(`${task.key}의 computeMs에 측정 노드가 없습니다.`);
    for (const node of nodes)
      if (!knownNodes.has(node)) throw new Error(`${task.key}의 측정 노드 ${node}를 노드 목록에서 찾을 수 없습니다.`);
    const rows: Record<string, NodeCosts> = Object.fromEntries(
      nodes.map((node) => [node, { computeMs: importedMs(compute[node], task.key, costLabel("computeMs")), downloadMs: {}, uploadMs: {} }]),
    );
    for (const [direction, ports] of [["downloadMs", task.inputs], ["uploadMs", task.outputs]] as const) {
      const byPort = record(costs[direction], `${task.key}의 ${direction}`);
      sameKeys(byPort, ports, `${task.key}의 ${direction} 포트는 ${listed(ports)}와 정확히 일치해야 합니다.`);
      for (const port of ports) {
        const byNode = record(byPort[port], `${task.key}의 ${direction}.${port}`);
        sameKeys(byNode, nodes, `${task.key}의 ${port} 포트 측정 노드는 computeMs 노드와 같아야 합니다.`);
        for (const node of nodes) rows[node][direction][port] = importedMs(byNode[node], task.key, costLabel(direction, port));
      }
    }
    draftTasks[task.key] = { nodes, costs: rows };
  }
  const draft = {
    measuredAt: root.measuredAt,
    validUntil: root.validUntil,
    evidenceId: provenance.evidenceId,
    sampleCount: provenance.sampleCount.value,
    parameters: pretty(parameters),
    tasks: draftTasks,
  };
  metadata(draft);
  return draft;
}
