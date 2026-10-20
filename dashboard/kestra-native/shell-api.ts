import type { IncomingMessage, ServerResponse } from "node:http";

// Original frontend bootstrap answers; no Kestra server or DB is installed.
export const shellConfig = {
  version: "2.0.6",
  uuid: "edgeai-native-preview",
  isBasicAuthInitialized: true,
  isAnonymousUsageEnabled: false,
  isUiAnonymousUsageEnabled: false,
  isCustomDashboardsEnabled: false,
  isTemplateEnabled: false,
  isAiEnabled: false,
  isTaskRunEnabled: false,
  environment: { name: "EdgeAI · 운영 콘솔", color: "#631bf3" },
};

// Server-side settings only; the browser never chooses an upstream.
// Host defaults; an empty EDGEAI_WORKFLOW_API_BASE_URL sends workflows to the management API.
export function apiOrigins(env: Record<string, string | undefined>) {
  return {
    managementUrl: env.EDGEAI_API_BASE_URL ?? "http://127.0.0.1:18080",
    workflowUrl:
      env.EDGEAI_WORKFLOW_API_BASE_URL === ""
        ? undefined
        : (env.EDGEAI_WORKFLOW_API_BASE_URL ?? "http://127.0.0.1:18082"),
    platformUrl: env.EDGEAI_PLATFORM_SERVICE_URL || "http://127.0.0.1:18081",
    platformToken: env.EDGEAI_PLATFORM_SERVICE_TOKEN || undefined,
    workflowsEnabled: (env.EDGEAI_WORKFLOW_ENABLED ?? "true") === "true",
  };
}

export function createShellApiMiddleware({
  definitions,
}: {
  definitions: () => Record<string, string>;
}) {
  return (
    request: IncomingMessage,
    response: ServerResponse,
    next: () => void,
  ) => {
    // This cookie only unlocks the isolated original frontend, never the EdgeAI backend.
    response.setHeader(
      "Set-Cookie",
      "kestraBasicAuthenticated=true; Path=/; SameSite=Lax",
    );
    const url = new URL(request.url ?? "/", "http://preview.local");
    const pathname = url.pathname;
    if (
      !pathname.startsWith("/api/") &&
      !pathname.startsWith("/preview-meta/") &&
      !pathname.startsWith("/swagger/")
    )
      return next();
    let body;
    if (request.method !== "GET") {
      response.statusCode = 405;
      body = { message: "현재 단계는 조회만 지원합니다." };
    } else if (pathname.startsWith("/preview-meta/"))
      body = pathname.endsWith("/feeds")
        ? { feeds: [], version: "2.0.6" }
        : { content: "", metadata: {}, results: [] };
    else if (pathname === "/swagger/kestra.yml")
      body = {
        openapi: "3.0.3",
        info: { title: "EdgeAI", version: "1" },
        paths: {},
      };
    else if (pathname.endsWith("/configs/login"))
      body = { isBasicAuthInitialized: true };
    else if (pathname.endsWith("/configs")) body = shellConfig;
    else if (pathname.endsWith("/dashboards/defaults/definitions"))
      body = definitions();
    else if (
      pathname.includes("/plugins") ||
      pathname.endsWith("/namespaces") ||
      pathname.endsWith("/usages/all")
    )
      body = [];
    else if (pathname.endsWith("/flows/search"))
      body = { results: [], total: 0 };
    else {
      response.statusCode = 501;
      body = { message: "지원하지 않는 API입니다." };
    }
    response.setHeader("Content-Type", "application/json; charset=utf-8");
    response.setHeader("Cache-Control", "no-store");
    response.end(JSON.stringify(body));
  };
}
