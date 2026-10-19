<template>
  <TopNavBar title="실행 · 작업 · 결과"
    ><template #actions
      ><KsButton :disabled="busy" @click="act(refresh)"
        >새로고침</KsButton
      ></template
    ></TopNavBar
  >
  <main class="native-page">
    <NativeLoading :busy="busy" />
    <NativeFeedback :error="error || pollError" :notice="notice" />
    <NativeRecords
      title="최근 실행"
      :rows="items"
      :columns="[
        { key: 'id', label: '실행 ID', width: 300 },
        { key: 'state', label: '상태' },
        { key: 'mode', label: '실행 정책' },
        { key: 'createdAt', label: '요청 시각', width: 220 },
      ]"
      :busy="busy"
      :offset="offset"
      :nextOffset="nextOffset"
      @page="(value) => act(() => load(value))"
      ><template #id="{ row }"
        ><KsButton link @click="act(() => show(row.id))">{{
          row.id
        }}</KsButton></template
      ><template #state="{ row }"
        ><KsTag :type="tone(row.state)">{{ row.state }}</KsTag></template
      ><template #createdAt="{ row }">{{
        time(row.createdAt)
      }}</template></NativeRecords
    >
    <section v-if="detail" class="native-panel native-detail">
      <div class="native-toolbar">
        <h2>선택한 실행</h2>
        <KsTag :type="tone(detail.run.state)">{{ detail.run.state }}</KsTag
        ><KsButton
          @click="
            detail = null;
            task = null;
            results = null;
          "
          >상세 닫기</KsButton
        ><KsButton
          v-if="
            detail.run.parameters.workflowSourceBindings &&
            !terminal.has(detail.run.state)
          "
          :disabled="busy"
          @click="prepareCancel('workflow-runs', detail.run.id, 'drain')"
          >입력 종료 후 완료</KsButton
        ><KsButton
          v-if="!terminal.has(detail.run.state)"
          type="danger"
          :disabled="busy"
          @click="prepareCancel('workflow-runs', detail.run.id)"
          >실행 취소</KsButton
        >
      </div>
      <p class="native-note">
        {{ detail.run.id }} · Run·Task·Placement·결과를 3초마다 조회합니다.
      </p>
      <p class="native-note">
        {{
          detail.run.retry
            ? `최대 ${detail.run.retry.maxAttempts}회 · 대기 ${detail.run.retry.backoffSeconds}초 · 허용 ${detail.run.retry.maxElapsedSeconds}초`
            : "자동 재시도 없음"
        }}
      </p>
      <details v-if="detail.run.offload">
        <summary>자동 전환 정책</summary>
        <pre class="native-json">{{ pretty(detail.run.offload) }}</pre>
      </details>
      <NativeRecords
        title="작업별 실제 배치"
        :rows="placements.map((p) => ({ ...p, id: p.taskId }))"
        :columns="[
          { key: 'taskKey', label: '작업', width: 200 },
          { key: 'requested', label: '요청 정책' },
          { key: 'node', label: '실제 노드', width: 230 },
          { key: 'runtime', label: '실행 관측', width: 220 },
        ]"
        ><template #taskKey="{ row }"
          ><KsButton link @click="act(() => showTask(row.taskId))">{{
            row.taskKey
          }}</KsButton
          ><small>{{ row.taskState }}</small></template
        ><template #requested="{ row }">{{
          row.attempt
            ? `${row.attempt.mode} · 시도 ${row.attempt.number}`
            : "선행 작업 대기"
        }}</template
        ><template #node="{ row }">{{
          row.runtime?.nodeName ||
          (row.attempt?.mode === "REMOTE" ? "원격 제공자" : "배치 관측 대기")
        }}</template
        ><template #runtime="{ row }"
          >{{ row.runtime?.observedState || "Runtime 생성 전" }}
          {{ row.runtime?.failureReason }}
          <details v-if="row.runtime">
            <summary>Pod·Job 정보</summary>
            <p>
              {{ row.runtime.namespace }} /
              {{ row.runtime.jobName || "공유 실행" }}
            </p>
            <p>{{ row.runtime.podUid || "관측 전" }}</p>
          </details></template
        ></NativeRecords
      >
      <NativeRecords
        title="실행의 작업"
        :rows="detail.tasks"
        :columns="[
          { key: 'key', label: '작업 키' },
          { key: 'state', label: '상태' },
          { key: 'initialMode', label: '최초 배치' },
        ]"
        ><template #key="{ row }"
          ><KsButton link @click="act(() => showTask(row.id))">{{
            row.key
          }}</KsButton></template
        ><template #state="{ row }"
          ><KsTag :type="tone(row.state)">{{ row.state }}</KsTag>
          <p v-if="row.cancellationReason" class="native-note">
            {{ row.cancellationReason }}
          </p></template
        ><template #actions="{ row }"
          ><KsButton
            v-if="!terminal.has(row.state)"
            type="danger"
            :disabled="busy"
            @click="prepareCancel('tasks', row.id)"
            >작업 취소</KsButton
          ></template
        ></NativeRecords
      >
      <details>
        <summary>STREAM 경로</summary>
        <KsButton :disabled="busy" @click="act(() => loadStreams(0))"
          >경로 조회·새로고침</KsButton
        ><NativeRecords
          v-if="streams"
          title="스트림 경로"
          :rows="streams.items"
          :columns="[
            { key: 'source', label: '원본 / 출력', width: 240 },
            { key: 'target', label: '소비 작업 / 포트', width: 240 },
            { key: 'generation', label: '세대 상태', width: 220 },
            { key: 'maxPayloadBytes', label: '메시지 한도(B)' },
          ]"
          :offset="streamOffset"
          :nextOffset="streams.nextOffset"
          @page="(value) => act(() => loadStreams(value))"
          ><template #source="{ row }"
            >{{ taskName(row.sourceTaskId) || row.sourceDeviceId }} ·
            {{ row.sourcePort }}</template
          ><template #target="{ row }"
            >{{ taskName(row.consumerTaskId) }} ·
            {{ row.consumerPort }}</template
          ><template #generation="{ row }"
            >{{ row.generation?.state || "작업 배정 대기" }} ·
            {{ row.generation?.number ?? "—" }}
            <details>
              <summary>경로 상세</summary>
              <pre class="native-json">{{ pretty(row) }}</pre>
            </details></template
          ></NativeRecords
        >
      </details>
      <section v-if="task">
        <h2>작업 상세 · {{ task.task.key }}</h2>
        <NativeRecords
          title="실행 시도"
          :rows="task.attempts"
          :columns="[
            { key: 'number', label: '시도' },
            { key: 'state', label: '상태' },
            { key: 'cause', label: '시작 원인' },
            { key: 'mode', label: '실행 정책' },
            { key: 'nodeId', label: '실행 대상', width: 280 },
          ]"
          ><template #nodeId="{ row }">{{
            nodes.find((n) => n.id === row.nodeId)?.name ||
            row.vdId ||
            row.remoteTarget?.providerKey ||
            "자동 배치"
          }}</template></NativeRecords
        >
        <h3>실행 측정</h3>
        <p v-if="task.attempts[0]?.mode === 'REMOTE'" class="native-note">
          Remote 실행은 자원·지연 측정을 지원하지 않습니다.
        </p>
        <p v-else-if="!task.telemetry" class="native-note">
          현재 시도의 측정값이 없습니다.
        </p>
        <template v-else
          ><p class="native-note">
            {{
              task.attempts[0]?.state !== "RUNNING"
                ? "종료된 시도의 마지막 측정"
                : Date.now() - Date.parse(task.telemetry.observedAt) > 60000
                  ? "측정 만료"
                  : "최근 수집된 측정"
            }}
            · {{ time(task.telemetry.observedAt) }}
          </p>
          <dl>
            <div>
              <dt>CPU</dt>
              <dd>{{ cpu(task.telemetry) }}</dd>
            </div>
            <div>
              <dt>메모리</dt>
              <dd>
                {{
                  task.telemetry.memoryBytes == null
                    ? "미수집"
                    : `${(task.telemetry.memoryBytes / 1048576).toFixed(1)} MiB`
                }}
                /
                {{
                  task.telemetry.memoryLimitBytes == null
                    ? "제한 미확인"
                    : `${(task.telemetry.memoryLimitBytes / 1048576).toFixed(1)} MiB`
                }}
              </dd>
            </div>
            <div>
              <dt>서비스 지연</dt>
              <dd>
                {{
                  task.telemetry.latencyMicros == null
                    ? "미수집"
                    : `${(task.telemetry.latencyMicros / 1000).toFixed(2)} ms`
                }}
              </dd>
            </div>
          </dl>
          <p class="native-note">
            실행 컨테이너의 측정입니다. 노드 잔여 자원이나 p95/p99가 아닙니다.
          </p></template
        >
        <details v-if="task.vdInputs">
          <summary>
            실행에 고정된 센서 입력 · {{ time(task.vdInputs.capturedAt) }}
          </summary>
          <pre class="native-json">{{ pretty(task.vdInputs) }}</pre>
        </details>
        <h3>실행 위치 전환</h3>
        <p class="native-note">
          RESTART는 같은 입력으로 재실행하고, 확정된 체크포인트가 있는 STREAM은
          연결 작업과 함께 재개합니다.
        </p>
        <KsButton
          v-if="
            task.task.state === 'RUNNING' &&
            task.attempts.some((a) => a.state === 'RUNNING')
          "
          :disabled="busy"
          @click="prepareOffload"
          >실행 위치 전환</KsButton
        >
        <details v-for="operation in task.offloads || []" :key="operation.id">
          <summary>
            {{ operation.state }} · {{ operation.trigger || "MANUAL" }} ·
            {{ operation.failureReason }}
          </summary>
          <pre class="native-json">{{ pretty(operation) }}</pre>
        </details>
        <section v-if="results">
          <h3>검증된 결과</h3>
          <p v-if="!results.items.length" class="native-note">
            아직 확정된 결과가 없습니다.
          </p>
          <section
            v-for="result in results.items"
            :key="result.id"
            class="native-panel"
          >
            <p class="native-note">
              파일 검증 완료 · {{ time(result.createdAt) }}
              {{
                result.remoteSourceMode === "SYNTHETIC"
                  ? "· Remote 참조 계산 · 실장비 검증 아님"
                  : ""
              }}
            </p>
            <NativeRecords
              title="검증된 출력 파일"
              :rows="result.artifacts.map((a) => ({ ...a, id: a.port }))"
              :columns="[
                { key: 'port', label: '출력 포트' },
                { key: 'bytes', label: '크기 (bytes)' },
                { key: 'mediaType', label: '형식' },
              ]"
            /><ResultContent
              v-for="artifact in result.artifacts"
              :key="`${result.id}/${artifact.port}`"
              :taskId="task.task.id"
              :port="artifact.port"
              :supported="
                artifact.mediaType === 'application/json' &&
                artifact.bytes <= 1048576
              "
            />
            <details>
              <summary>결과·시도·체크섬·파일 버전</summary>
              <pre class="native-json">{{ pretty(result) }}</pre>
            </details>
          </section>
        </section>
      </section>
      <details>
        <summary>실행 매개변수·전체 상세</summary>
        <pre class="native-json">{{ runJson }}</pre>
      </details>
    </section>
  </main>
  <ConfirmAction
    v-model="cancelOpen"
    :title="
      cancelTarget?.action === 'drain' ? '입력 종료 후 완료' : '실행 취소 요청'
    "
    :subject="cancelTarget?.id"
    :description="
      cancelTarget?.action === 'drain'
        ? '입력 수신을 종료하고 이미 받은 데이터를 처리한 뒤 정상 완료합니다.'
        : '실행 전체 또는 선택한 작업과 연결·후속 작업을 취소합니다. 독립된 분기는 계속 실행하며 실제 종료 상태를 조회합니다.'
    "
    :confirmLabel="cancelTarget?.action === 'drain' ? '입력 종료' : '취소 요청'"
    :danger="cancelTarget?.action !== 'drain'"
    :busy="commandBusy"
    :error="commandError"
    @confirm="cancel"
  />
  <KsDrawer
    v-model="offloadOpen"
    title="실행 위치 전환"
    class="sm native-dialog"
    :beforeClose="closeOffload"
    :showClose="!commandBusy"
    :closeOnPressEscape="!commandBusy"
    :closeOnClickModal="!commandBusy"
    ><NativeFeedback :error="commandError" /><KsForm
      labelPosition="top"
      @submit.prevent="offload"
      ><KsFormItem label="전환 대상 종류"
        ><KsSelect :disabled="commandBusy" v-model="offloadMode"
          ><KsOption value="NODE" label="Kubernetes 노드" /><KsOption
            value="REMOTE"
            label="Remote 제공자" /></KsSelect></KsFormItem
      ><KsFormItem v-if="offloadMode === 'NODE'" label="전환할 노드"
        ><KsSelect :disabled="commandBusy" v-model="offloadNode"
          ><KsOption
            v-for="node in nodes"
            :key="node.id"
            :value="node.id"
            :label="`${node.name} · ${node.status}`"
            :disabled="node.status !== 'READY'" /></KsSelect></KsFormItem
      ><KsFormItem v-else label="Remote 제공자 key"
        ><KsInput
          :disabled="commandBusy"
          v-model="offloadProvider"
          required /></KsFormItem
      ><KsFormItem label="이전 실행 종료 제한 (초)"
        ><KsInput
          :disabled="commandBusy"
          v-model="drainTimeout"
          type="number"
          min="1"
          max="600"
          required /></KsFormItem
      ><KsFormItem label="새 실행 시작 제한 (초)"
        ><KsInput
          :disabled="commandBusy"
          v-model="startTimeout"
          type="number"
          min="1"
          max="600"
          required
      /></KsFormItem>
      <p class="native-note">
        응답 오류 시 같은 요청 키를 유지합니다. {{ offloadDraft?.key }}
      </p>
      <KsButton
        nativeType="submit"
        :disabled="commandBusy"
        :loading="commandBusy"
        type="primary"
        >전환 요청</KsButton
      ></KsForm
    ></KsDrawer
  >
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { parse } from "lossless-json";
import { useRoute, useRouter } from "vue-router";
import {
  KsButton,
  KsDrawer,
  KsForm,
  KsFormItem,
  KsInput,
  KsSelect,
  KsOption,
  KsTag,
} from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import {
  get,
  request,
  write,
  all,
  pretty,
  time,
  requestKey,
  useAction,
  usePoll,
  type Schema,
} from "./native-api";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
import NativeRecords from "./NativeRecords.vue";
import ConfirmAction from "./ConfirmAction.vue";
import ResultContent from "./ResultContent.vue";
const route = useRoute(),
  router = useRouter();
