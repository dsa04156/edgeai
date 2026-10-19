<template>
  <NativeFeedback :error="error" />
  <KsFormItem label="입력 종류"
    ><KsSelect
      :modelValue="kind"
      :disabled="disabled"
      @update:model-value="changeKind(String($event))"
      ><KsOption value="SENSOR" label="센서 값" /><KsOption
        value="CONTINUOUS_VD"
        label="가상 장치 계산 결과" /></KsSelect
  ></KsFormItem>
  <KsFormItem label="입력 데이터"
    ><KsSelect
      :modelValue="selected"
      :disabled="disabled || busy"
      filterable
      @update:model-value="choose(String($event))"
      ><KsOption
        v-for="item in options"
        :key="selectionKey(item.selection)"
        :value="selectionKey(item.selection)"
        :label="`${item.label}${item.unit ? ' · ' + item.unit : ''}${item.available ? '' : ' · 계산 결과 대기'}`" /><KsOption
        v-if="
          selected &&
          !options.some((i) => selectionKey(i.selection) === selected)
        "
        :value="selected"
        label="선택한 원본 · 목록 확인 필요" /></KsSelect
  ></KsFormItem>
  <p class="native-note">
    1초마다 최신 유효값을 처리합니다. 원본 식별 정보는 실행에 고정되며, 입력
    변경은 새 실행에 적용됩니다.
  </p>
  <p v-if="catalog?.truncated">입력 목록이 일부만 표시됩니다.</p>
  <KsButton :disabled="busy" @click="load">입력 목록 새로고침</KsButton>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  KsButton,
  KsFormItem,
  KsOption,
  KsSelect,
} from "@kestra-io/design-system";
import { get, type Schema } from "./native-api";
import NativeFeedback from "./NativeFeedback.vue";
const props = defineProps<{
    modelValue: Record<string, unknown>;
    disabled?: boolean;
  }>(),
  emit = defineEmits<{
    "update:modelValue": [value: Record<string, unknown>];
  }>();
const catalog = ref<Schema["WorkflowDataSources"] | null>(null),
  busy = ref(false),
  error = ref("");
function selectionKey(value: Record<string, unknown>) {
  return value.kind === "SENSOR" && value.deviceId && value.property
    ? `SENSOR:${value.deviceId}:${value.property}`
    : value.kind === "CONTINUOUS_VD" && value.vdId && value.outputId
      ? `CONTINUOUS_VD:${value.vdId}:${value.outputId}`
      : "";
}
const kind = computed(() =>
    props.modelValue.kind === "CONTINUOUS_VD" ? "CONTINUOUS_VD" : "SENSOR",
  ),
  selected = computed(() => selectionKey(props.modelValue)),
  options = computed(
    () =>
      catalog.value?.items.filter((i) => i.selection.kind === kind.value) || [],
  );
function changeKind(kind: string) {
  emit("update:modelValue", { kind });
}
function choose(key: string) {
  const item = options.value.find((i) => selectionKey(i.selection) === key);
  if (item) emit("update:modelValue", item.selection);
}
async function load() {
  busy.value = true;
  error.value = "";
  try {
    catalog.value = await get<Schema["WorkflowDataSources"]>(
      "workflow-data-sources",
    );
  } catch (cause) {
    error.value =
      cause instanceof Error ? cause.message : "입력 목록 조회 실패";
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>
