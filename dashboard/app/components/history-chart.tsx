"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import type { ECharts, HistoryChartData } from "../../lib/history-echarts";

type Zoom = { start: number; end: number };
export function HistoryChart({ data, label, caption }: { data: HistoryChartData; label: string; caption: ReactNode }) {
  const container = useRef<HTMLDivElement>(null), instance = useRef<ECharts | null>(null), current = useRef<HistoryChartData | null>(null);
  const [ready, setReady] = useState(false), [error, setError] = useState("");
  const [zoom, setZoom] = useState<Zoom>({ start: 0, end: 100 });
  useEffect(() => {
    current.current = data;
    if (instance.current) void import("../../lib/history-echarts").then(engine => { if (instance.current) engine.updateChart(instance.current, data); });
  }, [data]);
  useEffect(() => {
    let stopped = false, chart: ECharts | undefined, observer: ResizeObserver | undefined;
    const element = container.current!;
    void import("../../lib/history-echarts").then(engine => {
      if (stopped) return;
      const resize = () => {
        if (stopped || !element.clientWidth || !element.clientHeight) return;
        if (chart) { chart.resize(); return; }
        chart = engine.init(element, undefined, { renderer: "canvas" }); instance.current = chart;
        engine.configureChart(chart, element);
        if (current.current) engine.updateChart(chart, current.current);
        chart.on("datazoom", (event: unknown) => {
          if (!event || typeof event !== "object") return;
          const change = "batch" in event && Array.isArray(event.batch) ? event.batch[0] : event;
          if (!change || typeof change !== "object") return;
          if (typeof change.start === "number" && typeof change.end === "number") setZoom({ start: change.start, end: change.end });
        });
        setReady(true);
      };
      observer = new ResizeObserver(resize); observer.observe(element); resize();
    }).catch(() => { if (!stopped) setError("그래프를 표시하지 못했습니다. 기록 표를 확인하세요."); });
    return () => { stopped = true; observer?.disconnect(); instance.current = null; chart?.dispose(); };
  }, []);
  function show(start: number, end: number) { instance.current?.dispatchAction({ type: "dataZoom", start, end }); }
  function scale(factor: number) { const span = Math.min(100, Math.max(1, (zoom.end - zoom.start) * factor)), start = Math.max(0, Math.min(100 - span, (zoom.start + zoom.end - span) / 2)); show(start, start + span); }
  return <figure className="continuous-history-chart interactive-history-chart">
    <div className="toolbar"><button type="button" disabled={!ready || zoom.end - zoom.start <= 1} onClick={() => scale(.5)}>확대</button><button type="button" disabled={!ready || zoom.end - zoom.start >= 100} onClick={() => scale(2)}>축소</button><button type="button" disabled={!ready} onClick={() => show(0, 100)}>전체 구간</button><span role="status" aria-label="그래프 표시 구간" className="muted">전체 범위의 {Math.round(zoom.end - zoom.start)}%</span></div>
    <div ref={container} role="img" aria-label={label} aria-busy={!ready} className="history-chart-canvas" />
    {!ready && !error && <p role="status">그래프 준비 중…</p>}{error && <p role="alert" className="error">{error}</p>}
    <figcaption>{caption}<span className="block">마우스·터치로 값을 확인하고 아래 슬라이더로 구간을 확대합니다. 확대는 조회한 기록 안에서 적용됩니다.</span></figcaption>
  </figure>;
}
