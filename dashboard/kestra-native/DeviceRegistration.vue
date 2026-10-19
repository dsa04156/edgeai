<template>
  <KsDrawer
    v-model="open"
    :title="
      editing ? '센서 설정 수정' : adding ? '측정 항목 추가' : '새 센서 등록'
    "
    class="sensor-registration"
    :beforeClose="closeIfIdle"
    :closeOnPressEscape="!saving"
    :closeOnClickModal="!saving"
    :showClose="!saving"
    @opened="focusInput"
    @closed="emit('closed')"
  >
    <p class="registration-intro">
      {{
        adding
          ? "기존 연결에 새 측정 항목을 추가합니다. 항목마다 독립된 센서로 표시됩니다."
          : editing
            ? "선택한 측정 항목의 값 위치와 환산 설정을 수정합니다. 센서·항목 이름과 연결 설정은 유지됩니다."
            : "측정 항목 하나를 센서 하나로 등록합니다. 서버·엣지 노드는 자동으로 관측됩니다."
      }}
    </p>
    <div v-if="error" ref="errorElement" tabindex="-1">
      <KsAlert type="error" :closable="false" showIcon :title="error" />
    </div>
    <p v-if="loadingEdit" role="status">현재 센서 설정을 불러오는 중…</p>
    <KsButton
      v-if="configured && !loadingEdit && !editorReady"
      @click="loadEdit"
      >설정 다시 조회</KsButton
    >
    <dl v-if="configured && loadedDevice" class="editing-identity">
      <div>
        <dt>센서</dt>
        <dd>{{ loadedDevice.id }}</dd>
      </div>
      <div>
        <dt>연결 노드</dt>
        <dd>{{ loadedDevice.targetNode }}</dd>
      </div>
      <div>
        <dt>프로토콜</dt>
        <dd>{{ protocol }}</dd>
      </div>
    </dl>
    <KsForm
      v-if="editorReady"
      :id="formId"
      :model="fields"
      :disabled="saving"
      labelPosition="top"
      size="large"
      @submit.prevent="submit"
    >
      <template v-if="!configured">
        <h2>기본 정보</h2>
        <div class="registration-grid">
          <KsFormItem label="센서 이름" required
            ><KsInput
              :disabled="saving"
              ref="nameInput"
              v-model="fields.name"
              aria-label="센서 이름"
              required
              maxlength="63"
              pattern="[a-z0-9]([a-z0-9.\-]*[a-z0-9])?"
              placeholder="예: room-temperature"
          /></KsFormItem>
          <KsFormItem label="대상 엣지 노드" required>
            <KsSelect
              :disabled="saving"
              v-model="fields.targetNode"
              aria-label="대상 엣지 노드"
              filterable
              placeholder="노드 선택"
            >
              <KsOption
                v-for="node in eligibleNodes"
                :key="node.name"
                :value="node.name"
                :label="`${node.name} · ${node.architecture || '—'}`"
              />
            </KsSelect>
          </KsFormItem>
        </div>
        <p v-if="!eligibleNodes.length" class="registration-note" role="status">
          {{
            nodes === null
              ? "엣지 노드를 확인하지 못했습니다. 목록을 새로고침한 뒤 다시 시도하세요."
              : protocol === "sensehat"
                ? "Sense HAT에 필요한 ARM64 엣지 노드가 없습니다."
                : "등록할 엣지 노드가 없습니다."
          }}
        </p>
        <KsFormItem label="프로토콜" required>
          <KsSelect :disabled="saving" v-model="protocol" aria-label="프로토콜"
            ><KsOption
              v-for="option in protocols"
              :key="option.value"
              :value="option.value"
              :label="option.label"
          /></KsSelect>
        </KsFormItem>

        <h2>연결 설정</h2>
        <KsFormItem v-if="protocol === 'rtsp'" label="RTSP 주소" required
          ><KsInput
            v-model="fields.rtspUrl"
            :disabled="saving"
            type="url"
            required
            maxlength="1024"
            placeholder="rtsp://192.168.0.30:554/live"
        /></KsFormItem>
        <div
          v-if="!localHardware && protocol !== 'rtsp'"
          class="registration-grid"
        >
          <KsFormItem label="Host" required
            ><KsInput
              :disabled="saving"
              v-model="fields.host"
              aria-label="Host"
              required
              maxlength="253"
              pattern="[a-zA-Z0-9][a-zA-Z0-9._\-]*"
              placeholder="192.168.0.31"
          /></KsFormItem>
          <KsFormItem label="Port" required
            ><KsInput
              :disabled="saving"
              v-model="fields.port"
              aria-label="Port"
              type="number"
              required
              min="1"
              max="65535"
              step="1"
          /></KsFormItem>
          <KsFormItem v-if="protocol === 'modbus-tcp'" label="Unit ID" required
            ><KsInput
              :disabled="saving"
              v-model="fields.unitId"
              aria-label="Unit ID"
              type="number"
              required
              min="1"
              max="247"
              step="1"
          /></KsFormItem>
          <KsFormItem
            v-if="protocol === 'mqtt'"
            label="메시지 유효 시간 (ms)"
            required
            ><KsInput
              :disabled="saving"
              v-model="fields.staleMs"
              aria-label="메시지 유효 시간 (ms)"
              type="number"
              required
              min="100"
              max="60000"
              step="1"
          /></KsFormItem>
          <KsFormItem
            v-if="protocol === 'opc-ua' || protocol === 'virtual'"
            label="런타임 경로"
            :required="protocol === 'virtual'"
            ><KsInput
              :disabled="saving"
              v-model="fields.path"
              aria-label="런타임 경로"
              :required="protocol === 'virtual'"
              maxlength="512"
              placeholder="/properties"
          /></KsFormItem>
          <KsFormItem label="물리 장치 이름 (선택)"
            ><KsInput
              :disabled="saving"
              v-model="fields.sourceName"
              aria-label="물리 장치 이름 (선택)"
              maxlength="63"
              pattern="[a-z0-9]([a-z0-9.\-]*[a-z0-9])?"
              placeholder="같은 연결을 공유하는 장치 이름"
          /></KsFormItem>
        </div>
        <div v-else-if="localHardware" class="registration-grid">
          <KsFormItem label="장치 경로" required
            ><KsInput
              :disabled="saving"
              v-model="fields.devicePath"
              aria-label="장치 경로"
              required
              :readonly="protocol === 'sensehat'"
          /></KsFormItem>
          <KsFormItem label="소스 Device ID" required
            ><KsInput
              :disabled="saving"
              v-model="fields.deviceId"
              aria-label="소스 Device ID"
              required
              maxlength="63"
              pattern="[a-z0-9]([a-z0-9.\-]*[a-z0-9])?"
          /></KsFormItem>
          <KsFormItem v-if="protocol === 'serial-json'" label="Baud rate"
            ><KsSelect
              :disabled="saving"
              v-model="fields.baudRate"
              aria-label="Baud rate"
              ><KsOption
                v-for="rate in [9600, 19200, 38400, 57600, 115200]"
                :key="rate"
                :value="rate"
                :label="String(rate)" /></KsSelect
          ></KsFormItem>
        </div>
        <p class="registration-note">{{ protocolNotes[protocol] }}</p>
      </template>

      <template v-if="protocol !== 'rtsp'">
        <h2>측정 항목</h2>
        <KsFormItem v-if="!editing" label="저장한 측정 항목">
          <KsSelect
            :disabled="saving"
            v-model="preset"
            aria-label="저장한 측정 항목"
            :loading="profilesLoading"
            @change="applyPreset"
            ><KsOption value="manual" label="직접 입력" /><KsOption
              v-for="choice in choices"
              :key="choice.key"
              :value="choice.key"
              :label="choice.label"
          /></KsSelect>
        </KsFormItem>
        <p v-if="profilesError" class="registration-note" role="status">
          저장한 항목을 불러오지 못했습니다. 직접 입력하거나
          <KsButton
            link
            :disabled="profilesLoading || saving"
            @click="loadProfiles"
            >다시 조회</KsButton
          >하세요.
        </p>
        <div class="registration-grid">
          <KsFormItem label="항목 이름" required
            ><KsInput
              :disabled="saving"
              :readonly="editing"
              v-model="property.name"
              aria-label="항목 이름"
              required
              maxlength="63"
              pattern="[a-z][a-z0-9_]*"
              placeholder="temperature"
          /></KsFormItem>
          <KsFormItem label="자료형" required
            ><KsSelect
              :disabled="saving"
              v-model="property.dataType"
              aria-label="자료형"
              ><KsOption
                v-for="type in dataTypes"
                :key="type"
                :value="type"
                :label="type" /></KsSelect
          ></KsFormItem>
          <KsFormItem v-if="protocol === 'modbus-tcp'" label="Register" required
            ><KsInput
              :disabled="saving"
              v-model="property.register"
              aria-label="Register"
              type="number"
              required
              :min="registerBase"
              :max="registerBase + 65536 - registerWidth"
              step="1"
          /></KsFormItem>
          <KsFormItem v-if="protocol === 'modbus-tcp'" label="읽기 함수"
            ><KsSelect
              :disabled="saving"
              v-model="property.function"
              aria-label="읽기 함수"
              ><KsOption
                v-for="value in ['holding', 'input', 'coil']"
                :key="value"
                :value="value"
                :label="value" /></KsSelect
          ></KsFormItem>
          <KsFormItem v-if="protocol === 'modbus-tcp'" label="바이트 순서"
            ><KsSelect
              :disabled="saving"
              v-model="property.byteOrder"
              aria-label="바이트 순서"
              ><KsOption
                v-for="value in ['ABCD', 'BADC', 'CDAB', 'DCBA']"
                :key="value"
                :value="value"
                :label="value" /></KsSelect
          ></KsFormItem>
          <KsFormItem v-if="protocol === 'opc-ua'" label="NodeId" required
            ><KsInput
              :disabled="saving"
              v-model="property.nodeId"
              aria-label="NodeId"
              required
              maxlength="512"
          /></KsFormItem>
          <KsFormItem v-if="protocol === 'mqtt'" label="입력 topic" required
            ><KsInput
              :disabled="saving"
              v-model="property.topic"
              aria-label="입력 topic"
              required
              maxlength="256"
              placeholder="sensors/room/temperature"
          /></KsFormItem>
          <KsFormItem
            v-if="protocol === 'serial-json'"
            label="프레임 sensor"
            required
            ><KsInput
              :disabled="saving"
              v-model="property.sensor"
              aria-label="프레임 sensor"
              required
              maxlength="63"
          /></KsFormItem>
          <KsFormItem
            v-if="
              ['mqtt', 'virtual', 'serial-json', 'sensehat'].includes(protocol)
            "
            :label="protocol === 'sensehat' ? 'Sense HAT 항목' : 'JSON 경로'"
            :required="localHardware"
          >
            <KsSelect
              :disabled="saving"
              v-if="protocol === 'sensehat'"
              v-model="property.jsonPath"
              aria-label="Sense HAT 항목"
              ><KsOption
                v-for="value in sensehatProperties"
                :key="value"
                :value="value"
                :label="value"
            /></KsSelect>
            <KsInput
              :disabled="saving"
              v-else
              v-model="property.jsonPath"
              aria-label="JSON 경로"
              :required="protocol === 'serial-json'"
              maxlength="256"
              placeholder="temperature"
            />
          </KsFormItem>
          <KsFormItem label="단위"
            ><KsInput
              :disabled="saving"
              v-model="property.unit"
              aria-label="단위"
              maxlength="64"
              placeholder="예: °C"
          /></KsFormItem>
          <KsFormItem label="배율" required
            ><KsInput
              :disabled="saving"
              v-model="property.scale"
              aria-label="배율"
              type="number"
              required
              step="any"
          /></KsFormItem>
          <KsFormItem label="보정값" required
            ><KsInput
              :disabled="saving"
              v-model="property.offset"
              aria-label="보정값"
              type="number"
              required
              step="any"
          /></KsFormItem>
        </div>
      </template>
      <p v-if="protocol === 'modbus-tcp'" class="registration-note">
        Holding 40001 · Input 30001 · Coil 1은 각각 주소 0입니다. Coil은 bool
        자료형을 사용합니다.
      </p>
      <div v-if="!configured" class="registration-grid collection-settings">
        <KsFormItem label="수집 간격 (ms)" required
          ><KsInput
            :disabled="saving"
            v-model="fields.interval"
            aria-label="수집 간격 (ms)"
            type="number"
            required
            min="100"
            max="60000"
            step="1"
        /></KsFormItem>
        <KsFormItem label="읽기 제한 시간 (ms)" required
          ><KsInput
            :disabled="saving"
            v-model="fields.timeoutMs"
            aria-label="읽기 제한 시간 (ms)"
            type="number"
            required
            min="100"
            max="5000"
            step="1"
        /></KsFormItem>
      </div>
      <p v-if="notice" role="status">{{ notice }}</p>
      <div v-if="!configured" class="registration-actions">
        <KsButton :disabled="saving" @click="auxiliary('test')"
          >연결 테스트</KsButton
        ><KsButton :disabled="saving" @click="auxiliary('preset')"
          >측정 항목 규격 저장</KsButton
        >
      </div>
    </KsForm>
    <template #footer
      ><div class="registration-actions">
        <KsButton :disabled="saving" @click="open = false">취소</KsButton
        ><KsButton
          type="primary"
          nativeType="submit"
          :form="formId"
          :loading="saving"
          :disabled="
            saving || !editorReady || (!configured && !eligibleNodes.length)
          "
          >{{
            editing
              ? saving
                ? "저장 중…"
                : "변경 저장"
              : saving
                ? "등록 중…"
                : adding
                  ? "항목 추가"
                  : "센서 등록"
          }}</KsButton
        >
      </div></template
    >
  </KsDrawer>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from "vue";
