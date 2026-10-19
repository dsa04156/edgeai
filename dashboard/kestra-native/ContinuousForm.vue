<template>
  <KsForm
    :id="formId"
    labelPosition="top"
    size="large"
    @submit.prevent="submit"
    class="native-form"
  >
    <NativeFeedback :error="localError || error" />
    <KsFormItem v-if="!expanded" label="조합 종류">
      <KsSelect
        v-model="presetId"
        :disabled="disabled || !enabled"
        aria-label="조합 종류"
        @change="selectPreset"
      >
        <KsOption value="" label="단일 센서" />
        <KsOption
          v-for="item in continuousPresets"
          :key="item.id"
          :value="item.id"
          :label="item.label"
        />
      </KsSelect>
    </KsFormItem>
    <p v-if="preset" class="native-note">
      {{ preset.label }} · {{ preset.expression }} · 결과 단위:
      {{ configuration.outputs[0].unit || "센서 선택 필요" }}
    </p>
    <p v-if="preset && !presetValid" class="native-note">
      각 역할에 맞는 같은 단위의 서로 다른 숫자 센서를 선택하세요.
    </p>
    <p v-if="!enabled" class="native-note">
      연속 처리 기능을 현재 사용할 수 없습니다. 목록을 새로고침해 상태를
      확인하세요.
    </p>
    <h2>입력 센서</h2>
    <p class="native-note">
      센서 측정값을 별칭으로 연결합니다. 이 폼은 가상 장치 한 개를 등록합니다.
    </p>
    <section
      v-for="(source, index) in sources"
      :key="index"
      class="native-panel"
    >
      <div class="native-grid">
        <KsFormItem
          :label="
            preset
              ? `${preset.slots[index].label} 센서`
              : `입력 ${index + 1} 센서`
          "
          ><KsSelect
            :disabled="disabled || !enabled"
            v-model="source.deviceId"
            filterable
            :aria-label="
              preset
                ? `${preset.slots[index].label} 센서`
                : `입력 ${index + 1} 센서`
            "
            @change="selectSource(index)"
            ><KsOption
              v-for="input in sourceChoices(index)"
              :key="`${input.deviceId}/${input.property}`"
              :value="input.deviceId"
              :label="`${input.property}${input.unit ? ` (${input.unit})` : ''} · ${input.deviceId} · ${input.targetNode}`" /><KsOption
              v-if="
                source.deviceId &&
                !sourceChoices(index).some(
                  (d) => d.deviceId === source.deviceId,
                )
              "
              :value="source.deviceId"
              disabled
              :label="`${source.deviceId} · 연결 확인 필요`" /></KsSelect
        ></KsFormItem>
        <KsFormItem v-if="expanded" :label="`입력 ${index + 1} 별칭`"
          ><KsInput
            :disabled="disabled || !enabled"
            v-model="source.sourceKey"
            required
            pattern="[a-z][a-z0-9_]*"
        /></KsFormItem>
        <KsFormItem v-if="expanded" label="유효 시간 (초)"
          ><KsInput
            :disabled="disabled || !enabled"
            v-model="source.maxAgeSeconds"
            type="number"
            required
            min="1"
            max="300"
        /></KsFormItem>
      </div>
      <KsButton
        v-if="expanded"
        :disabled="disabled || sources.length === 1"
        @click="sources.splice(index, 1)"
        >입력 삭제</KsButton
      >
    </section>
    <KsButton
      v-if="expanded"
      :disabled="disabled || !enabled || sources.length >= 16"
      @click="addSource"
      >입력 센서 추가</KsButton
    >
    <h2>계산 방식</h2>
    <section
      v-for="(output, index) in configuration.outputs"
      :key="index"
      class="native-panel"
    >
      <div class="native-grid">
        <KsFormItem label="계산 방식"
          ><KsSelect
            :disabled="disabled || !enabled"
            v-model="output.operation"
            @change="operationChanged(index)"
            ><KsOption
              v-for="(label, value) in operations"
              :disabled="
                (preset && ['threshold', 'filter'].includes(String(value))) ||
                (windowOperation(String(value)) &&
                  booleanExpression(output.expression))
              "
              :key="value"
              :value="value"
              :label="
                preset && value === 'scalar' ? '조합값 그대로' : label
              " /></KsSelect
        ></KsFormItem>
        <KsFormItem v-if="windowOperation(output.operation)" label="표본 수"
          ><KsInput
            :disabled="disabled || !enabled"
            v-model="output.sampleCount"
            type="number"
            min="1"
            max="1024"
            required
        /></KsFormItem>
        <KsFormItem v-if="expanded" :label="`출력 ${index + 1} 이름`"
          ><KsInput
            :disabled="disabled || !enabled"
            :modelValue="output.name"
            @update:model-value="renameOutput(index, String($event))"
            required
            maxlength="63"
            pattern="[a-z][a-z0-9_]*"
        /></KsFormItem>
        <KsFormItem
          v-if="expanded || output.operation === 'threshold'"
          :label="
            output.operation === 'threshold' ? '참/거짓 계산식' : '계산식'
          "
          ><KsInput
            :disabled="disabled || !enabled"
            v-model="output.expression"
            required
            maxlength="1024"
        /></KsFormItem>
        <KsFormItem v-if="expanded" label="단위"
          ><KsInput
            :disabled="disabled || !enabled"
            v-model="output.unit"
            maxlength="32"
        /></KsFormItem>
        <KsFormItem
          v-if="expanded || output.operation === 'filter'"
          label="출력 조건"
          ><KsInput
            :disabled="disabled || !enabled"
            v-model="output.condition"
            :required="output.operation === 'filter'"
            maxlength="1024"
        /></KsFormItem>
      </div>
      <KsButton
        v-if="expanded"
        :disabled="disabled || configuration.outputs.length === 1"
        @click="removeOutput(index)"
        >출력 삭제</KsButton
      >
    </section>
    <KsButton
      v-if="expanded"
      :disabled="disabled || !enabled || configuration.outputs.length >= 16"
      @click="addOutput"
      >출력 항목 추가</KsButton
    >
    <h2>결과 저장</h2>
    <KsFormItem label="이력 저장 정책"
      ><KsSelect
        :modelValue="configuration.resultStorage?.mode || 'ALL'"
        :disabled="disabled"
        @update:model-value="setStorage(String($event))"
        ><KsOption value="ALL" label="모든 결과 저장" /><KsOption
          value="NONE"
          label="이력 저장 안 함" /><KsOption
          value="SELECTED"
          label="선택한 출력만 저장" /></KsSelect
    ></KsFormItem>
    <template v-if="configuration.resultStorage?.mode === 'SELECTED'">
      <section
        v-for="output in configuration.outputs"
        :key="output.name"
        class="native-panel"
      >
        <KsCheckbox
          :disabled="disabled"
          :modelValue="storedOutputs.some((o) => o.name === output.name)"
          @update:model-value="selectStored(output.name, Boolean($event))"
          >{{ output.name }} 저장</KsCheckbox
        >
        <KsFormItem
          v-if="storedOutputs.some((o) => o.name === output.name)"
          label="저장 조건 (선택 · value 기준)"
          ><KsInput
            :disabled="disabled"
            :modelValue="
              storedOutputs.find((o) => o.name === output.name)?.condition || ''
            "
            @update:model-value="storageCondition(output.name, String($event))"
            placeholder="예: value > 10"
        /></KsFormItem>
      </section>
    </template>
    <h2>장치 이름</h2>
    <KsFormItem label="표시 이름"
      ><KsInput
        :disabled="disabled || !enabled"
        v-model="displayName"
        required
        maxlength="128"
        aria-label="연속 처리 장치 이름"
    /></KsFormItem>
    <p class="native-note">
      {{ configuration.intervalSeconds }}초마다 계산 · 최근
      {{ configuration.outputs[0]?.sampleCount || 1 }}개 표본 · 결과
      {{ configuration.retentionDays }}일 보관
    </p>
    <KsButton
      :disabled="disabled || !enabled || !presetValid"
      type="primary"
      nativeType="submit"
      >{{ initial ? "연속 처리 설정 저장" : "연속 처리 장치 등록" }}</KsButton
    >
    <details>
      <summary>고급 설정</summary>
      <div class="native-grid">
        <KsFormItem v-if="!initial" label="장치 키"
          ><KsInput
            :disabled="disabled || !enabled"
            v-model="key"
            required
            maxlength="100"
            pattern="[a-z][a-z0-9]*([._\-][a-z0-9]+)*" /></KsFormItem
        ><KsFormItem label="계산 간격 (초)"
          ><KsInput
            :disabled="disabled || !enabled"
            v-model="configuration.intervalSeconds"
            required
            type="number"
            min="1"
            max="3600" /></KsFormItem
        ><KsFormItem label="결과 보관 기간 (일)"
          ><KsInput
            :disabled="disabled || !enabled"
            v-model="configuration.retentionDays"
            required
            type="number"
            min="1"
            max="30"
        /></KsFormItem>
      </div>
      <KsButton :disabled="disabled" @click="toggleExpanded">{{
        expanded ? "간편 설정으로 보기" : "여러 센서·출력·계산식 전체 설정"
      }}</KsButton>
    </details>
  </KsForm>
