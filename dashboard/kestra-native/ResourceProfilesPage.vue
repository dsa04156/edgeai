<template>
  <TopNavBar title="자원 프로파일"
    ><template #actions
      ><RouterLink :to="{ name: 'edgeai/profiles', params: { tenant: route.params.tenant } }"
        >실행 모듈</RouterLink
      ></template
    ></TopNavBar
  >
  <main class="native-page">
    <NativeLoading :busy="loading" />
    <section class="native-panel" aria-labelledby="resource-source">
      <h2 id="resource-source">대상 서비스</h2>
      <p class="native-note">
        서비스·모델을 Pod로 실행할 때 선언할 CPU·메모리·GPU/NPU 요구량과 허용 범위를 확인해 불변 revision으로
        저장합니다. 계산·전송 시간 기반의 성능 프로파일과 별개이며, 저장한 요구량은 자원 예약·보장이 아닙니다.
      </p>
      <NativeFeedback :error="error" />
      <div class="native-grid">
        <KsFormItem label="서비스 실행 모듈"
          ><KsSelect
            :modelValue="serviceKey"
            :disabled="busy"
            filterable
            aria-label="서비스 실행 모듈"
            @update:model-value="selectService(String($event))"
            ><KsOption v-for="key in serviceKeys" :key="key" :value="key" :label="key" /></KsSelect
        ></KsFormItem>
        <KsFormItem label="서비스 버전"
          ><KsSelect
            :modelValue="service?.id ?? ''"
            :disabled="busy || !serviceKey"
            aria-label="서비스 버전"
            @update:model-value="selectVersion(String($event))"
            ><KsOption
              v-for="item in serviceVersions"
              :key="item.id"
              :value="item.id"
              :label="item.version" /></KsSelect
        ></KsFormItem>
      </div>
      <p v-if="!loading && !serviceKeys.length" class="native-note">
        등록된 SERVICE 실행 모듈이 없습니다. 실행 모듈 화면에서 먼저 발행하세요.
      </p>
      <p v-if="service" class="native-note">
        버전 ID {{ service.id }} · digest {{ service.digest }} · 아키텍처
        {{ serviceArchitectures.join(", ") || "미지정" }}
      </p>
    </section>

    <section v-if="service" class="native-panel" aria-labelledby="resource-target">
      <h2 id="resource-target">측정 대상·구간</h2>
      <p v-if="capabilities" class="native-note">
        분석 범위: 네임스페이스 {{ capabilities.namespaces.join(", ") || "없음" }} · Prometheus
        {{ capabilities.prometheus ? "연결" : "미구성" }} · KRR {{ capabilities.krrVersion }}
        {{ capabilities.krr ? "연결" : "미구성" }}. 실행 이력이 없으면 측정하지 않고 직접 입력할 수 있습니다.
      </p>
      <NativeFeedback :error="targetError || analyzeAction.error.value" />
      <KsForm labelPosition="top" class="native-form" @submit.prevent="analyze"
        ><div class="native-grid">
          <KsFormItem label="네임스페이스"
            ><KsSelect
              :modelValue="target.namespace"
              :disabled="analyzing"
              aria-label="네임스페이스"
              @update:model-value="selectNamespace(String($event))"
              ><KsOption
                v-for="ns in capabilities?.namespaces ?? []"
                :key="ns"
                :value="ns"
                :label="ns" /></KsSelect
          ></KsFormItem>
          <KsFormItem label="워크로드"
            ><KsSelect
              :modelValue="workloadKey"
              :disabled="analyzing || !target.namespace"
              filterable
              aria-label="워크로드"
              :loading="discovering"
              @update:model-value="selectWorkload(String($event))"
              ><KsOption
                v-for="w in workloads"
                :key="`${w.kind}/${w.name}`"
                :value="`${w.kind}/${w.name}`"
                :label="`${w.kind} · ${w.name}`" /></KsSelect
          ></KsFormItem>
          <KsFormItem label="컨테이너"
            ><KsSelect
              :modelValue="target.container"
              :disabled="analyzing || !workload"
              aria-label="컨테이너"
              @update:model-value="selectContainer(String($event))"
              ><KsOption
                v-for="c in workload?.containers ?? []"
                :key="c"
                :value="c"
                :label="c" /></KsSelect
          ></KsFormItem>
          <KsFormItem label="Pod 실행(선택)"
            ><KsSelect
              :modelValue="podChoice"
              :disabled="analyzing || !target.container"
              aria-label="Pod 실행(선택)"
              @update:model-value="podChoice = String($event)"
              ><KsOption value="" label="구간의 모든 Pod" /><KsOption
                v-for="p in pods"
                :key="`${p.name}/${p.uid}`"
                :value="`${p.name}/${p.uid}`"
                :label="`${p.name}${p.node ? ` · ${p.node}` : ''}`" /></KsSelect
          ></KsFormItem>
          <KsFormItem label="관측 구간"
            ><KsSelect
              :modelValue="preset"
              :disabled="analyzing"
              aria-label="관측 구간"
              @update:model-value="selectPreset(String($event))"
              ><KsOption v-for="h in [1, 6, 24, 168]" :key="h" :value="String(h)" :label="`최근 ${h}시간`" /><KsOption
                value="custom"
                label="직접 입력" /></KsSelect
          ></KsFormItem>
          <KsFormItem label="외부 실행 참조(선택)"
            ><KsInput v-model="executionRef" :disabled="analyzing" maxlength="128" spellcheck="false"
          /></KsFormItem>
          <KsFormItem label="구간 시작 (ISO 8601)"
            ><KsInput v-model="period.start" :disabled="analyzing || preset !== 'custom'" spellcheck="false" @change="reloadWindow"
          /></KsFormItem>
          <KsFormItem label="구간 종료 (ISO 8601)"
            ><KsInput v-model="period.end" :disabled="analyzing || preset !== 'custom'" spellcheck="false" @change="reloadWindow"
          /></KsFormItem>
          <KsFormItem label="장치 관측(선택)"
            ><KsSelect
              :modelValue="deviceChoices"
              :disabled="analyzing"
              multiple
              filterable
              aria-label="장치 관측(선택)"
              @update:model-value="deviceChoices = $event as string[]"
              ><KsOption
                v-for="d in devices"
                :key="d.value"
                :value="d.value"
                :label="d.label" /></KsSelect
          ></KsFormItem>
          <KsFormItem label="KRR 추천 이력(시간, 비우면 추천 안 함)"
            ><KsInput v-model="historyHours" :disabled="analyzing" type="number" min="1" max="336" step="1"
          /></KsFormItem>
        </div>
        <p class="native-note">
          장치 지표는 노드의 장치 전체 관측이며 exporter의 Pod 표시가 있어도 서비스별 사용량으로 쓰지 않습니다. KRR은 CPU·메모리만 추천하고 KRR 자체 이력 구간(현재 시각 기준)을 사용합니다.
        </p>
        <div class="native-toolbar">
          <KsButton nativeType="submit" type="primary" :disabled="analyzing || !canAnalyze">분석</KsButton>
          <KsButton :disabled="analyzing" @click="startManual">측정 없이 직접 입력</KsButton>
          <span v-if="analyzing" role="status" class="native-note">Prometheus 조회와 KRR 실행을 기다리는 중…</span>
        </div>
      </KsForm>
    </section>

    <section v-if="service && editing" class="native-panel" aria-labelledby="resource-review">
      <h2 id="resource-review" ref="reviewHeading" tabindex="-1">실측·추천 확인과 요구량 확정</h2>
      <p class="native-note">
        <template v-if="analysis"
          >분석 {{ analysis.id }} · {{ analysis.target.kind }} {{ analysis.target.namespace }}/{{ analysis.target.name }}
          · 컨테이너 {{ analysis.target.container }} · 구간 {{ time(analysis.window.start) }} ~
          {{ time(analysis.window.end) }} · Pod {{ analysis.measurement.pods.length }}개</template
        ><template v-else>분석 없이 직접 입력합니다. 저장하면 미측정·사용자 지정 값으로 표시됩니다.</template>
      </p>
      <div class="resource-scroll">
        <table class="resource-table" aria-label="CPU·메모리 실측·추천·확정값">
          <thead>
            <tr>
              <th scope="col">자원</th>
              <th scope="col">실측 최소 / 평균 / p95 / 최대</th>
              <th scope="col">KRR 추천 요청 / 제한</th>
              <th scope="col">최소</th>
              <th scope="col">요청</th>
              <th scope="col">최대</th>
              <th scope="col">근거</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <th scope="row">{{ row.label }}<span class="native-note"> · {{ row.unitHint }}</span></th>
              <td>
                <template v-if="row.usage">
                  <span :class="['resource-status', `resource-status--${row.usage.status}`]">{{ status(row.usage.status) }}</span>
                  {{ formatValue(row.usage.min, row.usage.unit) }} / {{ formatValue(row.usage.average, row.usage.unit) }} /
                  {{ formatValue(row.usage.p95, row.usage.unit) }} / {{ formatValue(row.usage.max, row.usage.unit) }}
                  <div class="native-note">
                    표본 {{ row.usage.samples }}<template v-if="row.usage.lastSample"> · 최근 {{ time(row.usage.lastSample) }}</template
                    ><template v-if="row.usage.reason"> · {{ row.usage.reason }}</template>
                  </div>
                  <KsButton
                    v-if="row.hint.measured != null"
                    size="small"
                    :aria-label="`${row.label} 실측 p95를 요청값으로 사용`"
                    @click="adopt(row.id, row.hint.measured)"
                    >p95 사용</KsButton
                  >
                </template>
                <template v-else>—<div class="native-note">미측정</div></template>
              </td>
              <td>
                <template v-if="analysis">
                  <span :class="['resource-status', `resource-status--${analysis.recommendation.status}`]">{{
                    status(analysis.recommendation.status)
                  }}</span>
                  {{ row.recommended }} / {{ row.recommendedLimit }}
                  <div class="native-note">
                    <template v-if="analysis.recommendation.window"
                      >KRR 이력 {{ analysis.recommendation.window.historyHours }}시간 ·
                      {{ time(analysis.recommendation.window.start) }} ~ {{ time(analysis.recommendation.window.end) }}</template
                    ><template v-if="row.info"> · {{ row.info }}</template
                    ><template v-if="analysis.recommendation.reason"> · {{ analysis.recommendation.reason }}</template>
                  </div>
                  <KsButton
                    v-if="row.hint.recommended != null"
                    size="small"
                    :aria-label="`${row.label} KRR 추천을 요청값으로 사용`"
                    @click="adopt(row.id, row.hint.recommended)"
                    >추천 사용</KsButton
                  >
                </template>
                <template v-else>—</template>
              </td>
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
      <details v-if="analysis && analysis.measurement.perPod.length > 1">
        <summary>복제본별 실측({{ analysis.measurement.perPod.length }}개 Pod)</summary>
        <ul>
          <li v-for="p in analysis.measurement.perPod" :key="p.pod">
            {{ p.pod }} · CPU p95 {{ formatValue(p.cpuP95Millicores, "millicores") }} / 최대 {{ formatValue(p.cpuMaxMillicores, "millicores") }} ·
            메모리 최대 {{ formatValue(p.memoryMaxBytes, "bytes") }} · 표본 {{ p.samples }}
          </li>
        </ul>
      </details>
      <p class="native-note">
        실측은 Pod당 컨테이너 값이며 복제본을 합산하지 않습니다(최대는 가장 큰 단일 Pod). 최소는 관측 최솟값이 아니라 실행에 필요한 하한입니다. 최대는 허용 상한이며 2-1이 컨테이너 limit을 정합니다.
        근거는 확정 요청값이 인용 분석의 KRR 추천·실측 p95와 같을 때만 추천·실측이고, 수정하면 사용자 지정입니다.
      </p>

      <template v-if="analysis && analysis.devices.length">
        <h3>장치 관측</h3>
        <div class="resource-scroll">
          <table class="resource-table" aria-label="GPU·NPU 장치 관측">
            <thead>
              <tr>
                <th scope="col">장치</th>
                <th scope="col">귀속</th>
                <th scope="col">사용률 평균 / p95 / 최대</th>
                <th scope="col">장치 메모리 최대 / 전체</th>
                <th scope="col">상태</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="d in analysis.devices" :key="`${d.node}/${d.device}`">
                <th scope="row">{{ d.kind }} · {{ d.model ?? "모델 미확인" }}<div class="native-note">{{ d.node }} · {{ d.exporter ?? "exporter 없음" }}</div></th>
                <td>{{ ATTRIBUTION_LABEL[d.attribution] }}</td>
                <td>
                  {{ formatValue(d.utilization?.average, "percent") }} / {{ formatValue(d.utilization?.p95, "percent") }} /
                  {{ formatValue(d.utilization?.max, "percent") }}
                </td>
                <td>{{ formatValue(d.memoryUsed?.max, "bytes") }} / {{ formatValue(d.memoryTotalBytes, "bytes") }}</td>
                <td>
                  <span :class="['resource-status', `resource-status--${d.status}`]">{{ status(d.status) }}</span>
                  <div v-if="d.reason" class="native-note">{{ d.reason }}</div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="native-note">
          장치 사용률을 GPU 개수나 분할 요청량으로 변환하지 않습니다. 가속기 요구량은 아래에서 직접 확정합니다.
        </p>
      </template>

      <h3>GPU/NPU 요구량(사용자 확정)</h3>
      <div v-if="draft.accelerators.length" class="resource-scroll">
        <table class="resource-table" aria-label="GPU·NPU 요구량">
          <thead>
            <tr>
              <th scope="col">종류</th>
              <th scope="col">확장 자원 이름</th>
              <th scope="col">단위</th>
              <th scope="col">최소</th>
              <th scope="col">요청</th>
              <th scope="col">최대</th>
              <th scope="col">장치 메모리 요구량</th>
              <th scope="col">벤더 · 호환 모델</th>
              <th scope="col"><span class="resource-visually-hidden">삭제</span></th>
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
                  ><KsOption value="COUNT" label="개수" /><KsOption value="MEBIBYTES" label="MiB" /><KsOption
                    value="PERCENT"
                    label="%"
                /></KsSelect>
              </td>
              <td v-for="field in rangeFields" :key="field">
                <KsInput v-model="a[field]" :disabled="saving" :aria-label="`가속기 ${index + 1} ${fieldLabel[field]}`" inputmode="numeric" />
              </td>
              <td><KsInput v-model="a.deviceMemory" :disabled="saving" :aria-label="`가속기 ${index + 1} 장치 메모리 요구량`" placeholder="예 8Gi" spellcheck="false" /></td>
              <td>
                <KsInput v-model="a.vendor" :disabled="saving" :aria-label="`가속기 ${index + 1} 벤더`" placeholder="벤더" />
                <KsInput v-model="a.models" :disabled="saving" :aria-label="`가속기 ${index + 1} 호환 모델`" placeholder="모델(쉼표 구분)" />
              </td>
              <td><KsButton :disabled="saving" :aria-label="`가속기 ${index + 1} 삭제`" @click="draft.accelerators.splice(index, 1)">삭제</KsButton></td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="native-note">
        확장 자원 이름은 실행 측 device plugin이 노출하는 이름(예 nvidia.com/gpu)입니다. 장치 메모리 요구량은 모델이
        필요한 장치 메모리이며 Kubernetes에서 예약 가능한 자원 필드가 아닙니다. GPU/NPU는 VPA로 조정하지 않습니다.
      </p>
      <KsButton :disabled="saving || draft.accelerators.length >= 8" @click="draft.accelerators.push(emptyAccelerator())">가속기 요구량 추가</KsButton>

      <h3>호환 조건</h3>
      <div class="native-grid">
        <fieldset>
          <legend class="native-note">대상 아키텍처</legend>
          <KsCheckbox
            v-for="arch in serviceArchitectures.length ? serviceArchitectures : ['amd64', 'arm64']"
            :key="arch"
            :modelValue="draft.architectures.includes(arch)"
            :disabled="saving"
            @update:model-value="toggleArchitecture(arch, Boolean($event))"
            >{{ arch }}</KsCheckbox
          >
        </fieldset>
        <div class="native-grid">
          <KsFormItem label="런타임 이름"><KsInput v-model="draft.runtimeName" :disabled="saving" maxlength="64" /></KsFormItem>
          <KsFormItem label="런타임 버전"><KsInput v-model="draft.runtimeVersion" :disabled="saving" maxlength="64" /></KsFormItem>
        </div>
        <KsFormItem label="입력 조건 (한 줄에 key=value)"
          ><KsInput v-model="draft.conditions" :disabled="saving" type="textarea" :rows="3" spellcheck="false"
        /></KsFormItem>
        <KsFormItem label="메모"><KsInput v-model="draft.note" :disabled="saving" type="textarea" :rows="3" maxlength="500" /></KsFormItem>
      </div>
      <NativeFeedback :error="saveAction.error.value" />
      <div class="native-toolbar">
        <KsButton type="primary" :disabled="saving" @click="save">새 revision 저장</KsButton>
        <span class="native-note">요청 키 {{ key }} · 응답이 불확실하면 같은 내용으로 다시 저장해도 중복되지 않습니다.</span>
      </div>
    </section>

    <section v-if="service" class="native-panel" aria-labelledby="resource-revisions">
      <h2 id="resource-revisions">저장된 revision</h2>
      <NativeFeedback :error="listAction.error.value" />
      <p v-if="listAction.busy.value" role="status" class="native-note">revision 목록을 불러오는 중…</p>
      <p v-else-if="!revisions.length" class="native-note">이 서비스 버전에 저장된 자원 프로파일이 없습니다.</p>
      <div v-else class="resource-scroll">
        <table class="resource-table" aria-label="저장된 자원 프로파일 revision">
          <thead>
            <tr>
              <th scope="col">revision</th>
              <th scope="col">측정 상태</th>
              <th scope="col">CPU 최소 / 요청 / 최대</th>
              <th scope="col">메모리 최소 / 요청 / 최대</th>
              <th scope="col">가속기</th>
              <th scope="col">저장 시각</th>
              <th scope="col"><span class="resource-visually-hidden">보기</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in revisions" :key="p.id" :aria-current="selected?.id === p.id ? 'true' : undefined">
              <th scope="row">{{ p.revision }}</th>
              <td><span :class="['resource-status', `resource-status--${p.measurementStatus}`]">{{ status(p.measurementStatus) }}</span></td>
              <td>
                {{ cpuQuantity(p.resources.cpu.minMillicores) }} / {{ cpuQuantity(p.resources.cpu.requestMillicores) }} /
                {{ cpuQuantity(p.resources.cpu.maxMillicores) }} · {{ BASIS_LABEL[p.resources.cpu.basis] }}
              </td>
              <td>
                {{ memoryQuantity(p.resources.memory.minBytes) }} / {{ memoryQuantity(p.resources.memory.requestBytes) }} /
                {{ memoryQuantity(p.resources.memory.maxBytes) }} · {{ BASIS_LABEL[p.resources.memory.basis] }}
              </td>
              <td>{{ (p.resources.accelerators ?? []).map((a) => `${a.resourceName} ${a.request}`).join(", ") || "없음" }}</td>
              <td>{{ time(p.createdAt) }}</td>
              <td><KsButton :aria-label="`revision ${p.revision} 보기`" @click="view(p)">보기</KsButton></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-if="selected" class="native-panel native-detail" aria-labelledby="resource-selected">
      <h2 id="resource-selected" ref="selectedHeading" tabindex="-1">revision {{ selected.revision }} · 2-1 전달 계약</h2>
      <dl>
        <div><dt>프로파일 ID</dt><dd>{{ selected.id }}</dd></div>
        <div><dt>서비스</dt><dd>{{ selected.service.key }}@{{ selected.service.version }} ({{ selected.service.profileVersionId }})</dd></div>
        <div><dt>digest</dt><dd>{{ selected.digest }}</dd></div>
        <div><dt>측정 상태</dt><dd>{{ status(selected.measurementStatus) }}{{ selected.analysisId ? ` · 분석 ${selected.analysisId}` : "" }}</dd></div>
        <div><dt>2-1 조회 경로</dt><dd>{{ handoffPath }}</dd></div>
      </dl>
      <NativeFeedback :error="handoffAction.error.value" />
      <p v-if="handoffAction.busy.value" role="status" class="native-note">전달 계약을 불러오는 중…</p>
      <template v-if="handoff">
        <div class="native-toolbar">
          <KsButton @click="copyHandoff">JSON 복사</KsButton>
          <KsButton @click="downloadHandoff">JSON 내려받기</KsButton>
        </div>
        <pre class="native-json" aria-label="2-1 전달 계약 JSON">{{ pretty(handoff) }}</pre>
      </template>
    </section>
  </main>
