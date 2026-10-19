<template>
  <div class="native-toolbar">
    <KsButton :disabled="disabled" @click="saveDraft">초안 저장</KsButton>
    <KsButton :disabled="disabled || !savedDraft" @click="restoreDraft"
      >저장한 초안 복구</KsButton
    >
    <KsButton :disabled="disabled || !savedDraft" @click="deleteDraft"
      >저장한 초안 삭제</KsButton
    >
    <span v-if="savedDraft" class="native-note"
      >이 브라우저에 저장 · {{ time(savedDraft.savedAt) }}</span
    >
  </div>
  <NativeFeedback :error="storageError" :notice="storageNotice" />
  <div class="native-workflow-editor">
    <MultiPanelGenericEditorView
      :editorElements="elements"
      :defaultActiveTabs="['topology']"
      ><template #actions
        ><span class="native-note"
          >EdgeAI DAG · {{ dag.tasks.length }}개 작업 ·
          {{ dag.dependencies.length }}개 연결</span
        ></template
      ></MultiPanelGenericEditorView
    >
  </div>
  <KsDrawer v-model="taskOpen" title="작업 설정" class="sm native-dialog"
    ><NativeFeedback :error="error" /><KsForm
      labelPosition="top"
      @submit.prevent="saveTask"
      ><KsFormItem label="작업 키"
        ><KsInput
          v-model="taskKey"
          :disabled="disabled"
          required
          maxlength="100"
          pattern="[a-z][a-z0-9]*([._\-][a-z0-9]+)*" /></KsFormItem
      ><KsFormItem label="실행 모듈"
        ><KsSelect v-model="taskProfile" :disabled="disabled"
          ><KsOption
            v-for="profile in profiles"
            :key="profile.id"
            :value="profile.id"
            :label="`${profile.key} · ${profile.version}`" /></KsSelect></KsFormItem
      ><WorkflowSourceFields
        v-if="inputSource"
        :modelValue="dataSource"
        :disabled="disabled"
        @update:model-value="setDataSource"
      /><KsFormItem label="매개변수 JSON"
        ><KsInput
          v-model="parameters"
          :disabled="disabled"
          type="textarea"
          :rows="7"
      /></KsFormItem>
      <div class="native-toolbar">
        <KsButton nativeType="submit" type="primary" :disabled="disabled"
          >작업 반영</KsButton
        ><KsButton type="danger" :disabled="disabled" @click="removeTask"
          >작업 삭제</KsButton
        >
      </div></KsForm
    ></KsDrawer
  >
</template>
<script setup lang="ts">
import { computed, defineComponent, h, markRaw, ref, watch } from "vue";
import {
  KsButton,
  KsDrawer,
  KsForm,
  KsFormItem,
  KsInput,
  KsOption,
  KsSelect,
} from "@kestra-io/design-system";
import GraphIcon from "vue-material-design-icons/GraphOutline.vue";
import CodeIcon from "vue-material-design-icons/CodeJson.vue";
import MultiPanelGenericEditorView from "kestra-original/components/MultiPanelGenericEditorView.vue";
import type { EditorElement } from "kestra-original/utils/multiPanelTypes";
import {
  readWorkflowDocument,
  arrangeWorkflow,
  connectTasks,
  profilePorts,
  type GraphPositions,
  type WorkflowDag,
} from "../lib/workflow-graph";
import { objectJson, pretty, time, type Schema } from "./native-api";
import WorkflowSourceFields from "./WorkflowSourceFields.vue";
import CanvasBoard from "./CanvasBoard.vue";
import NativeFeedback from "./NativeFeedback.vue";
const librarySearch = ref("");
const props = defineProps<{
  modelValue: string;
  draftKey: string;
  profiles: Schema["ProfileVersion"][];
  disabled?: boolean;
}>();
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
type SavedDraft = {
  format: "edgeai.workflow-draft/v1";
  document: string;
  code: string;
  savedAt: string;
};
const savedDraft = ref<SavedDraft | null>(null),
  storageError = ref(""),
  storageNotice = ref("");
