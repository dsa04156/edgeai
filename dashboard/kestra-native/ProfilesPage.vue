<template>
  <TopNavBar title="실행 모듈 관리"
    ><template #actions
      ><KsButton :disabled="busy" @click="act(() => load(offset))"
        >새로고침</KsButton
      ><KsButton type="primary" :disabled="busy" @click="create()"
        >새 버전 등록</KsButton
      ></template
    ></TopNavBar
  >
  <main class="native-page">
    <NativeFeedback :error="error" :notice="notice" />
    <div class="native-toolbar">
      <KsInput
        v-model="filter"
        placeholder="정확한 키로 찾기"
        aria-label="프로필 키 검색"
        clearable
      /><KsButton :disabled="busy" @click="act(() => load(0))">조회</KsButton>
    </div>
    <NativeRecords
      title="실행 모듈 버전"
      :rows="items"
      :columns="[
        { key: 'key', label: '키 / 버전', width: 240 },
        { key: 'digest', label: '내용 digest', width: 250 },
        { key: 'createdAt', label: '발행일' },
      ]"
      :busy="busy"
      :offset="offset"
      :nextOffset="nextOffset"
      @page="(value) => act(() => load(value))"
    >
      <template #key="{ row }"
        ><KsButton link @click="act(() => show(row))"
          >{{ row.key }} · {{ row.version }}</KsButton
        ></template
      ><template #digest="{ row }">{{ row.digest.slice(0, 22) }}…</template
      ><template #createdAt="{ row }">{{ time(row.createdAt) }}</template>
      <template #actions="{ row }"
        ><KsButton :disabled="busy" @click="create(row)"
          >새 버전으로 수정</KsButton
        ><KsButton
          type="danger"
          :disabled="busy"
          @click="
            deletion = row;
            deleteOpen = true;
            deleteError = '';
          "
          >삭제</KsButton
        ></template
      >
    </NativeRecords>
    <section v-if="detail" class="native-panel native-detail">
      <div class="native-toolbar">
        <h2>{{ detail.key }} · {{ detail.version }}</h2>
        <KsButton @click="detail = null">상세 닫기</KsButton
        ><KsButton @click="create(detail)">새 버전으로 수정</KsButton
        ><RouterLink
          v-if="kind === 'SERVICE'"
          :to="{
            name: 'edgeai/workflows',
            params: { tenant: route.params.tenant },
            query: { service: detail.id },
          }"
          >워크플로에서 사용</RouterLink
        >
      </div>
      <p class="native-note">
        발행된 규격입니다. 변경 내용은 새로운 버전으로 발행합니다.
      </p>
      <dl>
        <div>
          <dt>버전 ID</dt>
          <dd>{{ detail.id }}</dd>
        </div>
        <div>
          <dt>digest</dt>
          <dd>{{ detail.digest }}</dd>
        </div>
      </dl>
      <pre class="native-json" aria-label="발행된 JSON 규격">{{
        pretty(detail.spec)
      }}</pre>
    </section>
  </main>
  <KsDrawer
    v-model="formOpen"
    title="실행 모듈 버전 발행"
    class="native-dialog"
    :beforeClose="closeForm"
    :showClose="!saving"
    :closeOnClickModal="!saving"
    :closeOnPressEscape="!saving"
  >
    <p v-if="editing" class="native-note">
      {{ editing.key }} · {{ editing.version }}에서 새 버전을 만듭니다. 기존
      사용 중인 버전은 유지됩니다.
    </p>
    <NativeFeedback :error="formError" />
    <KsForm
      id="profile-publish-form"
      labelPosition="top"
      size="large"
      @submit.prevent="publish"
      class="native-form"
      ><fieldset :disabled="saving">
        <div class="native-grid">
          <KsFormItem label="키" required
            ><KsInput
              v-model="profileKey"
              :disabled="saving"
              required
              maxlength="100"
              pattern="[a-z][a-z0-9]*([._\-][a-z0-9]+)*"
              aria-label="프로필 키" /></KsFormItem
          ><KsFormItem label="버전" required
            ><KsInput
              v-model="version"
              :disabled="saving"
              required
              maxlength="32"
              pattern="[0-9]+\.[0-9]+\.[0-9]+"
              aria-label="프로필 버전"
          /></KsFormItem>
        </div>
        <ProfileSpec
          v-model="specText"
          :kind="kind"
          :nodes="nodes"
          :disabled="saving"
        />
        <details>
          <summary>전체 JSON 규격 직접 편집</summary>
          <KsInput
            v-model="specText"
            :disabled="saving"
            type="textarea"
            :rows="18"
            aria-label="프로필 JSON 규격"
            spellcheck="false"
          />
        </details></fieldset
    ></KsForm>
    <template #footer
      ><KsButton :disabled="saving" @click="formOpen = false">취소</KsButton
      ><KsButton
        type="primary"
        nativeType="submit"
        form="profile-publish-form"
        :disabled="saving"
        :loading="saving"
        >버전 발행</KsButton
      ></template
    >
  </KsDrawer>
  <ConfirmAction
    v-model="deleteOpen"
    title="프로필 버전 삭제"
    :subject="deletion ? `${deletion.key} · ${deletion.version}` : ''"
    description="이 버전을 삭제합니다. 사용 중인 워크플로·가상 장치가 있으면 서버가 삭제를 거절합니다."
    danger
    :busy="saving"
    :error="deleteError"
    @confirm="remove"
  />
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink, useRoute } from "vue-router";
import { parse } from "lossless-json";
import {
  KsButton,
  KsDrawer,
  KsForm,
  KsFormItem,
  KsInput,
} from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import {
  request,
  writeResponse,
  write,
  profileItems,
  all,
  objectJson,
  pretty,
  time,
  useAction,
  type Schema,
} from "./native-api";
import { nextProfileVersion } from "../lib/profile-version";
import NativeFeedback from "./NativeFeedback.vue";
import NativeRecords from "./NativeRecords.vue";
import ConfirmAction from "./ConfirmAction.vue";
import ProfileSpec from "./ProfileSpec.vue";
const route = useRoute();
const kind = ref<"SERVICE">("SERVICE");
useRouteContext(
  computed(() => ({
    title: "실행 모듈 관리",
  })),
);
const { busy, error, notice, act } = useAction();
const items = ref<Schema["ProfileVersion"][]>([]),
  choices = ref<Schema["ProfileVersion"][]>([]),
  nodes = ref<Schema["ExecutionNode"][]>([]);
