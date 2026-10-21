<template>
  <span v-if="!devices?.length">—</span>
  <div
    v-for="device in devices"
    :key="`${device.kind}:${device.slot}`"
    class="accelerator-cell"
  >
    <p class="identity">
      <span class="kind">{{ device.kind }}</span>
      <strong>{{ device.model }}</strong>
      <span v-if="device.modelNote" class="kind">{{ device.modelNote }}</span>
    </p>
    <UsageMeter
      :value="device.usagePercent"
      :label="`${device.node} ${device.kind} ${device.model}`"
    />
    <p v-if="details(device).length" class="details">
      <span v-for="detail in details(device)" :key="detail">{{ detail }}</span>
    </p>
    <p v-if="device.utilizationNote" class="details">
      {{ device.utilizationNote }}
    </p>
  </div>
</template>

<script setup lang="ts">
import type { Overview } from "./overview";
import UsageMeter from "./UsageMeter.vue";
type Accelerator = NonNullable<Overview["gpus"]>[number] & {
  kind: "GPU" | "NPU";
};
defineProps<{ devices: Accelerator[] | null }>();
// Unmeasured values are omitted; a measured 0 is still shown.
function details(device: Accelerator) {
  const gib = (value: number | null) =>
    value === null ? "—" : (value / 1024 ** 3).toFixed(2);
  return [
    device.memoryUsedBytes === null && device.memoryTotalBytes === null
      ? null
      : `메모리 ${gib(device.memoryUsedBytes)} / ${gib(device.memoryTotalBytes)} GiB`,
    device.temperatureC === null
      ? null
      : `${device.temperatureC.toFixed(1)} °C`,
    device.powerWatts === null ? null : `${device.powerWatts.toFixed(1)} W`,
  ].filter((detail): detail is string => detail !== null);
}
</script>

<style scoped lang="scss">
.accelerator-cell {
  min-width: 0;
  max-width: 100%;
  padding: var(--ks-spacing-2) 0;
  font-variant-numeric: tabular-nums;
  white-space: normal;
}
.accelerator-cell + .accelerator-cell {
  border-top: 1px solid var(--ks-border-default);
}
p {
  margin: 0;
}
.identity {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--ks-spacing-2);
  margin-bottom: var(--ks-spacing-1);
  min-width: 0;
}
.kind {
  flex: none;
  font-size: var(--ks-font-size-xs);
  color: var(--ks-text-secondary);
}
strong {
  min-width: 0;
  overflow-wrap: anywhere;
  font-weight: var(--ks-font-weight-bold);
  color: var(--ks-text-primary);
}
.details {
  display: flex;
  flex-wrap: wrap;
  column-gap: var(--ks-spacing-3);
  margin-top: var(--ks-spacing-1);
  color: var(--ks-text-secondary);
  font-size: var(--ks-font-size-xs);
}
</style>
