import assert from "node:assert/strict";
import {createServer} from "node:http";
import {test} from "node:test";
import {createOverviewMiddleware} from "../kestra-native/overview.ts";

async function listen(server) {
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  return `http://127.0.0.1:${server.address().port}`;
}

const metric = (key, value, fresh = true) => ({key, value, fresh, device: "", unit: "PERCENT", observedAt: new Date().toISOString()});
function fixtures(path) {
  if (path === "infrastructure") return {nodesStatus: "AVAILABLE", sensorsStatus: "AVAILABLE", fetchedAt: new Date().toISOString(), nodes: [
    {name: "server-a", kind: "EDGE_AI_SERVER", status: "READY"}, {name: "server-b", kind: "EDGE_AI_SERVER", status: "NOT_READY"},
    {name: "edge-a", kind: "EDGE_DEVICE", status: "READY"}, {name: "unclassified", kind: "UNKNOWN", status: "UNKNOWN"},
  ], sensors: [{name: "sensor-a"}, {name: "sensor-b"}]};
  if (path === "node-metrics") return {status: "AVAILABLE", fetchedAt: new Date().toISOString(), maxAgeSeconds: 90, items: [
    {nodeName: "server-a", measurements: [metric("CPU_USAGE", 12.5), metric("MEMORY_USAGE", 34)]},
    {nodeName: "edge-a", measurements: [metric("CPU_USAGE", 99, false), metric("MEMORY_USAGE", 25)]},
  ]};
  if (path === "virtual-devices?limit=100&offset=0") return {items: [{id: "a", state: "REGISTERED"}, {id: "b", state: "RELEASED"}], nextOffset: 2};
  if (path === "virtual-devices?limit=100&offset=2") return {items: [{id: "c", state: "REGISTERED"}], nextOffset: null};
  if (path === "workflow-runs?limit=20&offset=0") return {items: [{id: "run-a", state: "SUCCEEDED", mode: "AUTO", createdAt: "2026-10-14T12:00:00Z", parameters: {privateInput: "must not be exposed"}}], nextOffset: null};
  return undefined;
}

async function withServers(action, override = () => undefined) {
  const calls = [];
  const backend = createServer((req, res) => {
    calls.push([req.method, req.url]);
    const path = req.url.replace("/api/control-plane/", "");
    const response = override(path) ?? {status: 200, body: fixtures(path)};
    res.writeHead(response.body === undefined ? 404 : response.status, {"Content-Type": "application/json"});
    res.end(JSON.stringify(response.body ?? {}));
  });
  const baseUrl = await listen(backend);
  const middleware = createOverviewMiddleware({baseUrl});
  const preview = createServer((req, res) => middleware(req, res, () => {res.writeHead(404);res.end();}));
  const url = await listen(preview);
  try {await action(url, calls);} finally {await Promise.all([new Promise(resolve => preview.close(resolve)), new Promise(resolve => backend.close(resolve))]);}
}

test("native overview exposes real classified counts, all VD pages and only fresh metrics", async () => {
  await withServers(async (url, calls) => {
    const response = await fetch(`${url}/api/edgeai/overview`);
    assert.equal(response.status, 200);
    const data = await response.json();
    assert.deepEqual(data.resources, {servers: 2, edgeDevices: 1, sensors: 2, virtualDevices: 2});
    assert.equal(data.nodes.find(n => n.name === "server-a").cpuPercent, 12.5);
    assert.equal(data.nodes.find(n => n.name === "edge-a").cpuPercent, null);
    assert.equal(data.nodes.find(n => n.name === "server-b").readiness, "NOT_READY");
    assert.deepEqual(data.runs, [{id: "run-a", state: "SUCCEEDED", mode: "AUTO", createdAt: "2026-10-14T12:00:00Z"}]);
    assert.equal(JSON.stringify(data).includes("privateInput"), false);
    assert.deepEqual(data.errors, []);
    const chart = await fetch(`${url}/api/v1/main/dashboards/charts/preview`, {method: "POST", body: JSON.stringify({chart: `id: recent_runs\nedgeaiSnapshot: ${data.snapshotId}\n`, globalFilter: {pageNumber: 1, pageSize: 10}})});
    assert.equal((await chart.json()).results[0].state, "SUCCESS");
    assert.equal(calls.every(([method]) => method === "GET"), true);
  });
});

