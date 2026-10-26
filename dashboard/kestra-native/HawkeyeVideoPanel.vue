<template>
  <div class="hv">
    <NativeLoading :busy="loading && !capability" label="영상 점검을 불러오는 중…" />
    <NativeFeedback :error="loadError || pollError" />
    <section v-if="capability" class="native-panel hv-control" aria-labelledby="hv-control-title">
      <div class="native-toolbar hv-head">
        <h2 id="hv-control-title">영상 안전 점검</h2>
        <div class="hv-views" role="group" aria-label="영상 보기">
          <button v-for="v in views" :key="v.id" type="button" :aria-pressed="view === v.id" @click="setView(v.id)">{{ v.label }}</button>
        </div>
        <KsButton :disabled="loading" @click="loadAll(() => refresh())">새로고침</KsButton>
      </div>
      <p class="native-note">{{ intro }}</p>
      <div class="hv-grid">
        <div class="hv-stage">
          <h3 v-if="view === 'history'" class="hv-stage-title">구간 {{ windowSeq }} {{ motion ? "관측 기록" : "판정 결과" }}</h3>
          <MotionLive
            v-if="view === 'live' && session"
            :key="session.id"
            :sessionId="session.id"
            @frame="(o) => (current = o)"
            @live="(v) => (liveObserver = v.observer)"
          />
          <template v-else-if="view === 'history' && selectedWindow">
            <p v-if="framesNote" class="native-note">{{ framesNote }}</p>
            <MotionHistory
              :taskId="frameTaskId"
              :items="historyItems"
              :label="`구간 ${windowSeq}`"
              :empty="framesNote || '표시할 프레임이 없습니다.'"
              @frame="(f) => (current = f?.observation ?? null)"
            />
          </template>
          <MotionStage
            v-else
            :src="preview || null"
            :alt="`${camera?.id ?? '카메라'} 최신 영상`"
            :placeholder="previewMessage"
            :badge="preview ? { text: '카메라 미리보기', tone: 'info' } : null"
          >
            <span aria-live="polite">
              <template v-if="previewAt">{{ time(previewAt) }} 수신 · 2초마다 갱신 · 추적 표시 없음</template>
              <template v-else-if="previewError">{{ previewError }}</template>
            </span>
          </MotionStage>
        </div>
        <div class="hv-side">
          <MotionRisk v-if="motion && view !== 'preview' && session" :observation="current" />
          <KsFormItem label="카메라">
            <KsSelect v-model="cameraId" :disabled="running || starting" placeholder="카메라를 선택하세요" aria-label="카메라">
              <KsOptionGroup v-for="g in sourceGroups(cameras)" :key="g.label" :label="g.label">
                <KsOption v-for="{ camera: c, option } in g.items" :key="c.id" :value="c.id" :label="`${option} · ${cameraState(c).label}`" />
              </KsOptionGroup>
            </KsSelect>
          </KsFormItem>
          <div v-if="camera" class="hv-camera">
            <KsTag :type="cameraState(camera).online ? 'success' : 'danger'">{{ cameraState(camera).label }}</KsTag>
            <span>{{ source!.kind }}</span>
            <span v-if="source!.scene" class="native-note">{{ source!.group }} · {{ source!.option }}</span>
            <span v-if="cameraState(camera).reason" class="native-note">{{ cameraState(camera).reason }}</span>
            <details class="hv-more">
              <summary>원본·Mapper</summary>
              <dl class="hv-facts">
                <div v-for="[k, v] in source!.details" :key="k"><dt>{{ k }}</dt><dd>{{ v }}</dd></div>
                <div><dt>장치</dt><dd>{{ camera.id }}</dd></div>
                <div><dt>Mapper 노드</dt><dd>{{ camera.targetNode }}</dd></div>
                <div><dt>원본 주소</dt><dd class="hv-mono">{{ sourceAddress(camera) }}</dd></div>
              </dl>
            </details>
          </div>
          <p v-else-if="!cameras.length" class="native-notice">등록된 RTSP 카메라가 없습니다. 장치 관리에서 카메라를 등록하세요.</p>
          <KsFormItem label="워크플로 버전">
            <KsSelect v-model="versionId" :disabled="running || starting || !capability.versions.length" placeholder="발행된 버전 없음" aria-label="워크플로 버전">
              <KsOption v-for="v in capability.versions" :key="v.id" :value="v.id" :label="versionText(v)" />
            </KsSelect>
          </KsFormItem>
          <div class="native-toolbar">
            <KsButton v-if="!running" type="primary" :disabled="!canStart" @click="start">점검 시작</KsButton>
            <KsButton v-else type="danger" :disabled="stopping" @click="stop">점검 중지</KsButton>
            <RouterLink v-if="capability.workflow" :to="{ path: '/ui/main/workflows', query: { workflow: capability.workflow.id } }">워크플로 정의</RouterLink>
          </div>
          <p class="native-note" role="status">{{ startHelp }}</p>
          <p v-if="modeOf(versionId) === 'MOTION'" class="native-note">
            분석 결과는 구간을 기다리지 않고 프레임마다 바로 영상 위에 표시합니다. 10초 구간은 관측 기록을 저장하는 단위입니다.
          </p>
          <NativeFeedback :error="actionError" />
        </div>
      </div>
      <div v-if="session" class="hv-session" aria-labelledby="hv-session-title">
        <p class="hv-summary" aria-live="polite">
          <strong id="hv-session-title">점검 세션</strong>
          <KsTag :type="tone(session.state)">{{ sessionLabel(session.state) }}</KsTag>
          {{ summary }}
        </p>
        <p v-if="session.failureCode" role="alert" class="native-notice">
          {{ session.failureCode }}{{ session.failureMessage ? ` · ${session.failureMessage}` : "" }}
        </p>
        <div class="hv-windows" role="group" aria-label="처리 구간">
          <button
            v-for="(w, i) in windowsAsc"
            :key="w.seq"
            type="button"
            :class="['hv-window', tone(w.runState), { selected: view === 'history' && w.seq === windowSeq }]"
            :aria-pressed="view === 'history' && w.seq === windowSeq"
            @click="selectWindow(w.seq)"
          >
            <strong>구간 {{ w.seq }}</strong>
            <span>{{ statusLabel(w.runState) }}</span>
            <small v-if="gaps[i] != null">이전 구간 시작 후 {{ (gaps[i]! / 1000).toFixed(1) }}초</small>
          </button>
        </div>
      </div>
    </section>

    <section v-if="session && graph" class="native-panel" aria-labelledby="hv-graph-title">
      <div class="native-toolbar">
        <h2 id="hv-graph-title">구간 {{ windowSeq }} 실행 흐름</h2>
        <RouterLink v-if="selectedWindow" :to="{ path: '/ui/main/runs', query: { run: selectedWindow.runId } }">실행 상세 열기</RouterLink>
      </div>
      <p class="native-note">
        점선 상자는 워크플로 작업이 아닌 외부 구성 요소입니다.{{ motion ? " 관측기는 프레임 수신·탐지·추적·예측을 한 서비스에서 계속 실행하고, 구간 작업이 그 관측 기록을 저장합니다." : "" }}
        작업 위치는 실제 배치 기록만 표시합니다.
      </p>
      <div class="hv-flow">
        <PipelineGraph :nodes="graph.nodes" :edges="graph.edges" :selected="nodeId" @select="(id) => (nodeId = id)" />
        <aside v-if="node" class="hv-node" aria-live="polite" :aria-label="`${node.label} 상세`">
          <small>{{ node.kindLabel }}</small>
          <h3>{{ node.label }} <KsTag :type="node.tone">{{ statusLabel(node.status) }}</KsTag></h3>
          <dl>
            <div v-for="[k, v] in node.details" :key="k"><dt>{{ k }}</dt><dd>{{ v }}</dd></div>
          </dl>
          <p v-if="!node.details.length" class="native-note">아직 관측된 정보가 없습니다.</p>
        </aside>
      </div>
    </section>

    <section class="native-panel hv-history">
      <NativeRecords
        title="점검 기록"
        :rows="historyRows"
        :columns="[
          { key: 'created', label: '시작', width: 170 },
          { key: 'camera', label: '카메라', width: 170 },
          { key: 'state', label: '상태', width: 110 },
          { key: 'windows', label: '구간', width: 80 },
          { key: 'ended', label: '종료', width: 170 },
        ]"
        :offset="historyOffset"
        :nextOffset="historyNext"
        :pageSize="PAGE"
        @page="page"
        ><template #created="{ row }"
          ><KsButton link :aria-pressed="row.id === session?.id" @click="loadAll(() => showSession(row.id))">{{ row.created }}</KsButton></template
        ></NativeRecords
      >
    </section>
  </div>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { KsButton, KsFormItem, KsOption, KsOptionGroup, KsSelect, KsTag } from "@kestra-io/design-system";
