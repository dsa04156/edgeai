// Mirror of the server's strict run-request parsing (backend ModelProfilingInput.run/load) so tests check what the UI
// actually sends against what the API accepts, instead of a mock that takes any shape. Returns the first refusal or null.
type Json = Record<string, unknown>;
const CONTENT_TYPES = ["application/json", "text/plain", "application/octet-stream"];
const BODY_MAX_BYTES = 65536;

class Refused extends Error {}
const refuse = (message: string): never => {
  throw new Refused(message);
};
function fields(value: unknown, required: string[], optional: string[]): Json {
  if (!value || typeof value !== "object" || Array.isArray(value)) refuse("Object required");
  const map = value as Json;
  for (const key of Object.keys(map)) if (!required.includes(key) && !optional.includes(key)) refuse(`Unexpected field ${key}`);
  for (const key of required) if (map[key] == null) refuse(`Missing field ${key}`);
  return map;
}
const integer = (value: unknown) => (Number.isInteger(value) ? (value as number) : refuse("Integer quantity required"));
const bounded = (value: unknown, min: number, max: number, label: string) => {
  const n = integer(value);
  return n < min || n > max ? refuse(`${label} out of range`) : n;
};
const text = (value: unknown, max: number) => (typeof value === "string" && value.trim() && value.length <= max && !/[\u0000-\u001f\u007f-\u009f]/.test(value) ? value : refuse("Invalid text"));
const string = (value: unknown) => (typeof value === "string" && value && value.length <= 2 * BODY_MAX_BYTES && !value.includes("\0") ? value : refuse("본문은 비어 있지 않은 문자열입니다."));
/** java.util.Base64.getDecoder(): standard alphabet, no whitespace, padding optional but correct when present. */
function decodedBytes(encoded: string) {
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(encoded) || encoded.length % 4 === 1 || (encoded.includes("=") && encoded.length % 4 !== 0)) refuse("bodyBase64가 Base64 형식이 아닙니다.");
  const data = encoded.replace(/=+$/, "");
  return Math.floor((data.length * 3) / 4);
}

function load(value: unknown) {
  const map = fields(value, ["type"], ["port", "path", "method", "contentType", "body", "bodyBase64", "concurrency", "requestTimeoutSeconds", "readinessTimeoutSeconds", "command", "args"]);
  const type = text(map.type, 16);
  if (map.readinessTimeoutSeconds != null) bounded(map.readinessTimeoutSeconds, 30, 900, "readinessTimeoutSeconds");
  if (type === "COMMAND") {
    fields(value, ["type", "command"], ["args", "readinessTimeoutSeconds"]);
    for (const list of [map.command, map.args]) {
      if (list == null) continue;
      if (!Array.isArray(list) || list.length > 32 || list.some((v) => typeof v !== "string" || !v || v.length > 4096 || v.includes("\0"))) refuse("Invalid command");
    }
    return;
  }
  if (type !== "HTTP") refuse("부하 방식은 HTTP 또는 COMMAND입니다.");
  fields(value, ["type", "port", "path", "method"], ["contentType", "body", "bodyBase64", "concurrency", "requestTimeoutSeconds", "readinessTimeoutSeconds"]);
  bounded(map.port, 1, 65535, "port");
  const path = text(map.path, 512);
  if (!path.startsWith("/") || /[#\\]/.test(path) || /[^\x21-\x7e]/.test(path) || path.split(/[/?]/).includes("..")) refuse("요청 경로");
  const method = text(map.method, 8);
  if (!["GET", "POST"].includes(method)) refuse("메서드는 GET 또는 POST입니다.");
  const body = map.body == null ? null : string(map.body);
  const base64 = map.bodyBase64 == null ? null : string(map.bodyBase64);
  const contentType = map.contentType == null ? null : text(map.contentType, 64);
  let bytes = 0;
  if (method === "GET") {
    if (body != null || base64 != null || contentType != null) refuse("GET 요청에는 본문·contentType을 지정하지 않습니다.");
  } else {
    if (contentType == null || !CONTENT_TYPES.includes(contentType)) refuse("contentType");
    if ((body == null) === (base64 == null)) refuse("POST 요청에는 body 또는 bodyBase64 중 하나가 필요합니다.");
    if (base64 != null && contentType !== "application/octet-stream") refuse("bodyBase64는 application/octet-stream에만 씁니다.");
    if (body != null && contentType === "application/octet-stream") refuse("application/octet-stream 본문은 bodyBase64로 보냅니다.");
    bytes = base64 != null ? decodedBytes(base64) : new TextEncoder().encode(body!).length;
  }
  if (map.concurrency != null) bounded(map.concurrency, 1, 16, "concurrency");
  if (map.requestTimeoutSeconds != null) bounded(map.requestTimeoutSeconds, 1, 60, "requestTimeoutSeconds");
  if (bytes > BODY_MAX_BYTES) refuse("요청 본문은 64KiB 이하입니다.");
}

export function backendRunRefusal(request: unknown): string | null {
  try {
    const map = fields(request, ["nodeName", "allocation", "load"], ["durationSeconds"]);
    if (!/^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$/.test(text(map.nodeName, 253))) refuse("노드 이름 형식이 아닙니다.");
    const a = fields(map.allocation, ["cpuMillicores", "memoryBytes"], ["accelerator"]);
    bounded(a.cpuMillicores, 100, 8000, "cpuMillicores");
    bounded(a.memoryBytes, 67108864, 17179869184, "memoryBytes");
    if (a.accelerator != null) {
      const x = fields(a.accelerator, ["resourceName", "count"], []);
      text(x.resourceName, 253);
      bounded(x.count, 1, 4, "accelerator count");
    }
    if (map.durationSeconds != null) bounded(map.durationSeconds, 300, 1800, "durationSeconds");
    load(map.load);
    return null;
  } catch (error) {
    if (error instanceof Refused) return error.message;
    throw error;
  }
}
