import assert from "node:assert/strict";
import { test } from "node:test";
import { useSensorHistory } from "../kestra-native/sensor-history.ts";
import { HOUR } from "../lib/history-viewport.ts";

// Deferred responses: each read stays pending until the test resolves or rejects it, and aborting rejects it like fetch.
type Call = { path: string; signal: AbortSignal; done: boolean; resolve: (value: unknown) => void; reject: (cause: unknown) => void };
const now = Date.parse("2026-10-21T12:00:00Z");
const tick = (ms = 0) => new Promise(resolve => setTimeout(resolve, ms));
function harness() {
  const calls: Call[] = [];
  const read = <T,>(path: string, signal: AbortSignal) => new Promise<T>((resolve, reject) => {
    const call: Call = { path, signal, done: false, resolve: value => { call.done = true; resolve(value as T); }, reject: cause => { call.done = true; reject(cause); } };
    calls.push(call);
    signal.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
  });
  const history = useSensorHistory({ sensor: () => ({ deviceUid: "00000000-0000-4000-8000-00000000000a", property: "temperature" }), retentionDays: () => 30, read, now: () => now, settleMs: 5 });
  const pending = (kind: "chart" | "history") => calls.filter(call => call.path.includes(`/${kind}?`) && !call.signal.aborted && !call.done);
  return { history, calls, pending };
}
const period = (call: Call) => { const query = new URL(call.path, "http://x").searchParams; return { from: Date.parse(query.get("from")!), to: Date.parse(query.get("to")!) }; };
const chartReply = (call: Call, value: number) => { const { from, to } = period(call); call.resolve({ deviceUid: "u", property: "temperature", from: new Date(from).toISOString(), to: new Date(to).toISOString(), retentionDays: 30, truncated: false, displayMode: "ORIGINALS", totalObservations: 1, items: [{ observedAt: new Date((from + to) / 2).toISOString(), value }] }); };
const historyReply = (call: Call, value = 1) => call.resolve({ items: [{ observedAt: new Date(now).toISOString(), value, unit: "°C" }], truncated: false });
const visible = (h: ReturnType<typeof harness>["history"]) => (h.chart.value?.items ?? []).filter(item => { const at = Date.parse(item.observedAt); return at >= h.viewport.value.from && at <= h.viewport.value.to; });

async function loadedAtA() {
  const h = harness();
  const all = h.history.loadAll();
  chartReply(h.pending("chart")[0], 10);
  historyReply(h.pending("history")[0]);
  await all;
  return h;
}
const viewA = { from: now - 70 * 60e3, to: now - 10 * 60e3 }, viewB = { from: now - 8 * HOUR, to: now - 7 * HOUR };

test("A loaded → B pending → back to cached A: B is aborted and its late reply cannot replace A's data", async () => {
  const h = await loadedAtA();
  h.history.explore(viewB);
  await tick(20);
  const b = h.pending("chart");
  assert.equal(b.length, 1);
  assert.equal(h.history.chartBusy.value, true);
  h.history.explore(viewA);
  await tick(20);
  assert.equal(b[0].signal.aborted, true, "settling on cached A drops the abandoned B request");
  assert.equal(h.history.chartBusy.value, false);
  chartReply(b[0], 99);
  await tick();
  assert.deepEqual(visible(h.history).map(item => item.value), [10]);
  assert.equal(h.pending("chart").length, 0);
});

test("B answering before the return to A settles is ignored, so A's coverage stays visible", async () => {
  const h = await loadedAtA();
  h.history.explore(viewB);
  await tick(20);
  const [b] = h.pending("chart");
  h.history.explore(viewA);
  chartReply(b, 99);
  await tick();
  assert.deepEqual(visible(h.history).map(item => item.value), [10], "reply for B while viewing A keeps A");
  await tick(20);
  assert.equal(h.pending("chart").length, 0, "A is still covered, so no refetch is needed");
  assert.deepEqual(visible(h.history).map(item => item.value), [10]);
});