import { get, request, requestKey, time, useAction, usePoll, write, type Schema } from "./native-api";
import { categoryText } from "./hawkeye";
import {
  cameraState,
  defaultSource,
  motionFrames,
  pinnedSource,
  pipelineGraph,
  readDetections,
  readObservations,
  versionMode,
  sessionLabel,
  sourceAddress,
  sourceGroups,
  sourceView,
  statusLabel,
  tone,
  windowStartIntervals,
  type Camera,
  type CameraBinding,
  type DetectionsView,
  type HistoryFrame,
  type Observation,
  type ObservationsView,
  type ObserverIdentity,
  type VideoCapability,
  type VideoSession,
} from "./hawkeye-video";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
import NativeRecords from "./NativeRecords.vue";
import PipelineGraph from "./PipelineGraph.vue";
import MotionHistory from "./MotionHistory.vue";
import MotionLive from "./MotionLive.vue";
import MotionRisk from "./MotionRisk.vue";
import MotionStage from "./MotionStage.vue";

const BASE = "scenarios/hawkeye-safety/video";
const PAGE = 10;
const route = useRoute(),
  router = useRouter();
const { busy: loading, error: loadError, act: loadAll } = useAction();
const { busy: acting, error: actionError, act } = useAction();
const capability = ref<VideoCapability | null>(null),
  cameras = ref<Camera[]>([]),
  accelerator = ref<Schema["HawkeyeSafetyScenario"]["runtime"] | null>(null),
  dags = ref<Record<string, Schema["WorkflowDag"]>>({}),
  session = ref<VideoSession | null>(null),
  history = ref<VideoSession[]>([]),
  historyOffset = ref(0),
  historyNext = ref<number | null>(null),
  cameraId = ref(""),
  versionId = ref(""),
  windowSeq = ref<number | null>(null),
  run = ref<Schema["RunDetail"] | null>(null),
  placements = ref<Schema["Placement"][]>([]),
  detections = ref<DetectionsView | null>(null),
  observations = ref<ObservationsView | null>(null),
  view = ref<"preview" | "live" | "history">("preview"),
  current = ref<Observation | null>(null),
  liveObserver = ref<ObserverIdentity | null>(null),
  nodeId = ref("task:infer"),
  pollError = ref(""),
  preview = ref(""),
  previewAt = ref(""),
  previewError = ref(""),
  stopping = ref(false);
