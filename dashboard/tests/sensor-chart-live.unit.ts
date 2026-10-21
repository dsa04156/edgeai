import assert from "node:assert/strict";
import { test } from "node:test";
import { useSensorHistory } from "../kestra-native/sensor-history.ts";
import { DAY, HOUR, LIVE_OVERLAP, REBASE_EVERY, SECOND, mergeTail, pointBudget, tailStart, windowCount, type ChartSlice } from "../lib/history-viewport.ts";

// In-memory stand-in for the chart/history endpoints with the server's contract: originals within the budget, otherwise the
// first/min/max/last originals of each epoch-aligned window (ties → earliest) with per-window counts. Each original has an
// arrival time, so late originals and "not yet stored" data can be modelled.
type Original = { at: number; value: number; arrives: number; sequence: number; hidden?: boolean; legacy?: boolean };
const LADDER = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10800, 21600, 43200, 86400];
const iso = (ms: number) => new Date(ms).toISOString();
function serveChart(data: Original[], clock: number, query: URLSearchParams): ChartSlice & { pointBudget: number } {
  const from = Date.parse(query.get("from")!), to = Date.parse(query.get("to")!), points = Number(query.get("points") ?? 1000), forced = query.get("window");
  assert.ok(points >= 200 && points <= 2000, "budget within the server range");
  const rows = data.filter(p => !p.hidden && p.arrives <= clock && p.at >= from && p.at < to).sort((a, b) => a.at - b.at || a.sequence - b.sequence);
  const seconds = forced ? Number(forced) : LADDER.find(s => windowCount(from, to, s * SECOND) * 4 <= points)!;
  assert.ok(windowCount(from, to, seconds * SECOND) * 4 <= points, "the server rejects windows that do not fit");
  const common = { from: iso(from), to: iso(to), pointBudget: points, totalObservations: rows.length };
  const item = (p: Original) => ({ observedAt: iso(p.at), value: p.value });
  if (!forced && rows.length <= points) return { ...common, displayMode: "ORIGINALS", items: rows.map(item) };
  const size = seconds * SECOND, first = Math.floor(from / size), groups = new Map<number, Original[]>();
  for (const p of rows) groups.set(Math.floor(p.at / size), [...(groups.get(Math.floor(p.at / size)) ?? []), p]);
  const items = [...groups.values()].flatMap(g => [...new Set([g[0], g[g.length - 1], g.reduce((m, p) => p.value < m.value ? p : m), g.reduce((m, p) => p.value > m.value ? p : m)])].sort((a, b) => a.at - b.at || a.sequence - b.sequence)).map(item);
  const counts = Array.from({ length: windowCount(from, to, size) }, (_, i) => groups.get(first + i)?.length ?? 0);
  return { ...common, displayMode: "SELECTED_ORIGINALS", items, selection: { method: "FIRST_MIN_MAX_LAST", windowSeconds: seconds, bucketStart: iso(first * size), counts } };
}
function serveHistory(data: Original[], clock: number, query: URLSearchParams) {
  const from = Date.parse(query.get("from")!), to = Date.parse(query.get("to")!), limit = Number(query.get("limit")), cursor = query.get("cursor");
  const rows = data.filter(p => !p.hidden && p.arrives <= clock && p.at >= from && p.at < to && (!cursor || p.sequence < Number(cursor))).sort((a, b) => b.at - a.at || b.sequence - a.sequence);
  const page = rows.slice(0, limit);
  return { items: page.map(p => p.legacy ? { observedAt: iso(p.at), value: p.value, quality: "UNVERIFIED" } : { observedAt: iso(p.at), value: p.value, sequence: p.sequence, sessionId: "s", sourceGeneration: "g", quality: "QUALIFIED" }), truncated: rows.length > limit, ...(rows.length > limit ? { nextCursor: String(page[page.length - 1].sequence) } : {}) };
}

