"use client";
import { useEffect, useState } from "react";
import type { components } from "./api-schema";

export type InfrastructureSnapshot = components["schemas"]["InfrastructureSnapshot"];
export type InfrastructureKind = components["schemas"]["InfrastructureNode"]["kind"];
export const infrastructureLabels = { EDGE_AI_SERVER: "엣지 AI 서버", EDGE_DEVICE: "엣지 디바이스", UNKNOWN: "분류 미확인" };
export function currentInfrastructure(snapshot: InfrastructureSnapshot | null | undefined, now: number) {
  const observed = Date.parse(snapshot?.fetchedAt ?? "");
  return snapshot && observed <= now + 5000 && now - observed <= 60000 ? snapshot : null;
}
export function useInfrastructure(refresh = 0) {
  const [snapshot, setSnapshot] = useState<InfrastructureSnapshot | null>(null);
  const [failed, setFailed] = useState(false);
  const [now, setNow] = useState(0);
  useEffect(() => {
    let stopped = false; let timer: ReturnType<typeof setTimeout>; let pending: AbortController | undefined;
    const clock = setInterval(() => setNow(Date.now()), 5000);
    async function load() {
      pending = new AbortController(); const timeout = setTimeout(() => pending?.abort(), 30000);
      try {
        const response = await fetch("/api/control-plane/infrastructure", { cache: "no-store", signal: pending.signal });
        if (!response.ok) throw new Error("인프라 조회 실패");
        const value: InfrastructureSnapshot = await response.json();
        if (!Array.isArray(value.nodes) || !Array.isArray(value.sensors)) throw new Error("잘못된 인프라 응답");
        if (!stopped) { setSnapshot(value); setFailed(false); setNow(Date.now()); }
      } catch { if (!stopped) { setSnapshot(null); setFailed(true); } }
      finally { clearTimeout(timeout); if (!stopped) timer = setTimeout(load, 10000); }
    }
    void load(); return () => { stopped = true; pending?.abort(); clearTimeout(timer); clearInterval(clock); };
  }, [refresh]);
  return { snapshot: currentInfrastructure(snapshot, now), failed };
}
