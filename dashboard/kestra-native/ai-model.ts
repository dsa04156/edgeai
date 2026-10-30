import type { components } from "../lib/api-schema";
import { conditions } from "./resource-profile.ts";

// AI model catalog: draft, registration document and display helpers. The server re-validates everything and re-reads
// the image from the configured registry; this module never accepts a free-form image string. Resource requirements
// are not entered at registration: they come later from a measurement of the registered image.
type Api = components["schemas"];
export type ResolvedImage = Api["AiModelResolvedImage"];
/** `requirements` are the legacy registration-time values (null when none); readiness is `availability` alone. */
export type ModelEntry = Api["AiModelExecution"];
export type Registration = Api["AiModelRegistration"];
export type RequirementSource = "MANUAL" | "RESOURCE_PROFILE" | "MANUAL_FROM_RESOURCE_PROFILE";

export const SOURCE_LABEL: Record<RequirementSource, string> = {
  MANUAL: "사용자 지정(미측정)",
  RESOURCE_PROFILE: "자원 프로파일 그대로",
  MANUAL_FROM_RESOURCE_PROFILE: "자원 프로파일에서 수정(사용자 지정)",
};

const KEY = /^[a-z][a-z0-9._-]{0,99}$/;
const VERSION = /^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$/;
const CONFIG = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;
export const REPOSITORY = /^[a-z0-9]+((\.|_|__|-+)[a-z0-9]+)*(\/[a-z0-9]+((\.|_|__|-+)[a-z0-9]+)*)*$/;

export type ModelDraft = {
  modelKey: string;
  displayName: string;
  modelVersion: string;
  configName: string;
  description: string;
  /** Platform label chosen among the resolved image's declared platforms (e.g. "linux/arm64/v8"). */
  platform: string;
  command: string;
  args: string;
  conditions: string;
  runtimeName: string;
  runtimeVersion: string;
};

export function emptyModelDraft(): ModelDraft {
  return {
    modelKey: "", displayName: "", modelVersion: "", configName: "default", description: "", platform: "",
    command: "", args: "", conditions: "", runtimeName: "", runtimeVersion: "",
  };
}

const lines = (text: string) => text.split("\n").map((l) => l.trim()).filter(Boolean);
const optionalList = (values: string[]) => (values.length ? values : null);

export function registrationDocument(draft: ModelDraft, image: ResolvedImage | null): Registration {
  if (!KEY.test(draft.modelKey)) throw new Error("모델 키는 소문자로 시작하는 영문 소문자·숫자·._- 100자 이하로 입력하세요.");
  if (!draft.displayName.trim() || draft.displayName.length > 200) throw new Error("표시 이름을 200자 이하로 입력하세요.");
  if (!VERSION.test(draft.modelVersion)) throw new Error("모델 버전은 영문·숫자로 시작하는 64자 이하(예 1.0.0)로 입력하세요.");
  if (!CONFIG.test(draft.configName)) throw new Error("실행 구성명은 영문 소문자·숫자·-로 63자 이하(기본 default)입니다.");
  if (draft.description.length > 2000) throw new Error("설명은 2000자 이하입니다.");
  if (!image) throw new Error("Registry에서 이미지를 조회해 digest를 확인하세요.");
  const platform = image.platforms.find((p) => p.label === draft.platform);
  if (!platform) throw new Error("이미지가 선언한 플랫폼 중 하나를 선택하세요.");
  if (!platform.runnable) throw new Error("등록할 수 있는 플랫폼은 linux/amd64·linux/arm64입니다.");
  const command = lines(draft.command), args = lines(draft.args);
  if ([...command, ...args].some((v) => v.length > 4096) || command.length > 32 || args.length > 32)
    throw new Error("실행 명령·인자는 각각 32줄, 줄마다 4096자 이하입니다.");
  const runtime = draft.runtimeName.trim()
    ? { name: draft.runtimeName.trim(), ...(draft.runtimeVersion.trim() ? { version: draft.runtimeVersion.trim() } : {}) }
    : null;
  const tag = /^sha256:/.test(image.reference) ? null : image.reference;
  return {
    modelKey: draft.modelKey,
    displayName: draft.displayName.trim(),
    modelVersion: draft.modelVersion,
    configName: draft.configName,
    description: draft.description.trim() || null,
    image: {
      repository: image.repository, tag, digest: image.digest,
      platform: { os: platform.os as "linux", architecture: platform.architecture as "amd64" | "arm64", variant: platform.variant ?? null },
    },
    execution: { command: optionalList(command), args: optionalList(args) },
    inputConditions: conditions(draft.conditions),
    runtime,
  };
}

/** Tables show a fixed-width prefix; the full digest stays in the detail view and the title attribute. */
export function shortDigest(digest: string | null | undefined) {
  if (!digest) return "—";
  return digest.length > 19 ? `${digest.slice(0, 19)}…` : digest;
}

/** The existing push flow into the server-configured registry (images are never uploaded through the web). */
export function pushCommands(host: string, repository = "edgeai/<모델 이름>", tag = "<태그>") {
  const target = `${host}/${repository}:${tag}`;
  return [
    "# 단일 플랫폼 이미지",
    `docker tag <로컬 이미지> ${target}`,
    `docker push ${target}`,
    "# 여러 플랫폼(index) 이미지",
    `docker buildx build --platform linux/amd64,linux/arm64 -t ${target} --push <빌드 경로>`,
  ].join("\n");
}