const start = Date.parse("2026-10-21T12:00:03.250Z");
function rig({ spacing = SECOND, back = 2 * HOUR, budget = 2000, failOn, olderApi = false }: { spacing?: number; back?: number; budget?: number; failOn?: (path: string) => boolean; olderApi?: boolean } = {}) {
  let clock = start, sequence = 0;
  const data: Original[] = [];
  const add = (at: number, value: number, arrives = at + 70) => data.push({ at, value, arrives, sequence: ++sequence });
  for (let at = start - back; at < start + 2 * HOUR; at += spacing) add(at, Math.round(20 * Math.sin(at / 9e5) * 1e3) / 1e3);
  const requests: { kind: string; from: number; to: number; window: number | null; points: number | null }[] = [];
  const read = async <T,>(path: string) => {
    const url = new URL(path, "http://x"), kind = url.pathname.split("/").pop()!;
    requests.push({ kind, from: Date.parse(url.searchParams.get("from")!), to: Date.parse(url.searchParams.get("to")!), window: url.searchParams.has("window") ? Number(url.searchParams.get("window")) : null, points: url.searchParams.has("points") ? Number(url.searchParams.get("points")) : null });
    if (failOn?.(path)) throw new Error(`${kind} poll failed`);
    if (kind !== "chart") return serveHistory(data, clock, url.searchParams) as T;
    const { pointBudget: _, ...older } = serveChart(data, clock, url.searchParams);
    return (olderApi ? older : serveChart(data, clock, url.searchParams)) as T;
  };
  const state = { budget };
  const history = useSensorHistory({ sensor: () => ({ deviceUid: "00000000-0000-4000-8000-00000000000a", property: "temperature" }), retentionDays: () => 30, read, now: () => clock, budget: () => state.budget, settleMs: 5 });
  return {
    history, data, requests, add, state,
    advance(ms: number) { clock += ms; },
    get clock() { return clock; },
    /** A fresh full response over the merged range with the merged window: what a whole-window reread would show. */
    reread() { const c = history.chart.value!; return serveChart(data, clock, new URLSearchParams({ from: c.from, to: c.to, points: "2000", ...(c.selection ? { window: String(c.selection.windowSeconds) } : {}) })); },
  };
}
const charts = (r: ReturnType<typeof rig>) => r.requests.filter(q => q.kind === "chart");
const tick = (ms = 0) => new Promise(resolve => setTimeout(resolve, ms));
const sameView = (a: ChartSlice, b: ChartSlice) => { assert.deepEqual(a.items, b.items); assert.equal(a.totalObservations, b.totalObservations); assert.deepEqual(a.selection?.counts, b.selection?.counts); assert.equal(a.selection?.bucketStart, b.selection?.bucketStart); };

test("point budget follows the plot width in 250-point steps between 500 and the 2,000 cap", () => {
  assert.equal(pointBudget(0), 1000);
  assert.equal(pointBudget(358), 1250);
  assert.equal(pointBudget(250), 750);
  assert.equal(pointBudget(1000), 2000);
  assert.equal(pointBudget(1400), 2000);
  assert.equal(pointBudget(110), 500);
});

test("a tail replaces whole aligned windows: counts stay exact and an earlier extremum of a reopened window does not survive", () => {
  const w = 10 * SECOND, base: ChartSlice = { from: iso(0), to: iso(40_000), displayMode: "SELECTED_ORIGINALS", totalObservations: 9,
    items: [{ observedAt: iso(1_000), value: 1 }, { observedAt: iso(25_000), value: 2 }, { observedAt: iso(31_000), value: 50 }, { observedAt: iso(39_000), value: 3 }],
    selection: { method: "FIRST_MIN_MAX_LAST", windowSeconds: 10, bucketStart: iso(0), counts: [3, 0, 2, 4] } };
  assert.equal(tailStart({ from: 20_000, to: 40_000 }, w), 20_000, "never before the loaded start");
  assert.equal(tailStart({ from: 0, to: 64_000 }, w), 30_000);
  const tail: ChartSlice = { from: iso(30_000), to: iso(50_000), displayMode: "SELECTED_ORIGINALS", totalObservations: 6,
    items: [{ observedAt: iso(30_500), value: 4 }, { observedAt: iso(45_000), value: 5 }],
    selection: { method: "FIRST_MIN_MAX_LAST", windowSeconds: 10, bucketStart: iso(30_000), counts: [5, 1] } };
  const merged = mergeTail(base, tail, 12_000);
  assert.deepEqual(merged.items.map(i => i.value), [2, 4, 5], "window 0 evicted, window 3's stale 50/3 replaced");
  assert.deepEqual(merged.selection?.counts, [0, 2, 5, 1]);
  assert.equal(merged.selection?.bucketStart, iso(10_000));
  assert.equal(merged.totalObservations, 8);
  assert.equal(merged.from, iso(10_000));
});

