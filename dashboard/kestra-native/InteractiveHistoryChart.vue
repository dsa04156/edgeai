<template>
  <figure class="native-history">
    <div class="native-toolbar" aria-label="그래프 구간 탐색">
      <KsButton @click="zoom(0.5)" :disabled="!data.points.length && !explore"
        >확대</KsButton
      >
      <KsButton @click="zoom(2)" :disabled="!range && !explore">축소</KsButton>
      <KsButton @click="shift(-0.5)" :disabled="!range && !explore"
        >이전 구간</KsButton
      >
      <KsButton @click="shift(0.5)" :disabled="!range && !explore"
        >다음 구간</KsButton
      >
      <KsButton v-if="!explore" @click="reset" :disabled="!range"
        >전체 구간</KsButton
      >
      <slot name="actions" />
    </div>
    <p class="native-note" aria-label="그래프 표시 구간">
      {{ formatTime(visible.from) }} → {{ formatTime(visible.to) }}
    </p>
    <div
      ref="element"
      class="native-history-canvas"
      role="img"
      :aria-label="data.name"
    />
    <figcaption class="native-note">
      <template v-if="explore"
        >드래그로 이동 · 휠로 확대/축소 · 그래프를 가리키면 시각과 값을
        표시합니다.</template
      >
      <template v-else>
        슬라이더·휠로 구간을 조절할 수 있습니다.
        {{
          range ? "선택한 시각 구간을 유지합니다." : "전체 구간을 표시합니다."
        }}
      </template>
    </figcaption>
    <p v-if="empty" class="native-note">
      {{
        explore
          ? "표시 구간에 기록이 없습니다."
          : "선택한 기간에 표시할 기록이 없습니다."
      }}
    </p>
  </figure>
</template>
<script setup lang="ts">
import {
  computed,
  onMounted,
  onBeforeUnmount,
  ref,
  shallowRef,
  watch,
} from "vue";
import { KsButton } from "@kestra-io/design-system";
import {
  init,
  configureChart,
  updateChart,
  type ECharts,
  type HistoryChartData,
} from "../lib/history-echarts";
import {
  clampViewport,
  maxSpan,
  MIN_SPAN,
  sameRange,
  type TimeRange,
} from "../lib/history-viewport";
const formatTime = (value: number) => new Date(value).toLocaleString("ko-KR");
// With `viewport`, the parent owns the visible window (data.from/to is then the whole navigable domain). A pan/zoom moves
// the chart at once and is reported as `explore-start` when it begins and one `update:viewport` with the final window
// once it has been quiet for GESTURE_SETTLE_MS; without `viewport` the chart keeps its own range as before.
const props = defineProps<{
  data: HistoryChartData;
  scope: string;
  viewport?: { from: number; to: number };
  loading?: boolean;
}>();
const emit = defineEmits<{
  "update:viewport": [range: { from: number; to: number }];
  "explore-start": [];
}>();
const GESTURE_SETTLE_MS = 300;
const element = ref<HTMLElement>(),
  range = ref<{ from: number; to: number } | null>(null);
