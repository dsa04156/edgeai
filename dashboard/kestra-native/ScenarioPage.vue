<template>
  <TopNavBar title="GPU/NPU 이미지 비교"
    ><template #actions
      ><KsButton :disabled="loading" @click="loadAll(() => refresh())"
        >새로고침</KsButton
      ></template
    ></TopNavBar
  >
  <main class="native-page">
    <NativeLoading :busy="loading && !capability" label="시나리오를 불러오는 중…" />
    <NativeFeedback :error="loadError || pollError" />
    <section v-if="capability" class="native-panel native-detail">
      <h2>비교 시나리오</h2>
      <p class="native-note">
        서버가 고정 비교 데이터셋의 앞쪽부터 같은 순서·같은 입력 텐서를 GPU와
        NPU에 한 장씩 보내고, 장치별 분류 결과와 시간을 저장합니다. 원본 이미지
        파일은 배포에 포함되지 않아 미리보기를 제공하지 않으며, 미리 전처리된
        텐서만 사용합니다.
      </p>
      <dl>
        <div><dt>데이터셋</dt><dd>{{ capability.dataset.name }}</dd></div>
        <div><dt>버전</dt><dd>{{ capability.dataset.version }}</dd></div>
        <div><dt>이미지 수</dt><dd>{{ capability.dataset.imageCount }}장</dd></div>
        <div><dt>전처리</dt><dd>{{ capability.dataset.preprocessing }}</dd></div>
        <div>
          <dt>정답 라벨</dt>
          <dd>{{ capability.dataset.labelSource ?? "사용하지 않음 · 장치 간 일치만 비교" }}</dd>
        </div>
        <div><dt>manifest SHA-256</dt><dd class="scenario-sha">{{ capability.dataset.manifestSha256 }}</dd></div>
      </dl>
      <p v-if="!capability.enabled" class="native-notice" role="status">
        GPU/NPU 비교 런타임이 아직 배포·설정되지 않았습니다.
      </p>
      <div v-else class="native-grid">
        <section
          v-for="target in capability.targets"
          :key="target.id"
          class="scenario-target"
          :aria-label="`${target.id} 대상`"
        >
          <h3>
            {{ target.id }}
            <KsTag :type="target.status === 'READY' ? 'success' : 'danger'">{{
              TARGET_STATUS[target.status]
            }}</KsTag>
          </h3>
          <dl>
            <div><dt>모델</dt><dd>{{ target.modelName }}</dd></div>
            <div><dt>장치</dt><dd>{{ target.reported?.deviceName ?? "보고값 없음" }}</dd></div>
            <div><dt>노드</dt><dd>{{ target.reported?.nodeName ?? "보고값 없음" }}</dd></div>
            <div><dt>정밀도</dt><dd>{{ target.reported?.precision ?? "보고값 없음" }}</dd></div>
            <div><dt>실행 환경</dt><dd>{{ target.reported?.runtime ?? "보고값 없음" }}</dd></div>
            <div><dt>제공자</dt><dd>{{ target.expectedProvider }}</dd></div>
            <div><dt>모델 SHA-256</dt><dd class="scenario-sha">{{ target.expectedModelSha256 }}</dd></div>
          </dl>
        </section>
      </div>
      <p v-if="unlikePrecision" class="native-notice">
        두 대상의 모델 파일과 정밀도가 서로 다릅니다(GPU는 TFLite에서 변환한 FP32
        ONNX, NPU는 별도로 컴파일된 HEF). 가중치·양자화가 같다고 확인하지 않았으므로
        결과·시간 차이를 장치 성능만의 차이로 해석하지 마세요.
      </p>
      <KsForm labelPosition="top" @submit.prevent="start">
        <KsFormItem label="이미지 수" for="scenario-count">
          <KsInput
            id="scenario-count"
            v-model="countText"
            type="number"
            :min="1"
            :max="capability.maxImageCount"
            :disabled="!capability.enabled || starting || active"
            aria-describedby="scenario-count-help"
          />
        </KsFormItem>
        <p id="scenario-count-help" class="native-note">
          {{ countProblem || `앞쪽부터 1~${capability.maxImageCount}장. 실행 중에는 새 비교를 시작할 수 없습니다.` }}
        </p>
        <KsButton type="primary" nativeType="submit" :disabled="!canStart"
          >비교 시작</KsButton
        >
      </KsForm>
    </section>
    <NativeFeedback :error="actionError" />
    <section v-if="run" class="native-panel native-detail">
      <div class="native-toolbar">
        <h2>실행 결과</h2>
        <KsTag :type="tone(run.state)">{{ STATE_LABELS[run.state].split(" · ")[0] }}</KsTag>
        <KsButton
          v-if="run.state === 'QUEUED' || run.state === 'RUNNING'"
          type="danger"
          :disabled="stopping"
          @click="stop"
          >중지</KsButton
        >
      </div>
      <p role="status" aria-live="polite" class="scenario-progress">
        {{ STATE_LABELS[run.state] }} · 전송 {{ run.dispatchedCount }}/{{ run.requestedCount }}
      </p>
      <p v-if="run.failureMessage" role="alert" class="native-notice">
        {{ run.failureCode }} · {{ run.failureMessage }}
      </p>
      <p class="native-note">{{ coverageNote(run, run.comparison) }}</p>
      <dl>
        <div><dt>실행 ID</dt><dd>{{ run.id }}</dd></div>
        <div><dt>시작 요청</dt><dd>{{ time(run.createdAt) }}</dd></div>
        <div><dt>종료</dt><dd>{{ time(run.finishedAt) }}</dd></div>
      </dl>
      <template v-if="run.comparison">
        <div class="scenario-targets">
          <NativeRecords
            title="장치별 처리"
            :rows="rows"
            :columns="[
              { key: 'target', label: '대상', width: 70 },
              { key: 'processed', label: '처리', width: 150 },
              { key: 'roundTrip', label: '평균 왕복 시간 (성공 호출, API 측정)', width: 160 },
              { key: 'compute', label: '런타임 계산 시간 평균', width: 200 },
              { key: 'callWallRate', label: '처리량 (실패 포함 전체 호출 시간 기준)', width: 170 },
              { key: 'computeRate', label: '처리량 (성공 호출의 런타임 계산 시간 기준)', width: 170 },
              { key: 'accuracy', label: '라벨 정확도 (양쪽 성공 기준)', width: 160 },
            ]"
          />
        </div>
        <p class="native-note">
          top-1 일치 {{ run.comparison.top1AgreementCount }}/{{ run.comparison.pairedCount }}
          · 전체 실행 시간 {{ seconds(run.comparison.runWallSeconds) }}
          · 양쪽 성공 처리량 {{ perSecond(run.comparison.pairedImagesPerRunWallSecond) }}
        </p>
        <p class="native-note">
          런타임 계산 시간은 각 런타임이 측정한 정규화·전송·추론 완료 대기 시간이고,
          왕복 시간은 API 서버가 측정한 HTTP 호출 전체 시간입니다. 호출 시간 기준 처리량의
          분모에는 실패·시간 초과 호출도 포함하고, 계산 시간·왕복 평균은 성공 호출만 사용합니다. 두 대상은 이미지마다
          동시에 호출되며 비교·정확도는 양쪽 모두 성공한 이미지만 사용합니다. 점수는
          런타임 원시 출력이라 대상 간 크기를 비교하지 않습니다.
        </p>
      </template>
      <!-- An emptied later page keeps its navigation so the user can go back. -->
      <div v-if="images.length || resultOffset > 0" class="scenario-images">
        <NativeRecords
          title="이미지별 결과"
          :rows="images"
          :columns="[
            { key: 'index', label: '#', width: 50 },
            { key: 'label', label: '정답 라벨', width: 150 },
            { key: 'gpu', label: 'GPU top-1', width: 150 },
            { key: 'npu', label: 'NPU top-1', width: 150 },
            { key: 'agreement', label: '일치', width: 70 },
            { key: 'gpuMs', label: 'GPU 계산', width: 90 },
            { key: 'npuMs', label: 'NPU 계산', width: 90 },
          ]"
          :offset="resultOffset"
          :nextOffset="resultNext"
          :pageSize="PAGE"
          @page="page"
        />
      </div>
    </section>
  </main>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { KsButton, KsForm, KsFormItem, KsInput, KsTag } from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import { get, requestKey, time, useAction, usePoll, write, type Schema } from "./native-api";