import {
  KsAlert,
  KsButton,
  KsDrawer,
  KsForm,
  KsFormItem,
  KsInput,
  KsOption,
  KsSelect,
} from "@kestra-io/design-system";
import type { components } from "./live-api-schema";
import type { Overview } from "./overview";

type Schema = components["schemas"];
type Input = Schema["ManagedDeviceInput"];
type Protocol = Input["protocol"];
type Property = Schema["ManagedDeviceProperty"];
const props = defineProps<{
  modelValue: boolean;
  nodes: Overview["inventory"]["nodes"];
  edit?: { id: string; property: string };
  addTo?: string;
}>();
const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  created: [device: Schema["ManagedDevice"]];
  updated: [device: Schema["ManagedDevice"]];
  closed: [];
}>();
const saving = ref(false),
  error = ref(""),
  notice = ref("");
const editing = computed(() => !!props.edit),
  adding = computed(() => !!props.addTo),
  configured = computed(() => editing.value || adding.value),
  editorReady = ref(!props.edit && !props.addTo),
  loadingEdit = ref(false);
const loadedDevice = ref<Schema["ManagedDevice"] | null>(null);
const formId = computed(() =>
  configured.value ? "sensor-edit-form" : "sensor-registration-form",
);
const open = computed({
  get: () => props.modelValue,
  set: (value) => {
    if (!saving.value) emit("update:modelValue", value);
  },
});
const protocol = ref<Protocol>("modbus-tcp"),
  preset = ref("manual");
