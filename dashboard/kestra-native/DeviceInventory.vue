<!-- Original Kestra list composition: TopNavBar, KsDataTable and native controls. -->
<template>
  <TopNavBar title="장치 관리">
    <template #actions
      ><KsButton :icon="Refresh" :loading="busy" :disabled="busy" @click="load"
        >새로고침</KsButton
      ><KsButton type="primary" :disabled="busy" @click="openRegistration"
        >새 센서 등록</KsButton
      ></template
    >
  </TopNavBar>
  <section class="device-inventory" aria-label="장치 관리 목록">
    <KsAlert
      v-if="error || sourceErrors.length"
      type="warning"
      :closable="false"
      showIcon
      role="alert"
      :title="error || sourceErrors.join(' · ')"
    />
    <p v-if="notice" ref="noticeElement" tabindex="-1" role="status">
      {{ notice }}
    </p>
    <p v-if="selectionMissing" role="status">
      선택한 장치를 현재 관측에서 확인할 수 없습니다.
    </p>
    <KsDataTable
      id="device-inventory-table"
      :data="pageRows"
      :total="filteredRows.length"
      :currentPage="page"
      :pageSize="size"
      :loading="busy"
      rowKey="name"
      tableLayout="fixed"
      fitHeight
      :noDataText="emptyText"
      noDataDescription=""
      @page-changed="changePage"
      @sort-change="changeSort"
    >
      <template #top>
        <div class="inventory-controls">
          <KsSegmented
            :modelValue="view"
            :options="views"
            aria-label="장치 종류"
            class="inventory-views"
            @update:model-value="changeView"
          />
          <KsInput
            v-model="search"
            type="search"
            placeholder="이름 · 노드 · 장비 · 측정 항목 검색"
            aria-label="장치 검색"
            clearable
            size="large"
            class="inventory-search"
          />
        </div>
        <p class="inventory-observation" role="status">
          {{
            busy
              ? "불러오는 중…"
              : `${filteredRows.length}개 표시 · ${observationTime ? formatTime(observationTime) : "—"} 조회 시점 관측`
          }}
        </p>
      </template>
      <KsTableColumn prop="name" label="장치" sortable="custom" :minWidth="240">
        <template #default="{ row }"
          ><RouterLink :to="detailLocation(row)" @click="rememberFocus">{{
            row.name
          }}</RouterLink
          ><small>{{
            row.type === "node"
              ? `${row.architecture || "—"} · ${row.operatingSystem || "—"}`
              : row.model
          }}</small></template
        >
      </KsTableColumn>
      <KsTableColumn
        prop="readiness"
        label="상태"
        sortable="custom"
        :minWidth="120"
      >
        <template #default="{ row }"
          ><KsTag :type="stateTone(row.readiness)">{{
            row.readiness || "UNKNOWN"
          }}</KsTag></template
        >
      </KsTableColumn>
      <template v-if="view !== 'sensors'">
        <KsTableColumn label="CPU" :minWidth="150"
          ><template #default="{ row }"
            ><UsageMeter
              :value="row.cpuPercent"
              :label="`${row.name} CPU`" /></template
        ></KsTableColumn>
        <KsTableColumn label="메모리" :minWidth="150"
          ><template #default="{ row }"
            ><UsageMeter
              :value="row.memoryPercent"
              :label="`${row.name} 메모리`" /></template
        ></KsTableColumn>
        <KsTableColumn label="GPU / NPU" :minWidth="260"
          ><template #default="{ row }"
            ><AcceleratorCell :devices="row.accelerators" /></template
        ></KsTableColumn>
      </template>
      <template v-else>
        <KsTableColumn label="연결 노드" :minWidth="200"
          ><template #default="{ row }">{{
            row.nodeName || "—"
          }}</template></KsTableColumn
        >
        <KsTableColumn label="측정 항목 / 최신값" :minWidth="220"
          ><template #default="{ row }"
            ><div v-for="property in row.configuration" :key="property.name">
              {{ property.name
              }}<small
                >{{ row.readings[property.name]?.value ?? "—" }}
                {{ property.unit }} ·
                {{
                  row.readings[property.name]?.timestamp
                    ? new Date(
                        row.readings[property.name].timestamp,
                      ).toLocaleTimeString("ko-KR")
                    : "미수집"
                }}</small
              >
            </div></template
          ></KsTableColumn
        >
        <KsTableColumn label="관리 상태" :minWidth="140"
          ><template #default="{ row }">{{
            row.adminState || "UNKNOWN"
          }}</template></KsTableColumn
        >
        <KsTableColumn label="관리" :minWidth="350"
          ><template #default="{ row }"
            ><div class="sensor-edit-actions">
              <KsButton
                v-for="property in row.properties"
                :key="property"
                :aria-label="`${row.name} ${property} 수정`"
                :disabled="busy || row.protocol === 'rtsp'"
                @click="openEdit(row.name, property, $event)"
                >{{
                  row.properties.length === 1 ? "수정" : `${property} 수정`
                }}</KsButton
              ><KsButton
                v-if="row.protocol !== 'rtsp'"
                @click="openAdd(row.name, $event)"
                >항목 추가</KsButton
              ><KsButton type="danger" @click="prepareDelete(row.name, $event)"
                >삭제</KsButton
              >
            </div></template
          ></KsTableColumn
        >
      </template>
    </KsDataTable>
    <details v-if="view !== 'sensors'">
      <summary>실행 노드 등록 기록 더보기</summary>
      <NodeRecords />
    </details>
  </section>
  <KsDrawer
    v-model="drawerOpen"
    :title="selected?.name ?? '장치 상세'"
    class="sm"
    @closed="restoreFocus"
  >
    <div v-if="selected" class="inventory-detail">
      <h2>기본 정보</h2>
      <dl>
        <div>
          <dt>이름</dt>
          <dd>{{ selected.name }}</dd>
        </div>
        <div>
          <dt>상태</dt>
          <dd>
            <KsTag :type="stateTone(selected.readiness)">{{
              selected.readiness || "UNKNOWN"
            }}</KsTag>
          </dd>
        </div>
        <template v-if="selected.type === 'node'">
          <div>
            <dt>장치 종류</dt>
            <dd>{{ kinds[selected.kind] }}</dd>
          </div>
          <div>
            <dt>아키텍처</dt>
            <dd>{{ selected.architecture || "—" }}</dd>
          </div>
          <div>
            <dt>운영체제</dt>
            <dd>{{ selected.operatingSystem || "—" }}</dd>
          </div>
          <div>
            <dt>할당 가능 CPU</dt>
            <dd>{{ selected.allocatable?.cpu ?? "—" }}</dd>
          </div>
          <div>
            <dt>할당 가능 메모리</dt>
            <dd>{{ selected.allocatable?.memory ?? "—" }}</dd>
          </div>
        </template>
        <template v-else>
          <div>
            <dt>연결 노드</dt>
            <dd>{{ selected.nodeName || "—" }}</dd>
          </div>
          <div>
            <dt>모델</dt>
            <dd>{{ selected.model }}</dd>
          </div>
          <div>
            <dt>측정 항목</dt>
            <dd>{{ selected.properties.join(", ") || "—" }}</dd>
          </div>
          <div>
            <dt>관리 상태</dt>
            <dd>{{ selected.adminState || "UNKNOWN" }}</dd>
          </div>
          <div>
            <dt>수집 서비스</dt>
            <dd>{{ selected.serviceName || "—" }}</dd>
          </div>
        </template>
      </dl>
      <template v-if="selected.type === 'node'">
        <h2>현재 사용률</h2>
        <p>CPU</p>
        <UsageMeter
          :value="selected.cpuPercent"
          :label="`${selected.name} CPU`"
        />
        <p>메모리</p>
        <UsageMeter
          :value="selected.memoryPercent"
          :label="`${selected.name} 메모리`"
        />
        <h2>GPU / NPU</h2>
        <AcceleratorCell :devices="selected.accelerators" />
        <p class="detail-note">
          할당 가능량은 Kubernetes 노드 총량이며 현재 남은 자원과 다릅니다.
          미수집·만료된 사용률은 —로 표시합니다.
        </p>
      </template>
      <template v-else>
        <CameraPreview
          v-if="selected.protocol === 'rtsp'"
          :key="selected.name"
          :deviceId="selected.name"
        />
        <div v-else class="native-toolbar">
          <RouterLink
            v-for="property in selected.configuration"
            :key="property.name"
            :to="{
              name: 'edgeai/data-store',
              params: { tenant: route.params.tenant },
              query: { device: selected.name, property: property.name },
            }"
            >{{ property.name }} 원본 기록·그래프</RouterLink
          >
        </div>
        <h2>측정 설정</h2>
        <section
          v-for="property in selected.configuration"
          :key="property.name"
          class="sensor-property-detail"
        >
          <h3>{{ property.name }}</h3>
          <dl>
            <div>
              <dt>프로토콜</dt>
              <dd>{{ selected.protocol }}</dd>
            </div>
            <div>
              <dt>자료형</dt>
              <dd>{{ property.dataType }}</dd>
            </div>
            <div v-if="property.register !== undefined">
              <dt>Register</dt>
              <dd>
                {{ property.register }} · {{ property.function }} ·
                {{ property.byteOrder }}
              </dd>
            </div>
            <div v-if="property.nodeId">
              <dt>NodeId</dt>
              <dd>{{ property.nodeId }}</dd>
            </div>
            <div v-if="property.topic">
              <dt>입력 topic</dt>
              <dd>{{ property.topic }}</dd>
            </div>
            <div v-if="property.sensor">
              <dt>프레임 sensor</dt>
              <dd>{{ property.sensor }}</dd>
            </div>
            <div v-if="property.jsonPath">
              <dt>JSON 경로</dt>
              <dd>{{ property.jsonPath }}</dd>
            </div>
            <div>
              <dt>단위</dt>
              <dd>{{ property.unit || "—" }}</dd>
            </div>
            <div>
              <dt>배율</dt>
              <dd>{{ property.scale }}</dd>
            </div>
            <div>
              <dt>보정값</dt>
              <dd>{{ property.offset }}</dd>
            </div>
          </dl>
          <KsButton
            v-if="selected.protocol !== 'rtsp'"
            :aria-label="`${selected.name} ${property.name} 수정`"
            @click="openEdit(selected.name, property.name, $event)"
            >측정 항목 수정</KsButton
          >
          <KsButton
            v-if="selected.protocol !== 'rtsp'"
            type="danger"
            @click="prepareDelete(selected.name, $event, property.name)"
            >항목 삭제</KsButton
          >
        </section>
      </template>
      <p class="detail-note">
        {{
          selected.type === "sensor"
            ? sensorsAt
              ? formatTime(sensorsAt)
              : "—"
            : snapshot
              ? formatTime(snapshot.asOf)
              : "—"
        }}
        조회 시점 관측
      </p>
    </div>
    <template #footer
      ><KsButton @click="drawerOpen = false">닫기</KsButton></template
    >
  </KsDrawer>
  <DeviceRegistration
    v-model="registrationOpen"
    :nodes="snapshot?.inventory.nodes ?? null"
    @created="registered"
    @closed="registrationClosed"
  />
  <DeviceRegistration
    v-if="editTarget"
    :key="`${editTarget.id}/${editTarget.property}`"
    v-model="editingOpen"
    :edit="editTarget"
    :nodes="snapshot?.inventory.nodes ?? null"
    @updated="updated"
    @closed="editClosed"
  />
  <DeviceRegistration
    v-if="addTarget"
    :key="addTarget"
    v-model="addingOpen"
    :addTo="addTarget"
    :nodes="snapshot?.inventory.nodes ?? null"
    @created="registered"
    @closed="registrationClosed"
  />
  <ConfirmAction
    v-model="deletingOpen"
    title="센서 삭제"
    :subject="
      deletion
        ? `${deletion.id}${deletion.property ? ' · ' + deletion.property : ''}`
        : ''
    "
    description="선택한 센서 등록을 삭제합니다. 같은 연결의 다른 센서는 유지되며, 사용 중인 가상 장치가 있으면 서버가 삭제를 거절합니다."
    danger
    confirmLabel="삭제 확인"
    :busy="deleting"
    :error="deleteError"
    @confirm="removeSensor"
    @closed="restoreActionFocus"
  />
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import {
  KsAlert,
  KsButton,
  KsDataTable,
  KsDrawer,
  KsInput,
  KsSegmented,
  KsTableColumn,
  KsTag,
} from "@kestra-io/design-system";
import type { SortItem } from "@kestra-io/design-system";
import Refresh from "vue-material-design-icons/Refresh.vue";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import type { Overview } from "./overview";
import type { components } from "./live-api-schema";
import CameraPreview from "./CameraPreview.vue";
import DeviceRegistration from "./DeviceRegistration.vue";
import AcceleratorCell from "./AcceleratorCell.vue";
import UsageMeter from "./UsageMeter.vue";
import NodeRecords from "./NodeRecords.vue";
import ConfirmAction from "./ConfirmAction.vue";
import { write, usePoll } from "./native-api";

