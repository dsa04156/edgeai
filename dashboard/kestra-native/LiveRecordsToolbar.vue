<template>
  <div class="native-toolbar">
    <KsCheckbox
      :modelValue="modelValue"
      @update:model-value="$emit('update:modelValue', Boolean($event))"
      >기록 자동 갱신 · {{ intervalSeconds }}초</KsCheckbox
    >
    <span class="native-note" role="status">
      {{
        offset > 0
          ? "이전 페이지 · 목록 자동 갱신 대기"
          : modelValue
            ? "자동 갱신 사용"
            : "자동 갱신 일시정지"
      }}
      · {{ updatedAt ? `${time(updatedAt)} 조회` : "첫 기록 조회 대기" }}
    </span>
    <KsButton v-if="offset > 0" :disabled="disabled" @click="$emit('latest')"
      >최신 기록 보기</KsButton
    >
  </div>
</template>
<script setup lang="ts">
import { KsButton, KsCheckbox } from "@kestra-io/design-system";
import { time } from "./native-api";
defineProps<{
  modelValue: boolean;
  intervalSeconds: number;
  offset: number;
  updatedAt: string;
  disabled?: boolean;
}>();
defineEmits<{ "update:modelValue": [value: boolean]; latest: [] }>();
</script>
