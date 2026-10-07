import assert from "node:assert/strict";
import { test } from "node:test";
import { formatSensorValue, sensorSeries, readingFresh } from "../lib/sensor-series";
const reading = { resource: "temperature", valueType: "Float64", value: "0", units: "C", observedAt: "2026-10-06T10:00:00Z" };
test("sensor graph preserves zero and omits invalid or nonnumeric readings", () => {
  const points = sensorSeries([reading, { ...reading, value: "NaN" }, { ...reading, value: "" }, { ...reading, valueType: "String", value: "123" }, { ...reading, value: "1e1", observedAt: "2026-10-06T09:59:00Z" }]);
  assert.deepEqual(points.map(p => p.value), [10, 0]);
  assert.equal(sensorSeries([{ ...reading, valueType: "Uint16", value: "123" }])[0].value, 123);
  assert.deepEqual(sensorSeries([{ ...reading, valueType: "Uint64", value: "9007199254740993" }]), []);
});
test("old, invalid and future measurements cannot appear fresh", () => {
  const now = Date.parse(reading.observedAt);
  assert.equal(readingFresh(reading.observedAt, now), true);
  assert.equal(readingFresh(reading.observedAt, now + 90001), false);
  assert.equal(readingFresh(reading.observedAt, now - 6000), false);
  assert.equal(readingFresh("bad", now), false);
});
test("numeric display formats measurements without rounding large integer identities", () => {
  assert.equal(formatSensorValue(reading), "0");
  assert.equal(formatSensorValue({ ...reading, value: "4.6930885314941406e+01" }), "46.9309");
  assert.equal(formatSensorValue({ ...reading, valueType: "Uint64", value: "9007199254740993" }), "9007199254740993");
});
