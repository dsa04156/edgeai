<template>
  <section>
    <h2>공통 데이터 처리</h2>
    <p class="native-note">
      단계를 위에서 아래로 실행합니다. 센서 값은 이미 배율·보정값이 적용된
      값입니다.
    </p>
    <div class="native-grid">
      <KsFormItem label="처리 표본 수"
        ><KsInput
          :disabled="disabled"
          type="number"
          min="1"
          max="1024"
          required
          :modelValue="String(config.sampleCount)"
          @update:model-value="
            change({ sampleCount: Number($event) })
          " /></KsFormItem
      ><KsFormItem label="처리 출력 포트"
        ><KsInput
          :disabled="disabled"
          required
          :modelValue="String(config.outputPort)"
          @update:model-value="change({ outputPort: $event })"
      /></KsFormItem>
    </div>
    <div
      v-for="(pipeline, index) in pipelines"
      :key="index"
      class="native-panel"
    >
      <h3>처리 {{ index + 1 }}</h3>
      <div class="native-grid">
        <KsFormItem label="결과 이름"
          ><KsInput
            :disabled="disabled"
            required
            :modelValue="String(pipeline.name)"
            @update:model-value="
              setPipeline(index, { name: $event })
            " /></KsFormItem
        ><KsFormItem label="입력 종류"
          ><KsSelect
            :disabled="disabled"
            :modelValue="
              'port' in record(pipeline.input) ? 'artifact' : 'sensor'
            "
            @update:model-value="
              setPipeline(index, {
                input:
                  $event === 'artifact'
                    ? { port: 'input', output: 'signal' }
                    : {
                        sourceKey: sourceKeys[0] || '',
                        property: sensorProperty || '',
                      },
              })
            "
            ><KsOption value="sensor" label="원본 센서 측정값" /><KsOption
              value="artifact"
              label="다른 VD의 처리 결과" /></KsSelect
        ></KsFormItem>
      </div>
      <div class="native-grid" v-if="'port' in record(pipeline.input)">
        <KsFormItem
          v-for="key in ['port', 'output']"
          :key="key"
          :label="key === 'port' ? '입력 포트' : '원본 결과 이름'"
          ><KsInput
            :disabled="disabled"
            required
            :modelValue="String(record(pipeline.input)[key] || '')"
            @update:model-value="
              setPipeline(index, {
                input: { ...record(pipeline.input), [key]: $event },
              })
            "
        /></KsFormItem>
      </div>
      <div v-else class="native-grid">
        <KsFormItem label="원본 슬롯"
          ><KsSelect
            :disabled="disabled"
            :modelValue="record(pipeline.input).sourceKey"
            @update:model-value="
              setPipeline(index, {
                input: { ...record(pipeline.input), sourceKey: $event },
              })
            "
            ><KsOption
              v-for="key in sourceKeys"
              :key="key"
              :value="key"
              :label="key" /></KsSelect></KsFormItem
        ><KsFormItem label="측정 항목"
          ><KsSelect
            :disabled="disabled || !!sensorProperty"
            :modelValue="sensorProperty || record(pipeline.input).property"
            @update:model-value="
              setPipeline(index, {
                input: { ...record(pipeline.input), property: $event },
              })
            "
            ><KsOption
              v-for="name in properties"
              :key="name"
              :value="name"
              :label="name" /></KsSelect
        ></KsFormItem>
      </div>
      <div
        v-for="(step, position) in steps(pipeline)"
        :key="position"
        class="native-panel"
      >
        <h3>단계 {{ position + 1 }}</h3>
        <KsFormItem label="기능"
          ><KsSelect
            :disabled="disabled"
            :modelValue="String(step.op)"
            @update:model-value="
              setStep(index, position, newProcessingStep(String($event)), true)
            "
            ><KsOption
              v-for="(label, op) in processingNames"
              :key="op"
              :value="op"
              :label="label" /></KsSelect
        ></KsFormItem>
        <KsFormItem v-if="step.op === 'filter'" label="필터 종류"
          ><KsSelect
            :disabled="disabled"
            :modelValue="step.mode"
            @update:model-value="setStep(index, position, { mode: $event })"
            ><KsOption value="lowPass" label="저역 통과" /><KsOption
              value="highPass"
              label="고역 통과" /></KsSelect
        ></KsFormItem>
        <KsFormItem v-if="step.op === 'threshold'" label="비교"
          ><KsSelect
            :disabled="disabled"
            :modelValue="step.operator"
            @update:model-value="setStep(index, position, { operator: $event })"
            ><KsOption
              v-for="(label, key) in {
                gt: '>',
                gte: '≥',
                lt: '<',
                lte: '≤',
                eq: '=',
              }"
              :key="key"
              :value="key"
              :label="label" /></KsSelect
        ></KsFormItem>
        <KsFormItem
          :label="
            step.op === 'filter'
              ? '필터 계수'
              : step.op === 'fft'
                ? '표본 주파수 (Hz)'
                : step.op === 'threshold'
                  ? '기준값'
                  : '창 크기'
          "
          ><KsInput
            :disabled="disabled"
            type="number"
            required
            step="any"
            :modelValue="String(step[numberKey(step)] ?? '')"
            @update:model-value="
              setStep(index, position, { [numberKey(step)]: Number($event) })
            "
        /></KsFormItem>
        <div class="native-toolbar">
          <KsButton
            :disabled="disabled || position === 0"
            @click="move(index, position, -1)"
            >위로</KsButton
          ><KsButton
            :disabled="disabled || position === steps(pipeline).length - 1"
            @click="move(index, position, 1)"
            >아래로</KsButton
          ><KsButton
            :disabled="disabled || steps(pipeline).length === 1"
            @click="
              setPipeline(index, {
                steps: steps(pipeline).filter((_, n) => n !== position),
              })
            "
            >단계 삭제</KsButton
          >
        </div>
      </div>
      <div class="native-toolbar">
        <KsButton
          :disabled="disabled || steps(pipeline).length >= 16"
          @click="
            setPipeline(index, {
              steps: [...steps(pipeline), newProcessingStep('threshold')],
            })
          "
          >단계 추가</KsButton
        ><KsButton
          :disabled="disabled || pipelines.length === 1"
          @click="
            change({ pipelines: pipelines.filter((_, n) => n !== index) })
          "
          >처리 삭제</KsButton
        >
      </div>
    </div>
    <KsButton
      :disabled="disabled || pipelines.length >= 16"
      @click="addPipeline"
      >처리 파이프라인 추가</KsButton
    >
  </section>
