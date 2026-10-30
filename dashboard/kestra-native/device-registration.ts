import type { IncomingMessage, ServerResponse } from "node:http";

// Trusted server origins own management/workflow sessions; DDS gets the server-only token here.
// The browser cannot choose an upstream or access server credentials.
export function createDeviceRegistrationMiddleware({
  managementUrl,
  workflowUrl,
  platformUrl = "http://127.0.0.1:18081",
  platformToken,
  workflowsEnabled = true,
}: {
  managementUrl: string;
  workflowUrl?: string;
  platformUrl?: string;
  platformToken?: string;
  workflowsEnabled?: boolean;
}) {
  for (const value of [managementUrl, workflowUrl, platformUrl]) {
    if (!value) continue;
    const origin = new URL(value);
    if (
      !["http:", "https:"].includes(origin.protocol) ||
      origin.username ||
      origin.password ||
      origin.pathname !== "/" ||
      origin.search ||
      origin.hash
    )
      throw new Error("Invalid server API origin");
  }
  return async (
    request: IncomingMessage,
    response: ServerResponse,
    next: () => void,
  ) => {
    const url = new URL(request.url ?? "/", "http://preview.local");
    const devicePath =
      /^\/api\/control-plane\/managed-devices\/[a-z0-9]([a-z0-9.-]{0,61}[a-z0-9])?$/.test(
        url.pathname,
      );
    const propertyPath =
      /^\/api\/control-plane\/managed-devices\/[a-z0-9]([a-z0-9.-]{0,61}[a-z0-9])?\/properties\/[a-z][a-z0-9_]{0,62}$/.test(
        url.pathname,
      );
    const propertyCollection =
      /^\/api\/control-plane\/managed-devices\/[a-z0-9]([a-z0-9.-]{0,61}[a-z0-9])?\/properties$/.test(
        url.pathname,
      );
    const target = url.pathname.replace(/^\/api\/control-plane\//, "");
    const uuid =
      "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}";
    const profile =
      /^profiles\/SERVICE(\/[a-z][a-z0-9._-]*\/versions\/[0-9]+\.[0-9]+\.[0-9]+)?$/.test(
        target,
      );
    const coreRead =
      /^(infrastructure|node-metrics|nodes|virtual-devices|workflows|workflow-runs|audit-requests)$/.test(
        target,
      ) ||
      [
        "virtual-devices/continuous-catalog",
        "protocols",
        "workflow-csrf",
        "workflow-data-sources",
        "data-store",
        "inference/models",
        "offload-lab",
        "scenarios/image-comparison",
        "scenarios/image-comparison/runs",
        "scenarios/hawkeye-safety",
        "scenarios/hawkeye-safety/runs",
        "scenarios/hawkeye-safety/video",
        "scenarios/hawkeye-safety/video/sessions",
        "resource-profiles",
        "resource-profiles/capabilities",
        "resource-profiles/workloads",
        "resource-profiles/pods",
        "ai-models",
        "ai-models/registry",
        "ai-models/registry/repositories",
        "ai-models/registry/tags",
        "ai-models/registry/manifest",
      ].includes(target) ||
      new RegExp(`^ai-models/${uuid}$`).test(target) ||
      /^scenarios\/hawkeye-safety\/samples\/[a-z0-9_]{1,40}\/image$/.test(target) ||
      new RegExp(
        `^resource-profiles/(analyses/)?${uuid}$|^resource-profiles/${uuid}/handoff$|^resource-profiles/services/[a-z][a-z0-9._-]{0,99}/versions/[0-9]+\\.[0-9]+\\.[0-9]+/revisions/[1-9][0-9]{0,6}/handoff$`,
      ).test(target) ||
      new RegExp(
        `^(nodes|virtual-devices|workflows|workflow-runs|tasks|operations|audit-requests)/${uuid}$`,
      ).test(target) ||
      new RegExp(
        `^virtual-devices/${uuid}/(execution|processing-versions|outputs(/[a-z][a-z0-9_]{0,62}/history)?)$|^workflow-runs/${uuid}/(streams|placements|scheduling-decision)$|^scheduling-profiles/${uuid}$|^tasks/${uuid}/results(/[a-z][a-z0-9_]*/content)?$|^scenarios/image-comparison/runs/${uuid}(/results)?$|^scenarios/hawkeye-safety/runs/${uuid}$|^scenarios/hawkeye-safety/video/sessions/${uuid}(/live(/frames/[0-9]{1,9})?)?$|^scenarios/hawkeye-safety/video/tasks/${uuid}/frames/[0-9]{1,9}$`,
      ).test(target);
    const cameraPreview =
      /^managed-devices\/[a-z0-9]([a-z0-9.-]{0,61}[a-z0-9])?\/preview$/.test(
        target,
      );
    const historyRead = new RegExp(
      `^data-store/sensors/${uuid}/[a-z][a-z0-9_]{0,62}/(history|chart)$`,
    ).test(target);
    const corePost =
      [
        "profiles/SERVICE",
        "virtual-devices/continuous",
        "workflows",
        "workflow-runs",
        "scheduling-profiles",
        "resource-profiles",
        "resource-profiles/analyses",
        "ai-models",
        "device-profiles",
        "device-connections/test",
        "inference/predict",
        "offload-lab/sessions",
        "scenarios/image-comparison/runs",
        "scenarios/hawkeye-safety/runs",
        "scenarios/hawkeye-safety/video/sessions",
      ].includes(target) ||
      new RegExp(
        `^scenarios/image-comparison/runs/${uuid}/stop$|^scenarios/hawkeye-safety/video/sessions/${uuid}/stop$|^virtual-devices/${uuid}/(provision|drain)$|^workflows/${uuid}/versions$|^scheduling-profiles/${uuid}/placement-preview$|^workflow-runs/${uuid}/(cancel|drain)$|^tasks/${uuid}/cancel$|^tasks/${uuid}/offload$|^offload-lab/sessions/${uuid}/(stop|load|load/stop)$`,
      ).test(target);
    const coreDelete =
      (profile && target.includes("/versions/")) ||
      new RegExp(`^virtual-devices/${uuid}$`).test(target);
    const corePatch = new RegExp(`^virtual-devices/${uuid}/continuous$`).test(
      target,
    );
    const platform = url.pathname.startsWith("/api/platform-service/");
    const platformTarget = url.pathname.replace("/api/platform-service/", "");
    const platformRead =
      platform &&
      (platformTarget === "config" ||
        /^workflow\/status\/[a-z0-9][a-z0-9-]{0,62}$/.test(platformTarget));
    const platformWrite =
      platform &&
      ((request.method === "POST" &&
        ["workflow/build-image", "workflow/save-and-push"].includes(
          platformTarget,
        )) ||
        (request.method === "DELETE" && platformTarget === "workflow/delete"));
    const readable =
      (!platform &&
        (devicePath ||
          profile ||
          coreRead ||
          cameraPreview ||
          historyRead ||
          ["csrf", "device-profiles", "managed-devices"].includes(target))) ||
      platformRead;
    if (
      !readable &&
      !propertyPath &&
      !propertyCollection &&
      !corePost &&
      !coreDelete &&
      !corePatch &&
      !platformWrite
    )
      return next();
    const send = (status: number, message: string) => {
      if (response.destroyed || response.writableEnded) return;
      response.writeHead(status, {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "no-store",
      });
      response.end(JSON.stringify({ message }));
    };
    const writable =
      (!platform &&
        ((request.method === "POST" &&
          (target === "managed-devices" || propertyCollection || corePost)) ||
          (request.method === "PUT" && propertyPath) ||
          (request.method === "DELETE" &&
            (devicePath || propertyPath || coreDelete)) ||
          (request.method === "PATCH" && corePatch))) ||
      platformWrite;
    if (!(request.method === "GET" && readable) && !writable)
      return send(405, "지원하지 않는 요청 방식입니다.");
    if (
      cameraPreview &&
      (url.searchParams.getAll("uid").length !== 1 ||
        !new RegExp(`^${uuid}$`).test(url.searchParams.get("uid") ?? ""))
    )
      return send(400, "카메라 UID를 확인하세요.");
    const origin = request.headers.origin;
    const expectedOrigin = `${"encrypted" in request.socket && request.socket.encrypted ? "https" : "http"}://${request.headers.host}`;
    // DDS writes carry the server token, so they also require the browser's Origin (as the former BFF did).
    if (
      request.headers["sec-fetch-site"] === "cross-site" ||
      (origin && origin !== expectedOrigin) ||
      (platformWrite && !origin)
    )
      return send(403, "같은 Dashboard에서 요청하세요.");
    const workflow =
      /^(workflow-csrf|workflow-data-sources|workflows|workflow-runs|scheduling-profiles|tasks|operations|offload-lab)(\/|$)|^profiles\/SERVICE(\/|$)/.test(
        target,
      );
    if (
      !workflowsEnabled &&
      (platform || /^(workflows|workflow-runs|workflow-data-sources|scheduling-profiles|offload-lab)(\/|$)/.test(target))
    )
      return send(404, "지원하지 않는 경로입니다.");
    if (platform && !platformToken)
      return send(
        503,
        "Platform-Service 연결 설정이 없습니다. 설정을 읽도록 콘솔을 재실행하세요.",
      );
    // Without a separate workflow API the management API serves workflows, as the former BFF did.
    const apiOrigin = platform
      ? new URL(platformUrl).origin
      : workflow
        ? new URL(workflowUrl ?? managementUrl).origin
        : new URL(managementUrl).origin;
    const upstreamPath = platform
      ? "/" + platformTarget
      : target === "workflow-csrf"
        ? "/api/v1/csrf"
        : target.startsWith("managed-devices")
          ? "/api/" + target.replace(/^managed-devices/, "devices")
          : /^(device-profiles|protocols|device-connections)(\/|$)/.test(target)
            ? "/api/" + target
            : "/api/v1/" + target;
    // A single management API issues one session for both, like the former BFF without a workflow URL.
    const cookieName =
      workflow && workflowUrl ? "EDGEAI_WORKFLOW_SESSION=" : "EDGEAI_SESSION=";
    const headers = new Headers();
    if (platform) headers.set("X-Platform-Token", platformToken!);
    const session = request.headers.cookie
      ?.split(";")
      .map((value) => value.trim())
      .find((value) => value.startsWith(cookieName));
    if (session && !platform) headers.set("Cookie", session);
    // The browser origin was checked above; forward only to the trusted server origin.
    headers.set("Origin", apiOrigin);
    const csrf = request.headers["x-csrf-token"];
    if (typeof csrf === "string") headers.set("X-CSRF-TOKEN", csrf);
    const idempotency = request.headers["idempotency-key"];
    if (typeof idempotency === "string")
      headers.set("Idempotency-Key", idempotency);
    let body: string | undefined;
    if ((writable && request.method !== "DELETE") || platformWrite) {
      if (!request.headers["content-type"]?.startsWith("application/json"))
        return send(415, "JSON 요청이 필요합니다.");
      const chunks: Buffer[] = [];
      let length = 0;
      // Only prediction carries an image (4 MiB binary ≈ 5.6 MiB base64 JSON).
      const inference = target === "inference/predict";
      // The API accepts a measured scheduling profile up to 1 MiB.
      const schedulingProfile = target === "scheduling-profiles";
      try {
        for await (const chunk of request) {
          length += chunk.length;
          if (
            length >
            (platform
              ? 1_100_000
              : inference
                ? 6 * 1024 * 1024
                : schedulingProfile
                  ? 1024 * 1024
                  : 65536)
          )
            return send(
              413,
              platform
                ? "배포 정의는 1MB 이하로 입력하세요."
                : inference
                  ? "추론 이미지는 4 MiB 이하로 선택하세요."
                  : schedulingProfile
                    ? "측정 프로파일은 1 MiB 이하로 입력하세요."
                    : "JSON 규격은 64 KiB 이하로 입력하세요.",
            );
          chunks.push(chunk);
        }
      } catch {
        return send(400, "요청 본문을 읽지 못했습니다.");
      }
      body = Buffer.concat(chunks).toString("utf8");
      try {
        JSON.parse(body);
      } catch {
        return send(400, "JSON 입력을 확인하세요.");
      }
      headers.set("Content-Type", "application/json");
    }
    try {
      const upstream = await fetch(`${apiOrigin}${upstreamPath}${url.search}`, {
        method: request.method,
        headers,
        body,
        cache: "no-store",
        redirect: "error",
        signal: AbortSignal.timeout(
          platform
            ? platformTarget === "workflow/build-image"
              ? 950000
              : 190000
            : // A resource analysis waits for Prometheus (≤ 40 s budget) and the KRR run (bridge 120 s < API 130 s).
              target === "resource-profiles/analyses"
              ? 210000
              : 25000,
        ),
      });
      response.statusCode = upstream.status;
      response.setHeader(
        "Content-Type",
        upstream.headers.get("content-type") ?? "application/json",
      );
      response.setHeader("Cache-Control", "no-store");
      const cookies = upstream.headers.getSetCookie();
      if (cookies.length) response.setHeader("Set-Cookie", cookies);
      for (const name of ["x-edgeai-audit-id", "location"]) {
        const value = upstream.headers.get(name);
        if (value) response.setHeader(name, value);
      }
      response.end(Buffer.from(await upstream.arrayBuffer()));
    } catch {
      send(
        503,
        "EdgeAI 서버에 연결할 수 없습니다. 입력을 유지한 채 다시 시도하세요.",
      );
    }
  };
}
