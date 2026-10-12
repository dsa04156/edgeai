import { test } from "node:test";
import assert from "node:assert/strict";
import { parse, stringify } from "lossless-json";
import type { components } from "../lib/api-schema";
import { compatibleSource, newVDTemplate, sourceRequirements, validateVDTemplate } from "../lib/vd-profile";

type Schema = components["schemas"];
const profiles = [{ id: "service-1", kind: "SERVICE" }, { id: "device-1", kind: "DEVICE" }] as Schema["ProfileVersion"][];
function template() {
  const value = newVDTemplate(); value.serviceProfileVersionId = "service-1"; value.sources.input.deviceModel = "temperature-model"; return value;
}
test("VD template requires published references, source conditions and bounded runtime policy", () => {
  const value = template();
  assert.equal(validateVDTemplate(value, profiles), undefined);
  assert.equal(validateVDTemplate(parse(stringify(value)!), profiles), undefined);
  assert.match(validateVDTemplate({ ...value, serviceProfileVersionId: "device-1" }, profiles)!, /SERVICE/);
  assert.match(validateVDTemplate({ ...value, sources: {} }, profiles)!, /필수 원본/);
  assert.match(validateVDTemplate({ ...value, runtime: { ...value.runtime, maxConcurrentTasks: 17 } }, profiles)!, /maxConcurrentTasks/);
  assert.match(validateVDTemplate({ ...value, state: { mode: "CHECKPOINT" } }, profiles)!, /STATELESS/);
  assert.match(validateVDTemplate({ ...value, apiVersion: "edgeai.vd/v1" }, profiles)!, /새 버전/);
  assert.match(validateVDTemplate({ ...value, sources: { input: { deviceModel: "", required: true } } }, profiles)!, /DeviceModel/);
  const emulation = { ...newVDTemplate("emulation"), serviceProfileVersionId: "service-1" };
  assert.equal(validateVDTemplate(emulation, profiles), undefined);
});
test("VD selects sensor devices by DeviceModel without requiring an app registration", () => {
  const requirement = sourceRequirements(template()).input;
  const device = { id: "sensehat-001", state: "ONLINE", model: "temperature-model" } as Schema["ManagedDevice"];
  assert.equal(compatibleSource(device, requirement), true);
  assert.equal(compatibleSource({ ...device, model: "different-model" }, requirement), false);
  assert.equal(compatibleSource({ ...device, state: "OFFLINE" }, requirement), true);
  assert.deepEqual(sourceRequirements(null), {});
  assert.equal(sourceRequirements({ sources: { broken: null } }).broken.deviceModel, "");
});
