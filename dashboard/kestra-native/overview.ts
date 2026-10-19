import type { IncomingMessage, ServerResponse } from "node:http";
import { randomUUID } from "node:crypto";
import type { components, operations } from "../lib/api-schema";

type Schema = components["schemas"];
type Node = {
  name: string;
  kind: Schema["InfrastructureNode"]["kind"];
  readiness: Schema["InfrastructureNode"]["status"];
  cpuPercent: number | null;
  memoryPercent: number | null;
};
type Accelerator = {
  node: string;
  vendor: string;
  model: string;
  slot: number;
  verified: boolean;
  usagePercent: number | null;
  memoryUsedBytes: number | null;
  memoryTotalBytes: number | null;
  temperatureC: number | null;
  powerWatts: number | null;
  utilizationNote: string | null;
};
export type Overview = {
  snapshotId: string;
  asOf: string;
  resources: {
    servers: number | null;
    edgeDevices: number | null;
    sensors: number | null;
    virtualDevices: number | null;
  };
  nodes: Node[];
  gpus: Accelerator[] | null;
  npus: Accelerator[] | null;
  inventory: {
    nodes: Schema["InfrastructureNode"][] | null;
    sensors: Schema["ClusterSensor"][] | null;
    metricsAvailable: boolean;
  };
  runs:
    Pick<Schema["WorkflowRun"], "id" | "state" | "mode" | "createdAt">[] | null;
  errors: string[];
};

function current(at: string, maxAge: number, now: number) {
  const timestamp = Date.parse(at);
  return (
    Number.isFinite(timestamp) &&
    timestamp <= now + 5000 &&
    now - timestamp <= maxAge * 1000
  );
}

function quantityCount(value: string) {
  const parts =
    /^([+-]?(?:\d+(?:\.\d*)?|\.\d+))(?:(n|u|m|k|M|G|T|P|E|Ki|Mi|Gi|Ti|Pi|Ei)|[eE]([+-]?\d+))?$/.exec(
      value,
    );
  if (!parts) return 0;
  const factors: Record<string, number> = {
    n: 1e-9,
    u: 1e-6,
    m: 1e-3,
    k: 1e3,
    M: 1e6,
    G: 1e9,
    T: 1e12,
    P: 1e15,
    E: 1e18,
    Ki: 1024,
    Mi: 1024 ** 2,
    Gi: 1024 ** 3,
    Ti: 1024 ** 4,
    Pi: 1024 ** 5,
    Ei: 1024 ** 6,
  };
  const count =
    Number(parts[1]) *
    (parts[2] ? factors[parts[2]] : parts[3] ? 10 ** Number(parts[3]) : 1);
  return Number.isSafeInteger(count) && count > 0 ? count : 0;
}