useRouteContext(computed(() => ({ title: "실행 · 작업 · 결과" })));
const { busy, error, notice, act } = useAction();
const items = ref<Schema["WorkflowRun"][]>([]),
  offset = ref(0),
  nextOffset = ref<number | null>(null),
  detail = ref<Schema["RunDetail"] | null>(null),
  task = ref<Schema["TaskDetail"] | null>(null),
  results = ref<Schema["TaskResults"] | null>(null),
  placements = ref<Schema["Placement"][]>([]),
  nodes = ref<Schema["ExecutionNode"][]>([]),
  pollError = ref(""),
  runJson = ref(""),
  streams = ref<Schema["StreamRoutePage"] | null>(null),
  streamOffset = ref(0);
const terminal = new Set(["SUCCEEDED", "FAILED", "CANCELLED", "SKIPPED"]);
const tone = (state: string) =>
  state === "SUCCEEDED"
    ? "success"
    : state === "FAILED"
      ? "danger"
      : state === "CANCELLED"
        ? "warning"
        : "info";
const taskName = (id: string) =>
  detail.value?.tasks.find((t) => t.id === id)?.key || id;
async function load(value = offset.value) {
  const page = await get<Schema["RunPage"]>(
    `workflow-runs?limit=20&offset=${value}`,
  );
  items.value = page.items;
  offset.value = value;
  nextOffset.value = page.nextOffset;
}
async function readRun(id: string) {
  const text = await (await request(`workflow-runs/${id}`)).text();
  return {
    detail: JSON.parse(text) as Schema["RunDetail"],
    json: pretty(parse(text)),
  };
}
let selectionGeneration = 0,
  taskGeneration = 0;
