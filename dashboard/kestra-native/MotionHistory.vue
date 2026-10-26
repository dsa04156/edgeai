<template>
  <div class="mh">
    <MotionStage
      :src="image?.url ?? null"
      :alt="item ? `${label} 프레임 ${item.seq}` : `${label} 프레임`"
      :observation="item?.observation ?? null"
      :placeholder="!item ? empty : (image?.error ?? (taskId ? '프레임을 불러오는 중…' : '프레임 영상이 없는 결과입니다.'))"
      :failed="!!image?.error"
      :badge="item ? { text: '기록', tone: 'info' } : null"
    >
      <template v-if="item">
        {{ label }} · 프레임 {{ index + 1 }}/{{ items.length }} (seq {{ item.seq }}) · {{ time(item.frameTime) }}
      </template>
    </MotionStage>
    <div v-if="items.length" class="mh-controls" role="group" :aria-label="`${label} 프레임 이동`">
      <KsButton aria-label="이전 프레임" :disabled="index === 0" @click="seek(index - 1)">‹</KsButton>
      <KsButton :aria-label="playing ? '일시정지' : '재생'" :aria-pressed="playing" @click="toggle">{{ playing ? "일시정지" : "재생" }}</KsButton>
      <KsButton aria-label="다음 프레임" :disabled="index >= items.length - 1" @click="seek(index + 1)">›</KsButton>
      <div class="mh-track">
        <input
          type="range"
          min="0"
          :max="items.length - 1"
          :value="index"
          aria-label="프레임 위치"
          :aria-valuetext="`프레임 ${index + 1}/${items.length}`"
          @input="seek(Number(($event.target as HTMLInputElement).value), true)"
        />
        <div class="mh-ticks" aria-hidden="true">
          <span v-for="it in items" :key="it.seq" :class="it.tone" />
        </div>
      </div>
    </div>
    <p v-if="item?.caption" class="mh-caption" :class="{ unread: item.unread }">
      {{ item.caption }}<span v-if="item.note" class="native-note"> · {{ item.note }}</span>
    </p>
  </div>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { KsButton } from "@kestra-io/design-system";
import { request, time } from "./native-api";
import type { HistoryFrame } from "./hawkeye-video";
import MotionStage from "./MotionStage.vue";

const props = defineProps<{ taskId: string | null; items: HistoryFrame[]; label: string; empty: string }>();
const emit = defineEmits<{ frame: [item: HistoryFrame | null] }>();
const index = ref(0),
  playing = ref(false),
  images = ref<Record<number, { url?: string; error?: string }>>({});
const item = computed(() => props.items[index.value] ?? null);
const image = computed(() => (item.value ? images.value[item.value.seq] : undefined));
watch(item, (value) => emit("frame", value), { immediate: true });

// Polling rebuilds the item list; only a different frame source or frame set resets the position and images.
const key = computed(() => (props.items.length ? `${props.taskId}:${props.items.map((f) => f.seq).join(",")}` : ""));
let load: AbortController | null = null,
  queue: number[] = [],
  timer: ReturnType<typeof setTimeout> | undefined;
function release() {
  load?.abort();
  load = null;
  queue = [];
  for (const f of Object.values(images.value)) if (f.url) URL.revokeObjectURL(f.url);
  images.value = {};
}
// The frame reader admits only a few concurrent reads and answers 429 RESULT_PREVIEW_BUSY beyond that, so at most
// two frames are fetched at a time; a frame the user seeks to moves to the front of the queue.
watch(
  key,
  (value) => {
    release();
    pause();
    index.value = 0;
    if (!value || !props.taskId) return;
    const controller = (load = new AbortController()),
      taskId = props.taskId;
    queue = props.items.map((f) => f.seq);
    const next = async (): Promise<void> => {
      const seq = queue.shift();
      if (seq == null || controller.signal.aborted) return;
      try {
        const response = await request(`scenarios/hawkeye-safety/video/tasks/${taskId}/frames/${seq}`, {
          signal: AbortSignal.any([controller.signal, AbortSignal.timeout(25000)]),
        });
        const blob = await response.blob();
        if (controller.signal.aborted) return;
        images.value[seq] = { url: URL.createObjectURL(blob) };
      } catch (cause) {
        if (controller.signal.aborted) return;
        images.value[seq] = { error: `프레임 불러오기 실패 · ${cause instanceof Error ? cause.message : "조회 실패"}` };
      }
      return next();
    };
    void Promise.all([next(), next()]);
  },
  { immediate: true },
);
function seek(i: number, user = false) {
  index.value = Math.min(Math.max(i, 0), props.items.length - 1);
  if (user) pause();
  const seq = props.items[index.value]?.seq;
  if (seq != null && queue.includes(seq)) queue = [seq, ...queue.filter((s) => s !== seq)];
}
function pause() {
  playing.value = false;
  clearTimeout(timer);
}
/** Plays at the recorded frame spacing (bounded 0.1–1 s) and waits for a frame that is still loading. */
function play() {
  const now = props.items[index.value], next = props.items[index.value + 1];
  if (!playing.value || !next) return pause();
  if (!images.value[next.seq] && props.taskId) {
    timer = setTimeout(play, 150);
    return;
  }
  const spacing = now.frameTime && next.frameTime ? Date.parse(next.frameTime) - Date.parse(now.frameTime) : 500;
  timer = setTimeout(() => {
    if (!playing.value) return;
    seek(index.value + 1);
    play();
  }, Math.min(Math.max(spacing || 500, 100), 1000));
}
function toggle() {
  if (playing.value) return pause();
  if (index.value >= props.items.length - 1) index.value = 0;
  playing.value = true;
  play();
}
onBeforeUnmount(() => {
  pause();
  release();
});
</script>
<style scoped>
.mh {
  display: grid;
  gap: var(--ks-spacing-2);
}
.mh-controls {
  display: flex;
  align-items: center;
  gap: var(--ks-spacing-2);
}
.mh-controls :deep(.kel-button) {
  margin: 0;
  min-width: 44px;
}
.mh-track {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 2px;
}
.mh-track input {
  width: 100%;
  accent-color: var(--ks-border-focus);
}
.mh-ticks {
  display: flex;
  gap: 1px;
  height: 4px;
}
.mh-ticks span {
  flex: 1;
  background: var(--ks-border-default);
  border-radius: 1px;
}
.mh-ticks .danger {
  background: var(--ks-chart-failed);
}
.mh-ticks .warning {
  background: var(--ks-log-warn);
}
.mh-ticks .success {
  background: var(--ks-chart-success);
}
.mh-caption {
  margin: 0;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.mh-caption.unread {
  color: var(--ks-text-error);
}
</style>