watch(
  () => props.draftKey,
  () => {
    savedDraft.value = null;
    storageError.value = "";
    storageNotice.value = "";
    try {
      const raw = localStorage.getItem(props.draftKey);
      if (!raw) return;
      const saved = JSON.parse(raw);
      if (
        saved?.format !== "edgeai.workflow-draft/v1" ||
        typeof saved.document !== "string" ||
        saved.document.length > 1000000 ||
        typeof saved.code !== "string" ||
        saved.code.length > 1000000 ||
        typeof saved.savedAt !== "string" ||
        !Number.isFinite(Date.parse(saved.savedAt))
      )
        throw new Error("저장한 초안 형식을 확인할 수 없습니다.");
      savedDraft.value = saved;
    } catch {
      storageError.value =
        "저장한 초안을 읽지 못했습니다. 브라우저 저장소를 확인하세요.";
    }
  },
  { immediate: true },
);
const dag = ref<WorkflowDag>({ tasks: [], dependencies: [] }),
  positions = ref<GraphPositions>({}),
  draft = ref(props.modelValue),
  error = ref(""),
  selected = ref("");
function editorDocument() {
  return pretty({
    format: "edgeai.workflow-editor/v1",
    dag: dag.value,
    positions: positions.value,
  });
}
function saveDraft() {
  storageError.value = "";
  storageNotice.value = "";
  try {
    const saved: SavedDraft = {
      format: "edgeai.workflow-draft/v1",
      document: editorDocument(),
      code: draft.value,
      savedAt: new Date().toISOString(),
    };
    if (saved.document.length > 1000000 || saved.code.length > 1000000)
      throw new Error("초안은 그래프와 코드 각각 1MB 이하로 저장하세요.");
    localStorage.setItem(props.draftKey, JSON.stringify(saved));
    savedDraft.value = saved;
    storageNotice.value =
      "초안을 이 브라우저에 저장했습니다. 적용 전 코드도 함께 보관합니다. DAG 발행은 별도로 진행하세요.";
  } catch (cause) {
    storageError.value =
      cause instanceof Error
        ? `초안 저장 실패: ${cause.message}`
        : "초안을 저장하지 못했습니다.";
  }
}
function restoreDraft() {
  if (!savedDraft.value) return;
  storageError.value = "";
  storageNotice.value = "";
  try {
    const parsed = readWorkflowDocument(
      savedDraft.value.document,
      props.profiles,
    );
    dag.value = parsed.dag;
    positions.value = { ...arrangeWorkflow(parsed.dag), ...parsed.positions };
    draft.value = savedDraft.value.code;
    selected.value = "";
    taskOpen.value = false;
    error.value = "";
    emit("update:modelValue", pretty(parsed.dag));
    storageNotice.value =
      "초안의 그래프와 코드를 복구했습니다. 적용 전 코드는 코드 탭에서 확인하고 적용하세요.";
  } catch (cause) {
    storageError.value =
      cause instanceof Error
        ? `초안 복구 실패: ${cause.message}`
        : "초안을 복구하지 못했습니다.";
  }
}
function deleteDraft() {
  storageError.value = "";
  storageNotice.value = "";
  try {
    localStorage.removeItem(props.draftKey);
    savedDraft.value = null;
    storageNotice.value =
      "저장한 초안을 삭제했습니다. 현재 편집 내용은 유지됩니다.";
  } catch {
    storageError.value = "저장한 초안을 삭제하지 못했습니다.";
  }
}
function apply(value: string) {
  try {
    const parsed = readWorkflowDocument(value, props.profiles);
    dag.value = parsed.dag;
    positions.value = {
      ...arrangeWorkflow(parsed.dag),
      ...positions.value,
      ...parsed.positions,
    };
    draft.value = pretty(parsed.dag);
    error.value = "";
    emit("update:modelValue", draft.value);
  } catch (cause) {
    error.value =
      cause instanceof Error ? cause.message : "워크플로 규격을 확인하세요.";
  }
}
watch(
  () => props.modelValue,
  (value) => {
    if (value !== pretty(dag.value)) apply(value);
  },
  { immediate: true },
);
watch(
  () => props.profiles,
  () => {
    if (props.modelValue) apply(props.modelValue);
  },
);
function update(value: WorkflowDag) {
  dag.value = value;
  draft.value = pretty(value);
  emit("update:modelValue", draft.value);
}
function add(profileId: string, x?: number, y?: number) {
  if (props.disabled || dag.value.tasks.length >= 128) return;
  const profile = props.profiles.find((p) => p.id === profileId);
  if (!profile) return;
  let n = 1;
  while (dag.value.tasks.some((t) => t.key === `task${n}`)) n++;
  const key = `task${n}`;
  positions.value[key] = {
    x: x ?? 48 + dag.value.tasks.length * 40,
    y: y ?? 56 + dag.value.tasks.length * 100,
  };
  update({
    ...dag.value,
    tasks: [
      ...dag.value.tasks,
      { key, serviceProfileVersionId: profileId, parameters: {} },
    ],
  });
}
function connect(
  source: string,
  port: string,
  target: string,
  targetPort: string,
) {
  try {
    update(
      connectTasks(dag.value, props.profiles, source, port, target, targetPort),
    );
    error.value = "";
  } catch (cause) {
    error.value =
      cause instanceof Error ? cause.message : "연결하지 못했습니다.";
  }
}
const canvasNodes = computed(() =>
  dag.value.tasks.map((task) => {
    const profile = props.profiles.find(
      (p) => p.id === task.serviceProfileVersionId,
    );
    return {
      id: task.key,
      label: task.key,
      subtitle: profile
        ? `${profile.key} · ${profile.version}`
        : "모듈 조회 필요",
      inputs: profilePorts(profile, "inputs"),
      outputs: profilePorts(profile, "outputs"),
      x: positions.value[task.key]?.x || 0,
      y: positions.value[task.key]?.y || 0,
    };
  }),
);
const taskOpen = ref(false),
  taskKey = ref(""),
  taskProfile = ref(""),
  parameters = ref("{}");
