<template>
  <TopNavBar title="오프로딩 테스트"
    ><template #actions
      ><KsButton :disabled="loading" @click="refresh()">새로고침</KsButton></template
    ></TopNavBar
  >
  <main class="native-page">
    <p class="native-note">
      시험용 화면입니다. 모델은 손글씨 숫자 분류 MLP(UCI digits로 학습, 64-32-10)를
      CPU에서 실제로 추론하고, 부하는 선택한 edge에 제한 시간이 있는 CPU 부하 Job을
      실행합니다. GPU 추론이나 성능 측정이 아닙니다. 노드 사용률·Pod는 Prometheus와
      Kubernetes의 실제 관측이며, 관측이 없으면 —로 표시합니다.
    </p>
    <NativeLoading :busy="loading && !lab" label="상태를 불러오는 중…" />
    <NativeFeedback :error="loadError" />
    <KsAlert
      v-if="lab && !lab.enabled"
      type="warning"
      :closable="false"
      :title="`모델 실행을 사용할 수 없습니다: ${disabledText}`"
    />
    <KsAlert
      v-if="failure"
      type="error"
      :closable="false"
      :title="`모델 실행 실패: ${failure.reason ?? '사유 미보고'}`"
    >
      시도 {{ failure.attempt ?? "—" }} · {{ failure.nodeName ?? "노드 미상" }} · {{ time(failure.at) }}. 실패한 시험에는 부하를 시작할 수
      없고, 남은 부하는 자동으로 중지됩니다. 정리 후 '모델 실행'으로 새 시험을 시작하세요.
    </KsAlert>

    <section class="native-panel" aria-labelledby="lab-placement">
      <div class="native-toolbar lab-heading">
        <h2 id="lab-placement">현재 배치</h2>
        <span class="native-note" role="status">
          {{ sessionText }}
          <template v-if="observedAt"> · 조회 {{ time(observedAt) }}</template>
        </span>
      </div>
      <div class="lab-placement">
        <article
          v-for="side in sides"
          :key="side.role"
          class="lab-node"
          :class="{ current: here === side.role }"
          :aria-current="here === side.role ? 'location' : undefined"
        >
          <header>
            <span class="lab-role">{{ side.title }}</span>
            <KsTag v-if="here === side.role" type="success" size="small">모델 실행 중</KsTag>
          </header>
          <strong class="lab-node-name">{{ side.node?.name ?? side.empty }}</strong>
          <dl v-if="side.node" class="lab-metrics">
            <div><dt>CPU</dt><dd>{{ percent(side.node.cpuPercent) }}</dd></div>
            <div><dt>메모리</dt><dd>{{ percent(side.node.memoryPercent) }}</dd></div>
            <div><dt>관측</dt><dd>{{ time(side.node.observedAt) }}</dd></div>
          </dl>
          <ul class="lab-pods">
            <li v-for="pod in side.pods" :key="pod.name">
              <span>{{ pod.role === "MODEL" ? "모델" : "부하" }} Pod</span>
              <code>{{ pod.name }}</code>
              <span>UID {{ shortId(pod.uid) }}</span>
              <KsTag size="small" :type="pod.deletingAt ? 'warning' : pod.ready ? 'success' : 'info'">{{
                pod.deletingAt ? `종료 중 (${pod.phase})` : pod.phase
              }}</KsTag>
            </li>
            <li v-if="!side.pods.length" class="native-note">관측된 Pod 없음</li>
          </ul>
        </article>
        <div class="lab-arrow" aria-live="polite">
          <span aria-hidden="true">⇄</span>
          <template v-if="move">
            <strong>{{ move.label }}</strong>
            <KsTag size="small" :type="move.active ? 'warning' : 'info'">{{ move.state }}</KsTag>
            <span v-if="move.reason" class="native-note">사유 {{ move.reason }}</span>
          </template>
          <span v-else class="native-note">전환 없음</span>
        </div>
      </div>
      <p v-if="session?.pods.status === 'UNAVAILABLE'" class="native-note">
        Kubernetes Pod 조회 실패: Pod 상태를 알 수 없습니다.
      </p>
      <p v-if="here === 'UNKNOWN' && session && session.state !== 'STOPPED'" class="native-note">
        실행 중으로 관측된 모델 Pod가 없습니다 (시작·전환 중이거나 종료됨).
      </p>
    </section>

    <section class="native-panel" aria-labelledby="lab-controls">
      <h2 id="lab-controls">제어</h2>
      <div class="native-toolbar">
        <KsButton type="primary" :disabled="busy || !can.start || !!formProblem" :loading="pending === 'start'" @click="startModel">모델 실행</KsButton>
        <KsButton :disabled="busy || !can.stop" :loading="pending === 'stop'" @click="stopModel">모델 중지</KsButton>
        <KsButton :disabled="busy || !can.loadStart || !!formProblem" :loading="pending === 'load'" @click="startLoad">부하 시작</KsButton>
        <KsButton :disabled="busy || !can.loadStop" :loading="pending === 'loadStop'" @click="stopLoad">부하 중지</KsButton>
      </div>
      <p v-if="formProblem && (can.start || can.loadStart)" class="native-note">{{ formProblem }}</p>
      <div v-if="lab" class="lab-estimate">
        <p class="native-note">
          {{ edgeName ?? "edge 미선택" }} · {{ edgeCores == null ? "코어 수 알 수 없음" : `${edgeCores}코어` }}
          <template v-if="estimate">
            · 부하 {{ form.workers }}프로세스 ≈ CPU +{{ estimate.added.toFixed(0) }}%p 추가 · 예상
            {{ estimate.expected.toFixed(0) }}%
            ({{ estimate.observed ? `현재 ${percent(edgeCpu)} 기준` : "현재 사용률 미관측, 0% 가정" }}, 추정치) · 과부하 기준
            {{ threshold }}%
          </template>
        </p>
        <p v-if="estimate?.insufficient && !loadRunning(session)" class="native-note lab-warning" role="alert">
          부하가 부족합니다: 예상 {{ estimate.expected.toFixed(0) }}%가 과부하 기준 {{ threshold }}%보다 낮아 모델이 worker로 이동하지 않을 수 있습니다.
          부하 프로세스를 {{ recommended }}개 이상으로 늘리거나 기준을 낮추세요.
        </p>
        <KsButton v-if="!locked || session?.state === 'ACTIVE'" v-show="form.workers !== recommended" size="small" :disabled="busy || loadRunning(session)" @click="applyPreset">
          이 edge 권장 부하 적용 ({{ recommended }}프로세스)
        </KsButton>
      </div>
      <NativeFeedback :error="actionError" :notice="notice" />
      <div v-if="session" class="native-detail lab-facts"><dl>
        <div><dt>시험 상태</dt><dd>{{ sessionStatus(session) }} · Run {{ session.runState }}</dd></div>
        <div><dt>원래 edge</dt><dd>{{ session.edgeNodeName }}</dd></div>
        <div><dt>부하</dt><dd>{{ loadText }}</dd></div>
        <div>
          <dt>추론 증거</dt>
          <dd v-if="latency">
            최근 1회 추론 {{ latency.latencyMicros }} μs · {{ time(latency.observedAt) }} (모델 보고 표본 {{ session.latency.length }}개)
          </dd>
          <dd v-else>현재 Attempt의 추론 지연 보고 없음</dd>
        </div>
      </dl></div>
    </section>

    <section class="native-panel" aria-labelledby="lab-settings">
      <h2 id="lab-settings">시험 설정</h2>
      <p class="native-note">
        시험용 기본값입니다. 모델 실행 전에 바꿀 수 있고 실행 중에는 정책이 고정됩니다.
        노드 지표는 약 30초마다 수집되므로 연속 표본 {{ form.consecutiveSamples }}개에는 최소
        {{ (form.consecutiveSamples - 1) * 30 }}초 이상 걸립니다.
      </p>
      <KsForm v-if="lab" labelPosition="top" @submit.prevent
        ><div class="native-grid">
          <KsFormItem label="edge 노드"
            ><KsSelect v-model="form.edgeNodeId" :disabled="locked" placeholder="edge 노드 선택"
              ><KsOption
                v-for="node in edges"
                :key="node.id"
                :value="node.id"
                :disabled="node.status !== 'READY'"
                :label="`${node.name} · ${node.architecture} · ${node.status} · CPU ${percent(node.cpuPercent)}`" /></KsSelect
          ></KsFormItem>
          <KsFormItem v-for="field in fields" :key="field.key" :label="field.label"
            ><KsInputNumber
              v-model="form[field.key]"
              :min="field.min"
              :max="field.max"
              :step="1"
              :disabled="field.load ? busy || loadRunning(session) : locked"
              controlsPosition="right"
          /></KsFormItem>
        </div></KsForm
      >
    </section>

    <section v-if="session" class="native-panel" aria-labelledby="lab-history">
      <h2 id="lab-history">Pod·전환 기록</h2>
      <NativeRecords
        title="Attempt별 Pod (종료·생성 기록)"
        :rows="session.attempts"
        :columns="[
          { key: 'number', label: '#', width: 50 },
          { key: 'cause', label: '원인', width: 90 },
          { key: 'nodeName', label: '노드', width: 170 },
          { key: 'podUid', label: 'Pod UID', width: 110 },
          { key: 'observedState', label: '관측 상태', width: 130 },
          { key: 'createdAt', label: '생성', width: 160 },
          { key: 'updatedAt', label: '갱신', width: 160 },
        ]"
        ><template #podUid="{ row }"><code>{{ shortId(row.podUid) }}</code></template
        ><template #observedState="{ row }">{{ row.observedState ?? "—" }}{{ row.desiredState === "STOPPED" && row.observedState !== "TERMINATED" ? " (종료 요청됨)" : "" }}</template
        ><template #createdAt="{ row }">{{ time(row.createdAt) }}</template
        ><template #updatedAt="{ row }">{{ time(row.updatedAt) }}</template></NativeRecords
      >
      <NativeRecords
        title="자동 전환"
        :rows="session.operations"
        :columns="[
          { key: 'trigger', label: '판단', width: 190 },
          { key: 'route', label: '원 → 대상', width: 240 },
          { key: 'state', label: '상태', width: 140 },
          { key: 'createdAt', label: '판단 시각', width: 160 },
          { key: 'updatedAt', label: '갱신', width: 160 },
        ]"
        ><template #trigger="{ row }">{{ triggerLabel(row.trigger) }}</template
        ><template #route="{ row }">{{ route(row) }}</template
        ><template #state="{ row }">{{ stateLabel(row.state) }}{{ row.failureReason ? ` · ${row.failureReason}` : "" }}</template
        ><template #createdAt="{ row }">{{ time(row.createdAt) }}</template
        ><template #updatedAt="{ row }">{{ time(row.updatedAt) }}</template></NativeRecords
      >
      <details>
        <summary>판단 근거·정책 원본 (JSON)</summary>
        <pre class="native-json">{{ pretty({ policy: session.policy, decisions: session.operations.map((o) => o.decision) }) }}</pre>
      </details>
    </section>

    <section v-if="lab?.model" class="native-panel native-detail" aria-labelledby="lab-model">
      <h2 id="lab-model">모델·부하 실행 선언</h2>
      <dl>
        <div><dt>워크플로</dt><dd>{{ lab.model.workflowKey }} {{ lab.model.version }}</dd></div>
        <div><dt>실행 모듈</dt><dd>{{ lab.model.profileKey }} {{ lab.model.profileVersion }} · {{ lab.model.architectures.join(", ") }}</dd></div>
        <div><dt>모델 이미지</dt><dd><code>{{ lab.model.image }}</code></dd></div>
        <div><dt>명령</dt><dd><code>{{ lab.model.command.join(" ") }}</code></dd></div>
        <div><dt>부하 이미지</dt><dd><code>{{ lab.loadImage ?? "—" }}</code></dd></div>
      </dl>
    </section>
  </main>