</template>
<script setup lang="ts">
import { computed, ref, watch } from "vue";
import {
  KsForm,
  KsFormItem,
  KsInput,
  KsSelect,
  KsOption,
  KsButton,
  KsCheckbox,
} from "@kestra-io/design-system";
import { requestKey, type Schema } from "./native-api";
import NativeFeedback from "./NativeFeedback.vue";
import {
  continuousPresets,
  presetChoices,
  presetReady,
  presetUnit,
} from "../app/virtual-devices/continuous-presets";
const props = defineProps<{
  inputs: Schema["ContinuousSensor"][];
  enabled: boolean;
  disabled?: boolean;
  error?: string;
  formId: string;
  initial?: {
    displayName: string;
    sources: Schema["VirtualDeviceSources"];
    continuous: Schema["ContinuousConfiguration"];
  };
}>();
const emit = defineEmits<{ save: [value: Schema["ContinuousCreate"]] }>();
const localError = ref("");
const presetId = ref("");
const preset = computed(() =>
  continuousPresets.find((p) => p.id === presetId.value),
);
const key = ref("vd-" + requestKey()),
  displayName = ref(props.initial?.displayName || ""),
  expanded = ref(!!props.initial);
const sources = ref<Schema["VirtualDeviceSources"]>(
  props.initial
    ? JSON.parse(JSON.stringify(props.initial.sources))
    : [{ sourceKey: "a", deviceId: "", properties: [], maxAgeSeconds: 30 }],
);
const configuration = ref<Schema["ContinuousConfiguration"]>(
  props.initial
    ? JSON.parse(JSON.stringify(props.initial.continuous))
    : {
        intervalSeconds: 1,
        retentionDays: 7,
        outputs: [
          { name: "value", expression: "a", operation: "scalar", unit: "" },
        ],
      },
);
const presetValid = computed(
  () => !preset.value || presetReady(preset.value, props.inputs, sources.value),
);
function sourceChoices(index: number) {
  return preset.value
    ? presetChoices(preset.value, index, props.inputs, sources.value)
    : props.inputs;
}
function selectPreset(value: string) {
  const previousName = continuousPresets.find(
    (p) => p.label === displayName.value,
  )?.label;
  const selected = continuousPresets.find((p) => p.id === value);
  if (!displayName.value || previousName)
    displayName.value = selected?.label || "";
  sources.value = Array.from(
    { length: selected?.slots.length || 1 },
    (_, i) => ({
      sourceKey: String.fromCharCode(97 + i),
      deviceId: "",
      properties: [],
      maxAgeSeconds: 30,
    }),
  );
  const output = configuration.value.outputs[0];
  configuration.value.outputs = [
    {
      name: output.name,
      operation: "scalar",
      expression: selected?.expression || "a",
      unit: "",
    },
  ];
  if (configuration.value.resultStorage?.mode === "SELECTED")
    configuration.value.resultStorage.outputs =
      configuration.value.resultStorage.outputs.filter(
        (o) => o.name === output.name,
      );
  localError.value = "";
}
function toggleExpanded() {
  expanded.value = !expanded.value;
  if (expanded.value) presetId.value = "";
}
watch(
  [preset, () => props.inputs, sources],
  () => {
    if (!preset.value) return;
    configuration.value.outputs[0].expression = preset.value.expression;
    configuration.value.outputs[0].unit = presetUnit(
      preset.value,
      props.inputs,
      sources.value,
    );
  },
  { deep: true },
);
const storedOutputs = computed(() =>
  configuration.value.resultStorage?.mode === "SELECTED"
    ? configuration.value.resultStorage.outputs
    : [],
);
function renameOutput(index: number, name: string) {
  if (
    !name.trim() ||
    configuration.value.outputs.some(
      (output, other) => other !== index && output.name === name,
    )
  ) {
    localError.value = "출력 이름은 비어 있거나 다른 출력과 같을 수 없습니다.";
    return;
  }
  localError.value = "";
  const oldName = configuration.value.outputs[index].name;
  configuration.value.outputs[index].name = name;
  if (configuration.value.resultStorage?.mode === "SELECTED")
    configuration.value.resultStorage.outputs =
      configuration.value.resultStorage.outputs.map((output) =>
        output.name === oldName ? { ...output, name } : output,
      );
}
function removeOutput(index: number) {
  const [removed] = configuration.value.outputs.splice(index, 1);
  if (configuration.value.resultStorage?.mode === "SELECTED")
    configuration.value.resultStorage.outputs =
      configuration.value.resultStorage.outputs.filter(
        (output) => output.name !== removed.name,
      );
}
function setStorage(mode: string) {
  configuration.value.resultStorage =
    mode === "SELECTED"
      ? {
          mode,
          outputs: configuration.value.outputs.map((o) => ({ name: o.name })),
        }
      : { mode: mode === "NONE" ? "NONE" : "ALL" };
}
function selectStored(name: string, selected: boolean) {
  configuration.value.resultStorage = {
    mode: "SELECTED",
    outputs: selected
      ? [...storedOutputs.value, { name }]
      : storedOutputs.value.filter((o) => o.name !== name),
  };
}
function storageCondition(name: string, condition: string) {
  configuration.value.resultStorage = {
    mode: "SELECTED",
    outputs: storedOutputs.value.map((o) =>
      o.name === name ? { name, ...(condition ? { condition } : {}) } : o,
    ),
  };
}
const operations = {
  scalar: "측정값",
  movingAverage: "이동 평균",
  minimum: "최솟값",
  maximum: "최댓값",
  threshold: "기준값 비교",
  filter: "조건 필터",
};
// Simple aliases have known types; the server validates compound expressions per output.
function booleanExpression(expression: string) {
  const source = sources.value.find(
    (source) => source.sourceKey === expression.trim(),
  );
  return (
    !!source &&
    props.inputs.some(
      (input) => input.deviceId === source.deviceId && input.type === "bool",
    )
  );
}
const windowOperation = (op: string) =>
  ["movingAverage", "minimum", "maximum"].includes(op);
