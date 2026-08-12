<script setup lang="ts">
import { computed } from 'vue'
import { Connection } from '@element-plus/icons-vue'
import type { TraceNode } from '../../api'

const props = defineProps<{
  projectKey: string
  rootType: 'REQUIREMENT' | 'RELEASE' | 'CHANGE_EVENT'
  rootId: string
  path?: TraceNode[]
  compact?: boolean
}>()

const emit = defineEmits<{ 'open-node': [{ type: string; id: string }] }>()

const nodes = computed(() => props.path ?? [])

function nodeShort(node: TraceNode) {
  return node.id.length > 16 ? node.id.slice(0, 16) + '…' : node.id
}
function nodeTitle(node: TraceNode) {
  return node.title || node.reqKey || nodeShort(node)
}
function nodeTag(node: TraceNode) {
  return node.type
}
</script>

<template>
  <div class="trace-graph" :class="{ compact }">
    <div v-if="nodes.length" class="step-col">
      <div v-for="(node, i) in nodes" :key="i" class="step">
        <div class="rail">
          <span class="dot" :class="[node.type.toLowerCase()]"></span>
          <span v-if="i < nodes.length - 1" class="line"></span>
        </div>
        <button class="node" @click="emit('open-node', { type: node.type, id: node.id })">
          <span class="node-tag">{{ nodeTag(node) }}</span>
          <span class="node-title">{{ nodeTitle(node) }}</span>
        </button>
      </div>
    </div>
    <div v-else class="empty">
      <el-icon :size="20"><Connection /></el-icon>
      <span>暂无链路路径 — 建立关联后此处展示</span>
    </div>
  </div>
</template>

<style scoped>
.trace-graph { padding: 4px 2px; }
.step-col { display: flex; flex-direction: column; }
.step { display: flex; gap: 12px; }
.rail { display: flex; flex-direction: column; align-items: center; width: 14px; flex-shrink: 0; }
.dot { width: 12px; height: 12px; border-radius: 50%; margin-top: 7px; flex-shrink: 0; box-shadow: 0 0 8px currentColor; }
.dot.requirement { background: #6d7cff; color: #6d7cff; }
.dot.change_event { background: #38e1ff; color: #38e1ff; }
.dot.release { background: #34d399; color: #34d399; }
.dot.bug { background: #fb7185; color: #fb7185; }
.dot.test_case { background: #a78bfa; color: #a78bfa; }
.line { flex: 1; width: 2px; background: var(--et-border); margin: 2px 0; }
.node {
  flex: 1; display: flex; align-items: center; gap: 8px; padding: 8px 12px; margin-bottom: 6px;
  border-radius: 10px; border: 1px solid var(--et-border); background: var(--et-bg-muted);
  color: var(--et-text-secondary); font-size: 12.5px; font-family: inherit; cursor: pointer;
  transition: all 0.15s; text-align: left;
}
.node:hover { border-color: var(--et-hover-border); color: var(--et-text); transform: translateX(3px); }
.node-tag { font-size: 10px; font-weight: 700; color: var(--et-grad-c); padding: 2px 7px; border-radius: 6px; background: rgba(56, 225, 255, 0.1); flex-shrink: 0; letter-spacing: 0.4px; }
.node-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.empty { display: flex; align-items: center; gap: 8px; color: var(--et-text-muted); font-size: 12.5px; padding: 18px 4px; }
</style>