</template>
<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { KsAlert, KsButton, KsForm, KsFormItem, KsInputNumber, KsOption, KsSelect, KsTag } from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import { all, get, pretty, requestKey, time, useAction, usePoll, write, type Schema } from "./native-api";
import { actionKeys, controls, cores, formFrom, lockedFields, loadEstimate, presetWorkers, loadRunning, modelFailure, percent, placement, policyFrom, problem, sessionStatus, shortId, stateLabel, transition, triggerLabel, type Lab, type LabForm, type NumericField } from "./offload-lab";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
import NativeRecords from "./NativeRecords.vue";

useRouteContext(computed(() => ({ title: "오프로딩 테스트" })));
const { busy: loading, error: loadError, act: loadAction } = useAction();
const { busy, error: actionError, notice, act } = useAction();
const lab = ref<Lab | null>(null),
  observedAt = ref<string | null>(null),
  pending = ref(""),
  form = ref<LabForm>({} as LabForm);
// One key per intended action, kept until it succeeds so a retry or reload replays instead of duplicating.
const keys = actionKeys(requestKey);
const session = computed(() => lab.value?.session ?? null);
const can = computed(() => controls(lab.value));
const locked = computed(() => busy.value || (session.value != null && session.value.state !== "STOPPED"));
const formProblem = computed(() => (lab.value ? problem(form.value) : ""));
// Core counts come from the node inventory API (Kubernetes capacity); unknown stays unknown.
const inventory = ref<Schema["ExecutionNode"][]>([]);
const open = computed(() => session.value != null && session.value.state !== "STOPPED");
const edgeId = computed(() => (open.value ? session.value!.edgeNodeId : form.value.edgeNodeId));
const edgeName = computed(() => lab.value?.nodes.find((n) => n.id === edgeId.value)?.name ?? null);
const edgeCpu = computed(() => lab.value?.nodes.find((n) => n.id === edgeId.value)?.cpuPercent ?? null);
const edgeCores = computed(() => cores(inventory.value.find((n) => n.id === edgeId.value)?.cpu));
// A running session keeps its own fixed policy threshold.
const threshold = computed(() =>
  open.value ? ((session.value!.policy as Schema["OffloadPolicy"])?.nodePlacement?.sourceCpuPercent ?? form.value.sourceCpuPercent) : form.value.sourceCpuPercent,
);
const recommended = computed(() => presetWorkers(edgeCores.value, threshold.value));
const estimate = computed(() => (edgeId.value ? loadEstimate(form.value.workers, edgeCores.value, edgeCpu.value, threshold.value) : null));
// The load preset follows the chosen edge only while the user has not typed their own value.
let presetApplied: number | null = null;
watch(recommended, (next) => {
  if (!lab.value || loadRunning(session.value) || form.value.workers !== presetApplied) return;
  form.value.workers = next;
  presetApplied = next;
});
function applyPreset() {
  form.value.workers = recommended.value;
  presetApplied = recommended.value;
}
const here = computed(() => placement(session.value));
const move = computed(() => transition(session.value));
const failure = computed(() => modelFailure(session.value));
const latency = computed(() => session.value?.latency[0] ?? null);
const edges = computed(() => lab.value?.nodes.filter((n) => n.role === "EDGE") ?? []);
const reasons: Record<string, string> = {
  RUNTIME_DISABLED: "실행 기능이 꺼져 있거나 실행 범위에 시험 워크플로가 없습니다",
  KUBERNETES_DISABLED: "부하 이미지·Kubernetes 연결이 설정되지 않았습니다",
  WORKFLOW_NOT_PUBLISHED: "시험 워크플로가 발행되지 않았습니다",
};
const disabledText = computed(() => reasons[lab.value?.disabledReason ?? ""] ?? lab.value?.disabledReason ?? "");
const sessionText = computed(() =>
  session.value ? `시험 ${sessionStatus(session.value)}` : "실행 중인 시험 없음",
);
const fields: { key: NumericField; label: string; min: number; max: number; load?: boolean }[] = [
  { key: "sourceCpuPercent", label: "edge 과부하 CPU (%) 이상", min: 1, max: 100 },
  { key: "returnCpuPercent", label: "복귀 CPU (%) 미만", min: 1, max: 100 },
  { key: "targetMaxCpuPercent", label: "worker CPU 상한 (%)", min: 1, max: 100 },
  { key: "targetMaxMemoryPercent", label: "worker 메모리 상한 (%)", min: 1, max: 100 },
  { key: "consecutiveSamples", label: "연속 표본 수", min: 2, max: 6 },
  { key: "maxSampleAgeSeconds", label: "표본 최대 나이 (초)", min: 10, max: 180 },
  { key: "maxGapSeconds", label: "표본 간격 상한 (초)", min: 5, max: 120 },
  { key: "minRunningSeconds", label: "시작 후 대기 (초)", min: 10, max: 3600 },
  { key: "cooldownSeconds", label: "전환 후 대기 (초)", min: 10, max: 3600 },
  { key: "maxTransfers", label: "최대 전환 횟수", min: 1, max: 8 },
  { key: "workers", label: "부하 프로세스 수 (CPU 코어)", min: 1, max: 16, load: true },
  { key: "durationSeconds", label: "부하 시간 (초)", min: 30, max: 1800, load: true },
];
const sides = computed(() => {
  const s = session.value;
  const nodes = lab.value?.nodes ?? [];
  const edge = s ? nodes.find((n) => n.id === s.edgeNodeId) : nodes.find((n) => n.id === form.value.edgeNodeId);
  // The worker side is the node the model currently runs on or most recently moved to.
  const workerName = s?.attempts.find((a) => a.nodeName && a.nodeName !== s.edgeNodeName)?.nodeName;
  const worker = workerName ? nodes.find((n) => n.name === workerName) : undefined;
  const pods = s?.pods.items ?? [];
  return [
    { role: "EDGE", title: "원래 edge", node: edge, empty: "edge 미선택", pods: pods.filter((p) => edge && p.nodeName === edge.name) },
    { role: "WORKER", title: "worker", node: worker, empty: "아직 이동하지 않음", pods: pods.filter((p) => worker && p.nodeName === worker.name) },
  ];
});
const loadText = computed(() => {
  const load = session.value?.load;
  if (!load || load.desired === "NONE") return "시작 안 함";
  const what = `${load.workers}프로세스 · ${load.seconds}초 (${load.generation}회차)`;
  const pods = session.value!.pods.items.filter((p) => p.role === "LOAD" && p.generation === load.generation);
  const physical = session.value!.pods.status !== "OBSERVED" ? "Pod 알 수 없음" : pods.length ? `Pod ${pods.map((p) => (p.deletingAt ? "종료 중" : p.phase)).join(", ")}` : "Pod 없음";
  const desired = { RUNNING: `실행 요청 · ${time(load.expiresAt)}까지`, STOPPED: "중지 요청", EXPIRED: "시간 만료" }[load.desired] ?? load.desired;
  return `${desired} · ${what} · ${physical}${load.jobUid ? "" : load.desired === "RUNNING" ? " · Job 생성 확인 전" : ""}`;
});
const route = (op: Schema["OffloadOperation"]) => {
  const d = op.decision as { sourceNode?: { name: string }; targetNode?: { name: string } } | null;
  return d?.sourceNode && d.targetNode ? `${d.sourceNode.name} → ${d.targetNode.name}` : "—";
};

