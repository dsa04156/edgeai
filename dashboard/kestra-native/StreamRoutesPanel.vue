<template>
  <details @toggle="toggled">
    <summary>STREAM 경로</summary>
    <div class="native-toolbar">
      <KsButton :disabled="disabled || busy" @click="load(offset)"
        >경로 조회·새로고침</KsButton
      >
      <KsButton v-if="offset > 0" :disabled="disabled || busy" @click="load(0)"
        >최신 경로 보기</KsButton
      >
      <span class="native-note" role="status">
        {{
          offset > 0
            ? "이전 페이지 · 경로 자동 갱신 대기"
            : live
              ? "열린 경로를 3초마다 갱신합니다."
              : "경로 자동 갱신 일시정지"
        }}
        · {{ updatedAt ? `${time(updatedAt)} 조회` : "첫 경로 조회 대기" }}
      </span>
    </div>
    <NativeFeedback :error="error" />
    <NativeLoading :busy="busy" label="스트림 경로를 불러오는 중…" />
    <NativeRecords
      v-if="page"
      title="스트림 경로"
      :rows="page.items"
      :busy="busy || disabled"
      :columns="[
        { key: 'source', label: '원본 / 출력', width: 240 },
        { key: 'target', label: '소비 작업 / 포트', width: 240 },
        { key: 'generation', label: '세대 상태', width: 260 },
        { key: 'maxPayloadBytes', label: '메시지 한도(B)' },
      ]"
      :offset="offset"
      :nextOffset="page.nextOffset"
      @page="load"
      ><template #source="{ row }">
        {{
          row.sourceTaskId
            ? taskName(row.sourceTaskId)
            : row.sourceDeviceId || "원본 미확인"
        }}
        · {{ row.sourcePort }}
        <small v-if="row.sourceMode">{{
          sources[row.sourceMode] || row.sourceMode
        }}</small>
      </template>
      <template #target="{ row }"
        >{{ taskName(row.consumerTaskId) }} · {{ row.consumerPort }}</template
      >
      <template #generation="{ row }">
        {{ row.generation?.state || "작업 배정 대기" }} ·
        {{ row.generation?.number ?? "—" }}
        <p v-if="row.generation?.fenceReason" class="native-note">
          차단 사유: {{ row.generation.fenceReason }}
        </p>
        <details>
          <summary>경로 상세</summary>
          <p class="native-note">
            연결 기한: {{ time(row.generation?.leaseUntil) }}
          </p>
          <p class="native-note">
            원본 세션: {{ row.sourceSessionId || "미지정" }} · epoch
            {{ row.sourceEpoch ?? "—" }}
          </p>
          <p class="native-note">
            소비 실행 시도:
            {{ row.generation?.consumerAttemptId || "배정 대기" }} · epoch
            {{ row.generation?.consumerEpoch ?? "—" }}
          </p>
          <pre class="native-json">{{ pretty(row) }}</pre>
        </details>
      </template>
    </NativeRecords>
  </details>
</template>
<script setup lang="ts">
import { onBeforeUnmount, ref } from "vue";
import { KsButton } from "@kestra-io/design-system";
import { get, pretty, time, usePoll, type Schema } from "./native-api";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
import NativeRecords from "./NativeRecords.vue";
const props = defineProps<{
  runId: string;
  tasks: Schema["Task"][];
  live: boolean;
  disabled?: boolean;
}>();
const page = ref<Schema["StreamRoutePage"] | null>(null),
  offset = ref(0),
  opened = ref(false),
  busy = ref(false),
  pending = ref(false),
  error = ref(""),
  updatedAt = ref("");
let generation = 0;
const sources: Record<string, string> = {
  LIVE: "실제 장치",
  REPLAY: "기록 재생",
  SYNTHETIC: "합성 데이터 · 실장비 검증 아님",
};
const taskName = (id: string) =>
  props.tasks.find((task) => task.id === id)?.key || id;
async function load(value = offset.value, quiet = false) {
  if (props.disabled) return;
  const current = ++generation,
    runId = props.runId;
  pending.value = true;
  busy.value = !quiet;
  try {
    const result = await get<Schema["StreamRoutePage"]>(
      `workflow-runs/${runId}/streams?limit=20&offset=${value}`,
    );
    if (current !== generation || runId !== props.runId) return;
    page.value = result;
    offset.value = value;
    updatedAt.value = new Date().toISOString();
    error.value = "";
  } catch (cause) {
    if (current === generation && runId === props.runId)
      error.value =
        (quiet ? "자동 갱신 실패 · 마지막 경로를 표시합니다. " : "") +
        (cause instanceof Error ? cause.message : "스트림 경로 조회 실패");
  } finally {
    if (current === generation) {
      pending.value = false;
      busy.value = false;
    }
  }
}
function toggled(event: Event) {
  opened.value = (event.target as HTMLDetailsElement).open;
  if (opened.value) void load();
  else {
    ++generation;
    pending.value = false;
    busy.value = false;
  }
}
usePoll(async () => {
  if (
    props.live &&
    opened.value &&
    offset.value === 0 &&
    !pending.value &&
    !props.disabled &&
    !document.hidden
  )
    await load(0, true);
}, 3000);
onBeforeUnmount(() => {
  ++generation;
});
</script>
