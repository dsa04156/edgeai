import assert from "node:assert/strict";
import { test } from "node:test";
import { stringify } from "lossless-json";
import { readWorkflowDocument, type WorkflowProfile } from "../lib/workflow-graph.ts";
import { deliveryUnsupported, selectionKey, sourceDelivery, withDelivery } from "../kestra-native/workflow-source.ts";

const sensor = { kind: "SENSOR", deviceId: "temperature", property: "value" };
const vd = { kind: "CONTINUOUS_VD", vdId: "00000000-0000-4000-8000-0000000000aa", outputId: "alarm" };

test("LATEST is the omitted default and legacy sources without delivery read as LATEST", () => {
  assert.equal(sourceDelivery(sensor), "LATEST");
  assert.equal(sourceDelivery({ ...sensor, delivery: "LATEST" }), "LATEST");
  assert.equal(sourceDelivery({ ...sensor, delivery: "ALL_OBSERVATIONS" }), "ALL_OBSERVATIONS");
  assert.deepEqual(withDelivery(sensor, "LATEST"), sensor);
  assert.deepEqual(withDelivery({ ...sensor, delivery: "LATEST" }, "LATEST"), sensor);
  assert.deepEqual(withDelivery(sensor, "ALL_OBSERVATIONS"), { ...sensor, delivery: "ALL_OBSERVATIONS" });
  // Switching back to LATEST removes the policy instead of storing a second spelling of the default.
  assert.deepEqual(withDelivery({ ...vd, delivery: "ALL_OBSERVATIONS" }, "LATEST"), vd);
  assert.equal(selectionKey({ ...vd, delivery: "ALL_OBSERVATIONS" }), selectionKey(vd));
});

test("delivery survives changing the input kind or the selected source", () => {
  const chosen = withDelivery(sensor, "ALL_OBSERVATIONS");
  assert.deepEqual(withDelivery(vd, sourceDelivery(chosen)), { ...vd, delivery: "ALL_OBSERVATIONS" });
  assert.deepEqual(withDelivery({ kind: "CONTINUOUS_VD" }, sourceDelivery(chosen)), { kind: "CONTINUOUS_VD", delivery: "ALL_OBSERVATIONS" });
  assert.deepEqual(withDelivery({ kind: "SENSOR" }, sourceDelivery(sensor)), { kind: "SENSOR" });
});

test("string sensor measurements are flagged as unsupported only for ALL_OBSERVATIONS", () => {
  assert.equal(deliveryUnsupported({ selection: sensor, type: "string" }, "ALL_OBSERVATIONS"), true);
  assert.equal(deliveryUnsupported({ selection: sensor, type: "string" }, "LATEST"), false);
  assert.equal(deliveryUnsupported({ selection: sensor, type: "float64" }, "ALL_OBSERVATIONS"), false);
  assert.equal(deliveryUnsupported({ selection: sensor, type: "boolean" }, "ALL_OBSERVATIONS"), false);
  assert.equal(deliveryUnsupported({ selection: vd, type: "" }, "ALL_OBSERVATIONS"), false);
  assert.equal(deliveryUnsupported(undefined, "ALL_OBSERVATIONS"), false);
});

test("editing delivery on a saved version keeps task keys, links, layout and other parameters through reload", () => {
  const profiles = [
    { id: "src", key: "input", version: "1.0.0", spec: { stream: { source: "WORKFLOW_DATA", inputs: {}, outputs: { sample: {} } } } },
    { id: "proc", key: "process", version: "1.0.0", spec: { stream: { inputs: { input: {} }, outputs: {} } } },
  ].map(p => ({ ...p, kind: "SERVICE" as const, digest: "fixture", createdAt: "2026-10-22T00:00:00Z" })) satisfies WorkflowProfile[];
  const saved = '{"tasks":[{"key":"input","serviceProfileVersionId":"src","parameters":{"dataSource":{"kind":"SENSOR","deviceId":"temperature","property":"value"},"window":9007199254740993}},{"key":"process","serviceProfileVersionId":"proc","parameters":{"threshold":3}}],"dependencies":[{"fromTask":"input","fromPort":"sample","toTask":"process","toPort":"input","mode":"STREAM"}]}';
  const loaded = readWorkflowDocument(saved, profiles);
  const edit = (dag: typeof loaded.dag, delivery: "LATEST" | "ALL_OBSERVATIONS") => ({
    ...dag,
    tasks: dag.tasks.map(t => t.key === "input" ? { ...t, parameters: { ...t.parameters, dataSource: withDelivery(t.parameters.dataSource as Record<string, unknown>, delivery) } } : t),
  });
  const full = edit(loaded.dag, "ALL_OBSERVATIONS");
  const reloaded = readWorkflowDocument(stringify({ format: "edgeai.workflow-editor/v1", dag: full, positions: { input: { x: 10, y: 20 }, process: { x: 300, y: 20 } } })!, profiles);
  assert.deepEqual({ ...reloaded.positions }, { input: { x: 10, y: 20 }, process: { x: 300, y: 20 } });
  assert.deepEqual(reloaded.dag.dependencies, loaded.dag.dependencies);
  assert.deepEqual(reloaded.dag.tasks.map(t => t.key), ["input", "process"]);
  assert.match(stringify(reloaded.dag)!, /"dataSource":\{"kind":"SENSOR","deviceId":"temperature","property":"value","delivery":"ALL_OBSERVATIONS"\},"window":9007199254740993/);
  assert.equal(stringify(reloaded.dag.tasks[1].parameters), '{"threshold":3}');
  // Returning to LATEST restores the exact legacy document.
  assert.equal(stringify(edit(reloaded.dag, "LATEST")), stringify(loaded.dag));
});
