<!-- Adapted from Kestra v2.0.6 ui/src/components/dashboard/Dashboard.vue.
     Native Header/Sections and all chart/table components remain upstream.
     EdgeAI provides a current-state dashboard and a read-only refresh action. -->
<template>
  <Header v-if="dashboard" :dashboard :load />
  <section class="operations-toolbar" aria-label="운영 현황 갱신">
    <span role="status">{{
      busy
        ? "EdgeAI 서버에서 불러오는 중…"
        : asOf
          ? `${formatTime(asOf)} 조회 시점 관측 · 수동 갱신`
          : "조회 상태 확인 필요"
    }}</span>
    <KsButton :icon="Refresh" :loading="busy" :disabled="busy" @click="refresh"
      >새로고침</KsButton
    >
  </section>
  <NativeLoading :busy="busy" />
  <div v-if="errors.length" class="operations-errors">
    <KsAlert
      type="warning"
      :closable="false"
      showIcon
      role="alert"
      :title="`${errors.join(' · ')}. 해당 값은 — 또는 확인 불가로 표시합니다.`"
    />
  </div>
  <template v-if="dashboard">
    <Sections
      :key="`kpi:${snapshotId}`"
      :dashboard
      :charts="kpiCharts"
      :showDefault="true"
      :padding="true"
    />
    <NodeResources v-if="overview" :overview />
    <Sections
      :key="`other:${snapshotId}`"
      :dashboard
      :charts="otherCharts"
      :showDefault="true"
      :padding="true"
    />
  </template>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { KsAlert, KsButton } from "@kestra-io/design-system";
import Refresh from "vue-material-design-icons/Refresh.vue";
import * as YAML from "@kestra-io/topology/flow-yaml-utils";
import Header from "kestra-original/components/dashboard/components/Header.vue";
import Sections from "kestra-original/components/dashboard/sections/Sections.vue";
import { useDashboardStore } from "kestra-original/stores/dashboard";
import type {
  Chart,
  Dashboard,
} from "kestra-original/components/dashboard/types";
import type { Overview } from "./overview";
import NodeResources from "./NodeResources.vue";
import NativeLoading from "./NativeLoading.vue";

const store = useDashboardStore();
const dashboard = computed(() => store.activeDashboard);
const charts = ref<Chart[]>([]);
const overview = ref<Overview | null>(null);
const kpiCharts = computed(() =>
  charts.value.filter((chart) => chart.type.endsWith(".KPI")),
);
const otherCharts = computed(() =>
  charts.value.filter((chart) => !chart.type.endsWith(".KPI")),
);
const snapshotId = ref("");
const busy = ref(false);
const asOf = ref("");
const errors = ref<string[]>([]);
const formatTime = (value: string) =>
  new Date(value).toLocaleTimeString("ko-KR");
async function readSnapshot(force = false) {
  const response = await fetch(
    `/api/edgeai/overview${force ? "?refresh=1" : ""}`,
    { cache: "no-store" },
  );
  if (!response.ok) throw new Error("운영 현황을 불러오지 못했습니다.");
  const data: Overview = await response.json();
  overview.value = data;
  asOf.value = data.asOf;
  errors.value = data.errors;
  charts.value = (dashboard.value?.charts ?? []).map((chart) => ({
    ...chart,
    content: YAML.stringify({ ...chart, edgeaiSnapshot: data.snapshotId }),
  }));
  snapshotId.value = data.snapshotId;
}
async function load() {
  busy.value = true;
  try {
    const definitions = await store.loadDefaultDefinitions();
    const definition = YAML.parse(definitions.main) as Dashboard;
    store.activeDashboard = {
      ...definition,
      id: "default",
      deleted: false,
      edgeaiReadOnly: true,
    } as Dashboard;
    await readSnapshot();
  } catch {
    errors.value = ["운영 현황을 불러오지 못했습니다. 새로고침하세요"];
  } finally {
    busy.value = false;
  }
}
async function refresh() {
  if (!dashboard.value || !charts.value.length) return load();
  busy.value = true;
  try {
    await readSnapshot(true);
  } catch {
    errors.value = ["운영 현황을 새로고침하지 못했습니다"];
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>

<style scoped lang="scss">
.operations-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ks-spacing-4);
  padding: var(--ks-spacing-6) var(--ks-spacing-8) 0;
  color: var(--ks-text-secondary);
  font-size: var(--ks-font-size-xs);
}
.operations-errors {
  padding: var(--ks-spacing-4) var(--ks-spacing-8) 0;
}
:global(:root) {
  --ks-chart-ready: var(--ks-chart-success);
  --ks-chart-not_ready: var(--ks-chart-failed);
  --ks-chart-unknown: var(--ks-chart-created);
  --ks-chart-cpu: var(--ks-chart-duration);
  --ks-chart-gpu: var(--ks-chart-running);
  --ks-chart-npu: var(--ks-chart-duration);
}
@media (max-width: 768px) {
  .operations-toolbar {
    padding: var(--ks-spacing-4) var(--ks-spacing-4) 0;
  }
  .operations-errors {
    padding: var(--ks-spacing-4) var(--ks-spacing-4) 0;
  }
}
</style>