const fields = reactive({
  name: "",
  rtspUrl: "",
  targetNode: "",
  sourceName: "",
  host: "",
  port: 502,
  unitId: 1,
  staleMs: 5000,
  path: "",
  devicePath: "",
  deviceId: "",
  baudRate: 115200 as 9600 | 19200 | 38400 | 57600 | 115200,
  interval: 1000,
  timeoutMs: 1000,
});
const protocols: { value: Protocol; label: string }[] = [
  { value: "modbus-tcp", label: "Modbus TCP" },
  { value: "opc-ua", label: "OPC-UA" },
  { value: "mqtt", label: "MQTT" },
  { value: "virtual", label: "Virtual · HTTP JSON" },
  { value: "serial-json", label: "Serial JSON · Arduino" },
  { value: "sensehat", label: "Sense HAT · I²C" },
  { value: "rtsp", label: "RTSP · 카메라" },
];
const protocolNotes: Record<Protocol, string> = {
  rtsp: "인증 없는 RTSP 카메라를 실제 영상 수신으로 확인합니다. 영상은 장치 상세에서 볼 수 있습니다.",
  "modbus-tcp": "TCP 연결에서 지정한 레지스터 하나의 측정값을 읽습니다.",
  "opc-ua": "현재 Anonymous / SecurityMode None 연결을 지원합니다.",
  mqtt: "입력 topic은 정확한 이름을 입력하세요. 와일드카드와 EdgeAI 출력 topic은 사용할 수 없습니다.",
  virtual:
    "HTTP JSON 속성을 제공하는 서비스에 연결합니다. Virtual 센서로 등록됩니다.",
  "serial-json":
    "시리얼 JSON 프레임의 device_id·sensor와 값의 경로를 지정합니다. Arduino 값은 환산 전 원시값입니다.",
  sensehat: "ARM64 Raspberry Pi의 Sense HAT v1을 /dev/i2c-1에서 읽습니다.",
};
const sensehatProperties = [
  "temp_humidity",
  "temp_pressure",
  "humidity",
  "pressure",
  "compass",
  "pitch",
  "roll",
  "yaw",
  "gyro_x",
  "gyro_y",
  "gyro_z",
];
function newProperty(value: Protocol): Property {
  if (value === "rtsp")
    return {
      name: "connected",
      dataType: "bool",
      jsonPath: "connected",
      scale: 1,
      offset: 0,
      unit: "",
    };
  return {
    name: "",
    dataType: value === "serial-json" ? "int32" : "float32",
    scale: 1,
    offset: 0,
    unit: "",
    ...(value === "modbus-tcp"
      ? { register: 40001, function: "holding", byteOrder: "ABCD" }
      : value === "opc-ua"
        ? { nodeId: "ns=1;s=temperature" }
        : value === "mqtt"
          ? { topic: "", jsonPath: "temperature" }
          : value === "serial-json"
            ? { sensor: "temperature", jsonPath: "raw" }
            : {
                jsonPath:
                  value === "sensehat" ? "temp_humidity" : "temperature",
              }),
  };
}
const property = ref<Property>(newProperty(protocol.value));
const localHardware = computed(() =>
  ["serial-json", "sensehat"].includes(protocol.value),
);
const eligibleNodes = computed(() =>
  (props.nodes ?? []).filter(
    (node) =>
      node.kind === "EDGE_DEVICE" &&
      (protocol.value !== "sensehat" || node.architecture === "arm64"),
  ),
);
const dataTypes = computed(() => [
  "float32",
  "float64",
  "int16",
  "uint16",
  "int32",
  "uint32",
  "bool",
  ...(protocol.value === "modbus-tcp" ? [] : ["string"]),
]);
const registerBase = computed(() =>
  property.value.function === "input"
    ? 30001
    : property.value.function === "coil"
      ? 1
      : 40001,
);
const registerWidth = computed(() =>
  property.value.dataType === "float64"
    ? 4
    : ["float32", "int32", "uint32"].includes(property.value.dataType)
      ? 2
      : 1,
);
const profiles = ref<Schema["ManagedDeviceProfile"][]>([]),
  profilesLoading = ref(false),
  profilesError = ref(false);