</template>
<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { KsButton, KsCheckbox, KsForm, KsFormItem, KsInput, KsOption, KsSelect } from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import type { components } from "../lib/api-schema";
import { get, pretty, profileItems, request, requestKey, time, useAction, write, type Schema } from "./native-api";
import {
  ATTRIBUTION_LABEL,
  BASIS_LABEL,
  STATUS_LABEL,
  basis,
  cpuQuantity,
  emptyAccelerator,
  emptyDraft,
  formatValue,
  memoryQuantity,
  parseCpu,
  parseMemory,
  profileDocument,
  suggestions,
  windowEnding,
  type ProfileDraft,
} from "./resource-profile";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
type Api = components["schemas"];
type Profile = Api["ResourceProfile"];
const route = useRoute(),
  router = useRouter();
useRouteContext(computed(() => ({ title: "자원 프로파일" })));
const { busy, error, act } = useAction();
const analyzeAction = useAction(),
  saveAction = useAction(),
  listAction = useAction(),
  handoffAction = useAction();
const loading = ref(true),
  services = ref<Schema["ProfileVersion"][]>([]),
  serviceKey = ref(""),
  service = ref<Schema["ProfileVersion"] | null>(null),
  capabilities = ref<Api["ResourceProfilingCapabilities"] | null>(null),
  workloads = ref<Api["ResourceWorkloadList"]["items"]>([]),
  pods = ref<Api["ResourcePodList"]["items"]>([]),
  nodeMetrics = ref<Schema["NodeMetrics"][]>([]),
  target = reactive({ namespace: "", kind: "", name: "", container: "" }),
  podChoice = ref(""),
  preset = ref("1"),
  period = reactive(windowEnding(new Date(), 1)),
  executionRef = ref(""),
  deviceChoices = ref<string[]>([]),
  historyHours = ref("24"),
  targetError = ref(""),
  discovering = ref(false),
  analysis = ref<Api["ResourceAnalysis"] | null>(null),
  editing = ref(false),
  draft = ref<ProfileDraft>(emptyDraft([])),
  saving = ref(false),
  key = ref(requestKey()),
  revisions = ref<Profile[]>([]),
  selected = ref<Profile | null>(null),
  handoff = ref<Api["ResourceProfileHandoff"] | null>(null),
  reviewHeading = ref<HTMLElement | null>(null),
  selectedHeading = ref<HTMLElement | null>(null);