async function show(id: string) {
  const generation = ++selectionGeneration;
  ++taskGeneration;
  const [run, where] = await Promise.all([
    readRun(id),
    get<Schema["Placement"][]>(`workflow-runs/${id}/placements`),
  ]);
  if (generation !== selectionGeneration) return;
  detail.value = run.detail;
  runJson.value = run.json;
  placements.value = where;
  task.value = null;
  results.value = null;
  streams.value = null;
  pollError.value = "";
  await router.replace({ query: { run: id } });
}
async function showTask(id: string) {
  const generation = ++taskGeneration,
    run = detail.value?.run.id,
    selection = selectionGeneration;
  const [value, result] = await Promise.all([
    get<Schema["TaskDetail"]>(`tasks/${id}`),
    get<Schema["TaskResults"]>(`tasks/${id}/results`),
  ]);
  if (
    generation !== taskGeneration ||
    selection !== selectionGeneration ||
    detail.value?.run.id !== run
  )
    return;
  task.value = value;
  results.value = result;
}
async function loadStreams(value: number) {
  if (!detail.value) return;
  streams.value = await get<Schema["StreamRoutePage"]>(
    `workflow-runs/${detail.value.run.id}/streams?limit=20&offset=${value}`,
  );
  streamOffset.value = value;
}
async function refresh() {
  const selectedTaskGeneration = taskGeneration;
  await load();
  if (detail.value) {
    const id = detail.value.run.id,
      taskId = task.value?.task.id;
    const [run, where] = await Promise.all([
      readRun(id),
      get<Schema["Placement"][]>(`workflow-runs/${id}/placements`),
    ]);
    if (detail.value?.run.id !== id) return;
    detail.value = run.detail;
    runJson.value = run.json;
    placements.value = where;
    if (
      taskId &&
      task.value?.task.id === taskId &&
      selectedTaskGeneration === taskGeneration
    )
      await showTask(taskId);
  }
}
function cpu(s: Schema["RuntimeTelemetry"]) {
  return s.cpuUsageMicros == null
    ? "미수집"
    : s.cpuLimitMillicores == null ||
        s.cpuLimitMillicores <= 0 ||
        s.intervalMillis <= 0
      ? `${(s.cpuUsageMicros / 1000).toFixed(1)} ms 사용 · 제한 미확인`
      : `${((100 * s.cpuUsageMicros) / (s.intervalMillis * s.cpuLimitMillicores)).toFixed(1)}% · CPU 제한 기준`;
}
const cancelOpen = ref(false),
  cancelTarget = ref<{
    kind: "tasks" | "workflow-runs";
    id: string;
    action: "cancel" | "drain";
  } | null>(null),
  commandBusy = ref(false),
  commandError = ref("");
