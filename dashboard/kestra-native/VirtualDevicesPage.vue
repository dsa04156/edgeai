<template>
  <TopNavBar title="가상 장치"
    ><template #actions
      ><KsButton :disabled="busy" @click="refresh">새로고침</KsButton
      ><KsButton type="primary" :disabled="busy" @click="openCreate"
        >가상 장치 등록</KsButton
      ></template
    ></TopNavBar
  >
  <main class="native-page">
    <NativeFeedback :error="error" :notice="notice" />
    <div class="native-toolbar">
      <p class="native-note">센서 원본 → 중앙 계산 → 결과 저장 → 항목별 조회</p>
      <KsInput
        v-model="search"
        placeholder="이름 · 키 검색"
        aria-label="가상 장치 검색"
        clearable
      />
    </div>
    <NativeRecords
      title="등록된 가상 장치"
      :rows="filtered"
      :columns="[
        { key: 'displayName', label: '이름 / 키', width: 240 },
        { key: 'state', label: '등록 상태' },
        { key: 'runtime', label: '실행 상태', width: 200 },
        { key: 'placement', label: '배치 의도' },
      ]"
      :busy="busy"
      :offset="offset"
      :nextOffset="nextOffset"
      @page="(value) => act(() => load(value))"
    >
      <template #displayName="{ row }"
        ><KsButton link @click="act(() => show(row.id))">{{
          row.displayName
        }}</KsButton>
        <p class="native-note">{{ row.key }}</p></template
      ><template #state="{ row }"
        ><KsTag>{{ row.state }}</KsTag></template
      ><template #runtime="{ row }">{{ runtimeLabel(row) }}</template
      ><template #placement="{ row }"
        >{{ row.placement.mode }}
        {{
          nodes.find((n) => n.id === row.placement.nodeId)?.name || ""
        }}</template
      >
      <template #actions="{ row }"
        ><KsButton
          :disabled="busy || row.state !== 'REGISTERED'"
          @click="edit(row.id)"
          >설정</KsButton
        ><KsButton
          :disabled="busy || row.state !== 'REGISTERED' || !canStart(row)"
          @click="command(row, 'provision')"
          >시작</KsButton
        ><KsButton
          :disabled="busy || !canStop(row)"
          @click="command(row, 'drain')"
          >중지</KsButton
        ><KsButton
          type="danger"
          :disabled="busy"
          @click="
            removeTarget = row;
            confirmRemove = true;
            removeError = '';
          "
          >삭제</KsButton
        ></template
      >
    </NativeRecords>
    <section v-if="detail" class="native-panel native-detail">
      <div class="native-toolbar">
        <h2>{{ detail.vd.displayName }}</h2>
        <KsButton @click="detail = null">상세 닫기</KsButton
        ><KsButton
          :disabled="busy || detail.vd.state !== 'REGISTERED'"
          @click="edit(detail.vd.id)"
          >설정 수정</KsButton
        ><KsButton
          type="danger"
          :disabled="busy || detail.vd.state !== 'REGISTERED'"
          @click="
            removeTarget = detail.vd;
            confirmRemove = true;
            removeError = '';
          "
          >등록 해제</KsButton
        >
      </div>
      <dl>
        <div>
          <dt>키</dt>
          <dd>{{ detail.vd.key }}</dd>
        </div>
        <div>
          <dt>상태</dt>
          <dd>{{ detail.vd.state }} · revision {{ detail.vd.revision }}</dd>
        </div>
      </dl>
      <h3>원본 연결</h3>
      <NativeRecords
        title="현재 원본"
        :rows="detail.activeSources"
        :columns="[
          { key: 'sourceKey', label: '원본 슬롯' },
          { key: 'deviceId', label: '센서', width: 260 },
          { key: 'properties', label: '측정 항목' },
          { key: 'maxAgeSeconds', label: '최대 수신 경과(초)' },
        ]"
      />
      <VDRuntimePanel
        :key="`${detail.vd.id}/${detail.vd.revision}`"
        :vd="detail.vd"
      />
      <details>
        <summary>원본 연결 이력</summary>
        <NativeRecords
          title="연결 이력"
          :rows="detail.sourceHistory"
          :columns="[
            { key: 'sourceKey', label: '원본 슬롯' },
            { key: 'deviceId', label: '센서', width: 260 },
            { key: 'openedRevision', label: '시작 revision' },
            { key: 'closedRevision', label: '종료 revision' },
          ]"
        />
        <p v-if="detail.sourceHistoryTruncated">
          최근 이력 100개까지만 표시합니다.
        </p>
      </details>
    </section>
  </main>
  <KsDrawer
    :destroyOnClose="false"
    v-model="formOpen"
    :title="editing ? '가상 장치 설정' : '가상 장치 등록'"
    class="native-dialog"
    :beforeClose="closeForm"
    :showClose="!saving"
    :closeOnPressEscape="!saving"
    :closeOnClickModal="!saving"
  >
    <NativeFeedback :error="formError" />
    <p v-if="catalogError" class="native-note">
      {{ catalogError }}
      <KsButton :disabled="saving" @click="references"
        >등록 가능 상태 다시 조회</KsButton
      >
    </p>
    <ContinuousForm
      :key="formGeneration"
      formId="vd-continuous-form"
      :inputs="continuousCatalog.inputs"
      :enabled="continuousCatalog.enabled"
      :disabled="saving"
      :initial="continuousInitial"
      @save="saveContinuous"
    />
    <template #footer
      ><KsButton :disabled="saving" @click="formOpen = false"
        >닫기</KsButton
      ></template
    >
  </KsDrawer>
  <ConfirmAction
    v-model="confirmRemove"
    title="가상 장치 등록 해제"
    :subject="removeTarget?.displayName"
    description="등록을 해제합니다. 원본 센서, 계산 결과와 이력은 보존됩니다."
    danger
    :busy="saving"
    :error="removeError"
    @confirm="remove"
  />
  <ConfirmAction
    v-model="commandOpen"
    :title="commandDraft?.action === 'drain' ? '실행 중지' : '실행 시작'"
    :subject="commandDraft?.vd.displayName"
    description="현재 revision으로 요청합니다. 실제 실행 상태는 관측 후 갱신됩니다."
    :busy="saving"
    :error="commandError"
    @confirm="sendCommand"
  />
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { KsButton, KsDrawer, KsInput, KsTag } from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import {
  get,
  write,
  all,
  requestKey,
  useAction,
  usePoll,
  type Schema,
} from "./native-api";
import NativeRecords from "./NativeRecords.vue";
import NativeFeedback from "./NativeFeedback.vue";
import ConfirmAction from "./ConfirmAction.vue";
import ContinuousForm from "./ContinuousForm.vue";
import VDRuntimePanel from "./VDRuntimePanel.vue";
const route = useRoute(),
  router = useRouter();