let keyedPayload = "";
const rangeFields = ["min", "request", "max"] as const;
const fieldLabel = { min: "최소", request: "요청", max: "최대" };
const status = (value: string) => STATUS_LABEL[value] ?? value;
const serviceKeys = computed(() => [...new Set(services.value.map((s) => s.key))].sort());
const serviceVersions = computed(() => services.value.filter((s) => s.key === serviceKey.value));
const serviceArchitectures = computed<string[]>(() => {
  const platform = (service.value?.spec as { platform?: { architectures?: unknown } } | undefined)?.platform;
  return Array.isArray(platform?.architectures) ? platform.architectures.map(String).sort() : [];
});
const analyzing = computed(() => analyzeAction.busy.value);
const workloadKey = computed(() => (target.kind ? `${target.kind}/${target.name}` : ""));
const workload = computed(() => workloads.value.find((w) => `${w.kind}/${w.name}` === workloadKey.value) ?? null);
const canAnalyze = computed(() => Boolean(target.namespace && target.name && target.container));
const devices = computed(() =>
  nodeMetrics.value.flatMap((node) =>
    (node.accelerators ?? []).map((a) => ({
      value: JSON.stringify({ node: node.nodeName, kind: a.kind, device: a.device }),
      label: `${node.nodeName} · ${a.kind} · ${a.vendor} ${a.model}`,
    })),
  ),
);
const hints = computed(() => suggestions(analysis.value));
const rows = computed(() => {
  const a = analysis.value;
  const cpuRequest = parseCpu(draft.value.cpu.request), memoryRequest = parseMemory(draft.value.memory.request);
  return [
    {
      id: "cpu" as const, label: "CPU", unitHint: "millicores", placeholder: "예 250m", usage: a?.measurement.cpu ?? null, hint: hints.value.cpu,
      recommended: a ? formatValue(a.recommendation.cpu.requestMillicores, "millicores") : "—",
      recommendedLimit: a ? (a.recommendation.cpu.limitMillicores == null ? "제한 없음" : formatValue(a.recommendation.cpu.limitMillicores, "millicores")) : "—",
      info: a?.recommendation.cpu.info, basis: basis(cpuRequest, hints.value.cpu),
    },
    {
      id: "memory" as const, label: "메모리", unitHint: "bytes", placeholder: "예 512Mi", usage: a?.measurement.memory ?? null, hint: hints.value.memory,
      recommended: a ? formatValue(a.recommendation.memory.requestBytes, "bytes") : "—",
      recommendedLimit: a ? formatValue(a.recommendation.memory.limitBytes, "bytes") : "—",
      info: a?.recommendation.memory.info, basis: basis(memoryRequest, hints.value.memory),
    },
  ];
});
const handoffPath = computed(() =>
  selected.value
    ? `/api/v1/resource-profiles/services/${selected.value.service.key}/versions/${selected.value.service.version}/revisions/${selected.value.revision}/handoff`
    : "",
);

