import assert from "node:assert/strict";
import { test } from "node:test";
import { stringify } from "lossless-json";
import { arrangeWorkflow, connectTasks, readWorkflowDocument, type WorkflowDag, type WorkflowProfile } from "../lib/workflow-graph";

const profiles = [
  { id: "a", key: "sensor", version: "1.0.0", kind: "SERVICE", spec: { inputs: { data: {} }, outputs: { data: {} } } },
  { id: "b", key: "alarm", version: "1.0.0", kind: "SERVICE", spec: { inputs: { data: {} }, outputs: { data: {} } } },
  { id: "c", key: "stream", version: "1.0.0", kind: "SERVICE", spec: { stream: { inputs: { data: {} }, outputs: { data: {} } } } },
].map(p => ({ ...p, kind: "SERVICE" as const, digest: "fixture", createdAt: "2026-10-06T00:00:00Z" })) satisfies WorkflowProfile[];
const dag: WorkflowDag = { tasks: ["a", "b", "c"].map(key => ({ key, serviceProfileVersionId: key, parameters: {} })), dependencies: [] };
test("canvas connections persist as executable DAG dependencies, rejecting cycles and duplicate input", () => {
  const connected = connectTasks(dag, profiles, "a", "data", "b", "data");
  assert.deepEqual(connected.dependencies, [{ fromTask: "a", fromPort: "data", toTask: "b", toPort: "data", mode: "BATCH" }]);
  assert.throws(() => connectTasks(connected, profiles, "b", "data", "a", "data"), /순환/);
  assert.throws(() => connectTasks(connected, profiles, "a", "data", "b", "data"), /이미 연결/);
  assert.throws(() => connectTasks(dag, profiles, "a", "missing", "b", "data"), /포트/);
  assert.throws(() => connectTasks(dag, profiles, "a", "data", "c", "data"), /BATCH와 STREAM/);
});
test("editor file round-trip keeps connections, positions, and lossless parameter integers", () => {
  const text = '{"tasks":[{"key":"a","serviceProfileVersionId":"a","parameters":{"counter":9007199254740993}},{"key":"b","serviceProfileVersionId":"b","parameters":{}}],"dependencies":[{"fromTask":"a","fromPort":"data","toTask":"b","toPort":"data","mode":"BATCH"}]}';
  const first = readWorkflowDocument(text, profiles);
  const doc = stringify({ format: "edgeai.workflow-editor/v1", dag: first.dag, positions: { a: { x: 140, y: -30 } } })!;
  const restored = readWorkflowDocument(doc, profiles);
  assert.deepEqual(restored.positions.a, { x: 140, y: -30 });
  assert.equal(restored.dag.dependencies.length, 1);
  assert.match(stringify(restored.dag)!, /9007199254740993/);
});
test("imports reject unknown modules, dangling links, invalid tasks and cyclic graphs", () => {
  assert.throws(() => readWorkflowDocument(JSON.stringify(dag), []), /등록되지 않은/);
  assert.throws(() => readWorkflowDocument('{"tasks":[null],"dependencies":[]}', profiles), /작업 키/);
  assert.throws(() => readWorkflowDocument('{"tasks":[{"key":"a","serviceProfileVersionId":"a","parameters":12}],"dependencies":[]}', profiles), /작업 키/);
  assert.throws(() => readWorkflowDocument(JSON.stringify({ ...dag, tasks: [...dag.tasks, dag.tasks[0]] }), profiles), /중복/);
  const connected = connectTasks(dag, profiles, "a", "data", "b", "data");
  assert.throws(() => readWorkflowDocument(JSON.stringify({ ...connected, tasks: dag.tasks.slice(1) }), profiles), /연결 대상/);
  assert.throws(() => readWorkflowDocument(JSON.stringify({ ...connected, dependencies: [...connected.dependencies, { fromTask: "b", fromPort: "data", toTask: "a", toPort: "data", mode: "BATCH" }] }), profiles), /순환/);
  assert.throws(() => readWorkflowDocument('apiVersion: apps/v1', profiles));
});
test("automatic layout respects dependencies when task array is reversed", () => {
  const connected = connectTasks(dag, profiles, "a", "data", "b", "data");
  const positions = arrangeWorkflow({ ...connected, tasks: [...dag.tasks].reverse() });
  assert.ok(positions.a.x < positions.b.x);
  assert.notEqual(positions.a.y, positions.c.y);
});
