<template>
  <TopNavBar title="데이터 저장소"
    ><template #actions
      ><KsButton :disabled="busy" @click="act(load)"
        >새로고침</KsButton
      ></template
    ></TopNavBar
  >
  <main ref="page" class="native-page">
    <NativeLoading :busy="busy || (chartBusy && !chart)" />
    <NativeFeedback :error="error" />
    <template v-if="catalog">
      <p class="native-note">
        {{ catalog.engine }} · {{ catalog.status }} · 원본
        {{ catalog.rawRetentionDays }}일 보관 · 수집
        {{ catalog.collector.status }} · 저장 수신
        {{ catalog.collector.storedDeliveries }}회 / 거절
        {{ catalog.collector.rejectedDeliveries }}회
      </p>
      <p v-if="catalog.truncated">일부 센서만 표시됩니다.</p>
      <p v-if="!catalog.sensors.length" class="native-note">
        저장된 센서 원본이 없습니다.
      </p>
      <div class="native-toolbar">
        <KsSelect
          v-model="selectedKey"
          filterable
          aria-label="저장 원본 선택"
          class="data-store-sensor"
          @change="changeSensor()"
          ><KsOption
            v-for="sensor in catalog.sensors"
            :key="sensorKey(sensor)"
            :value="sensorKey(sensor)"
            :label="`${sensor.deviceId} · ${sensor.property} · ${sensor.quality}`" /></KsSelect
        ><KsSegmented
          :modelValue="activeRange"
          :options="ranges"
          aria-label="최근 구간 바로 보기"
          @update:model-value="pickRange"
        /><KsButton
          :type="follow ? 'primary' : undefined"
          :aria-pressed="follow"
          :disabled="!selected"
          @click="goLive"
          >실시간</KsButton
        >
      </div>
      <p class="native-note" role="status">
        {{
          follow
            ? "실시간 · 5초마다 최신 구간 갱신"
            : "탐색 중 · 자동 갱신 멈춤"
        }}
        ·
        {{ lastUpdated ? `${time(lastUpdated)} 조회` : "첫 기록 조회 대기" }}
        <template v-if="chartBusy"> · 그래프 구간 불러오는 중…</template>
      </p>
      <NativeFeedback :error="chartError" />
      <template v-if="selected">
        <InteractiveHistoryChart
          :data="chartData"
          :scope="selectedKey"
          :viewport="viewport"
          :loading="chartBusy"
          @update:viewport="explore"
        />
        <p v-if="chart" class="native-note">
          불러온 구간 {{ time(chart.from) }} → {{ time(chart.to) }} · 전체
          {{ chart.totalObservations }}개 중 {{ chart.items.length }}개 표시 ·
          {{
            chart.displayMode === "ORIGINALS"
              ? "원본 측정값"
              : "시간 구간별 처음·최소·최대·마지막 원본 선택"
          }}
          · 수집 시각 기준 · 평소 측정 간격보다 크게 빈 구간은 선을 잇지
          않습니다.
        </p>
      </template>
      <p v-if="olderRecords" class="native-note">
        이전 기록을 보는 동안 표는 고정하고 그래프만 갱신합니다.
        <KsButton :disabled="recordsBusy" @click="loadRecords('reset')"
          >현재 구간 기록 보기</KsButton
        >
      </p>
      <NativeFeedback :error="recordsError" />
      <p v-if="recordPeriod" class="native-note">
        표 {{ time(new Date(recordPeriod.from)) }} →
        {{ time(new Date(recordPeriod.to)) }} · 최신순
      </p>
      <NativeRecords
        title="센서 원본 기록"
        :busy="busy || recordsBusy"
        :rows="recordRows"
        :columns="recordColumns"
      >
        <template #observedAt="{ row }"
          >{{ time(row.observedAt)
          }}<small>{{ row.timeBasis }}</small></template
        >
        <template #value="{ row }">{{ row.value }} {{ row.unit }}</template>
        <template #identity="{ row }"
          ><details>
            <summary>수집 출처</summary>
            <pre class="native-json">{{ pretty(row) }}</pre>
          </details></template
        >
      </NativeRecords>
      <KsButton
        v-if="nextCursor && records.length < 2000"
        :disabled="recordsBusy"
        @click="loadRecords('more')"
        >원본 더보기</KsButton
      >
      <p v-if="nextCursor && records.length >= 2000">
        최근 원본 2,000개 표시 한도입니다. 그래프를 확대해 구간을 줄이세요.
        그래프는 표시 구간 전체를 표시합니다.
      </p>
    </template>
  </main>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import {
  KsButton,
  KsSelect,
  KsOption,
  KsSegmented,
} from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import {
  get,
  pretty,
  request,
  time,
  useAction,
  usePoll,
  type Schema,
} from "./native-api";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
import NativeRecords from "./NativeRecords.vue";
import InteractiveHistoryChart from "./InteractiveHistoryChart.vue";
import { useSensorHistory } from "./sensor-history";
import type { HistoryChartData } from "../lib/history-echarts";
import {
  historyBounds,
  markGaps,
  pointBudget,
  quickRanges,
} from "../lib/history-viewport";
useRouteContext(computed(() => ({ title: "데이터 저장소" })));
const route = useRoute(),
  { busy, error, act } = useAction();