</template>
<script setup lang="ts">
import { computed } from "vue";
import {
  KsButton,
  KsFormItem,
  KsInput,
  KsSelect,
  KsOption,
} from "@kestra-io/design-system";
import { record } from "../lib/json-record";
import { newProcessingStep, processingNames } from "../lib/processing";
const props = defineProps<{
  modelValue: Record<string, unknown>;
  sourceKeys: string[];
  properties: string[];
  sensorProperty?: string;
  disabled?: boolean;
}>();
const emit = defineEmits<{
  "update:modelValue": [value: Record<string, unknown>];
}>();
const config = computed(() => props.modelValue),
  pipelines = computed(() =>
    Array.isArray(config.value.pipelines)
      ? config.value.pipelines.map(record)
      : [],
  );
function change(patch: Record<string, unknown>) {
  emit("update:modelValue", { ...config.value, ...patch });
}
function steps(pipeline: Record<string, unknown>) {
  return Array.isArray(pipeline.steps) ? pipeline.steps.map(record) : [];
}
function setPipeline(index: number, patch: Record<string, unknown>) {
  change({
    pipelines: pipelines.value.map((row, n) =>
      n === index ? { ...row, ...patch } : row,
    ),
  });
}
function setStep(
  index: number,
  position: number,
  patch: Record<string, unknown>,
  replace = false,
) {
  setPipeline(index, {
    steps: steps(pipelines.value[index]).map((row, n) =>
      n === position ? (replace ? patch : { ...row, ...patch }) : row,
    ),
  });
}
function numberKey(step: Record<string, unknown>) {
  return step.op === "filter"
    ? "alpha"
    : step.op === "fft"
      ? "sampleRateHz"
      : step.op === "threshold"
        ? "value"
        : "window";
}
function move(index: number, position: number, delta: number) {
  const next = steps(pipelines.value[index]);
  [next[position], next[position + delta]] = [
    next[position + delta],
    next[position],
  ];
  setPipeline(index, { steps: next });
}
function addPipeline() {
  let n = 1;
  while (pipelines.value.some((p) => p.name === `signal${n}`)) n++;
  change({
    pipelines: [
      ...pipelines.value,
      {
        name: `signal${n}`,
        input: {
          sourceKey: props.sourceKeys[0] || "",
          property: props.sensorProperty || "",
        },
        steps: [newProcessingStep("threshold")],
      },
    ],
  });
}
</script>