test("expired snapshots and partial registry failures remain unknown rather than becoming zero", async () => {
  await withServers(async url => {
    const data = await (await fetch(`${url}/api/edgeai/overview`)).json();
    assert.deepEqual(data.resources, {servers: null, edgeDevices: null, sensors: null, virtualDevices: null});
    assert.deepEqual(data.nodes, []);
    assert.deepEqual(data.inventory, {nodes: null, sensors: null, metricsAvailable: false});
    assert.equal(data.runs, null);
    assert.equal(data.errors.length, 5);
    const chart = await fetch(`${url}/api/v1/main/dashboards/charts/preview`, {method: "POST", body: JSON.stringify({chart: `id: edge_ai_servers\nedgeaiSnapshot: ${data.snapshotId}\n`})});
    assert.deepEqual((await chart.json()).results, [{value: "—"}]);
  }, path => {
    if (["infrastructure", "node-metrics"].includes(path)) return {status: 200, body: {...fixtures(path), fetchedAt: "2000-01-01T00:00:00Z"}};
    if (path.includes("offset=2") || path.startsWith("workflow-runs")) return {status: 503, body: {message: "unavailable"}};
  });
});

test("successful empty observations are zero and native chart queries remain read only", async () => {
  await withServers(async (url, calls) => {
    const data = await (await fetch(`${url}/api/edgeai/overview`)).json();
    assert.deepEqual(data.resources, {servers: 0, edgeDevices: 0, sensors: 0, virtualDevices: 0});
    assert.deepEqual(data.errors, []);
    assert.deepEqual(data.runs, []);
    const before = calls.length;
    assert.equal((await fetch(`${url}/api/edgeai/overview`, {method: "POST"})).status, 405);
    assert.equal((await fetch(`${url}/api/v1/main/flows`, {method: "POST"})).status, 404);
    assert.equal((await fetch(`${url}/api/v1/main/dashboards/charts/preview`, {method: "POST", body: JSON.stringify({chart: "id: unknown\n"})})).status, 400);
    assert.equal(calls.length, before);
  }, path => {
    const body = fixtures(path);
    if (!body) return undefined;
    return {status: 200, body: {...body, nodes: [], sensors: [], items: [], nextOffset: null}};
  });
});

test("manual refresh retrieves new observations instead of serving the previous snapshot", async () => {
  let changed = false;
  await withServers(async url => {
    const first = await (await fetch(`${url}/api/edgeai/overview`)).json();
    assert.equal(first.resources.sensors, 2);
    changed = true;
    const updated = await (await fetch(`${url}/api/edgeai/overview?refresh=1`)).json();
    assert.equal(updated.resources.sensors, 3);
    const chart = await fetch(`${url}/api/v1/main/dashboards/charts/preview`, {method: "POST", body: JSON.stringify({chart: `id: sensors\nedgeaiSnapshot: ${updated.snapshotId}\n`})});
    assert.deepEqual((await chart.json()).results, [{value: 3}]);
  }, path => changed && path === "infrastructure" ? {status: 200, body: {...fixtures(path), sensors: [{name: "a"}, {name: "b"}, {name: "c"}]}} : undefined);
});

test("lazy chart requests keep the observation generation even when another view refreshes", async () => {
  let changed = false;
  await withServers(async url => {
    const first = await (await fetch(`${url}/api/edgeai/overview`)).json();
    changed = true;
    const second = await (await fetch(`${url}/api/edgeai/overview?refresh=1`)).json();
    assert.equal(second.resources.sensors, 3);
    const oldChart = await fetch(`${url}/api/v1/main/dashboards/charts/preview`, {method: "POST", body: JSON.stringify({chart: `id: sensors\nedgeaiSnapshot: ${first.snapshotId}\n`})});
    assert.deepEqual((await oldChart.json()).results, [{value: 2}]);
  }, path => changed && path === "infrastructure" ? {status: 200, body: {...fixtures(path), sensors: [{name: "a"}, {name: "b"}, {name: "c"}]}} : undefined);
});

