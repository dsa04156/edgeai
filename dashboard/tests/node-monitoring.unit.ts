import assert from "node:assert/strict";
import { test } from "node:test";
import { currentInventory, monitoredNodes } from "../lib/monitored-nodes";
import type { components } from "../lib/api-schema";
import type { NodeMetricsState } from "../lib/node-metrics";
import { nodeAccelerators } from "../lib/node-accelerators";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MonitoredNodes } from "../app/components/monitored-nodes";
import { NodeUsage } from "../app/components/node-usage";
import { ClusterSensors } from "../app/components/cluster-sensors";
import type { InfrastructureSnapshot } from "../lib/infrastructure";

const now = Date.parse("2026-10-06T06:20:00Z");
const node: components["schemas"]["ExecutionNode"] = {
  id: "old-node", name: "real-node", architecture: "arm64", operatingSystem: "linux", status: "REMOVED",
  cpu: "6", memory: "8Gi", labels: {}, allocatable: {}, observedAt: new Date(now - 86400000).toISOString(),
};
const state: NodeMetricsState = {
  now, failed: false, snapshot: { status: "AVAILABLE", fetchedAt: new Date(now).toISOString(), maxAgeSeconds: 90,
    unmappedSeries: 0, items: [{ nodeName: "real-node", measurements: [
      { key: "GPU_USAGE", device: "Jetson GPU", value: 0, unit: "PERCENT", observedAt: new Date(now).toISOString(), fresh: true },
    ] }] },
};

test("live metric node is visible with empty inventory or a removed historical record", () => {
  for (const records of [[], [node]]) {
    const rows = monitoredNodes(state, records);
    assert.equal(rows.length, 1);
    assert.equal(rows[0].name, "real-node");
    assert.equal(rows[0].node, undefined);
    assert.deepEqual(rows[0].kinds, ["GPU"]);
  }
});
test("unrelated test records do not become monitored nodes", () => {
  const records = Array.from({ length: 2406 }, (_, index) => ({ ...node, id: String(index), name: `fixture-${index}` }));
  assert.deepEqual(monitoredNodes(state, records).map(row => row.name), ["real-node"]);
});
test("only a unique current inventory record supplies execution readiness", () => {
  const ready = { ...node, status: "READY" as const, observedAt: new Date(now).toISOString() };
  assert.equal(monitoredNodes(state, [ready])[0].node?.status, "READY");
  assert.equal(monitoredNodes(state, [ready, { ...ready, id: "duplicate" }])[0].node, undefined);
  assert.equal(monitoredNodes(state, [{ ...ready, observedAt: node.observedAt }])[0].node, undefined);
  assert.deepEqual(currentInventory([{ ...ready, status: "STALE" }, node], now), []);
});
test("unavailable metrics cannot revive a saved monitoring snapshot", () => {
  assert.deepEqual(monitoredNodes({ ...state, failed: true }, [node]), []);
  assert.deepEqual(monitoredNodes({ ...state, snapshot: { ...state.snapshot!, status: "UNAVAILABLE" } }, [node]), []);
});

test("Kubernetes condition supplies Ready and NotReady independently of historical inventory", () => {
  for (const status of ["READY", "NOT_READY", "UNKNOWN"] as const) {
    const live = { ...state, snapshot: { ...state.snapshot!, items: [{ ...state.snapshot!.items[0],
      readiness: { status, observedAt: new Date(now).toISOString(), fresh: true },
    }] } };
    assert.equal(monitoredNodes(live, [node])[0].status, status);
  }
});

test("expired or failed Ready observations are never shown as Ready", () => {
  for (const readiness of [
    { status: "READY" as const, observedAt: new Date(now - 91000).toISOString(), fresh: true },
    { status: "READY" as const, observedAt: new Date(now).toISOString(), fresh: false },
    { status: "READY" as const, observedAt: new Date(now + 10000).toISOString(), fresh: true },
  ]) {
    const live = { ...state, snapshot: { ...state.snapshot!, items: [{ ...state.snapshot!.items[0], readiness }] } };
    assert.equal(monitoredNodes(live)[0].status, "UNKNOWN");
  }
});

