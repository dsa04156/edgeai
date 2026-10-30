import assert from "node:assert/strict";
import { test } from "node:test";
import {
  emptyModelDraft,
  pushCommands,
  registrationDocument,
  requirementSource,
  shortDigest,
  sourceDraft,
  type ModelDraft,
  type ResolvedImage,
  type SourceProfile,
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
    command: "python3\n/app/serve.py\n", args: "--port\n8080", conditions: "batchSize=1", runtimeName: "onnxruntime", runtimeVersion: "1.20.1",
    cpu: { min: "100m", request: "250m", max: "2" }, memory: { min: "256Mi", request: "512Mi", max: "1Gi" } };
}

test("a new model starts with empty requirement inputs and the default execution configuration", () => {
  const draft = emptyModelDraft();
  assert.equal(draft.configName, "default");
  assert.deepEqual([draft.cpu, draft.memory], [{ min: "", request: "", max: "" }, { min: "", request: "", max: "" }]);
  assert.deepEqual(draft.accelerators, []);
  assert.throws(() => registrationDocument(draft, resolved, null), /모델 키/);
  const noValues = { ...filled(), cpu: { min: "", request: "", max: "" } };
  assert.throws(() => registrationDocument(noValues, resolved, null), /CPU 최소값/);
});

test("the registration document pins the confirmed digest and declared platform and labels manual values MANUAL", () => {
  const doc = registrationDocument(filled(), resolved, null);
  assert.deepEqual(doc.image, { repository: "edgeai/detector", tag: "1.0", digest, platform: { os: "linux", architecture: "arm64", variant: "v8" } });
  assert.deepEqual(doc.execution, { command: ["python3", "/app/serve.py"], args: ["--port", "8080"] });
  assert.deepEqual(doc.resources.cpu, { minMillicores: 100, requestMillicores: 250, maxMillicores: 2000, basis: "MANUAL" });
  assert.deepEqual(doc.resources.memory, { minBytes: 268435456, requestBytes: 536870912, maxBytes: 1073741824, basis: "MANUAL" });
  assert.equal(doc.resourceProfileId, null);
  assert.equal(doc.configName, "default");
  assert.deepEqual(doc.runtime, { name: "onnxruntime", version: "1.20.1" });
  // A digest-only lookup keeps no tag, and non-runnable or unknown platforms are refused before sending.
  assert.equal(registrationDocument(filled(), { ...resolved, reference: digest }, null).image.tag, null);
  assert.throws(() => registrationDocument({ ...filled(), platform: "linux/arm/v7" }, resolved, null), /linux\/amd64·linux\/arm64/);
  assert.throws(() => registrationDocument({ ...filled(), platform: "" }, resolved, null), /플랫폼/);
  assert.throws(() => registrationDocument(filled(), null, null), /이미지/);
  assert.throws(() => registrationDocument({ ...filled(), cpu: { min: "300m", request: "250m", max: "2" } }, resolved, null), /최소 ≤ 요청 ≤ 최대/);
  assert.throws(() => registrationDocument({ ...filled(), modelKey: "Detector" }, resolved, null), /모델 키/);
  assert.throws(() => registrationDocument({ ...filled(), configName: "GPU Config" }, resolved, null), /실행 구성명/);
});

const source: SourceProfile = {
  profile: {
    id: "00000000-0000-4000-8000-0000000000b1", revision: 2, measurementStatus: "MEASURED",
    inputConditions: { batchSize: "1" }, compatibility: { os: "linux", architectures: ["arm64"], runtime: { name: "onnxruntime", version: "1.20.1" } },
    resources: { cpu: { minMillicores: 100, requestMillicores: 177, maxMillicores: 2000, basis: "RECOMMENDED" },
      memory: { minBytes: 268435456, requestBytes: 450000000, maxBytes: 1073741824, basis: "MEASURED" },
      accelerators: [{ kind: "GPU", resourceName: "nvidia.com/gpu", unit: "COUNT", min: 1, request: 1, max: 1, deviceMemoryBytes: 4294967296, vendor: "NVIDIA", models: ["RTX 5080"] }] },
  } as unknown as SourceProfile["profile"],
  command: ["python3", "/app/serve.py"], args: ["--port", "8080"],
};

test("an unchanged source profile keeps its cited bases; any edit turns every basis MANUAL", () => {
  const draft = { ...filled(), ...sourceDraft(source) };
  assert.equal(draft.cpu.request, "177m");
  assert.equal(draft.memory.request, "450000000");
  assert.equal(draft.accelerators[0].deviceMemory, "4Gi");
  assert.equal(draft.conditions, "batchSize=1");
  assert.equal(draft.command, "python3\n/app/serve.py");
  assert.equal(requirementSource(draft, source), "RESOURCE_PROFILE");
  const doc = registrationDocument(draft, resolved, source);
  assert.equal(doc.resourceProfileId, source.profile.id);
  assert.equal(doc.resources.cpu.basis, "RECOMMENDED");
  assert.equal(doc.resources.memory.basis, "MEASURED");
  for (const edit of [{ cpu: { ...draft.cpu, request: "200m" } }, { conditions: "batchSize=8" }, { command: "python3\n/app/other.py" }, { runtimeVersion: "1.21" },
    { accelerators: [{ ...draft.accelerators[0], request: "1", max: "2" }] }]) {
    const edited = { ...draft, ...edit };
    assert.equal(requirementSource(edited, source), "MANUAL_FROM_RESOURCE_PROFILE", JSON.stringify(edit));
    const manual = registrationDocument(edited, resolved, source);
    assert.equal(manual.resources.cpu.basis, "MANUAL");
    assert.equal(manual.resources.memory.basis, "MANUAL");
    assert.equal(manual.resourceProfileId, source.profile.id);
  }
  assert.equal(requirementSource(filled(), null), "MANUAL");
});

test("long digests are shortened for tables and push instructions name the configured registry", () => {
  assert.equal(shortDigest(digest), "sha256:aaaaaaaaaaaa…");
  assert.equal(shortDigest(null), "—");
  const commands = pushCommands("10.254.192.47:5000", "edgeai/detector", "1.0");
  assert.match(commands, /docker tag <로컬 이미지> 10\.254\.192\.47:5000\/edgeai\/detector:1\.0/);
  assert.match(commands, /docker push 10\.254\.192\.47:5000\/edgeai\/detector:1\.0/);
  assert.match(commands, /buildx build --platform linux\/amd64,linux\/arm64/);
});
