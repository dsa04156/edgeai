import { isLosslessNumber } from "lossless-json";
import type { components } from "./api-schema";
import { validateProcessing } from "./processing";
import { record } from "./json-record";
export { record } from "./json-record";

type Schema = components["schemas"];
export type VDSourceRequirement = { deviceModel: string; required: boolean };
export type VDTemplate = { apiVersion: "edgeai.vd/v2"; type: "sensorMirror" | "processing" | "emulation"; serviceProfileVersionId: string;
  sources: Record<string, VDSourceRequirement>; state: { mode: "STATELESS" }; runtime: { maxConcurrentTasks: number; startupTimeoutSeconds: number; drainTimeoutSeconds: number } };
export const vdTypeNames = { sensorMirror: "센서 미러", processing: "데이터 처리", emulation: "모의 장치" };
export const sourceModeNames = { LIVE: "실제 장치", REPLAY: "재생 데이터", SYNTHETIC: "합성 데이터" };
export function newVDTemplate(type: VDTemplate["type"] = "sensorMirror"): VDTemplate {
  return { apiVersion: "edgeai.vd/v2", type, serviceProfileVersionId: "", sources: type === "emulation" ? {} : { input: { deviceModel: "", required: true } },
    state: { mode: "STATELESS" }, runtime: { maxConcurrentTasks: 1, startupTimeoutSeconds: 120, drainTimeoutSeconds: 120 } };
}
export function sourceRequirements(value: unknown): Record<string, VDSourceRequirement> {
  return Object.fromEntries(Object.entries(record(record(value).sources)).map(([key, raw]) => {
    const source = record(raw);
    return [key, { deviceModel: typeof source.deviceModel === "string" ? source.deviceModel : "", required: source.required === true }];
  }));
}
export function compatibleSource(device: Schema["ManagedDevice"], requirement: VDSourceRequirement) {
  return !!requirement.deviceModel && device.model === requirement.deviceModel;
}
/** UI preflight follows the VD consumer contract; the backend remains authoritative. */
export function validateVDTemplate(value: unknown, profiles: Schema["ProfileVersion"][]): string | undefined {
  const spec = record(value);
  if (spec.apiVersion !== "edgeai.vd/v2") return "센서 디바이스용 VD 템플릿은 edgeai.vd/v2를 사용합니다. 기존 템플릿은 새 버전으로 전환하세요.";
  if (!Object.hasOwn(vdTypeNames, String(spec.type))) return "가상 장치 종류를 선택하세요.";
  if (!profiles.some(p => p.kind === "SERVICE" && p.id === spec.serviceProfileVersionId)) return "발행된 실행 SERVICE 버전을 선택하세요.";
  const sources = sourceRequirements(spec);
  if (spec.sources === null || typeof spec.sources !== "object" || Array.isArray(spec.sources)) return "원본 조건은 키별 JSON 객체여야 합니다.";
  if (Object.keys(sources).length > 16) return "원본 조건은 16개까지 정의할 수 있습니다.";
  if (spec.type !== "emulation" && !Object.values(sources).some(source => source.required) && !(spec.type === "processing" && spec.processing && !Object.keys(sources).length)) return "센서 미러와 데이터 처리는 필수 원본 조건이 하나 이상 필요합니다.";
  if (spec.processing) {
    if (spec.type !== "processing") return "공통 처리 설정은 데이터 처리 종류에서 사용합니다.";
    const issue = validateProcessing(spec.processing, Object.keys(sources)); if (issue) return issue;
  }
  for (const [key, source] of Object.entries(sources)) {
    const raw = record(record(spec.sources)[key]);
    if (typeof raw.required !== "boolean") return `${key}: 필수 여부를 지정하세요.`;
    if (key.length > 100 || !/^[a-z][a-z0-9]*([._-][a-z0-9]+)*$/.test(key)) return "원본 키는 영문 소문자로 시작하는 고유 키여야 합니다.";
    if (!source.deviceModel || source.deviceModel.length > 253 || !/^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$/.test(source.deviceModel)) return `${key}: 센서 DeviceModel을 선택하세요.`;
  }
  if (record(spec.state).mode !== "STATELESS") return "현재 VD 실행은 STATELESS 상태 관리만 지원합니다.";
  const runtime = record(spec.runtime);
  for (const [key, max] of [["maxConcurrentTasks", 16], ["startupTimeoutSeconds", 600], ["drainTimeoutSeconds", 600]] as const) {
    const number = isLosslessNumber(runtime[key]) ? Number(runtime[key].value) : runtime[key];
    if (typeof number !== "number" || !Number.isInteger(number) || number < 1 || number > max) return `${key}: 1~${max} 정수를 입력하세요.`;
  }
}
