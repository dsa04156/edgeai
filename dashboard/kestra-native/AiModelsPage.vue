<template>
  <TopNavBar title="AI 모델 관리"
    ><template #actions
      ><KsButton type="primary" :disabled="saving" @click="openRegister">모델 등록</KsButton></template
    ></TopNavBar
  >
  <main class="native-page">
    <form class="native-toolbar" role="search" @submit.prevent="search">
      <KsInput v-model="query" class="model-search" aria-label="모델 검색" placeholder="모델 키·이름·버전·저장소" maxlength="100" clearable />
      <KsButton nativeType="submit" :disabled="listLoading">검색</KsButton>
      <RouterLink class="model-aux-link" :to="{ name: 'edgeai/resource-profiles', params: { tenant: route.params.tenant } }">서비스 자원 프로파일</RouterLink>
    </form>
    <NativeFeedback :error="listError" />
    <NativeLoading :busy="listLoading && !items.length" label="모델 목록을 불러오는 중…" />
    <div v-if="!listLoading && !listError && !items.length" class="model-empty" role="status">
      <template v-if="appliedQuery">
        <strong>"{{ appliedQuery }}"에 맞는 모델이 없습니다.</strong>
      </template>
      <template v-else>
        <strong>등록된 모델이 없습니다.</strong>
        <span class="native-note">Registry에 올린 실행 이미지와 자원 요구량을 묶어 모델로 등록합니다.</span>
        <KsButton type="primary" :disabled="saving" @click="openRegister">모델 등록</KsButton>
      </template>
    </div>
    <NativeRecords
      v-if="items.length"
      title="등록된 모델"
      :rows="items"
      :columns="[
        { key: 'model', label: '모델', width: 200 },
        { key: 'version', label: '버전 · 실행 구성', width: 140 },
        { key: 'image', label: '이미지 · 플랫폼', width: 230 },
        { key: 'request', label: '확정 CPU · 메모리 요청', width: 150 },
        { key: 'accelerators', label: '확정 가속기', width: 170 },
        { key: 'status', label: '상태', width: 100 },
        { key: 'createdAt', label: '등록 시각', width: 170 },
      ]"
      :busy="listLoading"
      :offset="offset > 0 || nextOffset != null ? offset : undefined"
      :nextOffset="nextOffset"
      :pageSize="PAGE"
      @page="page"
    >
      <template #model="{ row }"
        ><KsButton link class="model-link" :aria-label="`${row.modelKey} ${row.modelVersion} ${row.configName} 상세`" @click="open(row.id)">{{ row.displayName }}</KsButton>
        <div class="native-note">{{ row.modelKey }}</div></template
      >
      <template #version="{ row }">{{ row.modelVersion }}<div class="native-note">{{ row.configName }}</div></template>
      <template #image="{ row }"
        >{{ row.image.repository }}
        <div class="native-note model-digest" :title="row.image.digest">{{ shortDigest(row.image.digest) }} · {{ row.image.platform.label }}</div></template
      >
      <template #request="{ row }">{{ confirmedSummary(row.availability)?.request ?? "—" }}</template>
      <template #accelerators="{ row }"
        ><template v-if="confirmedSummary(row.availability)"
          ><div v-for="a in confirmedSummary(row.availability)!.accelerators" :key="a.resourceName">
            {{ a.resourceName }}<div class="native-note">{{ a.amount }}</div>
          </div>
          <template v-if="!confirmedSummary(row.availability)!.accelerators.length">없음</template></template
        ><template v-else>—</template></template
      >
      <template #status="{ row }"
        ><span v-if="row.availability" :class="['model-status', `model-status--${availabilityKey(row)}`]">{{ AVAILABILITY_LABEL[availabilityKey(row)] }}</span
        ><span v-else :class="['model-status', `model-status--${row.measurementStatus}`]">{{ MEASUREMENT_LABEL[row.measurementStatus] }}</span></template
      >
      <template #createdAt="{ row }">{{ time(row.createdAt) }}</template>
    </NativeRecords>

    <section v-if="selected || detailLoading || detailError" class="native-panel native-detail" aria-labelledby="model-detail">
      <div class="native-toolbar model-detail-head">
        <h2 id="model-detail" ref="detailHeading" tabindex="-1">
          {{ selected ? `${selected.displayName} · ${selected.modelVersion} · ${selected.configName}` : "모델 상세" }}
        </h2>
        <KsButton @click="closeDetail">상세 닫기</KsButton>
      </div>
      <NativeFeedback :error="detailError" />
      <NativeLoading :busy="detailLoading" label="모델 상세를 불러오는 중…" />
      <template v-if="selected && !detailLoading">
        <p v-if="selected.description" class="native-note">{{ selected.description }}</p>
        <dl>
          <div><dt>모델 키</dt><dd>{{ selected.modelKey }}</dd></div>
          <div><dt>이미지</dt><dd><code>{{ selected.image.reference }}</code></dd></div>
          <div>
            <dt>플랫폼</dt>
            <dd>{{ selected.image.platform.label }} · {{ selected.image.kind === "INDEX" ? "여러 플랫폼 index에서 선택" : "단일 이미지" }}</dd>
          </div>
          <div><dt>등록 시각</dt><dd>{{ time(selected.createdAt) }}</dd></div>
        </dl>
        <ModelProfilingPanel :modelId="selected.id" :availability="selected.availability ?? null" @changed="refreshSelected" />
        <details v-if="selected.requirements" class="model-guide">
          <summary>등록 시 선언한 요구량(참고 · 사용 가능 판정에 쓰지 않음)</summary>
          <p class="native-note">
            <span :class="['model-status', `model-status--${selected.measurementStatus}`]">{{ MEASUREMENT_LABEL[selected.measurementStatus] }}</span>
            {{ SOURCE_LABEL[selected.requirements.source] }}
          </p>
          <div class="model-scroll">
          <table class="model-table" aria-label="등록 시 선언 요구량">
            <thead>
              <tr><th scope="col">자원</th><th scope="col">최소</th><th scope="col">요청</th><th scope="col">최대</th><th scope="col">근거</th></tr>
            </thead>
            <tbody>
              <tr>
                <th scope="row">CPU</th>
                <td>{{ cpuQuantity(selected.requirements.resources.cpu.minMillicores) }}</td>
                <td>{{ cpuQuantity(selected.requirements.resources.cpu.requestMillicores) }}</td>
                <td>{{ cpuQuantity(selected.requirements.resources.cpu.maxMillicores) }}</td>
                <td>{{ BASIS_LABEL[selected.requirements.resources.cpu.basis] }}</td>
              </tr>
              <tr>
                <th scope="row">메모리</th>
                <td>{{ memoryQuantity(selected.requirements.resources.memory.minBytes) }}</td>
                <td>{{ memoryQuantity(selected.requirements.resources.memory.requestBytes) }}</td>
                <td>{{ memoryQuantity(selected.requirements.resources.memory.maxBytes) }}</td>
                <td>{{ BASIS_LABEL[selected.requirements.resources.memory.basis] }}</td>
              </tr>
              <tr v-for="a in selected.requirements.resources.accelerators ?? []" :key="a.resourceName">
                <th scope="row">{{ a.kind }} · {{ a.resourceName }}<div class="native-note">{{ [a.vendor, ...(a.models ?? [])].filter(Boolean).join(" · ") }}</div></th>
                <td>{{ a.min }}</td>
                <td>{{ a.request }}</td>
                <td>{{ a.max }}</td>
                <td>사용자 지정 · {{ a.unit }}{{ a.deviceMemoryBytes ? ` · 장치 메모리 ${memoryQuantity(a.deviceMemoryBytes)}` : "" }}</td>
              </tr>
            </tbody>
          </table>
          </div>
        </details>
        <details class="model-guide">
          <summary>실행 구성</summary>
          <dl>
            <div><dt>조회 태그</dt><dd>{{ selected.image.sourceTag ?? "—" }} <span class="native-note">(등록은 digest로 고정)</span></dd></div>
            <div><dt>실행 명령</dt><dd><code>{{ selected.execution.command?.join(" ") ?? "이미지 기본값" }}</code></dd></div>
            <div><dt>인자</dt><dd><code>{{ selected.execution.args?.join(" ") ?? "이미지 기본값" }}</code></dd></div>
            <div><dt>입력 조건</dt><dd>{{ entries(selected.inputConditions) || "—" }}</dd></div>
            <div><dt>런타임</dt><dd>{{ selected.runtime ? `${selected.runtime.name} ${selected.runtime.version ?? ""}` : "—" }}</dd></div>
          </dl>
        </details>
        <details class="model-guide">
          <summary>기술 상세</summary>
          <dl>
            <div><dt>항목 ID</dt><dd>{{ selected.id }}</dd></div>
            <div><dt>media type</dt><dd>{{ selected.image.mediaType }}</dd></div>
            <div v-if="selected.image.kind === 'INDEX'"><dt>플랫폼 manifest</dt><dd><code>{{ selected.image.platformManifestDigest }}</code></dd></div>
            <div><dt>설정 digest</dt><dd><code>{{ selected.image.configDigest }}</code></dd></div>
            <div><dt>검증 시각</dt><dd>{{ time(selected.image.verifiedAt) }} · 레이어 {{ formatValue(selected.image.layersSize, "bytes") }}</dd></div>
            <div><dt>문서 digest</dt><dd><code>{{ selected.digest }}</code></dd></div>
          </dl>
          <ul class="native-note">
            <li v-for="l in selected.limitations" :key="l">{{ l }}</li>
          </ul>
        </details>
      </template>
    </section>
  </main>

  <KsDrawer
    v-model="registering"
    title="모델 등록"
    class="native-dialog"
    :beforeClose="closeRegister"
    :showClose="!saving"
    :closeOnClickModal="!saving"
    :closeOnPressEscape="!saving"
    @opened="registerOpened"
    @closed="registerClosed"
  >
    <KsForm id="model-register-form" labelPosition="top" size="large" class="native-form" @submit.prevent="save">
      <h2>기본 정보</h2>
      <div class="native-grid">
        <KsFormItem label="모델 키" required
          ><KsInput v-model="draft.modelKey" :disabled="saving" maxlength="100" spellcheck="false" placeholder="예 forklift-detector"
        /></KsFormItem>
        <KsFormItem label="표시 이름" required><KsInput v-model="draft.displayName" :disabled="saving" maxlength="200" placeholder="예 지게차 탐지" /></KsFormItem>
        <KsFormItem label="모델 버전" required
          ><KsInput v-model="draft.modelVersion" :disabled="saving" maxlength="64" spellcheck="false" placeholder="예 1.0.0"
        /></KsFormItem>
        <KsFormItem label="실행 구성명" required
          ><KsInput v-model="draft.configName" :disabled="saving" maxlength="63" spellcheck="false" placeholder="default"
        /></KsFormItem>
      </div>

      <h2>실행 이미지</h2>
      <NativeFeedback :error="registry && !registry.configured ? '서버에 Registry가 설정되지 않아 이미지를 조회·등록할 수 없습니다.' : imageError" />
      <div class="native-grid">
        <KsFormItem label="저장소" required
          ><KsSelect
            :modelValue="repository"
            :disabled="saving || !registry?.configured"
            filterable
            allowCreate
            defaultFirstOption
            aria-label="저장소"
            placeholder="선택하거나 입력 후 Enter"
            :loading="reposLoading"
            @update:model-value="selectRepository(String($event ?? ''))"
            ><KsOption v-for="r in repos" :key="r" :value="r" :label="r" /></KsSelect
        ></KsFormItem>
        <KsFormItem label="태그 또는 digest" required
          ><KsSelect
            :modelValue="reference"
            :disabled="saving || !repository"
            filterable
            allowCreate
            defaultFirstOption
            aria-label="태그 또는 digest"
            placeholder="선택하거나 입력 후 Enter"
            :loading="tagsLoading"
            @update:model-value="selectReference(String($event ?? ''))"
            ><KsOption v-for="t in tags" :key="t" :value="t" :label="t" /></KsSelect
        ></KsFormItem>
      </div>
      <div v-if="reposNext != null || tagsNext != null || resolving || (reference && !resolved)" class="native-toolbar">
        <KsButton v-if="reposNext != null" :disabled="saving || reposLoading" @click="loadRepos(true)">저장소 더 보기</KsButton>
        <KsButton v-if="tagsNext != null" :disabled="saving || tagsLoading" @click="loadTags(true)">태그 더 보기</KsButton>
        <span v-if="resolving" role="status" class="native-note">manifest·digest를 확인하는 중…</span>
        <KsButton v-else-if="reference && !resolved" :disabled="saving" @click="resolve">이미지 다시 확인</KsButton>
      </div>
      <template v-if="resolved">
        <dl class="model-resolved" aria-label="확인한 이미지">
          <div><dt>digest</dt><dd><code>{{ resolved.digest }}</code></dd></div>
          <div><dt>종류</dt><dd>{{ resolved.kind === "INDEX" ? "여러 플랫폼 index — 고른 플랫폼의 manifest도 고정" : "단일 이미지 manifest" }}</dd></div>
        </dl>
        <KsFormItem label="플랫폼" required
          ><KsSelect :modelValue="draft.platform" :disabled="saving" aria-label="플랫폼" placeholder="실행할 플랫폼 선택" @update:model-value="draft.platform = String($event)"
            ><KsOption
              v-for="p in resolved.platforms"
              :key="p.manifestDigest"
              :value="p.label"
              :disabled="!p.runnable"
              :label="`${p.label}${p.runnable ? '' : ' (등록 불가)'} · ${shortDigest(p.manifestDigest)}`" /></KsSelect
        ></KsFormItem>
        <p v-if="!resolved.platforms.length" class="native-note">이 index에는 실행할 수 있는 이미지 플랫폼이 없습니다.</p>
      </template>
      <details v-if="registry?.configured" class="model-guide">
        <summary>Registry {{ registry.host }}에 이미지 올리는 방법</summary>
        <pre class="native-json">{{ pushCommands(registry.host!, repository || undefined) }}</pre>
      </details>

      <details class="model-guide" :open="advancedOpen" @toggle="advancedOpen = ($event.target as HTMLDetailsElement).open">
        <summary>실행 조건·설명(선택)</summary>
        <KsFormItem label="설명"><KsInput v-model="draft.description" :disabled="saving" type="textarea" :rows="2" maxlength="2000" /></KsFormItem>
        <div class="native-grid">
          <KsFormItem label="실행 명령(선택, 한 줄에 하나)"
            ><KsInput v-model="draft.command" :disabled="saving" type="textarea" :rows="3" spellcheck="false" placeholder="비우면 이미지 기본값"
          /></KsFormItem>
          <KsFormItem label="인자(선택, 한 줄에 하나)"
            ><KsInput v-model="draft.args" :disabled="saving" type="textarea" :rows="3" spellcheck="false" placeholder="비우면 이미지 기본값"
          /></KsFormItem>
        </div>
        <KsFormItem label="입력 조건(한 줄에 key=value)"
          ><KsInput v-model="draft.conditions" :disabled="saving" type="textarea" :rows="2" spellcheck="false" placeholder="예 resolution=640x640"
        /></KsFormItem>
        <div class="native-grid">
          <KsFormItem label="런타임 이름"><KsInput v-model="draft.runtimeName" :disabled="saving" maxlength="64" placeholder="예 onnxruntime" /></KsFormItem>
          <KsFormItem label="런타임 버전"><KsInput v-model="draft.runtimeVersion" :disabled="saving" maxlength="64" /></KsFormItem>
        </div>
      </details>
    </KsForm>
    <template #footer
      ><div class="model-footer">
        <NativeFeedback :error="saveError" />
        <div class="native-toolbar model-footer-actions">
          <KsButton :disabled="saving" @click="registering = false">닫기(입력 유지)</KsButton
          ><KsButton type="primary" nativeType="submit" form="model-register-form" :loading="saving" :disabled="saving">{{
            saving ? "등록하는 중…" : "등록"
          }}</KsButton>
        </div>
      </div></template
    >
  </KsDrawer>
