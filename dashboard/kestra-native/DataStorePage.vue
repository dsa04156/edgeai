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
          @change="query(false)"
          ><KsOption
            v-for="sensor in catalog.sensors"
            :key="sensorKey(sensor)"
            :value="sensorKey(sensor)"
            :label="`${sensor.deviceId} · ${sensor.property} · ${sensor.quality}`" /></KsSelect
        ><KsSelect v-model="hours" aria-label="원본 조회 기간"
          ><KsOption
            v-for="hour in [1, 6, 24, 168, 720].filter(
              (h) => h <= catalog!.rawRetentionDays * 24,
            )"
            :key="hour"
            :value="hour"
            :label="`${hour}시간`" /></KsSelect
        ><KsButton :disabled="historyBusy || !selected" @click="query(false)"
          >기록 조회</KsButton
        >
      </div>
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
        <svg
          v-if="dots.length"
          class="native-history-chart"
          viewBox="0 0 1000 250"
          role="img"
          :aria-label="`${selected?.property} 원본 측정값 그래프`"
        >
          <line x1="45" y1="220" x2="980" y2="220" stroke="currentColor" />
          <text x="4" y="20" fill="currentColor">{{ range.max }}</text>
          <text x="4" y="218" fill="currentColor">{{ range.min }}</text>
          <circle
            v-for="(point, i) in dots"
            :key="i"
            :cx="point.x"
            :cy="point.y"
            r="2"
            fill="var(--ks-content-link)"
          >
            <title>{{ time(point.at) }} · {{ point.value }}</title>
          </circle>
        </svg>
        <p v-else>선택 기간에 표시할 숫자·참/거짓 원본이 없습니다.</p>
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
        @click="query(true)"
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
import { KsButton, KsSelect, KsOption } from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import { get, pretty, time, useAction, type Schema } from "./native-api";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
import NativeRecords from "./NativeRecords.vue";
useRouteContext(computed(() => ({ title: "데이터 저장소" })));
const route = useRoute(),
  { busy, error, act } = useAction();
const catalog = ref<Schema["DataStoreCatalog"] | null>(null),
  selectedKey = ref(""),
  hours = ref(1),
  historyBusy = ref(false),
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
  if (selected.value) await query(false);
}
async function query(more = false) {
  const sensor = selected.value;
  if (!sensor) return;
  const request = ++generation;
  historyBusy.value = true;
  historyError.value = "";
  if (!more) {
    const to = new Date();
    period = {
      from: new Date(
        to.getTime() -
          Math.min(hours.value, (catalog.value?.rawRetentionDays || 1) * 24) *
            3600000,
      ).toISOString(),
      to: to.toISOString(),
    };
    records.value = [];
    chart.value = null;
    nextCursor.value = undefined;
  }
  const params = new URLSearchParams({
    ...period,
    limit: String(Math.min(1000, 2000 - records.value.length)),
    order: "desc",
    ...(more && nextCursor.value ? { cursor: nextCursor.value } : {}),
  });
  const base = `data-store/sensors/${sensor.deviceUid}/${encodeURIComponent(sensor.property)}`;
  try {
    const [history, plot] = await Promise.all([
      get<Schema["SensorOriginalHistory"]>(`${base}/history?${params}`),
      more
        ? Promise.resolve(chart.value)
        : get<Schema["SensorPeriodChart"]>(
            `${base}/chart?${new URLSearchParams(period)}`,
          ),
    ]);
    if (request !== generation) return;
    records.value = more ? [...records.value, ...history.items] : history.items;
    nextCursor.value = history.nextCursor;
    chart.value = plot;
  } catch (cause) {
    if (request === generation)
      historyError.value =
        cause instanceof Error ? cause.message : "원본 조회 실패";
  } finally {
    if (request === generation) historyBusy.value = false;
  }
}
const range = computed(() => {
  const values =
    chart.value?.items.map((p) => Number(p.value)).filter(Number.isFinite) ||
    [];
  return {
    min: values.length ? Math.min(...values) : 0,
    max: values.length ? Math.max(...values) : 1,
  };
});
const dots = computed(() => {
  const plot = chart.value;
  if (!plot) return [];
  const start = Date.parse(plot.from),
    duration = Date.parse(plot.to) - start;
  return plot.items
    .filter((p) => Number.isFinite(Number(p.value)))
    .map((p) => ({
      x:
        45 + ((Date.parse(p.observedAt) - start) / Math.max(duration, 1)) * 935,
      y:
        220 -
        ((Number(p.value) - range.value.min) /
          (range.value.max - range.value.min || 1)) *
          200,
      at: p.observedAt,
      value: p.value,
    }));
});
watch(
  () => [route.query.device, route.query.property],
  () => {
    void act(() => load(true));
  },
);
onMounted(() => act(() => load(true)));
</script>
<style scoped>
.native-history-chart {
  width: 100%;
  height: 250px;
  min-height: 250px;
  flex-shrink: 0;
  border: 1px solid var(--ks-border-primary);
  border-radius: 8px;
}
small {
  display: block;
}
</style>
