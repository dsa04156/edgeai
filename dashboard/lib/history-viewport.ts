// Stock-style exploration of sensor history: the chart axis spans the raw retention period, the user moves a viewport inside it,
// and only the viewport plus a pan margin is fetched from the existing chart endpoint (maximum 31-day period per request).
export const SECOND = 1000, HOUR = 3600 * SECOND, DAY = 24 * HOUR;
export const MAX_PERIOD = 31 * DAY, MIN_SPAN = 10 * SECOND;
export const QUICK_RANGES = [{ label: "1시간", span: HOUR }, { label: "6시간", span: 6 * HOUR }, { label: "1일", span: DAY }, { label: "7일", span: 7 * DAY }, { label: "30일", span: 30 * DAY }];
export type TimeRange = { from: number; to: number };
export type LoadedWindow = TimeRange & { selected: boolean; budget?: number };

export const historyBounds = (now: number, retentionDays: number): TimeRange => ({ from: now - Math.max(1, retentionDays) * DAY, to: now });
export const maxSpan = (bounds: TimeRange) => Math.max(MIN_SPAN, Math.min(MAX_PERIOD, bounds.to - bounds.from));
export const quickRanges = (bounds: TimeRange) => QUICK_RANGES.filter(range => range.span <= maxSpan(bounds));

/** Keeps the viewport width within the zoom limits and shifts it, without resizing, back inside the retained period. */
export function clampViewport(view: TimeRange, bounds: TimeRange): TimeRange {
  const width = Math.min(maxSpan(bounds), Math.max(MIN_SPAN, view.to - view.from));
  const from = Math.max(bounds.from, Math.min(view.from, bounds.to - width));
  return { from, to: from + width };
}
export const followViewport = (width: number, bounds: TimeRange) => clampViewport({ from: bounds.to - width, to: bounds.to }, bounds);

/** The viewport plus up to half a width on each side, so a drag reveals already-loaded data before the next request settles. */
export function fetchWindow(view: TimeRange, bounds: TimeRange): TimeRange {
  const width = view.to - view.from, pad = Math.max(0, Math.min(width / 2, (MAX_PERIOD - width) / 2));
  return { from: Math.max(bounds.from, view.from - pad), to: Math.min(bounds.to, view.to + pad) };
}

/** A settled viewport needs data when it leaves the loaded window, when the point budget (chart width) changed, or when a selected (thinned) window is zoomed in far enough for finer originals. */
export function needsFetch(loaded: LoadedWindow | null, view: TimeRange, bounds: TimeRange, budget?: number) {
  if (!loaded || view.from < loaded.from || view.to > loaded.to) return true;
  if (budget !== undefined && loaded.budget !== undefined && budget !== loaded.budget) return true;
  const planned = fetchWindow(view, bounds);
  return loaded.selected && (planned.to - planned.from) * 2 <= loaded.to - loaded.from;
}

// Point budget sent to the chart endpoint (server hard cap 2,000): four originals per plot pixel column as in M4 selection,
// in steps of 250 so that small resizes keep the loaded data.
export const MIN_POINTS = 500, MAX_POINTS = 2000, DEFAULT_POINTS = 1000, PLOT_MARGIN = 102;
export const pointBudget = (canvasWidth: number) =>
  canvasWidth > 0 ? Math.min(MAX_POINTS, Math.max(MIN_POINTS, Math.ceil(((canvasWidth - PLOT_MARGIN) * 4) / 250) * 250)) : DEFAULT_POINTS;

