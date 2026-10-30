<template>
  <section class="profiling" aria-labelledby="model-requirements">
    <div class="native-toolbar profiling-head">
      <h3 id="model-requirements">자원 요구량</h3>
      <span v-if="availabilityKey" :class="['profiling-badge', `profiling-badge--${availabilityKey}`]">{{ AVAILABILITY_LABEL[availabilityKey] }}</span>
      <KsButton
        v-if="!active && !loading"
        :type="profile || pending ? 'default' : 'primary'"
        :disabled="busy || !!cleaning"
        class="profiling-start"
        @click="openStart"
        >{{ runs.length ? "다시 측정" : "측정 시작" }}</KsButton
      >
    </div>
    <NativeFeedback :error="loadError || actionError" />
    <p v-if="cleaning && !active" class="native-note" role="status">
      직전 측정의 임시 Pod를 정리하는 중입니다. 정리가 끝나면 다시 측정할 수 있습니다.
    </p>
    <NativeLoading :busy="loading && !runs.length" label="측정 기록을 불러오는 중…" />

    <p v-if="!loading && !loadError && !runs.length && !profile" class="native-note">
      등록한 이미지를 시험 노드에서 임시로 실행하고 실제 추론 부하를 주는 동안 CPU·메모리를 측정합니다. 측정이 성공하고 요구량을 확정하면 사용 가능합니다.
    </p>

    <!-- A run in progress -->
    <div v-if="active" class="profiling-block" role="status" aria-live="polite" aria-label="진행 중인 측정">
      <div class="native-toolbar profiling-head">
        <strong>{{ STATE_LABEL[active.state] }}</strong>
        <span class="native-note">{{ active.message }}</span>
        <KsButton :disabled="busy || !!active.cancelRequestedAt" class="profiling-start" @click="cancel(active)">측정 취소</KsButton>
      </div>
      <dl class="profiling-dl">
        <div><dt>시험 노드</dt><dd>{{ active.nodeName }} · {{ active.nodeArchitecture }}</dd></div>
        <div><dt>시험 할당</dt><dd>{{ allocationText(active.allocation) }}</dd></div>
        <div><dt>부하</dt><dd><code>{{ loadText(active.load) }}</code> · {{ active.durationSeconds }}초</dd></div>
        <div>
          <dt>측정 구간</dt>
          <dd>{{ active.window.start ? `${time(active.window.start)} 시작` : "부하 시작 전" }}</dd>
        </div>
        <div v-if="progress">
          <dt>요청</dt>
          <dd>{{ progress.requests ?? 0 }}건 · 성공 {{ progress.succeeded ?? 0 }} · 실패 {{ progress.failed ?? 0 }}</dd>
        </div>
      </dl>
      <p v-if="profile" class="native-note">측정이 끝날 때까지 확정된 r{{ profile.revision }} 요구량을 그대로 사용합니다.</p>
    </div>

    <!-- The latest run ended without a usable result -->
    <div v-else-if="latest && latest.state !== 'SUCCEEDED'" class="profiling-block">
      <p>
        <span :class="['profiling-badge', `profiling-badge--${latest.state}`]">{{ STATE_LABEL[latest.state] }}</span>
        <template v-if="latest.failure">{{ FAILURE_LABEL[latest.failure.code] ?? latest.failure.code }} — {{ latest.failure.message }}</template>
        <template v-else>{{ time(latest.finishedAt) }}에 취소했습니다.</template>
      </p>
      <p v-if="profile" class="native-note">확정된 r{{ profile.revision }} 요구량은 그대로 유지됩니다.</p>
    </div>

    <!-- A successful run waiting to be confirmed -->
    <form v-if="pending && pending.result" class="profiling-block" aria-label="측정 결과 확인과 요구량 확정" @submit.prevent="confirm">
      <p>
        <span class="profiling-badge profiling-badge--SUCCEEDED">측정 성공</span>
        {{ pending.nodeName }} · {{ allocationText(pending.allocation) }} · {{ time(pending.window.start) }} ~ {{ time(pending.window.end) }}
      </p>
      <p v-if="evidence" class="native-note">
        <template v-if="evidence.type === 'HTTP'"
          >부하 근거: 요청 {{ evidence.requests ?? 0 }}건 · 성공률 {{ percent(evidence.successRatio) }} · 지연 p95
          {{ evidence.latencyMillis?.p95 ?? "—" }}ms · 초당 {{ evidence.throughputPerSecond ?? "—" }}건</template
        ><template v-else>부하 근거: 명령 종료 코드 {{ evidence.exitCode ?? "—" }} · 실행 {{ evidence.runSeconds ?? "—" }}초<template v-if="evidence.note"> · {{ evidence.note }}</template></template>
      </p>
      <fieldset v-for="row in confirmRows" :key="row.id" class="profiling-range">
        <legend>
          {{ row.label }} <span class="native-note">{{ REQUIREMENT_BASIS_LABEL[basis[row.id]] }}</span>
        </legend>
        <p class="native-note profiling-evidence">
          관측 평균 / p95 / 최대 {{ formatValue(row.usage?.average, row.usage?.unit ?? "") }} / {{ formatValue(row.usage?.p95, row.usage?.unit ?? "") }} /
          {{ formatValue(row.usage?.max, row.usage?.unit ?? "") }} · 표본 {{ row.usage?.samples ?? 0 }}<br />추천 요청 {{ row.recommended }} · 최대
          {{ row.recommendedMax }}
        </p>
        <div class="profiling-grid">
          <KsFormItem v-for="field in rangeFields" :key="field" :label="field === 'request' ? '요청' : `${fieldLabel[field]}(선택)`" labelPosition="top"
            ><KsInput
              v-model="confirmation[row.id][field]"
              :disabled="busy"
              :aria-label="`${row.label} ${fieldLabel[field]}`"
              :placeholder="field === 'min' ? '미지정' : row.placeholder"
              spellcheck="false"
          /></KsFormItem>
        </div>
      </fieldset>
      <p v-if="pending.allocation.accelerator" class="native-note">
        가속기 {{ acceleratorText(pending.allocation.accelerator.resourceName, pending.allocation.accelerator.count) }} · 시험 할당 그대로 확정합니다. 다른 가속기
        구성은 다시 측정하세요. {{ ACCELERATOR_UNIT_NOTE }}
      </p>
      <p v-if="pendingDevice" :class="['native-note', 'profiling-device', `profiling-device--${pendingDevice.status}`]">
        <strong>{{ DEVICE_STATUS_LABEL[pendingDevice.status] ?? pendingDevice.status }}</strong> — {{ pendingDevice.reason }}
      </p>
      <ul v-if="pending.result.notices?.length" class="profiling-notices" aria-label="측정 결과 주의">
        <li v-for="n in pending.result.notices" :key="n">{{ n }}</li>
      </ul>
      <details v-if="pending.result.devices?.length" class="model-guide">
        <summary>장치 관측 {{ pending.result.devices.length }}개</summary>
        <ul class="native-note">
          <li v-for="d in pending.result.devices" :key="`${d.node}/${d.device}`">
            {{ d.kind }} {{ d.model ?? d.device }} · {{ d.node }} · {{ ATTRIBUTION_LABEL[d.attribution] ?? d.attribution }} · 사용률 평균/최대
            {{ formatValue(d.utilization?.average, "percent") }}/{{ formatValue(d.utilization?.max, "percent") }}
            <template v-if="d.reason"> · {{ d.reason }}</template>
          </li>
        </ul>
      </details>
      <details class="model-guide">
        <summary>추천 근거</summary>
        <ul class="native-note">
          <li v-for="r in pending.result.recommendation?.rationale ?? []" :key="r">{{ r }}</li>
        </ul>
      </details>
      <div v-if="pending.result.limitations?.length">
        <strong class="profiling-subhead">관측 한계</strong>
        <ul class="native-note profiling-limits">
          <li v-for="l in pending.result.limitations" :key="l">{{ l }}</li>
        </ul>
      </div>
      <div class="native-toolbar">
        <KsButton type="primary" nativeType="submit" :disabled="busy" :loading="confirming">요구량 확정</KsButton>
      </div>
    </form>

    <!-- The confirmed requirement that makes the model usable -->
    <template v-if="profile">
      <p class="native-note">
        r{{ profile.revision }} · {{ time(profile.createdAt) }} 확정 · {{ profile.scope.nodeName }}({{ profile.scope.nodeArchitecture }}) ·
        {{ allocationText(profile.scope.allocation) }} · {{ profile.scope.loadType === "HTTP" ? "HTTP 추론 부하" : "벤치마크 명령" }}
      </p>
      <div class="profiling-scroll">
        <table class="profiling-table" aria-label="확정 자원 요구량">
          <thead>
            <tr><th scope="col">자원</th><th scope="col">최소</th><th scope="col">요청</th><th scope="col">최대</th><th scope="col">근거</th></tr>
          </thead>
          <tbody>
            <tr>
              <th scope="row">CPU</th>
              <td>{{ optional(profile.requirements.cpu.minimumMillicores, cpuQuantity) }}</td>
              <td>{{ cpuQuantity(profile.requirements.cpu.requestMillicores) }}</td>
              <td>{{ optional(profile.requirements.cpu.maximumMillicores, cpuQuantity) }}</td>
              <td>{{ REQUIREMENT_BASIS_LABEL[profile.requirements.cpu.basis] }}</td>
            </tr>
            <tr>
              <th scope="row">메모리</th>
              <td>{{ optional(profile.requirements.memory.minimumBytes, memoryQuantity) }}</td>
              <td>{{ memoryQuantity(profile.requirements.memory.requestBytes) }}</td>
              <td>{{ optional(profile.requirements.memory.maximumBytes, memoryQuantity) }}</td>
              <td>{{ REQUIREMENT_BASIS_LABEL[profile.requirements.memory.basis] }}</td>
            </tr>
            <tr v-for="a in profile.requirements.accelerators" :key="a.resourceName">
              <th scope="row">{{ a.resourceName }}</th>
              <td>—</td>
              <td>{{ a.count }} {{ acceleratorUnit(a.resourceName) }}</td>
              <td>—</td>
              <td>{{ REQUIREMENT_BASIS_LABEL[a.basis] }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <details v-if="profile.limitations.length" class="model-guide">
        <summary>사용 범위·한계</summary>
        <ul class="native-note">
          <li v-for="l in profile.limitations" :key="l">{{ l }}</li>
        </ul>
      </details>
    </template>

    <details v-if="runs.length" class="model-guide">
      <summary>측정 기록 {{ runs.length }}건</summary>
      <div class="profiling-scroll">
        <table class="profiling-table" aria-label="측정 기록">
          <thead>
            <tr><th scope="col">시작</th><th scope="col">상태</th><th scope="col">노드 · 할당</th><th scope="col">부하</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in runs" :key="r.id">
              <td>{{ time(r.createdAt) }}</td>
              <td>
                <span :class="['profiling-badge', `profiling-badge--${r.state}`]">{{ STATE_LABEL[r.state] }}</span>
                <div v-if="r.failure" class="native-note">{{ FAILURE_LABEL[r.failure.code] ?? r.failure.code }}</div>
                <div v-if="r.cleanup.state === 'PENDING'" class="native-note">임시 Pod 정리 중</div>
              </td>
              <td>{{ r.nodeName }}<div class="native-note">{{ allocationText(r.allocation) }}</div></td>
              <td><code>{{ loadText(r.load) }}</code></td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>
  </section>

  <KsDrawer
    v-model="starting"
    title="자원 측정 시작"
    class="native-dialog"
    :beforeClose="closeStart"
    :showClose="!busy"
    :closeOnClickModal="!busy"
    :closeOnPressEscape="!busy"
    @opened="startOpened"
    @closed="startClosed"
  >
    <NativeLoading :busy="optionsLoading" label="측정 조건을 불러오는 중…" />
    <NativeFeedback :error="optionsError" />
    <NativeFeedback v-if="options && !options.enabled" :error="DISABLED_LABEL[options.disabledReason ?? ''] ?? '지금은 측정할 수 없습니다.'" />
    <KsForm v-if="options" id="model-profiling-form" labelPosition="top" size="large" class="native-form" @submit.prevent="start">
      <p class="native-note">
        <code>{{ options.image.reference }}</code> · {{ options.image.platform }}을(를) 임시 Pod로 실행해 아래 부하를 주는 동안 CPU·메모리를 관측합니다. 끝나면 Pod를
        정리합니다.
      </p>
      <h2>시험 노드와 할당</h2>
      <KsFormItem label="시험 노드" required
        ><KsSelect v-model="draft.nodeName" :disabled="busy" aria-label="시험 노드" placeholder="노드 선택" @change="draft.acceleratorResource = ''"
          ><KsOption
            v-for="n in options.nodes"
            :key="n.name"
            :value="n.name"
            :disabled="!n.compatible || n.busy || n.status !== 'READY'"
            :label="nodeLabel(n)" /></KsSelect
      ></KsFormItem>
      <div class="native-grid">
        <KsFormItem label="CPU 할당" required><KsInput v-model="draft.cpu" :disabled="busy" aria-label="CPU 할당" placeholder="예 2 또는 2000m" spellcheck="false" /></KsFormItem>
        <KsFormItem label="메모리 할당" required><KsInput v-model="draft.memory" :disabled="busy" aria-label="메모리 할당" placeholder="예 4Gi" spellcheck="false" /></KsFormItem>
      </div>
      <div v-if="node?.accelerators.length" class="native-grid">
        <KsFormItem label="가속기"
          ><KsSelect v-model="draft.acceleratorResource" :disabled="busy" aria-label="가속기"
            ><KsOption value="" label="사용 안 함" /><KsOption
              v-for="a in node.accelerators"
              :key="a.resourceName"
              :value="a.resourceName"
              :disabled="a.allocatableCount < 1"
              :label="`${a.resourceName} · 노드 용량 ${a.allocatableCount} ${acceleratorUnit(a.resourceName)}`" /></KsSelect
        ></KsFormItem>
        <KsFormItem v-if="draft.acceleratorResource" :label="`가속기 할당 수(${acceleratorUnit(draft.acceleratorResource)})`"
          ><KsInput v-model="draft.acceleratorCount" :disabled="busy" aria-label="가속기 할당 수" inputmode="numeric"
        /></KsFormItem>
      </div>
      <p v-if="draft.acceleratorResource" class="native-note">{{ ACCELERATOR_UNIT_NOTE }}</p>

      <h2>추론 부하</h2>
      <KsFormItem label="부하 방식"
        ><KsSegmented v-model="draft.loadType" :options="loadTypes" :disabled="busy" aria-label="부하 방식"
      /></KsFormItem>
      <template v-if="draft.loadType === 'HTTP'">
        <div class="profiling-request">
          <KsFormItem label="방식"
            ><KsSelect v-model="draft.method" :disabled="busy" aria-label="요청 방식"
              ><KsOption v-for="m in options.limits.methods" :key="m" :value="m" :label="m" /></KsSelect
          ></KsFormItem>
          <KsFormItem label="포트" required><KsInput v-model="draft.port" :disabled="busy" aria-label="포트" inputmode="numeric" placeholder="예 8080" /></KsFormItem>
          <KsFormItem label="경로" required><KsInput v-model="draft.path" :disabled="busy" aria-label="요청 경로" spellcheck="false" placeholder="/predict" /></KsFormItem>
        </div>
        <template v-if="draft.method === 'POST'">
          <KsFormItem label="본문 형식"
            ><KsSelect v-model="draft.contentType" :disabled="busy" aria-label="본문 형식"
              ><KsOption v-for="c in options.limits.contentTypes" :key="c" :value="c" :label="c" /></KsSelect
          ></KsFormItem>
          <KsFormItem v-if="draft.contentType !== 'application/octet-stream'" label="추론 요청 본문" required
            ><KsInput
              v-model="draft.body"
              :disabled="busy"
              type="textarea"
              :rows="4"
              aria-label="추론 요청 본문"
              spellcheck="false"
              placeholder="모델이 실제로 추론하는 입력 예 {&quot;instances&quot;:[…]}"
          /></KsFormItem>
          <template v-else>
            <KsFormItem label="추론 요청 본문(Base64)" required
              ><KsInput
                v-model="draft.bodyBase64"
                :disabled="busy"
                type="textarea"
                :rows="4"
                aria-label="추론 요청 본문(Base64)"
                aria-describedby="profiling-binary-note"
                spellcheck="false"
                placeholder="실제 추론 입력 파일의 바이트를 표준 Base64로 입력하거나 아래에서 파일을 고르세요"
            /></KsFormItem>
            <div class="native-toolbar profiling-binary">
              <label :class="['profiling-file', { 'is-disabled': busy }]"
                >파일에서 불러오기<input type="file" :disabled="busy" aria-label="추론 입력 파일" @change="readBinary"
              /></label>
              <span id="profiling-binary-note" class="native-note" role="status">{{ binaryNote }}</span>
            </div>
          </template>
        </template>
        <div class="native-grid">
          <KsFormItem label="동시 요청"><KsInput v-model="draft.concurrency" :disabled="busy" aria-label="동시 요청" inputmode="numeric" /></KsFormItem>
          <KsFormItem label="측정 시간(초)"><KsInput v-model="draft.durationSeconds" :disabled="busy" aria-label="측정 시간(초)" inputmode="numeric" /></KsFormItem>
        </div>
      </template>
      <template v-else>
        <KsFormItem label="벤치마크 명령(한 줄에 하나)" required
          ><KsInput v-model="draft.command" :disabled="busy" type="textarea" :rows="2" aria-label="벤치마크 명령" spellcheck="false" placeholder="예 python3"
        /></KsFormItem>
        <KsFormItem label="인자(한 줄에 하나)"
          ><KsInput v-model="draft.args" :disabled="busy" type="textarea" :rows="2" aria-label="벤치마크 인자" spellcheck="false" placeholder="예 /opt/bench.py"
        /></KsFormItem>
        <KsFormItem label="측정 시간(초)"><KsInput v-model="draft.durationSeconds" :disabled="busy" aria-label="측정 시간(초)" inputmode="numeric" /></KsFormItem>
        <p class="native-note">명령은 측정 시간 이상 실제 추론을 반복하고 0으로 종료해야 합니다. 정상 종료만으로는 부하 내용을 확인하지 못합니다.</p>
      </template>
      <details class="model-guide">
        <summary>제한 시간</summary>
        <div class="native-grid">
          <KsFormItem label="준비 대기(초)"><KsInput v-model="draft.readinessTimeoutSeconds" :disabled="busy" aria-label="준비 대기(초)" inputmode="numeric" /></KsFormItem>
          <KsFormItem v-if="draft.loadType === 'HTTP'" label="요청 제한 시간(초)"
            ><KsInput v-model="draft.requestTimeoutSeconds" :disabled="busy" aria-label="요청 제한 시간(초)" inputmode="numeric"
          /></KsFormItem>
        </div>
      </details>
    </KsForm>
    <template #footer
      ><div class="profiling-footer">
        <NativeFeedback :error="startError" />
        <div class="native-toolbar profiling-footer-actions">
          <KsButton :disabled="busy" @click="starting = false">닫기(입력 유지)</KsButton
          ><KsButton type="primary" nativeType="submit" form="model-profiling-form" :loading="busy" :disabled="busy || !options?.enabled">측정 시작</KsButton>
        </div>
      </div></template
    >
  </KsDrawer>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { KsButton, KsDrawer, KsForm, KsFormItem, KsInput, KsOption, KsSegmented, KsSelect } from "@kestra-io/design-system";
import { get, requestKey, time, write } from "./native-api";
import { ATTRIBUTION_LABEL, cpuQuantity, formatValue, memoryQuantity } from "./resource-profile";
import {
  ACCELERATOR_UNIT_NOTE,
  AVAILABILITY_LABEL,
  DEVICE_STATUS_LABEL,
  DISABLED_LABEL,
  FAILURE_LABEL,
  INCOMPATIBLE_LABEL,
  REQUIREMENT_BASIS_LABEL,
  STATE_LABEL,
  acceleratorText,
  acceleratorUnit,
  allocationText,
  base64Bytes,
  confirmDraft,
  confirmationDocument,
  deviceStatus,
  emptyRunDraft,
  encodeBase64,
  isActive,
  isCleaning,
  loadText,
  requirementBasis,
  runRequest,
  type Availability,
  type ModelResourceProfile,
  type ProfilingNode,
  type ProfilingOptions,
  type ProfilingRun,
  type Progress,
} from "./model-profiling";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";

const props = defineProps<{ modelId: string; availability?: Availability | null }>();
const emit = defineEmits<{ changed: [] }>();
const POLL_MS = 3000;
const rangeFields = ["min", "request", "max"] as const;
const fieldLabel = { min: "최소", request: "요청", max: "최대" };
const loadTypes = [
  { value: "HTTP", label: "HTTP 추론 요청" },
  { value: "COMMAND", label: "벤치마크 명령" },
];
const message = (cause: unknown, fallback: string) => (cause instanceof Error ? cause.message : fallback);
const base = () => `ai-models/${encodeURIComponent(props.modelId)}`;
const percent = (ratio: number | null | undefined) => (ratio == null ? "—" : `${Math.round(ratio * 1000) / 10}%`);
const optional = (value: number | null, format: (v: number) => string) => (value == null ? "—" : format(value));
// allocatableCount is node capacity, not what is free right now.
const nodeLabel = (n: ProfilingNode) =>
  [
    `${n.name} · ${n.architecture ?? "아키텍처 미확인"}`,
    `CPU ${n.allocatable.cpuMillicores == null ? "—" : cpuQuantity(n.allocatable.cpuMillicores)} · 메모리 ${n.allocatable.memoryBytes == null ? "—" : memoryQuantity(n.allocatable.memoryBytes)}`,
    ...n.accelerators.map((a) => `${a.resourceName} 용량 ${a.allocatableCount} ${acceleratorUnit(a.resourceName)}`),
    ...(n.busy ? ["측정 중"] : !n.compatible ? [INCOMPATIBLE_LABEL[n.incompatibleReason ?? ""] ?? "호환되지 않음"] : n.status !== "READY" ? [n.status] : []),
  ].join(" · ");

// ---- runs and confirmed profiles --------------------------------------------------------------------------------
const runs = ref<ProfilingRun[]>([]),
  profiles = ref<ModelResourceProfile[]>([]),
  loading = ref(false),
  loadError = ref(""),
  actionError = ref(""),
  busy = ref(false);
let generation = 0,
  timer: ReturnType<typeof setTimeout> | undefined;
const active = computed(() => runs.value.find(isActive) ?? null);
const cleaning = computed(() => runs.value.find(isCleaning) ?? null);
const latest = computed(() => runs.value[0] ?? null);
const profile = computed(() => profiles.value[0] ?? null);
const progress = computed(() => (active.value?.progress ?? null) as Progress | null);
const evidence = computed(() => pending.value?.result?.loadEvidence ?? null);
const pendingDevice = computed(() => (pending.value ? deviceStatus(pending.value) : null));
// A successful run that no confirmed revision cites yet.
const pending = computed(() =>
  latest.value?.state === "SUCCEEDED" && !profiles.value.some((p) => p.runId === latest.value!.id) ? latest.value : null,
);
const availabilityKey = computed(() => {
  const a = props.availability;
  return a ? (a.status === "READY" ? "READY" : (a.reason ?? "NO_MEASUREMENT")) : null;
});

async function load() {
  const g = ++generation;
  clearTimeout(timer);
  loading.value = true;
  loadError.value = "";
  try {
    const [r, p] = await Promise.all([
      get<{ items: ProfilingRun[] }>(`${base()}/profiling-runs?limit=20`),
      get<{ items: ModelResourceProfile[] }>(`${base()}/resource-profiles`),
    ]);
    if (g !== generation) return;
    runs.value = r.items;
    profiles.value = p.items;
    schedule(g);
  } catch (cause) {
    if (g === generation) loadError.value = message(cause, "측정 기록 조회 실패");
  } finally {
    if (g === generation) loading.value = false;
  }
}
/**
 * Polls only the run in progress, then the ended run until its temporary Pod is gone; a hidden tab waits, and a newer
 * model or reload drops older answers.
 */
function schedule(g: number) {
  clearTimeout(timer);
  if (!active.value && !cleaning.value) return;
  timer = setTimeout(async () => {
    if (g !== generation) return;
    if (document.hidden) return schedule(g);
    const watched = active.value ?? cleaning.value;
    if (!watched) return;
    try {
      const run = await get<ProfilingRun>(`${base()}/profiling-runs/${encodeURIComponent(watched.id)}`);
      if (g !== generation) return;
      runs.value = runs.value.map((r) => (r.id === run.id ? run : r));
      if (isActive(watched) && !isActive(run)) {
        // The result and the model's availability changed; reload both (cleanup polling resumes from there).
        await load();
        emit("changed");
        return;
      }
    } catch {
      // A transient failure keeps the last known state; the next tick retries.
    }
    schedule(g);
  }, POLL_MS);
}
watch(
  () => props.modelId,
  () => {
    runs.value = [];
    profiles.value = [];
    actionError.value = "";
    void load();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  ++generation;
  clearTimeout(timer);
});

async function cancel(run: ProfilingRun) {
  busy.value = true;
  actionError.value = "";
  try {
    const updated = await write<ProfilingRun>(`${base()}/profiling-runs/${encodeURIComponent(run.id)}/cancel`, "POST", {}, requestKey());
    runs.value = runs.value.map((r) => (r.id === updated.id ? updated : r));
    if (!isActive(updated)) emit("changed");
    schedule(generation);
  } catch (cause) {
    actionError.value = message(cause, "측정 취소 실패");
  } finally {
    busy.value = false;
  }
}

// ---- start a run -----------------------------------------------------------------------------------------------
const starting = ref(false),
  options = ref<ProfilingOptions | null>(null),
  optionsLoading = ref(false),
  optionsError = ref(""),
  startError = ref(""),
  draft = reactive(emptyRunDraft(null)),
  startKey = ref(requestKey());
let startPayload = "",
  startOrigin: HTMLElement | null = null,
  optionsGeneration = 0;
const node = computed(() => options.value?.nodes.find((n) => n.name === draft.nodeName) ?? null);
async function openStart(event: Event) {
  startOrigin = event.currentTarget as HTMLElement;
  starting.value = true;
  const g = ++optionsGeneration;
  optionsLoading.value = true;
  optionsError.value = "";
  try {
    const result = await get<ProfilingOptions>(`${base()}/profiling/options`);
    if (g !== optionsGeneration) return;
    // Keep what was typed before; only fill the server's timing defaults into a fresh draft.
    if (!options.value) Object.assign(draft, emptyRunDraft(result));
    options.value = result;
  } catch (cause) {
    if (g === optionsGeneration) optionsError.value = message(cause, "측정 조건 조회 실패");
  } finally {
    if (g === optionsGeneration) optionsLoading.value = false;
  }
}
// Binary bodies travel as standard Base64; a picked file is encoded here and can still be inspected or replaced.
// The file name is shown only while the field still holds exactly that file's encoding.
const binaryFile = ref<{ name: string; encoded: string } | null>(null);
const binaryNote = computed(() => {
  const encoded = draft.bodyBase64.replace(/\s+/g, "");
  if (!encoded) return "application/octet-stream 본문은 Base64로 전송합니다.";
  const bytes = base64Bytes(encoded);
  if (bytes == null) return "표준 Base64가 아닙니다.";
  const name = binaryFile.value?.encoded === draft.bodyBase64 ? `${binaryFile.value.name} · ` : "";
  return `${name}디코딩 후 ${bytes.toLocaleString()} bytes`;
});
async function readBinary(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0];
  input.value = "";
  if (!file || !options.value) return;
  startError.value = "";
  if (file.size > options.value.limits.bodyMaxBytes) {
    startError.value = `요청 본문은 ${Math.floor(options.value.limits.bodyMaxBytes / 1024)} KiB 이하여야 합니다(${file.name} ${file.size.toLocaleString()} bytes).`;
    return;
  }
  try {
    const encoded = encodeBase64(new Uint8Array(await file.arrayBuffer()));
    draft.bodyBase64 = encoded;
    binaryFile.value = { name: file.name, encoded };
  } catch {
    startError.value = `${file.name}을(를) 읽지 못했습니다.`;
  }
}
function closeStart(done: () => void) {
  if (!busy.value) done();
}
function startOpened() {
  document.querySelector<HTMLInputElement>("#model-profiling-form input")?.focus();
}
function startClosed() {
  if (startOrigin?.isConnected) startOrigin.focus();
}
async function start() {
  if (busy.value || !options.value || active.value || cleaning.value) return;
  busy.value = true;
  startError.value = "";
  try {
    const body = runRequest(draft, options.value);
    const payload = JSON.stringify(body);
    // An unchanged request keeps its key so an uncertain answer can be retried without a second Job.
    if (startPayload && payload !== startPayload) startKey.value = requestKey();
    startPayload = payload;
    const run = await write<ProfilingRun>(`${base()}/profiling-runs`, "POST", body, startKey.value);
    startKey.value = requestKey();
    startPayload = "";
    startOrigin = null;
    starting.value = false;
    runs.value = [run, ...runs.value.filter((r) => r.id !== run.id)];
    schedule(generation);
    emit("changed");
  } catch (cause) {
    startError.value = message(cause, "측정 시작 실패");
  } finally {
    busy.value = false;
  }
}