const offset = ref(0),
  nextOffset = ref<number | null>(null),
  filter = ref(""),
  detail = ref<Schema["ProfileVersion"] | null>(null);
const formOpen = ref(false),
  saving = ref(false),
  formError = ref(""),
  profileKey = ref(""),
  version = ref("1.0.0"),
  specText = ref(""),
  editing = ref<Schema["ProfileVersion"] | null>(null),
  deletion = ref<Schema["ProfileVersion"] | null>(null),
  deleteOpen = ref(false),
  deleteError = ref("");
function defaults() {
  return {
    apiVersion: "edgeai/v1",
    image: "",
    command: ["python3", "/opt/edgeai/examples/linear.py"],
    args: [],
    resources: {
      requests: { cpu: "100m", memory: "64Mi" },
      limits: { cpu: "1", memory: "256Mi" },
    },
    platform: { os: "linux", architectures: ["amd64"] },
    inputs: {},
    outputs: {
      output: { mediaType: "application/json", maxBytes: 1048576 },
    },
    timeoutSeconds: 120,
    qos: "Burstable",
  };
}
async function load(value = 0) {
  const page = parse(
    await (
      await request(
        `profiles/${kind.value}?limit=20&offset=${value}${filter.value ? "&key=" + encodeURIComponent(filter.value.trim()) : ""}`,
      )
    ).text(),
  ) as { items: Schema["ProfileVersion"][]; nextOffset: number | null };
  items.value = page.items;
  offset.value = value;
  nextOffset.value = page.nextOffset == null ? null : Number(page.nextOffset);
}
async function show(profile: Schema["ProfileVersion"]) {
  detail.value = parse(
    await (
      await request(
        `profiles/${profile.kind}/${profile.key}/versions/${profile.version}`,
      )
    ).text(),
  ) as Schema["ProfileVersion"];
}
async function references() {
  const results = await Promise.allSettled([
    profileItems("SERVICE"),
    all<Schema["ExecutionNode"]>("nodes"),
  ]);
  if (results[0].status === "fulfilled") choices.value = results[0].value;
  if (results[1].status === "fulfilled") nodes.value = results[1].value;
}
function create(profile?: Schema["ProfileVersion"]) {
  editing.value = profile || null;
  profileKey.value = profile?.key || "";
  version.value = profile
    ? nextProfileVersion(profile, [...items.value, ...choices.value])
    : "1.0.0";
  specText.value = pretty(profile?.spec || defaults());
  formError.value = "";
  formOpen.value = true;
  void references();
}
function closeForm(done: () => void) {
  if (!saving.value) done();
}
async function publish() {
  if (saving.value) return;
  saving.value = true;
  formError.value = "";
  try {
    const spec = objectJson(specText.value);
    if (!Object.keys(spec).length)
      throw new Error("규격은 비어 있지 않은 JSON 객체여야 합니다.");
    const response = await writeResponse(`profiles/${kind.value}`, "POST", {
      key: profileKey.value,
      version: version.value,
      spec,
    });
    detail.value = parse(await response.text()) as Schema["ProfileVersion"];
    formOpen.value = false;
    notice.value = "버전을 발행했습니다.";
    try {
      await load(0);
    } catch {
      error.value =
        "발행은 완료됐지만 목록 갱신에 실패했습니다. 새로고침하세요.";
    }
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : "발행 실패";
  } finally {
    saving.value = false;
  }
}
async function remove() {
  if (!deletion.value || saving.value) return;
  saving.value = true;
  deleteError.value = "";
  try {
    const value = deletion.value;
    await write(
      `profiles/${value.kind}/${value.key}/versions/${value.version}`,
      "DELETE",
    );
    deleteOpen.value = false;
    if (detail.value?.id === value.id) detail.value = null;
    notice.value = "프로필 버전을 삭제했습니다.";
    await load(
      items.value.length === 1 ? Math.max(0, offset.value - 20) : offset.value,
    );
  } catch (cause) {
    deleteError.value = cause instanceof Error ? cause.message : "삭제 실패";
  } finally {
    saving.value = false;
  }
}
onMounted(() => {
  void act(() => load(0));
  void references();
});
</script>
