<template>
  <TopNavBar title="오프로딩 테스트"
    ><template #actions
      ><KsButton :disabled="loading" @click="refresh()">새로고침</KsButton></template
    ></TopNavBar
  >
  <main class="native-page lab">
    <header class="lab-status">
      <div class="lab-status-line" role="status">
        <KsTag :type="status.tone">{{ status.text }}</KsTag>
        <span v-if="open"
          >원래 엣지 <strong>{{ session!.edgeNodeName }}</strong> · 모델 위치
          <strong>{{ session!.currentNodeName ?? "—" }}</strong></span
        >
        <span v-else-if="session" class="native-note"
          >직전 시험 {{ time(session.stoppedAt) }} 종료 · 원래 엣지 {{ session.edgeNodeName }}</span
        >
        <span v-if="observedAt" class="native-note">조회 {{ time(observedAt) }}</span>
      </div>
      <p class="native-note">
        선택한 엣지에서 숫자 분류 모델을 실행하고 CPU 또는 GPU 부하를 걸어, 모델이 워커로 옮겨졌다가 돌아오는지 확인합니다.
      </p>
      <details class="lab-about">
        <summary>시험 조건</summary>
        <p class="native-note">
          모델은 손글씨 숫자 분류 MLP(UCI digits로 학습, 64-32-10)를 CPU에서 실제로 추론합니다. CPU 부하는 선택한 엣지에 제한
          시간이 있는 CPU 부하 Job을, GPU 부하는 같은 엣지의 NVIDIA GPU 1개에 CUDA 연산 Job을 실행합니다. GPU 부하는 엣지 GPU
          사용률로 이동·복귀하는지 보는 시험이며 모델의 GPU 추론이나 GPU 성능 측정이 아닙니다. 노드 사용률·Pod는 Prometheus와
          Kubernetes의 실제 관측이며, 관측이 없으면 —로 표시합니다.
        </p>
      </details>
    </header>
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

    <section v-if="lab" class="native-panel lab-controls" aria-label="시험 제어">
      <KsForm labelPosition="top" @submit.prevent
        ><div class="lab-edge">
          <KsFormItem label="부하 종류"
            ><KsSegmented
              v-model="form.mode"
              :options="modes"
              :disabled="locked"
              aria-label="부하 종류"
          /></KsFormItem>
          <KsFormItem label="엣지 노드"
            ><KsSelect v-model="form.edgeNodeId" :disabled="locked" placeholder="엣지 노드 선택"
              ><KsOption
                v-for="node in edges"
                :key="node.id"
                :value="node.id"
                :disabled="node.status !== 'READY' || (gpuMode && !node.gpuLoadResource)"
                :label="edgeLabel(node)" /></KsSelect
          ></KsFormItem>
          <p v-if="gpuMode" class="native-note">
            <template v-if="edgeNode?.gpuLoadResource"
              >GPU 자원 {{ edgeNode.gpuLoadResource }} 1개 · CUDA 1프로세스 · GPU {{ percent(edgeNode.gpuPercent) }}
              (관측 {{ time(edgeNode.gpuObservedAt) }}) · 과부하 기준 {{ threshold }}%</template
            >
            <template v-else>NVIDIA GPU 자원이 있는 엣지만 선택할 수 있습니다.</template>
          </p>
          <p v-else class="native-note">
            {{ edgeCores == null ? "코어 수 알 수 없음" : `${edgeCores}코어` }}
            <template v-if="estimate">
              · 부하 {{ form.workers }}프로세스 ≈ CPU +{{ estimate.added.toFixed(0) }}%p · 예상
              {{ estimate.expected.toFixed(0) }}%
              ({{ estimate.observed ? `현재 ${percent(edgeCpu)} 기준` : "현재 사용률 미관측, 0% 가정" }}, 추정치) · 과부하 기준
              {{ threshold }}%
            </template>
          </p>
        </div>
        <div class="lab-groups">
          <fieldset class="lab-group">
            <legend>모델</legend>
            <div class="native-toolbar">
              <KsButton type="primary" :disabled="busy || !can.start || !!formProblem" :loading="pending === 'start'" @click="startModel">모델 실행</KsButton>
              <KsButton :disabled="busy || !can.stop" :loading="pending === 'stop'" @click="stopModel">모델 중지</KsButton>
            </div>
          </fieldset>
          <fieldset class="lab-group">
            <legend>부하</legend>
            <div class="native-toolbar">
              <KsFormItem v-for="field in loadFields.filter((f) => !gpuMode || f.key !== 'workers')" :key="field.key" :label="field.label"
                ><KsInputNumber
                  v-model="form[field.key]"
                  :min="field.min"
                  :max="field.max"
                  :step="1"
                  :disabled="busy || loadRunning(session)"
                  controlsPosition="right"
              /></KsFormItem>
              <KsButton :disabled="busy || !can.loadStart || !!formProblem" :loading="pending === 'load'" @click="startLoad">부하 시작</KsButton>
              <KsButton :disabled="busy || !can.loadStop" :loading="pending === 'loadStop'" @click="stopLoad">부하 중지</KsButton>
            </div>
          </fieldset>
        </div></KsForm
      >
      <p v-if="formProblem && (can.start || can.loadStart)" class="native-note">{{ formProblem }}</p>
      <p v-if="!gpuMode && estimate?.insufficient && !loadRunning(session)" class="native-note lab-warning" role="alert">
        부하가 부족합니다: 예상 {{ estimate.expected.toFixed(0) }}%가 과부하 기준 {{ threshold }}%보다 낮아 모델이 워커로 이동하지 않을 수 있습니다.
        부하 프로세스를 {{ recommended }}개 이상으로 늘리거나 기준을 낮추세요.
      </p>
      <KsButton v-if="!gpuMode && (!locked || session?.state === 'ACTIVE')" v-show="form.workers !== recommended" size="small" :disabled="busy || loadRunning(session)" @click="applyPreset">
        이 엣지 권장 부하 적용 ({{ recommended }}프로세스)
      </KsButton>
      <NativeFeedback :error="actionError" :notice="notice" />
    </section>

    <section class="native-panel" aria-labelledby="lab-placement">
      <div class="lab-heading">
        <h2 id="lab-placement">모델 위치</h2>
        <span class="native-note">{{ open ? "현재 시험의 관측" : "시작 전 · 선택한 엣지" }}</span>
        <span v-if="count" class="native-note lab-count"
          >완료된 전환 {{ count.done }} / 최대 {{ count.max ?? "—" }}<template v-if="count.exhausted"> · 한도 도달</template></span
        >
      </div>
      <div class="lab-lane">
        <article
          v-for="side in sides"
          :key="side.role"
          class="lab-station"
          :class="{ current: here === side.role }"
          :aria-current="here === side.role ? 'location' : undefined"
        >
          <header>
            <span class="lab-role">{{ side.title }}</span>
            <KsTag v-if="here === side.role" type="success" size="small">모델 실행 중</KsTag>
          </header>
          <strong class="lab-node-name">{{ side.node?.name ?? side.empty }}</strong>
          <template v-if="side.node">
            <div class="lab-cpu">
              <span>{{ side.metric.label }} <strong>{{ percent(side.metric.value) }}</strong></span>
              <span class="lab-bar" aria-hidden="true"
                ><span v-if="side.metric.value != null" class="lab-fill" :style="{ width: `${Math.min(100, side.metric.value)}%` }" /><span
                  v-for="mark in side.marks"
                  :key="mark.label"
                  class="lab-mark"
                  :style="{ left: `${mark.value}%` }"
              /></span>
              <span class="native-note">{{ side.marks.map((m) => `${m.label} ${m.value}%`).join(" · ") }}</span>
            </div>
            <span v-if="side.metric.label === 'GPU'" class="native-note"
              >GPU 관측 {{ time(side.metric.observedAt) }} · CPU {{ percent(side.node.cpuPercent) }} · 메모리 {{ percent(side.node.memoryPercent) }} · 관측
              {{ time(side.node.observedAt) }}</span
            >
            <span v-else class="native-note">메모리 {{ percent(side.node.memoryPercent) }} · 관측 {{ time(side.node.observedAt) }}</span>
          </template>
          <ul v-if="open" class="lab-pods">
            <li v-for="pod in side.pods" :key="pod.name">
              <span>{{ pod.role === "MODEL" ? "모델" : "부하" }}</span>
              <code :title="pod.name">{{ pod.name }}</code>
              <span class="native-note">UID {{ shortId(pod.uid) }}</span>
              <KsTag size="small" :type="pod.deletingAt ? 'warning' : pod.phase === 'Failed' ? 'danger' : pod.ready ? 'success' : 'info'">{{
                pod.deletingAt ? `종료 중 (${pod.phase})` : pod.reason && pod.phase !== 'Running' ? `${pod.phase} · ${pod.reason}` : pod.phase
              }}</KsTag>
            </li>
            <li v-if="!side.pods.length" class="native-note">관측된 Pod 없음</li>
          </ul>
        </article>
        <div class="lab-track" :class="move ? [move.direction.toLowerCase(), move.tone] : 'idle'" aria-live="polite">
          <span class="lab-arrow" aria-hidden="true">{{ move ? (move.direction === "RETURN" ? "←" : "→") : "⇢" }}</span>
          <template v-if="move">
            <strong>{{ move.label }}</strong>
            <KsTag size="small" :type="move.tone">{{ move.state }}</KsTag>
            <span v-if="move.reason" class="native-note">사유 {{ move.reason }}</span>
          </template>
          <span v-else class="native-note">{{ open ? "전환 없음" : "모델 실행 전" }}</span>
        </div>
      </div>
      <p v-if="open && session!.pods.status === 'UNAVAILABLE'" class="native-note">
        Kubernetes Pod 조회 실패: Pod 상태를 알 수 없습니다.
      </p>
      <p v-if="open && here === 'UNKNOWN'" class="native-note">
        실행 중으로 관측된 모델 Pod가 없습니다 (시작·전환 중이거나 종료됨).
      </p>
      <dl v-if="session" class="lab-facts">
        <div><dt>{{ open ? "부하" : "직전 부하" }}</dt><dd>{{ loadText }}</dd></div>
        <div>
          <dt>{{ open ? "추론 증거" : "직전 추론 증거" }}</dt>
          <dd v-if="latency">
            최근 1회 추론 {{ latency.latencyMicros }} μs · {{ time(latency.observedAt) }} (모델 보고 표본 {{ session.latency.length }}개)
          </dd>
          <dd v-else>현재 Attempt의 추론 지연 보고 없음</dd>
        </div>
      </dl>
    </section>

    <section class="native-panel" aria-labelledby="lab-events">
      <div class="lab-heading">
        <h2 id="lab-events">진행 기록</h2>
        <span v-if="session" class="native-note">{{ open ? "현재 시험" : "직전 시험 (종료됨)" }} · {{ sessionStatus(session) }} · Run {{ session.runState }}</span>
      </div>
      <ol v-if="events.length" class="lab-events">
        <li v-for="(event, index) in events" :key="index" :class="event.tone">
          <time :datetime="event.at">{{ time(event.at) }}</time>
          <span><strong>{{ event.text }}</strong> <span class="native-note">{{ event.detail }}</span></span>
        </li>
      </ol>
      <p v-else class="native-note">모델을 실행하면 Attempt·전환·부하·Pod 기록이 시각 순으로 표시됩니다.</p>
    </section>

    <details v-if="lab" class="native-panel lab-more">
      <summary>
        자동 전환 정책 <span class="native-note">{{ form.mode }} 과부하 {{ threshold }}% · 복귀 {{ returnThreshold }}% · 최대 전환
          {{ form.maxTransfers }}회{{ locked ? " · 실행 중 고정" : "" }}</span>
      </summary>
      <p class="native-note">
        시험용 기본값입니다. 모델 실행 전에 바꿀 수 있고 실행 중에는 정책이 고정됩니다. 최대 전환 2회가 왕복 1회입니다.
        노드 지표는 약 30초마다 수집되므로 연속 표본 {{ form.consecutiveSamples }}개에는 최소
        {{ (form.consecutiveSamples - 1) * 30 }}초 이상 걸립니다.
      </p>
      <KsForm labelPosition="top" @submit.prevent
        ><div class="native-grid lab-policy">
          <KsFormItem v-for="field in policyFields.filter((f) => !f.mode || f.mode === form.mode)" :key="field.key" :label="field.label"
            ><KsInputNumber
              v-model="form[field.key]"
              :min="field.min"
              :max="field.max"
              :step="1"
              :disabled="locked"
              controlsPosition="right"
          /></KsFormItem></div
      ></KsForm>
    </details>

    <details v-if="session" class="native-panel lab-more">
      <summary>Pod·Attempt 원본 기록 <span class="native-note">전체 이름·UID·판단 근거</span></summary>
      <NativeRecords
        title="지금 관측된 Pod"
        :rows="session.pods.items.map((pod) => ({ ...pod, id: pod.uid ?? pod.name }))"
        :columns="[
          { key: 'role', label: '역할', width: 70 },
          { key: 'name', label: 'Pod 이름', width: 300 },
          { key: 'uid', label: 'UID', width: 300 },
          { key: 'nodeName', label: '노드', width: 170 },
          { key: 'phase', label: '상태', width: 140 },
          { key: 'createdAt', label: '생성', width: 160 },
        ]"
        ><template #name="{ row }"><code>{{ row.name }}</code></template
        ><template #uid="{ row }"><code>{{ row.uid ?? "—" }}</code></template
        ><template #phase="{ row }">{{ row.phase }}{{ row.deletingAt ? ` · 삭제 요청 ${time(row.deletingAt)}` : "" }}</template
        ><template #createdAt="{ row }">{{ time(row.createdAt) }}</template></NativeRecords
      >
      <NativeRecords
        title="Attempt별 Pod (종료·생성 기록)"
        :rows="session.attempts"
        :columns="[
          { key: 'number', label: '#', width: 50 },
          { key: 'cause', label: '원인', width: 90 },
          { key: 'nodeName', label: '노드', width: 170 },
          { key: 'podUid', label: 'Pod UID', width: 300 },
          { key: 'observedState', label: '관측 상태', width: 130 },
          { key: 'createdAt', label: '생성', width: 160 },
          { key: 'updatedAt', label: '갱신', width: 160 },
        ]"
        ><template #podUid="{ row }"><code>{{ row.podUid ?? "—" }}</code></template
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
    </details>

    <details v-if="lab?.model" class="native-panel native-detail lab-more">
      <summary>모델·부하 실행 선언</summary>
      <dl>
        <div><dt>워크플로</dt><dd>{{ lab.model.workflowKey }} {{ lab.model.version }}</dd></div>
        <div><dt>실행 모듈</dt><dd>{{ lab.model.profileKey }} {{ lab.model.profileVersion }} · {{ lab.model.architectures.join(", ") }}</dd></div>
        <div><dt>모델 이미지</dt><dd><code>{{ lab.model.image }}</code></dd></div>
        <div><dt>명령</dt><dd><code>{{ lab.model.command.join(" ") }}</code></dd></div>
        <div><dt>부하 이미지</dt><dd><code>{{ lab.loadImage ?? "—" }}</code></dd></div>
      </dl>
    </details>
  </main>
