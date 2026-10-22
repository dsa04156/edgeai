<template>
  <TopNavBar title="DDS 배포 편집기"
    ><template #actions
      ><KsButton :disabled="busy" @click="act(loadConfig)">연결 확인</KsButton
      ><KsButton
        :disabled="busy || !connection?.configured"
        type="primary"
        @click="prepare('deploy')"
        >배포 검토</KsButton
      ><KsButton
        :disabled="busy || !connection?.configured"
        type="danger"
        @click="prepare('delete')"
        >배포 삭제</KsButton
      ></template
    ></TopNavBar
  >
  <main class="native-page">
    <NativeLoading :busy="busy" />
    <NativeFeedback :error="error" :notice="notice" />
    <p class="native-note">
      {{
        connection?.configured
          ? `Gitea ${connection.repository} · ${connection.branch}`
          : "배포 서버 연결 확인 필요"
      }}<span v-if="connection?.missing?.length">
        · 서버 설정 필요: {{ connection.missing.join(", ") }}</span
      >
    </p>
    <p class="native-note">
      Sensor Pub은 시험 데이터를 발행합니다. 연결선은 편집용이며 실제 통신은 DDS
      토픽 설정을 따릅니다.
    </p>
    <div class="deployment-layout">
      <aside class="native-panel native-form">
        <KsForm labelPosition="top"
          ><fieldset :disabled="busy">
            <KsFormItem label="앱 이름"
              ><KsInput
                v-model="draft.name"
                :disabled="busy"
                aria-label="DDS 앱 이름"
                maxlength="63"
            /></KsFormItem>
            <KsFormItem label="DDS 프로필"
              ><KsSelect
                v-model="draft.profile"
                :disabled="busy"
                aria-label="DDS 프로필"
                ><KsOption
                  v-for="p in [
                    'dds_discovery',
                    'dds_shm',
                    'dds_tcp',
                    'dds_udp',
                  ]"
                  :key="p"
                  :value="p"
                  :label="p" /></KsSelect
            ></KsFormItem>
            <KsFormItem label="Discovery Server IP"
              ><KsInput
                v-model="draft.discoveryIp"
                :disabled="busy"
                aria-label="Discovery Server IP"
            /></KsFormItem>
            <p class="native-note">
              KubeEdge에서는 Service DNS 대신 실제 Cloud Node IP를 입력하세요.
            </p>
            <h2>배포 노드</h2>
            <div class="deployment-library">
              <KsButton
                v-for="(label, key) in templates"
                :key="key"
                :disabled="busy"
                draggable
                @dragstart="drag($event, key)"
                @click="add(key)"
                >+ {{ label }}</KsButton
              >
            </div>
            <div class="native-toolbar">
              <KsButton :disabled="busy" @click="pipeline('sensor')"
                >센서 파이프라인</KsButton
              ><KsButton :disabled="busy" @click="pipeline('camera')"
                >카메라 파이프라인</KsButton
              >
            </div>
            <h2>이미지 빌드</h2>
            <KsCheckbox v-model="buildFirst" :disabled="busy"
              >배포 전 Buildx 빌드·푸시</KsCheckbox
            >
            <KsFormItem label="이미지 태그"
              ><KsInput
                v-model="buildTag"
                :disabled="busy"
                placeholder="비우면 자동 생성"
                @update:model-value="builtImage = ''"
            /></KsFormItem>
            <p class="native-note">
              플랫폼 {{ connection?.platforms?.join(", ") || "서버 설정" }}
            </p>
            <KsButton
              :disabled="busy || !connection?.buildConfigured"
              @click="prepare('build')"
              >이미지 빌드·푸시 검토</KsButton
            >
            <p v-if="builtImage" class="native-note">{{ builtImage }}</p>
            <h2>YAML</h2>
            <label class="native-note"
              >YAML 가져오기<input
                type="file"
                accept=".yaml,.yml"
                :disabled="busy"
                aria-label="DDS YAML 가져오기"
                @change="importFile"
            /></label>
            <div class="native-toolbar">
              <KsButton :disabled="busy" @click="download"
                >YAML 다운로드</KsButton
              ><KsButton :disabled="busy" @click="preview"
                >YAML 미리보기</KsButton
              >
            </div>
          </fieldset></KsForm
        >
      </aside>
      <section class="native-panel deployment-canvas">
        <CanvasBoard
          :nodes="draft.nodes"
          :edges="draft.edges"
          :selected="selectedId"
          :disabled="busy"
          @select="edit"
          @move="move"
          @connect="connect"
          @remove-edge="removeEdge"
          @arrange="arrange"
          @drop="add"
        />
      </section>
    </div>
    <section class="native-panel">
      <div class="native-toolbar">
        <h2>Argo CD 배포 상태</h2>
        <KsInput
          v-model="statusName"
          placeholder="앱 이름"
          aria-label="배포 상태 앱 이름"
        /><KsButton :disabled="busy" @click="act(loadStatus)"
          >상태 조회</KsButton
        >
      </div>
      <NativeFeedback :error="statusError" />
      <p v-if="appStatus" role="status">
        {{ appStatus.name }} ·
        {{
          appStatus.status === "absent"
            ? "앱 없음"
            : `동기화 ${appStatus.sync || "확인 중"} · 준비 ${appStatus.health || "확인 중"}`
        }}{{ appStatus.deleting ? " · 삭제 진행 중" : "" }}
      </p>
      <details v-if="appStatus">
        <summary>전체 배포 상태</summary>
        <pre class="native-json">{{ pretty(appStatus) }}</pre>
      </details>
    </section>
  </main>
  <KsDrawer v-model="nodeOpen" title="DDS 노드 설정" class="native-dialog">
    <NativeFeedback :error="nodeError" /><KsForm
      v-if="nodeDraft"
      id="dds-node-form"
      labelPosition="top"
      class="native-form"
      @submit.prevent="saveNode"
    >
      <KsFormItem label="표시 이름" required
        ><KsInput
          v-model="nodeDraft.label"
          required
          aria-label="DDS 노드 표시 이름" /></KsFormItem
      ><KsFormItem label="Deployment 이름"
        ><KsInput
          v-model="nodeDraft.deployment"
          placeholder="비우면 앱 접두사로 자동 생성"
          aria-label="Deployment 이름" /></KsFormItem
      ><KsFormItem label="이미지" required
        ><KsInput
          v-model="nodeDraft.image"
          required
          aria-label="DDS 컨테이너 이미지" /></KsFormItem
      ><KsFormItem label="Replicas" required
        ><KsInput
          v-model="nodeDraft.replicas"
          type="number"
          min="0"
          max="50"
          step="1"
          required
      /></KsFormItem>
      <KsFormItem
        v-if="nodeDraft.template === 'discovery'"
        label="Discovery 포트"
        ><KsInput
          v-model="nodeDraft.port"
          aria-label="Discovery 포트"
          type="number"
          min="1"
          max="65535"
          required
      /></KsFormItem>
      <template v-if="nodeDraft.template !== 'discovery'"
        ><KsFormItem label="장치 ID"
          ><KsInput v-model="nodeDraft.device" /></KsFormItem
        ><KsFormItem label="파이프라인 ID"
          ><KsInput v-model="nodeDraft.pipeline" /></KsFormItem
        ><KsFormItem v-if="nodeDraft.template === 'camera_pub'" label="RTSP URL"
          ><KsInput v-model="nodeDraft.rtsp" /></KsFormItem
        ><KsFormItem v-if="nodeDraft.template === 'app'" label="Python 스크립트"
          ><KsInput v-model="nodeDraft.script" /></KsFormItem
      ></template>
      <KsFormItem label="실행 환경"
        ><KsSelect
          :modelValue="nodeDraft.selector.environment || ''"
          @update:model-value="(value) => setEnvironment(String(value))"
          ><KsOption value="" label="자동" /><KsOption
            value="edge"
            label="Edge" /><KsOption value="cloud" label="Cloud" /></KsSelect
      ></KsFormItem>
      <div class="native-toolbar">
        <KsCheckbox
          v-for="capability in [
            'sensor',
            'camera',
            'preprocessing',
            'inference',
          ]"
          :key="capability"
          :modelValue="nodeDraft.selector[capability] === 'true'"
          @update:model-value="(value) => setCapability(capability, !!value)"
          >{{ capability }}</KsCheckbox
        >
      </div>
      <KsFormItem label="전체 노드 선택자 JSON"
        ><KsInput
          v-model="selectorText"
          type="textarea"
          :rows="4"
          aria-label="DDS 노드 선택자 JSON"
      /></KsFormItem> </KsForm
    ><template #footer
      ><KsButton type="danger" @click="deleteNode">노드 삭제</KsButton
      ><KsButton @click="nodeOpen = false">취소</KsButton
      ><KsButton type="primary" nativeType="submit" form="dds-node-form"
        >설정 저장</KsButton
      ></template
    >
  </KsDrawer>
  <KsDrawer v-model="yamlOpen" title="배포 YAML" class="native-dialog">
    <pre class="native-json">{{ yamlText }}</pre>
  </KsDrawer>
  <ConfirmAction
    v-model="confirmOpen"
    :title="
      confirmation === 'delete'
        ? '배포 앱 삭제'
        : confirmation === 'build'
          ? '이미지 빌드·푸시'
          : 'Gitea 저장 · Argo CD 배포'
    "
    :subject="pendingName"
    :description="
      confirmation === 'delete'
        ? '이 앱의 Argo CD 리소스와 Gitea YAML을 삭제합니다.'
        : confirmation === 'build'
          ? '서버의 소스를 빌드하고 레지스트리에 이미지를 푸시합니다.'
          : '아래 YAML을 Gitea에 저장하고 Argo CD에 앱 생성·갱신을 요청합니다.'
    "
    :danger="confirmation === 'delete'"
    :busy="busy"
    :error="error"
    @confirm="execute"
    ><p v-if="confirmation !== 'delete'" class="native-note">
      {{
        buildFirst && confirmation === "deploy"
          ? "통합 이미지 사용 노드는 빌드한 이미지로 치환합니다. "
          : ""
      }}태그: {{ pendingTag }}
    </p>
    <pre v-if="confirmation === 'deploy'" class="native-json">{{
      pendingYaml
    }}</pre>
  </ConfirmAction>
  <ConfirmAction
    v-model="importOpen"
    title="DDS 편집 내용 교체"
    subject="가져온 YAML"
    description="현재 캔버스를 가져온 YAML의 노드와 연결로 교체합니다."
    @confirm="applyImport"
  />
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import {
  KsButton,
  KsCheckbox,
  KsDrawer,
  KsForm,
  KsFormItem,
  KsInput,
  KsOption,
  KsSelect,
} from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import {
  objectJson,
  pretty,
  request,
  requestKey,
  useAction,
  usePoll,
  write,
} from "./native-api";
import {
  deploymentNode,
  deploymentYaml,
  importDeployment,
  sanitizeName,
  templates,
  usesBuildImage,
  type DeploymentDraft,
  type DeploymentNode,
} from "./platform-model";
import CanvasBoard from "./CanvasBoard.vue";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
import ConfirmAction from "./ConfirmAction.vue";
useRouteContext(computed(() => ({ title: "DDS 배포 편집기" })));
const { busy, error, notice, act } = useAction();
type Connection = {
  configured: boolean;
  buildConfigured: boolean;
  repository?: string;
  branch?: string;
  missing?: string[];
  platforms?: string[];
  imageRepository?: string;
};
const connection = ref<Connection | null>(null),
  appStatus = ref<Record<string, any> | null>(null),
  statusName = ref(""),
  statusError = ref("");
