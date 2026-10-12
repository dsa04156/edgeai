import { isLosslessNumber } from "lossless-json";

export function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value) && !isLosslessNumber(value) ? value as Record<string, unknown> : {};
}