</template>
<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { KsAlert, KsButton, KsForm, KsFormItem, KsInputNumber, KsOption, KsSegmented, KsSelect, KsTag } from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import { all, get, pretty, requestKey, time, useAction, usePoll, write, type Schema } from "./native-api";
import { actionKeys, controls, cores, formFrom, lockedFields, loadBody, loadEstimate, loadMode, metric, presetWorkers, loadRunning, modelFailure, runFinished, percent, placement, policyFrom, problem, route, sessionStatus, shortId, stateLabel, timeline, transfers, transition, triggerLabel, type Lab, type LabForm, type LabNode, type LoadMode, type NumericField, type OffloadPolicy, type Tone } from "./offload-lab";
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
const formProblem = computed(() => (lab.value ? problem(form.value, lab.value.nodes) : ""));
// The form mode equals the open session's stored policy (lockedFields), so it is the session's mode while one is open.
const gpuMode = computed(() => form.value.mode === "GPU");
const modes: { value: LoadMode; label: string }[] = [
  { value: "CPU", label: "CPU 부하" },
  { value: "GPU", label: "GPU 부하" },
];
// Core counts come from the node inventory API (Kubernetes capacity); unknown stays unknown.
const inventory = ref<Schema["ExecutionNode"][]>([]);
const open = computed(() => session.value != null && session.value.state !== "STOPPED");
const edgeId = computed(() => (open.value ? session.value!.edgeNodeId : form.value.edgeNodeId));
const edgeNode = computed(() => lab.value?.nodes.find((n) => n.id === edgeId.value) ?? null);
const edgeCpu = computed(() => edgeNode.value?.cpuPercent ?? null);
const edgeLabel = (node: LabNode) =>
  gpuMode.value
    ? `${node.name} · ${node.architecture} · ${node.status} · ${node.gpuLoadResource ? `GPU ${percent(node.gpuPercent)}` : "GPU 없음"}`
    : `${node.name} · ${node.architecture} · ${node.status} · CPU ${percent(node.cpuPercent)}`;
