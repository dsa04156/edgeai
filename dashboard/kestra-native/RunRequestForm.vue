<template>
  <KsForm
    labelPosition="top"
    size="large"
    @submit.prevent="submit"
    class="native-form"
    ><NativeFeedback :error="error" />
    <h2>실행 요청 · {{ version.version }}</h2>
    <KsFormItem label="기본 실행 위치 정책"
      ><KsSelect v-model="mode" :disabled="disabled"
        ><KsOption
          v-for="value in ['AUTO', 'NODE', 'REMOTE']"
          :key="value"
          :value="value"
          :label="modes[value]" /></KsSelect
    ></KsFormItem>
    <KsFormItem v-if="mode === 'NODE'" label="실행 노드"
      ><KsSelect v-model="nodeId" :disabled="disabled"
        ><KsOption
          v-for="node in nodes"
          :key="node.id"
          :value="node.id"
          :label="`${node.name} · ${node.architecture}`"
          :disabled="node.status !== 'READY'" /></KsSelect
    ></KsFormItem>
    <KsFormItem v-if="mode === 'REMOTE'" label="Remote 제공자 key"
      ><KsInput
        :disabled="disabled"
        v-model="provider"
        required
        maxlength="63"
        pattern="[a-z][a-z0-9]*(-[a-z0-9]+)*"
    /></KsFormItem>
    <details>
      <summary>작업별 실행 위치</summary>
      <section
        v-for="task in version.dag.tasks"
        :key="task.key"
        class="native-panel"
      >
        <KsFormItem :label="`${task.key} 실행 위치`"
          ><KsSelect
            :modelValue="taskExecutions[task.key]?.mode || 'DEFAULT'"
            :disabled="disabled"
            @update:model-value="taskMode(task.key, String($event))"
            ><KsOption value="DEFAULT" label="기본 정책 따름" /><KsOption
              v-for="value in ['AUTO', 'NODE', 'REMOTE']"
              :key="value"
              :value="value"
              :label="modes[value]"
              :disabled="stream && value === 'REMOTE'" /></KsSelect></KsFormItem
        ><KsFormItem
          v-if="taskExecutions[task.key]?.mode === 'NODE'"
          label="노드"
          ><KsSelect
            :modelValue="taskTarget(task.key, 'nodeId')"
            :disabled="disabled"
            @update:model-value="
              taskExecutions[task.key] = {
                mode: 'NODE',
                nodeId: String($event),
              }
            "
            ><KsOption
              v-for="node in nodes"
              :key="node.id"
              :value="node.id"
              :label="node.name"
              :disabled="node.status !== 'READY'" /></KsSelect></KsFormItem
        ><KsFormItem
          v-if="taskExecutions[task.key]?.mode === 'REMOTE'"
          label="Remote 제공자"
          ><KsInput
            :disabled="disabled"
            :modelValue="taskTarget(task.key, 'providerKey')"
            required
            @update:model-value="
              taskExecutions[task.key] = {
                mode: 'REMOTE',
                providerKey: String($event),
              }
            "
        /></KsFormItem>
      </section>
    </details>
    <KsFormItem label="실행 매개변수 JSON"
      ><KsInput
        v-model="parameters"
        :disabled="disabled"
        type="textarea"
        :rows="5"
        spellcheck="false"
    /></KsFormItem>
    <details>
      <summary>고급 설정 · STREAM 입력</summary>
      <p class="native-note">
        활성 장치 세션을 실행에 고정합니다. 연결된 작업은 체크포인트 계약에 따라
        함께 재개합니다.
      </p>
      <section
        v-for="(input, index) in streamInputs"
        :key="index"
        class="native-panel"
      >
        <div class="native-grid">
          <KsFormItem
            v-for="(label, field) in {
              deviceId: '원본 장치 UUID',
              sourcePort: '장치 출력 포트',
              toTask: '받는 작업 키',
              toPort: '받는 스트림 포트',
            }"
            :key="field"
            :label="label"
            ><KsInput
              :disabled="disabled"
              :modelValue="input[field]"
              required
              @update:model-value="input[field] = String($event)" /></KsFormItem
          ><KsFormItem label="메시지 최대 크기 (바이트)"
            ><KsInput
              v-model="input.maxPayloadBytes"
              :disabled="disabled"
              type="number"
              min="1"
              max="262144"
              required
          /></KsFormItem>
        </div>
        <KsButton :disabled="disabled" @click="streamInputs.splice(index, 1)"
          >입력 삭제</KsButton
        >
      </section>
      <KsButton
        :disabled="disabled || streamInputs.length >= 128"
        @click="
          streamInputs.push({
            deviceId: '',
            sourcePort: 'samples',
            toTask: '',
            toPort: '',
            maxPayloadBytes: 4096,
          })
        "
        >장치 스트림 입력 추가</KsButton
      >
    </details>
    <details>
      <summary>고급 설정 · 재시도 및 자동 위치 전환</summary>
      <KsFormItem label="최대 실행 횟수 · 최초 실행 포함"
        ><KsInput
          v-model="attempts"
          :disabled="disabled"
          required
          type="number"
          min="1"
          max="8" /></KsFormItem
      ><template v-if="Number(attempts) > 1"
        ><div class="native-grid">
          <KsFormItem label="재시도 대기 (초)"
            ><KsInput
              v-model="backoff"
              :disabled="disabled"
              required
              type="number"
              min="1"
              max="300" /></KsFormItem
          ><KsFormItem label="재시도 허용 기간 (초)"
            ><KsInput
              v-model="retryWindow"
              :disabled="disabled"
              required
              type="number"
              min="1"
              max="86400"
          /></KsFormItem>
        </div>
        <KsCheckbox
          v-for="(label, code) in retryChoices"
          :key="code"
          :disabled="disabled"
          :modelValue="retryOn.includes(code)"
          @update:model-value="
            retryOn = $event
              ? [...retryOn, code]
              : retryOn.filter((c) => c !== code)
          "
          >{{ label }}</KsCheckbox
        ></template
      >
      <template v-if="!external"
        ><KsCheckbox v-model="automatic" :disabled="disabled"
          >측정 기반 자동 노드 전환 사용</KsCheckbox
        >
        <p class="native-note">
          연속 측정 기준 충족 시 다른 호환 노드로 전환합니다. 누락·오래된 측정은
          판단하지 않으며 현재 여유량을 추정하지 않습니다.
        </p>
        <div v-if="automatic" class="native-grid">
          <KsFormItem
            v-for="field in offloadFields"
            :key="field.key"
            :label="field.label"
            ><KsInput
              :disabled="disabled"
              :modelValue="offload[field.key] ?? ''"
              type="number"
              :required="field.required"
              :min="field.min"
              :max="field.max"
              @update:model-value="
                offload[field.key] = $event === '' ? null : Number($event)
              "
          /></KsFormItem></div
      ></template>
    </details>
    <KsFormItem label="실행 요청 키"
      ><KsInput :modelValue="key" readonly
    /></KsFormItem>
    <p class="native-note">
      같은 키와 내용의 재전송은 기존 실행을 반환합니다. 응답이 불확실할 때는
      키를 유지하세요.
    </p>
    <div class="native-toolbar">
      <KsButton :disabled="disabled" @click="key = requestKey()"
        >새 실행 키 만들기</KsButton
      ><KsButton nativeType="submit" type="primary" :disabled="disabled"
        >실행 요청 저장</KsButton
      >
    </div>
  </KsForm>
