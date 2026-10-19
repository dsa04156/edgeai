<template>
  <InteractiveHistoryChart :data="data" :scope="scope" />
  <p class="native-note">
    기록 누락·실행 세대 변경 구간은 선을 연결하지 않습니다.
    {{
      history.aggregation
        ? `${history.aggregation.bucketSeconds}초 구간 평균`
        : "원본 결과"
    }}
  </p>
</template>
<script setup lang="ts">
import { computed } from "vue";
import type { Schema } from "./native-api";
import type { HistoryChartData } from "../lib/history-echarts";
import InteractiveHistoryChart from "./InteractiveHistoryChart.vue";
const props = defineProps<{
  history: Schema["ContinuousHistory"];
  interval: number;
  label: string;
  scope: string;
  unit?: string;
}>();
const data = computed<HistoryChartData>(() => {
  const rows = props.history.items
    .filter(
      (row) =>
        typeof (row.average ?? row.value) === "number" ||
        typeof (row.average ?? row.value) === "boolean",
    )
    .sort((a, b) => Date.parse(a.evaluationAt) - Date.parse(b.evaluationAt));
  return {
    name: props.label,
    from: Date.parse(props.history.from),
    to: Date.parse(props.history.to),
    connected: true,
    showSymbol: false,
    gaps: props.history.gaps.map((g) => ({
      from: Date.parse(g.from),
      to: Date.parse(g.to || props.history.to),
    })),
    points: rows.map((row, index) => {
      const previous = rows[index - 1],
        at = Date.parse(row.evaluationAt),
        value = row.average ?? row.value;
      return {
        at,
        value: Number(value),
        boolean: typeof value === "boolean",
        unit: row.unit || props.unit,
        breakBefore:
          !!previous &&
          (row.generation !== previous.generation ||
            at - Date.parse(previous.evaluationAt) >
              Math.max(
                props.interval,
                props.history.aggregation?.bucketSeconds || 0,
              ) *
                3000 ||
            props.history.gaps.some(
              (g) =>
                Date.parse(g.from) < at &&
                Date.parse(g.to || props.history.to) >
                  Date.parse(previous.evaluationAt),
            )),
      };
    }),
  };
});
</script>