const draft = reactive<DeploymentDraft>({
  name: "flow-project",
  profile: "dds_discovery",
  discoveryIp: "10.254.192.47",
  nodes: [],
  edges: [],
});
const buildFirst = ref(true),
  buildTag = ref(""),
  builtImage = ref("");
const selectedId = ref(""),
  nodeOpen = ref(false),
  nodeDraft = ref<DeploymentNode | null>(null),
  nodeError = ref(""),
  selectorText = ref("{}");
const yamlOpen = ref(false),
  yamlText = ref("");
async function loadConfig() {
  connection.value = await (await request("config", undefined, true)).json();
}
async function loadStatus() {
  const name = sanitizeName(statusName.value);
  if (!/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(name))
    throw new Error("조회할 앱 이름을 입력하세요.");
  try {
    appStatus.value = await (
      await request(`workflow/status/${name}`, undefined, true)
    ).json();
    statusError.value = "";
  } catch (cause) {
    appStatus.value = null;
    statusError.value =
      cause instanceof Error ? cause.message : "상태 조회 실패";
  }
}
function add(
  template: string,
  x = 60 + (draft.nodes.length % 3) * 290,
  y = 60 + Math.floor(draft.nodes.length / 3) * 230,
) {
  if (busy.value) return;
  if (draft.nodes.length >= 128) {
    error.value = "노드는 128개까지 추가할 수 있습니다.";
    return;
  }
  try {
    if (
      template === "discovery" &&
      draft.nodes.some((n) => n.template === template)
    )
      throw new Error("Discovery Server는 하나만 추가할 수 있습니다.");
    const node = deploymentNode(template, requestKey(), x, y);
    node.device =
      template === "camera_pub"
        ? `cam${draft.nodes.length + 1}`
        : `temp${draft.nodes.length + 1}`;
    draft.nodes.push(node);
  } catch (cause) {
    error.value = String(cause instanceof Error ? cause.message : cause);
  }
}
function pipeline(kind: string) {
  const types =
    kind === "camera"
      ? ["camera_pub", "fps_display"]
      : ["sensor_pub", "alarm_service", "alarm_monitor"];
  if (draft.nodes.length + types.length > 128) return;
  const start = draft.nodes.length,
    pipe = `pipe${start + 1}`,
    device = `${kind === "camera" ? "cam" : "temp"}${start + 1}`;
  types.forEach((type, index) => {
    add(type, 60 + index * 290, 60 + Math.ceil(start / 3) * 230);
    const node = draft.nodes[start + index];
    node.pipeline = pipe;
    node.device = device;
    if (index)
      connect(draft.nodes[start + index - 1].id, "DDS", node.id, "DDS");
  });
}
function drag(event: DragEvent, template: string) {
  event.dataTransfer?.setData("application/edgeai-profile", template);
}
function move(id: string, x: number, y: number) {
  if (busy.value) return;
  const node = draft.nodes.find((n) => n.id === id);
  if (node) Object.assign(node, { x, y });
}
function connect(
  source: string,
  sourcePort: string,
  target: string,
  targetPort: string,
) {
  if (
    busy.value ||
    source === target ||
    draft.edges.some((e) => e.source === source && e.target === target)
  )
    return;
  draft.edges.push({
    id: requestKey(),
    source,
    target,
    sourcePort,
    targetPort,
  });
}
function removeEdge(id: string) {
  if (!busy.value) draft.edges = draft.edges.filter((e) => e.id !== id);
}
function arrange() {
  if (!busy.value)
    draft.nodes.forEach((node, index) => {
      node.x = 60 + (index % 3) * 290;
      node.y = 60 + Math.floor(index / 3) * 230;
    });
}
function edit(id: string) {
  if (busy.value) return;
  selectedId.value = id;
  nodeDraft.value = structuredClone(
    JSON.parse(JSON.stringify(draft.nodes.find((n) => n.id === id))),
  );
  selectorText.value = pretty(nodeDraft.value?.selector || {});
  nodeError.value = "";
  nodeOpen.value = true;
}
function setEnvironment(value: string) {
  try {
    const selector = objectJson(selectorText.value);
    if (value) selector.environment = value;
    else delete selector.environment;
    selectorText.value = pretty(selector);
    if (nodeDraft.value)
      nodeDraft.value.selector = selector as Record<string, string>;
  } catch (cause) {
    nodeError.value = cause instanceof Error ? cause.message : "JSON 확인";
  }
}
function setCapability(key: string, value: boolean) {
  try {
    const selector = objectJson(selectorText.value);
    if (value) selector[key] = "true";
    else delete selector[key];
    selectorText.value = pretty(selector);
    if (nodeDraft.value)
      nodeDraft.value.selector = selector as Record<string, string>;
  } catch (cause) {
    nodeError.value = cause instanceof Error ? cause.message : "JSON 확인";
  }
}
function saveNode() {
  if (!nodeDraft.value) return;
  try {
    const selector = objectJson(selectorText.value);
    if (Object.values(selector).some((value) => typeof value !== "string"))
      throw new Error("노드 선택자는 문자열 값이어야 합니다.");
    const index = draft.nodes.findIndex((n) => n.id === selectedId.value);
    if (index < 0) return;
    draft.nodes[index] = {
      ...nodeDraft.value,
      replicas: Number(nodeDraft.value.replicas),
      port: Number(nodeDraft.value.port),
      selector: selector as Record<string, string>,
    };
    nodeOpen.value = false;
  } catch (cause) {
    nodeError.value = cause instanceof Error ? cause.message : "설정 저장 실패";
  }
}
function deleteNode() {
  draft.nodes = draft.nodes.filter((n) => n.id !== selectedId.value);
  draft.edges = draft.edges.filter(
    (e) => e.source !== selectedId.value && e.target !== selectedId.value,
  );
  nodeOpen.value = false;
}
function preview() {
  try {
    yamlText.value = deploymentYaml(
      draft,
      builtImage.value,
      connection.value?.imageRepository,
    );
    yamlOpen.value = true;
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "YAML 생성 실패";
  }
}
function download() {
  try {
    const text = deploymentYaml(
      draft,
      builtImage.value,
      connection.value?.imageRepository,
    );
    const url = URL.createObjectURL(new Blob([text], { type: "text/yaml" })),
      link = document.createElement("a");
    link.href = url;
    link.download = `${sanitizeName(draft.name)}.yaml`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "YAML 생성 실패";
  }
}
const importOpen = ref(false);
let imported: DeploymentDraft | null = null;
async function importFile(event: Event) {
  const input = event.target as HTMLInputElement,
    file = input.files?.[0];
  if (!file) return;
  try {
    if (file.size > 1_000_000)
      throw new Error("YAML 파일은 1MB 이하여야 합니다.");
    imported = importDeployment(await file.text());
    if (draft.nodes.length) importOpen.value = true;
    else applyImport();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "가져오기 실패";
  } finally {
    input.value = "";
  }
}
function applyImport() {
  if (imported) {
    Object.assign(draft, imported);
    builtImage.value = "";
    notice.value = "YAML을 불러왔습니다.";
  }
  imported = null;
  importOpen.value = false;
}
const confirmOpen = ref(false),
  confirmation = ref<"build" | "deploy" | "delete">("deploy"),
  pendingName = ref(""),
  pendingTag = ref(""),
  pendingYaml = ref("");
