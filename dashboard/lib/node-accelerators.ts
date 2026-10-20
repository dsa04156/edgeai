import type { components } from "./api-schema";
import { isCurrent, type NodeMetric, type NodeMetricsState } from "./node-metrics";

type Node = components["schemas"]["NodeMetrics"];

const resourceHardware: Record<string, readonly [string, string]> = {
  "nvidia.com/gpu": ["GPU", "nvidia"],
  "nvidia.com/gpu.shared": ["GPU", "nvidia"],
  "gpu.intel.com/i915": ["GPU", "intel"],
  "gpu.intel.com/xe": ["GPU", "intel"],
  "hailo.ai/h8": ["NPU", "hailo"],
  "mobilint.com/npu": ["NPU", "mobilint"],
  "npu.intel.com/accel": ["NPU", "intel"],
  "rockchip.com/rk3399pro": ["NPU", "rockchip"],
};

function quantityCount(value: string) {
  const parts = /^([+-]?(?:\d+(?:\.\d*)?|\.\d+))(?:(n|u|m|k|M|G|T|P|E|Ki|Mi|Gi|Ti|Pi|Ei)|[eE]([+-]?\d+))?$/.exec(value);
  if (!parts) return 0;
  const factors: Record<string, number> = { n: 1e-9, u: 1e-6, m: 1e-3, k: 1e3, M: 1e6, G: 1e9, T: 1e12, P: 1e15, E: 1e18,
    Ki: 1024, Mi: 1024 ** 2, Gi: 1024 ** 3, Ti: 1024 ** 4, Pi: 1024 ** 5, Ei: 1024 ** 6 };
  const count = Number(parts[1]) * (parts[2] ? factors[parts[2]] : parts[3] ? 10 ** Number(parts[3]) : 1);
  return Number.isFinite(count) && Number.isInteger(count) && count > 0 ? count : 0;
}

/** Internal identities only join inventory to measurements; labels never contain them. */
export function nodeAccelerators(item: Node | undefined, state: NodeMetricsState, allocatable?: Record<string, string>) {
  const exposed = Object.entries(allocatable ?? {}).map(([resource, value]) => ({ hardware: resourceHardware[resource], count: quantityCount(value), shared: resource.endsWith(".shared") }))
    .filter(resource => resource.hardware && resource.count > 0);
  const devices = new Map<string, { id: string; kind: string; label: string; measurements: NodeMetric[]; utilizationNote?: string }>();
  for (const hardware of item?.accelerators ?? []) {
    if (!isCurrent(hardware, state)) continue;
    if (allocatable) {
      const peers = new Set((item?.accelerators ?? []).filter(other => isCurrent(other, state) && other.kind === hardware.kind
        && other.vendor.toLowerCase() === hardware.vendor.toLowerCase()).map(other => other.device)).size;
      // Counts cannot identify a partially exposed device subset; shared slots are not physical devices.
      if (!exposed.some(resource => resource.hardware[0] === hardware.kind && resource.hardware[1] === hardware.vendor.toLowerCase()
        && (resource.shared ? peers === 1 : resource.count >= peers))) continue;
    }
    const id = `${hardware.kind}\n${hardware.device}`;
    // The inventory's generic PCI model includes vendor/product codes, not a product name.
    const model = hardware.model.replace(/\s*\[[^\]]*\]/g, "").trim();
    const requiresInstrumentation = hardware.kind === "NPU" && (hardware.vendor.toLowerCase() === "hailo" && model === "Hailo-8"
      || hardware.vendor.toLowerCase() === "rockchip" && model === "Rockchip RKNN NPU");
    devices.set(id, { id, kind: hardware.kind, label: model || hardware.kind, measurements: [],
      utilizationNote: requiresInstrumentation ? "사용률 별도 계측 필요" : undefined });
  }
  for (const metric of item?.measurements ?? []) {
    if (!/^(GPU|NPU)_/.test(metric.key)) continue;
    const kind = metric.key.split("_")[0];
    const id = `${kind}\n${metric.device}`;
    // A resource quantity alone cannot attach a measurement to an unknown device.
    if (allocatable && !devices.has(id)) continue;
    if (!devices.has(id)) devices.set(id, { id, kind, label: kind, measurements: [] });
    devices.get(id)!.measurements.push(metric);
  }
  const result = [...devices.values()].sort((a, b) => a.id.localeCompare(b.id));
  return result.map(device => {
    const peers = result.filter(other => other.label === device.label);
    return { ...device, utilizationNote: device.measurements.some(metric => metric.key.endsWith("_USAGE")) ? undefined : device.utilizationNote, label: peers.length > 1 ? `${device.label} ${peers.indexOf(device) + 1}` : device.label };
  });
}