</template>
<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { KsButton, KsDrawer, KsForm, KsFormItem, KsInput, KsOption, KsSelect } from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import type { components } from "../lib/api-schema";
import { get, requestKey, time, write } from "./native-api";
import { BASIS_LABEL, cpuQuantity, formatValue, memoryQuantity } from "./resource-profile";
import {
  REPOSITORY,
  SOURCE_LABEL,
  emptyModelDraft,
  pushCommands,
  registrationDocument,
  shortDigest,
  type ModelEntry,
  type ResolvedImage,
} from "./ai-model";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
import NativeRecords from "./NativeRecords.vue";
import ModelProfilingPanel from "./ModelProfilingPanel.vue";
import { AVAILABILITY_LABEL, confirmedSummary } from "./model-profiling";
type Api = components["schemas"];
const PAGE = 20;
const MEASUREMENT_LABEL: Record<string, string> = { MEASURED: "실측", PARTIAL: "일부 실측", UNMEASURED: "미측정" };
const message = (cause: unknown, fallback: string) => (cause instanceof Error ? cause.message : fallback);

const route = useRoute(),
  router = useRouter();
useRouteContext(computed(() => ({ title: "AI 모델 관리" })));

// ---- list and detail -------------------------------------------------------------------------------------------
const items = ref<ModelEntry[]>([]),
  query = ref(""),
  appliedQuery = ref(""),
  offset = ref(0),
  nextOffset = ref<number | null>(null),
  listLoading = ref(true),
  listError = ref("");
