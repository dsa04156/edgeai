import type { components } from "./api-schema";

export async function getControlPlaneHealth(): Promise<components["schemas"]["Health"]> {
  const port = process.env.EDGEAI_API_PORT ?? "18080";
  if (!/^\d{1,5}$/.test(port) || Number(port) < 1 || Number(port) > 65535) return { status: "DOWN" };
  try {
    const response = await fetch(`http://127.0.0.1:${port}/actuator/health/readiness`, {
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
