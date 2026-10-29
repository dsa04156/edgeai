<template>
  <TopNavBar title="성능 프로파일"
    ><template #actions
      ><RouterLink
        :to="{ name: 'edgeai/workflows', params: { tenant: route.params.tenant } }"
        >서비스 워크플로</RouterLink
      ></template
    ></TopNavBar
  >
  <main class="native-page">
    <NativeLoading :busy="loading" />
    <section class="native-panel scheduling-profile-form" aria-labelledby="profile-source">
      <h2 id="profile-source">측정 대상 DAG</h2>
      <p class="native-note">
        발행된 SERVICE BATCH DAG의 artifact 경로를 실제로 측정한 값만 등록합니다.
        등록한 프로파일은 수정할 수 없으며 새 측정은 새 프로파일로 등록합니다.
      </p>
      <NativeFeedback :error="error" />
      <KsForm labelPosition="top" class="native-form" @submit.prevent="submit"
        ><div class="native-grid">
          <KsFormItem label="워크플로"
            ><KsSelect
              :modelValue="workflowId"
              :disabled="busy"
              filterable
              @update:model-value="act(() => selectWorkflow(String($event)))"
              ><KsOption
                v-for="item in workflows"
                :key="item.id"
                :value="item.id"
                :label="`${item.displayName} · ${item.key}`" /></KsSelect></KsFormItem
          ><KsFormItem label="DAG 버전"
            ><KsSelect
              :modelValue="version?.id ?? ''"
              :disabled="busy || !workflowId"
              @update:model-value="selectVersion(String($event))"
              ><KsOption
                v-for="item in versions"
                :key="item.id"
                :value="item.id"
                :label="item.version" /></KsSelect
          ></KsFormItem>
        </div>
        <p v-if="workflowId && !versions.length && !busy" class="native-note">
          발행된 DAG 버전이 없습니다. 워크플로 화면에서 먼저 발행하세요.
        </p>
        <template v-if="version">
          <p class="native-note">
            버전 ID {{ version.id }} · {{ version.dag.tasks.length }}개 작업 ·
            digest {{ version.digest }}
          </p>
          <NativeFeedback :error="ports.unsupported" />
          <template v-if="!ports.unsupported">
            <h2>측정 정보</h2>
            <div class="native-grid">
              <KsFormItem label="측정 시각"
                ><KsInput
                  v-model="draft.measuredAt"
                  :disabled="busy"
                  required
                  placeholder="2026-10-28T09:00:00+09:00"
                  spellcheck="false" /></KsFormItem
              ><KsFormItem label="유효 종료 시각"
                ><KsInput
                  v-model="draft.validUntil"
                  :disabled="busy"
                  required
                  placeholder="2026-11-28T09:00:00+09:00"
                  spellcheck="false" /></KsFormItem
              ><KsFormItem label="측정 증빙 ID"
                ><KsInput
                  v-model="draft.evidenceId"
                  :disabled="busy"
                  required
                  maxlength="200" /></KsFormItem
              ><KsFormItem label="표본 수"
                ><KsInput
                  v-model="draft.sampleCount"
                  :disabled="busy"
                  required
                  type="number"
                  min="1"
                  step="1"
              /></KsFormItem>
            </div>
            <p class="native-note">
              시각은 시간대를 포함한 ISO 8601로 입력한 그대로 보냅니다. 측정 시각은
              현재 이전이어야 하고, 유효 종료 시각부터 새 배치에 쓰지 않습니다.
            </p>
            <KsFormItem label="측정 실행 매개변수 JSON"
              ><KsInput
                v-model="draft.parameters"
                :disabled="busy"
                type="textarea"
                :rows="4"
                spellcheck="false"
            /></KsFormItem>
            <p class="native-note">
              미리보기와 실행은 이 매개변수와 정규화 결과가 같을 때만 프로파일을 사용합니다.
            </p>
            <h2>작업별 측정값</h2>
            <p class="native-note">
              노드별 계산 시간, 연결된 BATCH 입력의 다운로드 시간, 모든 출력의 업로드
              시간(ms)을 입력합니다. 빈 값은 0으로 채우지 않으며 측정한 0은 0으로 입력합니다.
            </p>
            <section
              v-for="task in ports.tasks"
              :key="task.key"
              class="native-panel scheduling-task"
              :aria-label="`${task.key} 측정값`"
            >
              <h3>{{ task.key }}</h3>
              <p class="native-note">
                {{ serviceLabel(task.key) }} · 입력 {{ task.inputs.join(", ") || "없음" }}
                · 출력 {{ task.outputs.join(", ") || "없음" }}
              </p>
              <KsFormItem :label="`${task.key} 측정 노드`"
                ><KsSelect
                  :modelValue="draft.tasks[task.key].nodes"
                  :disabled="busy"
                  multiple
                  filterable
                  @update:model-value="selectNodes(task, $event as string[])"
                  ><KsOption
                    v-for="node in nodes"
                    :key="node.id"
                    :value="node.id"
                    :label="node.name" /></KsSelect
              ></KsFormItem>
              <fieldset
                v-for="node in draft.tasks[task.key].nodes"
                :key="node"
                class="scheduling-cost"
              >
                <legend>{{ task.key }} · {{ nodeName(node) }}</legend>
                <div class="native-grid">
                  <KsFormItem :label="costLabel('computeMs')"
                    ><KsInput
                      v-model="draft.tasks[task.key].costs[node].computeMs"
                      v-bind="msInput" /></KsFormItem
                  ><KsFormItem
                    v-for="port in task.inputs"
                    :key="`in-${port}`"
                    :label="costLabel('downloadMs', port)"
                    ><KsInput
                      v-model="draft.tasks[task.key].costs[node].downloadMs[port]"
                      v-bind="msInput" /></KsFormItem
                  ><KsFormItem
                    v-for="port in task.outputs"
                    :key="`out-${port}`"
                    :label="costLabel('uploadMs', port)"
                    ><KsInput
                      v-model="draft.tasks[task.key].costs[node].uploadMs[port]"
                      v-bind="msInput"
                  /></KsFormItem>
                </div>
              </fieldset>
            </section>
            <details class="native-panel scheduling-import">
              <summary>측정 JSON 가져오기</summary>
              <p class="native-note">
                이 DAG 버전의 실제 측정 문서(1 MiB 이하)를 입력란에 불러옵니다. 등록은
                내용을 확인한 뒤 직접 요청합니다. 오류가 있으면 현재 입력을 유지합니다.
              </p>
              <label class="native-note"
                >측정 JSON 파일<input
                  type="file"
                  accept="application/json,.json"
                  :disabled="busy"
                  @change="importFile"
              /></label>
              <KsFormItem label="측정 JSON 붙여넣기"
                ><KsInput
                  v-model="pasted"
                  :disabled="busy"
                  type="textarea"
                  :rows="4"
                  spellcheck="false"
              /></KsFormItem>
              <KsButton :disabled="busy || !pasted.trim()" @click="importText(pasted)"
                >붙여넣은 JSON 가져오기</KsButton
              >
            </details>
            <KsFormItem label="등록 요청 키"
              ><KsInput :modelValue="key" readonly
            /></KsFormItem>
            <p class="native-note">
              응답이 불확실하면 같은 내용으로 다시 등록하세요. 같은 키로 재전송해 중복
              등록하지 않으며, 내용을 바꾸면 새 키를 사용합니다.
            </p>
            <KsButton nativeType="submit" type="primary" :disabled="busy"
              >성능 프로파일 등록</KsButton
            >
          </template>
        </template>
      </KsForm>
      <p v-if="notice" role="status" class="native-notice">{{ notice }}</p>
    </section>
    <section
      v-if="profile"
      class="native-panel native-detail scheduling-profile-saved"
      aria-labelledby="profile-saved"
    >
      <h2 id="profile-saved" ref="savedHeading" tabindex="-1">등록된 성능 프로파일</h2>
      <dl>
        <div><dt>프로파일 ID</dt><dd>{{ profile.id }}</dd></div>
        <div>
          <dt>DAG 버전</dt>
          <dd>{{ profileVersion }}</dd>
        </div>
        <div>
          <dt>측정 기간</dt>
          <dd>{{ profile.measuredAt }} ~ {{ profile.validUntil }}</dd>
        </div>
        <div>
          <dt>측정 증빙</dt>
          <dd>{{ profile.provenance.evidenceId }} · 표본 {{ profile.provenance.sampleCount }}</dd>
        </div>
        <div><dt>작업</dt><dd>{{ Object.keys(profile.tasks).join(", ") }}</dd></div>
        <div><dt>digest</dt><dd>{{ profile.digest }}</dd></div>
        <div><dt>등록 시각</dt><dd>{{ time(profile.createdAt) }}</dd></div>
      </dl>
      <details>
        <summary>저장된 측정 JSON</summary>
        <pre class="native-json">{{ pretty(profile) }}</pre>
      </details>
      <NativeFeedback :error="previewAction.error.value" />
      <KsButton :disabled="previewAction.busy.value" @click="runPreview"
        >배치 미리보기</KsButton
      >
      <p class="native-note">
        저장된 측정 매개변수로 현재 노드 상태에 대한 최초 배치를 계산합니다. 실행은
        만들지 않습니다.
      </p>
      <section v-if="placement" aria-labelledby="placement-result">
        <h3 id="placement-result">배치 미리보기 결과</h3>
        <p>
          예측 임계 경로 {{ placement.predictedCriticalPathMs }} ms ·
          {{ placement.criticalPath.join(" → ") }}
        </p>
        <table class="scheduling-table">
          <thead>
            <tr><th scope="col">작업</th><th scope="col">선택 노드</th><th scope="col">예측 완료 (ms)</th></tr>
          </thead>
          <tbody>
            <tr v-for="(nodeId, task) in placement.placements" :key="task">
              <td>{{ task }}</td>
              <td>{{ nodeName(nodeId) }}</td>
              <td>{{ placement.predictedCommitMs[task] ?? "—" }}</td>
            </tr>
          </tbody>
        </table>
        <details>
          <summary>후보 노드와 제외 사유</summary>
          <ul>
            <li v-for="(candidates, task) in placement.candidates" :key="task">
              {{ task }}:
              {{
                candidates
                  .map((c) => `${nodeName(c.nodeId)} ${c.eligible ? "적합" : `제외(${c.reasons.join(", ")})`}${c.costMs == null ? "" : ` ${c.costMs} ms`}`)
                  .join(" · ")
              }}
            </li>
          </ul>
        </details>
        <p class="native-note">
          측정값을 순차 합산한 예측이며 실제 실행 성능이 아닙니다. 대기·경합·Pod 시작·이미지
          다운로드는 포함하지 않고, 최종 배치 승인은 kube-scheduler가 합니다. 평가 시각
          {{ time(placement.evaluatedAt) }}.
        </p>
      </section>
    </section>
    <section class="native-panel" aria-labelledby="profile-lookup">
      <h2 id="profile-lookup">등록된 프로파일 열기</h2>
      <KsForm labelPosition="top" @submit.prevent="act(() => open(lookup.trim()))"
        ><KsFormItem label="프로파일 ID"
          ><KsInput
            v-model="lookup"
            :disabled="busy"
            required
            spellcheck="false"
            pattern="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" /></KsFormItem
        ><KsButton nativeType="submit" :disabled="busy">프로파일 열기</KsButton></KsForm
      >
    </section>
  </main>
