import assert from "node:assert/strict";
import { test } from "node:test";
import { categoryText, isActive, placementText, referenceText, timingText } from "../kestra-native/hawkeye.ts";

const result = (extra = {}) => ({
  category: "forklift_violation", matchesReference: true, processor: "GPU", sizeBytes: 1618623855, sizeVramBytes: 1618623855,
  inferenceMs: 575.19, loadMs: 170.42, roundTripMs: 580.7, ...extra,
});

test("a missing category is a failed judgement, never safe", () => {
  assert.equal(categoryText(result()), "지게차 위반 (forklift_violation)");
  assert.equal(categoryText(result({ category: "safe" })), "위험 없음 (safe)");
  const missing = categoryText(result({ category: null }));
  assert.match(missing, /판정 실패/);
  assert.doesNotMatch(missing, /safe|위험 없음/);
});

test("reference comparison states agreement, disagreement or no category", () => {
  assert.equal(referenceText(result(), "forklift_violation"), "참조 라벨 forklift_violation과 일치");
  assert.equal(referenceText(result({ category: "spill", matchesReference: false }), "safe"), "참조 라벨 safe과 다름");
  assert.equal(referenceText(result({ category: null, matchesReference: null }), "safe"), "참조 라벨 safe · 비교 불가");
});

test("time and placement come from the stored measurement", () => {
  assert.equal(timingText(result()), "575.2 ms (Ollama 처리, 로드 170.4 ms 포함) · API 왕복 580.7 ms");
  assert.equal(placementText(result()), "GPU (VRAM 1.62 GB / 1.62 GB)");
  assert.equal(placementText(result({ processor: "CPU", sizeVramBytes: 0 })), "CPU (VRAM 0.00 GB / 1.62 GB)");
});

test("only QUEUED and RUNNING are active", () => {
  assert.ok(isActive("QUEUED") && isActive("RUNNING"));
  for (const state of ["SUCCEEDED", "FAILED", "INTERRUPTED"]) assert.ok(!isActive(state));
});