test("live follow loads once, then every poll requests only the tail and the merged view equals a full reread", async () => {
  const r = rig();
  await r.history.loadAll();
  const [initial] = charts(r);
  assert.equal(initial.window, null);
  assert.equal(initial.to - initial.from, 1.5 * HOUR, "bounded initial window: the hour plus a pan margin");
  const window = r.history.chart.value!.selection!.windowSeconds * SECOND;
  let largest = 0;
  for (let poll = 0; poll < 50; poll++) {
    r.advance(5 * SECOND);
    await r.history.loadAll(true);
    largest = Math.max(largest, r.history.chart.value!.items.length);
  }
  const polls = charts(r).slice(1);
  assert.equal(polls.length, 50);
  for (const poll of polls) {
    assert.equal(poll.window, window / SECOND, "tails keep the loaded window");
    assert.ok(poll.to - poll.from <= LIVE_OVERLAP + window + 5 * SECOND, `tail of ${poll.to - poll.from} ms`);
  }
  sameView(r.history.chart.value!, r.reread());
  assert.ok(largest <= 2000, "rendered points bounded over time");
  assert.equal(r.history.chartError.value, "");
});

test("a narrow spike in the unfinished window appears once, a later higher one replaces it, and a late original inside the overlap is added", async () => {
  const r = rig();
  await r.history.loadAll();
  const window = r.history.chart.value!.selection!.windowSeconds * SECOND;
  const open = Math.floor(r.clock / window) * window;
  r.add(open + 1500, 900, r.clock); // between originals, so neither the window's first nor its last
  r.advance(SECOND);
  await r.history.loadAll(true);
  assert.equal(r.history.chart.value!.items.filter(i => i.value === 900).length, 1);
  r.add(open + 1600, 950, r.clock);
  r.add(r.clock - 20 * SECOND + 3, -800, r.clock); // measured 20 s ago, stored only now
  r.advance(SECOND);
  await r.history.loadAll(true);
  const items = r.history.chart.value!.items;
  assert.equal(items.filter(i => i.value === 950).length, 1);
  assert.equal(items.filter(i => i.value === 900).length, 0, "the replaced window's previous maximum is gone, not duplicated");
  assert.equal(items.filter(i => i.value === -800).length, 1, "late arrival within LIVE_OVERLAP");
  assert.equal(new Set(items.map(i => `${i.observedAt}|${i.value}`)).size, items.length);
  sameView(r.history.chart.value!, r.reread());
});

test("an original stored later than the overlap is only shown after the periodic rebase (documented limit)", async () => {
  const r = rig();
  await r.history.loadAll();
  r.add(r.clock - 2 * 60 * SECOND, 4321, r.clock + SECOND);
  r.advance(5 * SECOND);
  await r.history.loadAll(true);
  assert.equal(r.history.chart.value!.items.some(i => i.value === 4321), false);
  while (charts(r).at(-1)!.window !== null) { r.advance(5 * SECOND); await r.history.loadAll(true); }
  assert.ok(r.clock - start >= REBASE_EVERY && r.clock - start <= REBASE_EVERY + 10 * SECOND);
  assert.equal(r.history.chart.value!.items.some(i => i.value === 4321), true);
});

test("empty tails advance the end, a failed poll keeps data and retries from the same end, and alignment stays exact", async () => {
  let fail = false;
  const r = rig({ failOn: path => fail && path.includes("/chart?") });
  await r.history.loadAll();
  const before = r.history.chart.value;
  fail = true;
  r.advance(5 * SECOND);
  await r.history.loadAll(true);
  assert.match(r.history.chartError.value, /^자동 갱신 실패.*chart poll failed$/);
  assert.equal(r.history.chart.value, before);
  const failed = charts(r).at(-1)!;
  fail = false;
  r.advance(5 * SECOND);
  await r.history.loadAll(true);
  assert.equal(charts(r).at(-1)!.from, failed.from, "retry starts where the last success ended");
  assert.equal(r.history.chartError.value, "");
  sameView(r.history.chart.value!, r.reread());
  const quiet = rig({ back: 2 * HOUR });
  quiet.data.splice(0, quiet.data.length, ...quiet.data.filter(p => p.at < start));
  await quiet.history.loadAll();
  for (let i = 0; i < 4; i++) { quiet.advance(5 * SECOND); await quiet.history.loadAll(true); }
  assert.equal(quiet.history.loaded.value!.to, quiet.clock, "empty deltas move the loaded end");
  sameView(quiet.history.chart.value!, quiet.reread());
});

