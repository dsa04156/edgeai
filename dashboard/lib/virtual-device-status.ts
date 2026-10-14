import type { components } from "./api-schema";
import { registryItems } from "./registry";

type Schema = components["schemas"];
export type VirtualDeviceStatuses = { states: string[]; failures: number };

/** Registration counts and actual execution states are separate; failed detail reads stay unknown. */
export async function virtualDeviceStatuses(request: (path: string) => Promise<Response>): Promise<VirtualDeviceStatuses> {
  const devices = (await registryItems<Schema["VirtualDevice"]>("virtual-devices", request)).filter(device => device.state === "REGISTERED");
  const states = devices.map(() => "UNKNOWN");
  let next = 0, failures = 0;
  async function worker() {
    for (;;) {
      const index = next++;
      if (index >= devices.length) return;
      try {
        const execution: Schema["VDExecution"] = await (await request(`virtual-devices/${devices[index].id}/execution`)).json();
        const runtime = execution.continuous;
        states[index] = !runtime ? "UNKNOWN" : runtime.enabled === false ? "DISABLED" : runtime.state;
      } catch { failures++; }
    }
  }
  await Promise.all(Array.from({ length: Math.min(4, devices.length) }, worker));
  return { states, failures };
}