</template>
<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from "vue";
import { stringify } from "lossless-json";
import { RouterLink, useRoute, useRouter } from "vue-router";
import {
  KsButton,
  KsForm,
  KsFormItem,
  KsInput,
  KsOption,
  KsSelect,
} from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import type { components } from "../lib/api-schema";
import {
  all,
  get,
  pretty,
  profileItems,
  requestKey,
  time,
  useAction,
  write,
  type Schema,
} from "./native-api";
import {
  MAX_PROFILE_BYTES,
  costLabel,
  emptyCosts,
  emptyDraft,
  importDraft,
  profileDocument,
  taskPorts,
  type ProfileDraft,
  type TaskPorts,
} from "./scheduling-profile";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
type Api = components["schemas"];
const route = useRoute(),
  router = useRouter();
useRouteContext(computed(() => ({ title: "성능 프로파일" })));
const { busy, error, notice, act } = useAction();
const previewAction = useAction();
const loading = ref(true),
  workflows = ref<Schema["Workflow"][]>([]),
  services = ref(new Map<string, Schema["ProfileVersion"]>()),
  nodes = ref<Schema["ExecutionNode"][]>([]),
  workflowId = ref(""),
  versions = ref<Schema["WorkflowVersion"][]>([]),
  version = ref<Schema["WorkflowVersion"] | null>(null),
  draft = ref<ProfileDraft>(emptyDraft([])),
  pasted = ref(""),
  key = ref(requestKey()),
  profile = ref<Api["SchedulingProfile"] | null>(null),
  placement = ref<Api["PlacementPreview"] | null>(null),
  lookup = ref(""),
  savedHeading = ref<HTMLElement | null>(null);
