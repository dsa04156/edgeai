<template>
  <KsDrawer
    v-model="open"
    :title="title"
    class="sm"
    :beforeClose="close"
    :showClose="!busy"
    :closeOnClickModal="!busy"
    :closeOnPressEscape="!busy"
    @closed="$emit('closed')"
  >
    <p>{{ description }}</p>
    <p v-if="subject">
      <strong>{{ subject }}</strong>
    </p>
    <NativeFeedback :error="error" /><slot />
    <template #footer
      ><KsButton :disabled="busy" @click="open = false">취소</KsButton
      ><KsButton
        :type="danger ? 'danger' : 'primary'"
        :disabled="busy"
        :loading="busy"
        @click="$emit('confirm')"
        >{{ busy ? "처리 중…" : confirmLabel || "확인" }}</KsButton
      ></template
    >
  </KsDrawer>
</template>
<script setup lang="ts">
import { computed } from "vue";
import { KsDrawer, KsButton } from "@kestra-io/design-system";
import NativeFeedback from "./NativeFeedback.vue";
const props = defineProps<{
  modelValue: boolean;
  title: string;
  description: string;
  subject?: string;
  busy?: boolean;
  error?: string;
  danger?: boolean;
  confirmLabel?: string;
}>();
const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  confirm: [];
  closed: [];
}>();
const open = computed({
  get: () => props.modelValue,
  set: (value) => {
    if (!props.busy) emit("update:modelValue", value);
  },
});
function close(done: () => void) {
  if (!props.busy) done();
}
</script>