let pendingDraft: DeploymentDraft | null = null,
  pendingBuild = false;
function prepare(action: "build" | "deploy" | "delete") {
  error.value = "";
  try {
    pendingName.value = sanitizeName(draft.name);
    if (!/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(pendingName.value))
      throw new Error("앱 이름을 확인하세요.");
    pendingTag.value = buildTag.value || `workflow-${Date.now()}`;
    pendingDraft = JSON.parse(JSON.stringify(draft));
    pendingBuild = buildFirst.value;
    pendingYaml.value =
      action === "deploy"
        ? deploymentYaml(
            pendingDraft!,
            builtImage.value,
            connection.value?.imageRepository,
          )
        : "";
    confirmation.value = action;
    confirmOpen.value = true;
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "요청 확인 실패";
  }
}
async function build() {
  const result = await write<{ image: string }>(
    "workflow/build-image",
    "POST",
    { tag: pendingTag.value },
    undefined,
    true,
  );
  if (!result.image) throw new Error("빌드 결과에 이미지가 없습니다.");
  builtImage.value = result.image;
  buildTag.value = pendingTag.value;
  return result.image;
}
async function execute() {
  await act(async () => {
    const name = pendingName.value,
      base = {
        local_directory: `./workspace/${name}`,
        filename: "deployment.yaml",
        repo_path: `${name}/deployment.yaml`,
      };
    if (confirmation.value === "build") {
      await build();
      notice.value = "이미지 빌드·푸시 완료";
    } else if (confirmation.value === "delete") {
      await write(
        "workflow/delete",
        "DELETE",
        { ...base, commit_message: `Delete ${name}` },
        undefined,
        true,
      );
      statusName.value = name;
      notice.value =
        "배포 삭제 요청을 접수했습니다. 리소스 종료 상태를 확인하세요.";
    } else {
      let image = builtImage.value;
      if (
        pendingBuild &&
        pendingDraft!.nodes.some(
          (n) =>
            n.template !== "discovery" &&
            usesBuildImage(n.image, connection.value?.imageRepository),
        )
      )
        image = await build();
      const result = await write<{
        argocd_result?: { status?: string; error?: string };
      }>(
        "workflow/save-and-push",
        "POST",
        {
          ...base,
          content: deploymentYaml(
            pendingDraft!,
            image,
            connection.value?.imageRepository,
          ),
          overwrite_local: true,
          commit_message: `Deploy ${name}`,
        },
        undefined,
        true,
      );
      statusName.value = name;
      if (result.argocd_result?.status === "failed")
        throw new Error(
          `Gitea 저장 완료 · Argo CD 단계 실패: ${result.argocd_result.error}`,
        );
      notice.value =
        "Gitea 저장과 Argo CD 갱신 요청을 완료했습니다. 실제 준비 상태를 확인하세요.";
    }
    confirmOpen.value = false;
    if (statusName.value) await loadStatus();
  });
}
onMounted(() => void act(loadConfig));
usePoll(async () => {
  if (statusName.value && !busy.value) await loadStatus();
}, 5000);
</script>
<style scoped>
.deployment-layout {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: var(--ks-spacing-4);
}
.deployment-library {
  display: flex;
  flex-direction: column;
  gap: var(--ks-spacing-2);
  margin-bottom: var(--ks-spacing-3);
}
.deployment-canvas {
  padding: 0;
  min-width: 0;
  min-height: 700px;
}
fieldset {
  border: 0;
  margin: 0;
  padding: 0;
}
input[type="file"] {
  display: block;
  max-width: 100%;
  margin: var(--ks-spacing-3) 0;
}
@media (max-width: 1000px) {
  .deployment-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .deployment-library {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }
}
</style>
