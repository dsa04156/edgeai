import { workflowsEnabled } from "../../../../lib/features";
import { NextRequest, NextResponse } from "next/server";
import { controlPlaneOrigin } from "../../../../lib/control-plane";

export const dynamic = "force-dynamic";

async function forward(request: NextRequest, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  const target = path.join("/");
  const managedProperties = /^managed-devices\/[a-z0-9][a-z0-9.-]*\/properties$/.test(target);
  const managedProperty = /^managed-devices\/[a-z0-9][a-z0-9.-]*\/properties\/[a-z][a-z0-9_]*$/.test(target);
  const managed = /^managed-devices(\/[a-z0-9][a-z0-9.-]*)?$/.test(target) || ["device-profiles", "protocols", "device-connections/test"].includes(target);
  if (!workflowsEnabled() && /^(workflows|workflow-runs)(\/|$)/.test(target))
    return NextResponse.json({ message: "지원하지 않는 경로입니다." }, { status: 404 });
  const uuid = "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}";
  const continuousAllowed = request.method === "GET" && (target === "virtual-devices" || target === "virtual-devices/continuous-catalog" || new RegExp(`^virtual-devices/${uuid}(/execution|/outputs(/[a-z][a-z0-9_]{0,62}/history)?)?$`).test(target))
    || request.method === "POST" && (target === "virtual-devices/continuous" || new RegExp(`^virtual-devices/${uuid}/(provision|drain)$`).test(target))
    || request.method === "PATCH" && new RegExp(`^virtual-devices/${uuid}/continuous$`).test(target)
    || request.method === "DELETE" && new RegExp(`^virtual-devices/${uuid}$`).test(target);
  const allowed = request.method === "GET" ? target === "csrf" || target === "node-metrics" || target === "infrastructure"
    || target === "data-store" || new RegExp(`^data-store/sensors/${uuid}/[a-z][a-z0-9_]{0,62}/history$`).test(target)
    || new RegExp(`^virtual-devices/${uuid}/processing-versions$`).test(target)
    || ["sensors/readings", "sensors/commands", "sensors/registration-options"].includes(target)
    || new RegExp(`^audit-requests(/${uuid})?$`).test(target)
    || /^profiles\/(DEVICE|SERVICE)(\/[a-z][a-z0-9._-]*\/versions\/[0-9]+\.[0-9]+\.[0-9]+)?$/.test(target)
    || new RegExp(`^workflow-runs/${uuid}/(streams|placements)$`).test(target)
    || new RegExp(`^tasks/${uuid}/results/[a-z][a-z0-9_]*/content$`).test(target)
    || new RegExp(`^(devices|nodes)(/${uuid})?$`).test(target)
    || new RegExp(`^(workflows|workflow-runs)(/${uuid})?$|^tasks/${uuid}(/results)?$|^operations/${uuid}$`).test(target)
    : request.method === "POST" ? /^profiles\/(DEVICE|SERVICE)$/.test(target) || ["devices", "workflows", "workflow-runs", "sensors/command", "sensors/registrations"].includes(target) || new RegExp(`^devices/${uuid}/(sessions|observations)$|^workflows/${uuid}/versions$|^(workflow-runs|tasks)/${uuid}/cancel$|^tasks/${uuid}/offload$`).test(target)
    : request.method === "PUT" ? new RegExp(`^devices/${uuid}/attachments/${uuid}$`).test(target)
    : request.method === "DELETE" ? new RegExp(`^(devices)/${uuid}(/registration)?$`).test(target)
      || /^profiles\/(DEVICE|SERVICE)\/[a-z][a-z0-9._-]*\/versions\/[0-9]+\.[0-9]+\.[0-9]+$/.test(target)
    : ["PATCH", "DELETE"].includes(request.method) && new RegExp(`^(devices)/${uuid}$`).test(target);
  const managedAllowed = request.method === "POST" && managedProperties || ["PUT", "DELETE"].includes(request.method) && managedProperty || managed && (request.method === "GET" && target !== "device-connections/test" || request.method === "POST" && ["managed-devices", "device-profiles", "device-connections/test"].includes(target) || ["PUT", "DELETE"].includes(request.method) && /^managed-devices\/[a-z0-9][a-z0-9.-]*$/.test(target));
  if (!allowed && !managedAllowed && !continuousAllowed)
    return NextResponse.json({ message: "지원하지 않는 경로입니다." }, { status: 404 });
  // The open dashboard may mutate through its own origin only. Spring still verifies CSRF.
  const origin = request.headers.get("origin");
  if (request.headers.get("sec-fetch-site") === "cross-site" ||
      (origin && origin !== request.nextUrl.origin && origin !== `${request.nextUrl.protocol}//${request.headers.get("host")}`))
    return NextResponse.json({ message: "같은 Dashboard에서 요청하세요." }, { status: 403 });
  const headers = new Headers();
  const session = request.cookies.get("EDGEAI_SESSION")?.value;
  if (session) headers.set("Cookie", `EDGEAI_SESSION=${session}`);
  const csrf = request.headers.get("x-csrf-token");
  if (csrf) headers.set("X-CSRF-TOKEN", csrf);
  const idempotency = request.headers.get("idempotency-key");
  if (idempotency) headers.set("Idempotency-Key", idempotency);
  let body: string | undefined;
  if (["POST", "PATCH", "PUT"].includes(request.method)) {
    if (!request.headers.get("content-type")?.startsWith("application/json"))
      return NextResponse.json({ message: "JSON 요청이 필요합니다." }, { status: 415 });
    // Stream with a hard byte cap before buffering the complete request.
    const reader = request.body?.getReader();
    const chunks: Uint8Array[] = [];
    let length = 0;
    if (reader) {
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        length += chunk.value.byteLength;
        if (length > 65536) {
          await reader.cancel();
          return NextResponse.json({ message: "JSON 규격은 64 KiB 이하로 입력하세요." }, { status: 413 });
        }
        chunks.push(chunk.value);
      }
    }
    body = Buffer.concat(chunks).toString("utf8");
    headers.set("Content-Type", "application/json");
  }
  try {
    const backendPath = managedAllowed ? `/api/${target.replace(/^managed-devices/, "devices")}` : `/api/v1/${target}`;
    const response = await fetch(`${controlPlaneOrigin()}${backendPath}${request.nextUrl.search}`, {
      method: request.method, headers, body, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(managedAllowed || target.startsWith("sensors/") ? 20000 : 8000),
    });
    const outgoing = new Headers({ "Content-Type": response.headers.get("content-type") || "application/json", "Cache-Control": "no-store" });
    const cookie = response.headers.get("set-cookie");
    if (cookie) outgoing.set("Set-Cookie", cookie);
    const location = response.headers.get("location");
    if (location) outgoing.set("Location", location.replace("/api/v1/", "/api/control-plane/"));
    const auditId = response.headers.get("x-edgeai-audit-id");
    if (auditId && new RegExp(`^${uuid}$`).test(auditId)) outgoing.set("X-EdgeAI-Audit-Id", auditId);
    return new NextResponse(response.body, { status: response.status, headers: outgoing });
  } catch {
    return NextResponse.json({ message: "Control Plane에 연결할 수 없습니다. 실행 상태를 확인하세요." }, { status: 503 });
  }
}
export const GET = forward;
export const POST = forward;
export const PATCH = forward;
export const PUT = forward;
export const DELETE = forward;
