<template>
  <TopNavBar title="AI 모델 관리"
    ><template #actions
      ><RouterLink :to="{ name: 'edgeai/resource-profiles', params: { tenant: route.params.tenant } }">자원 프로파일</RouterLink></template
    ></TopNavBar
  >
  <main class="native-page">
    <section class="native-panel" aria-labelledby="model-list">
      <h2 id="model-list">등록된 모델</h2>
      <p class="native-note">
        모델 버전·실행 구성별 실행 이미지와 자원 요구량을 보관합니다. 등록한 항목은 바꾸지 않고 새 버전이나 실행 구성으로 추가합니다.
      </p>
      <form class="native-toolbar" role="search" @submit.prevent="search">
        <KsInput v-model="query" class="model-search" aria-label="모델 검색" placeholder="모델 키·이름·버전·저장소" maxlength="100" clearable />
        <KsButton nativeType="submit" :disabled="listLoading">검색</KsButton>
        <KsButton type="primary" :disabled="saving" :aria-expanded="registering ? 'true' : 'false'" aria-controls="model-register" @click="toggleRegister">{{
          registering ? "등록 양식 닫기" : "모델 등록"
        }}</KsButton>
      </form>
      <NativeFeedback :error="listError" />
      <NativeLoading :busy="listLoading && !items.length" label="모델 목록을 불러오는 중…" />
      <p v-if="!listLoading && !listError && !items.length" class="native-note" role="status">
        {{ appliedQuery ? `"${appliedQuery}"에 맞는 모델이 없습니다.` : "등록된 모델이 없습니다. ‘모델 등록’에서 Registry 이미지를 선택해 등록하세요." }}
      </p>
      <div v-if="items.length" class="model-scroll">
        <table class="model-table" aria-label="등록된 AI 모델 실행 구성">
          <thead>
            <tr>
              <th scope="col">모델</th>
              <th scope="col">버전 · 실행 구성</th>
              <th scope="col">이미지 · 플랫폼</th>
              <th scope="col">CPU 요청</th>
              <th scope="col">메모리 요청</th>
              <th scope="col">가속기</th>
              <th scope="col">측정 상태</th>
              <th scope="col">등록 시각</th>
              <th scope="col"><span class="model-visually-hidden">상세</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in items" :key="m.id" :aria-current="selected?.id === m.id ? 'true' : undefined">
              <th scope="row">{{ m.displayName }}<div class="native-note">{{ m.modelKey }}</div></th>
              <td>{{ m.modelVersion }}<div class="native-note">{{ m.configName }}</div></td>
              <td>
                {{ m.image.repository }}<div class="native-note model-digest" :title="m.image.digest">
                  {{ shortDigest(m.image.digest) }} · {{ m.image.platform.label }}
                </div>
              </td>
              <td>{{ cpuQuantity(m.requirements.resources.cpu.requestMillicores) }}</td>
              <td>{{ memoryQuantity(m.requirements.resources.memory.requestBytes) }}</td>
              <td>{{ acceleratorSummary(m) }}</td>
              <td><span :class="['model-status', `model-status--${m.measurementStatus}`]">{{ MEASUREMENT_LABEL[m.measurementStatus] }}</span></td>
              <td>{{ time(m.createdAt) }}</td>
              <td><KsButton :aria-label="`${m.modelKey} ${m.modelVersion} ${m.configName} 상세`" @click="open(m.id)">상세</KsButton></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="offset > 0 || nextOffset != null" class="native-toolbar">
        <KsButton :disabled="listLoading || offset === 0" @click="page(Math.max(0, offset - PAGE))">이전</KsButton>
        <span class="native-note">{{ offset + 1 }}–{{ offset + items.length }}번째</span>
        <KsButton :disabled="listLoading || nextOffset == null" @click="page(nextOffset!)">다음</KsButton>
      </div>
    </section>

    <section v-if="selected || detailLoading || detailError" class="native-panel native-detail" aria-labelledby="model-detail">
      <h2 id="model-detail" ref="detailHeading" tabindex="-1">
        {{ selected ? `${selected.displayName} · ${selected.modelVersion} · ${selected.configName}` : "모델 상세" }}
      </h2>
      <NativeFeedback :error="detailError" />
      <NativeLoading :busy="detailLoading" label="모델 상세를 불러오는 중…" />
      <template v-if="selected && !detailLoading">
        <p v-if="selected.description" class="native-note">{{ selected.description }}</p>
        <dl>
          <div><dt>모델 키</dt><dd>{{ selected.modelKey }}</dd></div>
          <div><dt>이미지 참조</dt><dd><code>{{ selected.image.reference }}</code></dd></div>
          <div>
            <dt>플랫폼</dt>
            <dd>{{ selected.image.platform.label }} · {{ selected.image.kind === "INDEX" ? "여러 플랫폼 index에서 선택" : "단일 이미지" }}</dd>
          </div>
          <div><dt>조회 태그</dt><dd>{{ selected.image.sourceTag ?? "—" }} <span class="native-note">(등록은 digest로 고정)</span></dd></div>
          <div><dt>실행 명령</dt><dd><code>{{ selected.execution.command?.join(" ") ?? "이미지 기본값" }}</code></dd></div>
          <div><dt>인자</dt><dd><code>{{ selected.execution.args?.join(" ") ?? "이미지 기본값" }}</code></dd></div>
          <div><dt>입력 조건</dt><dd>{{ entries(selected.inputConditions) || "—" }}</dd></div>
          <div><dt>런타임</dt><dd>{{ selected.runtime ? `${selected.runtime.name} ${selected.runtime.version ?? ""}` : "—" }}</dd></div>
          <div>
            <dt>요구량 출처</dt>
            <dd>
              <span :class="['model-status', `model-status--${selected.measurementStatus}`]">{{ MEASUREMENT_LABEL[selected.measurementStatus] }}</span>
              {{ SOURCE_LABEL[selected.requirements.source] }}
              <template v-if="selected.resourceProfile">
                · 자원 프로파일 {{ selected.resourceProfile.service?.key }}@{{ selected.resourceProfile.service?.version }} r{{ selected.resourceProfile.revision }}
              </template>
            </dd>
          </div>
          <div><dt>등록 시각</dt><dd>{{ time(selected.createdAt) }}</dd></div>
        </dl>
        <div class="model-scroll">
          <table class="model-table" aria-label="자원 요구량">
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

    <section v-show="registering" id="model-register" class="native-panel" aria-labelledby="model-register-heading">
      <h2 id="model-register-heading" ref="registerHeading" tabindex="-1">모델 등록</h2>
      <p class="native-note">
        이미지 바이너리는 기존 Registry에 두고 여기에는 검증한 digest·플랫폼과 자원 요구량만 저장합니다. 웹에서 이미지를 올리거나 빌드하지 않습니다.
      </p>
      <KsForm labelPosition="top" class="native-form" @submit.prevent="save">
        <h3>기본 정보</h3>
        <div class="native-grid">
          <KsFormItem label="모델 키"
            ><KsInput v-model="draft.modelKey" :disabled="saving" maxlength="100" spellcheck="false" placeholder="예 forklift-detector"
          /></KsFormItem>
          <KsFormItem label="표시 이름"><KsInput v-model="draft.displayName" :disabled="saving" maxlength="200" /></KsFormItem>
          <KsFormItem label="모델 버전"><KsInput v-model="draft.modelVersion" :disabled="saving" maxlength="64" spellcheck="false" placeholder="예 1.0.0" /></KsFormItem>
          <KsFormItem label="실행 구성명"
            ><KsInput v-model="draft.configName" :disabled="saving" maxlength="63" spellcheck="false" placeholder="default"
          /></KsFormItem>
        </div>
        <p class="native-note">같은 모델·버전에 장치·이미지·입력 조건이 다른 실행 구성을 이름을 바꿔 추가할 수 있습니다.</p>
        <KsFormItem label="설명(선택)"><KsInput v-model="draft.description" :disabled="saving" type="textarea" :rows="2" maxlength="2000" /></KsFormItem>

        <h3>실행 이미지</h3>
        <p class="native-note">
          Registry {{ registry?.configured ? registry.host : "미설정" }}<template v-if="registry && !registry.configured"
            > — 서버에 Registry가 설정되지 않아 이미지를 조회·등록할 수 없습니다.</template
          >
        </p>
        <details v-if="registry?.configured" class="model-guide">
          <summary>기존 Registry로 이미지 올리는 방법</summary>
          <pre class="native-json">{{ pushCommands(registry.host!, repository || undefined) }}</pre>
          <p class="native-note">
            push가 끝나면 아래에서 저장소·태그를 고르고 ‘이미지 확인’으로 digest와 플랫폼을 읽습니다. 이미지 문자열을 직접 입력해 등록하지 않습니다.
          </p>
        </details>
        <NativeFeedback :error="imageError" />
        <div class="native-grid">
          <KsFormItem label="저장소"
            ><KsSelect
              :modelValue="repository"
              :disabled="saving || !registry?.configured"
              filterable
              aria-label="저장소"
              :loading="reposLoading"
              @visible-change="$event && !repos.length && loadRepos(false)"
              @update:model-value="selectRepository(String($event))"
              ><KsOption v-for="r in repos" :key="r" :value="r" :label="r" /></KsSelect
          ></KsFormItem>
          <KsFormItem label="저장소 직접 입력(목록에 없을 때)"
            ><KsInput
              v-model="manualRepository"
              :disabled="saving || !registry?.configured"
              maxlength="255"
              spellcheck="false"
              placeholder="예 edgeai/detector"
              @keydown.enter.prevent="selectRepository(manualRepository.trim())"
          /></KsFormItem>
        </div>
        <div class="native-toolbar">
          <KsButton :disabled="saving || reposLoading || !registry?.configured || (repos.length > 0 && reposNext == null)" @click="loadRepos(true)">{{
            repos.length ? "저장소 더 보기" : "저장소 목록 조회"
          }}</KsButton>
          <KsButton :disabled="saving || !manualRepository.trim()" @click="selectRepository(manualRepository.trim())">입력한 저장소 사용</KsButton>
          <span v-if="reposLoading" role="status" class="native-note">저장소를 불러오는 중…</span>
        </div>
        <div class="native-grid">
          <KsFormItem label="태그"
            ><KsSelect
              :modelValue="reference"
              :disabled="saving || !repository"
              filterable
              aria-label="태그"
              :loading="tagsLoading"
              @update:model-value="selectReference(String($event))"
              ><KsOption v-for="t in tags" :key="t" :value="t" :label="t" /></KsSelect
          ></KsFormItem>
          <KsFormItem label="태그 또는 digest 직접 입력"
            ><KsInput
              v-model="manualReference"
              :disabled="saving || !repository"
              maxlength="128"
              spellcheck="false"
              placeholder="예 1.0.0 또는 sha256:…"
              @input="referenceEdited"
              @keydown.enter.prevent="resolve"
          /></KsFormItem>
        </div>
        <div class="native-toolbar">
          <KsButton :disabled="saving || tagsLoading || !repository || tagsNext == null" @click="loadTags(true)">태그 더 보기</KsButton>
          <KsButton type="primary" :disabled="saving || !repository || !(manualReference.trim() || reference)" @click="resolve">이미지 확인</KsButton>
          <span v-if="tagsLoading" role="status" class="native-note">태그를 불러오는 중…</span>
          <span v-if="resolving" role="status" class="native-note">manifest·digest를 확인하는 중…</span>
        </div>
        <template v-if="resolved">
          <dl class="model-resolved" aria-label="확인한 이미지">
            <div><dt>digest</dt><dd><code>{{ resolved.digest }}</code></dd></div>
            <div>
              <dt>종류</dt>
              <dd>{{ resolved.kind === "INDEX" ? "여러 플랫폼 index — 아래 플랫폼의 manifest가 함께 고정됩니다." : "단일 이미지 manifest" }}</dd>
            </div>
          </dl>
          <KsFormItem label="플랫폼"
            ><KsSelect :modelValue="draft.platform" :disabled="saving" aria-label="플랫폼" @update:model-value="draft.platform = String($event)"
              ><KsOption
                v-for="p in resolved.platforms"
                :key="p.manifestDigest"
                :value="p.label"
                :disabled="!p.runnable"
                :label="`${p.label}${p.runnable ? '' : ' (등록 불가)'} · ${shortDigest(p.manifestDigest)}`" /></KsSelect
          ></KsFormItem>
          <p v-if="!resolved.platforms.length" class="native-note">이 index에는 실행할 수 있는 이미지 플랫폼이 없습니다.</p>
        </template>

        <h3>실행 구성</h3>
        <div class="native-grid">
          <KsFormItem label="실행 명령(선택, 한 줄에 하나)"
            ><KsInput v-model="draft.command" :disabled="saving" type="textarea" :rows="3" spellcheck="false" placeholder="비우면 이미지 기본값"
          /></KsFormItem>
          <KsFormItem label="인자(선택, 한 줄에 하나)"
            ><KsInput v-model="draft.args" :disabled="saving" type="textarea" :rows="3" spellcheck="false" placeholder="비우면 이미지 기본값"
          /></KsFormItem>
          <KsFormItem label="입력 조건(한 줄에 key=value)"
            ><KsInput v-model="draft.conditions" :disabled="saving" type="textarea" :rows="3" spellcheck="false" placeholder="예 resolution=640x640"
          /></KsFormItem>
          <div class="native-grid">
            <KsFormItem label="런타임 이름"><KsInput v-model="draft.runtimeName" :disabled="saving" maxlength="64" placeholder="예 onnxruntime" /></KsFormItem>
            <KsFormItem label="런타임 버전"><KsInput v-model="draft.runtimeVersion" :disabled="saving" maxlength="64" /></KsFormItem>
          </div>
        </div>

        <h3>자원 요구량</h3>
        <NativeFeedback :error="sourceError" />
        <div class="native-grid">
          <KsFormItem label="기존 자원 프로파일 가져오기(선택)"
            ><KsSelect
              :modelValue="sourceId"
              :disabled="saving"
              filterable
              aria-label="기존 자원 프로파일 가져오기(선택)"
              :loading="sourcesLoading || sourceLoading"
              @visible-change="$event && !sources.length && loadSources()"
              @update:model-value="selectSource(String($event))"
              ><KsOption value="" label="가져오지 않음(직접 입력)" /><KsOption
                v-for="p in sources"
                :key="p.id"
                :value="p.id"
                :label="`${p.service.key}@${p.service.version} r${p.revision} · ${MEASUREMENT_LABEL[p.measurementStatus]}`" /></KsSelect
          ></KsFormItem>
          <p class="native-note" role="status">
            요구량 출처: <strong>{{ SOURCE_LABEL[requirement] }}</strong
            >. 프로파일 값을 그대로 두면 원본 근거를 유지하고, 실행 명령·입력 조건·런타임·값을 하나라도 바꾸면 사용자 지정(미측정)으로 저장합니다.
            서버가 원본 프로파일의 이미지·플랫폼이 선택한 이미지와 같은지 다시 확인합니다.
          </p>
        </div>
        <div class="model-scroll">
          <table class="model-table" aria-label="CPU·메모리 요구량 입력">
            <thead>
              <tr><th scope="col">자원</th><th scope="col">최소</th><th scope="col">요청</th><th scope="col">최대</th><th scope="col">근거</th></tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id">
                <th scope="row">{{ row.label }}<span class="native-note"> · {{ row.hint }}</span></th>
                <td v-for="field in rangeFields" :key="field">
                  <KsInput
                    v-model="draft[row.id][field]"
                    :disabled="saving"
                    :aria-label="`${row.label} ${fieldLabel[field]}`"
                    :placeholder="row.placeholder"
                    spellcheck="false"
                  />
                </td>
                <td>{{ BASIS_LABEL[row.basis] }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="native-note">
          값은 비어 있으면 0으로 채우지 않습니다. 직접 입력한 값은 측정·추천이 아닌 사용자 지정입니다. 최소 ≤ 요청 ≤ 최대를 지켜 입력하세요.
        </p>
        <div v-if="draft.accelerators.length" class="model-scroll">
          <table class="model-table" aria-label="GPU·NPU 요구량 입력">
            <thead>
              <tr>
                <th scope="col">종류</th><th scope="col">확장 자원 이름</th><th scope="col">단위</th><th scope="col">최소</th><th scope="col">요청</th>
                <th scope="col">최대</th><th scope="col">장치 메모리 요구량</th><th scope="col">벤더 · 호환 모델</th>
                <th scope="col"><span class="model-visually-hidden">삭제</span></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(a, index) in draft.accelerators" :key="index">
                <td>
                  <KsSelect v-model="a.kind" :disabled="saving" :aria-label="`가속기 ${index + 1} 종류`"
                    ><KsOption value="GPU" label="GPU" /><KsOption value="NPU" label="NPU"
                  /></KsSelect>
                </td>
                <td><KsInput v-model="a.resourceName" :disabled="saving" :aria-label="`가속기 ${index + 1} 확장 자원 이름`" spellcheck="false" /></td>
                <td>
                  <KsSelect v-model="a.unit" :disabled="saving" :aria-label="`가속기 ${index + 1} 단위`"
                    ><KsOption value="COUNT" label="개수" /><KsOption value="MEBIBYTES" label="MiB" /><KsOption value="PERCENT" label="%"
                  /></KsSelect>
                </td>
                <td v-for="field in rangeFields" :key="field">
                  <KsInput v-model="a[field]" :disabled="saving" :aria-label="`가속기 ${index + 1} ${fieldLabel[field]}`" inputmode="numeric" />
                </td>
                <td><KsInput v-model="a.deviceMemory" :disabled="saving" :aria-label="`가속기 ${index + 1} 장치 메모리 요구량`" placeholder="예 8Gi" /></td>
                <td>
                  <KsInput v-model="a.vendor" :disabled="saving" :aria-label="`가속기 ${index + 1} 벤더`" placeholder="벤더" />
                  <KsInput v-model="a.models" :disabled="saving" :aria-label="`가속기 ${index + 1} 호환 모델`" placeholder="모델(쉼표 구분)" />
                </td>
                <td><KsButton :disabled="saving" :aria-label="`가속기 ${index + 1} 삭제`" @click="draft.accelerators.splice(index, 1)">삭제</KsButton></td>
              </tr>
            </tbody>
          </table>
        </div>
        <KsButton :disabled="saving || draft.accelerators.length >= 8" @click="draft.accelerators.push(emptyAccelerator())">가속기 요구량 추가</KsButton>
        <p class="native-note">
          확장 자원 이름은 실행 측 device plugin이 노출하는 이름(예 nvidia.com/gpu)입니다. 장치 메모리 요구량은 예약 가능한 Kubernetes 자원이 아닙니다.
        </p>

        <NativeFeedback :error="saveError" />
        <div class="native-toolbar">
          <KsButton nativeType="submit" type="primary" :disabled="saving || sourceLoading">{{ saving ? "등록하는 중…" : "등록" }}</KsButton>
          <KsButton :disabled="saving" @click="toggleRegister">닫기(입력 유지)</KsButton>
        </div>
        <details class="model-guide">
          <summary>기술 상세</summary>
          <p class="native-note">요청 키 {{ key }} · 응답이 불확실하면 같은 내용으로 다시 등록해도 중복되지 않습니다.</p>
        </details>
      </KsForm>
    </section>
  </main>
</template>
<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { KsButton, KsForm, KsFormItem, KsInput, KsOption, KsSelect } from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import type { components } from "../lib/api-schema";
import { get, requestKey, time, write } from "./native-api";
import { BASIS_LABEL, cpuQuantity, emptyAccelerator, formatValue, memoryQuantity } from "./resource-profile";
import {
  REPOSITORY,
  SOURCE_LABEL,
  emptyModelDraft,
  pushCommands,
  registrationDocument,
  requirementSource,
  shortDigest,
  sourceDraft,
  type ModelEntry,
  type ResolvedImage,
  type SourceProfile,
} from "./ai-model";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
type Api = components["schemas"];
const PAGE = 20;
const MEASUREMENT_LABEL: Record<string, string> = { MEASURED: "실측", PARTIAL: "일부 실측", UNMEASURED: "미측정" };
const rangeFields = ["min", "request", "max"] as const;
const fieldLabel = { min: "최소", request: "요청", max: "최대" };
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
const acceleratorSummary = (m: ModelEntry) =>
  (m.requirements.resources.accelerators ?? []).map((a) => `${a.resourceName} ${a.request}`).join(", ") || "없음";
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
  if (entry) selected.value = entry;
  else {
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

// ---- registration ----------------------------------------------------------------------------------------------
const registering = ref(false),
  registerHeading = ref<HTMLElement | null>(null),
  registry = ref<Api["AiModelRegistryStatus"] | null>(null),
  draft = reactive(emptyModelDraft()),
  saving = ref(false),
  saveError = ref(""),
  key = ref(requestKey());
let keyedPayload = "";
async function toggleRegister() {
  registering.value = !registering.value;
  if (!registering.value) return;
  await nextTick();
  registerHeading.value?.focus();
}

const repos = ref<string[]>([]),
  reposNext = ref<string | null>(null),
  reposLoading = ref(false),
  repository = ref(""),
  manualRepository = ref(""),
  tags = ref<string[]>([]),
  tagsNext = ref<string | null>(null),
  tagsLoading = ref(false),
  reference = ref(""),
  manualReference = ref(""),
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
  manualReference.value = "";
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
function selectReference(value: string) {
  reference.value = value;
  manualReference.value = value;
  resolved.value = null;
  draft.platform = "";
  ++imageGeneration;
  resolving.value = false;
}
/** Editing the reference invalidates the checked image and any lookup still in flight. */
function referenceEdited() {
  ++imageGeneration;
  resolved.value = null;
  resolving.value = false;
  draft.platform = "";
}
async function resolve() {
  const generation = ++imageGeneration;
  const repo = repository.value,
    target = manualReference.value.trim() || reference.value;
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

const sources = ref<Api["ResourceProfile"][]>([]),
  sourcesLoading = ref(false),
  sourceId = ref(""),
  source = ref<SourceProfile | null>(null),
  sourceLoading = ref(false),
  sourceError = ref("");
let sourceGeneration = 0;
async function loadSources() {
  sourcesLoading.value = true;
  sourceError.value = "";
  try {
    sources.value = (await get<Api["ResourceProfilePage"]>("resource-profiles?limit=100")).items;
  } catch (cause) {
    sourceError.value = message(cause, "자원 프로파일 목록 조회 실패");
  } finally {
    sourcesLoading.value = false;
  }
}
async function selectSource(id: string) {
  const generation = ++sourceGeneration;
  sourceError.value = "";
  sourceId.value = id;
  // The previous source stops applying at once; saving waits until the new one is loaded.
  source.value = null;
  sourceLoading.value = false;
  const profile = id ? sources.value.find((p) => p.id === id) : undefined;
  if (!profile) return;
  sourceLoading.value = true;
  try {
    const service = await get<{ spec: { command?: string[]; args?: string[] } }>(
      `profiles/SERVICE/${encodeURIComponent(profile.service.key)}/versions/${encodeURIComponent(profile.service.version)}`,
    );
    if (generation !== sourceGeneration) return;
    const loaded = { profile, command: service.spec.command ?? null, args: service.spec.args ?? null };
    source.value = loaded;
    Object.assign(draft, sourceDraft(loaded));
  } catch (cause) {
    if (generation !== sourceGeneration) return;
    sourceId.value = "";
    source.value = null;
    sourceError.value = message(cause, "자원 프로파일의 서비스 버전 조회 실패");
  } finally {
    if (generation === sourceGeneration) sourceLoading.value = false;
  }
}
const requirement = computed(() => requirementSource(draft, source.value));
const rows = computed(() => {
  const kept = requirement.value === "RESOURCE_PROFILE";
  return [
    { id: "cpu" as const, label: "CPU", hint: "millicores", placeholder: "예 250m 또는 0.25", basis: kept ? source.value!.profile.resources.cpu.basis : ("MANUAL" as const) },
    { id: "memory" as const, label: "메모리", hint: "bytes", placeholder: "예 512Mi", basis: kept ? source.value!.profile.resources.memory.basis : ("MANUAL" as const) },
  ];
});

async function save() {
  if (saving.value || sourceLoading.value) return;
  saving.value = true;
  saveError.value = "";
  try {
    const document = registrationDocument(draft, resolved.value, source.value);
    const payload = JSON.stringify(document);
    // The same payload keeps its key so an uncertain response can be retried without a duplicate.
    if (keyedPayload && payload !== keyedPayload) key.value = requestKey();
    keyedPayload = payload;
    const saved = await write<ModelEntry>("ai-models", "POST", document, key.value);
    Object.assign(draft, emptyModelDraft());
    resolved.value = null;
    reference.value = manualReference.value = "";
    sourceId.value = "";
    source.value = null;
    ++sourceGeneration;
    key.value = requestKey();
    keyedPayload = "";
    registering.value = false;
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
.model-scroll {
  /* Positioning context: visually hidden header text stays inside the horizontal scroller on narrow screens. */
  position: relative;
  overflow-x: auto;
  margin: var(--ks-spacing-3) 0;
}
.model-table {
  width: 100%;
  min-width: 860px;
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
.model-table thead th {
  color: var(--ks-text-secondary);
  font-weight: 600;
  white-space: nowrap;
}
.model-table td .kel-input,
.model-table td .kel-select {
  min-width: 96px;
}
.model-table tr[aria-current="true"] {
  background: var(--ks-bg-hover);
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
.model-resolved > div {
  display: grid;
  grid-template-columns: 80px minmax(0, 1fr);
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
.model-guide summary {
  min-height: 44px;
  display: flex;
  align-items: center;
  cursor: pointer;
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
.model-status--MEASURED {
  color: var(--ks-content-success);
}
.model-status--PARTIAL {
  color: var(--ks-content-warning);
}
.model-visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
</style>