let startKey = requestKey(),
  polling = false,
  previewing = false,
  alive = true,
  ticks = 0,
  picked = false;

const modeOf = (id: string | undefined) => versionMode(capability.value?.versions.find((v) => v.id === id) ?? {});
const motion = computed(() => (session.value ? (session.value.mode ?? modeOf(session.value.workflowVersionId)) : modeOf(versionId.value)) === "MOTION");
const versionText = (v: VideoCapability["versions"][number]) =>
  `${capability.value?.workflow?.displayName ?? capability.value?.workflowKey} v${v.version} · ${versionMode(v) === "MOTION" ? "지게차·사람 추적" : "프레임 판정"}`;
const intro = computed(() =>
  modeOf(versionId.value) === "MOTION"
    ? "관측기가 카메라 영상을 계속 받아 지게차·사람을 추적하고 3초 앞 경로를 예측합니다. 구간마다 워크플로 작업이 그 관측 기록을 저장합니다."
    : "카메라 영상을 정해진 길이의 구간으로 나눠 발행된 워크플로(프레임 수집 → Hawkeye 판정)를 차례로 실행합니다. 구간 사이에 들어온 영상은 판정하지 않습니다.",
);
const canLive = computed(() => motion.value && session.value?.state === "RUNNING");
const views = computed(() => [
  canLive.value ? { id: "live" as const, label: "실시간 관측" } : { id: "preview" as const, label: "카메라 미리보기" },
  ...(session.value?.windows.length ? [{ id: "history" as const, label: "구간 기록" }] : []),
]);
function setView(next: "preview" | "live" | "history") {
  if (view.value === next) return;
  view.value = next;
  current.value = null;
}
const starting = computed(() => acting.value && !stopping.value);
const camera = computed(() => cameras.value.find((c) => c.id === cameraId.value));
const source = computed(() => (camera.value ? sourceView(camera.value) : null));
const running = computed(() => session.value?.state === "RUNNING");
const canStart = computed(
  () =>
    !!capability.value?.enabled &&
    capability.value.executes &&
    !capability.value.activeSessionId &&
    !!camera.value &&
    cameraState(camera.value).online &&
    !!versionId.value &&
    !acting.value,
);
const startHelp = computed(() => {
  const c = capability.value;
  if (running.value) return "점검 중입니다. 중지하면 진행 중인 구간을 취소하고 새 구간을 만들지 않습니다.";
  if (c?.observer && modeOf(versionId.value) === "MOTION" && c.observer.status !== "READY")
    return `관측기 ${statusLabel(c.observer.status)}${c.observer.error ? ` · ${c.observer.error}` : ""} · 시작하면 서버가 거부할 수 있습니다.`;
  if (!c?.enabled) return "영상 점검이 이 환경에 설정되지 않았습니다.";
  if (!c.executes) return "워크플로 실행 환경이 꺼져 있어 시작할 수 없습니다.";
  if (c.activeSessionId) return "다른 점검이 진행 중입니다. 그 점검을 중지한 뒤 시작하세요.";
  if (!c.versions.length) return "발행된 워크플로 버전이 없습니다.";
  if (camera.value && !cameraState(camera.value).online) return `카메라를 사용할 수 없습니다 · ${cameraState(camera.value).reason ?? cameraState(camera.value).label}`;
  return "시작하면 첫 구간이 바로 실행되고, 구간이 끝날 때마다 다음 구간을 만듭니다.";
});
const windowsAsc = computed(() => [...(session.value?.windows ?? [])].sort((a, b) => a.seq - b.seq));
const gaps = computed(() => windowStartIntervals(windowsAsc.value.map((w) => w.createdAt)));
const selectedWindow = computed(() => session.value?.windows.find((w) => w.seq === windowSeq.value) ?? null);
const summary = computed(() => {
  const s = session.value;
  if (!s) return "";
  const done = s.windows.filter((w) => w.runState === "SUCCEEDED").length;
  return `${s.camera.id} · ${time(s.createdAt)} 시작 · 구간 ${s.windowCount}/${s.maxWindows} 생성, ${done}개 완료${s.endedAt ? ` · ${time(s.endedAt)} 종료` : ""}${s.endReason ? ` (${sessionLabel(s.endReason)})` : ""}`;
});
const dag = computed(() => (session.value ? dags.value[session.value.workflowVersionId] : undefined));
// The motion workflow (v2) has one snapshot Task that stores the window's frames and observations.
const frameTaskKey = computed(() =>
  motion.value ? (dag.value?.tasks.length === 1 ? dag.value.tasks[0].key : "snapshot") : "capture",
);
const frameTaskId = computed(() => run.value?.tasks.find((t) => t.key === frameTaskKey.value)?.id ?? null);
const graph = computed(() => {
  if (!dag.value || !session.value) return null;
  const observed = detections.value?.accelerator;
  const reported = accelerator.value?.reported;
  const recorded = observations.value?.observer;
  const live = view.value === "live" ? liveObserver.value : null;
  const reportedObserver = capability.value?.observer;
  return pipelineGraph({
    dag: dag.value,
    placements: placements.value,
    // The selected window's own pinned camera; the live camera list only supplies the CURRENT connection row.
    source: pinnedSource(
      run.value?.run.parameters.cameraSource as CameraBinding | undefined,
      cameras.value.find((c) => c.id === session.value!.camera.id),
    ),
    // Identity recorded with this window's frames wins over the runtime's current report.
    accelerator: motion.value
      ? null
      : observed
      ? { ...observed, status: "ANSWERED", basis: "이 구간의 판정 기록" }
      : reported
        ? { nodeName: reported.nodeName ?? null, podUid: null, processor: reported.processor ?? null, status: accelerator.value!.status, basis: "현재 런타임 보고" }
        : null,
    sourceTask: frameTaskKey.value,
    acceleratorTask: motion.value ? "" : "infer",
    result: run.value
      ? {
          state: (motion.value ? observations.value : detections.value) ? "VERIFIED" : run.value.run.state === "FAILED" ? "FAILED" : "PENDING",
          verified: historyItems.value.length,
        }
      : null,
    observer: motion.value
      ? recorded
        ? { ...recorded, status: "RECORDED", basis: "이 구간의 관측 기록" }
        : live
          ? { ...live, status: "RUNNING", basis: "실시간 관측 응답" }
          : reportedObserver
            ? { ...reportedObserver, basis: "현재 관측기 보고" }
            : null
      : undefined,
  });
});
const frames = computed(() => detections.value?.frames ?? []);
const node = computed(() => graph.value?.nodes.find((n) => n.id === nodeId.value));
const historyItems = computed<HistoryFrame[]>(() =>
  motion.value
    ? motionFrames(observations.value?.frames ?? [])
    : frames.value.map((f) => ({
        seq: f.seq,
        frameTime: f.frameTime,
        observation: null,
        tone: f.category == null ? "danger" : "none",
        caption: categoryText(f),
        note: [f.explanation, f.inferenceMs == null ? null : `${f.inferenceMs.toFixed(0)} ms${f.roundTripMs == null ? "" : ` · 왕복 ${f.roundTripMs.toFixed(0)} ms`}`]
          .filter(Boolean)
          .join(" · "),
        unread: f.category == null,
      })),
);
const framesNote = computed(() => {
  if (!run.value) return "실행 정보를 불러오는 중입니다.";
  if (motion.value) {
    const o = observations.value;
    if (!o) return ["FAILED", "CANCELLED"].includes(run.value.run.state) ? "이 구간은 결과 없이 끝났습니다." : "관측 기록이 확정되면 표시합니다.";
    const span = o.window ? ` · 영상 ${time(o.window.startedAt)} ~ ${time(o.window.finishedAt)}` : "";
    const evicted = o.window?.evicted ? ` · 보관 범위를 넘어 ${o.window.evicted}장 제외` : "";
    return `검증된 관측 기록 · 프레임 ${o.frames.length}장${span}${evicted}`;
  }
  const d = detections.value;
  if (!d)
    return ["FAILED", "CANCELLED"].includes(run.value.run.state) ? "이 구간은 결과 없이 끝났습니다." : "판정 결과가 확정되면 표시합니다.";
  const span = d.window ? ` · 영상 ${time(d.window.startedAt)} ~ ${time(d.window.finishedAt)} 사이에서 서로 다른 장면 ${d.window.distinctFrames}/${d.window.requestedFrames}장` : "";
  const failed = d.frames.filter((f) => f.category == null).length;
  return `검증된 결과 · 프레임 ${d.frames.length}장${failed ? `, 판정 실패 ${failed}장` : ""}${span}`;
});
const previewMessage = computed(() =>
  !camera.value ? "카메라를 선택하세요." : cameraState(camera.value).online ? "영상 수신 확인 중…" : "카메라 연결이 끊겨 영상을 받을 수 없습니다.",
);
const historyRows = computed(() =>
  history.value.map((s) => ({
    id: s.id,
    created: time(s.createdAt),
    camera: s.camera.id,
    state: sessionLabel(s.state),
    windows: s.windowCount,
    ended: s.endedAt ? time(s.endedAt) : "—",
  })),
);

