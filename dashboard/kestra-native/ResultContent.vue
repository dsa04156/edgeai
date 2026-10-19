<template>
  <div>
    <KsButton :disabled="busy || !supported" @click="load"
      >{{ port }} · 결과 내용 조회</KsButton
    >
    <p v-if="!supported" class="native-note">
      내용 조회는 1 MiB 이하 JSON 파일을 지원합니다.
    </p>
    <NativeFeedback :error="error" />
    <p v-if="busy" role="status">결과 파일을 검증하는 중…</p>
    <template v-if="loaded"
      ><section v-for="(output, name) in processingOutputs" :key="name">
        <NativeRecords
          :title="`${String(name)} · 처리 결과`"
          :rows="samples(output)"
          :columns="[
            { key: 'index', label: '표본' },
            { key: 'value', label: '값' },
            { key: 'observedAt', label: '관측 시각', width: 220 },
            { key: 'frequency', label: '주파수 (Hz)' },
          ]"
        />
        <p
          v-if="
            Array.isArray(record(output).values) &&
            (record(output).values as unknown[]).length > 100
          "
          class="native-note"
        >
          처음 100개 표본을 표시합니다. 전체 값은 JSON에서 확인하세요.
        </p>
      </section>
      <details>
        <summary>{{ port }} · 결과 JSON</summary>
        <pre class="native-json">{{ pretty(value) }}</pre>
      </details></template
    >
  </div>
</template>
<script setup lang="ts">
import { computed, ref } from "vue";
import { parse, isLosslessNumber } from "lossless-json";
import { KsButton } from "@kestra-io/design-system";
import { request, pretty, useAction } from "./native-api";
import { record } from "../lib/json-record";
import NativeFeedback from "./NativeFeedback.vue";
import NativeRecords from "./NativeRecords.vue";
const props = defineProps<{
  taskId: string;
  port: string;
  supported: boolean;
}>();
const { busy, error, act } = useAction();
const value = ref<unknown>(),
  loaded = ref(false),
  processingOutputs = computed(() =>
    record(value.value).apiVersion === "edgeai.processing-output/v1"
      ? record(record(value.value).outputs)
      : {},
  );
async function load() {
  await act(async () => {
    value.value = parse(
      await (
        await request(
          `tasks/${props.taskId}/results/${encodeURIComponent(props.port)}/content`,
        )
      ).text(),
    );
    loaded.value = true;
  });
}
const display = (v: unknown) =>
  isLosslessNumber(v)
    ? v.value
    : typeof v === "boolean"
      ? v
        ? "참"
        : "거짓"
      : String(v ?? "—");
function samples(raw: unknown) {
  const output = record(raw),
    values = Array.isArray(output.values) ? output.values : [],
    times = Array.isArray(output.observedAt) ? output.observedAt : [],
    frequencies = Array.isArray(output.frequenciesHz)
      ? output.frequenciesHz
      : [];
  return values.slice(0, 100).map((v, index) => ({
    id: String(index),
    index: index + 1,
    value: display(v),
    observedAt: display(times[index]),
    frequency: display(frequencies[index]),
  }));
}
</script>
