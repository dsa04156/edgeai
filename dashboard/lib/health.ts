import type { components } from "./api-schema";
import { controlPlaneOrigin } from "./control-plane";

export async function getControlPlaneHealth(): Promise<components["schemas"]["Health"]> {
  try {
    const response = await fetch(`${controlPlaneOrigin()}/actuator/health/readiness`, {
      cache: "no-store",
      signal: AbortSignal.timeout(2000),
    });
    const body: unknown = await response.json();
    if (response.ok && typeof body === "object" && body !== null && "status" in body && body.status === "UP") {
      return { status: "UP" };
    }
  } catch { /* An unreachable dependency is not a healthy platform. */ }
  return { status: "DOWN" };
}
