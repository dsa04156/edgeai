<template>
  <TopNavBar title="모델 추론"
    ><template #actions
      ><KsButton
        :disabled="loading || running"
        @click="loadModels(() => load())"
        >모델 새로고침</KsButton
      ></template
    ></TopNavBar
  >
  <main class="native-page">
    <NativeLoading :busy="loading" label="모델 목록을 불러오는 중…" />
    <NativeFeedback :error="modelError" />
    <p class="native-note">
      서버에 설정된 모델로 이미지 한 장의 객체를 검출합니다. 결과는 이 화면에만
      표시하며 저장하지 않습니다.
    </p>
    <section class="native-panel">
      <h2>추론 입력</h2>
      <KsForm labelPosition="top" @submit.prevent="submit"
        ><div class="native-grid">
          <KsFormItem label="모델"
            ><KsSelect
              v-model="modelId"
              :disabled="loading || running || !available.length"
              placeholder="모델을 선택하세요"
              ><KsOption
                v-for="model in models"
                :key="model.id"
                :value="model.id"
                :disabled="!model.available"
                :label="modelLabel(model)" /></KsSelect
            ><p
              v-for="model in targets"
              :key="model.id"
              class="inference-target"
            >
              <span v-if="!selected">{{ model.name }}</span
              ><span>실행 노드 {{ model.nodeName }}</span
              ><span>GPU {{ model.deviceName }}</span
              ><span v-if="!model.available">사용 불가</span>
            </p></KsFormItem
          >
          <KsFormItem label="이미지 파일" for="inference-image"
            ><input
              id="inference-image"
              ref="fileInput"
              type="file"
              accept="image/png,image/jpeg"
              aria-describedby="inference-image-help"
              :disabled="running"
              @change="choose"
          /></KsFormItem>
        </div>
        <p id="inference-image-help" class="native-note">
          PNG 또는 JPEG, 4 MiB 이하
        </p>
        <p v-if="!loading && loaded && !available.length" class="native-note">
          추론에 사용할 수 있는 모델이 없습니다.
        </p>
        <KsButton
          type="primary"
          nativeType="submit"
          :disabled="running || !modelId || !file"
          >추론 실행</KsButton
        ></KsForm
      >
    </section>
    <NativeFeedback :error="fileError || runError" />
    <div v-if="preview" class="inference-result">
      <section class="native-panel">
        <h2>이미지</h2>
        <NativeLoading :busy="running" label="추론 중…" />
        <figure>
          <div class="inference-frame">
            <img :src="preview" :alt="`${file?.name} 미리보기`" />
            <template v-if="result">
              <div
                v-for="(item, index) in result.detections"
                :key="index"
                class="inference-box"
                aria-hidden="true"
                :style="boxStyle(item, result.imageWidth, result.imageHeight)"
              >
                <span>{{ item.label }} {{ score(item.score) }}</span>
              </div>
            </template>
          </div>
          <figcaption v-if="result" class="native-note">
            원본 {{ result.imageWidth }}×{{ result.imageHeight }}px 기준 검출
            {{ result.detections.length }}개
          </figcaption>
        </figure>
      </section>
      <section v-if="result" class="native-panel native-detail">
        <h2>추론 결과</h2>
        <dl>
          <div>
            <dt>실행 위치</dt>
            <dd>{{ result.nodeName }} / {{ result.deviceName }}</dd>
          </div>
          <div>
            <dt>실행 제공자</dt>
            <dd>{{ result.provider }}</dd>
          </div>
          <div>
            <dt>추론 시간</dt>
            <dd>{{ result.inferenceMs.toFixed(1) }} ms</dd>
          </div>
        </dl>
        <p v-if="!result.detections.length" role="status">
          검출된 객체가 없습니다.
        </p>
        <div v-else class="inference-detections">
          <NativeRecords
            title="검출 객체"
            :rows="rows"
            :columns="[
              { key: 'label', label: '객체', width: 120 },
              { key: 'score', label: '신뢰도', width: 90 },
              { key: 'x', label: 'x', width: 70 },
              { key: 'y', label: 'y', width: 70 },
              { key: 'width', label: '너비', width: 70 },
              { key: 'height', label: '높이', width: 70 },
            ]"
            ><template #score="{ row }">{{ score(row.score) }}</template
            ><template v-for="key in ['x', 'y', 'width', 'height']" #[key]="{ row }" :key="key">{{
              Math.round(row[key])
            }}</template></NativeRecords
          >
        </div>
      </section>
    </div>
  </main>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  KsButton,
  KsForm,
  KsFormItem,
  KsOption,
  KsSelect,
} from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import { get, useAction, write, type Schema } from "./native-api";
import { boxStyle, imageProblem, toBase64 } from "./inference";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
import NativeRecords from "./NativeRecords.vue";

