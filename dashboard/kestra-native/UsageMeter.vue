<template>
  <span
    class="usage-meter"
    :aria-label="value === null ? `${label} 사용률 미수집` : undefined"
  >
    <meter
      v-if="value !== null"
      :min="0"
      :max="100"
      :value
      :aria-label="`${label} 사용률`"
    >
      {{ value.toFixed(1) }}%
    </meter>
    <span v-else class="unavailable" aria-hidden="true" />
    <strong>{{ value === null ? "—" : `${value.toFixed(1)}%` }}</strong>
  </span>
</template>

<script setup lang="ts">
defineProps<{ value: number | null; label: string }>();
</script>

<style scoped lang="scss">
.usage-meter {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--ks-spacing-2);
  font-variant-numeric: tabular-nums;
}
strong {
  min-width: 48px;
  text-align: right;
  font-weight: var(--ks-font-weight-medium);
}
meter,
.unavailable {
  width: 100%;
  height: 6px;
  border: 0;
  border-radius: var(--ks-radius-base);
  background: var(--ks-border-default);
  appearance: none;
  overflow: hidden;
}
meter::-webkit-meter-bar {
  border: 0;
  border-radius: var(--ks-radius-base);
  background: var(--ks-border-default);
}
meter::-webkit-meter-optimum-value {
  background: var(--ks-chart-duration);
}
meter::-moz-meter-bar {
  background: var(--ks-chart-duration);
}
.unavailable {
  border: 1px solid var(--ks-border-default);
}
</style>