type View = "servers" | "edge" | "sensors" | "unknown";
const kinds = {
  EDGE_AI_SERVER: "AI 서버",
  EDGE_DEVICE: "엣지 장치",
  UNKNOWN: "분류 미확인",
};
const viewKinds = {
  servers: "EDGE_AI_SERVER",
  edge: "EDGE_DEVICE",
  unknown: "UNKNOWN",
} as const;
const route = useRoute(),
  router = useRouter();
const snapshot = ref<Overview | null>(null),
  busy = ref(false),
  error = ref("");
type Schema = components["schemas"];
type Sensor = Schema["ClusterSensor"] & {
  protocol: Schema["ManagedDeviceInput"]["protocol"];
  configuration: Schema["ManagedDeviceProperty"][];
  readings: Schema["ManagedDevice"]["properties"];
};
const sensors = ref<Sensor[] | null>(null),
  sensorsAt = ref("");
const registrationOpen = ref(false),
  notice = ref(""),
  noticeElement = ref<HTMLElement>();
const editTarget = ref<{ id: string; property: string } | null>(null),
  editingOpen = ref(false);
let registrationOrigin: HTMLElement | null = null;
let editOrigin: HTMLElement | null = null;
const addTarget = ref(""),
  addingOpen = ref(false),
  deletion = ref<{ id: string; property?: string } | null>(null),
  deletingOpen = ref(false),
  deleting = ref(false),
  deleteError = ref("");
