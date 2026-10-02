import { NextRequest, NextResponse } from "next/server";
import { controlPlaneOrigin } from "../../../../lib/control-plane";

export const dynamic = "force-dynamic";

async function forward(request: NextRequest, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  const target = path.join("/");
  const uuid = "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}";
  const allowed = request.method === "GET" ? target === "csrf"
    || /^profiles\/(DEVICE|SERVICE|VD)(\/[a-z][a-z0-9._-]*\/versions\/[0-9]+\.[0-9]+\.[0-9]+)?$/.test(target)
    || new RegExp(`^(devices|nodes)(/${uuid})?$`).test(target)
    : request.method === "POST" ? /^profiles\/(DEVICE|SERVICE|VD)$/.test(target) || target === "devices" || new RegExp(`^devices/${uuid}/(sessions|observations)$`).test(target)
    : request.method === "PUT" ? new RegExp(`^devices/${uuid}/attachments/${uuid}$`).test(target)
    : ["PATCH", "DELETE"].includes(request.method) && new RegExp(`^devices/${uuid}$`).test(target);
  if (!allowed)
    return NextResponse.json({ message: "지원하지 않는 경로입니다." }, { status: 404 });
  const authorization = request.headers.get("authorization");
  if (!authorization?.startsWith("Basic "))
    return NextResponse.json({ message: "개발 계정으로 연결하세요." }, { status: 401 });
  const headers = new Headers({ Authorization: authorization });
  const session = request.cookies.get("EDGEAI_SESSION")?.value;
  if (session) headers.set("Cookie", `EDGEAI_SESSION=${session}`);
  const csrf = request.headers.get("x-csrf-token");
  if (csrf) headers.set("X-CSRF-TOKEN", csrf);
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
    const response = await fetch(`${controlPlaneOrigin()}/api/v1/${target}${request.nextUrl.search}`, {
      method: request.method, headers, body, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(8000),
    });
    const outgoing = new Headers({ "Content-Type": response.headers.get("content-type") || "application/json", "Cache-Control": "no-store" });
    const cookie = response.headers.get("set-cookie");
    if (cookie) outgoing.set("Set-Cookie", cookie);
    const location = response.headers.get("location");
    if (location) outgoing.set("Location", location.replace("/api/v1/", "/api/control-plane/"));
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
