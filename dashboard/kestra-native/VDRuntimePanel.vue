<template>
  <section class="native-panel native-detail">
    <div class="native-toolbar">
      <h2>연속 계산</h2>
      <KsButton :disabled="busy || loading" @click="refresh()"
        >상태 새로고침</KsButton
      >
    </div>
    <NativeFeedback :error="error" :notice="notice" />
    <NativeLoading :busy="loading" label="계산 데이터를 불러오는 중…" />
    <template v-if="execution?.continuous">
      <KsTag
        :type="
          error
            ? 'warning'
            : execution.continuous.state === 'RUNNING'
              ? 'success'
              : 'info'
        "
        >{{ error ? "상태 확인 필요" : execution.continuous.state }}</KsTag
      >
      <p class="native-note">{{ execution.continuous.reason }}</p>
      <dl>
        <div>
          <dt>계산 세대</dt>
          <dd>{{ execution.continuous.generation || "—" }}</dd>
        </div>
        <div>
          <dt>실행 엔진</dt>
          <dd>{{ execution.continuous.engineId || "—" }}</dd>
        </div>
        <div>
          <dt>엔진 lease 만료</dt>
          <dd>{{ time(execution.continuous.engineLeaseExpiresAt) }}</dd>
        </div>
        <div>
          <dt>상태 갱신</dt>
          <dd>{{ time(execution.continuous.updatedAt) }}</dd>
        </div>
      </dl>
      <p v-if="!enabled">
        현재 계산 기능이 비활성화되어 있습니다. 저장된 이력은 조회할 수
        있습니다.
      </p>
      <div class="native-toolbar">
        <KsButton
          :disabled="
            busy || !!error || !enabled || vd.state !== 'REGISTERED' || running
          "
          @click="prepare('provision')"
          >계산 시작</KsButton
        >
        <KsButton
          :disabled="busy || !!error || !enabled || !running"
          @click="prepare('drain')"
          >계산 중지</KsButton
        >
      </div>
    </template>
    <details @toggle="versionsToggled">
      <summary>계산 설정 버전 이력</summary>
      <NativeFeedback :error="versionsError" />
      <NativeLoading :busy="versionsBusy" label="설정 버전을 불러오는 중…" />
      <NativeRecords
        title="계산 설정 버전"
        :busy="versionsBusy"
        :rows="versions.map((v) => ({ ...v, id: String(v.revision) }))"
        :columns="[
          { key: 'revision', label: 'revision' },
          { key: 'recordedAt', label: '저장 시각' },
          { key: 'configuration', label: '계산 설정', width: 400 },
        ]"
        :offset="versionsOffset"
        :nextOffset="versionsNext"
        @page="loadVersions"
      >
        <template #recordedAt="{ row }">{{ time(row.recordedAt) }}</template>
        <template #configuration="{ row }"
          ><details>
            <summary>{{ row.configuration.outputs.length }}개 출력</summary>
            <pre class="native-json">{{ pretty(row) }}</pre>
          </details></template
        >
      </NativeRecords>
    </details>
    <template v-if="outputs"
      ><h2>계산 결과</h2>
      <p class="native-note">
        {{ outputs.intervalSeconds }}초 주기 · {{ outputs.retentionDays }}일
        보관 ·
        {{ outputs.historyEnabled ? "기록 저장 사용" : "기록 저장소 미연결" }}
      </p>
      <NativeRecords
        title="출력 항목별 최신 결과"
        :rows="outputs.items.map((row) => ({ ...row, id: row.outputId }))"
        :columns="[
          { key: 'name', label: '출력 / 계산식', width: 220 },
          { key: 'value', label: '최신값' },
          { key: 'quality', label: '상태' },
          { key: 'at', label: '계산 시각' },
        ]"
        ><template #name="{ row }"
          ><strong>{{ row.name }}</strong>
          <p class="native-note">{{ row.expression }}</p></template
        ><template #value="{ row }"
          >{{ row.latest?.value ?? "—" }} {{ row.unit }}</template
        ><template #at="{ row }">{{ time(row.latest?.evaluationAt) }}</template
        ><template #quality="{ row }"
          >{{ error ? "조회 확인 필요" : row.quality
          }}{{
            row.latest?.storageStatus === "PENDING"
              ? " · 기록 저장 대기"
              : row.latest?.storageStatus === "SKIPPED"
                ? " · 저장 정책에 따라 제외"
                : row.latest?.storageStatus === "EXPIRED"
                  ? " · 보관 기간 만료"
                  : row.latest && !row.latest.historyStored
                    ? " · 기록 저장 확인 필요"
                    : ""
          }}</template
        ><template #actions="{ row }"
          ><KsButton
            :disabled="busy || !outputs.historyEnabled"
            @click="chart(row.outputId)"
            >{{ row.name }} 그래프</KsButton
          ></template
        ></NativeRecords
      ></template
    >
    <section v-if="selectedOutput">
      <div class="native-toolbar">
        <h3>{{ selectedOutput }} 기록</h3>
        <KsSelect v-model="hours" aria-label="조회 기간"
          ><KsOption
            v-for="hour in [1, 6, 24, 168, 720].filter(
              (h) => h <= (outputs?.retentionDays || 7) * 24,
            )"
            :key="hour"
            :value="hour"
            :label="`${hour}시간`" /></KsSelect
        ><KsButton :disabled="historyBusy" @click="chart(selectedOutput)"
          >그래프 조회</KsButton
        >
      </div>
      <NativeFeedback :error="historyError" /><HistoryChart
        v-if="history"
        :history="history"
        :interval="outputs?.intervalSeconds || 1"
        :label="`${selectedOutput} 결과 이력`"
      />
      <NativeLoading :busy="historyBusy" label="기록을 불러오는 중…" />
      <p v-if="history?.truncated" class="native-note">
        표시 한도에 도달했습니다. 조회 기간을 줄여 확인하세요.
      </p>
      <ul v-if="history?.gaps.length">
        <li v-for="(gap, index) in history.gaps" :key="index">
          {{ gap.reason }} · {{ time(gap.from) }} →
          {{ gap.to ? time(gap.to) : "계속 중" }}
        </li>
      </ul>
      <details v-if="history">
        <summary>원본 기록 표 · {{ history.items.length }}개</summary>
        <NativeRecords
          title="계산 기록"
          :rows="
            history.items.map((row, index) => ({ ...row, id: String(index) }))
          "
          :columns="[
            { key: 'evaluationAt', label: '계산 시각', width: 220 },
            { key: 'value', label: '값 / 평균' },
            { key: 'min', label: '최솟값' },
            { key: 'max', label: '최댓값' },
            { key: 'count', label: '표본 수' },
          ]"
          ><template #value="{ row }">{{
            row.average ?? row.value
          }}</template></NativeRecords
        >
      </details>
    </section>
  </section>
  <ConfirmAction
    v-model="confirmOpen"
    :title="draft?.action === 'drain' ? '계산 중지 요청' : '계산 시작 요청'"
    :subject="vd.displayName"
    description="현재 revision으로 요청합니다. 응답 오류 시 같은 요청 키로 재시도하며 실제 준비·종료 상태는 조회로 확인합니다."
    :busy="busy"
    :error="commandError"
    @confirm="submit"
    ><p class="native-note">
      요청 키 {{ draft?.key }} · revision {{ draft?.revision }}
    </p></ConfirmAction
  >
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { KsButton, KsTag, KsSelect, KsOption } from "@kestra-io/design-system";
import {
  get,
  write,
  requestKey,
  time,
  pretty,
  usePoll,
  type Schema,
} from "./native-api";
import NativeFeedback from "./NativeFeedback.vue";
import NativeLoading from "./NativeLoading.vue";
import NativeRecords from "./NativeRecords.vue";
import ConfirmAction from "./ConfirmAction.vue";
import HistoryChart from "./HistoryChart.vue";
const props = defineProps<{ vd: Schema["VirtualDevice"] }>();
const execution = ref<Schema["VDExecution"] | null>(null),
  outputs = ref<Schema["ContinuousOutputs"] | null>(null),
  error = ref(""),
  notice = ref(""),
  busy = ref(false),
  loading = ref(false),
  commandError = ref(""),
  confirmOpen = ref(false),
  draft = ref<{
    action: "provision" | "drain";
    key: string;
    revision: number;
  } | null>(null);