// Same exposure/identity boundary as lib/node-accelerators.ts: allocatable slots
// are not physical devices, and measurements alone cannot identify hardware.
function acceleratorObservations(
  kind: "GPU" | "NPU",
  nodes: Schema["InfrastructureNode"][] | null,
  metrics: Schema["NodeMetricsSnapshot"] | null,
  now: number,
): Accelerator[] | null {
  if (!nodes) return null;
  const maxAge = metrics?.maxAgeSeconds ?? 0;
  const vendors: Record<string, string> =
    kind === "GPU"
      ? {
          "nvidia.com/gpu": "nvidia",
          "nvidia.com/gpu.shared": "nvidia",
          "gpu.intel.com/i915": "intel",
          "gpu.intel.com/xe": "intel",
        }
      : {
          "hailo.ai/h8": "hailo",
          "mobilint.com/npu": "mobilint",
          "npu.intel.com/accel": "intel",
          "rockchip.com/rk3399pro": "rockchip",
        };
  const result: Accelerator[] = [];
  for (const node of nodes) {
    const resources = Object.entries(node.allocatable ?? {})
      .map(([resource, value]) => ({
        vendor: vendors[resource],
        count: quantityCount(value),
        shared: resource.endsWith(".shared"),
      }))
      .filter((r) => r.vendor && Number.isSafeInteger(r.count) && r.count > 0);
    if (!resources.length) continue;
    const item = metrics?.items.find((n) => n.nodeName === node.name);
    const hardware = (item?.accelerators ?? []).filter(
      (a) => a.kind === kind && a.fresh && current(a.observedAt, maxAge, now),
    );
    const exposed = [
      ...new Map(
        hardware
          .filter((a) => {
            const peers = new Set(
              hardware
                .filter(
                  (other) =>
                    other.vendor.toLowerCase() === a.vendor.toLowerCase(),
                )
                .map((other) => other.device),
            ).size;
            return resources.some(
              (r) =>
                r.vendor === a.vendor.toLowerCase() &&
                (r.shared ? peers === 1 : r.count >= peers),
            );
          })
          .map((a) => [a.device, a]),
      ).values(),
    ].sort((a, b) => a.device.localeCompare(b.device));
    const empty = {
      usagePercent: null,
      memoryUsedBytes: null,
      memoryTotalBytes: null,
      temperatureC: null,
      powerWatts: null,
      utilizationNote: null,
    };
    if (!exposed.length) {
      result.push({
        node: node.name,
        vendor: resources.every((r) => r.vendor === resources[0].vendor)
          ? resources[0].vendor
          : "",
        model: `${kind} 장착 정보 확인 불가`,
        slot: 1,
        verified: false,
        ...empty,
      });
      continue;
    }
    const labels = exposed.map(
      (a) => a.model.replace(/\s*\[[^\]]*\]/g, "").trim() || kind,
    );
    exposed.forEach((accelerator, index) => {
      function value(
        key: Schema["NodeMetric"]["key"],
        unit: Schema["NodeMetric"]["unit"],
      ) {
        const matches = (item?.measurements ?? []).filter(
          (m) =>
            m.key === key &&
            m.device === accelerator.device &&
            m.unit === unit &&
            m.fresh &&
            current(m.observedAt, maxAge, now) &&
            Number.isFinite(m.value) &&
            (unit === "CELSIUS" || m.value >= 0) &&
            (unit !== "PERCENT" || m.value <= 100),
        );
        return matches.length === 1 ? matches[0].value : null;
      }
      const label = labels[index];
      const ordinal = labels
        .slice(0, index + 1)
        .filter((other) => other === label).length;
      let used = value(`${kind}_MEMORY_USED`, "BYTES");
      const total = value(`${kind}_MEMORY_TOTAL`, "BYTES");
      if (used !== null && total !== null && used > total) used = null;
      result.push({
        node: node.name,
        vendor: accelerator.vendor.toLowerCase(),
        model:
          labels.filter((other) => other === label).length > 1
            ? `${label} ${ordinal}`
            : label,
        slot: index + 1,
        verified: true,
        usagePercent: value(`${kind}_USAGE`, "PERCENT"),
        memoryUsedBytes: used,
        memoryTotalBytes: total !== null && total > 0 ? total : null,
        temperatureC: value(`${kind}_TEMPERATURE`, "CELSIUS"),
        powerWatts: value(`${kind}_POWER`, "WATTS"),
        utilizationNote:
          kind === "NPU" &&
          ((accelerator.vendor.toLowerCase() === "hailo" &&
            label === "Hailo-8") ||
            (accelerator.vendor.toLowerCase() === "rockchip" &&
              label === "Rockchip RKNN NPU")) &&
          !(item?.measurements ?? []).some(
            (m) => m.key === "NPU_USAGE" && m.device === accelerator.device,
          )
            ? "사용률 별도 계측 필요"
            : null,
      });
    });
  }
  return result;
}

