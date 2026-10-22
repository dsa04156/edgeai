import { ref } from "vue";
import type { Schema } from "./native-api";
import {
  DEFAULT_POINTS,
  HOUR,
  LIVE_OVERLAP,
  REBASE_EVERY,
  REBASE_GAP,
  SECOND,
  clampViewport,
  covers,
  debounce,
  fetchWindow,
  followViewport,
  historyBounds,
  latestRequest,
  mergeTail,
  needsFetch,
  sameRange,
  tailStart,
  windowCount,
  type LoadedWindow,
  type TimeRange,
} from "../lib/history-viewport.ts";

type Sensor = { deviceUid: string; property: string };
export type SensorHistoryOptions = {
  sensor: () => Sensor | undefined;
  retentionDays: () => number;
  read: <T>(path: string, signal: AbortSignal) => Promise<T>;
  /** Chart point budget for the current chart width (server range 200–2000). */
  budget?: () => number;
  now?: () => number;
  settleMs?: number;
};
const iso = (range: TimeRange) => ({
  from: new Date(range.from).toISOString(),
  to: new Date(range.to).toISOString(),
});
const failure = (cause: unknown, live: boolean) =>
  (live ? "자동 갱신 실패 · 마지막 조회 기록을 표시합니다. " : "") +
  (cause instanceof Error ? cause.message : "원본 조회 실패");
const sensorPath = (sensor: Sensor) =>
  `data-store/sensors/${sensor.deviceUid}/${encodeURIComponent(sensor.property)}`;
/**
 * Viewport-driven sensor history for the data store page. The chart and the record table are independent requests,
 * each keeping only its latest call and its own error; a chart response is applied only while it still covers the
 * viewport the user is looking at, so an abandoned pan can never replace visible data. While following live, the chart is
 * loaded once and each poll then fetches only its tail (the selection windows from the loaded end minus a late-arrival
 * overlap), replacing those windows; the table likewise adds only newer originals.
 */
