<template>
  <section class="native-panel">
    <h2>카메라 영상</h2>
    <NativeFeedback :error="error" /><KsButton :disabled="busy" @click="refresh"
      >영상 새로고침</KsButton
    >
    <p v-if="busy" role="status">영상 수신 확인 중…</p>
    <img v-if="image" :src="image" :alt="`${deviceId} 카메라 최신 영상`" />
    <p v-if="received" class="native-note">
      {{ time(received) }} 수신한 정지 영상
    </p>
  </section>
</template>
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { KsButton } from "@kestra-io/design-system";
import { get, request, time, type Schema } from "./native-api";
import NativeFeedback from "./NativeFeedback.vue";
const props = defineProps<{ deviceId: string }>(),
  image = ref(""),
  busy = ref(false),
  error = ref(""),
  received = ref("");
let active = true;
async function refresh() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    const device = await get<Schema["ManagedDevice"]>(
      `managed-devices/${encodeURIComponent(props.deviceId)}`,
    );
    const response = await request(
      `managed-devices/${encodeURIComponent(props.deviceId)}/preview?uid=${encodeURIComponent(device.uid)}`,
    );
    if (!response.headers.get("content-type")?.startsWith("image/"))
      throw new Error("카메라 영상 형식을 확인하세요.");
    const blob = await response.blob();
    if (!active) return;
    const next = URL.createObjectURL(blob);
    if (image.value) URL.revokeObjectURL(image.value);
    image.value = next;
    received.value = new Date().toISOString();
  } catch (cause) {
    if (active)
      error.value = cause instanceof Error ? cause.message : "영상 조회 실패";
  } finally {
    if (active) busy.value = false;
  }
}
onMounted(refresh);
onBeforeUnmount(() => {
  active = false;
  if (image.value) URL.revokeObjectURL(image.value);
});
</script>
<style scoped>
img {
  display: block;
  max-width: 100%;
  height: auto;
  margin-top: 1rem;
}
</style>
