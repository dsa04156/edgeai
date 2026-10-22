import { init, use as register, type ECharts } from "echarts/core";
import { LineChart, ScatterChart } from "echarts/charts";
import { DataZoomComponent, GridComponent, MarkAreaComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import { formatMeasurementValue } from "./measurement-value";

register([LineChart, ScatterChart, DataZoomComponent, GridComponent, MarkAreaComponent, TooltipComponent, CanvasRenderer]);
export { init };
export type { ECharts };
export type HistoryPoint = { at: number; value: number; unit?: string; boolean?: boolean; breakBefore?: boolean };
export type HistoryChartData = { name: string; from: number; to: number; points: HistoryPoint[]; connected?: boolean; showSymbol?: boolean; gaps?: { from: number; to: number }[] };
const formatAxisValue = (value: number, boolean = false) => boolean ? value ? "참" : "거짓" : Number(value.toPrecision(8)).toLocaleString(undefined, { maximumSignificantDigits: 8 });
/** Local date-time with seconds, plus milliseconds when the original measurement carries them. */
export const formatInstant = (at: number) => {
  const date = new Date(at), text = date.toLocaleString("ko-KR", { year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });
  return date.getMilliseconds() ? `${text}.${String(date.getMilliseconds()).padStart(3, "0")}` : text;
};
const escape = (text: string) => text.replace(/[&<>"']/g, character => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[character]!));

function tooltip(params: unknown) {
  const rows = (Array.isArray(params) ? params : [params]).flatMap(param => {
    if (!param || typeof param !== "object" || !("data" in param)) return [];
    const data = param.data as { value?: unknown; unit?: string; boolean?: boolean };
    if (!Array.isArray(data.value) || !Number.isFinite(data.value[0]) || !Number.isFinite(data.value[1])) return [];
    return [`${escape(formatInstant(data.value[0]))}<br/><strong>${escape(formatMeasurementValue(data.boolean ? !!data.value[1] : data.value[1]))}${data.unit ? ` ${escape(data.unit)}` : ""}</strong>`];
  });
  return `<div role="tooltip">${rows.join("<br/>")}</div>`;
}

export type ChartViewport = { from: number; to: number; minSpan: number; maxSpan: number };

export function configureChart(chart: ECharts, element: HTMLElement, explore = false) {
  const style = getComputedStyle(element);
  const inspector = !!element.closest(".history-chart-inspector");
  const token = (name: string, fallback: string) => style.getPropertyValue(name).trim() || fallback;
  const ink = token("--ink", "#202d3e"), line = token("--line", "#dfe8f3"), signal = token("--signal", "#1479ed");
  const tooltipPosition = (point: number[], _params: unknown, _dom: unknown, _rect: unknown, size: { contentSize: number[]; viewSize: number[] }) => {
    const chart = element.getBoundingClientRect(), body = element.closest(".registration-dialog-body")?.getBoundingClientRect();
    const top = Math.max(0, (body?.top ?? 0) - chart.top), bottom = Math.min(size.viewSize[1], (body?.bottom ?? window.innerHeight) - chart.top);
    return [Math.max(8, Math.min(point[0] + 12, size.viewSize[0] - size.contentSize[0] - 8)), Math.max(top + 8, Math.min(point[1] - size.contentSize[1] - 12, bottom - size.contentSize[1] - 8))];
  };
  chart.setOption({
    animation: false, color: [signal], textStyle: { fontFamily: style.fontFamily, color: ink },
    grid: inspector ? { left: 68, right: 16, top: 16, bottom: explore ? 32 : 72 } : { left: 78, right: 24, top: 24, bottom: explore ? 40 : 88 },
    xAxis: { type: "time", axisLine: { lineStyle: { color: line } }, axisLabel: { color: ink, hideOverlap: true } },
    yAxis: { type: "value", scale: true, splitNumber: inspector ? 4 : undefined, axisLabel: { color: ink, width: inspector ? 58 : 66, overflow: "truncate", ...(inspector ? { showMinLabel: false, showMaxLabel: false } : {}) }, splitLine: { lineStyle: { color: line } } },
    tooltip: { trigger: "axis", confine: true, position: tooltipPosition, transitionDuration: 0, renderMode: "html", formatter: tooltip, backgroundColor: token("--panel", "#ffffff"), borderColor: line, textStyle: { color: ink }, extraCssText: "box-shadow:none;border-radius:7px;",
      axisPointer: { type: "cross", lineStyle: { color: ink, type: "dashed", opacity: 0.6 }, crossStyle: { color: ink, type: "dashed", opacity: 0.6 }, label: { backgroundColor: ink, color: token("--panel", "#ffffff"), formatter: (pointer: { axisDimension: string; value: number }) => pointer.axisDimension === "x" ? formatInstant(pointer.value) : formatAxisValue(pointer.value) } } },
    dataZoom: [{ id: "inside", type: "inside", xAxisIndex: 0, start: 0, end: 100 }, { id: "slider", type: "slider", show: !explore, xAxisIndex: 0, start: 0, end: 100, bottom: inspector ? 8 : 12, height: inspector ? 20 : 26, borderColor: line, fillerColor: token("--signal-surface", "#eaf3ff"), textStyle: { color: ink } }],
  });
}

/** `view` keeps an explored window by absolute time in the same update as the axis domain, so new data or a wider domain never moves it. */
export function updateChart(chart: ECharts, data: HistoryChartData, view?: ChartViewport) {
  const boolean = data.points.every(point => point.boolean);
  const points = data.points.flatMap((point, index) => {
    const connected = index > 0 && !point.breakBefore || index + 1 < data.points.length && !data.points[index + 1].breakBefore;
    const value = { value: [point.at, point.value], unit: point.unit, boolean: point.boolean, symbol: data.showSymbol === false && connected ? "none" : "circle" };
    return data.connected && point.breakBefore && index ? [{ value: [(data.points[index - 1].at + point.at) / 2, null] }, value] : [value];
  });
  const gapColor = getComputedStyle(chart.getDom()).getPropertyValue("--warning-surface").trim() || "#faf4e3";
  const zoom = (id: string) => ({ id, rangeMode: ["value", "value"], start: null, end: null, startValue: view!.from, endValue: view!.to, minValueSpan: view!.minSpan, maxValueSpan: view!.maxSpan });
  chart.setOption({
    ...(view ? { dataZoom: [zoom("inside"), zoom("slider")] } : {}),
    xAxis: { min: data.from, max: data.to },
    yAxis: { min: boolean ? 0 : (extent: { min: number; max: number }) => extent.min === extent.max ? extent.min - 1 : extent.min, max: boolean ? 1 : (extent: { min: number; max: number }) => extent.min === extent.max ? extent.max + 1 : extent.max, interval: boolean ? 1 : undefined, axisLabel: { formatter: (value: number) => formatAxisValue(value, boolean) } },
    series: [{ id: "observations", name: data.name, type: data.connected ? "line" : "scatter", symbolSize: 6, showSymbol: true, connectNulls: false, data: points, markArea: { silent: true, itemStyle: { color: gapColor }, data: (data.gaps ?? []).map(gap => [{ xAxis: gap.from }, { xAxis: gap.to }]) } }],
  }, { replaceMerge: ["series"] });
}