let listGeneration = 0;
async function loadList() {
  const generation = ++listGeneration;
  listLoading.value = true;
  listError.value = "";
  try {
    const params = new URLSearchParams({ limit: String(PAGE), offset: String(offset.value) });
    if (appliedQuery.value) params.set("q", appliedQuery.value);
    const result = await get<Api["AiModelPage"]>(`ai-models?${params}`);
    if (generation !== listGeneration) return;
    items.value = result.items;
    nextOffset.value = result.nextOffset ?? null;
  } catch (cause) {
    if (generation === listGeneration) listError.value = message(cause, "모델 목록 조회 실패");
  } finally {
    if (generation === listGeneration) listLoading.value = false;
  }
}
function search() {
  appliedQuery.value = query.value.trim();
  offset.value = 0;
  void loadList();
}
function page(value: number) {
  offset.value = value;
  void loadList();
}
const availabilityKey = (m: ModelEntry) => (m.availability?.status === "READY" ? "READY" : (m.availability?.reason ?? "NO_MEASUREMENT"));
const entries = (map: Record<string, string>) => Object.entries(map).map(([k, v]) => `${k}=${v}`).join(", ");

const selected = ref<ModelEntry | null>(null),
  detailLoading = ref(false),
  detailError = ref(""),
  detailHeading = ref<HTMLElement | null>(null);
