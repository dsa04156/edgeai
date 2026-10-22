<template>
  <div class="native-shell-footer">
    <a :href="docsUrl" target="_blank" rel="noopener">사용 안내 · 문서 ↗</a
    ><KsButton @click="cycleTheme">화면 테마 변경</KsButton
    ><small>EdgeAI · Kestra 2.0.6</small>
  </div>
</template>
<script setup lang="ts">
import { KsButton } from "@kestra-io/design-system";
import { switchTheme } from "kestra-original/utils/utils";
import { useMiscStore } from "override/stores/misc";
const misc = useMiscStore();
function cycleTheme() {
  switchTheme(misc, misc.theme === "light" ? "dark" : "light");
}
// The former Next.js /docs site is retired with the console; the source documents stay in Git.
const docsUrl = "https://github.com/dsa04156/edgeai/tree/main/docs";
</script>
<style scoped>
.native-shell-footer {
  display: flex;
  flex-direction: column;
  gap: var(--ks-spacing-3);
  padding: var(--ks-spacing-3);
  color: var(--ks-text-secondary);
}
.native-shell-footer a {
  color: var(--ks-text-link);
  min-height: 44px;
  display: flex;
  align-items: center;
}
</style>