type Model = Schema["InferenceModel"];
type Prediction = Schema["InferenceResult"];

useRouteContext(computed(() => ({ title: "모델 추론" })));
const { busy: loading, error: modelError, act: loadModels } = useAction();
const { busy: running, error: runError, act: runAction } = useAction();
const models = ref<Model[]>([]),
  loaded = ref(false),
  modelId = ref(""),
  file = ref<File | null>(null),
  fileError = ref(""),
  preview = ref(""),
  result = ref<Prediction | null>(null);
const available = computed(() => models.value.filter((m) => m.available));
// The select truncates long labels; node and GPU stay readable below it.
const selected = computed(() =>
  models.value.find((m) => m.id === modelId.value),
);
// Without a selectable model, the configured identities stay visible as unavailable.
const targets = computed(() =>
  selected.value ? [selected.value] : models.value,
);
const rows = computed(
  () => result.value?.detections.map((d, id) => ({ id, ...d })) ?? [],
);
const modelLabel = (m: Model) =>
  `${m.name} · ${m.nodeName} / ${m.deviceName}${m.available ? "" : " · 사용 불가"}`;
const score = (value: number) => `${(value * 100).toFixed(1)}%`;

async function load() {
  models.value = (await get<Schema["InferenceModelPage"]>("inference/models")).items;
  loaded.value = true;
  if (!available.value.some((m) => m.id === modelId.value))
    modelId.value = available.value[0]?.id ?? "";
}
function choose(event: Event) {
  const chosen = (event.target as HTMLInputElement).files?.[0] ?? null;
  if (preview.value) URL.revokeObjectURL(preview.value);
  preview.value = "";
  fileError.value = chosen ? imageProblem(chosen) : "";
  file.value = chosen && !fileError.value ? chosen : null;
  if (file.value) preview.value = URL.createObjectURL(file.value);
}
// A result belongs to the exact model and image that produced it.
watch([modelId, file], () => {
  result.value = null;
  runError.value = "";
});
function submit() {
  const selected = file.value;
  if (!selected || !modelId.value) return;
  void runAction(async () => {
    result.value = null;
    const imageBase64 = toBase64(new Uint8Array(await selected.arrayBuffer()));
    result.value = await write<Prediction>("inference/predict", "POST", {
      modelId: modelId.value,
      imageBase64,
    } satisfies Schema["InferenceRequest"]);
  });
}
onMounted(() => loadModels(() => load()));
onBeforeUnmount(() => {
  if (preview.value) URL.revokeObjectURL(preview.value);
});
</script>
<style scoped>
.inference-result {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
  gap: var(--ks-spacing-4);
  align-items: start;
}
figure {
  margin: 0;
}
.inference-frame {
  position: relative;
  /* Room for the label of a box that starts at the top edge. */
  margin-top: var(--ks-spacing-5);
  width: fit-content;
  max-width: 100%;
}
.inference-frame img {
  display: block;
  max-width: 100%;
  max-height: 70vh;
  /* The runtime does not apply EXIF rotation; show the same encoded pixels the boxes refer to. */
  image-orientation: none;
}
.inference-box {
  position: absolute;
  box-sizing: border-box;
  border: 2px solid var(--ks-border-focus);
  pointer-events: none;
}
.inference-box span {
  position: absolute;
  left: -2px;
  bottom: 100%;
  padding: 0 var(--ks-spacing-1);
  background: var(--ks-border-focus);
  color: var(--ks-bg-base);
  font-size: var(--ks-font-size-xs);
  white-space: nowrap;
}
.inference-target {
  display: flex;
  flex-wrap: wrap;
  gap: 0 var(--ks-spacing-4);
  margin: var(--ks-spacing-2) 0 0;
  color: var(--ks-text-primary);
  font-size: var(--ks-font-size-sm);
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.inference-target span {
  min-width: 0;
}
input[type="file"] {
  min-height: 44px;
  max-width: 100%;
}
.inference-detections {
  max-height: 420px;
  overflow: auto;
}
@media (max-width: 768px) {
  .inference-result {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