async function loadOverview(baseUrl: string): Promise<Overview> {
  const deadline = AbortSignal.timeout(10000);
  async function read<T>(path: string): Promise<T> {
    const response = await fetch(
      new URL(`/api/control-plane/${path}`, baseUrl),
      {
        cache: "no-store",
        signal: AbortSignal.any([deadline, AbortSignal.timeout(6500)]),
      },
    );
    if (!response.ok) throw new Error("EdgeAI 조회 실패");
    return response.json();
  }
  async function registeredVds() {
    let count = 0,
      offset = 0;
    for (let pageIndex = 0; pageIndex < 100; pageIndex++) {
      const page = await read<
        operations["listVirtualDevices"]["responses"][200]["content"]["application/json"]
      >(`virtual-devices?limit=100&offset=${offset}`);
      if (!Array.isArray(page.items)) throw new Error("잘못된 가상 장치 목록");
      count += page.items.filter((vd) => vd.state === "REGISTERED").length;
      if (page.nextOffset == null) return count;
      if (!Number.isSafeInteger(page.nextOffset) || page.nextOffset <= offset)
        throw new Error("잘못된 목록 페이지");
      offset = page.nextOffset;
    }
    throw new Error("가상 장치 전체 목록을 확인할 수 없습니다");
  }
  const [infrastructure, metrics, vds, runs] = await Promise.allSettled([
    read<Schema["InfrastructureSnapshot"]>("infrastructure"),
    read<Schema["NodeMetricsSnapshot"]>("node-metrics"),
    registeredVds(),
    read<Schema["RunPage"]>("workflow-runs?limit=20&offset=0"),
  ]);
  const now = Date.now();
  const errors: string[] = [];
  const infra =
    infrastructure.status === "fulfilled" ? infrastructure.value : null;
  const freshInfra = infra && current(infra.fetchedAt, 60, now);
  const nodes =
    freshInfra &&
    infra.nodesStatus === "AVAILABLE" &&
    Array.isArray(infra.nodes)
      ? infra.nodes
      : null;
  const sensors =
    freshInfra &&
    infra.sensorsStatus === "AVAILABLE" &&
    Array.isArray(infra.sensors)
      ? infra.sensors
      : null;
  if (!nodes) errors.push("노드 분류·준비 상태 확인 불가");
  if (!sensors) errors.push("센서 목록 확인 불가");
  const metricData =
    metrics.status === "fulfilled" &&
    metrics.value.status === "AVAILABLE" &&
    Array.isArray(metrics.value.items) &&
    current(metrics.value.fetchedAt, metrics.value.maxAgeSeconds, now)
      ? metrics.value
      : null;
  if (!metricData) errors.push("노드 사용량 확인 불가");
  if (vds.status === "rejected") errors.push("가상 장치 목록 확인 불가");
  const runItems =
    runs.status === "fulfilled" && Array.isArray(runs.value.items)
      ? runs.value.items
      : null;
  if (!runItems) errors.push("최근 실행 확인 불가");
  const gpus = acceleratorObservations("GPU", nodes, metricData, now);
  const npus = acceleratorObservations("NPU", nodes, metricData, now);
  if (gpus?.some((gpu) => !gpu.verified))
    errors.push("일부 GPU 장착 정보 확인 불가");
  if (npus?.some((npu) => !npu.verified))
    errors.push("일부 NPU 장착 정보 확인 불가");
  const names = [
    ...new Set([
      ...(nodes ?? []).map((n) => n.name),
      ...(metricData?.items ?? []).map((n) => n.nodeName),
    ]),
  ].sort();
  return {
    snapshotId: randomUUID(),
    asOf: new Date(now).toISOString(),
    resources: {
      servers: nodes
        ? nodes.filter((n) => n.kind === "EDGE_AI_SERVER").length
        : null,
      edgeDevices: nodes
        ? nodes.filter((n) => n.kind === "EDGE_DEVICE").length
        : null,
      sensors: sensors?.length ?? null,
      virtualDevices: vds.status === "fulfilled" ? vds.value : null,
    },
    nodes: names.map((name) => {
      const node = nodes?.find((n) => n.name === name);
      const observed = metricData?.items.find((n) => n.nodeName === name);
      function percent(key: "CPU_USAGE" | "MEMORY_USAGE") {
        const values =
          observed?.measurements.filter(
            (m) =>
              m.key === key &&
              m.fresh &&
              m.unit === "PERCENT" &&
              current(m.observedAt, metricData?.maxAgeSeconds ?? 0, now) &&
              Number.isFinite(m.value) &&
              m.value >= 0 &&
              m.value <= 100,
          ) ?? [];
        return values.length === 1 ? values[0].value : null;
      }
      const readiness = observed?.readiness;
      return {
        name,
        kind: node?.kind ?? "UNKNOWN",
        readiness:
          node?.status ??
          (readiness?.fresh &&
          current(readiness.observedAt, metricData?.maxAgeSeconds ?? 0, now)
            ? readiness.status
            : "UNKNOWN"),
        cpuPercent: percent("CPU_USAGE"),
        memoryPercent: percent("MEMORY_USAGE"),
      };
    }),
    gpus,
    npus,
    inventory: { nodes, sensors, metricsAvailable: metricData !== null },
    runs:
      runItems?.map(({ id, state, mode, createdAt }) => ({
        id,
        state,
        mode,
        createdAt,
      })) ?? null,
    errors,
  };
}

