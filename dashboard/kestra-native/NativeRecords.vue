<template>
  <KsDataTable
    :data="rows"
    :loading="busy"
    :total="0"
    :pageSize="Math.max(rows.length, 1)"
    rowKey="id"
    tableLayout="fixed"
    :noDataText="busy ? '불러오는 중…' : '표시할 항목이 없습니다.'"
    noDataDescription=""
  >
    <template #top
      ><div class="native-toolbar">
        <h2>{{ title }}</h2>
        <slot name="tools" /></div
    ></template>
    <KsTableColumn
      v-for="column in columns"
      :key="column.key"
      :label="column.label"
      :minWidth="column.width || 150"
    >
      <template #default="{ row }"
        ><slot :name="column.key" :row="row">{{
          row[column.key] ?? "—"
        }}</slot></template
      >
    </KsTableColumn>
    <KsTableColumn v-if="$slots.actions" label="관리" :minWidth="240"
      ><template #default="{ row }"
        ><div class="native-toolbar">
          <slot name="actions" :row="row" /></div></template
    ></KsTableColumn>
  </KsDataTable>
  <div v-if="offset !== undefined" class="native-toolbar">
    <KsButton
      :disabled="busy || offset === 0"
      @click="$emit('page', Math.max(0, offset - (pageSize || 20)))"
      >이전</KsButton
    ><span>{{ Math.floor(offset / (pageSize || 20)) + 1 }} 페이지</span
    ><KsButton
      :disabled="busy || nextOffset == null"
      @click="$emit('page', nextOffset!)"
      >다음</KsButton
    >
  </div>
</template>
<script setup lang="ts">
import { KsDataTable, KsTableColumn, KsButton } from "@kestra-io/design-system";
defineProps<{
  title: string;
  rows: object[];
  columns: { key: string; label: string; width?: number }[];
  busy?: boolean;
  offset?: number;
  nextOffset?: number | null;
  pageSize?: number;
}>();
defineEmits<{ page: [offset: number] }>();
</script>
