<template>
  <TopNavBar title="창고 지게차 안전 점검"
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
      <h2>Hawkeye 안전 점검</h2>
      <p class="native-note">
        고정 창고 샘플 이미지 1장을 Hawkeye 창고 안전 VLM에 보내 위험 범주, 한 줄 설명, 측정 시간을 저장합니다.
        샘플은 공식 데이터셋의 생성(합성) 이미지이며 실제 현장 CCTV가 아닙니다. 모델이 범주를 분명히 답하지 않으면
        실패로 기록하고 안전으로 간주하지 않습니다.
      </p>
      <dl>
        <div><dt>모델</dt><dd>{{ capability.model.name }}</dd></div>
        <div>
          <dt>출처</dt>
          <dd>
            <a :href="capability.model.repository" target="_blank" rel="noopener noreferrer">{{ capability.model.repository }}</a>
            @{{ capability.model.revision.slice(0, 8) }} · {{ capability.model.license }}
          </dd>
        </div>
        <div><dt>Ollama manifest</dt><dd class="hawkeye-sha">{{ capability.model.manifestDigest }}</dd></div>
        <div>
          <dt>데이터셋</dt>
          <dd>
            <a :href="capability.dataset.repository" target="_blank" rel="noopener noreferrer">{{ capability.dataset.name }}</a>
            @{{ capability.dataset.revision.slice(0, 8) }} · {{ capability.dataset.license }}
          </dd>
        </div>
        <div><dt>범주</dt><dd>{{ capability.categories.join(", ") }}</dd></div>
      </dl>
      <p v-if="!capability.enabled || !capability.runtime" class="native-notice" role="status">
        Hawkeye 런타임이 아직 배포·설정되지 않았습니다.
      </p>
      <div v-else class="hawkeye-runtime" aria-label="실행 대상">
        <h3>
          실행 대상
          <KsTag :type="capability.runtime.status === 'READY' ? 'success' : 'danger'">{{
            RUNTIME_STATUS[capability.runtime.status]
          }}</KsTag>
        </h3>
        <dl>
          <div><dt>노드</dt><dd>{{ capability.runtime.reported?.nodeName ?? "보고값 없음" }}</dd></div>
          <div><dt>현재 배치</dt><dd>{{ capability.runtime.reported?.processor ?? "보고값 없음" }}</dd></div>
          <div><dt>Ollama</dt><dd>{{ capability.runtime.reported?.ollamaVersion ?? "보고값 없음" }}</dd></div>
        </dl>
        <p v-if="capability.runtime.error" class="native-notice">{{ capability.runtime.error }}</p>
      </div>
      <fieldset class="hawkeye-samples" :disabled="active || starting">
        <legend>샘플 선택</legend>
        <div class="hawkeye-sample-grid">
        <label v-for="sample in capability.samples" :key="sample.id" class="hawkeye-sample" :class="{ chosen: sample.id === chosen }">
          <input v-model="chosen" type="radio" name="hawkeye-sample" :value="sample.id" />
          <img
            v-if="capability.runtime && !broken[sample.id]"
            :src="`/api/control-plane/scenarios/hawkeye-safety/samples/${sample.id}/image`"
            :alt="`${sample.id} 합성 창고 이미지`"
            width="320"
            height="180"
            @error="broken[sample.id] = true"
          />
          <span v-else class="hawkeye-noimage">미리보기 없음</span>
          <strong>{{ sample.id }}</strong>
          <span>참조 라벨 {{ sample.referenceLabel }} · 합성 이미지</span>
        </label>
        </div>
      </fieldset>
      <KsButton type="primary" :disabled="!canStart" @click="start">안전 점검 시작</KsButton>
      <p class="native-note">{{ startHelp }}</p>
    </section>
    <NativeFeedback :error="startError" />
    <section v-if="run" class="native-panel native-detail hawkeye-run">
      <div class="native-toolbar">
        <h2>실행 결과</h2>
        <KsTag :type="tone(run.state)">{{ STATE_LABELS[run.state].split(" · ")[0] }}</KsTag>
      </div>
      <p role="status" aria-live="polite" class="hawkeye-progress">{{ STATE_LABELS[run.state] }} · 샘플 {{ run.sampleId }}</p>
      <p v-if="run.failureCode" role="alert" class="native-notice">
        {{ run.failureCode }}{{ run.failureMessage ? ` · ${run.failureMessage}` : "" }}
      </p>
      <template v-if="run.result">
        <p class="hawkeye-category" :class="{ unread: run.result.category == null }">{{ categoryText(run.result) }}</p>
        <p v-if="run.result.explanation" class="hawkeye-explanation">{{ run.result.explanation }}</p>
        <dl>
          <div><dt>참조 비교</dt><dd>{{ referenceText(run.result, run.referenceLabel) }}</dd></div>
          <div><dt>추론 시간</dt><dd>{{ timingText(run.result) }}</dd></div>
          <div><dt>처리 위치</dt><dd>{{ run.target?.nodeName }} · {{ placementText(run.result) }}</dd></div>
        </dl>
      </template>
      <dl>
        <div><dt>실행 ID</dt><dd>{{ run.id }}</dd></div>
        <div><dt>시작 요청</dt><dd>{{ time(run.createdAt) }}</dd></div>
        <div><dt>종료</dt><dd>{{ time(run.finishedAt) }}</dd></div>
        <div v-if="run.target"><dt>모델 digest</dt><dd class="hawkeye-sha">{{ run.target.modelDigest }}</dd></div>
        <div v-if="run.result"><dt>입력 SHA-256</dt><dd class="hawkeye-sha">{{ run.result.inputSha256 }}</dd></div>
      </dl>
      <details v-if="run.result" class="hawkeye-raw">
        <summary>모델 원문 출력</summary>
        <pre>{{ run.result.rawOutput || "(빈 출력)" }}</pre>
      </details>
    </section>
    <section v-if="history.length || historyOffset > 0" class="native-panel hawkeye-history">
      <NativeRecords
        title="실행 기록"
        :rows="historyRows"
        :columns="[
          { key: 'created', label: '시작', width: 170 },
          { key: 'sample', label: '샘플', width: 130 },
          { key: 'state', label: '상태', width: 90 },
          { key: 'category', label: '모델 판정', width: 200 },
          { key: 'reference', label: '참조 라벨', width: 140 },
          { key: 'time', label: 'Ollama 처리', width: 110 },
          { key: 'processor', label: '배치', width: 90 },
        ]"
        :offset="historyOffset"
        :nextOffset="historyNext"
        :pageSize="PAGE"
        @page="page"
      />
    </section>
  </main>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { KsButton, KsTag } from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import { get, requestKey, time, useAction, usePoll, write, type Schema } from "./native-api";