function chartResult(
  id: string,
  overview: Overview,
  pageNumber: number,
  pageSize: number,
) {
  const resource = (
    {
      edge_ai_servers: "servers",
      edge_devices: "edgeDevices",
      sensors: "sensors",
      virtual_devices: "virtualDevices",
    } as const
  )[id as "edge_ai_servers"];
  if (resource)
    return {
      results: [{ value: overview.resources[resource] ?? "—" }],
      total: 1,
    };
  if (id === "node_cpu") {
    const results = overview.nodes
      .filter((n) => n.cpuPercent !== null)
      .map((n) => ({ node: n.name, kind: "CPU", usage: n.cpuPercent }));
    return { results, total: results.length };
  }
  if (id === "node_readiness") {
    const results = ["READY", "NOT_READY", "UNKNOWN"]
      .map((state) => ({
        state,
        total: overview.nodes.filter((n) => n.readiness === state).length,
      }))
      .filter((n) => n.total > 0);
    return { results, total: results.length };
  }
  if (id === "gpu_usage" || id === "npu_usage") {
    const kind = id === "gpu_usage" ? "GPU" : "NPU";
    const results = ((kind === "GPU" ? overview.gpus : overview.npus) ?? [])
      .filter((device) => device.usagePercent !== null)
      .map((device) => ({
        device: `${device.node} · ${kind} ${device.slot}`,
        kind,
        usage: device.usagePercent,
      }));
    return { results, total: results.length };
  }
  if (id === "gpu_resources") {
    const percent = (v: number | null) =>
      v === null ? "—" : `${v.toFixed(1)}%`;
    const gib = (v: number | null) =>
      v === null ? "—" : (v / 1024 ** 3).toFixed(2);
    const results = (overview.gpus ?? []).map((gpu) => ({
      node: gpu.node,
      model: gpu.model,
      usage: percent(gpu.usagePercent),
      memory:
        gpu.memoryUsedBytes === null && gpu.memoryTotalBytes === null
          ? "—"
          : `${gib(gpu.memoryUsedBytes)} / ${gib(gpu.memoryTotalBytes)} GiB`,
      temperature:
        gpu.temperatureC === null ? "—" : `${gpu.temperatureC.toFixed(1)} °C`,
      power: gpu.powerWatts === null ? "—" : `${gpu.powerWatts.toFixed(1)} W`,
    }));
    return {
      results: results.slice(
        (pageNumber - 1) * pageSize,
        pageNumber * pageSize,
      ),
      total: results.length,
    };
  }
  const results =
    id === "node_resources"
      ? overview.nodes.map((n) => ({
          node: n.name,
          cpu: n.cpuPercent === null ? "—" : `${n.cpuPercent.toFixed(1)}%`,
          memory:
            n.memoryPercent === null ? "—" : `${n.memoryPercent.toFixed(1)}%`,
          gpu:
            overview.gpus?.filter((device) => device.node === n.name) ?? null,
          npu:
            overview.npus?.filter((device) => device.node === n.name) ?? null,
          readiness: n.readiness,
        }))
      : id === "recent_runs"
        ? (overview.runs ?? []).map((r) => ({
            id: r.id,
            state:
              (
                {
                  SUCCEEDED: "SUCCESS",
                  PENDING: "CREATED",
                  CANCELLING: "KILLING",
                } as Record<string, string>
              )[r.state] ?? r.state,
            mode: r.mode,
            createdAt: r.createdAt,
          }))
        : null;
  return results
    ? {
        results: results.slice(
          (pageNumber - 1) * pageSize,
          pageNumber * pageSize,
        ),
        total: results.length,
      }
    : null;
}