async function loadDag(versionId: string, workflowId: string) {
  if (dags.value[versionId]) return;
  const detail = await get<Schema["WorkflowDetail"]>(`workflows/${workflowId}`);
  for (const v of detail.versions) dags.value[v.id] = v.dag;
}
async function loadHistory() {
  const page = await get<{ items: VideoSession[]; nextOffset: number | null }>(`${BASE}/sessions?limit=${PAGE}&offset=${historyOffset.value}`);
  history.value = page.items;
  historyNext.value = page.nextOffset;
}
async function refresh() {
  const [cap, devices, safety] = await Promise.all([
    get<VideoCapability>(BASE),
    get<{ items: Camera[] }>("managed-devices"),
    get<Schema["HawkeyeSafetyScenario"]>("scenarios/hawkeye-safety"),
  ]);
  capability.value = cap;
  cameras.value = devices.items.filter((d) => d.profile.protocol === "rtsp");
  accelerator.value = safety.runtime;
  if (!cameraId.value) cameraId.value = defaultSource(cameras.value)?.id ?? "";
  if (!versionId.value) versionId.value = (cap.versions.find((v) => versionMode(v) === "MOTION") ?? cap.versions[0])?.id ?? "";
  await loadHistory();
  // Only an explicit or active session opens by itself; the newest stored one would show an old source's history as current.
  const id = (typeof route.query.session === "string" && route.query.session) || cap.activeSessionId;
  if (id) await showSession(id, session.value?.id === id);
}
async function showSession(id: string, keepWindow = false) {
  const detail = await get<VideoSession>(`${BASE}/sessions/${id}`);
  if (capability.value?.workflow) await loadDag(detail.workflowVersionId, capability.value.workflow.id);
  const changed = session.value?.id !== id;
  session.value = detail;
  // The selector follows the shown session, so it never names a different source than the clip and records below.
  if (cameras.value.some((c) => c.id === detail.camera.id)) cameraId.value = detail.camera.id;
  if (changed && route.query.session !== id) await router.replace({ query: { ...route.query, session: id } });
  const latest = detail.windows[0]?.seq ?? null;
  if (changed || !keepWindow || windowSeq.value == null) windowSeq.value = latest;
  if (changed) {
    picked = false;
    nodeId.value = motion.value ? "observer" : "task:infer";
    setView(canLive.value ? "live" : detail.windows.length ? "history" : "preview");
  }
  await loadWindow();
}
// However the session ended (stop, poll, failure), the live view leaves with it.
/** Clears a finished session from view (and the URL) so a newly chosen source shows its own preview. */
async function leaveSession() {
  if (!session.value || running.value) return;
  session.value = null;
  windowSeq.value = null;
  void loadWindow();
  setView("preview");
  if (route.query.session) {
    const query = { ...route.query };
    delete query.session;
    await router.replace({ query });
  }
}
watch(cameraId, (id) => {
  if (session.value && session.value.camera.id !== id) void leaveSession();
});
watch(canLive, (live) => {
  if (!live && view.value === "live") setView(session.value?.windows.length ? "history" : "preview");
});
function selectWindow(seq: number) {
  windowSeq.value = seq;
  picked = true;
  setView("history");
  void loadAll(() => loadWindow());
}
let windowGeneration = 0;
async function loadWindow() {
  const w = selectedWindow.value, generation = ++windowGeneration;
  // A different Run must not keep showing the previous window's frames and details under the new window's label.
  if (!w || w.runId !== run.value?.run.id) {
    run.value = null;
    placements.value = [];
    detections.value = null;
    observations.value = null;
  }
  if (!w) return;
  const [detail, where] = await Promise.all([
    get<Schema["RunDetail"]>(`workflow-runs/${w.runId}`),
    get<Schema["Placement"][]>(`workflow-runs/${w.runId}/placements`),
  ]);
  const infer = detail.tasks.find((t) => t.key === (motion.value ? frameTaskKey.value : "infer"));
  let read: DetectionsView | null = null,
    observed: ObservationsView | null = null;
  if (infer && motion.value) {
    const results = await get<Schema["TaskResults"]>(`tasks/${infer.id}/results`);
    const port = results.items[0]?.artifacts.find((a) => a.port === "observations")?.port;
    if (port) observed = readObservations(await (await request(`tasks/${infer.id}/results/${encodeURIComponent(port)}/content`)).json());
  } else if (infer) {
    const results = await get<Schema["TaskResults"]>(`tasks/${infer.id}/results`);
    const port = results.items[0]?.artifacts.find((a) => a.port === "detections")?.port;
    if (port) {
      read = readDetections(await (await request(`tasks/${infer.id}/results/${encodeURIComponent(port)}/content`)).json());
    }
  }
  if (generation !== windowGeneration) return;
  run.value = detail;
  placements.value = where;
  detections.value = read;
  observations.value = observed;
}
function start() {
  if (!canStart.value || !camera.value) return;
  void act(async () => {
    // A finished session stays in the records; the stage shows only the new one from here on.
    await leaveSession();
    const created = await write<VideoSession>(
      `${BASE}/sessions`,
      "POST",
      { workflowVersionId: versionId.value, camera: { id: camera.value!.id, uid: camera.value!.uid } },
      startKey,
    );
    startKey = requestKey();
    capability.value = await get<VideoCapability>(BASE);
    historyOffset.value = 0;
    await loadHistory();
    await showSession(created.id);
  });
}
function stop() {
  if (!session.value) return;
  stopping.value = true;
  void act(async () => {
    try {
      session.value = await write<VideoSession>(`${BASE}/sessions/${session.value!.id}/stop`, "POST", {});
      capability.value = await get<VideoCapability>(BASE);
      await loadHistory();
      await loadWindow();
    } finally {
      stopping.value = false;
    }
  });
}
function page(offset: number) {
  historyOffset.value = offset;
  void loadAll(() => loadHistory());
}
async function refreshPreview() {
  const c = camera.value;
  if (previewing || view.value !== "preview" || !c || !cameraState(c).online || document.hidden) return;
  previewing = true;
  try {
    const response = await request(`managed-devices/${encodeURIComponent(c.id)}/preview?uid=${encodeURIComponent(c.uid)}`);
    if (!response.headers.get("content-type")?.startsWith("image/")) throw new Error("카메라 영상 형식을 확인하세요.");
    const url = URL.createObjectURL(await response.blob());
    if (!alive || c.id !== cameraId.value) return URL.revokeObjectURL(url);
    if (preview.value) URL.revokeObjectURL(preview.value);
    preview.value = url;
    previewAt.value = new Date().toISOString();
    previewError.value = "";
  } catch (cause) {
    previewError.value = `영상 수신 실패 · ${cause instanceof Error ? cause.message : "조회 실패"}`;
  } finally {
    previewing = false;
  }
}
watch(cameraId, () => {
  if (preview.value) URL.revokeObjectURL(preview.value);
  preview.value = previewAt.value = previewError.value = "";
  void refreshPreview();
});
onMounted(() => loadAll(() => refresh()));
onBeforeUnmount(() => {
  alive = false;
  if (preview.value) URL.revokeObjectURL(preview.value);
});
usePoll(refreshPreview, 2000);
// Non-overlapping and idle while the tab is hidden; a running session is followed every 2 s, otherwise every 10 s.
usePoll(async () => {
  if (polling || loading.value || acting.value || document.hidden) return;
  polling = true;
  try {
    if (running.value && session.value) {
      // A window the user picked stays put; otherwise the newest window is followed.
      const wasLatest = windowSeq.value === session.value.windows[0]?.seq;
      await showSession(session.value.id, picked || !wasLatest);
      if (!running.value) {
        capability.value = await get<VideoCapability>(BASE);
        await loadHistory();
      }
    } else if (++ticks % 5 === 0) await refresh();
    pollError.value = "";
  } catch (cause) {
    pollError.value = "자동 갱신 실패 · 마지막으로 조회한 상태를 표시합니다. " + (cause instanceof Error ? cause.message : "조회 실패");
  } finally {
    polling = false;
  }
}, 2000);
</script>
<style scoped>
.hv {
  display: grid;
  gap: var(--ks-spacing-4);
  margin-top: var(--ks-spacing-4);
}
.hv-head h2 {
  margin-right: auto;
}
.hv-views {
  display: inline-flex;
  border: 1px solid var(--ks-border-default);
  border-radius: var(--ks-radius-base);
  overflow: hidden;
}
.hv-views button {
  min-height: 32px;
  padding: 0 var(--ks-spacing-3);
  color: var(--ks-text-secondary);
  background: var(--ks-bg-base);
  border: 0;
  cursor: pointer;
}
.hv-views button + button {
  border-left: 1px solid var(--ks-border-default);
}
.hv-views button[aria-pressed="true"] {
  color: var(--ks-text-primary);
  background: var(--ks-bg-hover);
  font-weight: 600;
}
.hv-views button:focus-visible {
  outline: 2px solid var(--ks-border-focus);
  outline-offset: -2px;
}
/* The clip is the focal point; controls and the risk forecast sit beside it on wide screens. */
.hv-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(16rem, 22rem);
  gap: var(--ks-spacing-4);
  align-items: start;
}
.hv-stage {
  display: grid;
  gap: var(--ks-spacing-2);
  min-width: 0;
}
.hv-stage-title {
  margin: 0;
  font-size: var(--ks-font-size-md);
}
.hv-stage .native-note {
  margin: 0;
}
.hv-side {
  display: grid;
  gap: var(--ks-spacing-3);
  min-width: 0;
}
.hv-side :deep(.kel-form-item) {
  margin-bottom: 0;
}
.hv-camera {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ks-spacing-2);
}
.hv-more {
  flex-basis: 100%;
}
.hv-more summary {
  cursor: pointer;
  color: var(--ks-text-secondary);
  font-size: var(--ks-font-size-xs);
}
.hv-session {
  display: grid;
  gap: var(--ks-spacing-2);
  margin-top: var(--ks-spacing-4);
  padding-top: var(--ks-spacing-3);
  border-top: 1px solid var(--ks-border-default);
}
.hv-facts {
  display: grid;
  gap: var(--ks-spacing-2);
  margin: 0 0 var(--ks-spacing-3);
}
.hv-facts div {
  display: grid;
  grid-template-columns: 7rem minmax(0, 1fr);
  gap: var(--ks-spacing-2);
}
.hv-facts dt {
  color: var(--ks-text-secondary);
}
.hv-facts dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.hv-mono {
  font-family: monospace;
  font-size: var(--ks-font-size-xs);
}
.hv-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ks-spacing-2);
  margin: 0;
}
.hv-windows {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ks-spacing-2);
}
.hv-window {
  display: grid;
  gap: 2px;
  min-width: 6.5rem;
  min-height: 44px;
  padding: var(--ks-spacing-1) var(--ks-spacing-3);
  text-align: left;
  color: var(--ks-text-primary);
  background: var(--ks-bg-base);
  border: 1px solid var(--ks-border-default);
  border-left: 3px solid var(--ks-border-strong);
  border-radius: var(--ks-radius-base);
  cursor: pointer;
}
.hv-window.success {
  border-left-color: var(--ks-border-success, var(--ks-chart-success));
}
.hv-window.danger {
  border-left-color: var(--ks-border-error, var(--ks-chart-failed));
}
.hv-window.info {
  border-left-color: var(--ks-chart-duration);
}
.hv-window.selected {
  border-color: var(--ks-border-focus);
  outline: 1px solid var(--ks-border-focus);
}
.hv-window small {
  color: var(--ks-text-secondary);
}
.hv-window:focus-visible {
  outline: 2px solid var(--ks-border-focus);
  outline-offset: 2px;
}
/* The selected stage's detail sits beside the graph only when both fit without scrolling. */
.hv-flow {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: var(--ks-spacing-4);
  align-items: start;
}
.hv-node {
  border: 1px solid var(--ks-border-default);
  border-radius: var(--ks-radius-base);
  padding: var(--ks-spacing-4);
}
.hv-node small {
  color: var(--ks-text-secondary);
}
.hv-node h3 {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ks-spacing-2);
  margin: var(--ks-spacing-1) 0 var(--ks-spacing-3);
}
.hv-node dl {
  display: grid;
  gap: var(--ks-spacing-2);
  margin: 0;
}
.hv-node dt {
  color: var(--ks-text-secondary);
  font-size: var(--ks-font-size-xs);
}
.hv-node dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.hv-history {
  max-width: 100%;
  overflow-x: auto;
}
@media (min-width: 1500px) {
  .hv-flow {
    grid-template-columns: minmax(0, 1fr) 18rem;
  }
}
@media (max-width: 900px) {
  .hv-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
