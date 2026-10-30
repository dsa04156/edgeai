import assert from "node:assert/strict";
import { test } from "node:test";
import {
  emptyModelDraft,
  pushCommands,
  registrationDocument,
  shortDigest,
  type ModelDraft,
  type ResolvedImage,
} from "../kestra-native/ai-model.ts";

const digest = "sha256:" + "a".repeat(64);
const armDigest = "sha256:" + "b".repeat(64);
const resolved = {
  registry: "10.254.192.47:5000", repository: "edgeai/detector", reference: "1.0", digest, mediaType: "application/vnd.oci.image.index.v1+json", kind: "INDEX",
  imageReference: `10.254.192.47:5000/edgeai/detector@${digest}`,
  platforms: [
    { os: "linux", architecture: "amd64", variant: null, label: "linux/amd64", runnable: true, manifestDigest: "sha256:" + "c".repeat(64), mediaType: "m", size: 1 },
    { os: "linux", architecture: "arm64", variant: "v8", label: "linux/arm64/v8", runnable: true, manifestDigest: armDigest, mediaType: "m", size: 1 },
    { os: "linux", architecture: "arm", variant: "v7", label: "linux/arm/v7", runnable: false, manifestDigest: "sha256:" + "d".repeat(64), mediaType: "m", size: 1 },
  ],
} as unknown as ResolvedImage;

function filled(): ModelDraft {
  return { ...emptyModelDraft(), modelKey: "detector", displayName: "Detector", modelVersion: "1.0.0", description: "", platform: "linux/arm64/v8",
    command: "python3\n/app/serve.py\n", args: "--port\n8080", conditions: "batchSize=1", runtimeName: "onnxruntime", runtimeVersion: "1.20.1" };
}

test("a new model starts with the default execution configuration and is registered without requirements", () => {
  const draft = emptyModelDraft();
  assert.equal(draft.configName, "default");
  assert.equal("cpu" in draft, false);
  assert.throws(() => registrationDocument(draft, resolved), /모델 키/);
  // Requirements come from a later measurement, so registration neither asks for nor invents them.
  const doc = registrationDocument(filled(), resolved);
  assert.equal("resources" in doc, false);
  assert.equal("resourceProfileId" in doc, false);
});

test("the registration document pins the confirmed digest and declared platform", () => {
  const doc = registrationDocument(filled(), resolved);
  assert.deepEqual(doc.image, { repository: "edgeai/detector", tag: "1.0", digest, platform: { os: "linux", architecture: "arm64", variant: "v8" } });
  assert.deepEqual(doc.execution, { command: ["python3", "/app/serve.py"], args: ["--port", "8080"] });
  assert.deepEqual(doc.inputConditions, { batchSize: "1" });
  assert.equal(doc.configName, "default");
  assert.deepEqual(doc.runtime, { name: "onnxruntime", version: "1.20.1" });
  // A digest-only lookup keeps no tag, and non-runnable or unknown platforms are refused before sending.
  assert.equal(registrationDocument(filled(), { ...resolved, reference: digest }).image.tag, null);
  assert.throws(() => registrationDocument({ ...filled(), platform: "linux/arm/v7" }, resolved), /linux\/amd64·linux\/arm64/);
  assert.throws(() => registrationDocument({ ...filled(), platform: "" }, resolved), /플랫폼/);
  assert.throws(() => registrationDocument(filled(), null), /이미지/);
  assert.throws(() => registrationDocument({ ...filled(), modelKey: "Detector" }, resolved), /모델 키/);
  assert.throws(() => registrationDocument({ ...filled(), configName: "GPU Config" }, resolved), /실행 구성명/);
});

test("long digests are shortened for tables and push instructions name the configured registry", () => {
  assert.equal(shortDigest(digest), "sha256:aaaaaaaaaaaa…");
  assert.equal(shortDigest(null), "—");
  const commands = pushCommands("10.254.192.47:5000", "edgeai/detector", "1.0");
  assert.match(commands, /docker tag <로컬 이미지> 10\.254\.192\.47:5000\/edgeai\/detector:1\.0/);
  assert.match(commands, /docker push 10\.254\.192\.47:5000\/edgeai\/detector:1\.0/);
  assert.match(commands, /buildx build --platform linux\/amd64,linux\/arm64/);
});
