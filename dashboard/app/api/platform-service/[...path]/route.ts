import { NextRequest, NextResponse } from "next/server";
import { workflowsEnabled } from "../../../../lib/features";

export const dynamic = "force-dynamic";
export const maxDuration = 960;

async function forward(request: NextRequest, context: { params: Promise<{ path: string[] }> }) {
  const target = (await context.params).path.join("/");
  const allowed = request.method === "GET" ? target === "config" || /^workflow\/status\/[a-z0-9][a-z0-9-]{0,62}$/.test(target)
    : request.method === "POST" ? ["workflow/build-image", "workflow/save-and-push"].includes(target)
    : request.method === "DELETE" && target === "workflow/delete";
  if (!workflowsEnabled() || !allowed) return NextResponse.json({ detail: "지원하지 않는 경로입니다." }, { status: 404 });
  if (request.method !== "GET") {
    const origin = request.headers.get("origin");
    if (!origin || request.headers.get("sec-fetch-site") === "cross-site" ||
        (origin !== request.nextUrl.origin && origin !== `${request.nextUrl.protocol}//${request.headers.get("host")}`))
      return NextResponse.json({ detail: "같은 대시보드에서 요청하세요." }, { status: 403 });
    if (!request.headers.get("content-type")?.startsWith("application/json"))
      return NextResponse.json({ detail: "JSON 요청이 필요합니다." }, { status: 415 });
  }
  const token = process.env.EDGEAI_PLATFORM_SERVICE_TOKEN;
  if (!token) return NextResponse.json({ detail: "Platform-Service 연결 설정이 없습니다. 설정을 읽도록 대시보드를 재실행하세요." }, { status: 503 });
  let body: string | undefined;
  if (request.method !== "GET") {
    const reader = request.body?.getReader(); const chunks: Uint8Array[] = []; let size = 0;
    if (reader) for (;;) {
      const item = await reader.read(); if (item.done) break;
      size += item.value.byteLength;
      if (size > 1_100_000) { await reader.cancel(); return NextResponse.json({ detail: "배포 정의는 1MB 이하로 입력하세요." }, { status: 413 }); }
      chunks.push(item.value);
    }
    body = Buffer.concat(chunks).toString("utf8");
  }
  try {
    const base = new URL(process.env.EDGEAI_PLATFORM_SERVICE_URL || "http://127.0.0.1:18081");
    if (!["http:", "https:"].includes(base.protocol) || base.username || base.password || base.search || base.hash || base.pathname !== "/") throw new Error("Invalid integration origin");
    const response = await fetch(`${base.origin}/${target}`, {
      method: request.method, body, headers: { "Content-Type": "application/json", "X-Platform-Token": token },
      redirect: "error", cache: "no-store", signal: AbortSignal.timeout(target === "workflow/build-image" ? 930000 : 180000),
    });
    return new NextResponse(response.body, { status: response.status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
  } catch {
    return NextResponse.json({ detail: "Platform-Service API 응답을 확인할 수 없습니다. 실행 상태와 배포 상태를 확인하세요." }, { status: 503 });
  }
}
export const GET = forward;
export const POST = forward;
export const DELETE = forward;
