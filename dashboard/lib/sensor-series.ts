import type { components } from "./api-schema";
type Reading = components["schemas"]["SensorReading"];
const numericType = /^(?:(?:U?Int|Uint)(?:8|16|32|64)|Float(?:32|64))$/;

export function formatSensorValue(reading: Reading) {
  const value = Number(reading.value);
  if (!reading.value.trim() || !Number.isFinite(value) || !numericType.test(reading.valueType)) return reading.value;
  if (!reading.valueType.startsWith("Float") && !Number.isSafeInteger(value)) return reading.value;
  return value.toLocaleString("ko-KR", { maximumSignificantDigits: 6 });
}

export function sensorSeries(readings: Reading[]) {
  return readings.flatMap(reading => {
    if (!numericType.test(reading.valueType) || !reading.value.trim()) return [];
    const value = Number(reading.value), time = Date.parse(reading.observedAt);
    if (!reading.valueType.startsWith("Float") && !Number.isSafeInteger(value)) return [];
    return Number.isFinite(value) && Number.isFinite(time) ? [{ value, time }] : [];
  }).sort((a, b) => a.time - b.time);
}
export function readingFresh(observedAt: string, now: number) {
  const age = now - Date.parse(observedAt);
  return Number.isFinite(age) && age >= -5000 && age <= 90000;
}