let generation = 0;
const enabled = computed(() => execution.value?.continuous?.enabled),
  running = computed(
    () => execution.value?.continuous?.desiredRunning === true,
  );
const versions = ref<Schema["ProcessingVersionPage"]["items"]>([]),
  versionsOffset = ref(0),
  versionsNext = ref<number | null>(null),
  versionsBusy = ref(false),
  versionsError = ref("");
async function loadVersions(offset = 0) {
  if (versionsBusy.value) return;
  versionsBusy.value = true;
  versionsError.value = "";
  try {
    const page = await get<Schema["ProcessingVersionPage"]>(
      `virtual-devices/${props.vd.id}/processing-versions?limit=20&offset=${offset}`,
    );
    versions.value = page.items;
    versionsOffset.value = offset;
    versionsNext.value = page.nextOffset;
  } catch (cause) {
    versionsError.value =
      cause instanceof Error ? cause.message : "버전 조회 실패";
  } finally {
    versionsBusy.value = false;
  }
}
function versionsToggled(event: Event) {
  if ((event.target as HTMLDetailsElement).open) void loadVersions();
}
async function refresh(showLoading = true) {
  const request = ++generation;
  if (showLoading) loading.value = true;
  try {
    const value = await get<Schema["VDExecution"]>(
      `virtual-devices/${props.vd.id}/execution`,
    );
    const result = await get<Schema["ContinuousOutputs"]>(
      `virtual-devices/${props.vd.id}/outputs`,
    );
    if (request !== generation) return;
    execution.value = value;
    outputs.value = result;
    error.value = "";
  } catch (cause) {
    if (request === generation)
      error.value =
        cause instanceof Error ? cause.message : "실행 상태 조회 실패";
  } finally {
    if (request === generation) loading.value = false;
  }
}
function prepare(action: "provision" | "drain") {
  draft.value = { action, key: requestKey(), revision: props.vd.revision };
  commandError.value = "";
  confirmOpen.value = true;
}
async function submit() {
  if (!draft.value || busy.value) return;
  busy.value = true;
  commandError.value = "";
  try {
    await write(
      `virtual-devices/${props.vd.id}/${draft.value.action}`,
      "POST",
      { revision: draft.value.revision },
      draft.value.key,
    );
    confirmOpen.value = false;
    notice.value = "실행 요청을 저장했습니다. 실제 실행 상태를 확인하세요.";
    await refresh();
  } catch (cause) {
    commandError.value =
      cause instanceof Error ? cause.message : "실행 요청 실패";
  } finally {
    busy.value = false;
  }
}
const selectedOutput = ref(""),
  hours = ref(1),
  history = ref<Schema["ContinuousHistory"] | null>(null),
  historyBusy = ref(false),
  historyError = ref("");
