<template>
  <TopNavBar title="서비스 워크플로"
    ><template #actions
      ><KsButton :disabled="busy" @click="act(() => load())">새로고침</KsButton
      ><RouterLink
        :to="{
          name: 'edgeai/platform-service',
          params: { tenant: route.params.tenant },
        }"
        >DDS 배포 편집기</RouterLink
      ></template
    ></TopNavBar
  >
  <main class="native-page">
    <NativeLoading :busy="busy" />
    <NativeFeedback :error="error" :notice="notice" />
    <NativeRecords
      title="워크플로"
      :rows="items"
      :columns="[
        { key: 'displayName', label: '이름', width: 240 },
        { key: 'key', label: '키' },
        { key: 'createdAt', label: '등록일' },
      ]"
      :offset="offset"
      :nextOffset="nextOffset"
      :busy="busy"
      @page="(value) => act(() => load(value))"
      ><template #displayName="{ row }"
        ><KsButton link @click="act(() => show(row.id))">{{
          row.displayName
        }}</KsButton></template
      ><template #createdAt="{ row }">{{
        time(row.createdAt)
      }}</template></NativeRecords
    >
    <details class="native-panel" :open="!items.length || undefined">
      <summary>새 워크플로 만들기</summary>
      <KsForm labelPosition="top" @submit.prevent="create"
        ><div class="native-grid">
          <KsFormItem label="워크플로 키"
            ><KsInput
              :disabled="busy"
              v-model="workflowKey"
              required
              maxlength="100"
              pattern="[a-z][a-z0-9]*([._\-][a-z0-9]+)*" /></KsFormItem
          ><KsFormItem label="워크플로 이름"
            ><KsInput
              :disabled="busy"
              v-model="displayName"
              required
              maxlength="128"
          /></KsFormItem>
        </div>
        <KsButton nativeType="submit" type="primary" :disabled="busy"
          >워크플로 생성</KsButton
        ></KsForm
      >
    </details>
    <WorkflowEditor
      :key="editorGeneration"
      v-model="dag"
      :draftKey="draftKey"
      :profiles="profiles"
      :disabled="busy"
    />
    <section v-if="workflow" class="native-panel">
      <h2>{{ workflow.workflow.displayName }}</h2>
      <p class="native-note">
        {{ workflow.workflow.key }} · {{ workflow.workflow.id }}
      </p>
      <NativeRecords
        title="발행된 DAG 버전"
        :rows="workflow.versions"
        :busy="busy"
        :columns="[
          { key: 'version', label: '버전' },
          { key: 'createdAt', label: '발행일' },
          { key: 'digest', label: 'digest', width: 270 },
        ]"
        :offset="versionOffset"
        :nextOffset="workflow.nextOffset"
        :pageSize="10"
        @page="(value) => act(() => show(workflow!.workflow.id, value))"
        ><template #version="{ row }"
          ><KsButton link @click="act(() => selectVersion(row.version))">{{
            row.version
          }}</KsButton></template
        ></NativeRecords
      >
      <KsForm labelPosition="top" @submit.prevent="publish"
        ><KsFormItem label="새 DAG 버전"
          ><KsInput
            :disabled="busy"
            v-model="versionName"
            required
            maxlength="32"
            pattern="[0-9]+\.[0-9]+\.[0-9]+" /></KsFormItem
        ><KsButton nativeType="submit" type="primary" :disabled="busy"
          >DAG 버전 발행</KsButton
        ></KsForm
      >
    </section>
    <section v-if="version" class="native-panel">
      <div class="native-toolbar">
        <h2>선택한 DAG · {{ version.version }}</h2>
        <KsButton :disabled="busy" @click="dag = pretty(version!.dag)"
          >이 버전으로 편집</KsButton
        ><RouterLink
          :to="{
            name: 'edgeai/scheduling-profiles',
            params: { tenant: route.params.tenant },
            query: { workflow: version.workflowId, version: version.version },
          }"
          >이 버전의 성능 프로파일 등록</RouterLink
        >
      </div>
      <p class="native-note">
        {{ version.dag.tasks.length }}개 작업 ·
        {{ version.dag.dependencies.length }}개 연결 · {{ version.digest }}
      </p>
      <details>
        <summary>발행된 DAG JSON</summary>
        <pre class="native-json">{{ pretty(version.dag) }}</pre>
      </details>
      <RunRequestForm
        :key="version.id"
        :version="version"
        :nodes="nodes"
        :disabled="busy"
        @submit="run"
      />
    </section>
  </main>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { parse } from "lossless-json";
