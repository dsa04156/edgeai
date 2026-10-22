import { isLosslessNumber, parse, stringify } from "lossless-json";
import { onBeforeUnmount, ref } from "vue";
import type { components } from "./live-api-schema";
export type Schema = components["schemas"];
export function requestKey() {
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 15) | 64;
  b[8] = (b[8] & 63) | 128;
  const h = [...b].map((v) => v.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
export async function request(
  path: string,
  init?: RequestInit,
  platform = false,
) {
  const response = await fetch(
    `/api/${platform ? "platform-service" : "control-plane"}/${path}`,
    {
      cache: "no-store",
      signal: AbortSignal.timeout(platform ? 950000 : 25000),
      ...init,
    },
  );
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    const audit = response.headers.get("x-edgeai-audit-id");
    throw new Error(
      `${data.message || data.detail || `요청 실패 (${response.status})`}${audit ? ` · 감사 ID ${audit}` : ""}`,
    );
  }
  return response;
}
export async function get<T>(path: string): Promise<T> {
  return (await request(path)).json();
}
export async function profileItems(kind: "SERVICE") {
  return all<Schema["ProfileVersion"]>(`profiles/${kind}`, async (response) => {
    const page = parse(await response.text()) as {
      items: Schema["ProfileVersion"][];
      nextOffset: number | null;
    };
    return {
      ...page,
      nextOffset: page.nextOffset == null ? null : Number(page.nextOffset),
    };
  });
}
export async function all<T>(
  path: string,
  read: (
    response: Response,
  ) => Promise<{ items: T[]; nextOffset: number | null }> = (response) =>
    response.json(),
): Promise<T[]> {
  const items: T[] = [];
  let offset = 0;
  for (;;) {
    const page = await read(
      await request(`${path}?limit=100&offset=${offset}`),
    );
    items.push(...page.items);
    if (page.nextOffset == null) return items;
    if (page.nextOffset <= offset)
      throw new Error("목록 페이지 조회에 실패했습니다.");
    offset = page.nextOffset;
  }
}
export async function writeResponse(
  path: string,
  method: string,
  body?: unknown,
  key?: string,
  platform = false,
) {
  const { token } = platform
    ? { token: "" }
    : await get<{ token: string }>(
        /^(workflows|workflow-runs|tasks|operations)(\/|$)|^profiles\/SERVICE(\/|$)/.test(
          path,
        )
          ? "workflow-csrf"
          : "csrf",
      );
  const response = await request(
    path,
    {
      method,
      headers: {
        "X-CSRF-TOKEN": token,
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
        ...(key ? { "Idempotency-Key": key } : {}),
      },
      ...(body !== undefined ? { body: stringify(body) } : {}),
    },
    platform,
  );
  return response;
}
export async function write<T>(
  path: string,
  method: string,
  body?: unknown,
  key?: string,
  platform = false,
): Promise<T> {
  const response = await writeResponse(path, method, body, key, platform);
  return response.status === 204 ? (undefined as T) : response.json();
}
export function objectJson(text: string): Record<string, unknown> {
  const value = parse(text);
  if (
    !value ||
    typeof value !== "object" ||
    Array.isArray(value) ||
    isLosslessNumber(value)
  )
    throw new Error("JSON 객체를 입력하세요.");
  return value as Record<string, unknown>;
}
export const pretty = (value: unknown) => stringify(value, null, 2) ?? "";
export const time = (value: unknown) =>
  value ? new Date(String(value)).toLocaleString("ko-KR") : "—";
export function useAction() {
  const busy = ref(false),
    error = ref(""),
    notice = ref("");
  async function act(action: () => Promise<unknown>) {
    if (busy.value) return;
    busy.value = true;
    error.value = "";
    notice.value = "";
    try {
      await action();
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : "요청 실패";
    } finally {
      busy.value = false;
    }
  }
  return { busy, error, notice, act };
}
export function usePoll(action: () => Promise<unknown>, interval = 5000) {
  let stopped = false,
    timer: ReturnType<typeof setTimeout>;
  const poll = async () => {
    try {
      await action();
    } finally {
      if (!stopped) timer = setTimeout(poll, interval);
    }
  };
  timer = setTimeout(poll, interval);
  onBeforeUnmount(() => {
    stopped = true;
    clearTimeout(timer);
  });
}
