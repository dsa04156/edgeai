"use client";

import { useEffect, useState } from "react";
import type { components } from "./api-schema";

export type NodeMetricsSnapshot = components["schemas"]["NodeMetricsSnapshot"];
export type NodeMetric = components["schemas"]["NodeMetric"];

export function useNodeMetrics(enabled = true, refresh = 0) {
  const [snapshot, setSnapshot] = useState<NodeMetricsSnapshot | null>(null);
  const [failed, setFailed] = useState(false);
  const [now, setNow] = useState(0);
  useEffect(() => {
    if (!enabled) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    let pending: AbortController | undefined;
    const clock = setInterval(() => setNow(Date.now()), 5000);
    async function load() {
      const controller = new AbortController();
      pending = controller;
      const timeout = setTimeout(() => controller.abort(), 6000);
      try {
        const response = await fetch("/api/control-plane/node-metrics", { cache: "no-store", signal: controller.signal });
        if (!response.ok) throw new Error("메트릭 조회 실패");
        const value: NodeMetricsSnapshot = await response.json();
        if (!Array.isArray(value.items) || !["AVAILABLE", "DISABLED", "UNAVAILABLE"].includes(value.status)) throw new Error("잘못된 메트릭 응답");
        if (!stopped) { setSnapshot(value); setFailed(false); setNow(Date.now()); }
      } catch {
        if (!stopped) { setSnapshot(null); setFailed(true); setNow(Date.now()); }
      } finally {
        clearTimeout(timeout);
        if (!stopped) timer = setTimeout(load, 5000);
      }
    }
    void load();
    return () => { stopped = true; pending?.abort(); clearTimeout(timer); clearInterval(clock); };
  }, [enabled, refresh]);
  return { snapshot, failed, now };
}

export type NodeMetricsState = ReturnType<typeof useNodeMetrics>;
export function isCurrent(metric: Pick<NodeMetric, "observedAt" | "fresh">, state: NodeMetricsState) {
  const observed = Date.parse(metric.observedAt);
  return state.snapshot?.status === "AVAILABLE" && !state.failed && metric.fresh && Number.isFinite(observed)
    && observed <= state.now + 5000 && state.now - observed <= state.snapshot.maxAgeSeconds * 1000;
}
