export const dynamic = "force-dynamic";

// Liveness must not restart healthy UI processes during a database/API outage.
export async function GET() {
  return Response.json({ status: "UP" });
}