test("long ranges: steady polls read only the tail, not the week, while memory stays within the budget", async () => {
  const r = rig({ spacing: 10 * SECOND, back: 11 * DAY });
  await r.history.loadAll(); // the page loads the catalog (retention) before a quick range is picked
  r.history.startFollowing(7 * DAY);
  await tick();
  const initial = charts(r)[1];
  assert.equal(initial.to - initial.from, 10.5 * DAY);
  for (let poll = 0; poll < 30; poll++) { r.advance(5 * SECOND); await r.history.loadAll(true); }
  const window = r.history.chart.value!.selection!.windowSeconds * SECOND;
  for (const poll of charts(r).slice(2)) assert.ok(poll.to - poll.from <= window + LIVE_OVERLAP + 5 * SECOND && poll.window === window / SECOND);
  assert.ok(r.history.chart.value!.items.length <= 2000);
  sameView(r.history.chart.value!, r.reread());
});

test("budget change, span change, a long gap and a sensor reset each rebase with one full request", async () => {
  const r = rig();
  await r.history.loadAll();
  r.state.budget = 1000;
  r.history.resize();
  await tick(20);
  assert.equal(charts(r).at(-1)!.window, null);
  assert.equal(charts(r).at(-1)!.points, 1000);
  assert.ok(r.history.chart.value!.items.length <= 1000);
  r.advance(5 * SECOND);
  await r.history.loadAll(true);
  assert.notEqual(charts(r).at(-1)!.window, null, "then tails again");
  r.history.startFollowing(6 * HOUR);
  await tick();
  assert.equal(charts(r).at(-1)!.window, null);
  r.advance(11 * 60 * SECOND);
  await r.history.loadAll(true);
  assert.equal(charts(r).at(-1)!.window, null, "a gap beyond REBASE_GAP");
  r.history.clear();
  assert.equal(r.history.chart.value, null);
  await r.history.loadAll(true);
  assert.equal(charts(r).at(-1)!.window, null);
});

test("a sparse sensor tails originals and switches to selection with one rebase when they no longer fit", async () => {
  const r = rig({ spacing: 5 * SECOND, back: 2 * HOUR, budget: 1000 });
  await r.history.loadAll();
assert.equal(r.history.chart.value!.displayMode, "SELECTED_ORIGINALS"); // 1.5 h at 0.2 Hz = 1080 > 1000
  const sparse = rig({ spacing: 10 * SECOND, back: 2 * HOUR, budget: 1000 });
  await sparse.history.loadAll();
  assert.equal(sparse.history.chart.value!.displayMode, "ORIGINALS");
  for (let i = 0; i < 3; i++) { sparse.advance(5 * SECOND); await sparse.history.loadAll(true); }
  assert.equal(charts(sparse).at(-1)!.window, null);
  sameView(sparse.history.chart.value!, sparse.reread());
  const burst = sparse.clock;
  for (let i = 0; i < 600; i++) sparse.add(burst - 25 * SECOND + i * 40, i, burst);
  sparse.advance(5 * SECOND);
  await sparse.history.loadAll(true);
  assert.equal(sparse.history.chart.value!.displayMode, "SELECTED_ORIGINALS");
  assert.ok(sparse.history.chart.value!.items.length <= 1000);
  assert.deepEqual(charts(sparse).slice(-2).map(q => q.window), [null, null], "tail answered with selection → one full rebase");
});

test("the live table reads only newer originals, adds them on top once, and keeps the cursor for older rows", async () => {
  const r = rig();
  await r.history.loadAll();
  assert.equal(r.history.records.value.length, 1000);
  const cursor = r.history.nextCursor.value;
  for (let poll = 0; poll < 3; poll++) { r.advance(5 * SECOND); await r.history.loadAll(true); }
  const tables = r.requests.filter(q => q.kind === "history").slice(1);
  for (const table of tables) assert.ok(table.to - table.from <= LIVE_OVERLAP + 6 * SECOND, "delta, not the hour");
  const rows = r.history.records.value;
  assert.equal(rows.length, 1015);
  assert.equal(new Set(rows.map(row => `${row.observedAt}|${(row as { sequence?: number }).sequence}`)).size, rows.length);
  assert.ok(rows.every((row, i) => i === 0 || Date.parse(row.observedAt) <= Date.parse(rows[i - 1].observedAt)));
  assert.equal(r.history.nextCursor.value, cursor);
  await r.history.loadRecords("more");
  const after = r.history.records.value;
  assert.equal(after.length, 2000, "more continues below the original page");
  assert.ok(Date.parse(after[1015].observedAt) < Date.parse(after[1014].observedAt));
  assert.equal(new Set(after.map(row => (row as { sequence?: number }).sequence)).size, 2000);
  r.advance(5 * SECOND);
  await r.history.loadAll(true);
  assert.equal(r.history.records.value, after, "paged older rows stay pinned");
});