let detailGeneration = 0;
async function open(id: string, entry?: ModelEntry) {
  const generation = ++detailGeneration;
  detailError.value = "";
  void router.replace({ query: { ...route.query, model: id } });
  if (entry) {
    // A newer known entry wins over any older detail request still loading.
    selected.value = entry;
    detailLoading.value = false;
  } else {
    detailLoading.value = true;
    try {
      const result = await get<ModelEntry>(`ai-models/${encodeURIComponent(id)}`);
      if (generation !== detailGeneration) return;
      selected.value = result;
    } catch (cause) {
      if (generation !== detailGeneration) return;
      selected.value = null;
      detailError.value = message(cause, "모델 상세 조회 실패");
    } finally {
      if (generation === detailGeneration) detailLoading.value = false;
    }
  }
  await nextTick();
  detailHeading.value?.focus();
}
/** After a measurement changes, reread the open model (its availability) without hiding the detail. */
async function refreshSelected() {
  const id = selected.value?.id;
  if (!id) return;
  const generation = ++detailGeneration;
  void loadList();
  try {
    const result = await get<ModelEntry>(`ai-models/${encodeURIComponent(id)}`);
    if (generation === detailGeneration) selected.value = result;
  } catch {
    // The panel already shows the measurement itself; the badge catches up on the next read.
  }
}
function closeDetail() {
  ++detailGeneration;
  selected.value = null;
  detailError.value = "";
  detailLoading.value = false;
  const { model: _model, ...rest } = route.query;
  void router.replace({ query: rest });
}

