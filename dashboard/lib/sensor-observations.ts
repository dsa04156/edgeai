import type { components } from "./api-schema";

type Device = components["schemas"]["ManagedDevice"];
export type SensorObservation = { at: number; value: number; segment: number };
type Observations = { signature: string; connected: boolean; segment: number; points: SensorObservation[] };
export type SensorObservations = Record<string, Observations>;
export const sensorKey = (deviceId: string, property: string) => `${deviceId}/${property}`;

// This is a bounded page-session view of latest observations, not sensor history.
export function observeSensors(previous: SensorObservations, devices: Device[], now: number): SensorObservations {
  const next: SensorObservations = {};
  for (const device of devices) for (const property of device.profile.properties) {
    const key = sensorKey(device.id, property.name);
    const signature = JSON.stringify([device.uid, device.targetNode, device.profile, property]);
    const old = previous[key]?.signature === signature ? previous[key] : undefined;
    const connected = device.state === "ONLINE";
    const segment = (old?.segment ?? 0) + (old?.connected && !connected ? 1 : 0);
    const points = (old?.points ?? []).filter(point => point.at >= now - 30 * 60 * 1000);
    const reading = device.properties[property.name];
    const text = reading?.value.trim() ?? "";
    const value = property.dataType === "string" || !text ? NaN : property.dataType === "bool"
      ? ({ true: 1, false: 0, "1": 1, "0": 0 } as Record<string, number>)[text.toLowerCase()] : Number(text);
    if (connected && Number.isFinite(value) && reading?.timestamp >= now - 30 * 60 * 1000 && reading.timestamp > 0 && reading.timestamp <= now &&
        (!points.length || reading.timestamp > points[points.length - 1].at)) points.push({ at: reading.timestamp, value, segment });
    next[key] = { signature, connected, segment, points: points.slice(-360) };
  }
  return next;
}

export function interruptSensors(previous: SensorObservations): SensorObservations {
  return Object.fromEntries(Object.entries(previous).map(([key, value]) => [key, { ...value, connected: false, segment: value.segment + (value.connected ? 1 : 0) }]));
}
