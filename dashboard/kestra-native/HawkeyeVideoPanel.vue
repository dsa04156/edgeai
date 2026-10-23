<template>
  <div class="hv">
    <NativeLoading :busy="loading && !capability" label="영상 점검을 불러오는 중…" />
    <NativeFeedback :error="loadError || pollError" />
    <section v-if="capability" class="native-panel hv-control" aria-labelledby="hv-control-title">
      <div class="native-toolbar">
        <h2 id="hv-control-title">영상 안전 점검</h2>
        <KsButton :disabled="loading" @click="loadAll(() => refresh())">새로고침</KsButton>
      </div>
      <p class="native-note">
        카메라 영상을 정해진 길이의 구간으로 나눠 발행된 워크플로(프레임 수집 → Hawkeye 판정)를 차례로 실행합니다.
        구간 사이에 들어온 영상은 판정하지 않습니다.
      </p>
      <div class="hv-grid">
        <div class="hv-preview">
          <img v-if="preview" :src="preview" :alt="`${camera?.id ?? '카메라'} 최신 영상`" />
          <p v-else class="hv-nopreview">{{ previewMessage }}</p>
          <p class="native-note" aria-live="polite">
            <template v-if="previewAt">{{ time(previewAt) }} 수신 · 2초마다 갱신</template>
            <template v-else-if="previewError">{{ previewError }}</template>
          </p>
        </div>
        <div class="hv-form">
          <KsFormItem label="카메라">
            <KsSelect v-model="cameraId" :disabled="running || starting" placeholder="카메라를 선택하세요" aria-label="카메라">
              <KsOption v-for="c in cameras" :key="c.id" :value="c.id" :label="`${c.id} · ${cameraState(c).label}`" />
            </KsSelect>
          </KsFormItem>
          <dl v-if="camera" class="hv-facts">
            <div>
              <dt>현재 연결 상태</dt>
              <dd><KsTag :type="cameraState(camera).online ? 'success' : 'danger'">{{ cameraState(camera).label }}</KsTag>
                <span v-if="cameraState(camera).reason" class="native-note">{{ cameraState(camera).reason }}</span></dd>
            </div>
            <div v-if="replayLabel(camera)"><dt>영상 종류</dt><dd>{{ replayLabel(camera) }}</dd></div>
            <div><dt>Mapper 노드</dt><dd>{{ camera.targetNode }}</dd></div>
            <div><dt>원본 주소</dt><dd class="hv-mono">{{ sourceAddress(camera) }}</dd></div>
          </dl>
          <p v-else-if="!cameras.length" class="native-notice">등록된 RTSP 카메라가 없습니다. 장치 관리에서 카메라를 등록하세요.</p>
          <KsFormItem label="워크플로 버전">
            <KsSelect v-model="versionId" :disabled="running || starting || !capability.versions.length" placeholder="발행된 버전 없음" aria-label="워크플로 버전">
              <KsOption v-for="v in capability.versions" :key="v.id" :value="v.id" :label="`${capability.workflow?.displayName ?? capability.workflowKey} v${v.version}`" />
            </KsSelect>
          </KsFormItem>
          <p v-if="capability.workflow" class="native-note">
            <RouterLink :to="{ path: '/ui/main/workflows', query: { workflow: capability.workflow.id } }">워크플로 정의 보기</RouterLink>
            · 한 번에 최대 {{ capability.maxWindows }}개 구간
          </p>
          <div class="native-toolbar">
            <KsButton v-if="!running" type="primary" :disabled="!canStart" @click="start">점검 시작</KsButton>
            <KsButton v-else type="danger" :disabled="stopping" @click="stop">점검 중지</KsButton>
          </div>
          <p class="native-note" role="status">{{ startHelp }}</p>
          <NativeFeedback :error="actionError" />
        </div>
      </div>
    </section>

    <section v-if="session" class="native-panel hv-session" aria-labelledby="hv-session-title">
      <div class="native-toolbar">
        <h2 id="hv-session-title">점검 세션</h2>
        <KsTag :type="tone(session.state)">{{ sessionLabel(session.state) }}</KsTag>
      </div>
      <p class="hv-summary" aria-live="polite">{{ summary }}</p>
      <p v-if="session.failureCode" role="alert" class="native-notice">
        {{ session.failureCode }}{{ session.failureMessage ? ` · ${session.failureMessage}` : "" }}
      </p>
      <div class="hv-windows" role="group" aria-label="처리 구간">
        <button
          v-for="(w, i) in windowsAsc"
          :key="w.seq"
          type="button"
          :class="['hv-window', tone(w.runState), { selected: w.seq === windowSeq }]"
          :aria-pressed="w.seq === windowSeq"
          @click="selectWindow(w.seq)"
        >
          <strong>구간 {{ w.seq }}</strong>
          <span>{{ statusLabel(w.runState) }}</span>
          <small v-if="gaps[i] != null">이전 구간과 {{ (gaps[i]! / 1000).toFixed(1) }}초 간격</small>
        </button>
      </div>
    </section>

    <section v-if="session && graph" class="native-panel" aria-labelledby="hv-graph-title">
      <div class="native-toolbar">
        <h2 id="hv-graph-title">구간 {{ windowSeq }} 실행 흐름</h2>
        <RouterLink v-if="selectedWindow" :to="{ path: '/ui/main/runs', query: { run: selectedWindow.runId } }">실행 상세 열기</RouterLink>
      </div>
      <p class="native-note">점선 상자는 워크플로 작업이 아닌 외부 구성 요소입니다. 작업 위치는 실제 배치 기록만 표시합니다.</p>
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

    <section v-if="session && selectedWindow" class="native-panel" aria-labelledby="hv-frames-title">
      <h2 id="hv-frames-title">구간 {{ windowSeq }} 판정 결과</h2>
      <p v-if="framesNote" class="native-note">{{ framesNote }}</p>
      <ol v-if="frames.length" class="hv-frames">
        <li v-for="f in frames" :key="f.seq" class="hv-frame">
          <img
            v-if="captureTaskId"
            :src="`/api/control-plane/${BASE}/tasks/${captureTaskId}/frames/${f.seq}`"
            :alt="`구간 ${windowSeq} 프레임 ${f.seq}`"
            loading="lazy"
            width="320"
            height="180"
          />
          <div>
            <p class="hv-category" :class="{ unread: f.category == null }">{{ categoryText(f) }}</p>
            <p v-if="f.explanation" class="hv-explanation">{{ f.explanation }}</p>
            <dl class="hv-facts">
              <div><dt>프레임</dt><dd>{{ f.seq }} · {{ time(f.frameTime) }}</dd></div>
              <div><dt>판정 시간</dt><dd>{{ f.inferenceMs == null ? "—" : `${f.inferenceMs.toFixed(0)} ms` }}{{ f.roundTripMs == null ? "" : ` · 왕복 ${f.roundTripMs.toFixed(0)} ms` }}</dd></div>
              <div><dt>판정 위치</dt><dd>{{ detections?.accelerator?.nodeName ?? "—" }} · {{ f.processor ?? "—" }}</dd></div>
            </dl>
          </div>
        </li>
      </ol>
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
import { KsButton, KsFormItem, KsOption, KsSelect, KsTag } from "@kestra-io/design-system";
import { get, request, requestKey, time, useAction, usePoll, write, type Schema } from "./native-api";
import { categoryText } from "./hawkeye";
import {
  cameraState,
  pinnedSource,
  pipelineGraph,
  readDetections,
  sessionLabel,
  replayLabel,
  sourceAddress,
  statusLabel,
  tone,
  windowGaps,
  type Camera,
  type CameraBinding,
  type DetectionsView,
  type VideoCapability,
  type VideoSession,
} from "./hawkeye-video";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
import NativeRecords from "./NativeRecords.vue";
import PipelineGraph from "./PipelineGraph.vue";

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
  ticks = 0;