let actionOrigin: HTMLElement | null = null;
const search = ref("");
const page = ref(1),
  size = ref(25),
  sort = ref("name"),
  descending = ref(false);
const view = computed<View>(() =>
  ["servers", "edge", "sensors", "unknown"].includes(String(route.query.view))
    ? (route.query.view as View)
    : "servers",
);
const formatTime = (value: string) =>
  new Date(value).toLocaleTimeString("ko-KR");
useRouteContext(computed(() => ({ title: "장치 관리" })));

const accelerators = computed(() => [
  ...(snapshot.value?.gpus ?? []).map((device) => ({
    ...device,
    kind: "GPU" as const,
  })),
  ...(snapshot.value?.npus ?? [])
    .filter((device) => device.vendor !== "intel")
    .map((device) => ({ ...device, kind: "NPU" as const })),
]);
const nodeRows = computed(() =>
  (snapshot.value?.nodes ?? []).map((node) => {
    const metadata = snapshot.value?.inventory.nodes?.find(
      (item) => item.name === node.name,
    );
    return {
      ...node,
      type: "node" as const,
      architecture: metadata?.architecture,
      operatingSystem: metadata?.operatingSystem,
      allocatable: metadata?.allocatable,
      accelerators: accelerators.value.filter(
        (device) => device.node === node.name,
      ),
    };
  }),
);
const sensorRows = computed(() =>
  (sensors.value ?? []).map((sensor) => ({
    ...sensor,
    type: "sensor" as const,
    readiness: sensor.operatingState || "UNKNOWN",
  })),
);
const observationTime = computed(() =>
  view.value === "sensors" ? sensorsAt.value : snapshot.value?.asOf,
);
type Row = (typeof nodeRows.value)[number] | (typeof sensorRows.value)[number];
const views = computed(() => {
  const nodesKnown = snapshot.value?.inventory.nodes != null;
  return [
    {
      value: "servers",
      label: "AI 서버",
      count: nodesKnown
        ? nodeRows.value.filter((n) => n.kind === "EDGE_AI_SERVER").length
        : null,
    },
    {
      value: "edge",
      label: "엣지 장치",
      count: nodesKnown
        ? nodeRows.value.filter((n) => n.kind === "EDGE_DEVICE").length
        : null,
    },
    {
      value: "sensors",
      label: "센서",
      count: sensors.value !== null ? sensorRows.value.length : null,
    },
    ...(!nodesKnown ||
    view.value === "unknown" ||
    nodeRows.value.some((n) => n.kind === "UNKNOWN")
      ? [
          {
            value: "unknown",
            label: "분류 미확인",
            count: nodesKnown
              ? nodeRows.value.filter((n) => n.kind === "UNKNOWN").length
              : null,
          },
        ]
      : []),
  ].map((option) => ({
    value: option.value,
    label: `${option.label} (${option.count ?? "—"})`,
  }));
});
const filteredRows = computed<Row[]>(() => {
  const rows: Row[] =
    view.value === "sensors"
      ? sensorRows.value
      : nodeRows.value.filter(
          (node) =>
            node.kind === viewKinds[view.value as keyof typeof viewKinds],
        );
  const query = search.value.trim().toLowerCase();
  return rows
    .filter((row) =>
      (row.type === "node"
        ? `${row.name} ${row.architecture} ${row.operatingSystem} ${row.accelerators.map((d) => d.model).join(" ")}`
        : `${row.name} ${row.nodeName} ${row.model} ${row.properties.join(" ")}`
      )
        .toLowerCase()
        .includes(query),
    )
    .sort(
      (a, b) =>
        (sort.value === "readiness"
          ? a.readiness.localeCompare(b.readiness)
          : a.name.localeCompare(b.name)) * (descending.value ? -1 : 1),
    );
});
const pageRows = computed(() =>
  filteredRows.value.slice(
    (page.value - 1) * size.value,
    page.value * size.value,
  ),
);
const sourceErrors = computed(() => {
  if (view.value === "sensors")
    return sensors.value === null ? ["센서 목록 확인 불가"] : [];
  if (!snapshot.value) return [];
  return [
    snapshot.value.inventory.nodes === null
      ? "노드 분류 확인 불가 · 수집된 노드는 분류 미확인에서 확인하세요"
      : "",
    !snapshot.value.inventory.metricsAvailable
      ? "사용률 확인 불가 · 미수집 값은 —로 표시합니다"
      : "",
  ].filter(Boolean);
});
const emptyText = computed(() =>
  busy.value
    ? "장치를 불러오는 중입니다."
    : error.value || sourceErrors.value.length
      ? "장치 조회 상태를 확인하세요."
      : search.value
        ? "검색 조건에 맞는 장치가 없습니다."
        : "관측된 장치가 없습니다.",
);
const selected = computed(() =>
  typeof route.query.node === "string"
    ? nodeRows.value.find((row) => row.name === route.query.node)
    : typeof route.query.sensor === "string"
      ? sensorRows.value.find((row) => row.name === route.query.sensor)
      : undefined,
);
const selectionMissing = computed(
  () =>
    !busy.value &&
    !!(route.query.node || route.query.sensor) &&
    !selected.value,
);
const drawerOpen = computed({
  get: () => !!selected.value,
  set: (open) => {
    if (!open) {
      const { node: _node, sensor: _sensor, ...query } = route.query;
      void router.replace({ query });
    }
  },
});
const stateTone = (state: string) =>
  ["READY", "ONLINE"].includes(state)
    ? "success"
    : ["NOT_READY", "OFFLINE"].includes(state)
      ? "danger"
      : "info";
