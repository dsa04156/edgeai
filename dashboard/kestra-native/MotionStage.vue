<template>
  <figure class="ms" :style="{ '--ar': `${w} / ${h}`, '--arn': String(w / h) }">
    <div class="ms-frame">
      <img v-if="src" :src="src" :alt="alt" :class="{ fit: !observation }" />
      <p v-else class="ms-empty" :class="{ failed }" role="status">{{ placeholder }}</p>
      <!-- Drawn in the frame's own pixel space, so boxes and paths stay on the image they were computed from. -->
      <svg
        v-if="src && view"
        class="ms-overlay"
        :viewBox="view.viewBox"
        preserveAspectRatio="none"
        role="img"
        :aria-label="`탐지 ${view.boxes.length}개 · 추적 끊김 ${view.lost.length}개 · 근접 표시 ${view.pairs.length}개`"
      >
        <polyline v-for="p in view.history" :key="`h-${p.id}`" :data-history="p.id" :class="['ms-path', p.cls]" :points="p.points" />
        <g v-for="p in view.future" :key="`f-${p.id}`" :class="['ms-future', p.cls]">
          <polyline :data-future="p.id" :points="p.points" />
          <circle v-for="([x, y], i) in p.steps" :key="i" :cx="x" :cy="y" :r="dot" />
        </g>
        <g v-for="b in view.boxes" :key="`b-${b.id}`" :class="['ms-box', b.cls]">
          <rect :data-track="b.id" :x="b.x" :y="b.y" :width="b.w" :height="b.h" />
          <text :x="b.x" :y="Math.max(b.y - dot, font)" :font-size="font">{{ b.label }}</text>
        </g>
        <g v-for="t in view.lost" :key="`l-${t.id}`" :class="['ms-lost', t.cls]">
          <circle :data-lost="t.id" :cx="t.x" :cy="t.y" :r="dot * 3" />
          <text :x="t.x" :y="Math.max(t.y - dot * 4, font)" :font-size="font" text-anchor="middle">{{ t.label }}</text>
        </g>
        <g v-for="p in view.pairs" :key="`p-${p.key}`" :class="['ms-pair', p.level]" :data-pair="p.key">
          <line :x1="p.from[0]" :y1="p.from[1]" :x2="p.to[0]" :y2="p.to[1]" />
          <circle :cx="p.from[0]" :cy="p.from[1]" :r="dot * 1.6" />
          <circle :cx="p.to[0]" :cy="p.to[1]" :r="dot * 1.6" />
          <text :x="(p.from[0] + p.to[0]) / 2" :y="(p.from[1] + p.to[1]) / 2 - dot * 2" :font-size="font" text-anchor="middle">{{ p.label }}</text>
        </g>
      </svg>
      <span v-if="badge" :class="['ms-badge', badge.tone]" data-testid="stage-badge">{{ badge.text }}</span>
    </div>
    <figcaption v-if="$slots.default"><slot /></figcaption>
  </figure>
</template>
<script setup lang="ts">
import { computed } from "vue";
import { motionOverlay, type Observation, type Tone } from "./hawkeye-video";

const props = defineProps<{
  src: string | null;
  alt: string;
  observation?: Observation | null;
  placeholder?: string;
  failed?: boolean;
  badge?: { text: string; tone: Tone } | null;
}>();
const view = computed(() => (props.observation ? motionOverlay(props.observation) : null));
const w = computed(() => props.observation?.width || 16);
const h = computed(() => props.observation?.height || 9);
const font = computed(() => Math.max(10, Math.round(h.value / 32)));
const dot = computed(() => Math.max(2, Math.round(h.value / 180)));
</script>
<style scoped>
.ms {
  margin: 0;
  display: grid;
  gap: var(--ks-spacing-2);
}
/* Desktop clips stay short enough to keep controls and risk on screen; narrow screens keep the aspect ratio. */
.ms-frame {
  position: relative;
  aspect-ratio: var(--ar);
  width: min(100%, calc(420px * var(--arn)));
  margin-inline: auto;
  overflow: hidden;
  background: var(--ks-bg-base);
  border: 1px solid var(--ks-border-default);
  border-radius: var(--ks-radius-base);
}
.ms-frame img,
.ms-overlay {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}
.ms-frame img {
  object-fit: fill;
}
.ms-frame img.fit {
  object-fit: contain;
}
.ms-empty {
  display: grid;
  place-items: center;
  height: 100%;
  margin: 0;
  padding: var(--ks-spacing-3);
  text-align: center;
  color: var(--ks-text-secondary);
}
.ms-empty.failed {
  color: var(--ks-text-error);
  overflow-wrap: anywhere;
}
.ms-overlay * {
  vector-effect: non-scaling-stroke;
}
.ms-overlay .forklift {
  --c: var(--ks-log-warn);
}
.ms-overlay .person {
  --c: var(--ks-log-info);
}
.ms-path,
.ms-future polyline {
  fill: none;
  stroke: var(--c);
  stroke-width: 2.5;
  stroke-linejoin: round;
}
.ms-future polyline {
  stroke-dasharray: 6 5;
}
.ms-future circle {
  fill: var(--c);
}
.ms-box rect {
  fill: none;
  stroke: var(--c);
  stroke-width: 2;
}
.ms-lost circle {
  fill: none;
  stroke: var(--c);
  stroke-width: 2;
  stroke-dasharray: 2 3;
}
.ms-overlay text {
  fill: var(--c, var(--ks-text-primary));
  paint-order: stroke;
  stroke: rgb(0 0 0 / 0.75);
  stroke-width: 3px;
  font-weight: 600;
}
.ms-pair {
  --c: var(--ks-log-error);
}
.ms-pair.APPROACHING {
  --c: var(--ks-log-warn);
}
.ms-pair line {
  stroke: var(--c);
  stroke-width: 2;
  stroke-dasharray: 3 3;
}
.ms-pair circle {
  fill: none;
  stroke: var(--c);
  stroke-width: 2;
}
.ms-badge {
  position: absolute;
  top: var(--ks-spacing-2);
  left: var(--ks-spacing-2);
  padding: 2px var(--ks-spacing-2);
  font-size: var(--ks-font-size-xs);
  font-weight: 600;
  color: #fff;
  background: rgb(0 0 0 / 0.65);
  border-left: 3px solid var(--ks-chart-duration);
  border-radius: var(--ks-radius-base);
}
.ms-badge.success {
  border-left-color: var(--ks-chart-success);
}
.ms-badge.warning {
  border-left-color: var(--ks-log-warn);
}
.ms-badge.danger {
  border-left-color: var(--ks-chart-failed);
}
figcaption {
  color: var(--ks-text-secondary);
  font-size: var(--ks-font-size-xs);
}
</style>
