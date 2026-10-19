<template>
  <TopNavBar title="데이터 저장소"
    ><template #actions
      ><KsButton :disabled="busy" @click="act(load)"
        >새로고침</KsButton
      ></template
    ></TopNavBar
  >
  <main class="native-page">
    <NativeLoading :busy="busy || historyBusy" />
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
      <div class="native-toolbar">
        <KsSelect
          v-model="selectedKey"
          filterable
          aria-label="저장 원본 선택"
          @change="query()"
          ><KsOption
            v-for="sensor in catalog.sensors"
            :key="sensorKey(sensor)"
            :value="sensorKey(sensor)"
            :label="`${sensor.deviceId} · ${sensor.property} · ${sensor.quality}`" /></KsSelect
        ><KsSelect v-model="hours" aria-label="원본 조회 기간" @change="query()"
          ><KsOption
            v-for="hour in [1, 6, 24, 168, 720].filter(
              (h) => h <= catalog!.rawRetentionDays * 24,
            )"
            :key="hour"
            :value="hour"
            :label="`${hour}시간`" /></KsSelect
        ><KsButton :disabled="historyBusy || !selected" @click="query()"
          >기록 조회</KsButton
        >
      </div>
      <div class="native-toolbar">
        <KsCheckbox v-model="liveEnabled">기록 자동 갱신 · 5초</KsCheckbox>
        <span class="native-note" role="status"
          >{{ liveEnabled ? "자동 갱신 사용" : "자동 갱신 일시정지" }} ·
          {{
            lastUpdated ? `${time(lastUpdated)} 조회` : "첫 기록 조회 대기"
          }}</span
        >
      </div>
      <p v-if="olderRecords" class="native-note">
        이전 기록을 보는 동안 표는 고정하고 그래프만 갱신합니다.
        <KsButton :disabled="historyBusy" @click="query()"
          >최신 기록 보기</KsButton
        >
      </p>
      <NativeFeedback :error="historyError" />
      <p v-if="historyBusy" role="status">원본 기록 조회 중…</p>
      <template v-if="chart">
        <p class="native-note">
          {{ time(chart.from) }} → {{ time(chart.to) }} · 전체
          {{ chart.totalObservations }}개 중 {{ chart.items.length }}개 표시 ·
          {{
            chart.displayMode === "ORIGINALS"
              ? "원본 측정값"
              : "시간 구간별 처음·최소·최대·마지막 원본 선택"
          }}
          · 수집 시각 기준
        </p>
        <InteractiveHistoryChart
          :data="chartData"
          :scope="`${selectedKey}/${hours}`"
        />
      </template>
      <NativeRecords
        title="센서 원본 기록"
        :busy="busy || historyBusy"
        :rows="records.map((r, i) => ({ ...r, id: String(i) }))"
        :columns="[
          { key: 'observedAt', label: '측정 시각', width: 250 },
          { key: 'value', label: '원본값' },
          { key: 'quality', label: '검증 상태' },
          { key: 'identity', label: '원본 식별', width: 330 },
        ]"
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
        :disabled="historyBusy"
        @click="query('more')"
        >원본 더보기</KsButton
      >
      <p v-if="nextCursor && records.length >= 2000">
        최근 원본 2,000개 표시 한도입니다. 기간을 줄여 조회하세요. 그래프는 선택
        기간 전체를 표시합니다.
      </p>
    </template>
  </main>
</template>
<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import {
  KsButton,
  KsSelect,
  KsOption,
  KsCheckbox,
} from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import {
  get,
  pretty,
  time,
  useAction,
  usePoll,
  type Schema,
} from "./native-api";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
import NativeRecords from "./NativeRecords.vue";
import InteractiveHistoryChart from "./InteractiveHistoryChart.vue";
import type { HistoryChartData } from "../lib/history-echarts";
useRouteContext(computed(() => ({ title: "데이터 저장소" })));
const route = useRoute(),
  { busy, error, act } = useAction();
const catalog = ref<Schema["DataStoreCatalog"] | null>(null),
  selectedKey = ref(""),
  hours = ref(1),
  historyBusy = ref(false),
  liveBusy = ref(false),
  liveEnabled = ref(true),
  olderRecords = ref(false),
  lastUpdated = ref(""),
  historyError = ref(""),
  records = ref<Schema["SensorOriginalHistory"]["items"]>([]),
  chart = ref<Schema["SensorPeriodChart"] | null>(null),
  nextCursor = ref<string | undefined>();
const sensorKey = (sensor: { deviceUid: string; property: string }) =>
  `${sensor.deviceUid}/${sensor.property}`;