import {
  className,
  coverageNote,
  imageCountProblem,
  isActive,
  outcomeText,
  precisionDiffers,
  STATE_LABELS,
  targetRows,
} from "./scenario";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
import NativeRecords from "./NativeRecords.vue";

type Capability = Schema["ImageComparisonScenario"];
type Run = Schema["ImageComparisonRun"];
type Result = Schema["ImageComparisonImageResult"];
const BASE = "scenarios/image-comparison";
const PAGE = 20;
const TARGET_STATUS: Record<string, string> = {
  READY: "준비됨",
  NOT_READY: "준비 안 됨",
  MISMATCH: "설정과 다른 모델·데이터",
  UNAVAILABLE: "연결 불가",
};

useRouteContext(computed(() => ({ title: "GPU/NPU 이미지 비교" })));
const { busy: loading, error: loadError, act: loadAll } = useAction();
const { busy: starting, error: startError, act: startAction } = useAction();
const { busy: stopping, error: stopError, act: stopAction } = useAction();
const capability = ref<Capability | null>(null),
  run = ref<Run | null>(null),
  results = ref<Result[]>([]),
  resultOffset = ref(0),
  resultNext = ref<number | null>(null),
  countText = ref("100"),
  pollError = ref("");
// A start key survives retries of the same request and is replaced after an accepted run.
let startKey = requestKey(),
  generation = 0,
  polling = false,
  ticks = 0;