import { RouterLink, useRoute, useRouter } from "vue-router";
import {
  KsButton,
  KsForm,
  KsFormItem,
  KsInput,
} from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import {
  get,
  request,
  write,
  writeResponse,
  all,
  profileItems,
  pretty,
  time,
  useAction,
  type Schema,
} from "./native-api";
import { readWorkflowDocument } from "../lib/workflow-graph";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
import NativeRecords from "./NativeRecords.vue";
import WorkflowEditor from "./WorkflowEditor.vue";
import RunRequestForm from "./RunRequestForm.vue";
const route = useRoute(),
  router = useRouter();
useRouteContext(computed(() => ({ title: "서비스 워크플로" })));
const { busy, error, notice, act } = useAction();
const items = ref<Schema["Workflow"][]>([]),
  offset = ref(0),
  nextOffset = ref<number | null>(null),
  workflow = ref<Schema["WorkflowDetail"] | null>(null),
  version = ref<Schema["WorkflowVersion"] | null>(null),
  profiles = ref<Schema["ProfileVersion"][]>([]),
  nodes = ref<Schema["ExecutionNode"][]>([]),
  versionOffset = ref(0),
  editorGeneration = ref(0),
  workflowKey = ref(""),
  displayName = ref(""),
  dag = ref('{"tasks":[],"dependencies":[]}'),
  versionName = ref("1.0.0");
const draftKey = computed(
  () =>
    `edgeai.workflow-draft/v1/${encodeURIComponent(String(route.params.tenant || "main"))}/${workflow.value?.workflow.id || "new"}`,
);
async function load(value = offset.value) {
  const page = await get<Schema["WorkflowPage"]>(
    `workflows?limit=20&offset=${value}`,
  );
  items.value = page.items;
  offset.value = value;
  nextOffset.value = page.nextOffset;
}
async function show(id: string, value = 0, preserveDraft = false) {
  const next = await get<Schema["WorkflowDetail"]>(
    `workflows/${id}?limit=10&offset=${value}`,
  );
  if (workflow.value?.workflow.id !== id) {
    version.value = null;
    if (!preserveDraft) {
      dag.value = '{"tasks":[],"dependencies":[]}';
      editorGeneration.value++;
    }
  }
  workflow.value = next;
  versionOffset.value = value;
  await router.replace({ query: { ...route.query, workflow: id } });
}
async function selectVersion(number: string) {
  if (!workflow.value) return;
  const data = parse(
    await (
      await request(
        `workflows/${workflow.value.workflow.id}?version=${encodeURIComponent(number)}`,
      )
    ).text(),
  ) as Schema["WorkflowDetail"];
  version.value = data.versions[0] || null;
}
async function create() {
  await act(async () => {
    const value = await write<Schema["Workflow"]>("workflows", "POST", {
      key: workflowKey.value,
      displayName: displayName.value,
    });
    await show(value.id, 0, true);
    await load(0);
    notice.value = "워크플로를 생성했습니다. DAG 버전을 발행하세요.";
  });
}
async function publish() {
  if (!workflow.value) return;
  await act(async () => {
    const value = readWorkflowDocument(dag.value, profiles.value).dag;
    const response = await writeResponse(
      `workflows/${workflow.value!.workflow.id}/versions`,
      "POST",
      { version: versionName.value, ...value },
    );
    const saved = parse(await response.text()) as Schema["WorkflowVersion"];
    await show(workflow.value!.workflow.id);
    await selectVersion(saved.version);
    notice.value = "DAG 버전을 발행했습니다.";
  });
}
async function run(body: Record<string, unknown>, key: string) {
  await act(async () => {
    const value = await write<Schema["WorkflowRun"]>(
      "workflow-runs",
      "POST",
      body,
      key,
    );
    await router.push({
      name: "edgeai/runs",
      params: { tenant: route.params.tenant },
      query: { run: value.id },
    });
  });
}
onMounted(() => {
  void act(async () => {
    const refs = await Promise.all([
      profileItems("SERVICE"),
      all<Schema["ExecutionNode"]>("nodes"),
    ]);
    profiles.value = refs[0];
    nodes.value = refs[1];
    await load(0);
    if (typeof route.query.workflow === "string")
      await show(route.query.workflow);
    if (typeof route.query.service === "string")
      dag.value = pretty({
        tasks: [
          {
            key: "service",
            serviceProfileVersionId: route.query.service,
            parameters: {},
          },
        ],
        dependencies: [],
      });
  });
});
</script>