// Live follow: after one bounded load, each poll fetches only the tail since the loaded end. LIVE_OVERLAP re-reads recent
// originals that arrive late; older late arrivals appear at the next full rebase (budget/span/sensor change, a gap longer
// than REBASE_GAP, or every REBASE_EVERY).
export const LIVE_OVERLAP = 30 * SECOND, REBASE_GAP = 10 * 60 * SECOND, REBASE_EVERY = 5 * 60 * SECOND;
export type ChartSlice = {
  from: string; to: string; displayMode: "ORIGINALS" | "SELECTED_ORIGINALS"; totalObservations: number;
  items: { observedAt: string; value: number | boolean }[];
  selection?: { method: "FIRST_MIN_MAX_LAST"; windowSeconds: number; bucketStart: string; counts: number[] };
};
/** Epoch-aligned selection windows touched by [from, to), as the server counts them. */
export const windowCount = (from: number, to: number, windowMs: number) => Math.floor((to - 1) / windowMs) - Math.floor(from / windowMs) + 1;
/** Tail start: LIVE_OVERLAP before the loaded end, moved back to its selection window start so that whole windows are replaced. */
export function tailStart(loaded: TimeRange, windowMs: number) {
  const start = loaded.to - LIVE_OVERLAP;
  return Math.max(loaded.from, windowMs ? Math.floor(start / windowMs) * windowMs : start);
}
/**
 * Replaces everything from `tail.from` with the tail (same selection window) and drops what precedes `keepFrom`; a selected
 * response keeps whole windows, so the result's per-window counts and total stay exact and no stale extremum survives.
 */
export function mergeTail<T extends ChartSlice>(base: T, tail: T, keepFrom: number): T {
  const windowMs = (base.selection?.windowSeconds ?? 0) * SECOND, cut = Date.parse(tail.from);
  const from = Math.min(cut, Math.max(Date.parse(base.from), windowMs ? Math.floor(keepFrom / windowMs) * windowMs : keepFrom));
  const items = [...base.items.filter(item => { const at = Date.parse(item.observedAt); return at >= from && at < cut; }), ...tail.items];
  if (!windowMs || !base.selection || !tail.selection) return { ...tail, from: new Date(from).toISOString(), items, totalObservations: items.length };
  const counts = new Map<number, number>(), first = Math.floor(from / windowMs);
  for (const selection of [base.selection, tail.selection]) selection.counts.forEach((count, index) => counts.set(Date.parse(selection.bucketStart) / windowMs + index, count));
  const last = Date.parse(tail.selection.bucketStart) / windowMs + tail.selection.counts.length - 1;
  const merged = Array.from({ length: Math.max(0, last - first + 1) }, (_, index) => counts.get(first + index) ?? 0);
  return { ...tail, from: new Date(from).toISOString(), items, totalObservations: merged.reduce((sum, count) => sum + count, 0),
    selection: { ...tail.selection, bucketStart: new Date(first * windowMs).toISOString(), counts: merged } };
}

/** Breaks the line where the measurement spacing jumps far above its usual interval instead of drawing values that were never observed. */
export function markGaps<T extends { at: number }>(points: T[], selectionMs = 0): (T & { breakBefore: boolean })[] {
  const deltas = points.slice(1).map((point, index) => point.at - points[index].at).filter(delta => delta > 0).sort((a, b) => a - b);
  const typical = deltas.length >= 2 ? deltas[Math.floor(deltas.length / 2)] : Infinity;
  const limit = Math.max(typical * 10, selectionMs * 3, SECOND);
  return points.map((point, index) => ({ ...point, breakBefore: index > 0 && point.at - points[index - 1].at > limit }));
}

export const covers = (outer: TimeRange, inner: TimeRange) => outer.from <= inner.from && outer.to >= inner.to;

export function sameRange(a: TimeRange | null | undefined, b: TimeRange | null | undefined, tolerance = 1) {
  return !!a && !!b && Math.abs(a.from - b.from) <= tolerance && Math.abs(a.to - b.to) <= tolerance;
}

/** Runs only the last call after `wait` ms of quiet; `cancel` drops a pending call. */
export function debounce<A extends unknown[]>(action: (...args: A) => void, wait: number) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const run = (...args: A) => {
    if (timer !== undefined) clearTimeout(timer);
    timer = setTimeout(() => { timer = undefined; action(...args); }, wait);
  };
  return Object.assign(run, { cancel: () => { if (timer !== undefined) clearTimeout(timer); timer = undefined; } });
}

/** Latest-request-wins guard: starting a request aborts the previous one, and stale completions report `current() === false`. */
export function latestRequest() {
  let generation = 0, controller: AbortController | undefined;
  return {
    start() {
      controller?.abort();
      const own = ++generation, signal = (controller = new AbortController()).signal;
      return { signal, current: () => own === generation };
    },
    cancel() { controller?.abort(); controller = undefined; generation++; },
  };
}