test("fresh hardware without utilization appears in NPU filter without inventing zero", () => {
  const hardware = { device: "pci:0001:01:00.0", kind: "NPU" as const, vendor: "Hailo", model: "Hailo-8",
    observedAt: new Date(now).toISOString(), fresh: true };
  const item = { nodeName: "real-node", measurements: [], accelerators: [hardware] };
  const live = { ...state, snapshot: { ...state.snapshot!, items: [item] } };
  assert.deepEqual(monitoredNodes(live)[0].kinds, ["NPU"]);
  assert.equal(nodeAccelerators(item, live)[0].label, "Hailo-8");
  const html = renderToStaticMarkup(createElement(MonitoredNodes, { state: live }));
  assert.match(html, /Hailo-8/);
  assert.doesNotMatch(html, /0\.0|pci:0001/);
  assert.deepEqual(nodeAccelerators(item, { ...live, now: now + 91000 }), []);
});

test("GPU UUIDs and PCI identities never appear in node table, details, or tooltips", () => {
  const ids = ["GPU-322e2753-8411-28a6-ab7b-bb03d5ba0dac", "GPU-abcdef12-1234", "0000:00:0b.0"];
  const item = { nodeName: "real-node", measurements: ids.map((device, i) => ({
    ...state.snapshot!.items[0].measurements[0], device, key: i === 2 ? "NPU_USAGE" as const : "GPU_USAGE" as const,
  })) };
  const live = { ...state, snapshot: { ...state.snapshot!, items: [item] } };
  for (const html of [renderToStaticMarkup(createElement(MonitoredNodes, { state: live })),
    renderToStaticMarkup(createElement(NodeUsage, { node, state: live, detailed: true }))]) {
    for (const id of ids) assert.ok(!html.includes(id));
    assert.match(html, /GPU 1/); assert.match(html, /GPU 2/); assert.match(html, /NPU/);
  }
});

test("one PCI device has one display row and generic model codes are hidden", () => {
  const device = "pci:0000:02:00.0";
  const item = { nodeName: "real-node", measurements: [{ ...state.snapshot!.items[0].measurements[0], device }],
    accelerators: [{ device, kind: "GPU" as const, vendor: "NVIDIA", model: "NVIDIA GPU [10de:2c02]",
      observedAt: new Date(now).toISOString(), fresh: true }] };
  const devices = nodeAccelerators(item, state);
  assert.equal(devices.length, 1); assert.equal(devices[0].label, "NVIDIA GPU");
  assert.equal(devices[0].measurements[0].value, 0);
});

const infrastructure: InfrastructureSnapshot = { nodesStatus: "AVAILABLE", sensorsStatus: "AVAILABLE", fetchedAt: new Date(now).toISOString(), nodes: [
  { name: "arm-server", kind: "EDGE_AI_SERVER", architecture: "arm64", operatingSystem: "linux", status: "READY" },
  { name: "edge-device", kind: "EDGE_DEVICE", architecture: "arm64", operatingSystem: "linux", status: "READY" },
], sensors: [{ name: "sensehat-humidity", nodeName: "edge-device", model: "humidity", serviceName: "sensehat", adminState: "UNLOCKED", operatingState: "UP", properties: ["humidity"] }] };

test("server and edge lists use fresh cluster roles and work without performance metrics", () => {
  const live = { ...state, snapshot: null };
  const rows = monitoredNodes(live, [], infrastructure);
  assert.deepEqual(rows.map(row => row.kind), ["EDGE_AI_SERVER", "EDGE_DEVICE"]);
  const server = renderToStaticMarkup(createElement(MonitoredNodes, { state: live, infrastructure, kind: "EDGE_AI_SERVER" }));
  assert.match(server, /arm-server/); assert.doesNotMatch(server, /edge-device/);
  const edge = renderToStaticMarkup(createElement(MonitoredNodes, { state: live, infrastructure, kind: "EDGE_DEVICE" }));
  assert.match(edge, /edge-device/); assert.doesNotMatch(edge, /arm-server/);
  assert.deepEqual(monitoredNodes({ ...live, now: now + 61000 }, [], infrastructure), []);
  assert.equal(monitoredNodes(state, [node])[0].kind, "UNKNOWN");
});

test("EdgeX sensor display preserves source state and does not imply live readings", () => {
  const html = renderToStaticMarkup(createElement(ClusterSensors, { snapshot: infrastructure, failed: false }));
  assert.match(html, /sensehat-humidity/); assert.match(html, /edge-device/); assert.match(html, /UNLOCKED/); assert.match(html, /UP/);
  assert.doesNotMatch(html, /온라인/);
  const failed = renderToStaticMarkup(createElement(ClusterSensors, { snapshot: { ...infrastructure, sensorsStatus: "UNAVAILABLE" }, failed: false }));
  assert.doesNotMatch(failed, /sensehat-humidity/); assert.match(failed, /불러오지 못했습니다/);
});