function selectSource(index: number) {
  const source = sources.value[index],
    sensor = props.inputs.find((d) => d.deviceId === source.deviceId);
  source.properties = sensor ? [sensor.property] : [];
  if (index === 0 && !expanded.value && !preset.value) {
    configuration.value.outputs[0].unit = sensor?.unit || "";
    configuration.value.outputs[0].expression =
      configuration.value.outputs[0].operation === "threshold" ? "a > 0" : "a";
  }
}
function operationChanged(index: number) {
  const row = configuration.value.outputs[index];
  if (windowOperation(row.operation)) row.sampleCount = row.sampleCount || 5;
  else delete row.sampleCount;
  if (preset.value) row.expression = preset.value.expression;
  else if (!expanded.value)
    row.expression = row.operation === "threshold" ? "a > 0" : "a";
  if (row.operation === "filter") row.condition = row.condition || "a > 0";
  else if (!row.condition) delete row.condition;
}
function addSource() {
  let n = 1;
  while (sources.value.some((s) => s.sourceKey === `input${n}`)) n++;
  sources.value.push({
    sourceKey: `input${n}`,
    deviceId: "",
    properties: [],
    maxAgeSeconds: 30,
  });
}
function addOutput() {
  let n = 1;
  while (configuration.value.outputs.some((o) => o.name === `value${n}`)) n++;
  configuration.value.outputs.push({
    name: `value${n}`,
    expression: sources.value[0].sourceKey,
    operation: "scalar",
    unit: "",
  });
}
function submit() {
  if (props.disabled || !props.enabled) return;
  localError.value = "";
  if (!presetValid.value) {
    localError.value =
      "조합에 맞는 같은 단위의 서로 다른 센서를 모두 선택하세요.";
    return;
  }
  if (
    configuration.value.resultStorage?.mode === "SELECTED" &&
    !configuration.value.resultStorage.outputs.length
  ) {
    localError.value = "저장할 출력을 선택하거나 이력 저장 안 함을 선택하세요.";
    return;
  }
  if (
    configuration.value.outputs.some(
      (output) =>
        windowOperation(output.operation) &&
        booleanExpression(output.expression),
    )
  ) {
    localError.value =
      "bool 센서에는 평균·최솟값·최댓값 계산을 사용할 수 없습니다.";
    return;
  }
  const body: Schema["ContinuousCreate"] = {
    key: key.value,
    displayName: displayName.value,
    sources: sources.value.map((s) => ({
      ...s,
      maxAgeSeconds: Number(s.maxAgeSeconds),
    })),
    continuous: {
      ...configuration.value,
      intervalSeconds: Number(configuration.value.intervalSeconds),
      retentionDays: Number(configuration.value.retentionDays),
      outputs: configuration.value.outputs.map((o) => ({
        ...o,
        ...(windowOperation(o.operation)
          ? { sampleCount: Number(o.sampleCount) }
          : {}),
      })),
    },
  };
  if (body.sources.some((s) => !s.deviceId || !s.properties?.length)) {
    localError.value = "입력 센서를 모두 선택하세요.";
    return;
  }
  localError.value = "";
  emit("save", body);
}
</script>