let historyGeneration = 0;
async function chart(output: string) {
  selectedOutput.value = output;
  historyBusy.value = true;
  historyError.value = "";
  history.value = null;
  const request = ++historyGeneration;
  try {
    const period = Math.min(
        Number(hours.value),
        (outputs.value?.retentionDays || 7) * 24,
      ),
      to = new Date(),
      from = new Date(to.getTime() - period * 3600000),
      query = new URLSearchParams({
        from: from.toISOString(),
        to: to.toISOString(),
        limit: "2000",
        bucketSeconds: String(
          Math.max(
            outputs.value?.intervalSeconds || 1,
            Math.ceil((period * 3600) / 1000),
          ),
        ),
      });
    const value = await get<Schema["ContinuousHistory"]>(
      `virtual-devices/${props.vd.id}/outputs/${encodeURIComponent(output)}/history?${query}`,
    );
    if (request === historyGeneration) history.value = value;
  } catch (cause) {
    if (request === historyGeneration)
      historyError.value =
        cause instanceof Error ? cause.message : "기록 조회 실패";
  } finally {
    if (request === historyGeneration) historyBusy.value = false;
  }
}
onMounted(() => refresh());
usePoll(async () => {
  if (!busy.value && !loading.value) await refresh(false);
}, 3000);
</script>
