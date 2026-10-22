<template>
  <KsAlert
    v-if="error"
    type="error"
    :closable="false"
    showIcon
    role="alert"
    :title="error"
  />
  <p v-if="notice" role="status" class="native-notice">{{ notice }}</p>
</template>
<script setup lang="ts">
import { KsAlert } from "@kestra-io/design-system";
defineProps<{ error?: string; notice?: string }>();
</script>
