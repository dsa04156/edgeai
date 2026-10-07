import type { components } from "./api-schema";
import { isCurrent, type NodeMetric, type NodeMetricsState } from "./node-metrics";

type Node = components["schemas"]["NodeMetrics"];

/** Internal identities only join inventory to measurements; labels never contain them. */
export function nodeAccelerators(item: Node | undefined, state: NodeMetricsState) {
  const devices = new Map<string, { id: string; kind: string; label: string; measurements: NodeMetric[] }>();
  for (const hardware of item?.accelerators ?? []) {
    if (!isCurrent(hardware, state)) continue;
    const id = `${hardware.kind}\n${hardware.device}`;
    // The inventory's generic PCI model includes vendor/product codes, not a product name.
    const model = hardware.model.replace(/\s*\[[^\]]*\]/g, "").trim();
    devices.set(id, { id, kind: hardware.kind, label: model || hardware.kind, measurements: [] });
  }
  for (const metric of item?.measurements ?? []) {
    if (!/^(GPU|NPU)_/.test(metric.key)) continue;
    const kind = metric.key.split("_")[0];
    const id = `${kind}\n${metric.device}`;
    if (!devices.has(id)) devices.set(id, { id, kind, label: kind, measurements: [] });
    devices.get(id)!.measurements.push(metric);
  }
  const result = [...devices.values()].sort((a, b) => a.id.localeCompare(b.id));
  return result.map(device => {
    const peers = result.filter(other => other.label === device.label);
    return { ...device, label: peers.length > 1 ? `${device.label} ${peers.indexOf(device) + 1}` : device.label };
  });
}
