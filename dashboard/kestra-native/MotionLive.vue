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
      <template v-if="rates"> · {{ rates }}</template>
      <template v-if="note"> · {{ note }}</template>
    </span>
  </MotionStage>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { time } from "./native-api";
import { frameRate, liveStatus, type LiveView, type Observation } from "./hawkeye-video";
import MotionStage from "./MotionStage.vue";

const props = defineProps<{ sessionId: string }>();
const emit = defineEmits<{ frame: [observation: Observation | null]; live: [view: LiveView]; ended: [] }>();
/** The observer analyses the Mapper's 24 fps preview; a failed read is retried after a second. */
const INTERVAL = 1000 / 24,
  RETRY = 1000;
const src = ref(""),
  shown = ref<Observation | null>(null),
  view = ref<LiveView | null>(null),
  error = ref(""),
  note = ref(""),
  lastFrameAt = ref<number | null>(null),
  closed = ref(false),
  now = ref(Date.now()),
  shownAt = ref<number[]>([]),
  processedAt = ref<[number, number][]>([]);
const controller = new AbortController();
let timer: ReturnType<typeof setTimeout> | undefined;

const status = computed(() =>
  closed.value ? { text: "관측 종료", tone: "warning" as const, live: false } : liveStatus({ state: view.value?.state ?? "STARTING", lastFrameAt: lastFrameAt.value, error: error.value, now: now.value, failure: view.value?.failure, lastError: view.value?.lastError }),
);
/** Measured, not configured: frames this page displayed and frames the observer analysed, over the last 2 s. */
const rates = computed(() => {
  const shown = frameRate(shownAt.value, now.value);
  const samples = processedAt.value, [first, last] = [samples[0], samples[samples.length - 1]];
  const analysed = samples.length > 1 && last[0] > first[0] ? ((last[1] - first[1]) * 1000) / (last[0] - first[0]) : null;
  return [shown == null ? "" : `표시 ${shown.toFixed(1)} fps`, analysed == null ? "" : `관측 ${analysed.toFixed(1)} fps`].filter(Boolean).join(" · ");
});
const recent = <T,>(list: T[], at: (item: T) => number, time: number) => list.filter((item) => time - at(item) <= 2000);
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
  processedAt.value = [...recent(processedAt.value, (s) => s[0], now.value), [now.value, live.stats.processed]];
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
      shownAt.value = [...recent(shownAt.value, (t) => t, lastFrameAt.value), lastFrameAt.value];
      note.value = "";
      emit("frame", latest);
    }
  }
  return live.state === "RUNNING" || live.state === "STARTING";
}
/** Ticks start INTERVAL apart when the reads are faster than that, and right after the previous one when slower. */
async function loop() {
  const started = performance.now();
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
  if (again) timer = setTimeout(loop, Math.max(0, (error.value ? RETRY : INTERVAL) - (performance.now() - started)));
  else emit("ended");
}
onMounted(() => void loop());
onBeforeUnmount(() => {
  controller.abort();
  clearTimeout(timer);
  if (src.value) URL.revokeObjectURL(src.value);
});
</script>