function detailLocation(row: Row) {
  return {
    name: "edgeai/devices",
    params: { tenant: route.params.tenant },
    query: {
      ...route.query,
      node: row.type === "node" ? row.name : undefined,
      sensor: row.type === "sensor" ? row.name : undefined,
    },
  };
}
function changeView(value: string | number | boolean | undefined) {
  if (value !== undefined) void router.push({ query: { view: String(value) } });
}
function changePage(options: { page?: number; size?: number | string }) {
  size.value = Number(options.size ?? size.value);
  page.value = options.page ?? 1;
}
function changeSort(options: SortItem) {
  sort.value = options.prop ?? "name";
  descending.value = options.order === "descending";
  page.value = 1;
}
let focusOrigin: HTMLElement | null = null;
function rememberFocus(event: MouseEvent) {
  focusOrigin = event.currentTarget as HTMLElement;
}
function restoreFocus() {
  if (
    !editingOpen.value &&
    !registrationOpen.value &&
    !addingOpen.value &&
    !deletingOpen.value
  )
    void nextTick(() =>
      (focusOrigin?.isConnected
        ? focusOrigin
        : document.querySelector<HTMLAnchorElement>("#device-inventory-table a")
      )?.focus(),
    );
}
function openRegistration(event: MouseEvent) {
  registrationOrigin = event.currentTarget as HTMLElement;
  notice.value = "";
  registrationOpen.value = true;
}
function registrationClosed() {
  if (!selected.value)
    void nextTick(() =>
      (notice.value ? noticeElement.value : registrationOrigin)?.focus(),
    );
}
async function openAdd(id: string, event: MouseEvent) {
  registrationOrigin = event.currentTarget as HTMLElement;
  addTarget.value = id;
  addingOpen.value = true;
  notice.value = "";
}
function prepareDelete(id: string, event: MouseEvent, property?: string) {
  actionOrigin = event.currentTarget as HTMLElement;
  deletion.value = { id, property };
  deleteError.value = "";
  deletingOpen.value = true;
}
function restoreActionFocus() {
  if (!selected.value)
    void nextTick(() =>
      (actionOrigin?.isConnected
        ? actionOrigin
        : document.querySelector<HTMLAnchorElement>("#device-inventory-table a")
      )?.focus(),
    );
}
async function removeSensor() {
  if (!deletion.value || deleting.value) return;
  deleting.value = true;
  deleteError.value = "";
  try {
    const target = deletion.value;
    await write(
      `managed-devices/${encodeURIComponent(target.id)}${target.property ? `/properties/${encodeURIComponent(target.property)}` : ""}`,
      "DELETE",
    );
    deletingOpen.value = false;
    notice.value = `${target.id} 등록을 삭제했습니다.`;
    await loadSensors();
    if (!sensors.value?.some((sensor) => sensor.name === target.id))
      drawerOpen.value = false;
  } catch (cause) {
    deleteError.value =
      cause instanceof Error ? cause.message : "삭제하지 못했습니다.";
  } finally {
    deleting.value = false;
  }
}
async function openEdit(id: string, property: string, event: MouseEvent) {
  editOrigin = event.currentTarget as HTMLElement;
  notice.value = "";
  if (selected.value) {
    const { sensor: _sensor, node: _node, ...query } = route.query;
    await router.replace({ query });
  }
  editTarget.value = { id, property };
  editingOpen.value = true;
}
function editClosed() {
  if (selected.value) return;
  void nextTick(() => {
    const rowButton = [
      ...document.querySelectorAll<HTMLButtonElement>(
        "#device-inventory-table button",
      ),
    ].find(
      (button) =>
        button.getAttribute("aria-label") ===
        `${editTarget.value?.id} ${editTarget.value?.property} 수정`,
    );
    (notice.value
      ? noticeElement.value
      : editOrigin?.isConnected
        ? editOrigin
        : (rowButton ??
          document.querySelector<HTMLAnchorElement>(
            "#device-inventory-table a",
          ))
    )?.focus();
  });
}
async function updated(device: Schema["ManagedDevice"]) {
  notice.value = `${device.id} · ${editTarget.value?.property} 설정을 저장했습니다. 새 설정의 상태 보고를 기다립니다.`;
  await loadSensors();
  if (sensors.value?.some((sensor) => sensor.name === device.id)) {
    focusOrigin =
      [
        ...document.querySelectorAll<HTMLAnchorElement>(
          "#device-inventory-table a",
        ),
      ].find((link) => link.textContent === device.id) ?? null;
    await router.replace({
      query: { ...route.query, view: "sensors", sensor: device.id },
    });
  }
}
async function registered(device: components["schemas"]["ManagedDevice"]) {
  notice.value = `${device.id} 설정을 적용했습니다. 연결 상태 보고를 기다립니다.`;
  search.value = "";
  page.value = 1;
  await router.push({ query: { view: "sensors" } });
  await load();
  // The direct managed-device read avoids the infrastructure snapshot's 10-second cache.
  if (sensors.value?.some((sensor) => sensor.name === device.id)) {
    focusOrigin =
      [
        ...document.querySelectorAll<HTMLAnchorElement>(
          "#device-inventory-table a",
        ),
      ].find((link) => link.textContent === device.id) ?? null;
    await router.replace({ query: { view: "sensors", sensor: device.id } });
  }
}
async function load() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  await Promise.all([loadOverview(), loadSensors()]);
  busy.value = false;
}
async function loadOverview() {
  try {
    const response = await fetch("/api/edgeai/overview", { cache: "no-store" });
    if (!response.ok)
      throw new Error("장치 목록을 불러오지 못했습니다. 새로고침하세요.");
    snapshot.value = await response.json();
  } catch {
    snapshot.value = null;
    error.value = "장치 목록을 불러오지 못했습니다. 새로고침하세요.";
  }
}
async function loadSensors() {
  try {
    const response = await fetch("/api/control-plane/managed-devices", {
      cache: "no-store",
    });
    if (!response.ok) throw new Error("센서 조회 실패");
    const body: components["schemas"]["ManagedDevicePage"] =
      await response.json();
    sensors.value = body.items.map((device) => ({
      name: device.id,
      nodeName: device.targetNode,
      model: device.model,
      serviceName: "KubeEdge Mapper",
      adminState: "UNLOCKED",
      operatingState: device.state,
      properties: device.profile.properties.map((property) => property.name),
      protocol: device.profile.protocol,
      configuration: device.profile.properties,
      readings: device.properties,
    }));
    sensorsAt.value = new Date().toISOString();
  } catch {
    sensors.value = null;
    sensorsAt.value = "";
  }
}
watch([view, search], () => {
  page.value = 1;
});
watch(view, () => {
  search.value = "";
});
watch(filteredRows, (rows) => {
  page.value = Math.min(
    page.value,
    Math.max(1, Math.ceil(rows.length / size.value)),
  );
});
watch([view, views], async () => {
  await nextTick();
  if (window.innerWidth <= 768)
    document
      .querySelector<HTMLElement>(".inventory-views .is-selected")
      ?.scrollIntoView({ block: "nearest", inline: "nearest" });
});
onMounted(load);
usePoll(async () => {
  if (view.value === "sensors" && !busy.value && !deleting.value)
    await loadSensors();
}, 5000);
</script>

