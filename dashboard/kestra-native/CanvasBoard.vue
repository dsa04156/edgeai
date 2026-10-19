<template>
  <div class="canvas-wrap">
    <div class="native-toolbar canvas-tools">
      <KsButton :disabled="disabled" @click="$emit('arrange')"
        >자동 정렬</KsButton
      ><KsButton @click="scale = Math.max(0.4, scale - 0.2)">축소</KsButton
      ><KsButton @click="scale = Math.min(2, scale + 0.2)">확대</KsButton
      ><KsButton @click="scale = 1">100%</KsButton
      ><span class="native-note"
        >출력 포트 → 입력 포트를 선택해 연결합니다. 작업 제목을 끌어 이동할 수
        있습니다.</span
      >
    </div>
    <div class="canvas-scroll" @dragover.prevent @drop.prevent="drop">
      <div
        class="canvas"
        :style="{ width: `${width * scale}px`, height: `${height * scale}px` }"
      >
        <div
          :style="{
            width: `${width}px`,
            height: `${height}px`,
            transform: `scale(${scale})`,
            transformOrigin: 'top left',
          }"
          class="canvas-plane"
        >
          <svg
            :width="width"
            :height="height"
            aria-label="작업 연결"
            class="canvas-edges"
          >
            <defs>
              <marker
                id="native-arrow"
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="6"
                markerHeight="6"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" />
              </marker>
            </defs>
            <path
              v-for="edge in edges"
              :key="edge.id"
              :d="path(edge)"
              tabindex="0"
              role="button"
              :aria-label="`${edge.source}에서 ${edge.target} 연결 삭제`"
              @click="$emit('remove-edge', edge.id)"
              @keydown.enter="$emit('remove-edge', edge.id)"
              @keydown.delete="$emit('remove-edge', edge.id)"
              marker-end="url(#native-arrow)"
            />
          </svg>
          <section
            v-for="node in nodes"
            :key="node.id"
            class="canvas-node"
            :class="{ selected: selected === node.id }"
            :style="{ left: `${node.x}px`, top: `${node.y}px` }"
            :aria-label="node.label"
          >
            <button
              class="canvas-node-title"
              :disabled="disabled"
              @click="selectNode(node.id)"
              @pointerdown="start($event, node)"
              @pointermove="move"
              @pointerup="finish"
              @keydown="keyboard($event, node)"
            >
              <strong>{{ node.label }}</strong
              ><small>{{ node.subtitle }}</small>
            </button>
            <div class="canvas-node-ports">
              <div>
                <button
                  v-for="port in node.inputs"
                  :key="port"
                  :disabled="disabled || !pending"
                  @click="connect(node.id, port)"
                  :aria-label="`${node.label} 입력 ${port}`"
                >
                  ● {{ port }}
                </button>
              </div>
              <div>
                <button
                  v-for="port in node.outputs"
                  :key="port"
                  :disabled="disabled"
                  :class="{
                    active: pending?.node === node.id && pending.port === port,
                  }"
                  @click="pending = { node: node.id, port }"
                  :aria-label="`${node.label} 출력 ${port}`"
                >
                  {{ port }} ●
                </button>
              </div>
            </div>
          </section>
          <p v-if="!nodes.length" class="canvas-empty">
            실행 모듈을 추가하거나 파일을 불러오세요.
          </p>
        </div>
      </div>
    </div>
    <p v-if="pending" class="native-note" role="status">
      {{ pending.node }} · {{ pending.port }} 출력 선택됨
      <KsButton link @click="pending = null">연결 취소</KsButton>
    </p>
  </div>
</template>
<script setup lang="ts">
import { computed, ref } from "vue";
import { KsButton } from "@kestra-io/design-system";
export type CanvasNode = {
  id: string;
  label: string;
  subtitle?: string;
  inputs: string[];
  outputs: string[];
  x: number;
  y: number;
};
export type CanvasEdge = {
  id: string;
  source: string;
  target: string;
  sourcePort?: string;
  targetPort?: string;
};
const props = defineProps<{
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  selected?: string;
  disabled?: boolean;
}>();
const emit = defineEmits<{
  select: [id: string];
  move: [id: string, x: number, y: number];
  connect: [source: string, port: string, target: string, targetPort: string];
  "remove-edge": [id: string];
  arrange: [];
  drop: [profile: string, x: number, y: number];
}>();
const scale = ref(1),
  pending = ref<{ node: string; port: string } | null>(null);
const width = computed(() =>
    Math.max(900, ...props.nodes.map((n) => n.x + 300)),
  ),
  height = computed(() =>
    Math.max(
      500,
      ...props.nodes.map(
        (n) =>
          n.y +
          Math.max(200, n.inputs.length * 44 + 70, n.outputs.length * 44 + 70),
      ),
    ),
  );