async function load() {
  const next = await get<Lab>("offload-lab");
  observedAt.value = new Date().toISOString();
  if (!lab.value) {
    form.value = formFrom(next); // Includes the open session's stored policy and running load after a reload.
    presetApplied = form.value.workers; // The server preset counts as untouched.
  } else {
    // Locked inputs follow the server (also a session started elsewhere); an editable draft is left alone.
    const locked = lockedFields(next) as Record<string, unknown>;
    const current = form.value as unknown as Record<string, unknown>;
    for (const [name, value] of Object.entries(locked)) if (current[name] !== value) current[name] = value;
  }
  lab.value = next;
  if (!inventory.value.length) inventory.value = await all<Schema["ExecutionNode"]>("nodes").catch(() => []);
}
const refresh = () => loadAction(load);
// Polls only while this page is mounted; a failed poll keeps the last state and shows the error.
usePoll(() => (busy.value ? Promise.resolve() : refresh()), 3000);
onMounted(refresh);
watch(
  () => session.value?.id,
  (id, previous) => {
    if (id !== previous) keys.clear();
  },
);
// keyName names the exact key a request used; it is settled only after the server confirmed that request.
function run(action: string, keyName: string | null, call: () => Promise<unknown>, done: string) {
  pending.value = action;
  void act(async () => {
    try {
      await call();
      if (keyName) keys.settle(keyName);
      notice.value = done;
      await load();
    } finally {
      pending.value = "";
    }
  });
}
function startModel() {
  if (!lab.value || formProblem.value) return;
  const body = { edgeNodeId: form.value.edgeNodeId, offload: policyFrom(lab.value, form.value) };
  // The key belongs to this exact body: an edited retry is a new request (the server still refuses a second open session).
  const name = `start:${JSON.stringify(body)}`;
  run("start", name, () => write("offload-lab/sessions", "POST", body, keys.key(name)), "모델 실행을 요청했습니다. Pod가 Running으로 관측되면 배치에 표시됩니다.");
}
function stopModel() {
  const id = session.value?.id;
  if (id) run("stop", null, () => write(`offload-lab/sessions/${id}/stop`, "POST", {}), "모델 중지를 요청했습니다. Pod 종료가 관측되면 종료됨으로 바뀝니다.");
}
function startLoad() {
  const id = session.value?.id;
  if (!id) return;
  const body = { workers: form.value.workers, durationSeconds: form.value.durationSeconds };
  const name = `load:${id}:${body.workers}:${body.durationSeconds}`;
  run("load", name, () => write(`offload-lab/sessions/${id}/load`, "POST", body, keys.key(name)), "부하 Job 생성을 요청했습니다.");
}
function stopLoad() {
  const id = session.value?.id;
  if (id) run("loadStop", null, () => write(`offload-lab/sessions/${id}/load/stop`, "POST", {}), "부하 중지를 요청했습니다. Pod 종료를 확인하세요.");
}
</script>
<style scoped>
.lab-heading {
  justify-content: space-between;
  flex-wrap: wrap;
}
.lab-heading h2 {
  margin: 0;
}
.lab-placement {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  gap: var(--ks-spacing-4);
  align-items: stretch;
  margin-top: var(--ks-spacing-4);
}
.lab-node {
  border: 1px solid var(--ks-border-default);
  border-radius: var(--ks-radius-base);
  padding: var(--ks-spacing-4);
  display: flex;
  flex-direction: column;
  gap: var(--ks-spacing-2);
  min-width: 0;
}
.lab-node.current {
  border-color: var(--ks-border-focus);
  box-shadow: inset 0 0 0 1px var(--ks-border-focus);
}
.lab-node:nth-of-type(2) {
  order: 3;
}
.lab-node header {
  display: flex;
  justify-content: space-between;
  gap: var(--ks-spacing-2);
}
.lab-role {
  color: var(--ks-text-secondary);
  font-size: var(--ks-font-size-sm);
}
.lab-node-name {
  overflow-wrap: anywhere;
}
.lab-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ks-spacing-1) var(--ks-spacing-4);
  margin: 0;
  font-size: var(--ks-font-size-sm);
}
.lab-metrics div {
  display: flex;
  gap: var(--ks-spacing-1);
}
.lab-metrics dt {
  color: var(--ks-text-secondary);
}
.lab-metrics dd {
  margin: 0;
}
.lab-pods {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ks-spacing-2);
  font-size: var(--ks-font-size-sm);
}
.lab-pods li {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ks-spacing-1) var(--ks-spacing-2);
}
.lab-pods code {
  overflow-wrap: anywhere;
}
.lab-arrow {
  order: 2;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--ks-spacing-2);
  text-align: center;
  max-width: 200px;
}
.lab-arrow > span:first-child {
  font-size: var(--ks-font-size-xl);
  color: var(--ks-text-secondary);
}
.lab-estimate {
  margin-top: var(--ks-spacing-3);
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--ks-spacing-2);
}
.lab-estimate p {
  margin: 0;
}
.lab-warning {
  color: var(--ks-log-error);
}
.lab-facts {
  margin-top: var(--ks-spacing-4);
}
code {
  font-size: var(--ks-font-size-xs);
}
@media (max-width: 768px) {
  .lab-placement {
    grid-template-columns: minmax(0, 1fr);
  }
  .lab-arrow {
    max-width: none;
  }
}
</style>