// ---- registration ----------------------------------------------------------------------------------------------
const registering = ref(false),
  advancedOpen = ref(false),
  registry = ref<Api["AiModelRegistryStatus"] | null>(null),
  draft = reactive(emptyModelDraft()),
  saving = ref(false),
  saveError = ref(""),
  key = ref(requestKey());
let keyedPayload = "";
// Closing keeps the draft and request key; focus goes back to the button that opened the drawer unless a save
// already moved it to the new detail.
let registerOrigin: HTMLElement | null = null,
  returnFocus = true;
function openRegister(event: Event) {
  registerOrigin = event.currentTarget as HTMLElement;
  returnFocus = true;
  registering.value = true;
  if (registry.value?.configured && !repos.value.length && !reposLoading.value) void loadRepos(false);
}
function closeRegister(done: () => void) {
  if (!saving.value) done();
}
function registerOpened() {
  document.querySelector<HTMLInputElement>("#model-register-form input")?.focus();
}
function registerClosed() {
  if (returnFocus && registerOrigin?.isConnected) registerOrigin.focus();
}

const repos = ref<string[]>([]),
  reposNext = ref<string | null>(null),
  reposLoading = ref(false),
  repository = ref(""),
  tags = ref<string[]>([]),
  tagsNext = ref<string | null>(null),
  tagsLoading = ref(false),
  reference = ref(""),
  resolved = ref<ResolvedImage | null>(null),
  resolving = ref(false),
  imageError = ref("");