const edgeCores = computed(() => cores(inventory.value.find((n) => n.id === edgeId.value)?.cpu));
// A running session keeps its own fixed policy thresholds; the form shows the same values once locked.
const fixed = computed(() => (open.value ? (session.value!.policy as OffloadPolicy | null)?.nodePlacement : null));
const threshold = computed(() =>
  gpuMode.value ? (fixed.value?.sourceGpuPercent ?? form.value.sourceGpuPercent) : (fixed.value?.sourceCpuPercent ?? form.value.sourceCpuPercent),
);
const returnThreshold = computed(() =>
  gpuMode.value
    ? (fixed.value?.returnToEdge?.gpuPercent ?? form.value.returnGpuPercent)
    : (fixed.value?.returnToEdge?.cpuPercent ?? form.value.returnCpuPercent),
);
const recommended = computed(() => presetWorkers(edgeCores.value, fixed.value?.sourceCpuPercent ?? form.value.sourceCpuPercent));
const estimate = computed(() => (edgeId.value && !gpuMode.value ? loadEstimate(form.value.workers, edgeCores.value, edgeCpu.value, threshold.value) : null));
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
// A stopped session's movement belongs to the previous test, not to the edge selected for the next one.
const move = computed(() => (open.value ? transition(session.value) : null));
const count = computed(() => (open.value ? transfers(session.value) : null));
const events = computed(() => timeline(session.value));
const failure = computed(() => modelFailure(session.value));
const latency = computed(() => session.value?.latency[0] ?? null);
const edges = computed(() => lab.value?.nodes.filter((n) => n.role === "EDGE") ?? []);
const reasons: Record<string, string> = {
  RUNTIME_DISABLED: "실행 기능이 꺼져 있거나 실행 범위에 시험 워크플로가 없습니다",
  KUBERNETES_DISABLED: "부하 이미지·Kubernetes 연결이 설정되지 않았습니다",
  WORKFLOW_NOT_PUBLISHED: "시험 워크플로가 발행되지 않았습니다",
};
const disabledText = computed(() => reasons[lab.value?.disabledReason ?? ""] ?? lab.value?.disabledReason ?? "");
const status = computed<{ text: string; tone: Tone }>(() => {
  const s = session.value;
  if (!s || s.state === "STOPPED") return { text: s?.runState === "FAILED" ? "직전 시험 모델 실패" : "실행 중인 시험 없음", tone: "info" };
  const text = `시험 ${sessionStatus(s)}`;
  return { text, tone: s.runState === "FAILED" ? "danger" : s.state === "ACTIVE" && !runFinished(s) ? "success" : "warning" };
});
const fields: { key: NumericField; label: string; min: number; max: number; load?: boolean; mode?: LoadMode }[] = [
  { key: "sourceCpuPercent", label: "엣지 과부하 CPU (%) 이상", min: 1, max: 100, mode: "CPU" },
  { key: "returnCpuPercent", label: "복귀 CPU (%) 미만", min: 1, max: 100, mode: "CPU" },
  { key: "sourceGpuPercent", label: "엣지 과부하 GPU (%) 이상", min: 1, max: 100, mode: "GPU" },
  { key: "returnGpuPercent", label: "복귀 GPU (%) 미만", min: 1, max: 100, mode: "GPU" },
  { key: "targetMaxCpuPercent", label: "워커 CPU 상한 (%)", min: 1, max: 100 },
  { key: "targetMaxMemoryPercent", label: "워커 메모리 상한 (%)", min: 1, max: 100 },
  { key: "consecutiveSamples", label: "연속 표본 수", min: 2, max: 6 },
  { key: "maxSampleAgeSeconds", label: "표본 최대 나이 (초)", min: 10, max: 180 },
  { key: "maxGapSeconds", label: "표본 간격 상한 (초)", min: 5, max: 120 },
  { key: "minRunningSeconds", label: "시작 후 대기 (초)", min: 10, max: 3600 },
  { key: "cooldownSeconds", label: "전환 후 대기 (초)", min: 10, max: 3600 },
  { key: "maxTransfers", label: "최대 전환 횟수", min: 1, max: 8 },
  { key: "workers", label: "프로세스 (코어)", min: 1, max: 16, load: true },
  { key: "durationSeconds", label: "시간 (초)", min: 30, max: 1800, load: true },
];
const policyFields = fields.filter((f) => !f.load);
const loadFields = fields.filter((f) => f.load);
const sides = computed(() => {
  // Only an open session owns the lane; before a start it shows the selected edge for the next test.
  const s = open.value ? session.value! : null;
  const nodes = lab.value?.nodes ?? [];
  const edge = nodes.find((n) => n.id === edgeId.value);
  // The worker side is the node the model currently runs on or most recently moved to.
  const workerName = s?.attempts.find((a) => a.nodeName && a.nodeName !== s.edgeNodeName)?.nodeName;
  const worker = workerName ? nodes.find((n) => n.name === workerName) : undefined;
  const pods = s?.pods.items ?? [];
  const policy = fixed.value;
  return [
    {
      role: "EDGE", title: open.value ? "원래 엣지" : "선택한 엣지", node: edge, empty: "엣지를 선택하세요",
      pods: pods.filter((p) => edge && p.nodeName === edge.name),
      // The edge shows the metric its trigger and return use; the worker keeps CPU, which its headroom check uses.
      metric: metric(edge, form.value.mode),
      marks: [{ label: "복귀", value: returnThreshold.value }, { label: "과부하", value: threshold.value }],
    },
    {
      role: "WORKER", title: "워커", node: worker, empty: s ? "아직 이동하지 않음" : "—",
      pods: pods.filter((p) => worker && p.nodeName === worker.name),
      metric: metric(worker, "CPU"),
      marks: [{ label: "수용 상한", value: policy?.targetMaxCpuPercent ?? form.value.targetMaxCpuPercent }],
    },
  ];
});
const loadText = computed(() => {
  const load = session.value?.load;
  if (!load || load.desired === "NONE") return "시작 안 함";
  // The session's own stored policy decides, not the editable choice for the next test.
  const gpuLoad = loadMode(session.value!.policy as OffloadPolicy | null) === "GPU";
  const what = `${gpuLoad ? "GPU 1개 (CUDA)" : `${load.workers}프로세스`} · ${load.seconds}초 (${load.generation}회차)`;
  const pods = session.value!.pods.items.filter((p) => p.role === "LOAD" && p.generation === load.generation);
  // A failed load Pod (e.g. CUDA unavailable) shows its phase and reason; a requested load is not reported as running.
  const phase = (p: (typeof pods)[number]) => (p.deletingAt ? "종료 중" : p.reason && p.phase !== "Running" ? `${p.phase} (${p.reason})` : p.phase);
  const physical = session.value!.pods.status !== "OBSERVED" ? "Pod 알 수 없음" : pods.length ? `Pod ${pods.map(phase).join(", ")}` : "Pod 없음";
  const desired = { RUNNING: `실행 요청 · ${time(load.expiresAt)}까지`, STOPPED: "중지 요청", EXPIRED: "시간 만료" }[load.desired] ?? load.desired;
  return `${desired} · ${what} · ${physical}${load.jobUid ? "" : load.desired === "RUNNING" ? " · Job 생성 확인 전" : ""}`;
});

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
  const body = loadBody(form.value);
  const name = `load:${id}:${body.workers}:${body.durationSeconds}`;
  run("load", name, () => write(`offload-lab/sessions/${id}/load`, "POST", body, keys.key(name)), "부하 Job 생성을 요청했습니다.");
}
function stopLoad() {
  const id = session.value?.id;
  if (id) run("loadStop", null, () => write(`offload-lab/sessions/${id}/load/stop`, "POST", {}), "부하 중지를 요청했습니다. Pod 종료를 확인하세요.");
}
</script>
<style scoped>
.lab {
  gap: var(--ks-spacing-4);
}
.lab .native-panel {
  padding: var(--ks-spacing-4);
}
.lab h2 {
  margin: 0;
}
.lab-status {
  display: flex;
  flex-direction: column;
  gap: var(--ks-spacing-1);
}
.lab-status p {
  margin: 0;
}
.lab-status-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ks-spacing-2) var(--ks-spacing-3);
}
.lab-status-line strong {
  font-weight: var(--ks-font-weight-semibold);
  overflow-wrap: anywhere;
}
.lab-about summary,
.lab-more > summary {
  cursor: pointer;
  min-height: 44px;
  display: flex;
  align-items: center;
  gap: var(--ks-spacing-2);
  flex-wrap: wrap;
}
.lab-about summary::-webkit-details-marker,
.lab-more > summary::-webkit-details-marker {
  display: none;
}
.lab-about summary::before,
.lab-more > summary::before {
  content: "";
  width: 6px;
  height: 6px;
  border-right: 1.5px solid var(--ks-text-secondary);
  border-bottom: 1.5px solid var(--ks-text-secondary);
  transform: rotate(-45deg);
  transition: transform 0.15s cubic-bezier(0.23, 1, 0.32, 1);
  flex: none;
}
.lab-about[open] summary::before,
.lab-more[open] > summary::before {
  transform: rotate(45deg);
}
.lab-about summary:focus-visible,
.lab-more > summary:focus-visible {
  outline: 2px solid var(--ks-border-focus);
  outline-offset: 2px;
  border-radius: var(--ks-radius-xs);
}
.lab-about summary {
  min-height: 32px;
  list-style: none;
  font-size: var(--ks-font-size-sm);
  color: var(--ks-text-secondary);
}
.lab-more > summary {
  font-weight: var(--ks-font-weight-semibold);
  list-style: none;
}
.lab-more[open] > summary {
  margin-bottom: var(--ks-spacing-3);
}
.lab-more > * + * {
  margin-top: var(--ks-spacing-3);
}
.lab-controls {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--ks-spacing-2);
}
.lab-controls > * {
  margin: 0;
  max-width: 100%;
}
.lab-controls form {
  display: flex;
  flex-direction: column;
  gap: var(--ks-spacing-3);
  width: 100%;
}
.lab-edge {
  display: grid;
  grid-template-columns: minmax(0, 420px) minmax(0, 1fr);
  align-items: end;
  gap: var(--ks-spacing-2) var(--ks-spacing-4);
}
.lab-edge p {
  margin: 0 0 var(--ks-spacing-3);
}
.lab-controls :deep(.kel-form-item) {
  margin-bottom: 0;
}
.lab-groups {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ks-spacing-3) var(--ks-spacing-6);
}
.lab-group {
  border: 0;
  border-left: 2px solid var(--ks-border-default);
  margin: 0;
  padding: 0 0 0 var(--ks-spacing-3);
  min-width: 0;
}
.lab-group legend {
  padding: 0;
  margin-bottom: var(--ks-spacing-1);
  font-size: var(--ks-font-size-sm);
  color: var(--ks-text-secondary);
}
.lab-group .native-toolbar {
  align-items: flex-end;
  gap: var(--ks-spacing-2);
}
.lab-group :deep(.kel-input-number) {
  width: 120px;
}
.lab-warning {
  color: var(--ks-log-error);
}
.lab-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--ks-spacing-1) var(--ks-spacing-3);
  margin-bottom: var(--ks-spacing-3);
}
.lab-count {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}
.lab-lane {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(140px, 200px) minmax(0, 1fr);
  gap: var(--ks-spacing-3);
  align-items: stretch;
}
.lab-station {
  background: var(--ks-bg-surface);
  border: 1px solid var(--ks-border-default);
  border-radius: var(--ks-radius-base);
  padding: var(--ks-spacing-3) var(--ks-spacing-4);
  display: flex;
  flex-direction: column;
  gap: var(--ks-spacing-2);
  min-width: 0;
}
.lab-station.current {
  border-color: var(--ks-border-focus);
  box-shadow: inset 0 0 0 1px var(--ks-border-focus);
}
.lab-station:nth-of-type(2) {
  order: 3;
}
.lab-station header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--ks-spacing-2);
  min-height: 24px;
}
.lab-role {
  color: var(--ks-text-secondary);
  font-size: var(--ks-font-size-sm);
}
.lab-node-name {
  font-size: var(--ks-font-size-lg);
  font-weight: var(--ks-font-weight-semibold);
  overflow-wrap: anywhere;
}
.lab-cpu {
  display: grid;
  grid-template-columns: auto minmax(60px, 1fr);
  align-items: center;
  gap: var(--ks-spacing-1) var(--ks-spacing-2);
  font-size: var(--ks-font-size-sm);
  font-variant-numeric: tabular-nums;
}
.lab-cpu .native-note {
  grid-column: 1 / -1;
}
.lab-bar {
  position: relative;
  height: 6px;
  border-radius: var(--ks-radius-xs);
  background: var(--ks-border-default);
}
.lab-fill {
  position: absolute;
  inset: 0 auto 0 0;
  border-radius: var(--ks-radius-xs);
  background: var(--ks-chart-duration);
}
.lab-mark {
  position: absolute;
  top: -3px;
  bottom: -3px;
  width: 2px;
  margin-left: -1px;
  background: var(--ks-text-secondary);
}
.lab-pods {
  list-style: none;
  margin: 0;
  padding: var(--ks-spacing-2) 0 0;
  border-top: 1px solid var(--ks-border-subtle);
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
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.lab-track {
  order: 2;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--ks-spacing-2);
  text-align: center;
  font-size: var(--ks-font-size-sm);
}
.lab-arrow {
  font-size: var(--ks-font-size-4xl);
  line-height: 1;
  color: var(--ks-text-muted);
}
.lab-track.warning .lab-arrow {
  color: var(--ks-log-warn);
}
.lab-track.success .lab-arrow {
  color: var(--ks-border-success);
}
.lab-track.danger .lab-arrow {
  color: var(--ks-log-error);
}
.lab-facts {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  gap: var(--ks-spacing-1) var(--ks-spacing-3);
  margin: var(--ks-spacing-3) 0 0;
  font-size: var(--ks-font-size-sm);
}
.lab-facts div {
  display: contents;
}
.lab-facts dt {
  color: var(--ks-text-secondary);
}
.lab-facts dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.lab-events {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 320px;
  overflow: auto;
  font-size: var(--ks-font-size-sm);
}
.lab-events li {
  display: grid;
  grid-template-columns: 168px minmax(0, 1fr);
  gap: var(--ks-spacing-3);
  padding: var(--ks-spacing-2) 0 var(--ks-spacing-2) var(--ks-spacing-3);
  border-left: 2px solid var(--ks-border-default);
}
.lab-events li + li {
  border-top: 1px solid var(--ks-border-subtle);
}
.lab-events li.warning {
  border-left-color: var(--ks-log-warn);
}
.lab-events li.success {
  border-left-color: var(--ks-border-success);
}
.lab-events li.danger {
  border-left-color: var(--ks-log-error);
}
.lab-events time {
  color: var(--ks-text-secondary);
  font-variant-numeric: tabular-nums;
}
.lab-events li > span {
  overflow-wrap: anywhere;
}
.lab-policy {
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
}
code {
  font-size: var(--ks-font-size-xs);
}
@media (prefers-reduced-motion: reduce) {
  .lab-about summary::before,
  .lab-more > summary::before {
    transition: none;
  }
}
@media (max-width: 768px) {
  .lab-edge {
    grid-template-columns: minmax(0, 1fr);
  }
  .lab-edge p {
    margin: 0;
  }
  .lab-lane {
    grid-template-columns: minmax(0, 1fr);
  }
  .lab-arrow {
    transform: rotate(90deg);
  }
  .lab-count {
    margin-left: 0;
  }
  .lab-events li {
    grid-template-columns: minmax(0, 1fr);
    gap: 0;
  }
}
</style>
