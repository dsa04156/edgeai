import type { components } from "./api-schema";

export const acceleratorNames: Record<string, string> = {
  "nvidia.com/gpu": "NVIDIA GPU",
  "nvidia.com/gpu.shared": "NVIDIA 공유 GPU 슬롯",
  "hailo.ai/h8": "Hailo NPU",
  "mobilint.com/npu": "Mobilint NPU",
};

export function accelerators(node: Pick<components["schemas"]["ExecutionNode"], "allocatable">) {
  return Object.entries(node.allocatable || {}).filter(([key, value]) => key.includes("/") && Number(value) > 0);
}

export function hardwareLabel(node: components["schemas"]["ExecutionNode"]) {
  return accelerators(node).map(([key, value]) => `${acceleratorNames[key] || key} ${value}`).join(" · ") || "CPU";
}
