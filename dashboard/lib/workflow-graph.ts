import { isLosslessNumber, parse } from "lossless-json";
import type { components } from "./api-schema";

export type WorkflowProfile = components["schemas"]["ProfileVersion"];
export type WorkflowDag = components["schemas"]["WorkflowDag"];
export type GraphPositions = Record<string, { x: number; y: number }>;
export function profilePorts(profile: WorkflowProfile | undefined, direction: "inputs" | "outputs") {
  const spec = profile?.spec as { inputs?: object; outputs?: object; stream?: { inputs?: object; outputs?: object } } | undefined;
  return [...new Set([...Object.keys(spec?.[direction] || {}), ...Object.keys(spec?.stream?.[direction] || {})])];
}
function streamPort(profile: WorkflowProfile | undefined, direction: "inputs" | "outputs", port: string) {
  const spec = profile?.spec as { stream?: { inputs?: object; outputs?: object } } | undefined;
  return Object.hasOwn(spec?.stream?.[direction] || {}, port);
}
export function connectTasks(dag: WorkflowDag, profiles: WorkflowProfile[], fromTask: string, fromPort: string, toTask: string, toPort: string): WorkflowDag {
  if (dag.dependencies.length >= 512) throw new Error("연결은 512개까지 추가할 수 있습니다.");
  const profile = (key: string) => profiles.find(p => p.id === dag.tasks.find(t => t.key === key)?.serviceProfileVersionId);
  if (fromTask === toTask) throw new Error("서로 다른 작업을 연결하세요.");
  if (!profilePorts(profile(fromTask), "outputs").includes(fromPort) || !profilePorts(profile(toTask), "inputs").includes(toPort)) throw new Error("현재 모듈의 출력·입력 포트를 선택하세요.");
  if (dag.dependencies.some(e => e.toTask === toTask && e.toPort === toPort)) throw new Error("이미 연결된 입력 포트입니다. 기존 연결을 먼저 삭제하세요.");
  const reachable = new Set([toTask]);
  for (let i = 0; i < dag.tasks.length; i++) for (const e of dag.dependencies) if (reachable.has(e.fromTask)) reachable.add(e.toTask);
  if (reachable.has(fromTask)) throw new Error("순환 연결은 만들 수 없습니다.");
  const stream = streamPort(profile(fromTask), "outputs", fromPort);
  if (stream !== streamPort(profile(toTask), "inputs", toPort)) throw new Error("BATCH와 STREAM 포트는 서로 연결할 수 없습니다.");
  return { ...dag, dependencies: [...dag.dependencies, { fromTask, fromPort, toTask, toPort, mode: stream ? "STREAM" : "BATCH" }] };
}

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value) && !isLosslessNumber(value);
}
/** Native DAG imports are validated before replacing the current draft. */
export function readWorkflowDocument(text: string, profiles: WorkflowProfile[]): { dag: WorkflowDag; positions: GraphPositions } {
  if (text.length > 1_000_000) throw new Error("워크플로 파일은 1MB 이하여야 합니다.");
  const parsed: unknown = parse(text);
  if (!object(parsed)) throw new Error("워크플로 JSON 파일을 선택하세요.");
  const document = parsed.format === "edgeai.workflow-editor/v1" ? parsed.dag : parsed;
  if (!object(document) || Object.keys(document).sort().join(",") !== "dependencies,tasks" || !Array.isArray(document.tasks) || !Array.isArray(document.dependencies)) throw new Error("EdgeAI 워크플로 파일의 tasks와 dependencies를 확인하세요.");
  if (document.tasks.length > 128 || document.dependencies.length > 512) throw new Error("작업은 128개, 연결은 512개까지 추가할 수 있습니다.");
  const keys = new Set<string>();
  for (const task of document.tasks) {
    if (!object(task) || Object.keys(task).some(k => !["key", "serviceProfileVersionId", "parameters"].includes(k)) || typeof task.key !== "string" || task.key.length > 100 || !/^[a-z][a-z0-9]*([._-][a-z0-9]+)*$/.test(task.key) || keys.has(task.key) || !object(task.parameters)) throw new Error("작업 키·매개변수를 확인하세요. 중복된 작업 키는 사용할 수 없습니다.");
    if (!profiles.some(p => p.id === task.serviceProfileVersionId)) throw new Error(`${task.key}: 등록되지 않은 실행 모듈입니다. 해당 SERVICE 프로필이 필요합니다.`);
    keys.add(task.key);
  }
  let dag: WorkflowDag = { tasks: document.tasks as WorkflowDag["tasks"], dependencies: [] };
  for (const edge of document.dependencies) {
    if (!object(edge) || Object.keys(edge).sort().join(",") !== "fromPort,fromTask,mode,toPort,toTask" || ![edge.fromTask, edge.fromPort, edge.toTask, edge.toPort].every(v => typeof v === "string") || !keys.has(String(edge.fromTask)) || !keys.has(String(edge.toTask))) throw new Error("연결 대상 작업과 포트를 확인하세요.");
    dag = connectTasks(dag, profiles, String(edge.fromTask), String(edge.fromPort), String(edge.toTask), String(edge.toPort));
    if (dag.dependencies.at(-1)?.mode !== edge.mode) throw new Error("연결 방식이 모듈의 포트와 일치하지 않습니다.");
  }
  const positions: GraphPositions = Object.create(null);
  if (parsed.format === "edgeai.workflow-editor/v1" && object(parsed.positions)) {
    for (const [key, position] of Object.entries(parsed.positions)) {
      if (!keys.has(key) || !object(position)) continue;
      const x = isLosslessNumber(position.x) ? Number(position.x.value) : position.x;
      const y = isLosslessNumber(position.y) ? Number(position.y.value) : position.y;
      if (typeof x === "number" && typeof y === "number" && Number.isFinite(x) && Number.isFinite(y) && Math.abs(x) <= 100000 && Math.abs(y) <= 100000) positions[key] = { x, y };
    }
  }
  return { dag, positions };
}

/** Topological columns preserve branches and joins instead of array order. */
export function arrangeWorkflow(dag: WorkflowDag): GraphPositions {
  const levels = new Map(dag.tasks.map(t => [t.key, 0]));
  for (let i = 0; i < dag.tasks.length; i++) {
    let changed = false;
    for (const edge of dag.dependencies) {
      const level = (levels.get(edge.fromTask) || 0) + 1;
      if (level > (levels.get(edge.toTask) || 0)) { levels.set(edge.toTask, level); changed = true; }
    }
    if (!changed) break;
  }
  const rows = new Map<number, number>();
  return Object.fromEntries(dag.tasks.map(task => {
    const col = levels.get(task.key) || 0, row = rows.get(col) || 0;
    rows.set(col, row + 1);
    return [task.key, { x: 48 + col * 300, y: 56 + row * 220 }];
  }));
}