function selectService(key: string) {
  serviceKey.value = key;
  const latest = serviceVersions.value[0];
  if (latest) selectVersion(latest.id);
  else service.value = null;
}
function selectVersion(id: string) {
  service.value = services.value.find((s) => s.id === id) ?? null;
  analysis.value = null;
  editing.value = false;
  selected.value = null;
  handoff.value = null;
  void router.replace({ query: { ...route.query, service: id } });
  void loadRevisions();
}
// Discovery and Pod lookups are independent of analysis; a generation counter drops responses for an older selection.
let discoveryGeneration = 0,
  podGeneration = 0;
async function selectNamespace(namespace: string) {
  Object.assign(target, { namespace, kind: "", name: "", container: "" });
  workloads.value = [];
  pods.value = [];
  podChoice.value = "";
  await loadWorkloads();
}
/** Workloads observed in the selected window, so a Job that ended days ago is selectable for a 168 h analysis. */
async function loadWorkloads() {
  if (!target.namespace) return;
  const generation = ++discoveryGeneration;
  const query = new URLSearchParams({ namespace: target.namespace, start: period.start, end: period.end });
  discovering.value = true;
  targetError.value = "";
  try {
    const items = (await get<Api["ResourceWorkloadList"]>(`resource-profiles/workloads?${query}`)).items;
    if (generation !== discoveryGeneration) return;
    workloads.value = items;
    const kept = items.find((w) => w.kind === target.kind && w.name === target.name);
    if (target.kind && (!kept || !kept.containers.includes(target.container))) {
      Object.assign(target, { kind: "", name: "", container: "" });
      pods.value = [];
      podChoice.value = "";
    }
  } catch (cause) {
    if (generation === discoveryGeneration) targetError.value = cause instanceof Error ? cause.message : "워크로드 조회 실패";
  } finally {
    if (generation === discoveryGeneration) discovering.value = false;
  }
}
async function reloadWindow() {
  await loadWorkloads();
  await loadPods();
}
function selectWorkload(value: string) {
  const [kind, ...name] = value.split("/");
  Object.assign(target, { kind, name: name.join("/"), container: "" });
  pods.value = [];
  podChoice.value = "";
  const only = workload.value?.containers;
  if (only?.length === 1) void selectContainer(only[0]);
}
async function selectContainer(container: string) {
  target.container = container;
  await loadPods();
}
function selectPreset(value: string) {
  preset.value = value;
  if (value !== "custom") Object.assign(period, windowEnding(new Date(), Number(value)));
  void reloadWindow();
}
async function loadPods() {
  const generation = ++podGeneration;
  pods.value = [];
  podChoice.value = "";
  if (!canAnalyze.value) return;
  const query = new URLSearchParams({ ...target, start: period.start, end: period.end });
  try {
    const items = (await get<Api["ResourcePodList"]>(`resource-profiles/pods?${query}`)).items;
    if (generation === podGeneration) pods.value = items;
  } catch (cause) {
    if (generation === podGeneration) targetError.value = cause instanceof Error ? cause.message : "Pod 조회 실패";
  }
}
function toggleArchitecture(arch: string, checked: boolean) {
  const values = new Set(draft.value.architectures);
  if (checked) values.add(arch);
  else values.delete(arch);
  draft.value.architectures = [...values];
}
async function beginReview(result: Api["ResourceAnalysis"] | null) {
  analysis.value = result;
  draft.value = emptyDraft(serviceArchitectures.value);
  key.value = requestKey();
  keyedPayload = "";
  editing.value = true;
  await nextTick();
  reviewHeading.value?.focus();
}
function startManual() {
  void beginReview(null);
}
function adopt(row: "cpu" | "memory", value: number) {
  draft.value[row].request = row === "cpu" ? cpuQuantity(value) : String(value);
}
async function analyze() {
  if (preset.value !== "custom") Object.assign(period, windowEnding(new Date(), Number(preset.value)));
  // A bare Pod target names the Pod itself; controller targets may narrow to one observed Pod.
  const [pod, podUid] = podChoice.value ? podChoice.value.split("/") : [target.kind === "Pod" ? target.name : null, null];
  const hours = historyHours.value === "" ? null : Number(historyHours.value);
  const body = {
    serviceProfileVersionId: service.value!.id,
    target: { ...target, pod, podUid: podUid || null },
    window: { start: period.start, end: period.end },
    devices: deviceChoices.value.map((d) => JSON.parse(d)),
    ...(executionRef.value.trim() ? { executionRef: executionRef.value.trim() } : {}),
    ...(hours == null ? {} : { recommendation: { historyHours: hours } }),
  };
  // Its own action: a pending discovery or Pod lookup can neither skip nor lock the analysis.
  await analyzeAction.act(async () => {
    const { token } = await get<{ token: string }>("csrf");
    // The API bounds the whole analysis (default 180 s); the console proxy waits 210 s and the browser 220 s.
    const response = await request("resource-profiles/analyses", {
      method: "POST",
      headers: { "X-CSRF-TOKEN": token, "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(220000),
    });
    await beginReview(await response.json());
  });
}
async function save() {
  saving.value = true;
  await saveAction.act(async () => {
    try {
      const document = profileDocument(service.value!.id, analysis.value, draft.value);
      const payload = JSON.stringify(document);
      if (keyedPayload && payload !== keyedPayload) key.value = requestKey();
      keyedPayload = payload;
      const saved = await write<Profile>("resource-profiles", "POST", document, key.value);
      await loadRevisions();
      await view(saved);
    } finally {
      saving.value = false;
    }
  });
}
async function loadRevisions() {
  const id = service.value?.id;
  if (!id) return;
  await listAction.act(async () => {
    const page = await get<Api["ResourceProfilePage"]>(`resource-profiles?serviceProfileVersionId=${id}&limit=100`);
    if (service.value?.id === id) revisions.value = page.items;
  });
}
async function view(profile: Profile) {
  selected.value = profile;
  handoff.value = null;
  void router.replace({ query: { ...route.query, service: profile.serviceProfileVersionId, profile: profile.id } });
  await handoffAction.act(async () => {
    const result = await get<Api["ResourceProfileHandoff"]>(`resource-profiles/${profile.id}/handoff`);
    if (selected.value?.id === profile.id) handoff.value = result;
  });
  await nextTick();
  selectedHeading.value?.focus();
}
async function copyHandoff() {
  await handoffAction.act(() => navigator.clipboard.writeText(pretty(handoff.value)));
}
function downloadHandoff() {
  const url = URL.createObjectURL(new Blob([pretty(handoff.value)], { type: "application/json" }));
  const link = Object.assign(document.createElement("a"), {
    href: url,
    download: `resource-profile-${selected.value!.service.key}-${selected.value!.service.version}-r${selected.value!.revision}.json`,
  });
  link.click();
  URL.revokeObjectURL(url);
}
onMounted(() => {
  void act(async () => {
    try {
      const [serviceList, scope, metrics] = await Promise.all([
        profileItems("SERVICE"),
        get<Api["ResourceProfilingCapabilities"]>("resource-profiles/capabilities"),
        get<Schema["NodeMetricsSnapshot"]>("node-metrics").catch(() => ({ items: [] as Schema["NodeMetrics"][] })),
      ]);
      services.value = serviceList;
      capabilities.value = scope;
      nodeMetrics.value = metrics.items;
      if (scope.namespaces.length === 1) await selectNamespace(scope.namespaces[0]);
      const wanted = services.value.find((s) => s.id === route.query.service);
      if (wanted) {
        serviceKey.value = wanted.key;
        selectVersion(wanted.id);
        if (typeof route.query.profile === "string")
          await view(await get<Profile>(`resource-profiles/${encodeURIComponent(route.query.profile)}`));
      }
    } finally {
      loading.value = false;
    }
  });
});
</script>
<style scoped>
.resource-scroll {
  overflow-x: auto;
  margin: var(--ks-spacing-3) 0;
}
.resource-table {
  width: 100%;
  min-width: 880px;
  border-collapse: collapse;
  font-size: var(--ks-font-size-sm);
}
.resource-table th,
.resource-table td {
  text-align: left;
  vertical-align: top;
  padding: var(--ks-spacing-2) var(--ks-spacing-3);
  border-bottom: 1px solid var(--ks-border-default);
  overflow-wrap: anywhere;
}
.resource-table thead th {
  color: var(--ks-text-secondary);
  font-weight: 600;
  white-space: nowrap;
}
.resource-table td .kel-input,
.resource-table td .kel-select {
  min-width: 96px;
}
.resource-table tbody th {
  min-width: 112px;
}
.resource-table tr[aria-current="true"] {
  background: var(--ks-bg-hover);
}
.resource-status {
  display: inline-block;
  margin-right: var(--ks-spacing-2);
  padding: 0 var(--ks-spacing-2);
  border-radius: var(--ks-radius-base);
  font-size: var(--ks-font-size-xs);
  background: var(--ks-bg-hover);
  color: var(--ks-text-secondary);
}
.resource-status--AVAILABLE,
.resource-status--MEASURED {
  color: var(--ks-content-success);
}
.resource-status--STALE,
.resource-status--INSUFFICIENT,
.resource-status--PARTIAL,
.resource-status--AMBIGUOUS {
  color: var(--ks-content-warning);
}
.resource-status--UNAVAILABLE,
.resource-status--INVALID_REPORT,
.resource-status--NOT_FOUND {
  color: var(--ks-content-error);
}
fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--ks-spacing-3);
  align-items: center;
}
.resource-visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
</style>