const selected = computed(() =>
  catalog.value?.sensors.find(
    (sensor) => sensorKey(sensor) === selectedKey.value,
  ),
);
let catalogGeneration = 0;
let generation = 0,
  period = { from: "", to: "" };
async function load(preferRoute = false) {
  const current = ++catalogGeneration;
  const value = await get<Schema["DataStoreCatalog"]>("data-store");
  if (current !== catalogGeneration) return;
  catalog.value = value;
  if (preferRoute && typeof route.query.device === "string") {
    const target = value.sensors.find(
      (sensor) =>
        sensor.deviceId === route.query.device &&
        sensor.property === route.query.property,
    );
    selectedKey.value = target ? sensorKey(target) : "";
    if (!target) {
      ++generation;
      historyBusy.value = false;
      historyError.value = "";
      lastUpdated.value = "";
      records.value = [];
      chart.value = null;
      nextCursor.value = undefined;
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
  if (selected.value) await query();
}
function recentPeriod() {
  const to = new Date();
  return {
    from: new Date(
      to.getTime() -
        Math.min(hours.value, (catalog.value?.rawRetentionDays || 1) * 24) *
          3600000,
    ).toISOString(),
    to: to.toISOString(),
  };
}
async function query(mode: "reset" | "more" | "live" = "reset") {
  const sensor = selected.value;
  if (
    !sensor ||
    (mode === "more" && (!nextCursor.value || records.value.length >= 2000))
  )
    return;
  const request = ++generation;
  if (mode === "live") liveBusy.value = true;
  else {
    historyBusy.value = true;
    historyError.value = "";
  }
  if (mode === "reset") {
    period = recentPeriod();
    lastUpdated.value = "";
    olderRecords.value = false;
    records.value = [];
    chart.value = null;
    nextCursor.value = undefined;
  }
  const requestPeriod = mode === "live" ? recentPeriod() : { ...period };
  const readRows = mode !== "live" || !olderRecords.value;
  const params = new URLSearchParams({
    ...requestPeriod,
    limit: String(
      mode === "more" ? Math.min(1000, 2000 - records.value.length) : 1000,
    ),
    order: "desc",
    ...(mode === "more" && nextCursor.value
      ? { cursor: nextCursor.value }
      : {}),
  });
  const base = `data-store/sensors/${sensor.deviceUid}/${encodeURIComponent(sensor.property)}`;
  try {
    const [history, plot, latestCatalog] = await Promise.all([
      readRows
        ? get<Schema["SensorOriginalHistory"]>(`${base}/history?${params}`)
        : Promise.resolve(null),
      mode === "more"
        ? Promise.resolve(chart.value)
        : get<Schema["SensorPeriodChart"]>(
            `${base}/chart?${new URLSearchParams(requestPeriod)}`,
          ),
      mode === "live"
        ? get<Schema["DataStoreCatalog"]>("data-store")
        : Promise.resolve(null),
    ]);
    if (request !== generation) return;
    if (catalog.value && latestCatalog)
      catalog.value = {
        ...catalog.value,
        status: latestCatalog.status,
        collector: latestCatalog.collector,
      };
    if (history) {
      records.value =
        mode === "more" ? [...records.value, ...history.items] : history.items;
      nextCursor.value = history.nextCursor;
      if (mode === "more") olderRecords.value = true;
      else period = requestPeriod;
    }
    chart.value = plot;
    historyError.value = "";
    lastUpdated.value = new Date().toISOString();
  } catch (cause) {
    if (request === generation)
      historyError.value =
        (mode === "live"
          ? "자동 갱신 실패 · 마지막 조회 기록을 표시합니다. "
          : "") + (cause instanceof Error ? cause.message : "원본 조회 실패");
  } finally {
    if (mode === "live") liveBusy.value = false;
    if (request === generation) historyBusy.value = false;
  }
}
const chartData = computed<HistoryChartData>(() => ({
  name: `${selected.value?.property || "센서"} 원본 측정값 그래프`,
  from: Date.parse(chart.value?.from || period.from),
  to: Date.parse(chart.value?.to || period.to),
  connected: true,
  showSymbol: false,
  points: (chart.value?.items || []).map((p) => ({
    at: Date.parse(p.observedAt),
    value: Number(p.value),
    boolean: typeof p.value === "boolean",
    unit: records.value[0]?.unit,
  })),
}));
usePoll(async () => {
  if (
    liveEnabled.value &&
    selected.value &&
    !busy.value &&
    !historyBusy.value &&
    !liveBusy.value &&
    !document.hidden
  )
    await query("live");
}, 5000);
watch(
  () => [route.query.device, route.query.property],
  () => {
    void act(() => load(true));
  },
);
onMounted(() => act(() => load(true)));
</script>
<style scoped>
small {
  display: block;
}
</style>
