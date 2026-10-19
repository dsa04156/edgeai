<template>
  <figure class="native-history">
    <div class="native-toolbar" aria-label="그래프 구간 탐색">
      <KsButton @click="zoom(0.5)" :disabled="!data.points.length"
        >확대</KsButton
      >
      <KsButton @click="zoom(2)" :disabled="!range">축소</KsButton>
      <KsButton @click="shift(-0.5)" :disabled="!range">이전 구간</KsButton>
      <KsButton @click="shift(0.5)" :disabled="!range">다음 구간</KsButton>
      <KsButton @click="reset" :disabled="!range">전체 구간</KsButton>
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
      슬라이더·휠로 구간을 조절할 수 있습니다.
      {{ range ? "선택한 시각 구간을 유지합니다." : "전체 구간을 표시합니다." }}
    </figcaption>
    <p v-if="!data.points.length" class="native-note">
      선택한 기간에 표시할 기록이 없습니다.
    </p>
  </figure>
</template>
<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from "vue";
import { KsButton } from "@kestra-io/design-system";
import {
  init,
  configureChart,
  updateChart,
  type ECharts,
  type HistoryChartData,
} from "../lib/history-echarts";
const formatTime = (value: number) => new Date(value).toLocaleString("ko-KR");
const props = defineProps<{ data: HistoryChartData; scope: string }>();
const element = ref<HTMLElement>(),
  range = ref<{ from: number; to: number } | null>(null);
const visible = computed(
  () => range.value || { from: props.data.from, to: props.data.to },
);
let chart: ECharts | undefined, observer: ResizeObserver | undefined;
function applyRange(from: number, to: number) {
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
  updateChart(chart, props.data);
  if (range.value) applyRange(range.value.from, range.value.to);
  else reset();
}
watch(() => props.data, render);
watch(
  () => props.scope,
  () => {
    range.value = null;
    render();
  },
);
onMounted(() => {
  if (!element.value) return;
  chart = init(element.value);
  configureChart(chart, element.value);
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
    if (state.start <= 0 && state.end >= 100) {
      range.value = null;
      return;
    }
    const width = props.data.to - props.data.from;
    range.value = {
      from: Number(
        state.startValue ?? props.data.from + (width * state.start) / 100,
      ),
      to: Number(state.endValue ?? props.data.from + (width * state.end) / 100),
    };
  });
  observer = new ResizeObserver(() => chart?.resize());
  observer.observe(element.value);
  render();
});
onBeforeUnmount(() => {
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