useRouteContext(computed(() => ({ title: "가상 장치" })));
const { busy, error, notice, act } = useAction();
const items = ref<Schema["VirtualDevice"][]>([]),
  offset = ref(0),
  nextOffset = ref<number | null>(null),
  search = ref(""),
  detail = ref<Schema["VirtualDeviceDetail"] | null>(null),
  nodes = ref<Schema["ExecutionNode"][]>([]),
  catalogError = ref(""),
  continuousCatalog = ref<
    Pick<Schema["ContinuousCatalog"], "enabled" | "inputs">
  >({ enabled: false, inputs: [] });
const runtime = ref<
  Record<string, { execution: Schema["VDExecution"]; requested: number } | null>
>({});
const filtered = computed(() =>
  items.value.filter(
    (v) =>
      v.state === "REGISTERED" &&
      `${v.displayName} ${v.key}`
        .toLowerCase()
        .includes(search.value.toLowerCase()),
  ),
);
async function load(value = offset.value) {
  const page = await get<{
    items: Schema["VirtualDevice"][];
    nextOffset: number | null;
  }>(`virtual-devices?limit=20&offset=${value}`);
  items.value = page.items;
  offset.value = value;
  nextOffset.value = page.nextOffset;
  await runtimeStates();
}
async function runtimeStates() {
  const targets = [...items.value];
  const results = await Promise.allSettled(
    targets.map(async (vd) => ({
      id: vd.id,
      requested: performance.now(),
      execution: await get<Schema["VDExecution"]>(
        `virtual-devices/${vd.id}/execution`,
      ),
    })),
  );
  const next: typeof runtime.value = {};
  results.forEach((result, index) => {
    next[targets[index].id] =
      result.status === "fulfilled" ? result.value : null;
  });
  runtime.value = next;
}
function runtimeLabel(vd: Schema["VirtualDevice"]) {
  return runtime.value[vd.id]?.execution.continuous?.state || "상태 확인 필요";
}
function canStart(vd: Schema["VirtualDevice"]) {
  const r = runtime.value[vd.id]?.execution.continuous;
  return r?.enabled && !r.desiredRunning;
}
function canStop(vd: Schema["VirtualDevice"]) {
  const r = runtime.value[vd.id]?.execution.continuous;
  return r?.enabled && r.desiredRunning;
}
async function references() {
  catalogError.value = "";
  try {
    continuousCatalog.value = await get<Schema["ContinuousCatalog"]>(
      "virtual-devices/continuous-catalog",
    );
    nodes.value = await all<Schema["ExecutionNode"]>("nodes");
  } catch (cause) {
    continuousCatalog.value = { enabled: false, inputs: [] };
    catalogError.value =
      cause instanceof Error ? cause.message : "등록 상태 확인 실패";
  }
}
async function show(id: string) {
  detail.value = await get<Schema["VirtualDeviceDetail"]>(
    `virtual-devices/${id}`,
  );
  await router.replace({ query: { ...route.query, vd: id } });
}
const formOpen = ref(false),
  formGeneration = ref(0),
  editing = ref<Schema["VirtualDeviceDetail"] | null>(null),
  saving = ref(false),
  formError = ref(""),
  continuousInitial = ref<
    | {
        displayName: string;
        sources: Schema["VirtualDeviceSources"];
        continuous: Schema["ContinuousConfiguration"];
      }
    | undefined
  >();