test("GPU observations keep device identity, verified zero and missing memory separate", async () => {
  const now = new Date().toISOString();
  const gpuMetric = (key, device, value, unit, fresh = true) => ({key, device, value, unit, fresh, observedAt: now});
  await withServers(async url => {
    const data = await (await fetch(`${url}/api/edgeai/overview`)).json();
    assert.equal(data.gpus.length, 2);
    assert.deepEqual(data.gpus.map(gpu => ({model: gpu.model, usage: gpu.usagePercent, used: gpu.memoryUsedBytes, total: gpu.memoryTotalBytes})), [
      {model: "NVIDIA GPU 1", usage: 0, used: 0, total: 8 * 1024 ** 3},
      {model: "NVIDIA GPU 2", usage: null, used: null, total: null},
    ]);
    assert.equal(JSON.stringify(data.gpus).includes("0000:01:00.0"), false);
    assert.equal(JSON.stringify(data.gpus).includes("10de:1234"), false);
    const result = async id => (await (await fetch(`${url}/api/v1/main/dashboards/charts/preview`, {method: "POST", body: JSON.stringify({chart: `id: ${id}\nedgeaiSnapshot: ${data.snapshotId}\n`})})).json());
    assert.deepEqual((await result("gpu_usage")).results.map(r => r.usage), [0]);
    assert.equal((await result("gpu_resources")).results[0].memory, "0.00 / 8.00 GiB");
    assert.equal((await result("gpu_resources")).results[1].memory, "—");
  }, path => {
    if (path === "infrastructure") {
      const body = fixtures(path);body.nodes[0].allocatable = {"nvidia.com/gpu": "2000m"};return {status: 200, body};
    }
    if (path === "node-metrics") return {status: 200, body: {...fixtures(path), items: [{nodeName: "server-a", accelerators: [
      {device: "0000:01:00.0", kind: "GPU", vendor: "NVIDIA", model: "NVIDIA GPU [10de:1234]", fresh: true, observedAt: now},
      {device: "0000:02:00.0", kind: "GPU", vendor: "NVIDIA", model: "NVIDIA GPU [10de:1234]", fresh: true, observedAt: now},
      {device: "igpu", kind: "GPU", vendor: "Intel", model: "Intel GPU", fresh: true, observedAt: now},
    ], measurements: [
      gpuMetric("GPU_USAGE", "0000:01:00.0", 0, "PERCENT"),
      gpuMetric("GPU_MEMORY_USED", "0000:01:00.0", 0, "BYTES"),
      gpuMetric("GPU_MEMORY_TOTAL", "0000:01:00.0", 8 * 1024 ** 3, "BYTES"),
      gpuMetric("GPU_USAGE", "0000:02:00.0", 80, "PERCENT", false),
      gpuMetric("GPU_USAGE", "igpu", 95, "PERCENT"),
      gpuMetric("GPU_USAGE", "orphan", 90, "PERCENT"),
    ]}]}};
  });
});

test("GPU shared slots and incomplete inventory never manufacture physical devices", async t => {
  for (const scenario of [
    {name: "shared slots on one physical GPU", resource: "nvidia.com/gpu.shared", count: "8", peers: 1, fresh: true, verified: true},
    {name: "shared slots on ambiguous hardware", resource: "nvidia.com/gpu.shared", count: "8", peers: 2, fresh: true, verified: false},
    {name: "partial same-vendor exposure", resource: "nvidia.com/gpu", count: "1", peers: 2, fresh: true, verified: false},
    {name: "stale hardware", resource: "nvidia.com/gpu", count: "1", peers: 1, fresh: false, verified: false},
  ]) await t.test(scenario.name, async () => {
    const observedAt = new Date().toISOString();
    await withServers(async url => {
      const data = await (await fetch(`${url}/api/edgeai/overview`)).json();
      assert.equal(data.gpus.length, 1);
      assert.equal(data.gpus[0].verified, scenario.verified);
      assert.equal(data.gpus[0].usagePercent, scenario.verified ? 0 : null);
      assert.equal(data.errors.includes("일부 GPU 장착 정보 확인 불가"), !scenario.verified);
      const chart = await (await fetch(`${url}/api/v1/main/dashboards/charts/preview`, {method: "POST", body: JSON.stringify({chart: `id: gpu_usage\nedgeaiSnapshot: ${data.snapshotId}\n`})})).json();
      assert.equal(chart.results.length, scenario.verified ? 1 : 0);
    }, path => {
      if (path === "infrastructure") {
        const body = fixtures(path);body.nodes[0].allocatable = {[scenario.resource]: scenario.count};return {status: 200, body};
      }
      if (path === "node-metrics") return {status: 200, body: {...fixtures(path), items: [{nodeName: "server-a",
        accelerators: Array.from({length: scenario.peers}, (_, index) => ({device: `gpu-${index}`, kind: "GPU", vendor: "NVIDIA", model: "NVIDIA GPU", fresh: scenario.fresh, observedAt})),
        measurements: [{device: "gpu-0", key: "GPU_USAGE", unit: "PERCENT", value: 0, fresh: true, observedAt}],
      }]}};
    });
  });
});

test("GPU exposure stays visible when the metrics endpoint fails", async () => {
  await withServers(async url => {
    const data = await (await fetch(`${url}/api/edgeai/overview`)).json();
    assert.equal(data.gpus.length, 1);
    assert.equal(data.gpus[0].node, "server-a");
    assert.equal(data.gpus[0].verified, false);
    assert.equal(data.gpus[0].usagePercent, null);
    const chart = await (await fetch(`${url}/api/v1/main/dashboards/charts/preview`, {method: "POST", body: JSON.stringify({chart: `id: gpu_resources\nedgeaiSnapshot: ${data.snapshotId}\n`})})).json();
    assert.equal(chart.results[0].usage, "—");
    assert.equal(chart.results[0].memory, "—");
    assert.equal(data.errors.includes("노드 사용량 확인 불가"), true);
  }, path => {
    if (path === "node-metrics") return {status: 503, body: {message: "unavailable"}};
    if (path === "infrastructure") {
      const body = fixtures(path);body.nodes[0].allocatable = {"nvidia.com/gpu.shared": "8"};return {status: 200, body};
    }
  });
});