test("the live table rereads in full once it would exceed 2,000 rows", async () => {
  const r = rig({ spacing: 100 });
  await r.history.loadAll();
  r.advance(5 * SECOND);
  await r.history.loadAll(true);
  assert.equal(r.history.records.value.length, 1050);
  for (let i = 0; i < 20; i++) { r.advance(5 * SECOND); await r.history.loadAll(true); }
  assert.ok(r.history.records.value.length <= 2000);
  const tables = r.requests.filter(q => q.kind === "history");
  assert.ok(tables.some((q, i) => i > 0 && q.to - q.from >= HOUR - SECOND), "a full reread happened");
});

test("against an API without point budgets (console rolled forward alone) every poll rereads the window instead of merging", async () => {
  const r = rig({ olderApi: true });
  await r.history.loadAll();
  for (let poll = 0; poll < 3; poll++) { r.advance(5 * SECOND); await r.history.loadAll(true); }
  assert.deepEqual(charts(r).map(q => [q.window, q.to - q.from]), Array(4).fill([null, 1.5 * HOUR]));
});

test("the table delta is authoritative for its period: a reconciled legacy row and originals no longer stored disappear", async () => {
  const r = rig();
  await r.history.loadAll();
  const at = r.clock - 3 * SECOND + 1;
  r.data.push({ at, value: 7.5, arrives: r.clock, sequence: 0, legacy: true });
  r.advance(5 * SECOND);
  await r.history.loadAll(true);
  const atRows = () => r.history.records.value.filter(row => Date.parse(row.observedAt) === at);
  assert.deepEqual(atRows().map(row => row.quality), ["UNVERIFIED"]);
  // The qualified original arrives and the archive hides the legacy one (same time and value).
  r.data.find(p => p.legacy)!.hidden = true;
  r.add(at, 7.5, r.clock);
  const cursor = r.history.nextCursor.value, older = r.history.records.value.filter(row => Date.parse(row.observedAt) < r.clock - 40 * SECOND).length;
  r.advance(5 * SECOND);
  await r.history.loadAll(true);
  assert.deepEqual(atRows().map(row => row.quality), ["QUALIFIED"], "legacy row replaced, not kept beside the qualified one");
  // Originals inside the overlap that are no longer returned (hidden) are removed; older rows and the cursor stay.
  const gone = r.data.filter(p => p.at >= r.clock - 20 * SECOND && p.at < r.clock);
  for (const p of gone) p.hidden = true;
  r.advance(5 * SECOND);
  await r.history.loadAll(true);
  const rows = r.history.records.value;
  assert.equal(rows.some(row => gone.some(p => iso(p.at) === row.observedAt)), false);
  assert.ok(rows.filter(row => Date.parse(row.observedAt) < r.clock - 50 * SECOND).length >= older);
  assert.equal(r.history.nextCursor.value, cursor);
  assert.ok(rows.every((row, i) => i === 0 || Date.parse(row.observedAt) <= Date.parse(rows[i - 1].observedAt)));
});

test("an empty authoritative delta over the whole table period empties it without a stale cursor, then the next poll rereads", async () => {
  const r = rig({ spacing: 10 * SECOND, back: 20 * SECOND });
  r.data.splice(0, r.data.length, ...r.data.filter(p => p.at < start));
  await r.history.loadAll();
  assert.equal(r.history.records.value.length, 2);
  for (const p of r.data) p.hidden = true;
  r.advance(5 * SECOND);
  await r.history.loadAll(true);
  assert.deepEqual(r.history.records.value, []);
  assert.equal(r.history.nextCursor.value, undefined);
  assert.equal(r.history.recordsError.value, "");
  r.advance(5 * SECOND);
  await r.history.loadAll(true);
  const last = r.requests.filter(q => q.kind === "history").at(-1)!;
  assert.equal(last.to - last.from, HOUR, "with no rows the live table reads its whole view again");
});
