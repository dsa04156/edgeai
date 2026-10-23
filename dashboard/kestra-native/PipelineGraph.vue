<template>
  <div class="pipeline">
    <div class="pipeline-scroll">
      <div class="pipeline-plane" :style="{ width: `${width}px`, height: `${height}px` }">
        <svg :width="width" :height="height" class="pipeline-edges" aria-hidden="true">
          <defs>
            <marker id="pipeline-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10 z" />
            </marker>
          </defs>
          <path v-for="edge in edges" :key="`${edge.from}-${edge.to}`" :d="path(edge)" :class="edge.kind" marker-end="url(#pipeline-arrow)" />
        </svg>
        <ol class="pipeline-nodes" aria-label="워크플로 단계">
          <li
            v-for="node in ordered"
            :key="node.id"
            :class="[node.kind, { selected: node.id === selected, called: caller(node) }]"
            :style="{ left: `${x(node)}px`, top: `${y(node)}px`, width: `${W}px`, height: `${H}px` }"
          >
            <span v-if="caller(node)" class="pipeline-call">↳ {{ caller(node) }} 작업이 호출</span>
            <button type="button" :aria-pressed="node.id === selected" @click="$emit('select', node.id)">
              <small>{{ node.kindLabel }}</small>
              <strong>{{ node.label }}</strong>
              <span class="pipeline-status"><KsTag :type="node.tone" size="small">{{ statusLabel(node.status) }}</KsTag></span>
              <span class="pipeline-where">{{ node.location ?? (node.kind === "result" ? "MinIO" : "배치 관측 대기") }}</span>
            </button>
          </li>
        </ol>
      </div>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed } from "vue";
import { KsTag } from "@kestra-io/design-system";
import { statusLabel, type GraphEdge, type GraphNode } from "./hawkeye-video";

const props = defineProps<{ nodes: GraphNode[]; edges: GraphEdge[]; selected?: string }>();
defineEmits<{ select: [id: string] }>();
const W = 190, H = 120, GX = 44, GY = 28, PAD = 16;
const x = (n: GraphNode) => PAD + n.column * (W + GX);
const y = (n: GraphNode) => PAD + n.row * (H + GY);
const ordered = computed(() => [...props.nodes].sort((a, b) => a.column - b.column || a.row - b.row));
const width = computed(() => Math.max(...props.nodes.map((n) => x(n) + W + PAD), 0));
const height = computed(() => Math.max(...props.nodes.map((n) => y(n) + H + PAD), 0));
const caller = (n: GraphNode) => props.edges.find((e) => e.kind === "call" && e.to === n.id)?.from.replace(/^task:/, "");
function path(edge: GraphEdge) {
  const a = props.nodes.find((n) => n.id === edge.from), b = props.nodes.find((n) => n.id === edge.to);
  if (!a || !b) return "";
  if (edge.kind === "call") return `M${x(a) + W / 2},${y(a) + H} L${x(b) + W / 2},${y(b)}`;
  const x1 = x(a) + W, y1 = y(a) + H / 2, x2 = x(b), y2 = y(b) + H / 2;
  return `M${x1},${y1} C${x1 + GX / 2},${y1} ${x2 - GX / 2},${y2} ${x2},${y2}`;
}
</script>
<style scoped>
.pipeline-scroll {
  overflow-x: auto;
  background: var(--ks-bg-surface);
  border: 1px solid var(--ks-border-default);
  border-radius: var(--ks-radius-base);
}
.pipeline-plane {
  position: relative;
}
.pipeline-edges {
  position: absolute;
  inset: 0;
}
.pipeline-edges path {
  fill: none;
  stroke: var(--ks-chart-duration);
  stroke-width: 2;
}
.pipeline-edges path.call {
  stroke-dasharray: 6 4;
}
.pipeline-edges marker path {
  fill: var(--ks-chart-duration);
  stroke: none;
}
.pipeline-nodes {
  list-style: none;
  margin: 0;
  padding: 0;
}
.pipeline-nodes li {
  position: absolute;
}
.pipeline-nodes button {
  display: grid;
  align-content: start;
  gap: var(--ks-spacing-1);
  width: 100%;
  height: 100%;
  padding: var(--ks-spacing-3);
  text-align: left;
  color: var(--ks-text-primary);
  background: var(--ks-bg-base);
  border: 1px solid var(--ks-border-strong);
  border-radius: var(--ks-radius-base);
  box-shadow: 0 2px 6px var(--ks-shadow-element);
  cursor: pointer;
}
/* External stages are not DAG Tasks: dashed outline, no shadow. */
.pipeline-nodes li.external button,
.pipeline-nodes li.result button {
  border-style: dashed;
  box-shadow: none;
  background: var(--ks-bg-surface);
}
.pipeline-nodes li.selected button {
  border-color: var(--ks-border-focus);
  outline: 1px solid var(--ks-border-focus);
}
.pipeline-nodes button:focus-visible {
  outline: 2px solid var(--ks-border-focus);
  outline-offset: 2px;
}
.pipeline-nodes small,
.pipeline-where {
  color: var(--ks-text-secondary);
  font-size: var(--ks-font-size-xs);
  overflow-wrap: anywhere;
}
.pipeline-where {
  font-family: monospace;
}
.pipeline-call {
  display: none;
}
@media (max-width: 720px) {
  .pipeline-scroll {
    overflow: visible;
    border: 0;
    background: transparent;
  }
  .pipeline-plane {
    width: auto !important;
    height: auto !important;
  }
  .pipeline-edges {
    display: none;
  }
  .pipeline-nodes {
    display: grid;
    gap: var(--ks-spacing-5);
  }
  .pipeline-nodes li {
    position: relative;
    left: auto !important;
    top: auto !important;
    width: auto !important;
    height: auto !important;
  }
  .pipeline-nodes li + li::before {
    content: "↓";
    position: absolute;
    top: calc(-1 * var(--ks-spacing-5));
    left: var(--ks-spacing-4);
    color: var(--ks-chart-duration);
  }
  /* The called accelerator is listed last; its link is the caption, not the down arrow. */
  .pipeline-nodes li.called {
    order: 1;
  }
  .pipeline-nodes li.called::before {
    content: none;
  }
  .pipeline-call {
    display: inline;
    position: absolute;
    top: calc(-1 * var(--ks-spacing-5));
    left: var(--ks-spacing-4);
    color: var(--ks-text-secondary);
    font-size: var(--ks-font-size-xs);
  }
}
</style>
