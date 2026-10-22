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
  <KsFormItem label="전달 방식"
    ><KsSelect
      :modelValue="delivery"
      :disabled="disabled"
      @update:model-value="changeDelivery(String($event))"
      ><KsOption value="LATEST" label="최신값" /><KsOption
        value="ALL_OBSERVATIONS"
        label="모든 관측값" /></KsSelect
  ></KsFormItem>
  <p class="native-note">
    {{
      delivery === "ALL_OBSERVATIONS"
        ? "실행 시작 뒤 새로 수락된 관측을 수락 순서대로 한 번씩 처리합니다. 시작 전 관측은 전달하지 않으며(이전 이력 보충 없음), 수집 전 손실까지 보장하지는 않습니다. 보관 한도를 넘으면 실행 오류로 표시됩니다. 검증된 숫자·불리언 센서와 실행 중인 가상 장치 출력만 지원합니다."
        : "1초마다 최신 유효값을 처리합니다."
    }}
    원본 식별 정보는 실행에 고정되며, 입력 변경은 새 실행에 적용됩니다.
  </p>
  <p v-if="unsupported" class="native-note" role="alert">
    문자열 측정 항목은 모든 관측값 전달을 지원하지 않습니다. 최신값을
    선택하세요.
  </p>
  <p v-else-if="current && !current.available" class="native-note">
    이 가상 장치를 시작하고 계산 결과가 들어온 뒤 실행하세요.
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
import {
  deliveryUnsupported,
  selectionKey,
  sourceDelivery,
  withDelivery,
  type WorkflowSourceDelivery,
} from "./workflow-source";
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
const kind = computed(() =>
    props.modelValue.kind === "CONTINUOUS_VD" ? "CONTINUOUS_VD" : "SENSOR",
  ),
  selected = computed(() => selectionKey(props.modelValue)),
  options = computed(
    () =>
      catalog.value?.items.filter((i) => i.selection.kind === kind.value) || [],
  ),
  current = computed(() =>
    options.value.find((i) => selectionKey(i.selection) === selected.value),
  ),
  delivery = computed(() => sourceDelivery(props.modelValue)),
  unsupported = computed(() =>
    deliveryUnsupported(current.value, delivery.value),
  );
function changeKind(kind: string) {
  emit("update:modelValue", withDelivery({ kind }, delivery.value));
}
function choose(key: string) {
  const item = options.value.find((i) => selectionKey(i.selection) === key);
  if (item)
    emit("update:modelValue", withDelivery(item.selection, delivery.value));
}
function changeDelivery(value: string) {
  emit(
    "update:modelValue",
    withDelivery(props.modelValue, value as WorkflowSourceDelivery),
  );
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