</template>
<script setup lang="ts">
import { computed, ref, watch } from "vue";
import {
  KsButton,
  KsCheckbox,
  KsForm,
  KsFormItem,
  KsInput,
  KsSelect,
  KsOption,
} from "@kestra-io/design-system";
import { objectJson, requestKey, type Schema } from "./native-api";
import NativeFeedback from "./NativeFeedback.vue";
const props = defineProps<{
  version: Schema["WorkflowVersion"];
  nodes: Schema["ExecutionNode"][];
  disabled?: boolean;
}>();
const emit = defineEmits<{
  submit: [body: Record<string, unknown>, key: string];
}>();
const mode = ref("AUTO"),
  nodeId = ref(""),
  provider = ref("reference"),
  parameters = ref("{}"),
  key = ref(requestKey()),
  error = ref("");
const taskExecutions = ref<Schema["TaskExecutions"]>({}),
  streamInputs = ref<Schema["StreamRunInput"][]>([]),
  attempts = ref(1),
  backoff = ref(5),
  retryWindow = ref(600),
  retryOn = ref<string[]>(["STORAGE_FAILED", "RUNTIME_LOST"]),
  automatic = ref(false),
  offload = ref<Record<string, number | null>>({
    cpuPercent: 90,
    memoryPercent: 90,
    latencyMicros: null,
    consecutiveSamples: 3,
    maxSampleAgeSeconds: 30,
    maxGapSeconds: 10,
    minRunningSeconds: 30,
    cooldownSeconds: 120,
    maxTransfers: 1,
    drainTimeoutSeconds: 60,
    startTimeoutSeconds: 120,
  });