test("NPU observations preserve uninstrumented hardware and measured zero per device", async () => {
  const observedAt = new Date().toISOString();
  await withServers(async url => {
    const data = await (await fetch(`${url}/api/edgeai/overview`)).json();
    assert.equal(data.npus.length, 3);
    assert.deepEqual(data.npus.map(npu => npu.vendor), ["hailo", "intel", "intel"]);
    assert.deepEqual(data.npus.map(npu => [npu.model, npu.usagePercent, npu.utilizationNote]), [
      ["Hailo-8", null, "사용률 별도 계측 필요"], ["Intel NPU 1", 0, null], ["Intel NPU 2", 25, null],
    ]);
    assert.equal(JSON.stringify(data.npus).includes("pci:"), false);
    const chart = await (await fetch(`${url}/api/v1/main/dashboards/charts/preview`, {method: "POST", body: JSON.stringify({chart: `id: npu_usage\nedgeaiSnapshot: ${data.snapshotId}\n`})})).json();
    assert.deepEqual(chart.results.map(r => [r.device, r.usage]), [["server-b · NPU 1", 0], ["server-b · NPU 2", 25]]);
    const resources = await (await fetch(`${url}/api/v1/main/dashboards/charts/preview`, {method: "POST", body: JSON.stringify({chart: `id: node_resources\nedgeaiSnapshot: ${data.snapshotId}\n`})})).json();
    assert.equal(resources.results.find(n => n.node === "server-a").npu[0].utilizationNote, "사용률 별도 계측 필요");
    assert.deepEqual(resources.results.find(n => n.node === "server-b").npu.map(n => n.usagePercent), [0, 25]);
    assert.deepEqual(resources.results.find(n => n.node === "server-b").gpu, []);
  }, path => {
    if (path === "infrastructure") {
      const body = fixtures(path);body.nodes[0].allocatable = {"hailo.ai/h8": "1"};body.nodes[1].allocatable = {"npu.intel.com/accel": "2"};return {status: 200, body};
    }
    if (path === "node-metrics") return {status: 200, body: {...fixtures(path), items: [
      {nodeName: "server-a", accelerators: [{device: "pci:hailo", kind: "NPU", vendor: "Hailo", model: "Hailo-8", fresh: true, observedAt}], measurements: []},
      {nodeName: "server-b", accelerators: [
        {device: "pci:intel1", kind: "NPU", vendor: "Intel", model: "Intel NPU [8086:ad1d]", fresh: true, observedAt},
        {device: "pci:intel2", kind: "NPU", vendor: "Intel", model: "Intel NPU [8086:ad1d]", fresh: true, observedAt},
        {device: "pci:intel-gpu", kind: "GPU", vendor: "Intel", model: "Intel GPU", fresh: true, observedAt},
      ], measurements: [
        {device: "pci:intel1", key: "NPU_USAGE", value: 0, unit: "PERCENT", fresh: true, observedAt},
        {device: "pci:intel2", key: "NPU_USAGE", value: 25, unit: "PERCENT", fresh: true, observedAt},
        {device: "pci:intel-gpu", key: "GPU_USAGE", value: 99, unit: "PERCENT", fresh: true, observedAt},
        {device: "orphan", key: "NPU_USAGE", value: 90, unit: "PERCENT", fresh: true, observedAt},
      ]},
    ]}};
  });
});

test("device inventory keeps current node metadata and sensor states in the observation", async () => {
  await withServers(async url => {
    const data = await (await fetch(`${url}/api/edgeai/overview`)).json();
    assert.equal(data.inventory.nodes[0].architecture, "arm64");
    assert.deepEqual(data.inventory.nodes[0].allocatable, {cpu: "4"});
    assert.deepEqual(data.inventory.sensors, [{name: "temperature", nodeName: "edge-a", model: "model-a", serviceName: "KubeEdge Mapper", adminState: "UNLOCKED", operatingState: "OFFLINE", properties: ["temperature"]}]);
    assert.equal(data.inventory.metricsAvailable, true);
  }, path => path === "infrastructure" ? {status: 200, body: {...fixtures(path), nodes: [{name: "edge-a", kind: "EDGE_DEVICE", status: "READY", architecture: "arm64", operatingSystem: "linux", allocatable: {cpu: "4"}}],
    sensors: [{name: "temperature", nodeName: "edge-a", model: "model-a", serviceName: "KubeEdge Mapper", adminState: "UNLOCKED", operatingState: "OFFLINE", properties: ["temperature"]}]}} : undefined);
});
