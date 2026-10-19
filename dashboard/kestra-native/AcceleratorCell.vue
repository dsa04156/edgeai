<template>
  <span v-if="!devices?.length">—</span>
  <details
    v-for="device in devices"
    :key="`${device.kind}:${device.slot}`"
    class="accelerator-cell"
  >
    <summary>
      <span>{{ device.kind }} · {{ device.model }}</span
      ><UsageMeter
        :value="device.usagePercent"
        :label="`${device.node} ${device.kind} ${device.model}`"
      />
    </summary>
    <p v-if="device.utilizationNote">{{ device.utilizationNote }}</p>
    <dl>
      <div>
        <dt>메모리 사용/총량</dt>
        <dd>{{ memory(device) }}</dd>
      </div>
      <div>
        <dt>온도</dt>
        <dd>
          {{
            device.temperatureC === null
              ? "—"
              : `${device.temperatureC.toFixed(1)} °C`
          }}
        </dd>
      </div>
      <div>
        <dt>전력</dt>
        <dd>
          {{
            device.powerWatts === null
              ? "—"
              : `${device.powerWatts.toFixed(1)} W`
          }}
        </dd>
      </div>
    </dl>
  </details>
</template>

<script setup lang="ts">
import type { Overview } from "./overview";
import UsageMeter from "./UsageMeter.vue";
type Accelerator = NonNullable<Overview["gpus"]>[number] & {
  kind: "GPU" | "NPU";
};
defineProps<{ devices: Accelerator[] | null }>();
function memory(device: Accelerator) {
  const gib = (value: number | null) =>
    value === null ? "—" : (value / 1024 ** 3).toFixed(2);
  return device.memoryUsedBytes === null && device.memoryTotalBytes === null
    ? "—"
    : `${gib(device.memoryUsedBytes)} / ${gib(device.memoryTotalBytes)} GiB`;
}
</script>

<style scoped lang="scss">
.accelerator-cell {
  font-variant-numeric: tabular-nums;
  white-space: normal;
}
summary {
  cursor: pointer;
  min-height: 44px;
  padding: var(--ks-spacing-2) 0;
  align-content: center;
}
summary > span:first-child {
  font-size: var(--ks-font-size-xs);
  color: var(--ks-text-secondary);
}
summary:focus-visible {
  outline: 2px solid var(--ks-chart-running);
  outline-offset: 2px;
}
p,
dl {
  margin: 0 0 var(--ks-spacing-2);
  color: var(--ks-text-secondary);
  font-size: var(--ks-font-size-xs);
}
dl div {
  display: flex;
  justify-content: space-between;
  gap: var(--ks-spacing-2);
}
dt {
  font-weight: var(--ks-font-weight-regular);
}
dd {
  margin: 0;
  color: var(--ks-text-primary);
}
</style>