function prepareCancel(
  kind: "tasks" | "workflow-runs",
  id: string,
  action: "cancel" | "drain" = "cancel",
) {
  cancelTarget.value = { kind, id, action };
  commandError.value = "";
  cancelOpen.value = true;
}
async function cancel() {
  if (!cancelTarget.value || commandBusy.value) return;
  commandBusy.value = true;
  commandError.value = "";
  try {
    await write(
      `${cancelTarget.value.kind}/${cancelTarget.value.id}/${cancelTarget.value.action}`,
      "POST",
      {},
    );
    cancelOpen.value = false;
    notice.value = "요청을 반영했습니다. 실제 작업 상태를 확인하세요.";
    await refresh();
  } catch (cause) {
    commandError.value =
      cause instanceof Error ? cause.message : "취소 요청 실패";
  } finally {
    commandBusy.value = false;
  }
}
const offloadOpen = ref(false),
  offloadMode = ref("NODE"),
  offloadNode = ref(""),
  offloadProvider = ref("reference"),
  drainTimeout = ref(60),
  startTimeout = ref(120),
  offloadDraft = ref<{
    taskId: string;
    sourceAttemptId: string;
    key: string;
  } | null>(null);
function prepareOffload() {
  const attempt = task.value?.attempts.find((a) => a.state === "RUNNING");
  if (!task.value || !attempt) return;
  offloadDraft.value = {
    taskId: task.value.task.id,
    sourceAttemptId: attempt.id,
    key: requestKey(),
  };
  commandError.value = "";
  offloadOpen.value = true;
}
function closeOffload(done: () => void) {
  if (!commandBusy.value) done();
}
async function offload() {
  if (!offloadDraft.value || commandBusy.value) return;
  commandBusy.value = true;
  commandError.value = "";
  try {
    if (offloadMode.value === "NODE" && !offloadNode.value)
      throw new Error("전환할 노드를 선택하세요.");
    await write(
      `tasks/${offloadDraft.value.taskId}/offload`,
      "POST",
      {
        sourceAttemptId: offloadDraft.value.sourceAttemptId,
        ...(offloadMode.value === "REMOTE"
          ? { targetProviderKey: offloadProvider.value }
          : { targetNodeId: offloadNode.value }),
        drainTimeoutSeconds: Number(drainTimeout.value),
        startTimeoutSeconds: Number(startTimeout.value),
      },
      offloadDraft.value.key,
    );
    offloadOpen.value = false;
    notice.value =
      "전환 요청을 접수했습니다. 이전 실행 종료와 새 실행 시작 상태를 확인하세요.";
    await refresh();
  } catch (cause) {
    commandError.value =
      cause instanceof Error ? cause.message : "전환 요청 실패";
  } finally {
    commandBusy.value = false;
  }
}
onMounted(() => {
  void act(async () => {
    nodes.value = await all<Schema["ExecutionNode"]>("nodes");
    await load(0);
    if (typeof route.query.run === "string") await show(route.query.run);
  });
});
usePoll(async () => {
  if (!busy.value && !commandBusy.value && detail.value) {
    try {
      await refresh();
      pollError.value = "";
    } catch (cause) {
      pollError.value =
        (cause instanceof Error ? cause.message : "최신 상태 조회 실패") +
        " · 마지막 조회 결과입니다.";
    }
  }
}, 3000);
</script>
