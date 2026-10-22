<template>
  <div v-if="spec">
    <template v-if="kind === 'SERVICE'">
      <KsFormItem label="실행 이미지"
        ><KsInput
          :disabled="disabled"
          :modelValue="String(spec.image || '')"
          @update:model-value="patch({ image: $event })"
          placeholder="registry/image@sha256:…"
          required
      /></KsFormItem>
      <div class="native-grid">
        <KsFormItem label="실행 명령 · 한 줄에 인자 하나"
          ><KsInput
            :disabled="disabled"
            type="textarea"
            :rows="3"
            :modelValue="list(spec.command).join('\n')"
            @update:model-value="
              patch({ command: String($event).split('\n') })
            " /></KsFormItem
        ><KsFormItem label="추가 인자 · 한 줄에 하나"
          ><KsInput
            :disabled="disabled"
            type="textarea"
            :rows="3"
            :modelValue="list(spec.args).join('\n')"
            @update:model-value="
              patch({ args: $event ? String($event).split('\n') : [] })
            "
        /></KsFormItem>
      </div>
      <div class="native-grid">
        <KsFormItem label="제한 시간 (초)"
          ><KsInput
            :disabled="disabled"
            type="number"
            min="1"
            max="86400"
            required
            :modelValue="String(spec.timeoutSeconds || 120)"
            @update:model-value="
              patch({ timeoutSeconds: Number($event) })
            " /></KsFormItem
        ><KsFormItem label="QoS"
          ><KsSelect
            :disabled="disabled"
            :modelValue="spec.qos"
            @update:model-value="patch({ qos: $event })"
            ><KsOption
              v-for="q in ['Burstable', 'Guaranteed']"
              :key="q"
              :label="q"
              :value="q" /></KsSelect
        ></KsFormItem>
      </div>
      <h2>CPU·메모리 요구사항</h2>
      <div
        v-for="scope in ['requests', 'limits']"
        :key="scope"
        class="native-grid"
      >
        <KsFormItem
          v-for="resource in ['cpu', 'memory']"
          :key="resource"
          :label="`${scope} · ${resource}`"
          ><KsInput
            :disabled="disabled"
            :modelValue="
              String(record(record(spec.resources)[scope])[resource] || '')
            "
            @update:model-value="
              patch({
                resources: {
                  ...record(spec.resources),
                  [scope]: {
                    ...record(record(spec.resources)[scope]),
                    [resource]: $event,
                  },
                },
              })
            "
        /></KsFormItem>
      </div>
      <h2>관측 장비로 실행 요구사항 설정</h2>
      <div class="native-grid">
        <KsFormItem label="실행 노드"
          ><KsSelect :disabled="disabled" v-model="nodeId"
            ><KsOption
              v-for="node in nodes"
              :key="node.id"
              :value="node.id"
              :label="`${node.name} · ${node.architecture}`"
              :disabled="node.status !== 'READY'" /></KsSelect></KsFormItem
        ><KsFormItem label="요청할 GPU·NPU"
          ><KsSelect :disabled="disabled || !node" v-model="resource"
            ><KsOption value="keep" label="기존 자원 요청 유지" /><KsOption
              v-for="[key, count] in node ? accelerators(node) : []"
              :key="key"
              :value="key"
              :label="`${acceleratorNames[key] || key} · 총 ${count}`" /></KsSelect></KsFormItem
        ><KsFormItem label="요청 수량"
          ><KsInput
            :disabled="disabled || resource === 'keep'"
            v-model="quantity"
            type="number"
            min="1"
            :max="
              node && resource !== 'keep'
                ? Number(node.allocatable[resource])
                : 1
            " /></KsFormItem
        ><KsCheckbox :disabled="disabled" v-model="pin"
          >이 노드로 실행 위치 제한</KsCheckbox
        >
      </div>
      <KsButton :disabled="disabled || !node" @click="hardware"
        >실행 요구사항 반영</KsButton
      >
      <p class="native-note">
        할당 가능 총량이며 현재 여유량은 아닙니다. 실제 배치는 스케줄러가
        결정합니다.
      </p>
      <section v-for="direction in ['inputs', 'outputs']" :key="direction">
        <h2>{{ direction === "inputs" ? "BATCH 입력" : "BATCH 출력" }} 포트</h2>
        <div
          v-for="(port, key) in record(spec[direction])"
          :key="key"
          class="native-grid"
        >
          <KsFormItem :label="`${key} · 형식`"
            ><KsInput
              :disabled="disabled"
              :modelValue="String(record(port).mediaType || '')"
              @update:model-value="
                patch({
                  [direction]: {
                    ...record(spec[direction]),
                    [key]: { ...record(port), mediaType: $event },
                  },
                })
              "
          /></KsFormItem>
          <div class="native-toolbar">
            <KsInput
              :disabled="disabled"
              type="number"
              aria-label="최대 바이트"
              :modelValue="String(record(port).maxBytes || 1048576)"
              @update:model-value="
                patch({
                  [direction]: {
                    ...record(spec[direction]),
                    [key]: { ...record(port), maxBytes: Number($event) },
                  },
                })
              "
            /><KsButton
              :disabled="disabled"
              @click="
                patch({
                  [direction]: Object.fromEntries(
                    Object.entries(record(spec[direction])).filter(
                      ([name]) => name !== key,
                    ),
                  ),
                })
              "
              >{{ key }} 삭제</KsButton
            >
          </div>
        </div>
        <KsButton :disabled="disabled" @click="addPort(direction)"
          >포트 추가</KsButton
        >
      </section>
      <KsFormItem label="데이터 입력 모듈"
        ><KsCheckbox
          :disabled="disabled"
          :modelValue="record(spec.stream).source === 'WORKFLOW_DATA'"
          @update:model-value="setSource(Boolean($event))"
          >센서·가상 장치의 최신 유효값을 STREAM으로 전달</KsCheckbox
        ></KsFormItem
      >
      <p
        v-if="record(spec.stream).source === 'WORKFLOW_DATA'"
        class="native-note"
      >
        입력 데이터는 워크플로 작업 설정에서 선택합니다. STREAM 출력 sample를
        처리 작업의 입력에 연결하세요. 전체 STREAM·체크포인트 규격은 아래
        JSON에서 편집할 수 있습니다.
      </p>
    </template>
  </div>
