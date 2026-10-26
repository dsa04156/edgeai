<template>
  <MotionStage
    :src="src || null"
    :alt="shown ? `실시간 프레임 ${shown.seq}` : '실시간 프레임'"
    :observation="shown"
    :placeholder="placeholder"
    :failed="!!error && !src"
    :badge="status"
  >
    <span aria-live="polite">
      {{ status.text }}<template v-if="shown"> · 프레임 {{ shown.seq }} · {{ time(shown.frameTime ?? shown.receivedAt) }}</template>
      <template v-if="view"> · 처리 {{ view.stats.processed }} · 추적 재시작 {{ view.stats.resets }}</template>
      <template v-if="note"> · {{ note }}</template>
    </span>
  </MotionStage>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { time } from "./native-api";
import { liveStatus, type LiveView, type Observation } from "./hawkeye-video";
import MotionStage from "./MotionStage.vue";

const props = defineProps<{ sessionId: string }>();
const emit = defineEmits<{ frame: [observation: Observation | null]; live: [view: LiveView]; ended: [] }>();
const INTERVAL = 1000;
const src = ref(""),
  shown = ref<Observation | null>(null),
  view = ref<LiveView | null>(null),
  error = ref(""),
  note = ref(""),
  lastFrameAt = ref<number | null>(null),
  closed = ref(false),
  now = ref(Date.now());
const controller = new AbortController();
let timer: ReturnType<typeof setTimeout> | undefined;

const status = computed(() =>
  closed.value ? { text: "관측 종료", tone: "warning" as const, live: false } : liveStatus({ state: view.value?.state ?? "STARTING", lastFrameAt: lastFrameAt.value, error: error.value, now: now.value, failure: view.value?.failure, lastError: view.value?.lastError }),
);
const placeholder = computed(() =>
  error.value ? `실시간 관측을 받을 수 없습니다 · ${error.value}` : view.value?.state === "RUNNING" ? "첫 관측 프레임을 기다리는 중…" : "관측을 시작하는 중…",
);
const url = (path: string) => `/api/control-plane/scenarios/hawkeye-safety/video/sessions/${encodeURIComponent(props.sessionId)}/live${path}`;
async function read(path: string) {
  return fetch(url(path), { cache: "no-store", signal: AbortSignal.any([controller.signal, AbortSignal.timeout(8000)]) });
}
async function failure(response: Response) {
  const data = await response.json().catch(() => ({}));
  return data.message || data.detail || `요청 실패 (${response.status})`;
}
/** One status read and at most one frame read per tick; the next tick waits for both. */
async function tick() {
  if (document.hidden) return true;
  now.value = Date.now();
  const response = await read("");
  if (response.status === 409) {
    const data = await response.json().catch(() => ({}));
    // Only an ended session stops polling; OBSERVER_LOST is a failure the session state will report next.
    if (data.code !== "SESSION_NOT_LIVE") throw new Error(data.code === "OBSERVER_LOST" ? "관측 인스턴스 재시작" : data.message || "관측 상태 충돌");
    closed.value = true;
    return false;
  }
  if (response.status === 503) throw new Error("관측기 연결 불가");
  if (!response.ok) throw new Error(await failure(response));
  const live: LiveView = await response.json();
  view.value = live;
  emit("live", live);
  const latest = live.latest;
  if (latest && latest.seq !== shown.value?.seq) {
    const image = await read(`/frames/${latest.seq}`);
    if (image.status === 404) note.value = `프레임 ${latest.seq}은 보관 범위를 벗어나 건너뜀`;
    else if (!image.ok) throw new Error(await failure(image));
    else {
      const next = URL.createObjectURL(await image.blob());
      if (controller.signal.aborted) {
        URL.revokeObjectURL(next);
        return false;
      }
      // The image and its observation change together, so the overlay always belongs to the displayed frame.
      if (src.value) URL.revokeObjectURL(src.value);
      src.value = next;
      shown.value = latest;
      lastFrameAt.value = Date.now();
      note.value = "";
      emit("frame", latest);
    }
  }
  return live.state === "RUNNING" || live.state === "STARTING";
}
async function loop() {
  let again = true;
  try {
    again = await tick();
    error.value = "";
  } catch (cause) {
    if (controller.signal.aborted) return;
    error.value = cause instanceof Error ? (cause.name === "TimeoutError" ? "응답 시간 초과" : cause.message) : "조회 실패";
  }
  now.value = Date.now();
  if (controller.signal.aborted) return;
  if (again) timer = setTimeout(loop, INTERVAL);
  else emit("ended");
}
onMounted(() => void loop());
onBeforeUnmount(() => {
  controller.abort();
  clearTimeout(timer);
  if (src.value) URL.revokeObjectURL(src.value);
});
</script>