import { categoryText, isActive, placementText, referenceText, STATE_LABELS, timingText } from "./hawkeye";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
import NativeRecords from "./NativeRecords.vue";

type Capability = Schema["HawkeyeSafetyScenario"];
type Run = Schema["HawkeyeSafetyRun"];
const BASE = "scenarios/hawkeye-safety";
const PAGE = 10;
const RUNTIME_STATUS: Record<string, string> = {
  READY: "준비됨",
  NOT_READY: "준비 안 됨",
  MISMATCH: "설정과 다른 모델·샘플",
  UNAVAILABLE: "연결 불가",
};

useRouteContext(computed(() => ({ title: "창고 지게차 안전 점검" })));
const { busy: loading, error: loadError, act: loadAll } = useAction();
const { busy: starting, error: startError, act: startAction } = useAction();
const capability = ref<Capability | null>(null),
  run = ref<Run | null>(null),
  history = ref<Run[]>([]),
  historyOffset = ref(0),
  historyNext = ref<number | null>(null),
  chosen = ref(""),
  pollError = ref("");
const broken = reactive<Record<string, boolean>>({});
// A start key survives retries of the same request and is replaced after an accepted run.
let startKey = requestKey(),
  polling = false,
  ticks = 0;

const active = computed(() => !!run.value && isActive(run.value.state));
const canStart = computed(
  () =>
    !!capability.value?.enabled &&
    capability.value.runtime?.status === "READY" &&
    !!chosen.value &&
    !active.value &&
    !starting.value,
);
const startHelp = computed(() => {
  if (active.value) return "실행 중에는 새 점검을 시작할 수 없습니다.";
  if (capability.value?.runtime && capability.value.runtime.status !== "READY")
    return "실행 대상이 준비되면 시작할 수 있습니다.";
  return "샘플 1장을 한 번 추론합니다. 결과는 새로고침 후에도 실행 기록에 남습니다.";
});
const historyRows = computed(() =>
  history.value.map((r) => ({
    id: r.id,
    created: time(r.createdAt),
    sample: r.sampleId,
    state: STATE_LABELS[r.state].split(" · ")[0],
    category: r.result ? categoryText(r.result) : "—",
    reference: r.referenceLabel,
    time: r.result ? `${r.result.inferenceMs.toFixed(1)} ms` : "—",
    processor: r.result?.processor ?? "—",
  })),
);
const tone = (state: string) =>
  state === "SUCCEEDED" ? "success" : ["FAILED", "INTERRUPTED"].includes(state) ? "danger" : "info";

