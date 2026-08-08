<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { fileApi, type FileHistoryEntry } from '../api'

const props = defineProps<{
  modelValue: boolean
  path: string
  projectKey: string
}>()

const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

const loading = ref(false)
const history = ref<FileHistoryEntry[]>([])
const expanded = ref<Record<string, boolean>>({})

async function load() {
  if (!props.path || !props.projectKey) return
  loading.value = true
  expanded.value = {}
  try {
    history.value = (await fileApi.history(props.path, props.projectKey)) || []
  } catch {
    history.value = []
  } finally {
    loading.value = false
  }
}

function toggle(eventId: string) {
  expanded.value[eventId] = !expanded.value[eventId]
}

function shortSha(sha?: string) {
  return sha ? sha.substring(0, 8) : '—'
}

watch(
  () => [props.modelValue, props.path, props.projectKey] as const,
  ([open]) => { if (open) load() }
)
</script>

<template>
  <el-drawer v-model="visible" :title="'文件历史：' + path" size="720px" v-loading="loading">
    <el-empty v-if="!loading && history.length === 0" description="暂无该文件的历史记录" :image-size="70" />

    <div v-else class="fh-list">
      <div v-for="row in history" :key="row.eventId" class="fh-card">
        <div class="fh-head">
          <el-tag size="small" type="info">{{ row.changeKind || 'MODIFIED' }}</el-tag>
          <code class="fh-sha">{{ shortSha(row.commitSha) }}</code>
          <span class="fh-author">{{ row.author || '—' }}</span>
          <span class="fh-delta">
            <span class="add">+{{ row.addLines ?? 0 }}</span>
            /
            <span class="del">-{{ row.delLines ?? 0 }}</span>
          </span>
          <span class="fh-time">{{ row.occurredAt }}</span>
        </div>

        <div v-if="row.commitMessage" class="fh-msg">{{ row.commitMessage }}</div>
        <div v-if="row.summary" class="fh-summary">
          <span class="ai-badge">AI</span>{{ row.summary }}
        </div>

        <div class="fh-actions">
          <el-button
            v-if="row.hasDiff"
            link
            type="primary"
            size="small"
            @click="toggle(row.eventId)"
          >
            {{ expanded[row.eventId] ? '收起代码变更' : '查看代码变更' }}
          </el-button>
          <span v-else class="fh-no-diff">本次仅记录了文件路径/行数，未入库完整 diff</span>
        </div>

        <pre v-if="expanded[row.eventId] && row.diff" class="fh-diff">{{ row.diff }}</pre>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.fh-list { display: flex; flex-direction: column; gap: 12px }
.fh-card {
  border: 1px solid var(--et-border);
  border-radius: 10px;
  padding: 12px 14px;
  background: var(--et-card-bg);
}
.fh-head {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 12px;
}
.fh-sha {
  background: var(--et-page-bg); padding: 1px 6px; border-radius: 4px;
  font-family: monospace; color: var(--et-text-secondary);
}
.fh-author { color: var(--et-text-secondary) }
.fh-delta { margin-left: auto; font-family: monospace }
.fh-time { color: var(--et-text-muted); width: 100%; margin-top: 2px }
.fh-msg {
  margin-top: 8px; font-size: 13px; color: var(--et-text); white-space: pre-wrap; line-height: 1.5;
}
.fh-summary {
  margin-top: 6px; font-size: 13px; color: var(--et-text-secondary); line-height: 1.55;
}
.ai-badge {
  display: inline-block; background: linear-gradient(135deg, #f59e0b, #e94d0b);
  color: #fff; font-size: 10px; font-weight: 700; padding: 1px 6px; border-radius: 4px; margin-right: 6px;
}
.fh-actions { margin-top: 8px }
.fh-no-diff { font-size: 12px; color: var(--et-text-muted) }
.fh-diff {
  margin: 10px 0 0;
  padding: 12px;
  border-radius: 8px;
  background: #0f172a;
  color: #e2e8f0;
  font-size: 12px;
  line-height: 1.45;
  overflow: auto;
  max-height: 360px;
  white-space: pre-wrap;
  word-break: break-word;
}
.add { color: #10b981 }
.del { color: #ef4444 }
</style>
