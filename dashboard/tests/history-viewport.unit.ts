import assert from "node:assert/strict";
import { test } from "node:test";
import { DAY, HOUR, MAX_PERIOD, MIN_SPAN, SECOND, clampViewport, debounce, fetchWindow, followViewport, historyBounds, latestRequest, markGaps, needsFetch, quickRanges } from "../lib/history-viewport.ts";

const now = Date.parse("2026-10-21T12:00:00Z"), bounds = historyBounds(now, 30);

test("live view follows the latest edge and quick ranges stay within retention and the 31-day request limit", () => {
  assert.deepEqual(followViewport(HOUR, bounds), { from: now - HOUR, to: now });
  assert.deepEqual(quickRanges(bounds).map(range => range.label), ["1시간", "6시간", "1일", "7일", "30일"]);
  assert.deepEqual(quickRanges(historyBounds(now, 7)).map(range => range.label), ["1시간", "6시간", "1일", "7일"]);
  assert.equal(clampViewport({ from: 0, to: now }, historyBounds(now, 60)).to - clampViewport({ from: 0, to: now }, historyBounds(now, 60)).from, MAX_PERIOD);
});

test("panning past the newest or oldest retained time keeps the width instead of shrinking or showing the future", () => {
  assert.deepEqual(clampViewport({ from: now - HOUR / 2, to: now + HOUR / 2 }, bounds), { from: now - HOUR, to: now });
  assert.deepEqual(clampViewport({ from: bounds.from - HOUR, to: bounds.from }, bounds), { from: bounds.from, to: bounds.from + HOUR });
  assert.equal(clampViewport({ from: now - 1, to: now }, bounds).to - clampViewport({ from: now - 1, to: now }, bounds).from, MIN_SPAN);
});

test("dragging an hour back fetches older history than the initial hour, padded and bounded to 31 days", () => {
  const initial = fetchWindow(followViewport(HOUR, bounds), bounds);
  assert.deepEqual(initial, { from: now - 1.5 * HOUR, to: now });
  const dragged = { from: now - 3 * HOUR, to: now - 2 * HOUR };
  assert.equal(needsFetch({ ...initial, selected: false }, dragged, bounds), true);
  assert.deepEqual(fetchWindow(dragged, bounds), { from: now - 3.5 * HOUR, to: now - 1.5 * HOUR });
  const month = fetchWindow({ from: now - 30 * DAY, to: now }, historyBounds(now, 365));
  assert.ok(month.to - month.from <= MAX_PERIOD);
  assert.ok(fetchWindow({ from: bounds.from, to: bounds.from + HOUR }, bounds).from >= bounds.from);
});

test("small moves inside loaded data do not request again, while zooming into thinned originals does", () => {
  const loaded = { from: now - 1.5 * HOUR, to: now, selected: false };
  assert.equal(needsFetch(loaded, { from: now - 80 * 60 * SECOND, to: now - 20 * 60 * SECOND }, bounds), false);
  assert.equal(needsFetch(null, { from: now - HOUR, to: now }, bounds), true);
  const week = { from: now - 10 * DAY, to: now, selected: true };
  assert.equal(needsFetch(week, { from: now - 2 * DAY, to: now - DAY }, bounds), true);
  assert.equal(needsFetch({ ...week, selected: false }, { from: now - 2 * DAY, to: now - DAY }, bounds), false);
});

test("line breaks only where measurements stop well beyond their usual spacing", () => {
  const points = [0, 1, 2, 3, 60, 61, 62].map(s => ({ at: s * SECOND }));
  assert.deepEqual(markGaps(points).map(p => p.breakBefore), [false, false, false, false, true, false, false]);
  assert.deepEqual(markGaps(points, 30 * SECOND).map(p => p.breakBefore), Array(7).fill(false));
  assert.deepEqual(markGaps([{ at: 0 }, { at: HOUR }]).map(p => p.breakBefore), [false, false]);
});

test("latest request wins: a newer start aborts the older request and marks it stale", () => {
  const guard = latestRequest(), first = guard.start(), second = guard.start();
  assert.equal(first.signal.aborted, true);
  assert.equal(first.current(), false);
  assert.equal(second.current(), true);
  guard.cancel();
  assert.equal(second.signal.aborted, true);
  assert.equal(second.current(), false);
});

test("debounce runs only the last settled viewport and can be cancelled on sensor change", async () => {
  const calls: number[] = [], run = debounce((value: number) => calls.push(value), 10);
  run(1); run(2); run(3);
  await new Promise(resolve => setTimeout(resolve, 30));
  run(4); run.cancel();
  await new Promise(resolve => setTimeout(resolve, 30));
  assert.deepEqual(calls, [3]);
});