export function useSensorHistory(options: SensorHistoryOptions) {
  const now = options.now ?? Date.now;
  const follow = ref(true),
    viewport = ref<TimeRange>(followViewport(HOUR, historyBounds(now(), 1))),
    bounds = ref<TimeRange>(historyBounds(now(), 1)),
    loaded = ref<LoadedWindow | null>(null),
    chart = ref<Schema["SensorPeriodChart"] | null>(null),
    chartBusy = ref(false),
    chartError = ref(""),
    records = ref<Schema["SensorOriginalHistory"]["items"]>([]),
    recordsBusy = ref(false),
    recordsError = ref(""),
    recordPeriod = ref<TimeRange | null>(null),
    nextCursor = ref<string | undefined>(),
    olderRecords = ref(false),
    lastUpdated = ref("");
  const chartRequest = latestRequest(),
    recordsRequest = latestRequest();
  const budget = () => options.budget?.() ?? DEFAULT_POINTS;
  // The follow span and time of the last full load decide whether a poll may append a tail or must rebase.
  let loadedSpan = 0,
    rebasedAt = 0;
  function cancelChart() {
    chartRequest.cancel();
    chartBusy.value = false;
  }
  async function loadChart(live = false, rebase = false): Promise<void> {
    const sensor = options.sensor();
    if (!sensor) return;
    const at = now(),
      nextBounds = historyBounds(at, options.retentionDays()),
      points = budget();
    bounds.value = nextBounds;
    viewport.value = follow.value
      ? followViewport(viewport.value.to - viewport.value.from, nextBounds)
      : clampViewport(viewport.value, nextBounds);
    const window = fetchWindow(viewport.value, nextBounds),
      span = viewport.value.to - viewport.value.from,
      base = chart.value,
      held = loaded.value,
      windowMs = (base?.selection?.windowSeconds ?? 0) * SECOND,
      tailFrom = held ? tailStart(held, windowMs) : 0;
    const tail =
      !rebase &&
      live &&
      follow.value &&
      // An API without point budgets (pointBudget absent) answers tails with whole-range data: reread instead.
      base?.pointBudget !== undefined &&
      !!held &&
      held.budget === points &&
      loadedSpan === span &&
      at - held.to <= REBASE_GAP &&
      at - rebasedAt < REBASE_EVERY &&
      (!windowMs || windowCount(tailFrom, window.to, windowMs) * 4 <= points);
    const range = tail ? { from: tailFrom, to: window.to } : window,
      call = chartRequest.start();
    chartBusy.value = true;
    try {
      const plot = await options.read<Schema["SensorPeriodChart"]>(
        `${sensorPath(sensor)}/chart?${new URLSearchParams({
          ...iso(range),
          points: String(points),
          ...(tail && windowMs ? { window: String(windowMs / SECOND) } : {}),
        })}`,
        call.signal,
      );
      if (!call.current()) return;
      if (tail) {
        if (chart.value !== base || !base) return;
        const merged =
          (plot.displayMode === "SELECTED_ORIGINALS") === !!windowMs
            ? mergeTail(base, plot, window.from)
            : null;
        // A sparse sensor whose tail no longer fits as originals switches to selection through one full load.
        if (!merged || merged.items.length > points) {
          chartRequest.cancel();
          return loadChart(live, true);
        }
        chart.value = merged;
        loaded.value = {
          from: Date.parse(merged.from),
          to: Date.parse(merged.to),
          selected: merged.displayMode === "SELECTED_ORIGINALS",
          budget: points,
        };
      } else {
        const received = {
          from: Date.parse(plot.from),
          to: Date.parse(plot.to),
          selected: plot.displayMode === "SELECTED_ORIGINALS",
          budget: points,
        };
        // The user moved on while this was in flight: keep data that still covers the current view.
        if (
          loaded.value &&
          covers(loaded.value, viewport.value) &&
          !covers(received, viewport.value)
        )
          return;
        chart.value = plot;
        loaded.value = received;
        loadedSpan = span;
        rebasedAt = at;
      }
      chartError.value = "";
      lastUpdated.value = new Date(at).toISOString();
    } catch (cause) {
      if (call.current()) chartError.value = failure(cause, live);
    } finally {
      if (call.current()) chartBusy.value = false;
    }
  }
  async function loadRecords(mode: "reset" | "more" | "live"): Promise<void> {
    const sensor = options.sensor();
    // Following live with a current table: read only the period from its newest row minus the late-arrival overlap.
    if (
      mode === "live" &&
      records.value.length &&
      recordPeriod.value &&
      !olderRecords.value &&
      sensor
    ) {
      const shown = records.value,
        held = recordPeriod.value,
        newest = Date.parse(shown[0].observedAt),
        period = {
          from: Math.max(held.from, newest - LIVE_OVERLAP),
          to: viewport.value.to,
        },
        call = recordsRequest.start();
      try {
        const history = await options.read<Schema["SensorOriginalHistory"]>(
          `${sensorPath(sensor)}/history?${new URLSearchParams({ ...iso(period), limit: "1000", order: "desc" })}`,
          call.signal,
        );
        if (!call.current() || records.value !== shown) return;
        // The delta is the complete, authoritative content of its period (the history API excludes hidden originals),
        // so it replaces every shown row from period.from on: a legacy row reconciled to its qualified original, or an
        // original no longer stored, disappears. Older rows are kept untouched, so the cursor for older rows stays valid.
        const merged = [
          ...history.items,
          ...shown.filter((row) => Date.parse(row.observedAt) < period.from),
        ];
        // A truncated delta, a table that would exceed the 2,000-row limit, or rows that left the followed view are
        // replaced by one full read.
        if (
          history.truncated ||
          merged.length > 2000 ||
          (merged.length &&
            Date.parse(merged[merged.length - 1].observedAt) <
              viewport.value.from)
        ) {
          await loadRecords("reset");
          return;
        }
        // A delta that covered the whole table period found everything there is: nothing older remains to page.
        if (period.from === held.from) nextCursor.value = undefined;
        records.value = merged;
        recordPeriod.value = { from: held.from, to: period.to };
        recordsError.value = "";
      } catch (cause) {
        if (call.current()) recordsError.value = failure(cause, true);
      }
      return;
    }
    if (
      !sensor ||
      (mode === "more" &&
        (!nextCursor.value ||
          records.value.length >= 2000 ||
          !recordPeriod.value))
    )
      return;
    const period =
        mode === "more" && recordPeriod.value
          ? recordPeriod.value
          : { ...viewport.value },
      call = recordsRequest.start();
    if (mode !== "live") recordsBusy.value = true;
    const params = new URLSearchParams({
      ...iso(period),
      limit: String(
        mode === "more" ? Math.min(1000, 2000 - records.value.length) : 1000,
      ),
      order: "desc",
      ...(mode === "more" && nextCursor.value
        ? { cursor: nextCursor.value }
        : {}),
    });
    try {
      const history = await options.read<Schema["SensorOriginalHistory"]>(
        `${sensorPath(sensor)}/history?${params}`,
        call.signal,
      );
      if (!call.current()) return;
      records.value =
        mode === "more" ? [...records.value, ...history.items] : history.items;
      nextCursor.value = history.nextCursor;
      olderRecords.value = mode === "more";
      recordPeriod.value = period;
      recordsError.value = "";
    } catch (cause) {
      if (call.current()) recordsError.value = failure(cause, mode === "live");
    } finally {
      if (call.current()) recordsBusy.value = false;
    }
  }
  async function loadAll(live = false) {
    await Promise.all([
      loadChart(live),
      olderRecords.value && live ? null : loadRecords(live ? "live" : "reset"),
    ]);
  }
  // A settled pan/zoom fetches only when the viewport leaves the loaded window (or needs finer originals);
  // otherwise a request still running for an abandoned viewport is dropped. The table follows unless paged.
  function sync() {
    if (needsFetch(loaded.value, viewport.value, bounds.value, budget()))
      void loadChart();
    else if (chartBusy.value) cancelChart();
    if (!olderRecords.value && !sameRange(recordPeriod.value, viewport.value))
      void loadRecords("reset");
  }
  const settle = debounce(sync, options.settleMs ?? 300);
  /** A pan/zoom gesture started: stop following at once, so that no live reply moves the chart while the gesture runs. */
  function pause() {
    settle.cancel();
    if (!follow.value) return;
    follow.value = false;
    cancelChart();
  }
  /** The chart reports a viewport only once its gesture has settled, so the data is synchronized without a second delay. */
  function explore(range: TimeRange) {
    settle.cancel();
    follow.value = false;
    viewport.value = clampViewport(range, bounds.value);
    sync();
  }
  function startFollowing(width: number) {
    settle.cancel();
    follow.value = true;
    olderRecords.value = false;
    viewport.value = followViewport(width, bounds.value);
    void loadAll();
  }
  /** The chart width (point budget) changed: refetch once it settles if the loaded resolution no longer matches. */
  const resize = () => settle();
  function clear() {
    settle.cancel();
    cancelChart();
    recordsRequest.cancel();
    recordsBusy.value = false;
    chartError.value = "";
    recordsError.value = "";
    lastUpdated.value = "";
    chart.value = null;
    loaded.value = null;
    records.value = [];
    recordPeriod.value = null;
    nextCursor.value = undefined;
    olderRecords.value = false;
    follow.value = true;
  }
  return {
    follow,
    viewport,
    bounds,
    loaded,
    chart,
    chartBusy,
    chartError,
    records,
    recordsBusy,
    recordsError,
    recordPeriod,
    nextCursor,
    olderRecords,
    lastUpdated,
    loadAll,
    loadChart,
    loadRecords,
    pause,
    explore,
    startFollowing,
    resize,
    clear,
  };
}
