import assert from "node:assert/strict";
import { test } from "node:test";
import {
  accuracyText,
  className,
  coverageNote,
  imageCountProblem,
  isActive,
  outcomeText,
  precisionDiffers,
  targetRows,
} from "../kestra-native/scenario.ts";

const summary = (target: string, extra = {}) => ({
  target, processed: 10, succeeded: 10, failed: 0, meanInferenceMs: 2.5, p50InferenceMs: 2.4, p95InferenceMs: 3.1,
  meanRoundTripMs: 4.25, computeImagesPerSecond: 400, callWallSeconds: 0.0425, callWallImagesPerSecond: 235.29,
  pairedLabeledCorrect: 8, ...extra,
});
const comparison = (extra = {}) => ({
  targets: [summary("GPU"), summary("NPU", { pairedLabeledCorrect: 7 })], pairedCount: 10, top1AgreementCount: 9,
  labelsTrusted: true, runWallSeconds: 0.5, pairedImagesPerRunWallSecond: 20, ...extra,
});

test("image count must be a whole number from 1 to the server maximum", () => {
  assert.equal(imageCountProblem(1, 100), "");
  assert.equal(imageCountProblem(100, 100), "");
  for (const value of [0, 101, 2.5, Number.NaN, undefined]) assert.match(imageCountProblem(value, 100), /1~100/);
});

test("only QUEUED, RUNNING and STOPPING are active", () => {
  for (const state of ["QUEUED", "RUNNING", "STOPPING"]) assert.equal(isActive(state), true);
  for (const state of ["STOPPED", "SUCCEEDED", "FAILED", "INTERRUPTED"]) assert.equal(isActive(state), false);
});

test("target rows keep wall and compute figures separate and labelled with units", () => {
  const [gpu, npu] = targetRows(comparison());
  assert.equal(gpu.target, "GPU");
  assert.equal(gpu.processed, "10 (성공 10 · 실패 0)");
  assert.equal(gpu.roundTrip, "4.25 ms");
  assert.equal(gpu.compute, "2.50 ms (p50 2.40 · p95 3.10)");
  assert.equal(gpu.callWallRate, "235.3 장/s");
  assert.equal(gpu.computeRate, "400.0 장/s");
  assert.equal(gpu.accuracy, "8/10 (80.0%)");
  assert.equal(npu.accuracy, "7/10 (70.0%)");
  const [empty] = targetRows(comparison({ targets: [summary("GPU", { succeeded: 0, processed: 1, failed: 1, meanInferenceMs: null,
    p50InferenceMs: null, p95InferenceMs: null, meanRoundTripMs: null, computeImagesPerSecond: null, callWallImagesPerSecond: null }), summary("NPU")] }));
  assert.equal(empty.compute, "—");
  assert.equal(empty.callWallRate, "—");
});

test("accuracy is shown only for trusted labels and always with its paired denominator", () => {
  assert.equal(accuracyText(79, 100, true), "79/100 (79.0%)");
  assert.equal(accuracyText(null, 100, false), "라벨 미사용");
  assert.equal(accuracyText(0, 0, true), "—");
  const [gpu] = targetRows(comparison({ labelsTrusted: false, targets: [summary("GPU", { pairedLabeledCorrect: null }), summary("NPU")] }));
  assert.equal(gpu.accuracy, "라벨 미사용");
});

test("coverage note states the partial denominator of a stopped run", () => {
  assert.equal(coverageNote({ state: "SUCCEEDED", requestedCount: 100, dispatchedCount: 100 }, comparison({ pairedCount: 100 })),
    "요청 100장 · 전송 100장 · 양쪽 성공(비교 기준) 100장");
  assert.equal(coverageNote({ state: "STOPPED", requestedCount: 100, dispatchedCount: 37 }, comparison({ pairedCount: 37 })),
    "중지로 일부만 처리됨 · 요청 100장 · 전송 37장 · 양쪽 성공(비교 기준) 37장");
  assert.equal(coverageNote({ state: "QUEUED", requestedCount: 5, dispatchedCount: 0 }, null), "요청 5장 · 전송 0장");
});

test("unlike precision between targets is detected", () => {
  assert.equal(precisionDiffers([{ precision: "FP32" }, { precision: "HEF" }]), true);
  assert.equal(precisionDiffers([{ precision: "FP32" }, { precision: "FP32" }]), false);
  assert.equal(precisionDiffers([{ precision: "FP32" }]), false);
});

test("class numbers name only the ten Imagenette classes", () => {
  assert.equal(className(497), "497 church");
  assert.equal(className(0), "0 tench");
  assert.equal(className(406), "406");
  assert.equal(className(null), "—");
});

test("a missing target result is in progress only while the run is active", () => {
  for (const state of ["QUEUED", "RUNNING", "STOPPING"]) assert.equal(outcomeText(null, state), "처리 중");
  assert.equal(outcomeText(null, "INTERRUPTED"), "결과 없음 · 실행 중단");
  for (const state of ["STOPPED", "SUCCEEDED", "FAILED"]) assert.equal(outcomeText(null, state), "결과 없음");
  const succeeded = { status: "SUCCEEDED", top1: 497, failureCode: null };
  assert.equal(outcomeText(succeeded, "INTERRUPTED"), "497 church");
  assert.equal(outcomeText({ status: "FAILED", top1: null, failureCode: "RUNTIME_UNAVAILABLE" }, "FAILED"), "실패 · RUNTIME_UNAVAILABLE");
});