const modes: Record<string, string> = {
  AUTO: "자동 선택",
  NODE: "노드 지정",
  REMOTE: "원격 제공자",
};
const retryChoices: Record<string, string> = {
  WORKLOAD_FAILED: "작업 프로세스 실패",
  TIMEOUT: "작업 시간 초과",
  STORAGE_FAILED: "저장소 오류",
  RUNNER_FAILED: "실행기 오류",
  DISPATCH_TIMEOUT: "배치 시간 초과",
  RUNTIME_TIMEOUT: "실행 시간 초과",
  RUNTIME_LOST: "실행 자원 유실",
  JOB_FAILED: "Kubernetes Job 실패",
};
const offloadFields = [
  {
    key: "cpuPercent",
    label: "CPU 기준 (%)",
    min: 1,
    max: 100,
    required: false,
  },
  {
    key: "memoryPercent",
    label: "메모리 기준 (%)",
    min: 1,
    max: 100,
    required: false,
  },
  {
    key: "latencyMicros",
    label: "서비스 지연 기준 (μs)",
    min: 1,
    max: 600000000,
    required: false,
  },
  ...(
    [
      ["consecutiveSamples", "연속 측정 수", 2, 6],
      ["maxSampleAgeSeconds", "측정 유효 시간 (초)", 10, 60],
      ["maxGapSeconds", "측정 최대 간격 (초)", 5, 30],
      ["minRunningSeconds", "실행 후 판단 대기 (초)", 10, 3600],
      ["cooldownSeconds", "전환 후 판단 대기 (초)", 10, 3600],
      ["maxTransfers", "자동 전환 한도", 1, 8],
      ["drainTimeoutSeconds", "종료 제한 (초)", 1, 600],
      ["startTimeoutSeconds", "시작 제한 (초)", 1, 600],
    ] as const
  ).map(([key, label, min, max]) => ({ key, label, min, max, required: true })),
];
const stream = computed(
    () =>
      streamInputs.value.length > 0 ||
      props.version.dag.dependencies.some((e) => e.mode === "STREAM"),
  ),
  external = computed(
    () =>
      ["REMOTE"].includes(mode.value) ||
      Object.values(taskExecutions.value).some((v) =>
        ["REMOTE"].includes(v.mode),
      ),
  );
function taskMode(task: string, value: string) {
  if (value === "DEFAULT") delete taskExecutions.value[task];
  else if (value === "NODE")
    taskExecutions.value[task] = { mode: "NODE", nodeId: "" };
  else if (value === "REMOTE")
    taskExecutions.value[task] = { mode: "REMOTE", providerKey: "reference" };
  else taskExecutions.value[task] = { mode: "AUTO" };
}
function taskTarget(task: string, field: string) {
  return (
    (taskExecutions.value[task] as unknown as Record<string, string>)?.[
      field
    ] || ""
  );
}
function submit() {
  if (props.disabled) return;
  error.value = "";
  try {
    if (mode.value === "NODE" && !nodeId.value)
      throw new Error("실행 대상을 선택하세요.");
    for (const target of Object.values(taskExecutions.value))
      if (target.mode === "NODE" && !target.nodeId)
        throw new Error("작업별 실행 대상을 선택하세요.");
    if (
      stream.value &&
      (mode.value === "REMOTE" ||
        Object.values(taskExecutions.value).some((t) => t.mode === "REMOTE"))
    )
      throw new Error("STREAM은 Remote 실행을 지원하지 않습니다.");
    if (Number(attempts.value) > 1 && !retryOn.value.length)
      throw new Error("재시도 오류를 한 개 이상 선택하세요.");
    if (
      automatic.value &&
      !external.value &&
      ["cpuPercent", "memoryPercent", "latencyMicros"].every(
        (k) => offload.value[k] == null,
      )
    )
      throw new Error("자동 전환 기준을 하나 이상 입력하세요.");
    emit(
      "submit",
      {
        workflowVersionId: props.version.id,
        execution:
          mode.value === "NODE"
            ? { mode: "NODE", nodeId: nodeId.value }
            : mode.value === "REMOTE"
              ? { mode: "REMOTE", providerKey: provider.value }
              : { mode: "AUTO" },
        parameters: objectJson(parameters.value),
        ...(Object.keys(taskExecutions.value).length
          ? { taskExecutions: taskExecutions.value }
          : {}),
        ...(streamInputs.value.length
          ? {
              streamInputs: streamInputs.value.map((s) => ({
                ...s,
                maxPayloadBytes: Number(s.maxPayloadBytes),
              })),
            }
          : {}),
        ...(Number(attempts.value) > 1
          ? {
              retry: {
                maxAttempts: Number(attempts.value),
                backoffSeconds: Number(backoff.value),
                maxElapsedSeconds: Number(retryWindow.value),
                retryOn: retryOn.value,
              },
            }
          : {}),
        ...(automatic.value && !external.value
          ? { offload: offload.value }
          : {}),
      },
      key.value,
    );
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "실행 입력 오류";
  }
}
watch(external, (value) => {
  if (value) automatic.value = false;
});
</script>