test("a reply that covers the settled viewport is applied", async () => {
  const h = await loadedAtA();
  h.history.explore(viewB);
  await tick(20);
  chartReply(h.pending("chart")[0], 42);
  await tick();
  assert.deepEqual(visible(h.history).map(item => item.value), [42]);
  assert.equal(h.history.chartBusy.value, false);
});

test("table and chart errors are owned separately in both response orders", async () => {
  for (const historyFirst of [true, false]) {
    const h = harness(), all = h.history.loadAll();
    const [chart] = h.pending("chart"), [history] = h.pending("history");
    if (historyFirst) { history.reject(new Error("history endpoint unavailable")); await tick(); chartReply(chart, 1); }
    else { chartReply(chart, 1); await tick(); history.reject(new Error("history endpoint unavailable")); }
    await all;
    assert.equal(h.history.recordsError.value, "history endpoint unavailable", `historyFirst=${historyFirst}`);
    assert.equal(h.history.chartError.value, "");
    assert.equal(h.history.records.value.length, 0);
  }
  const h = harness(), all = h.history.loadAll();
  h.pending("chart")[0].reject(new Error("chart endpoint unavailable"));
  historyReply(h.pending("history")[0]);
  await all;
  assert.equal(h.history.chartError.value, "chart endpoint unavailable");
  assert.equal(h.history.recordsError.value, "");
});

test("a records-only retry clears only its own error", async () => {
  const h = harness(), all = h.history.loadAll();
  h.pending("chart")[0].reject(new Error("chart endpoint unavailable"));
  h.pending("history")[0].reject(new Error("history endpoint unavailable"));
  await all;
  const retry = h.history.loadRecords("reset");
  historyReply(h.pending("history")[0], 7);
  await retry;
  assert.equal(h.history.recordsError.value, "");
  assert.equal(h.history.chartError.value, "chart endpoint unavailable");
  assert.equal(h.history.records.value[0].value, 7);
});

test("live poll failures keep the last data and say so, per endpoint", async () => {
  const h = await loadedAtA(), poll = h.history.loadAll(true);
  h.pending("chart")[0].reject(new Error("timeout"));
  historyReply(h.pending("history")[0], 3);
  await poll;
  assert.match(h.history.chartError.value, /^자동 갱신 실패.*timeout$/);
  assert.equal(h.history.recordsError.value, "");
  assert.equal(h.history.chart.value?.items[0].value, 10);
});

test("clearing for a sensor change aborts both requests and drops their late replies", async () => {
  const h = harness();
  const all = h.history.loadAll();
  const [chart] = h.pending("chart"), [history] = h.pending("history");
  h.history.clear();
  assert.equal(chart.signal.aborted && history.signal.aborted, true);
  await all;
  assert.equal(h.history.chart.value, null);
  assert.equal(h.history.records.value.length, 0);
  assert.equal(h.history.chartBusy.value || h.history.recordsBusy.value, false);
  assert.equal(h.history.chartError.value + h.history.recordsError.value, "");
});

test("a settled viewport from the chart is synchronized at once, without a second debounce", async () => {
  const h = await loadedAtA();
  h.history.explore(viewB);
  assert.equal(h.pending("chart").length, 1, "chart request starts synchronously");
  assert.deepEqual(period(h.pending("history")[0]), viewB, "table follows the settled viewport");
  assert.equal(h.history.follow.value, false);
});

test("a gesture start stops following and drops the in-flight live reply, so the chart cannot move back", async () => {
  const h = await loadedAtA();
  const followed = { ...h.history.viewport.value };
  const poll = h.history.loadAll(true);
  const [live] = h.pending("chart");
  h.history.pause();
  assert.equal(h.history.follow.value, false);
  assert.equal(live.signal.aborted, true);
  assert.equal(h.history.chartBusy.value, false);
  chartReply(live, 99);
  historyReply(h.pending("history")[0]);
  await poll;
  assert.deepEqual(visible(h.history).map(item => item.value), [10], "the aborted live reply is not applied");
  assert.deepEqual(h.history.viewport.value, followed);
  h.history.explore(viewA);
  assert.equal(h.pending("chart").length, 0, "the settled viewport is still covered by the loaded window");
});