const explore = computed(() => !!props.viewport);
const bounds = computed(() => ({ from: props.data.from, to: props.data.to }));
// The window of a gesture not yet reported to the parent; it takes precedence over the parent's (older) viewport.
const pending = shallowRef<TimeRange | null>(null);
let settleTimer: ReturnType<typeof setTimeout> | undefined;
const visible = computed(
  () => pending.value || props.viewport || range.value || bounds.value,
);
const empty = computed(() =>
  explore.value
    ? !props.loading &&
      !props.data.points.some(
        (p) => p.at >= visible.value.from && p.at <= visible.value.to,
      )
    : !props.data.points.length,
);
let chart: ECharts | undefined, observer: ResizeObserver | undefined;
function preview(view: TimeRange) {
  if (!pending.value) emit("explore-start");
  pending.value = shown = view;
  clearTimeout(settleTimer);
  settleTimer = setTimeout(() => {
    const view = pending.value;
    settleTimer = undefined;
    pending.value = null;
    if (view) emit("update:viewport", view);
  }, GESTURE_SETTLE_MS);
}
function dropPending() {
  clearTimeout(settleTimer);
  settleTimer = undefined;
  pending.value = null;
}
function applyRange(from: number, to: number) {
  if (explore.value) {
    const view = clampViewport({ from, to }, bounds.value);
    chart?.dispatchAction(
      { type: "dataZoom", startValue: view.from, endValue: view.to },
      { silent: true },
    );
    preview(view);
    return;
  }
  const width = Math.min(to - from, props.data.to - props.data.from);
  const start = Math.max(
    props.data.from,
    Math.min(from, props.data.to - width),
  );
  const end = start + width;
  range.value =
    start <= props.data.from && end >= props.data.to
      ? null
      : { from: start, to: end };
  chart?.dispatchAction(
    range.value
      ? { type: "dataZoom", startValue: start, endValue: end }
      : { type: "dataZoom", start: 0, end: 100 },
    { silent: true },
  );
}
function zoom(factor: number) {
  const { from, to } = visible.value,
    center = (from + to) / 2;
  const half = Math.max(1, (to - from) * factor) / 2;
  applyRange(center - half, center + half);
}
function shift(fraction: number) {
  const { from, to } = visible.value,
    amount = (to - from) * fraction;
  applyRange(from + amount, to + amount);
}
function reset() {
  range.value = null;
  chart?.dispatchAction(
    { type: "dataZoom", start: 0, end: 100 },
    { silent: true },
  );
}
function render() {
  if (!chart) return;
  if (props.viewport) {
    shown = pending.value || props.viewport;
    updateChart(chart, props.data, {
      ...shown,
      minSpan: MIN_SPAN,
      maxSpan: maxSpan(bounds.value),
    });
    return;
  }
  updateChart(chart, props.data);
  if (range.value) applyRange(range.value.from, range.value.to);
  else reset();
}
watch(() => props.data, render);
// A pan/zoom echoed back from the parent is already on screen; only parent-driven moves are dispatched, without re-sending
// series data. Such a move (live follow, quick range) replaces a gesture still waiting to be reported.
let shown: { from: number; to: number } | undefined;
watch(
  () => props.viewport,
  (view, previous) => {
    if (!chart || !view || sameRange(view, previous) || sameRange(view, shown))
      return;
    dropPending();
    shown = view;
    chart.dispatchAction(
      { type: "dataZoom", startValue: view.from, endValue: view.to },
      { silent: true },
    );
  },
);
watch(
  () => props.scope,
  () => {
    dropPending();
    range.value = null;
    render();
  },
);
onMounted(() => {
  if (!element.value) return;
  chart = init(element.value);
  configureChart(chart, element.value, explore.value);
  chart.on("datazoom", () => {
    const state = (
      chart?.getOption().dataZoom as
        | {
            start: number;
            end: number;
            startValue?: number;
            endValue?: number;
          }[]
        | undefined
    )?.[0];
    if (!state) return;
    const width = props.data.to - props.data.from;
    const from = Number(
        state.startValue ?? props.data.from + (width * state.start) / 100,
      ),
      to = Number(
        state.endValue ?? props.data.from + (width * state.end) / 100,
      );
    if (explore.value) {
      preview({ from, to });
      return;
    }
    if (state.start <= 0 && state.end >= 100) {
      range.value = null;
      return;
    }
    range.value = { from, to };
  });
  observer = new ResizeObserver(() => chart?.resize());
  observer.observe(element.value);
  render();
});
// The window on screen, including a gesture not yet reported, e.g. for a live return that keeps the displayed width.
defineExpose({ displayed: () => visible.value });
onBeforeUnmount(() => {
  dropPending();
  observer?.disconnect();
  chart?.dispose();
});
</script>
<style scoped>
.native-history {
  margin: 0;
  min-width: 0;
  flex: 0 0 auto;
  --ink: var(--ks-text-primary);
  --line: var(--ks-border-default);
  --signal: var(--ks-chart-duration);
  --panel: var(--ks-bg-base);
  --signal-surface: var(--ks-bg-hover);
  --warning-surface: var(--ks-bg-hover);
}
.native-history-canvas {
  width: 100%;
  height: 300px;
  min-width: 0;
}
</style>
