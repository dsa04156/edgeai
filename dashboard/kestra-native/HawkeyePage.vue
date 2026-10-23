<template>
  <TopNavBar title="창고 지게차 안전 점검" />
  <main class="native-page">
    <KsSegmented
      :modelValue="mode"
      :options="[
        { value: 'video', label: '영상 점검' },
        { value: 'samples', label: '샘플 이미지 점검' },
      ]"
      aria-label="점검 방식"
      @update:model-value="pick"
    />
    <HawkeyeVideoPanel v-if="mode === 'video'" />
    <HawkeyeSamplePanel v-else />
  </main>
</template>
<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { KsSegmented } from "@kestra-io/design-system";
import TopNavBar from "kestra-original/components/layout/TopNavBar.vue";
import useRouteContext from "kestra-original/composables/useRouteContext";
import HawkeyeSamplePanel from "./HawkeyeSamplePanel.vue";
import HawkeyeVideoPanel from "./HawkeyeVideoPanel.vue";

useRouteContext(computed(() => ({ title: "창고 지게차 안전 점검" })));
const route = useRoute(),
  router = useRouter();
// The video workflow is the default; the fixed samples stay reachable as a secondary check.
const mode = computed(() => (route.query.mode === "samples" ? "samples" : "video"));
const pick = (value: unknown) =>
  router.replace({ query: value === "samples" ? { mode: "samples" } : {} });
</script>