const choices = computed(() =>
  profiles.value
    .filter((p) => p.protocol === protocol.value)
    .flatMap((p) =>
      p.properties.map((item) => ({
        key: `${p.name}/${item.name}`,
        label: `${item.name} · ${p.name}`,
        property: item,
      })),
    ),
);
const nameInput = ref<InstanceType<typeof KsInput>>(),
  errorElement = ref<HTMLElement>();
function focusInput() {
  if (!configured.value) nameInput.value?.focus();
  else
    document
      .querySelector<HTMLInputElement>(
        `#${formId.value} input:not([readonly]):not([disabled])`,
      )
      ?.focus();
}
function closeIfIdle(done: () => void) {
  if (!saving.value) done();
}
function applyPreset() {
  property.value = {
    ...(choices.value.find((choice) => choice.key === preset.value)?.property ??
      newProperty(protocol.value)),
  };
}
async function loadProfiles() {
  profilesLoading.value = true;
  profilesError.value = false;
  try {
    profiles.value = (await (await request("device-profiles")).json()).items;
  } catch {
    profiles.value = [];
    profilesError.value = true;
  } finally {
    profilesLoading.value = false;
  }
}
async function request(path: string, init?: RequestInit) {
  const response = await fetch(`/api/control-plane/${path}`, {
    ...init,
    cache: "no-store",
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.message || `요청 실패 (${response.status})`);
  }
  return response;
}
function input(): Input {
  if (!eligibleNodes.value.some((node) => node.name === fields.targetNode))
    throw new Error("대상 엣지 노드를 선택하세요.");
  const config: NonNullable<Input["protocolConfig"]> = {
    timeoutMs: Number(fields.timeoutMs),
  };
  let cameraAddress: URL | undefined;
  if (protocol.value === "rtsp") {
    try {
      cameraAddress = new URL(fields.rtspUrl);
    } catch {
      throw new Error("유효한 RTSP 주소를 입력하세요.");
    }
    if (
      cameraAddress.protocol !== "rtsp:" ||
      cameraAddress.username ||
      cameraAddress.password ||
      cameraAddress.search ||
      cameraAddress.hash
    )
      throw new Error("인증정보·쿼리 없는 rtsp:// 주소를 입력하세요.");
    config.path = cameraAddress.pathname || "/";
  }
  if (protocol.value === "modbus-tcp") {
    config.unitId = Number(fields.unitId);
  }
  if (protocol.value === "opc-ua") {
    config.securityMode = "None";
    config.path = fields.path;
  }
  if (protocol.value === "virtual") config.path = fields.path;
  if (protocol.value === "mqtt") config.staleMs = Number(fields.staleMs);
  if (localHardware.value) {
    config.devicePath = fields.devicePath;
    config.deviceId = fields.deviceId;
  }
  if (protocol.value === "serial-json") config.baudRate = fields.baudRate;
  return {
    name: fields.name,
    ...(fields.sourceName && !localHardware.value
      ? { sourceName: fields.sourceName }
      : {}),
    kind: protocol.value === "virtual" ? "virtual" : "physical",
    protocol: protocol.value,
    targetNode: fields.targetNode,
    collectionInterval: Number(fields.interval),
    protocolConfig: config,
    ...(!localHardware.value
      ? {
          host: cameraAddress?.hostname ?? fields.host,
          port: cameraAddress
            ? Number(cameraAddress.port || 554)
            : Number(fields.port),
        }
      : {}),
    properties: [measurementInput()],
  };
}
function measurementInput(): Property {
  if (property.value.name === "connection")
    throw new Error(
      "connection은 예약된 항목 이름입니다. 다른 이름을 입력하세요.",
    );
  if (editing.value && property.value.name !== props.edit?.property)
    throw new Error("측정 항목 이름은 변경할 수 없습니다.");
  if (
    protocol.value === "modbus-tcp" &&
    (property.value.function === "coil") !==
      (property.value.dataType === "bool")
  )
    throw new Error(
      "Coil은 bool 자료형을, Holding·Input은 숫자 자료형을 선택하세요.",
    );
  return {
    ...property.value,
    scale: Number(property.value.scale),
    offset: Number(property.value.offset),
    ...(protocol.value === "modbus-tcp"
      ? { register: Number(property.value.register) }
      : {}),
  };
}
let editGeneration = 0;
async function loadEdit() {
  if (!props.edit && !props.addTo) return;
  const generation = ++editGeneration,
    target = props.edit ?? { id: props.addTo!, property: "" };
  loadingEdit.value = true;
  editorReady.value = false;
  loadedDevice.value = null;
  error.value = "";
  try {
    const device: Schema["ManagedDevice"] = await (
      await request(`managed-devices/${encodeURIComponent(target.id)}`)
    ).json();
    const current = device.profile.properties.find(
      (item) => item.name === target.property,
    );
    if (device.id !== target.id || (!adding.value && !current))
      throw new Error("측정 항목이 없습니다. 목록을 새로고침하세요.");
    if (generation !== editGeneration || !open.value) return;
    protocol.value = device.profile.protocol;
    await nextTick();
    property.value = adding.value
      ? newProperty(protocol.value)
      : { ...current! };
    loadedDevice.value = device;
    editorReady.value = true;
    if (adding.value) void loadProfiles();
    await nextTick();
    focusInput();
  } catch (cause) {
    if (generation === editGeneration && open.value) {
      error.value =
        cause instanceof Error
          ? cause.message
          : "센서 설정을 불러오지 못했습니다.";
      await nextTick();
      errorElement.value?.focus();
    }
  } finally {
    if (generation === editGeneration) loadingEdit.value = false;
  }
}
async function auxiliary(action: "test" | "preset") {
  if (saving.value) return;
  const element = document.getElementById(
    formId.value,
  ) as HTMLFormElement | null;
  if (!element?.reportValidity()) return;
  saving.value = true;
  error.value = "";
  notice.value = "";
  try {
    const value = input();
    const { token } = await (await request("csrf")).json();
    const result = await (
      await request(
        action === "test" ? "device-connections/test" : "device-profiles",
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-CSRF-TOKEN": token,
          },
          body: JSON.stringify(
            action === "test"
              ? value
              : {
                  name: value.name,
                  protocol: value.protocol,
                  properties: value.properties,
                },
          ),
        },
      )
    ).json();
    if (action === "test") {
      if (!result.connected) throw new Error(result.error || "연결 실패");
      notice.value = `연결 확인: ${String(result.value ?? "응답 수신")}`;
    } else {
      notice.value = "측정 항목 규격을 저장했습니다.";
      await loadProfiles();
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "요청 실패";
  } finally {
    saving.value = false;
  }
}
async function submit() {
  if (saving.value || !editorReady.value) return;
  const element = document.getElementById(
    formId.value,
  ) as HTMLFormElement | null;
  if (!element?.reportValidity()) return;
  saving.value = true;
  error.value = "";
  try {
    const body = configured.value ? measurementInput() : input();
    const { token } = await (await request("csrf")).json();
    const path = props.edit
      ? `managed-devices/${encodeURIComponent(props.edit.id)}/properties/${encodeURIComponent(props.edit.property)}`
      : props.addTo
        ? `managed-devices/${encodeURIComponent(props.addTo)}/properties`
        : "managed-devices";
    const device: Schema["ManagedDevice"] = await (
      await request(path, {
        method: editing.value ? "PUT" : "POST",
        headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": token },
        body: JSON.stringify(body),
      })
    ).json();
    if (editing.value) emit("updated", device);
    else emit("created", device);
    saving.value = false;
    open.value = false;
    fields.name = "";
    property.value = newProperty(protocol.value);
    preset.value = "manual";
  } catch (cause) {
    error.value =
      cause instanceof Error ? cause.message : "센서 등록에 실패했습니다.";
    await nextTick();
    errorElement.value?.focus();
  } finally {
    saving.value = false;
  }
}
watch(protocol, (value) => {
  property.value = newProperty(value);
  preset.value = "manual";
  error.value = "";
  fields.port = {
    "modbus-tcp": 502,
    "opc-ua": 4840,
    mqtt: 1883,
    virtual: 8080,
    "serial-json": 0,
    sensehat: 0,
    rtsp: 554,
  }[value];
  fields.path = value === "virtual" ? "/properties" : "";
  fields.devicePath =
    value === "serial-json"
      ? "/dev/edgeai/arduino-001"
      : value === "sensehat"
        ? "/dev/i2c-1"
        : "";
  fields.deviceId =
    value === "serial-json"
      ? "arduino-001"
      : value === "sensehat"
        ? "sensehat-001"
        : "";
  fields.timeoutMs =
    value === "rtsp" ? 5000 : localHardware.value ? 4000 : 1000;
});
watch(eligibleNodes, (nodes) => {
  if (!nodes.some((node) => node.name === fields.targetNode))
    fields.targetNode = "";
});
watch(
  open,
  (value) => {
    if (value) {
      if (configured.value) void loadEdit();
      else void loadProfiles();
    } else editGeneration++;
  },
  { immediate: true },
);
</script>

<style scoped lang="scss">
.registration-intro,
.registration-note {
  color: var(--ks-text-secondary);
  font-size: var(--ks-font-size-xs);
  line-height: 1.6;
}
.registration-intro {
  margin-bottom: var(--ks-spacing-5);
}
h2 {
  font-size: var(--ks-font-size-md);
  font-weight: 600;
  margin: var(--ks-spacing-6) 0 var(--ks-spacing-4);
}
.registration-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--ks-spacing-5);
}
.collection-settings {
  margin-top: var(--ks-spacing-5);
}
.editing-identity {
  font-size: var(--ks-font-size-xs);
  overflow-wrap: anywhere;
}
.editing-identity div {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: var(--ks-spacing-3);
  margin-top: var(--ks-spacing-3);
}
.editing-identity dt {
  color: var(--ks-text-secondary);
}
.editing-identity dd {
  margin: 0;
}
.registration-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--ks-spacing-3);
}
.registration-actions :deep(button) {
  min-height: 44px;
}
:deep(.kel-select__wrapper),
:deep(.kel-input__wrapper) {
  min-height: 44px;
}
@media (max-width: 768px) {
  .registration-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .registration-actions > * {
    flex: 1;
  }
}
</style>