<style scoped lang="scss">
.device-inventory {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ks-spacing-4);
  padding: var(--ks-spacing-6);
}
.device-inventory > :deep(.ks-data-table-wrapper) {
  flex: 1;
  min-height: 0;
}
.inventory-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ks-spacing-4);
  padding-top: var(--ks-spacing-4);
}
.inventory-search {
  margin-left: auto;
  max-width: 360px;
}
.inventory-views :deep(.kel-segmented__item) {
  min-height: 44px;
}
.inventory-observation,
small {
  font-size: var(--ks-font-size-xs);
  color: var(--ks-text-secondary);
}
.inventory-observation {
  margin: var(--ks-spacing-3) 0;
}
small {
  display: block;
  overflow-wrap: anywhere;
}
.inventory-detail {
  overflow-wrap: anywhere;
}
.inventory-detail h2 {
  font-size: var(--ks-font-size-md);
  margin: var(--ks-spacing-5) 0 var(--ks-spacing-4);
}
.inventory-detail dl div {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  gap: var(--ks-spacing-4);
  margin-bottom: var(--ks-spacing-3);
}
.inventory-detail dt,
.detail-note {
  color: var(--ks-text-secondary);
  font-size: var(--ks-font-size-xs);
}
.inventory-detail dd {
  margin: 0;
}
.sensor-edit-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ks-spacing-2);
}
.sensor-edit-actions :deep(button),
.sensor-property-detail :deep(button) {
  min-height: 44px;
}
.sensor-property-detail h3 {
  font-size: var(--ks-font-size-sm);
  margin-bottom: var(--ks-spacing-4);
}
.detail-note {
  margin-top: var(--ks-spacing-5);
}
@media (max-width: 768px) {
  .device-inventory {
    padding: var(--ks-spacing-4);
  }
  .inventory-search {
    max-width: none;
    margin: 0;
  }
  .inventory-views {
    max-width: 100%;
    overflow-x: auto;
  }
  .inventory-views :deep(.kel-segmented__group) {
    flex-wrap: nowrap;
    width: max-content;
    min-width: 100%;
  }
}
</style>
