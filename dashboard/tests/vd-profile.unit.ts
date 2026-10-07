import { test } from "node:test";
import assert from "node:assert/strict";
import { parse, stringify } from "lossless-json";
import type { components } from "../lib/api-schema";
import { compatibleSource, newVDTemplate, sourceRequirements, validateVDTemplate } from "../lib/vd-profile";

type Schema = components["schemas"];
const profiles = [{ id: "service-1", kind: "SERVICE" }, { id: "device-1", kind: "DEVICE" }] as Schema["ProfileVersion"][];
function template() {
  const value = newVDTemplate(); value.serviceProfileVersionId = "service-1"; value.sources.input.deviceProfileVersionId = "device-1"; return value;
}
test("VD template requires published references, source conditions and bounded runtime policy", () => {
  const value = template();
  assert.equal(validateVDTemplate(value, profiles), undefined);
  assert.equal(validateVDTemplate(parse(stringify(value)!), profiles), undefined);
  assert.match(validateVDTemplate({ ...value, serviceProfileVersionId: "device-1" }, profiles)!, /SERVICE/);
  assert.match(validateVDTemplate({ ...value, sources: {} }, profiles)!, /필수 원본/);
  assert.match(validateVDTemplate({ ...value, runtime: { ...value.runtime, maxConcurrentTasks: 17 } }, profiles)!, /maxConcurrentTasks/);
  assert.match(validateVDTemplate({ ...value, state: { mode: "CHECKPOINT" } }, profiles)!, /STATELESS/);
  assert.match(validateVDTemplate({ ...value, sources: { input: { ...value.sources.input, sourceModes: ["LIVE", "UNKNOWN"] } } }, profiles)!, /데이터 출처/);
  assert.match(validateVDTemplate({ ...value, sources: { input: { ...value.sources.input, sourceModes: ["LIVE", "LIVE"] } } }, profiles)!, /데이터 출처/);
  const emulation = { ...newVDTemplate("emulation"), serviceProfileVersionId: "service-1" };
  assert.equal(validateVDTemplate(emulation, profiles), undefined);
});
test("source choices distinguish exact profile version, permitted mode and active registration", () => {
  const requirement = sourceRequirements(template()).input;
  const device = { state: "ACTIVE", sourceMode: "LIVE", profileVersionId: "device-1" } as Schema["Device"];
  assert.equal(compatibleSource(device, requirement), true);
  assert.equal(compatibleSource({ ...device, profileVersionId: "device-2" }, requirement), false);
  assert.equal(compatibleSource({ ...device, sourceMode: "SYNTHETIC" }, requirement), false);
  assert.equal(compatibleSource({ ...device, state: "RELEASED" }, requirement), false);
  assert.deepEqual(sourceRequirements(null), {});
  assert.deepEqual(sourceRequirements({ sources: { broken: null } }).broken.sourceModes, []);
});
