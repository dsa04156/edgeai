<template>
  <section class="node-resources" aria-labelledby="node-resources-title">
    <header>
      <h2 id="node-resources-title">노드별 자원 현황</h2>
      <p role="status">{{ scopeLabel }} · 조회 시점 사용률 · 미수집·만료는 —</p>
    </header>
    <KsDataTable
      id="node-resource-table"
      :data="rows"
      rowKey="name"
      tableLayout="fixed"
      :noDataText="emptyText"
      noFirstColumnGutter
      noPaginationGutter
    >
      <KsTableColumn label="노드" :minWidth="190">
        <template #default="{ row }"
          ><RouterLink
            :to="{
              name: 'edgeai/devices',
              params: { tenant: route.params.tenant },
              query: {
                view: views[row.kind as keyof typeof views],
                node: row.name,
              },
            }"
            >{{ row.name }}</RouterLink
          ><small class="node-kind">{{
            kinds[row.kind as keyof typeof kinds]
          }}</small></template
        >
      </KsTableColumn>
      <KsTableColumn label="상태" :minWidth="100">
        <template #default="{ row }"
          ><span class="node-status"
            ><i :class="row.readiness.toLowerCase()" aria-hidden="true" />{{
              readiness[row.readiness as keyof typeof readiness]
            }}</span
          ></template
        >
      </KsTableColumn>
      <KsTableColumn label="CPU" :minWidth="130">
        <template #default="{ row }"
          ><UsageMeter :value="row.cpuPercent" :label="`${row.name} CPU`"
        /></template>
      </KsTableColumn>
      <KsTableColumn label="메모리" :minWidth="130">
        <template #default="{ row }"
          ><UsageMeter :value="row.memoryPercent" :label="`${row.name} 메모리`"
        /></template>
      </KsTableColumn>
      <KsTableColumn label="GPU / NPU" :minWidth="260">
        <template #default="{ row }"
          ><AcceleratorCell :devices="row.accelerators"
        /></template>
      </KsTableColumn>
    </KsDataTable>
    <footer v-if="hasMore">
      <KsButton
        :icon="expanded ? ChevronUp : ChevronDown"
        :aria-expanded="expanded"
        aria-controls="node-resource-table"
        @click="expanded = !expanded"
        >{{
          expanded
            ? inventoryKnown
              ? "서버만 보기"
              : "접기"
            : `더보기 · ${inventoryKnown ? "전체" : "관측 노드"} ${overview.nodes.length}대`
        }}</KsButton
      >
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { RouterLink, useRoute } from "vue-router";
import { KsButton, KsDataTable, KsTableColumn } from "@kestra-io/design-system";
import ChevronDown from "vue-material-design-icons/ChevronDown.vue";
import ChevronUp from "vue-material-design-icons/ChevronUp.vue";
import type { Overview } from "./overview";
import AcceleratorCell from "./AcceleratorCell.vue";
import UsageMeter from "./UsageMeter.vue";

const props = defineProps<{ overview: Overview }>();
const expanded = ref(false);
const route = useRoute();
const views = {
  EDGE_AI_SERVER: "servers",
  EDGE_DEVICE: "edge",
  UNKNOWN: "unknown",
};
const kinds = {
  EDGE_AI_SERVER: "AI 서버",
  EDGE_DEVICE: "엣지 장치",
  UNKNOWN: "분류 확인 불가",
};
const readiness = { READY: "Ready", NOT_READY: "NotReady", UNKNOWN: "Unknown" };
const rank = { EDGE_AI_SERVER: 0, EDGE_DEVICE: 1, UNKNOWN: 2 };
const accelerators = computed(() => [
  ...(props.overview.gpus ?? []).map((device) => ({
    ...device,
    kind: "GPU" as const,
  })),
  ...(props.overview.npus ?? [])
    .filter((device) => device.vendor !== "intel")
    .map((device) => ({ ...device, kind: "NPU" as const })),
]);
const servers = computed(() =>
  props.overview.nodes.filter((node) => node.kind === "EDGE_AI_SERVER"),
);
const inventoryKnown = computed(
  () => props.overview.resources.servers !== null,
);
const hasMore = computed(
  () => props.overview.nodes.length > servers.value.length,
);
const rows = computed(() =>
  (expanded.value ? [...props.overview.nodes] : [...servers.value])
    .sort((a, b) => rank[a.kind] - rank[b.kind] || a.name.localeCompare(b.name))
    .map((node) => ({
      ...node,
      accelerators: accelerators.value.filter(
        (device) => device.node === node.name,
      ),
    })),
);
const scopeLabel = computed(() =>
  !inventoryKnown.value
    ? expanded.value
      ? `분류 확인 불가 · 관측 노드 ${rows.value.length}대`
      : "AI 서버 분류 확인 불가"
    : expanded.value
      ? `전체 노드 ${rows.value.length}대 · 서버 우선`
      : `AI 서버 ${rows.value.length}대`,
);
const emptyText = computed(() =>
  props.overview.resources.servers === null
    ? "서버 분류를 확인할 수 없습니다."
    : expanded.value
      ? "관측된 노드가 없습니다."
      : "분류된 AI 서버가 없습니다.",
);
</script>

<style scoped lang="scss">
.node-resources {
  flex: 0 0 auto;
  margin: 0 var(--ks-spacing-6) var(--ks-spacing-4);
  padding: var(--ks-spacing-5);
  background: var(--ks-bg-surface);
  border: 1px solid var(--ks-border-default);
  border-radius: var(--ks-radius-base);
  box-shadow: 0 2px 4px 0 var(--ks-shadow-element);
}
:deep(.ks-data-table-wrapper),
:deep(.ks-data-table-content) {
  height: auto;
}
h2 {
  margin: 0;
  font-size: var(--ks-font-size-md);
  font-weight: var(--ks-font-weight-bold);
  color: var(--ks-text-primary);
}
header {
  margin-bottom: var(--ks-spacing-5);
}
p,
.node-kind {
  color: var(--ks-text-secondary);
  font-size: var(--ks-font-size-xs);
}
p {
  margin: var(--ks-spacing-1) 0 0;
}
.node-kind {
  display: block;
}
.node-status {
  display: inline-flex;
  align-items: center;
  gap: var(--ks-spacing-2);
}
i {
  width: 8px;
  height: 8px;
  border-radius: var(--ks-radius-base);
  background: var(--ks-chart-created);
}
i.ready {
  background: var(--ks-chart-success);
}
i.not_ready {
  background: var(--ks-chart-failed);
}
footer {
  display: flex;
  justify-content: center;
  margin-top: var(--ks-spacing-4);
}
footer :deep(button) {
  min-height: 44px;
}
@media (max-width: 768px) {
  .node-resources {
    margin-inline: var(--ks-spacing-4);
  }
}
</style>