let repoGeneration = 0,
  tagGeneration = 0,
  imageGeneration = 0;
async function loadRepos(more: boolean) {
  const generation = ++repoGeneration;
  reposLoading.value = true;
  imageError.value = "";
  try {
    const params = new URLSearchParams({ limit: "50" });
    if (more && reposNext.value) params.set("last", reposNext.value);
    const result = await get<Api["AiModelRegistryPage"]>(`ai-models/registry/repositories?${params}`);
    if (generation !== repoGeneration) return;
    repos.value = more ? [...repos.value, ...result.items.filter((r) => !repos.value.includes(r))] : result.items;
    reposNext.value = result.next ?? null;
  } catch (cause) {
    if (generation === repoGeneration) imageError.value = message(cause, "저장소 목록 조회 실패");
  } finally {
    if (generation === repoGeneration) reposLoading.value = false;
  }
}
function selectRepository(value: string) {
  if (!REPOSITORY.test(value) || value.length > 255) {
    imageError.value = "저장소 이름은 소문자·숫자와 . _ - / 구분자만 사용합니다(예 edgeai/detector, host 제외).";
    return;
  }
  imageError.value = "";
  repository.value = value;
  reference.value = "";
  resolved.value = null;
  draft.platform = "";
  ++imageGeneration;
  resolving.value = false;
  tags.value = [];
  tagsNext.value = null;
  void loadTags(false);
}
async function loadTags(more: boolean) {
  const generation = ++tagGeneration;
  const repo = repository.value;
  tagsLoading.value = true;
  try {
    const params = new URLSearchParams({ repository: repo, limit: "50" });
    if (more && tagsNext.value) params.set("last", tagsNext.value);
    const result = await get<Api["AiModelRegistryPage"]>(`ai-models/registry/tags?${params}`);
    if (generation !== tagGeneration || repo !== repository.value) return;
    tags.value = more ? [...tags.value, ...result.items.filter((t) => !tags.value.includes(t))] : result.items;
    tagsNext.value = result.next ?? null;
  } catch (cause) {
    if (generation === tagGeneration) imageError.value = message(cause, "태그 조회 실패");
  } finally {
    if (generation === tagGeneration) tagsLoading.value = false;
  }
}
/** Choosing or typing a tag/digest checks it at once; the newer lookup invalidates any still in flight. */
function selectReference(value: string) {
  reference.value = value.trim();
  resolved.value = null;
  draft.platform = "";
  ++imageGeneration;
  resolving.value = false;
  if (reference.value) void resolve();
}
async function resolve() {
  const generation = ++imageGeneration;
  const repo = repository.value,
    target = reference.value;
  resolving.value = true;
  imageError.value = "";
  resolved.value = null;
  draft.platform = "";
  try {
    const result = await get<ResolvedImage>(
      `ai-models/registry/manifest?${new URLSearchParams({ repository: repo, reference: target })}`,
    );
    // A newer repository/tag choice or lookup wins over this slower answer.
    if (generation !== imageGeneration) return;
    resolved.value = result;
    const runnable = result.platforms.filter((p) => p.runnable);
    if (runnable.length === 1) draft.platform = runnable[0].label;
  } catch (cause) {
    if (generation === imageGeneration) imageError.value = message(cause, "이미지 확인 실패");
  } finally {
    if (generation === imageGeneration) resolving.value = false;
  }
}