// ---- confirm a successful run ----------------------------------------------------------------------------------
const confirmation = reactive({ cpu: { min: "", request: "", max: "" }, memory: { min: "", request: "", max: "" } }),
  confirming = ref(false),
  confirmKey = ref(requestKey());
let confirmPayload = "";
watch(
  () => pending.value?.id,
  () => {
    if (pending.value) Object.assign(confirmation, confirmDraft(pending.value));
    confirmKey.value = requestKey();
    confirmPayload = "";
  },
  { immediate: true },
);
const basis = computed(() => (pending.value ? requirementBasis(confirmation, pending.value) : { cpu: "MANUAL" as const, memory: "MANUAL" as const }));
const confirmRows = computed(() => {
  const result = pending.value?.result,
    r = result?.recommendation;
  return [
    { id: "cpu" as const, label: "CPU", usage: result?.measurement?.cpu, placeholder: "예 500m",
      recommended: r ? cpuQuantity(r.cpu.requestMillicores) : "—", recommendedMax: r ? cpuQuantity(r.cpu.maximumMillicores) : "—" },
    { id: "memory" as const, label: "메모리", usage: result?.measurement?.memory, placeholder: "예 512Mi",
      recommended: r ? memoryQuantity(r.memory.requestBytes) : "—", recommendedMax: r ? memoryQuantity(r.memory.maximumBytes) : "—" },
  ];
});
async function confirm() {
  if (busy.value || !pending.value) return;
  busy.value = confirming.value = true;
  actionError.value = "";
  try {
    const body = confirmationDocument(confirmation, pending.value);
    const payload = JSON.stringify(body);
    if (confirmPayload && payload !== confirmPayload) confirmKey.value = requestKey();
    confirmPayload = payload;
    await write<ModelResourceProfile>(`${base()}/resource-profiles`, "POST", body, confirmKey.value);
    await load();
    emit("changed");
  } catch (cause) {
    actionError.value = message(cause, "요구량 확정 실패");
  } finally {
    busy.value = confirming.value = false;
  }
}
</script>
<style scoped>
.profiling {
  display: flex;
  flex-direction: column;
  gap: var(--ks-spacing-3);
  margin-top: var(--ks-spacing-4);
}
.profiling > * {
  margin: 0;
}
.profiling-head {
  gap: var(--ks-spacing-3);
}
.profiling-head h3 {
  margin: 0;
}
.profiling-start {
  margin-left: auto;
}
.profiling-block {
  border-top: 1px solid var(--ks-border-default);
  padding-top: var(--ks-spacing-3);
}
.profiling-block > * + * {
  margin-top: var(--ks-spacing-3);
}
.profiling-dl {
  margin: var(--ks-spacing-3) 0 0;
}
.native-detail .profiling-dl > div {
  margin: var(--ks-spacing-2) 0;
}
.profiling-badge {
  display: inline-block;
  margin-right: var(--ks-spacing-2);
  padding: 0 var(--ks-spacing-2);
  border-radius: var(--ks-radius-base);
  font-size: var(--ks-font-size-xs);
  background: var(--ks-bg-hover);
  color: var(--ks-text-secondary);
}
.profiling-badge--READY,
.profiling-badge--SUCCEEDED {
  color: var(--ks-text-success);
}
.profiling-badge--MEASURING,
.profiling-badge--AWAITING_CONFIRMATION {
  color: var(--ks-text-warning);
}
.profiling-badge--MEASUREMENT_FAILED,
.profiling-badge--FAILED {
  color: var(--ks-text-error);
}
.profiling-range {
  border: 0;
  border-top: 1px solid var(--ks-border-default);
  margin: 0;
  padding: var(--ks-spacing-3) 0 0;
  min-width: 0;
}
.profiling-range legend {
  float: left;
  width: 100%;
  padding: 0;
  font-size: var(--ks-font-size-sm);
  font-weight: var(--ks-font-weight-semibold);
}
.profiling-evidence {
  clear: both;
  margin: var(--ks-spacing-2) 0;
}
.profiling-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 var(--ks-spacing-3);
}
.profiling-grid :deep(.kel-form-item) {
  margin-bottom: var(--ks-spacing-2);
}
.profiling-grid :deep(.kel-input__wrapper) {
  min-height: 44px;
}
.profiling-request {
  display: grid;
  grid-template-columns: 112px 112px minmax(0, 1fr);
  gap: 0 var(--ks-spacing-3);
}
.profiling-binary {
  gap: var(--ks-spacing-3);
  margin-bottom: var(--ks-spacing-4);
}
.profiling-file {
  position: relative;
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  padding: 0 var(--ks-spacing-4);
  border: 1px solid var(--ks-border-default);
  border-radius: var(--ks-radius-base);
  font-size: var(--ks-font-size-sm);
  cursor: pointer;
}
.profiling-file:focus-within {
  outline: 2px solid var(--ks-border-focus);
  outline-offset: 2px;
}
.profiling-file.is-disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
.profiling-file input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: inherit;
}
.profiling-device {
  border-left: 2px solid var(--ks-border-default);
  padding-left: var(--ks-spacing-3);
}
.profiling-device--UNSUPPORTED,
.profiling-device--UNRECORDED {
  border-left-color: var(--ks-border-warning);
}
.profiling-notices {
  margin: 0;
  padding-left: var(--ks-spacing-5);
  color: var(--ks-text-warning);
  font-size: var(--ks-font-size-sm);
}
.profiling-subhead {
  font-size: var(--ks-font-size-sm);
}
.profiling-limits {
  margin: var(--ks-spacing-2) 0 0;
  padding-left: var(--ks-spacing-5);
}
.profiling-scroll {
  position: relative;
  overflow-x: auto;
}
.profiling-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--ks-font-size-sm);
}
.profiling-table th,
.profiling-table td {
  text-align: left;
  vertical-align: top;
  padding: var(--ks-spacing-2) var(--ks-spacing-3);
  border-bottom: 1px solid var(--ks-border-default);
  overflow-wrap: anywhere;
}
/* Quantities and short Korean labels stay whole; only long names may break. */
.profiling-table td {
  overflow-wrap: normal;
  word-break: keep-all;
}
.profiling-table thead th {
  color: var(--ks-text-secondary);
  font-weight: 600;
  white-space: nowrap;
}
code {
  font-size: var(--ks-font-size-xs);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.model-guide > summary {
  min-height: 44px;
  display: flex;
  align-items: center;
  gap: var(--ks-spacing-2);
  cursor: pointer;
  list-style: none;
  font-size: var(--ks-font-size-sm);
  font-weight: var(--ks-font-weight-semibold);
}
.model-guide > summary::-webkit-details-marker {
  display: none;
}
.model-guide > summary::before {
  content: "";
  width: 6px;
  height: 6px;
  border-right: 1.5px solid var(--ks-text-secondary);
  border-bottom: 1.5px solid var(--ks-text-secondary);
  transform: rotate(-45deg);
  transition: transform 0.15s cubic-bezier(0.23, 1, 0.32, 1);
  flex: none;
}
.model-guide[open] > summary::before {
  transform: rotate(45deg);
}
.model-guide > summary:focus-visible {
  outline: 2px solid var(--ks-border-focus);
  outline-offset: 2px;
  border-radius: var(--ks-radius-xs);
}
.native-form h2 {
  font-weight: var(--ks-font-weight-semibold);
}
.profiling-footer {
  display: flex;
  flex-direction: column;
  gap: var(--ks-spacing-3);
  text-align: left;
}
.profiling-footer-actions {
  justify-content: flex-end;
}
@media (max-width: 768px) {
  .profiling-request {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }
  .profiling-request > :last-child {
    grid-column: 1 / -1;
  }
  .profiling-footer-actions > * {
    flex: 1;
  }
}
</style>