</template>
<script setup lang="ts">
import { computed, ref } from "vue";
import {
  KsButton,
  KsCheckbox,
  KsFormItem,
  KsInput,
  KsSelect,
  KsOption,
} from "@kestra-io/design-system";
import { objectJson, pretty, type Schema } from "./native-api";
import { record } from "../lib/json-record";
import { accelerators, acceleratorNames } from "../lib/hardware";
const props = defineProps<{
  modelValue: string;
  kind: "SERVICE";
  nodes: Schema["ExecutionNode"][];
  disabled?: boolean;
}>();
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
const spec = computed(() => {
  try {
    return objectJson(props.modelValue);
  } catch {
    return null;
  }
});
const nodeId = ref(""),
  resource = ref("keep"),
  quantity = ref(1),
  pin = ref(false);
const node = computed(() => props.nodes.find((n) => n.id === nodeId.value));
function list(value: unknown) {
  return Array.isArray(value) ? value.map(String) : [];
}
function setSource(enabled: boolean) {
  if (!spec.value) return;
  const stream = {
    command: ["edgeai-workflow-input"],
    args: [],
    inputs: {},
    outputs: {
      sample: { mediaType: "application/json", maxPayloadBytes: 4096 },
    },
    stepTimeoutSeconds: 60,
    limits: { maxFrames: 128, maxBufferBytes: 16777216, maxStateBytes: 262144 },
    ...record(spec.value.stream),
  };
  if (enabled)
    patch({
      recovery: { mode: "CHECKPOINT" },
      stream: { ...stream, source: "WORKFLOW_DATA" },
    });
  else {
    const value: Record<string, unknown> = { ...stream };
    delete value.source;
    patch({ stream: value });
  }
}
function patch(value: Record<string, unknown>) {
  emit("update:modelValue", pretty({ ...spec.value, ...value }));
}
function addPort(direction: string) {
  const ports = record(spec.value?.[direction]);
  let n = 1;
  while (`port${n}` in ports) n++;
  patch({
    [direction]: {
      ...ports,
      [`port${n}`]: {
        mediaType: "application/json",
        maxBytes: 1048576,
        ...(direction === "inputs" ? { required: true } : {}),
      },
    },
  });
}
function hardware() {
  if (!node.value || !spec.value) return;
  const key = resource.value,
    count = Number(quantity.value);
  if (
    key !== "keep" &&
    (!Number.isInteger(count) ||
      count < 1 ||
      count > Number(node.value.allocatable[key] || 0))
  )
    return;
  const resources = record(spec.value.resources),
    selector: Record<string, unknown> = {
      ...record(spec.value.nodeSelector),
      "kubernetes.io/arch": node.value.architecture,
    };
  if (pin.value) selector["kubernetes.io/hostname"] = node.value.name;
  else delete selector["kubernetes.io/hostname"];
  patch({
    resources: {
      requests: {
        ...record(resources.requests),
        ...(key !== "keep" ? { [key]: String(count) } : {}),
      },
      limits: {
        ...record(resources.limits),
        ...(key !== "keep" ? { [key]: String(count) } : {}),
      },
    },
    platform: {
      ...record(spec.value.platform),
      os: node.value.operatingSystem,
      architectures: [node.value.architecture],
    },
    nodeSelector: selector,
  });
}
</script>
