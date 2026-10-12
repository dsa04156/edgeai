import { isLosslessNumber } from "lossless-json";
import { record } from "./json-record";

export const processingNames = { filter: "필터", movingAverage: "이동평균", rms: "RMS", fft: "FFT", threshold: "임계값" };
export function newProcessingStep(op: string): Record<string, unknown> {
  if (op === "filter") return { op, mode: "lowPass", alpha: 0.5 };
  if (op === "movingAverage" || op === "rms") return { op, window: 2 };
  if (op === "fft") return { op, sampleRateHz: 1 };
  return { op: "threshold", operator: "gt", value: 0 };
}
const number = (value: unknown) => isLosslessNumber(value) ? Number(value.value) : typeof value === "number" ? value : NaN;
export function validateProcessing(value: unknown, sourceKeys: string[]): string | undefined {
  const config = record(value), count = number(config.sampleCount);
  if (config.apiVersion !== "edgeai.processing/v1" || !Number.isInteger(count) || count < 1 || count > 1024) return "처리 표본 수는 1~1024 정수여야 합니다.";
  const port = (value: unknown) => typeof value === "string" && value.length <= 63 && /^[a-z][a-z0-9_]*$/.test(value);
  if (!port(config.outputPort)) return "처리 출력 포트를 입력하세요.";
  if (!Array.isArray(config.pipelines) || !config.pipelines.length || config.pipelines.length > 16) return "처리 파이프라인은 1~16개 필요합니다.";
  const names = new Set<string>();
  for (const raw of config.pipelines) {
    const pipeline = record(raw), input = record(pipeline.input);
    if (!port(pipeline.name) || names.has(String(pipeline.name))) return "처리 결과 이름은 고유한 영문 소문자·숫자·밑줄로 입력하세요.";
    names.add(String(pipeline.name));
    if ("sourceKey" in input ? !sourceKeys.includes(String(input.sourceKey)) || !port(input.property) : !port(input.port) || !port(input.output)) return "처리 원본과 측정 항목 또는 연결할 입력 포트를 확인하세요.";
    if (!Array.isArray(pipeline.steps) || !pipeline.steps.length || pipeline.steps.length > 16) return "처리 단계는 1~16개 필요합니다.";
    let remaining = count, spectrum = false;
    for (const [index, rawStep] of pipeline.steps.entries()) {
      const step = record(rawStep), op = String(step.op);
      if (op === "filter") { const alpha = number(step.alpha); if (!(alpha > 0 && alpha <= 1) || !["lowPass", "highPass"].includes(String(step.mode))) return "필터 계수는 0 초과~1이어야 합니다."; }
      else if (op === "movingAverage" || op === "rms") { const size = number(step.window); if (!Number.isInteger(size) || size < 1 || size > remaining) return "창 크기가 처리 단계의 남은 표본 수보다 큽니다."; remaining -= size - 1; }
      else if (op === "fft") { if (spectrum || remaining < 2 || (remaining & (remaining - 1)) || !(number(step.sampleRateHz) > 0 && number(step.sampleRateHz) <= 1000000)) return "FFT는 시간 신호의 2의 거듭제곱 표본 수와 양의 표본 주파수가 필요합니다."; spectrum = true; remaining = remaining / 2 + 1; }
      else if (op === "threshold") { if (index !== pipeline.steps.length - 1 || !Number.isFinite(number(step.value)) || !["gt", "gte", "lt", "lte", "eq"].includes(String(step.operator))) return "임계값은 마지막 단계에 두고 유효한 기준값을 입력하세요."; }
      else return "지원하는 처리 기능을 선택하세요.";
    }
  }
}