let moved = false;
function selectNode(id: string) {
  if (moved) {
    moved = false;
    return;
  }
  emit("select", id);
}
let drag: {
  id: string;
  startX: number;
  startY: number;
  x: number;
  y: number;
} | null = null;
function start(event: PointerEvent, node: CanvasNode) {
  if (props.disabled || event.button !== 0) return;
  moved = false;
  drag = {
    id: node.id,
    startX: event.clientX,
    startY: event.clientY,
    x: node.x,
    y: node.y,
  };
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
}
function move(event: PointerEvent) {
  if (!drag) return;
  if (
    Math.abs(event.clientX - drag.startX) +
      Math.abs(event.clientY - drag.startY) >
    4
  )
    moved = true;
  emit(
    "move",
    drag.id,
    Math.max(0, drag.x + (event.clientX - drag.startX) / scale.value),
    Math.max(0, drag.y + (event.clientY - drag.startY) / scale.value),
  );
}
function finish() {
  drag = null;
}
function keyboard(event: KeyboardEvent, node: CanvasNode) {
  const delta: Record<string, [number, number]> = {
    ArrowLeft: [-20, 0],
    ArrowRight: [20, 0],
    ArrowUp: [0, -20],
    ArrowDown: [0, 20],
  };
  if (delta[event.key]) {
    event.preventDefault();
    emit(
      "move",
      node.id,
      Math.max(0, node.x + delta[event.key][0]),
      Math.max(0, node.y + delta[event.key][1]),
    );
  }
}
function connect(node: string, port: string) {
  if (!pending.value) return;
  emit("connect", pending.value.node, pending.value.port, node, port);
  pending.value = null;
}
function path(edge: CanvasEdge) {
  const a = props.nodes.find((n) => n.id === edge.source),
    b = props.nodes.find((n) => n.id === edge.target);
  if (!a || !b) return "";
  const x1 = a.x + 240,
    y1 = a.y + 80 + Math.max(0, a.outputs.indexOf(edge.sourcePort || "")) * 44,
    x2 = b.x,
    y2 = b.y + 80 + Math.max(0, b.inputs.indexOf(edge.targetPort || "")) * 44;
  return `M${x1},${y1} C${x1 + 90},${y1} ${x2 - 90},${y2} ${x2},${y2}`;
}
function drop(event: DragEvent) {
  const profile = event.dataTransfer?.getData("application/edgeai-profile");
  if (!profile) return;
  const box = (event.currentTarget as HTMLElement).getBoundingClientRect();
  emit(
    "drop",
    profile,
    Math.max(
      0,
      (event.clientX -
        box.left +
        (event.currentTarget as HTMLElement).scrollLeft) /
        scale.value,
    ),
    Math.max(
      0,
      (event.clientY -
        box.top +
        (event.currentTarget as HTMLElement).scrollTop) /
        scale.value,
    ),
  );
}
</script>
<style scoped>
.canvas-wrap {
  height: 100%;
  min-height: 500px;
  display: flex;
  flex-direction: column;
  background: var(--ks-bg-surface);
}
.canvas-tools {
  padding: var(--ks-spacing-3);
  border-bottom: 1px solid var(--ks-border-default);
}
.canvas-scroll {
  flex: 1;
  min-height: 400px;
  overflow: auto;
}
.canvas,
.canvas-plane {
  position: relative;
}
.canvas-edges {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
.canvas-edges path {
  fill: none;
  stroke: var(--ks-chart-duration);
  stroke-width: 2;
  pointer-events: stroke;
  cursor: pointer;
}
.canvas-edges marker path {
  fill: var(--ks-chart-duration);
  stroke: none;
}
.canvas-node {
  position: absolute;
  width: 240px;
  background: var(--ks-bg-base);
  border: 1px solid var(--ks-border-strong);
  border-radius: var(--ks-radius-base);
  box-shadow: 0 2px 6px var(--ks-shadow-element);
}
.canvas-node.selected {
  border-color: var(--ks-border-focus);
}
.canvas-node-title {
  border: 0;
  border-bottom: 1px solid var(--ks-border-default);
  background: var(--ks-bg-hover);
  color: var(--ks-text-primary);
  padding: var(--ks-spacing-3);
  width: 100%;
  text-align: left;
  cursor: grab;
  touch-action: none;
}
.canvas-node-title small {
  display: block;
  color: var(--ks-text-secondary);
  overflow-wrap: anywhere;
}
.canvas-node-ports {
  display: flex;
  justify-content: space-between;
  padding: var(--ks-spacing-2);
  gap: var(--ks-spacing-2);
}
.canvas-node-ports button {
  display: block;
  min-height: 44px;
  border: 0;
  background: transparent;
  color: var(--ks-text-primary);
  font-size: var(--ks-font-size-xs);
  text-align: left;
  overflow-wrap: anywhere;
  max-width: 110px;
}
.canvas-node-ports button.active {
  color: var(--ks-chart-duration);
}
.canvas-node-ports button:disabled {
  opacity: 0.5;
}
.canvas-empty {
  padding: var(--ks-spacing-6);
  color: var(--ks-text-secondary);
}
button:focus-visible,
path:focus-visible {
  outline: 2px solid var(--ks-border-focus);
  outline-offset: 2px;
}
</style>
