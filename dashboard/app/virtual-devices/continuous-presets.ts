import type { components } from "../../lib/api-schema";

type Schema = components["schemas"];
type Sensor = Schema["ContinuousSensor"];
type Source = Schema["VirtualDeviceSources"][number];
export type ContinuousPreset = { id: string; label: string; expression: string; squared?: boolean; slots: { label: string; property: RegExp }[] };
const temperature = /^(temp|temperature)(_|$)/i;
export const continuousPresets: ContinuousPreset[] = [
  { id: "temperatureMean", label: "통합 온도", expression: "(a + b) / 2", slots: [{ label: "온도 1", property: temperature }, { label: "온도 2", property: temperature }] },
  { id: "temperatureDifference", label: "온도 편차", expression: "a - b", slots: [{ label: "온도 1", property: temperature }, { label: "온도 2", property: temperature }] },
  ...([ ["rotation", "회전", "gyro"], ["acceleration", "가속도", "acceleration"] ] as const).flatMap(([id, label, property]) =>
    [["x", "y", "z"], ["x", "y"], ["x", "z"], ["y", "z"]].map(axes => ({
      id: `${id}${axes.join("").toUpperCase()}`,
      label: axes.length === 3 ? `3축 ${label} 제곱합` : `${label} ${axes.join("").toUpperCase()} 평면 제곱합`,
      expression: axes.length === 3 ? "a * a + b * b + c * c" : "a * a + b * b", squared: true,
      slots: axes.map(axis => ({ label: `${label} ${axis.toUpperCase()}`, property: new RegExp(`^${property}_${axis}(?:_raw)?$`, "i") })),
    }))),
];

export function presetChoices(preset: ContinuousPreset, index: number, inputs: Sensor[], sources: Source[]) {
  const otherSources = sources.filter((_, n) => n !== index);
  const unit = otherSources.map(source => inputs.find(sensor => sensor.deviceId === source.deviceId)?.unit).find(Boolean);
  return inputs.filter(sensor => sensor.type !== "bool" && sensor.unit && preset.slots[index].property.test(sensor.property)
    && (!unit || sensor.unit === unit) && !otherSources.some(source => source.deviceId === sensor.deviceId));
}

export function presetUnit(preset: ContinuousPreset, inputs: Sensor[], sources: Source[]) {
  const unit = inputs.find(sensor => sensor.deviceId === sources[0]?.deviceId)?.unit || "";
  return preset.squared && unit ? /[\s/]/.test(unit) ? `(${unit})²` : `${unit}²` : unit;
}

export function presetReady(preset: ContinuousPreset, inputs: Sensor[], sources: Source[]) {
  return presetUnit(preset, inputs, sources).length <= 32 && sources.length === preset.slots.length && sources.every((source, index) => presetChoices(preset, index, inputs, sources).some(sensor => sensor.deviceId === source.deviceId && source.properties?.length === 1 && source.properties[0] === sensor.property));
}
