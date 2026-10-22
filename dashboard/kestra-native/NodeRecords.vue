<template>
  <section class="native-panel">
    <NativeFeedback :error="error" /><NativeRecords
      title="실행 노드 등록 기록"
      :rows="items"
      :columns="[
        { key: 'name', label: '노드' },
        { key: 'status', label: '등록 상태' },
        { key: 'architecture', label: '아키텍처' },
        { key: 'lastHeartbeat', label: '최근 heartbeat' },
      ]"
      :offset="offset"
      :nextOffset="nextOffset"
      :busy="busy"
      @page="(value) => act(() => load(value))"
      ><template #tools
        ><KsButton :disabled="busy" @click="act(() => load(offset))"
          >기록 새로고침</KsButton
        ></template
      ><template #name="{ row }"
        ><KsButton link @click="detail = row">{{
          row.name
        }}</KsButton></template
      ><template #lastHeartbeat="{ row }">{{
        time(row.lastHeartbeat)
      }}</template></NativeRecords
    >
    <details v-if="detail" open>
      <summary>{{ detail.name }} 등록 상세</summary>
      <pre class="native-json">{{ pretty(detail) }}</pre>
    </details>
    <p class="native-note">
      등록 이력이며 상단의 현재 Kubernetes 관측 목록과 다를 수 있습니다.
    </p>
  </section>
</template>
<script setup lang="ts">
import { onMounted, ref } from "vue";
import { KsButton } from "@kestra-io/design-system";
import { get, pretty, time, useAction, type Schema } from "./native-api";
import NativeRecords from "./NativeRecords.vue";
import NativeFeedback from "./NativeFeedback.vue";
const { busy, error, act } = useAction();
const items = ref<Schema["ExecutionNode"][]>([]),
  detail = ref<Schema["ExecutionNode"] | null>(null),
  offset = ref(0),
  nextOffset = ref<number | null>(null);
async function load(value: number) {
  const page = await get<{
    items: Schema["ExecutionNode"][];
    nextOffset: number | null;
  }>(`nodes?limit=20&offset=${value}`);
  items.value = page.items;
  offset.value = value;
  nextOffset.value = page.nextOffset;
}
onMounted(() => void act(() => load(0)));
</script>