const actionError = computed(() => startError.value || stopError.value);
const active = computed(() => !!run.value && isActive(run.value.state));
const count = computed(() => (countText.value.trim() === "" ? undefined : Number(countText.value)));
const countProblem = computed(() =>
  capability.value ? imageCountProblem(count.value, capability.value.maxImageCount) : "",
);
const canStart = computed(
  () =>
    !!capability.value?.enabled &&
    capability.value.targets.length === 2 &&
    capability.value.targets.every((t) => t.status === "READY") &&
    !countProblem.value &&
    !active.value &&
    !starting.value,
);
const unlikePrecision = computed(() => {
  const reported = capability.value?.targets.map((t) => t.reported).filter((r) => r != null) ?? [];
  return reported.length === 2 && precisionDiffers(reported);
});
const rows = computed(() => (run.value?.comparison ? targetRows(run.value.comparison) : []));
const images = computed(() =>
  results.value.map((r) => ({
    id: r.index,
    index: r.index,
    label: className(r.label),
    gpu: outcomeText(r.gpu, run.value?.state ?? ""),
    npu: outcomeText(r.npu, run.value?.state ?? ""),
    agreement: r.top1Agreement == null ? "—" : r.top1Agreement ? "일치" : "불일치",
    gpuMs: r.gpu?.inferenceMs == null ? "—" : `${r.gpu.inferenceMs.toFixed(2)} ms`,
    npuMs: r.npu?.inferenceMs == null ? "—" : `${r.npu.inferenceMs.toFixed(2)} ms`,
  })),
);

const tone = (state: string) =>
  state === "SUCCEEDED" ? "success" : ["FAILED", "INTERRUPTED"].includes(state) ? "danger" : isActive(state) ? "info" : "warning";
const seconds = (value: number | null) => (value == null ? "—" : `${value.toFixed(2)} s`);
const perSecond = (value: number | null) => (value == null ? "—" : `${value.toFixed(1)} 장/s`);

async function loadRun(id: string) {
  const current = ++generation;
  const [detail, page] = await Promise.all([
    get<Run>(`${BASE}/runs/${id}`),
    get<Schema["ImageComparisonResultPage"]>(`${BASE}/runs/${id}/results?limit=${PAGE}&offset=${resultOffset.value}`),
  ]);
  // A response for a run or page the user has since left is dropped.
  if (current !== generation) return;
  run.value = detail;
  results.value = page.items;
  resultNext.value = page.nextOffset;
}
async function refresh() {
  capability.value = await get<Capability>(BASE);
  const id =
    capability.value.activeRunId ??
    (await get<Schema["ImageComparisonRunPage"]>(`${BASE}/runs?limit=1&offset=0`)).items[0]?.id;
  if (id) {
    if (id !== run.value?.id) resultOffset.value = 0;
    await loadRun(id);
  }
}
function start() {
  if (!canStart.value) return;
  void startAction(async () => {
    const created = await write<Run>(`${BASE}/runs`, "POST", { imageCount: count.value }, startKey);
    startKey = requestKey();
    resultOffset.value = 0;
    run.value = created;
    results.value = [];
    await loadRun(created.id);
  });
}
function stop() {
  const id = run.value?.id;
  if (!id) return;
  void stopAction(async () => {
    run.value = await write<Run>(`${BASE}/runs/${id}/stop`, "POST", {});
    await loadRun(id);
  });
}
function page(offset: number) {
  const id = run.value?.id;
  if (!id) return;
  resultOffset.value = offset;
  void loadAll(() => loadRun(id));
}
onMounted(() => loadAll(() => refresh()));
// Non-overlapping, stopped on unmount by usePoll, and idle while the tab is hidden.
usePoll(async () => {
  if (polling || loading.value || document.hidden) return;
  polling = true;
  try {
    if (active.value && run.value) {
      await loadRun(run.value.id);
      // The finished state also changes the capability's active run.
      if (!active.value) capability.value = await get<Capability>(BASE);
    } else if (++ticks % 5 === 0) await refresh();
    pollError.value = "";
  } catch (cause) {
    pollError.value =
      "자동 갱신 실패 · 마지막으로 조회한 상태를 표시합니다. " +
      (cause instanceof Error ? cause.message : "조회 실패");
  } finally {
    polling = false;
  }
}, 2000);
</script>
<style scoped>
.scenario-target {
  border: 1px solid var(--ks-border-default);
  border-radius: var(--ks-radius-base);
  padding: var(--ks-spacing-4);
}
.scenario-target h3 {
  display: flex;
  align-items: center;
  gap: var(--ks-spacing-3);
  margin-top: 0;
}
.scenario-sha {
  font-family: monospace;
  font-size: var(--ks-font-size-xs);
}
.scenario-progress {
  font-size: var(--ks-font-size-md);
  margin: var(--ks-spacing-3) 0;
}
.scenario-targets,
.scenario-images {
  max-width: 100%;
  overflow-x: auto;
}
.scenario-images {
  margin-top: var(--ks-spacing-4);
}
</style>
