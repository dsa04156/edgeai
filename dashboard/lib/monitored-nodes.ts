import type { components } from "./api-schema";
import { isCurrent, type NodeMetricsState } from "./node-metrics";
import { nodeAccelerators } from "./node-accelerators";
import { currentInfrastructure, type InfrastructureSnapshot } from "./infrastructure";

type ExecutionNode = components["schemas"]["ExecutionNode"];

export function currentInventory(nodes: ExecutionNode[], now: number) {
  return nodes.filter(node => node.status !== "REMOVED" && node.status !== "STALE"
    && Date.parse(node.observedAt) <= now + 5000 && now - Date.parse(node.observedAt) <= 60000);
}

/** Monitoring identity comes from Prometheus, never from historical execution records. */
export function monitoredNodes(state: NodeMetricsState, inventory: ExecutionNode[] = [], infrastructure?: InfrastructureSnapshot | null) {
  const live = currentInfrastructure(infrastructure, state.now || Date.now());
  const liveNodes = live?.nodesStatus === "AVAILABLE" ? live.nodes : [];
  const items = !state.failed && state.snapshot?.status === "AVAILABLE" ? state.snapshot.items : [];
  const current = currentInventory(inventory, state.now);
  const names = [...new Set([...items.map(item => item.nodeName), ...liveNodes.map(node => node.name)])].sort();
  return names.map(name => {
    const item = items.find(item => item.nodeName === name) ?? { nodeName: name, measurements: [] };
    const physical = liveNodes.find(node => node.name === name);
    const matches = current.filter(node => node.name === item.nodeName);
    const node = matches.length === 1 ? matches[0] : undefined;
    const devices = nodeAccelerators(item, state);
    const kinds = ["GPU", "NPU"].filter(kind => devices.some(device => device.kind === kind));
    const status = physical?.status ?? (item.readiness ? (isCurrent(item.readiness, state) ? item.readiness.status : "UNKNOWN") : node?.status || "UNKNOWN");
    return { name: item.nodeName, node: physical ?? node, kinds, status, devices, kind: physical?.kind ?? "UNKNOWN" };
  });
}