let creationDraftInitialized = false;
function openCreate() {
  if (creationDraftInitialized && !editing.value) {
    formOpen.value = true;
    return;
  }
  creationDraftInitialized = true;
  editing.value = null;
  continuousInitial.value = undefined;
  formGeneration.value++;
  formError.value = "";
  formOpen.value = true;
}
async function edit(id: string) {
  await act(async () => {
    await show(id);
    editing.value = detail.value;
    const value = detail.value!;
    if (!value.continuous)
      throw new Error("현재 계산 설정을 조회하지 못했습니다.");
    continuousInitial.value = {
      displayName: value.vd.displayName,
      sources: value.activeSources.map((s) => ({
        sourceKey: s.sourceKey,
        deviceId: s.deviceId,
        properties: s.properties,
        maxAgeSeconds: s.maxAgeSeconds,
      })),
      continuous: JSON.parse(JSON.stringify(value.continuous)),
    };
    formGeneration.value++;
    formError.value = "";
    formOpen.value = true;
  });
}
function closeForm(done: () => void) {
  if (!saving.value) done();
}
async function saved(vd: Schema["VirtualDevice"]) {
  formOpen.value = false;
  creationDraftInitialized = false;
  notice.value = "가상 장치 설정을 저장했습니다.";
  try {
    await references();
    await load(editing.value ? offset.value : 0);
    if (editing.value) await show(vd.id);
    else detail.value = null;
  } catch {
    error.value = "저장은 완료됐지만 조회에 실패했습니다. 새로고침하세요.";
  }
}
async function saveContinuous(body: Schema["ContinuousCreate"]) {
  if (saving.value) return;
  saving.value = true;
  formError.value = "";
  try {
    const vd = await write<Schema["VirtualDevice"]>(
      editing.value
        ? `virtual-devices/${editing.value.vd.id}/continuous`
        : "virtual-devices/continuous",
      editing.value ? "PATCH" : "POST",
      editing.value
        ? {
            revision: editing.value.vd.revision,
            displayName: body.displayName,
            sources: body.sources,
            continuous: body.continuous,
          }
        : body,
    );
    await saved(vd);
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : "저장 실패";
  } finally {
    saving.value = false;
  }
}
const removeTarget = ref<Schema["VirtualDevice"] | null>(null),
  confirmRemove = ref(false),
  removeError = ref("");
async function remove() {
  if (!removeTarget.value || saving.value) return;
  saving.value = true;
  removeError.value = "";
  try {
    await write(`virtual-devices/${removeTarget.value.id}`, "DELETE");
    confirmRemove.value = false;
    if (detail.value?.vd.id === removeTarget.value.id) detail.value = null;
    notice.value = "등록을 해제했습니다. 계산 결과와 이력은 보존됩니다.";
    await load(
      items.value.length === 1 ? Math.max(0, offset.value - 20) : offset.value,
    );
  } catch (cause) {
    removeError.value = cause instanceof Error ? cause.message : "요청 실패";
  } finally {
    saving.value = false;
  }
}
const commandOpen = ref(false),
  commandError = ref(""),
  commandDraft = ref<{
    vd: Schema["VirtualDevice"];
    action: "provision" | "drain";
    key: string;
    revision: number;
  } | null>(null);
function command(vd: Schema["VirtualDevice"], action: "provision" | "drain") {
  commandDraft.value = { vd, action, key: requestKey(), revision: vd.revision };
  commandError.value = "";
  commandOpen.value = true;
}
async function sendCommand() {
  if (!commandDraft.value || saving.value) return;
  saving.value = true;
  commandError.value = "";
  try {
    const d = commandDraft.value;
    await write(
      `virtual-devices/${d.vd.id}/${d.action}`,
      "POST",
      { revision: d.revision },
      d.key,
    );
    commandOpen.value = false;
    await runtimeStates();
    notice.value = "실행 요청을 저장했습니다.";
  } catch (cause) {
    commandError.value = cause instanceof Error ? cause.message : "요청 실패";
  } finally {
    saving.value = false;
  }
}
async function refresh() {
  await act(async () => {
    await references();
    await load();
    if (detail.value) await show(detail.value.vd.id);
  });
}
onMounted(() => {
  void act(async () => {
    await references();
    await load(0);
    if (typeof route.query.vd === "string") await show(route.query.vd);
    if (route.query.create === "1") openCreate();
  });
});
usePoll(async () => {
  if (!busy.value && !saving.value) {
    await runtimeStates();
    if (formOpen.value) await references();
  }
}, 10000);
</script>
