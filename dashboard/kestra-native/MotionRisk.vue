<template>
  <section class="mr" aria-label="충돌 위험 예측" aria-live="polite">
    <p class="mr-head">
      <KsTag :type="risk.tone">{{ risk.label }}</KsTag>
      <span v-if="observation" class="native-note">프레임 {{ observation.seq }}{{ observation.detectMs == null ? "" : ` · 탐지 ${observation.detectMs.toFixed(0)} ms` }}</span>
    </p>
    <p v-if="risk.reset" class="native-note">{{ risk.reset }}</p>
    <ul v-if="risk.lines.length" class="mr-pairs">
      <li v-for="line in risk.lines" :key="line">{{ line }}</li>
    </ul>
    <p class="native-note mr-units">
      실선은 지난 이동, 점선은 3초 앞까지의 예상 경로입니다. 거리는 영상 속 사람 키의 배수, 시간은 영상 좌표로 추정한 최근접 시점이며 실제 충돌 시각·미터 거리가 아닙니다. %는 탐지 신뢰도입니다.
    </p>
  </section>
</template>
<script setup lang="ts">
import { computed } from "vue";
import { KsTag } from "@kestra-io/design-system";
import { riskView, type Observation } from "./hawkeye-video";

const props = defineProps<{ observation: Observation | null }>();
const risk = computed(() => riskView(props.observation));
</script>
<style scoped>
.mr {
  display: grid;
  gap: var(--ks-spacing-1);
}
.mr-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ks-spacing-2);
  margin: 0;
}
.mr-pairs {
  margin: 0;
  padding-left: var(--ks-spacing-4);
  overflow-wrap: anywhere;
}
.mr p {
  margin: 0;
}
.mr-units {
  font-size: var(--ks-font-size-xs);
}
</style>