const inputSource = computed(() => {
  const spec = props.profiles.find((p) => p.id === taskProfile.value)?.spec as
    { stream?: { source?: string } } | undefined;
  return spec?.stream?.source === "WORKFLOW_DATA";
});
const dataSource = computed(() => {
  try {
    const source = objectJson(parameters.value).dataSource;
    return source && typeof source === "object" && !Array.isArray(source)
      ? (source as Record<string, unknown>)
      : {};
  } catch {
    return {};
  }
});
function setDataSource(dataSource: Record<string, unknown>) {
  try {
    parameters.value = pretty({ ...objectJson(parameters.value), dataSource });
    error.value = "";
  } catch (cause) {
    error.value =
      cause instanceof Error ? cause.message : "매개변수 JSON 확인 필요";
  }
}
function select(key: string) {
  const task = dag.value.tasks.find((t) => t.key === key);
  if (!task) return;
  selected.value = key;
  taskKey.value = key;
  taskProfile.value = task.serviceProfileVersionId;
  parameters.value = pretty(task.parameters);
  error.value = "";
  taskOpen.value = true;
}
function saveTask() {
  try {
    if (
      dag.value.tasks.some(
        (t) => t.key === taskKey.value && t.key !== selected.value,
      )
    )
      throw new Error("작업 키가 중복됩니다.");
    const key = selected.value,
      next = {
        ...dag.value,
        tasks: dag.value.tasks.map((t) =>
          t.key === key
            ? {
                ...t,
                key: taskKey.value,
                serviceProfileVersionId: taskProfile.value,
                parameters: objectJson(parameters.value),
              }
            : t,
        ),
        dependencies: dag.value.dependencies
          .filter(
            (e) =>
              taskProfile.value ===
                dag.value.tasks.find((t) => t.key === key)
                  ?.serviceProfileVersionId ||
              (e.fromTask !== key && e.toTask !== key),
          )
          .map((e) => ({
            ...e,
            fromTask: e.fromTask === key ? taskKey.value : e.fromTask,
            toTask: e.toTask === key ? taskKey.value : e.toTask,
          })),
      };
    readWorkflowDocument(pretty(next), props.profiles);
    positions.value[taskKey.value] = positions.value[key];
    update(next);
    taskOpen.value = false;
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "작업 설정 오류";
  }
}
function removeTask() {
  update({
    tasks: dag.value.tasks.filter((t) => t.key !== selected.value),
    dependencies: dag.value.dependencies.filter(
      (e) => e.fromTask !== selected.value && e.toTask !== selected.value,
    ),
  });
  taskOpen.value = false;
}
function download() {
  const blob = new Blob([editorDocument()], { type: "application/json" }),
    url = URL.createObjectURL(blob),
    link = document.createElement("a");
  link.href = url;
  link.download = "edgeai-workflow.json";
  link.click();
  URL.revokeObjectURL(url);
}
async function file(event: Event) {
  const input = event.target as HTMLInputElement,
    selected = input.files?.[0];
  if (!selected) return;
  if (selected.size > 1000000) {
    error.value = "워크플로 파일은 1MB 이하로 입력하세요.";
    return;
  }
  apply(await selected.text());
  input.value = "";
}
const visibleProfiles = computed(() =>
  props.profiles.filter((p) =>
    `${p.key} ${p.version}`
      .toLowerCase()
      .includes(librarySearch.value.toLowerCase()),
  ),
);
const topology = markRaw(
  defineComponent({
    setup: () => () =>
      h("div", { class: "native-editor-layout" }, [
        h("aside", { class: "native-module-library" }, [
          h("h3", "실행 모듈"),
          h(KsInput, {
            modelValue: librarySearch.value,
            "onUpdate:modelValue": (value: string | number | undefined) => {
              librarySearch.value = String(value || "");
            },
            placeholder: "모듈 이름 검색",
            "aria-label": "모듈 이름 검색",
          }),
          ...visibleProfiles.value.map((profile) =>
            h(
              KsButton,
              {
                disabled: props.disabled,
                draggable: true,
                onDragstart: (e: DragEvent) =>
                  e.dataTransfer?.setData(
                    "application/edgeai-profile",
                    profile.id,
                  ),
                onClick: () => add(profile.id),
              },
              () => `${profile.key} · ${profile.version}`,
            ),
          ),
          h("label", { class: "native-note" }, [
            "파일 불러오기",
            h("input", {
              type: "file",
              accept: ".json,application/json",
              disabled: props.disabled,
              onChange: file,
            }),
          ]),
          h(
            KsButton,
            { disabled: props.disabled, onClick: download },
            () => "파일 내보내기",
          ),
        ]),
        h("div", { class: "native-editor-canvas" }, [
          h(NativeFeedback, { error: error.value }),
          h(CanvasBoard, {
            nodes: canvasNodes.value,
            edges: dag.value.dependencies.map((e, index) => ({
              id: String(index),
              source: e.fromTask,
              target: e.toTask,
              sourcePort: e.fromPort,
              targetPort: e.toPort,
            })),
            selected: selected.value,
            disabled: props.disabled,
            onSelect: select,
            onMove: (key: string, x: number, y: number) => {
              positions.value[key] = { x, y };
            },
            onConnect: connect,
            onRemoveEdge: (id: string) => {
              if (!props.disabled)
                update({
                  ...dag.value,
                  dependencies: dag.value.dependencies.filter(
                    (_, index) => index !== Number(id),
                  ),
                });
            },
            onArrange: () => {
              positions.value = arrangeWorkflow(dag.value);
            },
            onDrop: add,
          }),
        ]),
      ]),
  }),
);
const code = markRaw(
  defineComponent({
    setup: () => () =>
      h("div", { class: "native-code-panel" }, [
        h(KsInput, {
          type: "textarea",
          rows: 22,
          modelValue: draft.value,
          "onUpdate:modelValue": (value: string | number | undefined) => {
            draft.value = String(value);
          },
          disabled: props.disabled,
          "aria-label": "워크플로 코드",
          spellcheck: false,
        }),
        h(NativeFeedback, { error: error.value }),
        h(
          KsButton,
          { disabled: props.disabled, onClick: () => apply(draft.value) },
          () => "코드 적용",
        ),
      ]),
  }),
);
const elements: EditorElement[] = [
  {
    uid: "topology",
    button: { icon: GraphIcon, label: "그래프" },
    component: topology,
    deserialize: (uid) => (uid === "topology" ? elements[0] : undefined),
  },
  {
    uid: "code",
    button: { icon: CodeIcon, label: "코드" },
    component: code,
    deserialize: (uid) => (uid === "code" ? elements[1] : undefined),
  },
];
</script>
<style lang="scss">
.native-workflow-editor {
  height: 780px;
  min-height: 600px;
  min-width: 0;
  border: 1px solid var(--ks-border-default);
  border-radius: var(--ks-radius-base);
  overflow: hidden;
  background: var(--ks-bg-surface);
}
.native-editor-layout {
  display: flex;
  height: 100%;
  min-width: 0;
}
.native-module-library {
  width: 240px;
  flex-shrink: 0;
  padding: var(--ks-spacing-4);
  display: flex;
  flex-direction: column;
  gap: var(--ks-spacing-3);
  overflow: auto;
  border-right: 1px solid var(--ks-border-default);
}
.native-editor-canvas {
  flex: 1;
  min-width: 0;
  overflow: auto;
}
.native-code-panel {
  padding: var(--ks-spacing-4);
  display: flex;
  flex-direction: column;
  gap: var(--ks-spacing-4);
  height: 100%;
  overflow: auto;
}
.native-module-library input {
  max-width: 100%;
}
@media (max-width: 768px) {
  .native-editor-layout {
    flex-direction: column;
  }
  .native-module-library {
    width: 100%;
    max-height: 180px;
    flex-direction: row;
    flex-wrap: wrap;
  }
  .native-editor-canvas {
    min-height: 400px;
  }
  .native-workflow-editor {
    height: 1000px;
  }
}
</style>
