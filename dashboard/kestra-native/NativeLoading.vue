<template>
  <div
    v-if="busy"
    v-ks-loading="true"
    class="native-loading"
    role="status"
    aria-live="polite"
    aria-atomic="true"
    :aria-label="label"
    :element-loading-text="label"
  />
</template>
<script setup lang="ts">
import { vKsLoading } from "@kestra-io/design-system";
withDefaults(defineProps<{ busy: boolean; label?: string }>(), {
  label: "데이터를 불러오는 중…",
});
</script>
<style scoped>
.native-loading {
  position: relative;
  min-height: 112px;
  flex: 0 0 auto;
}
</style>