async function save() {
  if (saving.value) return;
  saving.value = true;
  saveError.value = "";
  try {
    const document = registrationDocument(draft, resolved.value);
    const payload = JSON.stringify(document);
    // The same payload keeps its key so an uncertain response can be retried without a duplicate.
    if (keyedPayload && payload !== keyedPayload) key.value = requestKey();
    keyedPayload = payload;
    const saved = await write<ModelEntry>("ai-models", "POST", document, key.value);
    Object.assign(draft, emptyModelDraft());
    resolved.value = null;
    reference.value = "";
    key.value = requestKey();
    keyedPayload = "";
    returnFocus = false;
    registering.value = false;
    advancedOpen.value = false;
    appliedQuery.value = query.value = "";
    offset.value = 0;
    await loadList();
    await open(saved.id, saved);
  } catch (cause) {
    // The draft stays as entered so the user can correct it.
    saveError.value = message(cause, "모델 등록 실패");
  } finally {
    saving.value = false;
  }
}

onMounted(() => {
  void get<Api["AiModelRegistryStatus"]>("ai-models/registry")
    .then((status) => (registry.value = status))
    .catch(() => (registry.value = { configured: false, host: null }));
  void loadList();
  if (typeof route.query.model === "string") void open(route.query.model);
});
</script>
<style scoped>
.model-search {
  flex: 1 1 240px;
  max-width: 480px;
}
.model-empty {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--ks-spacing-3);
  padding: var(--ks-spacing-6) var(--ks-spacing-5);
  background: var(--ks-bg-base);
  border: 1px solid var(--ks-border-default);
  border-radius: var(--ks-radius-base);
}
.model-aux-link {
  margin-left: auto;
}
.model-detail-head {
  justify-content: space-between;
}
.model-detail-head h2 {
  margin: 0;
}
.model-detail-head h2:focus {
  outline: 2px solid var(--ks-border-focus);
  outline-offset: 2px;
  border-radius: var(--ks-radius-xs);
}
.model-scroll {
  /* Positioning context: the table scrolls inside the panel on narrow screens. */
  position: relative;
  overflow-x: auto;
  margin: var(--ks-spacing-3) 0;
}
.model-table {
  width: 100%;
  min-width: 560px;
  border-collapse: collapse;
  font-size: var(--ks-font-size-sm);
}
.model-table th,
.model-table td {
  text-align: left;
  vertical-align: top;
  padding: var(--ks-spacing-2) var(--ks-spacing-3);
  border-bottom: 1px solid var(--ks-border-default);
  overflow-wrap: anywhere;
}
.model-table td {
  overflow-wrap: normal;
  word-break: keep-all;
}
.model-table thead th {
  color: var(--ks-text-secondary);
  font-weight: 600;
  white-space: nowrap;
}
.model-digest {
  font-family: monospace;
}
code {
  font-size: var(--ks-font-size-xs);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-all;
}
.model-resolved {
  margin: 0 0 var(--ks-spacing-4);
  font-size: var(--ks-font-size-sm);
}
.model-resolved > div {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr);
  gap: var(--ks-spacing-3);
  margin: var(--ks-spacing-2) 0;
}
.model-resolved dt {
  color: var(--ks-text-secondary);
}
.model-resolved dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.model-guide {
  margin: var(--ks-spacing-3) 0;
}
.model-guide > summary {
  min-height: 44px;
  display: flex;
  align-items: center;
  gap: var(--ks-spacing-2);
  cursor: pointer;
  list-style: none;
  font-size: var(--ks-font-size-sm);
  font-weight: var(--ks-font-weight-semibold);
}
.model-guide > summary::-webkit-details-marker {
  display: none;
}
.model-guide > summary::before {
  content: "";
  width: 6px;
  height: 6px;
  border-right: 1.5px solid var(--ks-text-secondary);
  border-bottom: 1.5px solid var(--ks-text-secondary);
  transform: rotate(-45deg);
  transition: transform 0.15s cubic-bezier(0.23, 1, 0.32, 1);
  flex: none;
}
.model-guide[open] > summary::before {
  transform: rotate(45deg);
}
.model-guide > summary:focus-visible {
  outline: 2px solid var(--ks-border-focus);
  outline-offset: 2px;
  border-radius: var(--ks-radius-xs);
}
.model-guide[open] > summary {
  margin-bottom: var(--ks-spacing-3);
}
.native-form fieldset.model-range {
  border-top: 1px solid var(--ks-border-default);
  padding-top: var(--ks-spacing-3);
  margin-bottom: var(--ks-spacing-4);
}
.model-range legend {
  float: left;
  width: 100%;
  margin-bottom: var(--ks-spacing-2);
  font-size: var(--ks-font-size-sm);
  font-weight: var(--ks-font-weight-semibold);
}
.native-form h2 {
  font-weight: var(--ks-font-weight-semibold);
}
.native-form h2:first-child {
  margin-top: 0;
}
.model-link {
  padding: 0;
  justify-content: flex-start;
  font-weight: var(--ks-font-weight-semibold);
}
.model-range-grid {
  clear: both;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 var(--ks-spacing-3);
}
.model-range :deep(.kel-form-item) {
  margin-bottom: var(--ks-spacing-3);
}
.model-footer {
  display: flex;
  flex-direction: column;
  gap: var(--ks-spacing-3);
  text-align: left;
}
.model-footer-actions {
  justify-content: flex-end;
}
.model-status {
  display: inline-block;
  margin-right: var(--ks-spacing-2);
  padding: 0 var(--ks-spacing-2);
  border-radius: var(--ks-radius-base);
  font-size: var(--ks-font-size-xs);
  background: var(--ks-bg-hover);
  color: var(--ks-text-secondary);
}
.model-status--READY,
.model-status--MEASURED {
  color: var(--ks-text-success);
}
.model-status--PARTIAL {
  color: var(--ks-text-warning);
}
@media (max-width: 768px) {
  .model-table {
    min-width: 0;
  }
  .model-footer-actions > * {
    flex: 1;
  }
}
</style>
