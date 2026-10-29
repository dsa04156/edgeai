<template>
  <section aria-label="실행 위치 전환 이력">
    <p v-if="!operations.length" class="native-note">
      실행 위치 전환 이력이 없습니다.
    </p>
    <article
      v-for="operation in operations"
      :key="operation.id"
      class="native-panel"
    >
      <div class="native-toolbar">
        <KsTag
          :type="
            operation.state === 'SUCCEEDED'
              ? 'success'
              : operation.state === 'FAILED'
                ? 'danger'
                : 'info'
          "
        >
          {{ states[operation.state] || operation.state }}
        </KsTag>
        <span class="native-note">{{
          triggers[operation.trigger] || operation.trigger
        }}</span>
      </div>
      <dl>
        <div>
          <dt>전환 대상</dt>
          <dd>{{ target(operation) }}</dd>
        </div>
        <div>
          <dt>요청 시각</dt>
          <dd>{{ time(operation.createdAt) }}</dd>
        </div>
        <div>
          <dt>상태 갱신</dt>
          <dd>{{ time(operation.updatedAt) }}</dd>
        </div>
        <div v-if="operation.failureReason">
          <dt>실패 코드</dt>
          <dd>{{ operation.failureReason }}</dd>
        </div>
      </dl>
      <p
        v-if="operation.remoteTarget?.sourceMode === 'SYNTHETIC'"
        class="native-note"
      >
        Remote 참조 계산 · 실장비 검증 아님
      </p>
      <details v-if="operation.decision">
        <summary>{{ decisionSummary(operation.decision) }}</summary>
        <p class="native-note">
          판단 시각: {{ time(operation.decision.evaluatedAt) }} · 제외 노드:
          {{ operation.excludedNodeNames.join(", ") || "없음" }}
        </p>
        <p v-if="operation.decision.policy" class="native-note">
          {{ operation.decision.policy.consecutiveSamples }}회 연속 측정 · CPU
          {{ operation.decision.policy.cpuPercent ?? "기준 없음"
          }}{{ operation.decision.policy.cpuPercent == null ? "" : "%" }} ·
          메모리 {{ operation.decision.policy.memoryPercent ?? "기준 없음"
          }}{{ operation.decision.policy.memoryPercent == null ? "" : "%" }} ·
          지연
          {{
            operation.decision.policy.latencyMicros == null
              ? "기준 없음"
              : `${operation.decision.policy.latencyMicros} μs`
          }}
          · 작업별 최대 {{ operation.decision.policy.maxTransfers }}회 · 전환 후
          {{ operation.decision.policy.cooldownSeconds }}초 대기
        </p>
        <p v-else class="native-note">판단 정책이 보관되지 않았습니다.</p>
        <p v-if="operation.decision.policy?.nodePlacement" class="native-note">
          edge 과부하 기준 CPU
          {{ percent(operation.decision.policy.nodePlacement.sourceCpuPercent) }}
          · 메모리
          {{
            percent(operation.decision.policy.nodePlacement.sourceMemoryPercent)
          }}
          · GPU
          {{ percent(operation.decision.policy.nodePlacement.sourceGpuPercent) }}
          · worker 상한 CPU
          {{ operation.decision.policy.nodePlacement.targetMaxCpuPercent }}% ·
          메모리
          {{ operation.decision.policy.nodePlacement.targetMaxMemoryPercent }}%
        </p>
        <pre class="native-json">{{ pretty(operation.decision) }}</pre>
      </details>
      <details v-if="operation.members?.length">
        <summary>
          함께 전환하는 스트리밍 작업 {{ operation.members.length }}개
        </summary>
        <p class="native-note">
          선택한 작업은 지정 대상으로 이동하고 다른 작업은 기록된 배치 정책을
          유지합니다. 전환 완료 여부는 서버 상태로 확인합니다.
        </p>
        <NativeRecords
          title="전환 그룹과 체크포인트"
          :rows="
            operation.members.map((member) => ({
              ...member,
              id: member.taskId,
            }))
          "
          :columns="[
            { key: 'taskId', label: '작업' },
            { key: 'targetNodeId', label: '전환 대상', width: 220 },
            { key: 'checkpointId', label: '고정 체크포인트', width: 300 },
            { key: 'targetAttemptId', label: '새 실행 시도', width: 300 },
          ]"
          ><template #taskId="{ row }">{{ taskName(row.taskId) }}</template>
          <template #targetNodeId="{ row }">{{
            row.targetNodeId
              ? nodeName(row.targetNodeId)
              : row.targetVdId
                ? `과거 VD ${row.targetVdId}`
                : "자동 배치"
          }}</template>
          <template #targetAttemptId="{ row }">{{
            row.targetAttemptId || "새 실행 시도 없음"
          }}</template>
        </NativeRecords>
      </details>
      <details>
        <summary>전환 이력 ID·전체 기록</summary>
        <p class="native-note">
          이전 실행 시도: {{ operation.sourceAttemptId }} · 새 실행 시도:
          {{ operation.targetAttemptId || "아직 생성되지 않음" }}
        </p>
        <pre class="native-json">{{ pretty(operation) }}</pre>
      </details>
    </article>
  </section>
</template>
<script setup lang="ts">
import { KsTag } from "@kestra-io/design-system";
import { pretty, time, type Schema } from "./native-api";
import NativeRecords from "./NativeRecords.vue";
const props = defineProps<{
  operations: Schema["OffloadOperation"][];
  tasks: Schema["Task"][];
  nodes: Schema["ExecutionNode"][];
}>();
const states: Record<string, string> = {
  DRAINING: "이전 실행 종료 중",
  STARTING: "새 실행 시작 중",
  SUCCEEDED: "전환 성공 · 새 실행 시작됨",
  FAILED: "전환 실패",
  CANCELLING: "전환 취소 중",
  CANCELLED: "전환 취소됨",
};
const triggers: Record<string, string> = {
  MANUAL: "사용자 요청",
  CPU: "CPU 사용률 기준 충족",
  MEMORY: "메모리 사용률 기준 충족",
  LATENCY: "서비스 지연 기준 충족",
  NODE_CPU: "edge 노드 CPU 과부하",
  NODE_MEM: "edge 노드 메모리 과부하",
  NODE_GPU: "edge 노드 GPU 과부하",
  NODE_RETURN: "원래 edge 여유로 복귀",
};
type Decision = NonNullable<Schema["OffloadOperation"]["decision"]>;
// Task telemetry decisions keep raw samples; node decisions keep source/target nodes and candidates.
function decisionSummary(decision: Decision) {
  return "samples" in decision
    ? `자동 판단 근거 · 측정 ${decision.samples.length}개`
    : `노드 기반 판단 근거 · ${decision.sourceNode.name} → ${decision.targetNode.name} · 후보 ${decision.candidates.length}개`;
}
const percent = (value: number | null | undefined) =>
  value == null ? "기준 없음" : `${value}%`;
const nodeName = (id: string) =>
  props.nodes.find((node) => node.id === id)?.name || id;
const taskName = (id: string) =>
  props.tasks.find((task) => task.id === id)?.key || id;
function target(operation: Schema["OffloadOperation"]) {
  return operation.remoteTarget
    ? `Remote ${operation.remoteTarget.providerKey} · ${operation.remoteTarget.sourceMode}`
    : operation.targetNodeId
      ? nodeName(operation.targetNodeId)
      : "다른 호환 노드 자동 배치";
}
</script>