const catalog = ref<Schema["DataStoreCatalog"] | null>(null),
  selectedKey = ref("");
const sensorKey = (sensor: { deviceUid: string; property: string }) =>
  `${sensor.deviceUid}/${sensor.property}`;
const selected = computed(() =>
  catalog.value?.sensors.find(
    (sensor) => sensorKey(sensor) === selectedKey.value,
  ),
);
const retentionDays = () => catalog.value?.rawRetentionDays || 1;
// The chart spans the page width, so the page is measured before the first chart request.
const page = ref<HTMLElement>(),
  pageWidth = ref(0),
  budget = computed(() => pointBudget(pageWidth.value));
let resizeObserver: ResizeObserver | undefined;
const {
  follow,
  viewport,
  bounds,
  chart,
  chartBusy,
  chartError,
  records,
  recordsBusy,
  recordsError,
  recordPeriod,
  nextCursor,
  olderRecords,
  lastUpdated,
  loadAll,
  loadRecords,
  explore,
  startFollowing,
  resize,
  clear,
} = useSensorHistory({
  sensor: () => selected.value,
  retentionDays,
  budget: () => budget.value,
  read: async (path, signal) =>
    (
      await request(path, {
        signal: AbortSignal.any([signal, AbortSignal.timeout(25000)]),
      })
    ).json(),
});
const ranges = computed(() =>
  quickRanges(historyBounds(Date.now(), retentionDays())).map((range) => ({
    value: range.span,
    label: range.label,
  })),
);
const activeRange = computed(() =>
  follow.value ? viewport.value.to - viewport.value.from : undefined,
);
let catalogGeneration = 0;
async function load(preferRoute = false) {
  const current = ++catalogGeneration;
  const value = await get<Schema["DataStoreCatalog"]>("data-store");
  if (current !== catalogGeneration) return;
  catalog.value = value;
  const previous = selectedKey.value;
  if (preferRoute && typeof route.query.device === "string") {
    const target = value.sensors.find(
      (sensor) =>
        sensor.deviceId === route.query.device &&
        sensor.property === route.query.property,
    );
    selectedKey.value = target ? sensorKey(target) : "";
    if (!target) {
      clear();
      throw new Error("지정한 센서 원본을 현재 저장소에서 찾지 못했습니다.");
    }
  }
  if (!selected.value) {
    const sensor =
      catalog.value.sensors.find(
        (s) =>
          s.deviceId === route.query.device &&
          s.property === route.query.property,
      ) || catalog.value.sensors[0];
    selectedKey.value = sensor ? sensorKey(sensor) : "";
  }
  if (selectedKey.value !== previous || !selected.value) clear();
  if (selected.value) await loadAll();
}
const pickRange = (span: string | number | boolean | undefined) => {
  if (typeof span === "number") startFollowing(span);
};
const goLive = () => startFollowing(viewport.value.to - viewport.value.from);
function changeSensor() {
  clear();
  if (selected.value) void loadAll();
}
// Every pan/zoom step re-renders this page; stable table props keep up to 2,000 rows from re-rendering with it.
const recordRows = computed(() =>
  records.value.map((r, i) => ({ ...r, id: String(i) })),
);
const recordColumns = [
  { key: "observedAt", label: "측정 시각", width: 250 },
  { key: "value", label: "원본값" },
  { key: "quality", label: "검증 상태" },
  { key: "identity", label: "원본 식별", width: 330 },
];
const chartData = computed<HistoryChartData>(() => {
  const unit = records.value[0]?.unit || selected.value?.unit;
  return {
    name: `${selected.value?.property || "센서"} 원본 측정값 그래프`,
    from: bounds.value.from,
    to: bounds.value.to,
    connected: true,
    showSymbol: false,
    points: markGaps(
      (chart.value?.items || []).map((p) => ({
        at: Date.parse(p.observedAt),
        value: Number(p.value),
        boolean: typeof p.value === "boolean",
        unit,
      })),
      (chart.value?.selection?.windowSeconds || 0) * 1000,
    ),
  };
});
usePoll(async () => {
  if (
    !follow.value ||
    !selected.value ||
    busy.value ||
    chartBusy.value ||
    recordsBusy.value ||
    document.hidden
  )
    return;
  const [latestCatalog] = await Promise.all([
    get<Schema["DataStoreCatalog"]>("data-store").catch(() => null),
    loadAll(true),
  ]);
  if (catalog.value && latestCatalog)
    catalog.value = {
      ...catalog.value,
      status: latestCatalog.status,
      collector: latestCatalog.collector,
    };
}, 5000);
watch(
  () => [route.query.device, route.query.property],
  () => {
    void act(() => load(true));
  },
);
watch(budget, () => {
  if (selected.value) resize();
});
onMounted(() => {
  pageWidth.value = page.value?.clientWidth ?? 0;
  resizeObserver = new ResizeObserver(() => {
    pageWidth.value = page.value?.clientWidth ?? 0;
  });
  if (page.value) resizeObserver.observe(page.value);
  void act(() => load(true));
});
onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  clear();
});
</script>
<style scoped>
small {
  display: block;
}
.data-store-sensor {
  width: min(100%, 420px);
}
</style>