/** Read-only bridge from the native dashboard chart protocol to the existing EdgeAI BFF. */
export function createOverviewMiddleware({ baseUrl }: { baseUrl: string }) {
  // Lazy charts must read the same observation (including errors) as their toolbar.
  const snapshots = new Map<string, Overview>();
  return (
    request: IncomingMessage,
    response: ServerResponse,
    next: () => void,
  ) => {
    const url = new URL(request.url ?? "/", "http://preview.local");
    if (
      url.pathname !== "/api/edgeai/overview" &&
      url.pathname !== "/api/v1/main/dashboards/charts/preview"
    )
      return next();
    function send(status: number, body: unknown) {
      response.writeHead(status, {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "no-store",
      });
      response.end(JSON.stringify(body));
    }
    void (async () => {
      if (url.pathname === "/api/edgeai/overview") {
        if (request.method !== "GET")
          return send(405, { message: "운영 현황은 조회만 지원합니다." });
        const value = await loadOverview(baseUrl);
        snapshots.set(value.snapshotId, value);
        if (snapshots.size > 64)
          snapshots.delete(snapshots.keys().next().value!);
        return send(200, value);
      }
      if (request.method !== "POST")
        return send(405, { message: "차트 조회 요청을 확인하세요." });
      const chunks: Buffer[] = [];
      let bytes = 0;
      for await (const chunk of request) {
        bytes += chunk.length;
        if (bytes > 65536)
          return send(413, { message: "차트 조회 요청이 너무 큽니다." });
        chunks.push(Buffer.from(chunk));
      }
      const body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      const id =
        typeof body.chart === "string"
          ? /^id:\s*([a-z_]+)\s*$/m.exec(body.chart)?.[1]
          : undefined;
      if (
        !id ||
        ![
          "edge_ai_servers",
          "edge_devices",
          "sensors",
          "virtual_devices",
          "node_cpu",
          "node_readiness",
          "node_resources",
          "recent_runs",
          "gpu_usage",
          "npu_usage",
          "gpu_resources",
        ].includes(id)
      )
        return send(400, { message: "지원하지 않는 운영 현황 차트입니다." });
      const snapshotId = /^edgeaiSnapshot:\s*([a-f0-9-]{36})\s*$/m.exec(
        body.chart,
      )?.[1];
      const value = snapshotId ? snapshots.get(snapshotId) : undefined;
      if (!value)
        return send(410, {
          message: "관측이 만료됐습니다. 운영 현황을 새로고침하세요.",
        });
      const page = body.globalFilter?.pageNumber ?? 1,
        size = body.globalFilter?.pageSize ?? 25;
      if (
        !Number.isSafeInteger(page) ||
        page < 1 ||
        !Number.isSafeInteger(size) ||
        size < 1 ||
        size > 100
      )
        return send(400, { message: "잘못된 차트 페이지입니다." });
      send(200, chartResult(id, value, page, size));
    })().catch(() =>
      send(400, { message: "운영 현황 요청을 처리하지 못했습니다." }),
    );
  };
}