async function loadHistory() {
  const page = await get<Schema["HawkeyeSafetyRunPage"]>(`${BASE}/runs?limit=${PAGE}&offset=${historyOffset.value}`);
  history.value = page.items;
  historyNext.value = page.nextOffset;
}
async function refresh() {
  capability.value = await get<Capability>(BASE);
  if (!chosen.value) chosen.value = capability.value.samples[0]?.id ?? "";
  const id =
    capability.value.activeRunId ??
    (await get<Schema["HawkeyeSafetyRunPage"]>(`${BASE}/runs?limit=1&offset=0`)).items[0]?.id;
  if (id) run.value = await get<Run>(`${BASE}/runs/${id}`);
  await loadHistory();
}
function start() {
  if (!canStart.value) return;
  void startAction(async () => {
    run.value = await write<Run>(`${BASE}/runs`, "POST", { sampleId: chosen.value }, startKey);
    startKey = requestKey();
    historyOffset.value = 0;
    await loadHistory();
  });
}
function page(offset: number) {
  historyOffset.value = offset;
  void loadAll(() => loadHistory());
}
onMounted(() => loadAll(() => refresh()));
// Non-overlapping, stopped on unmount by usePoll, and idle while the tab is hidden.
usePoll(async () => {
  if (polling || loading.value || document.hidden) return;
  polling = true;
  try {
    if (active.value && run.value) {
      run.value = await get<Run>(`${BASE}/runs/${run.value.id}`);
      // The finished run also changes the capability's active run and the history.
      if (!active.value) {
        capability.value = await get<Capability>(BASE);
        await loadHistory();
      }
    } else if (++ticks % 5 === 0) await refresh();
    pollError.value = "";
  } catch (cause) {
    pollError.value =
      "자동 갱신 실패 · 마지막으로 조회한 상태를 표시합니다. " +
      (cause instanceof Error ? cause.message : "조회 실패");
  } finally {
    polling = false;
  }
}, 1000);
</script>
<style scoped>
.hawkeye-runtime {
  border: 1px solid var(--ks-border-default);
  border-radius: var(--ks-radius-base);
  padding: var(--ks-spacing-4);
  margin: var(--ks-spacing-4) 0;
}
.hawkeye-runtime h3 {
  display: flex;
  align-items: center;
  gap: var(--ks-spacing-3);
  margin-top: 0;
}
.hawkeye-sha {
  font-family: monospace;
  font-size: var(--ks-font-size-xs);
  overflow-wrap: anywhere;
}
.hawkeye-samples {
  border: 0;
  padding: 0;
  margin: 0 0 var(--ks-spacing-4);
  min-width: 0;
}
.hawkeye-samples legend {
  font-size: var(--ks-font-size-md);
  font-weight: var(--ks-font-weight-bold);
  padding: 0;
  margin-bottom: var(--ks-spacing-3);
}
.hawkeye-sample-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(14rem, 1fr));
  gap: var(--ks-spacing-4);
}
.hawkeye-sample {
  display: grid;
  gap: var(--ks-spacing-2);
  border: 1px solid var(--ks-border-default);
  border-radius: var(--ks-radius-base);
  padding: var(--ks-spacing-3);
  cursor: pointer;
}
.hawkeye-sample.chosen {
  border-color: var(--ks-border-focus);
  outline: 1px solid var(--ks-border-focus);
}
.hawkeye-sample img {
  width: 100%;
  height: auto;
  aspect-ratio: 1024 / 571;
  object-fit: cover;
  border-radius: var(--ks-radius-base);
}
.hawkeye-noimage {
  display: grid;
  place-items: center;
  aspect-ratio: 16 / 9;
  background: var(--ks-bg-base);
  color: var(--ks-text-secondary);
}
.hawkeye-progress {
  font-size: var(--ks-font-size-md);
  margin: var(--ks-spacing-3) 0;
}
.hawkeye-category {
  font-size: var(--ks-font-size-lg);
  font-weight: var(--ks-font-weight-bold);
  margin: var(--ks-spacing-2) 0;
}
.hawkeye-category.unread {
  color: var(--ks-text-error);
}
.hawkeye-explanation {
  margin: 0 0 var(--ks-spacing-4);
  overflow-wrap: anywhere;
}
.hawkeye-raw pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.hawkeye-history {
  max-width: 100%;
  overflow-x: auto;
}
</style>
