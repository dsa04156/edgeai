<template>
  <TopNavBar title="감사 기록"
    ><template #actions
      ><KsButton :disabled="busy" @click="act(() => load())"
        >새로고침</KsButton
      ></template
    ></TopNavBar
  >
  <main class="native-page">
    <NativeFeedback :error="error" />
    <p class="native-note">
      변경 요청과 접근 거절을 접수 시각순으로 표시합니다. 관리 API의 응답
      기록이며 실제 작업 완료 여부는 실행 상세에서 확인합니다.
    </p>
    <NativeRecords
      title="최근 관리 요청"
      :rows="items"
      :columns="[
        { key: 'operation', label: '요청', width: 250 },
        { key: 'actor', label: '계정' },
        { key: 'result', label: '응답', width: 210 },
        { key: 'startedAt', label: '접수 시각', width: 220 },
      ]"
      :busy="busy"
      :offset="offset"
      :nextOffset="nextOffset"
      @page="(value) => act(() => load(value))"
      ><template #operation="{ row }"
        ><KsButton link @click="act(() => show(row.id))">{{
          operations[row.operation] || row.operation
        }}</KsButton></template
      ><template #actor="{ row }">{{ actor(row) }}</template
      ><template #result="{ row }">{{ result(row) }}</template
      ><template #startedAt="{ row }">{{
        time(row.startedAt)
      }}</template></NativeRecords
    ><KsForm
      labelPosition="top"
      @submit.prevent="act(() => show(id))"
      class="native-panel"
      ><KsFormItem label="감사 ID로 찾기"
        ><KsInput
          v-model="id"
          required
          minlength="36"
          maxlength="36"
          pattern="[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}" /></KsFormItem
      ><KsButton nativeType="submit" :disabled="busy"
        >감사 조회</KsButton
      ></KsForm
    >
    <section v-if="detail" class="native-panel native-detail">
      <div class="native-toolbar">
        <h2>{{ operations[detail.operation] || detail.operation }}</h2>
        <KsButton :disabled="busy" @click="act(() => show(detail!.id))"
          >상세 새로고침</KsButton
        >
      </div>
      <p>{{ result(detail) }}</p>
      <p v-if="!detail.outcome" class="native-note">
        접수됐지만 결과 기록이 없습니다. 원래 자원의 상태를 조회한 후
        조치하세요.
      </p>
      <dl>
        <div>
          <dt>계정</dt>
          <dd>{{ actor(detail) }}</dd>
        </div>
        <div>
          <dt>감사 ID</dt>
          <dd>{{ detail.id }}</dd>
        </div>
        <div>
          <dt>접수 시각</dt>
          <dd>{{ time(detail.startedAt) }}</dd>
        </div>
        <div>
          <dt>응답 시각</dt>
          <dd>{{ time(detail.outcome?.completedAt) }}</dd>
        </div>
        <div>
          <dt>대상 ID</dt>
          <dd>{{ detail.targetId || "—" }}</dd>
        </div>
      </dl>
      <details>
        <summary>요청 식별 정보</summary>
        <pre class="native-json">{{ pretty(detail) }}</pre>
      </details>
    </section>
  </main>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  KsButton,
  KsForm,
  KsFormItem,
  KsInput,
} from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import { get, pretty, time, useAction, type Schema } from "./native-api";
import NativeFeedback from "./NativeFeedback.vue";
import NativeRecords from "./NativeRecords.vue";
useRouteContext(computed(() => ({ title: "감사 기록" })));
const { busy, error, act } = useAction();
const items = ref<Schema["ManagementAudit"][]>([]),
  offset = ref(0),
  nextOffset = ref<number | null>(null),
  id = ref(""),
  detail = ref<Schema["ManagementAudit"] | null>(null);
const operations: Record<string, string> = {
  publishProfile: "프로필 버전 발행",
  createWorkflow: "워크플로 등록",
  publishWorkflowVersion: "DAG 버전 발행",
  createWorkflowRun: "워크플로 실행",
  cancelWorkflowRun: "실행 취소",
  cancelTask: "작업 취소",
  offloadTask: "작업 위치 전환",
  createVirtualDevice: "가상 장치 등록",
  updateVirtualDevice: "가상 장치 수정",
  releaseVirtualDevice: "가상 장치 해제",
  deleteVirtualDeviceRegistration: "가상 장치 등록 삭제",
  deleteProfileVersion: "프로필 버전 삭제",
  createManagedDevice: "센서 등록",
  updateManagedDeviceProperty: "센서 항목 수정",
  deleteManagedDevice: "센서 삭제",
};
const actor = (value: Schema["ManagementAudit"]) => {
  const person = value.outcome?.actor;
  return !person
    ? "확인되지 않음"
    : person.type === "UNAUTHENTICATED"
      ? "계정 없이 접수"
      : person.subjectFormat === "SHA256"
        ? "계정 식별 해시"
        : person.subject || "확인되지 않음";
};
const result = (value: Schema["ManagementAudit"]) =>
  !value.outcome
    ? "결과 미확인"
    : `HTTP ${value.outcome.httpStatus} · ${value.outcome.httpStatus >= 500 ? "오류" : value.outcome.httpStatus >= 400 ? "거절" : value.outcome.httpStatus === 202 ? "접수" : "응답 기록됨"}`;
async function load(value = offset.value) {
  const page = await get<Schema["ManagementAuditPage"]>(
    `audit-requests?limit=20&offset=${value}`,
  );
  items.value = page.items;
  offset.value = value;
  nextOffset.value = page.nextOffset;
}
async function show(value: string) {
  detail.value = await get<Schema["ManagementAudit"]>(
    `audit-requests/${encodeURIComponent(value.trim())}`,
  );
}
onMounted(() => {
  void act(() => load(0));
});
</script>
