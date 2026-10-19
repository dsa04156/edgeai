<template>
  <figure v-if="points.length">
    <svg
      viewBox="0 0 800 210"
      role="img"
      :aria-label="label"
      class="history-chart"
    >
      <title>{{ label }}</title>
      <rect
        v-for="(gap, index) in history.gaps"
        :key="index"
        :x="x(Date.parse(gap.from))"
        y="20"
        :width="
          Math.max(
            1,
            x(Date.parse(gap.to || history.to)) - x(Date.parse(gap.from)),
          )
        "
        height="150"
        class="gap"
      />
      <path d="M55 20V170H780" class="axis" />
      <text x="5" y="30">{{ maximum.toPrecision(4) }}</text>
      <text x="5" y="170">{{ minimum.toPrecision(4) }}</text>
      <template v-for="(segment, index) in segments" :key="index">
        <polyline
          v-if="segment.length > 1"
          :points="segment.map((p) => `${x(p.at)},${y(p.value)}`).join(' ')"
          class="line"
        />
        <circle
          v-else
          :cx="x(segment[0].at)"
          :cy="y(segment[0].value)"
          r="3"
          class="dot"
        />
      </template>
      <text x="55" y="200">
        {{ new Date(history.from).toLocaleTimeString("ko-KR") }}
      </text>
      <text x="780" y="200" text-anchor="end">
        {{ new Date(history.to).toLocaleTimeString("ko-KR") }}
      </text>
    </svg>
    <figcaption class="native-note">
      기록 누락·실행 세대 변경 구간은 선을 연결하지 않습니다.
      {{
        history.aggregation
          ? `${history.aggregation.bucketSeconds}초 구간 평균`
          : "원본 결과"
      }}
    </figcaption>
  </figure>
  <p v-else class="native-note">선택한 기간에 표시할 기록이 없습니다.</p>
</template>
<script setup lang="ts">
import { computed } from "vue";
import type { Schema } from "./native-api";
const props = defineProps<{
  history: Schema["ContinuousHistory"];
  interval: number;
  label: string;
}>();
const points = computed(() =>
  props.history.items
    .filter((row) => {
      const value = row.average ?? row.value;
      return typeof value === "number" || typeof value === "boolean";
    })
    .map((row) => ({
      at: Date.parse(row.evaluationAt),
      value:
        typeof (row.average ?? row.value) === "boolean"
          ? Number(row.value)
          : Number(row.average ?? row.value),
      generation: row.generation,
    }))
    .filter((p) => Number.isFinite(p.at) && Number.isFinite(p.value))
    .sort((a, b) => a.at - b.at),
);
const minimum = computed(() => Math.min(...points.value.map((p) => p.value))),
  maximum = computed(() => Math.max(...points.value.map((p) => p.value)));
const x = (at: number) =>
  55 +
  ((Math.min(
    Date.parse(props.history.to),
    Math.max(Date.parse(props.history.from), at),
  ) -
    Date.parse(props.history.from)) /
    Math.max(
      1,
      Date.parse(props.history.to) - Date.parse(props.history.from),
    )) *
    725;
const y = (value: number) =>
  170 -
  ((value - minimum.value) /
    Math.max(0.000001, maximum.value - minimum.value)) *
    150;
const segments = computed(() => {
  const rows: (typeof points.value)[] = [];
  for (const point of points.value) {
    const last = rows.at(-1)?.at(-1);
    if (
      !last ||
      point.generation !== last.generation ||
      point.at - last.at >
        Math.max(
          props.interval,
          props.history.aggregation?.bucketSeconds || 0,
        ) *
          3000 ||
      props.history.gaps.some(
        (g) =>
          Date.parse(g.from) < point.at &&
          Date.parse(g.to || props.history.to) > last.at,
      )
    )
      rows.push([point]);
    else rows.at(-1)!.push(point);
  }
  return rows;
});
</script>
<style scoped>
.history-chart {
  width: 100%;
  height: auto;
  background: var(--ks-bg-base);
}
.axis {
  fill: none;
  stroke: var(--ks-border-default);
}
.line {
  fill: none;
  stroke: var(--ks-chart-duration);
  stroke-width: 2;
}
.dot {
  fill: var(--ks-chart-duration);
}
.gap {
  fill: var(--ks-bg-hover);
}
text {
  fill: var(--ks-text-secondary);
  font-size: 12px;
}
</style>