// The payload the current key was first sent with; only a different payload gets a new key.
let keyedPayload = "";
// Not native `required`: the submit check names the exact task·node·port that is still unmeasured.
const msInput = { type: "number", min: "0", step: "any", inputmode: "decimal", "aria-required": "true" };
const ports = computed(() =>
  version.value ? taskPorts(version.value, services.value) : { tasks: [], unsupported: "" },
);
const nodeName = (id: string) => nodes.value.find((n) => n.id === id)?.name ?? id;
const serviceLabel = (task: string) => {
  const id = version.value?.dag.tasks.find((t) => t.key === task)?.serviceProfileVersionId;
  const service = id ? services.value.get(id) : undefined;
  return service ? `${service.key}@${service.version}` : String(id);
};
const profileVersion = computed(() => {
  const id = profile.value?.workflowVersionId;
  const known = version.value?.id === id ? version.value : null;
  const name = workflows.value.find((w) => w.id === known?.workflowId);
  return known ? `${name?.displayName ?? ""} · ${known.version} (${id})` : id;
});
async function selectWorkflow(id: string, wanted?: string) {
  workflowId.value = id;
  version.value = null;
  versions.value = [];
  const loaded: Schema["WorkflowVersion"][] = [];
  for (let offset = 0; ; ) {
    const page = await get<Schema["WorkflowDetail"]>(`workflows/${id}?limit=100&offset=${offset}`);
    loaded.push(...page.versions);
    if (page.nextOffset == null || page.nextOffset <= offset) break;
    offset = page.nextOffset;
  }
  versions.value = loaded;
  const match = loaded.find((v) => v.version === wanted);
  if (match) selectVersion(match.id);
}
function selectVersion(id: string) {
  version.value = versions.value.find((v) => v.id === id) ?? null;
  draft.value = emptyDraft(ports.value.tasks);
}
function selectNodes(task: TaskPorts, selected: string[]) {
  const current = draft.value.tasks[task.key];
  for (const node of selected) current.costs[node] ??= emptyCosts(task);
  current.nodes = selected;
}
function importText(text: string) {
  error.value = "";
  try {
    draft.value = importDraft(text, version.value!.id, ports.value.tasks, new Set(nodes.value.map((n) => n.id)));
    notice.value = "측정 JSON을 입력란에 불러왔습니다. 확인 후 등록하세요.";
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "가져오기 실패";
  }
}
async function importFile(event: Event) {
  const input = event.target as HTMLInputElement,
    file = input.files?.[0];
  if (!file) return;
  try {
    if (file.size > MAX_PROFILE_BYTES) {
      error.value = "측정 JSON은 1 MiB 이하여야 합니다.";
      return;
    }
    importText(await file.text());
  } finally {
    input.value = "";
  }
}
async function show(saved: Api["SchedulingProfile"]) {
  profile.value = saved;
  placement.value = null;
  previewAction.error.value = "";
  lookup.value = saved.id;
  await router.replace({ query: { ...route.query, profile: saved.id } });
}
async function open(id: string) {
  await show(await get<Api["SchedulingProfile"]>(`scheduling-profiles/${encodeURIComponent(id)}`));
}
async function submit() {
  await act(async () => {
    const document = profileDocument(version.value!.id, ports.value.tasks, draft.value, nodeName);
    const payload = stringify(document) ?? "";
    if (keyedPayload && payload !== keyedPayload) key.value = requestKey();
    keyedPayload = payload;
    await show(await write<Api["SchedulingProfile"]>("scheduling-profiles", "POST", document, key.value));
    notice.value = "성능 프로파일을 등록했습니다.";
    await nextTick();
    savedHeading.value?.focus();
  });
}
async function runPreview() {
  const saved = profile.value!;
  await previewAction.act(async () => {
    placement.value = null;
    placement.value = await write<Api["PlacementPreview"]>(
      `scheduling-profiles/${saved.id}/placement-preview`,
      "POST",
      { parameters: saved.parameters },
    );
  });
}
onMounted(() => {
  void act(async () => {
    try {
      const [workflowList, serviceList, nodeList] = await Promise.all([
        all<Schema["Workflow"]>("workflows"),
        profileItems("SERVICE"),
        all<Schema["ExecutionNode"]>("nodes"),
      ]);
      workflows.value = workflowList;
      services.value = new Map(serviceList.map((s) => [s.id, s]));
      nodes.value = nodeList;
      if (typeof route.query.workflow === "string")
        await selectWorkflow(route.query.workflow, String(route.query.version ?? ""));
      if (typeof route.query.profile === "string") await open(route.query.profile);
    } finally {
      loading.value = false;
    }
  });
});
</script>
<style scoped>
.scheduling-cost {
  margin-top: var(--ks-spacing-4);
}
.scheduling-cost legend {
  font-size: var(--ks-font-size-sm);
  font-weight: 600;
  margin-bottom: var(--ks-spacing-3);
}
.scheduling-import {
  margin: var(--ks-spacing-4) 0;
}
.scheduling-task + .scheduling-task {
  margin-top: var(--ks-spacing-4);
}
.scheduling-table {
  width: 100%;
  border-collapse: collapse;
}
.scheduling-table th,
.scheduling-table td {
  text-align: left;
  padding: var(--ks-spacing-2) var(--ks-spacing-3);
  border-bottom: 1px solid var(--ks-border-default);
  overflow-wrap: anywhere;
}
input[type="file"] {
  display: block;
  margin: var(--ks-spacing-3) 0;
}
</style>
