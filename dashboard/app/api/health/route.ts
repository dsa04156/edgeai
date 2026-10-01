import { getControlPlaneHealth } from "../../../lib/health";

export const dynamic = "force-dynamic";

export async function GET() {
  const health = await getControlPlaneHealth();
  return Response.json(health, { status: health.status === "UP" ? 200 : 503 });
}