const starting = computed(() => acting.value && !stopping.value);
const camera = computed(() => cameras.value.find((c) => c.id === cameraId.value));
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
  if (!c?.enabled) return "영상 점검이 이 환경에 설정되지 않았습니다.";
  if (!c.executes) return "워크플로 실행 환경이 꺼져 있어 시작할 수 없습니다.";
  if (c.activeSessionId) return "다른 점검이 진행 중입니다. 그 점검을 중지한 뒤 시작하세요.";
  if (!c.versions.length) return "발행된 워크플로 버전이 없습니다.";
  if (camera.value && !cameraState(camera.value).online) return `카메라를 사용할 수 없습니다 · ${cameraState(camera.value).reason ?? cameraState(camera.value).label}`;
  return "시작하면 첫 구간이 바로 실행되고, 구간이 끝날 때마다 다음 구간을 만듭니다.";
});
const windowsAsc = computed(() => [...(session.value?.windows ?? [])].sort((a, b) => a.seq - b.seq));
const gaps = computed(() =>
  windowGaps(
    windowsAsc.value.map((w) => ({
      createdAt: w.createdAt,
      // A finished Run is no longer updated, so its updatedAt is its end time.
      finishedAt: ["SUCCEEDED", "FAILED", "CANCELLED"].includes(w.runState) ? w.updatedAt : null,
    })),
  ),
);
const selectedWindow = computed(() => session.value?.windows.find((w) => w.seq === windowSeq.value) ?? null);
const summary = computed(() => {
  const s = session.value;
  if (!s) return "";
  const done = s.windows.filter((w) => w.runState === "SUCCEEDED").length;
  return `${s.camera.id} · ${time(s.createdAt)} 시작 · 구간 ${s.windowCount}/${s.maxWindows} 생성, ${done}개 완료${s.endedAt ? ` · ${time(s.endedAt)} 종료` : ""}${s.endReason ? ` (${sessionLabel(s.endReason)})` : ""}`;
});
const dag = computed(() => (session.value ? dags.value[session.value.workflowVersionId] : undefined));
const captureTaskId = computed(() => run.value?.tasks.find((t) => t.key === "capture")?.id);
const graph = computed(() => {
  if (!dag.value || !session.value) return null;
  const observed = detections.value?.accelerator;
  const reported = accelerator.value?.reported;
  return pipelineGraph({
    dag: dag.value,
    placements: placements.value,
    // The selected window's own pinned camera; the live camera list only supplies the CURRENT connection row.
    source: pinnedSource(
      run.value?.run.parameters.cameraSource as CameraBinding | undefined,
      cameras.value.find((c) => c.id === session.value!.camera.id),
    ),
    // Identity recorded with this window's frames wins over the runtime's current report.
    accelerator: observed
      ? { ...observed, status: "ANSWERED", basis: "이 구간의 판정 기록" }
      : reported
        ? { nodeName: reported.nodeName ?? null, podUid: null, processor: reported.processor ?? null, status: accelerator.value!.status, basis: "현재 런타임 보고" }
        : null,
    sourceTask: "capture",
    acceleratorTask: "infer",
    result: run.value
      ? { state: detections.value ? "VERIFIED" : run.value.run.state === "FAILED" ? "FAILED" : "PENDING", verified: frames.value.length }
      : null,
  });
});
const frames = computed(() => detections.value?.frames ?? []);
const node = computed(() => graph.value?.nodes.find((n) => n.id === nodeId.value));
const framesNote = computed(() => {
  if (!run.value) return "실행 정보를 불러오는 중입니다.";
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
  if (!cameraId.value) cameraId.value = (cameras.value.find((c) => cameraState(c).online) ?? cameras.value[0])?.id ?? "";
  if (!versionId.value) versionId.value = cap.versions[0]?.id ?? "";
  await loadHistory();
  const id = (typeof route.query.session === "string" && route.query.session) || cap.activeSessionId || history.value[0]?.id;
  if (id) await showSession(id, session.value?.id === id);
}
async function showSession(id: string, keepWindow = false) {
  const detail = await get<VideoSession>(`${BASE}/sessions/${id}`);
  if (capability.value?.workflow) await loadDag(detail.workflowVersionId, capability.value.workflow.id);
  const changed = session.value?.id !== id;
  session.value = detail;
  if (changed && route.query.session !== id) await router.replace({ query: { ...route.query, session: id } });
  const latest = detail.windows[0]?.seq ?? null;
  if (changed || !keepWindow || windowSeq.value == null) windowSeq.value = latest;
  await loadWindow();
}
function selectWindow(seq: number) {
  windowSeq.value = seq;
  void loadAll(() => loadWindow());
}
let windowGeneration = 0;
async function loadWindow() {
  const w = selectedWindow.value, generation = ++windowGeneration;
  if (!w) {
    run.value = null;
    placements.value = [];
    detections.value = null;
    return;
  }
  const [detail, where] = await Promise.all([
    get<Schema["RunDetail"]>(`workflow-runs/${w.runId}`),
    get<Schema["Placement"][]>(`workflow-runs/${w.runId}/placements`),
  ]);
  const infer = detail.tasks.find((t) => t.key === "infer");
  let read: DetectionsView | null = null;
  if (infer) {
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
}
function start() {
  if (!canStart.value || !camera.value) return;
  void act(async () => {
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
  if (previewing || !c || !cameraState(c).online || document.hidden) return;
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
      const wasLatest = windowSeq.value === session.value.windows[0]?.seq;
      await showSession(session.value.id, !wasLatest);
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
.hv-grid {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(16rem, 2fr);
  gap: var(--ks-spacing-5);
  align-items: start;
}
.hv-preview img,
.hv-nopreview {
  display: block;
  width: 100%;
  aspect-ratio: 16 / 9;
  object-fit: contain;
  background: var(--ks-bg-base);
  border-radius: var(--ks-radius-base);
}
.hv-nopreview {
  display: grid;
  place-items: center;
  margin: 0;
  color: var(--ks-text-secondary);
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
  font-size: var(--ks-font-size-md);
  margin: var(--ks-spacing-2) 0 var(--ks-spacing-3);
}
.hv-windows {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ks-spacing-2);
}
.hv-window {
  display: grid;
  gap: 2px;
  min-width: 7.5rem;
  min-height: 44px;
  padding: var(--ks-spacing-2) var(--ks-spacing-3);
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
.hv-frames {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--ks-spacing-4);
}
.hv-frame {
  display: grid;
  grid-template-columns: 16rem minmax(0, 1fr);
  gap: var(--ks-spacing-4);
  padding-bottom: var(--ks-spacing-4);
  border-bottom: 1px solid var(--ks-border-default);
}
.hv-frame img {
  width: 100%;
  height: auto;
  aspect-ratio: 16 / 9;
  object-fit: cover;
  border-radius: var(--ks-radius-base);
  background: var(--ks-bg-base);
}
.hv-category {
  font-size: var(--ks-font-size-md);
  font-weight: var(--ks-font-weight-bold);
  margin: 0 0 var(--ks-spacing-1);
}
.hv-category.unread {
  color: var(--ks-text-error);
}
.hv-explanation {
  margin: 0 0 var(--ks-spacing-2);
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
  .hv-grid,
  .hv-frame {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
