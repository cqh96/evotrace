<script setup lang="ts">
import { onMounted, ref, computed, watch } from 'vue'
import { Clock } from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import FilterBar from '../components/FilterBar.vue'
import PageCard from '../components/PageCard.vue'
import { fileApi, timelineApi, type TimelineEvent } from '../api'
import { useProjectStore } from '../stores/project'

interface EventFile { path: string; kind: string; addLines: number; delLines: number }

const projectStore = useProjectStore()
const { current: project } = storeToRefs(projectStore)
const filters = ref({ project: project.value, app: '', type: '' })
const events = ref<TimelineEvent[]>([])
const loading = ref(false)
const error = ref('')

// 迭代详情抽屉
const iterationDrawer = ref(false)
const iterationTitle = ref('')
const iterationEvents = ref<TimelineEvent[]>([])
const iterationLoading = ref(false)

// 文件历史抽屉
const fileDrawer = ref(false)
const filePath = ref('')
const fileHistory = ref<{ changeKind: string; addLines: number; delLines: number; commitSha?: string; occurredAt: string; summary?: string }[]>([])
const fileLoading = ref(false)

const typeMeta: Record<string, { label: string; color: string; icon: string }> = {
  RELEASE_TAG:    { label: '版本发布', color: '#f59e0b', icon: '🚀' },
  MR_MERGED:      { label: 'MR 合并',  color: '#10b981', icon: '🔀' },
  CODE_COMMIT:     { label: '代码提交', color: '#6366f1', icon: '💻' },
  CONFIG_CHANGE:  { label: '配置变更', color: '#64748b', icon: '⚙️' },
  DDL_CHANGE:     { label: 'DDL 变更', color: '#ef4444', icon: '🗄️' },
  DEPLOY_RECORD:  { label: '部署记录', color: '#8b5cf6', icon: '📦' },
  INVENTORY_REPORT:{ label: '清单上报', color: '#06b6d4', icon: '📋' },
  ITERATION_SYNC: { label: '迭代同步', color: '#f472b6', icon: '📌' }
}

// Group events by date
const groupedEvents = computed(() => {
  const groups: { date: string; items: TimelineEvent[] }[] = []
  for (const e of events.value) {
    const date = e.occurredAt?.substring(0, 10) ?? '未知'
    const last = groups[groups.length - 1]
    if (last?.date === date) last.items.push(e)
    else groups.push({ date, items: [e] })
  }
  return groups
})

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await timelineApi.query(filters.value.project, { app: filters.value.app, type: filters.value.type })
    events.value = data || []
  } catch { error.value = '加载时间线失败'; events.value = [] }
  loading.value = false
}

async function openIteration(e: TimelineEvent) {
  if (!e.iterationId) return
  iterationTitle.value = e.iterationTitle ?? '迭代 ' + e.iterationId
  iterationDrawer.value = true
  iterationLoading.value = true
  try {
    iterationEvents.value = await timelineApi.query(filters.value.project,
      { iterationId: String(e.iterationId) }) || []
  } catch { iterationEvents.value = [] } finally { iterationLoading.value = false }
}

async function openFileHistory(path: string) {
  filePath.value = path
  fileDrawer.value = true
  fileLoading.value = true
  try {
    const data: any = await fileApi.history(path, filters.value.project)
    fileHistory.value = data || []
  } catch { fileHistory.value = [] } finally { fileLoading.value = false }
}

const eventFiles = (e: TimelineEvent): EventFile[] => e.files ?? []

// 全局项目切换时自动刷新
watch(project, (v) => { filters.value.project = v; load() })

onMounted(load)
</script>

<template>
  <div>
    <FilterBar :loading="loading" @search="load">
      <el-form-item label="项目"><el-input v-model="filters.project" style="width: 150px" /></el-form-item>
      <el-form-item label="应用"><el-input v-model="filters.app" placeholder="全部" style="width: 150px" /></el-form-item>
      <el-form-item label="类型">
        <el-select v-model="filters.type" placeholder="全部" clearable style="width: 150px">
          <el-option v-for="(m, k) in typeMeta" :key="k" :label="m.label" :value="k" />
        </el-select>
      </el-form-item>
    </FilterBar>

    <PageCard no-padding style="margin-top: 16px" v-loading="loading">
      <el-alert v-if="error" type="warning" :closable="false" style="margin: 12px" />
      <el-empty v-if="!loading && events.length === 0" description="暂无演化事件" :image-size="80" />

      <div v-else class="timeline-wrap">
        <div v-for="group in groupedEvents" :key="group.date" class="date-group">
          <div class="date-header">
            <el-icon :size="14"><Clock /></el-icon>
            <span>{{ group.date }}</span>
            <span class="date-count">{{ group.items.length }} 个事件</span>
          </div>

          <div class="events">
            <div v-for="e in group.items" :key="e.eventId" class="event-item">
              <div class="event-dot" :style="{ background: typeMeta[e.eventType]?.color ?? '#94a3b8' }">
                {{ typeMeta[e.eventType]?.icon ?? '📌' }}
              </div>
              <div class="event-line" />

              <div class="event-card">
                <div class="event-card-head">
                  <el-tag size="small" :color="typeMeta[e.eventType]?.color ?? '#94a3b8'" effect="dark" class="event-type-tag">
                    {{ typeMeta[e.eventType]?.label ?? e.eventType }}
                  </el-tag>
                  <span v-if="e.appKey" class="event-app">{{ e.appKey }}</span>
                  <code v-if="e.commitSha" class="event-sha">{{ e.commitSha?.substring(0, 7) }}</code>
                  <span class="event-time">{{ e.occurredAt?.substring(11, 16) }}</span>
                  <span class="event-author">{{ e.author }}</span>
                </div>

                <div v-if="e.summaryStatus === 'DONE'" class="event-summary">
                  <span class="ai-badge">AI</span> {{ e.summary }}
                </div>
                <div v-else-if="e.summaryStatus === 'PENDING'" class="event-summary pending">AI 摘要生成中…</div>

                <div v-if="e.iterationTitle" class="event-iteration">
                  <el-link type="primary" :underline="false" style="font-size: 12px" @click="openIteration(e)">
                    📋 {{ e.iterationTitle }}
                  </el-link>
                </div>

                <div v-if="eventFiles(e).length" class="event-files">
                  <el-tag v-for="f in eventFiles(e).slice(0, 6)" :key="f.path" size="small"
                          type="info" effect="plain" class="file-chip" @click="openFileHistory(f.path)">
                    {{ f.path }}
                    <span class="file-delta" :class="{ add: f.addLines, del: f.delLines }">
                      +{{ f.addLines }}/-{{ f.delLines }}
                    </span>
                  </el-tag>
                  <span v-if="eventFiles(e).length > 6" class="file-more">等 {{ eventFiles(e).length }} 个文件</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </PageCard>

    <!-- 迭代详情抽屉 -->
    <el-drawer v-model="iterationDrawer" :title="'迭代：' + iterationTitle" size="560px" v-loading="iterationLoading">
      <el-empty v-if="!iterationLoading && iterationEvents.length === 0" description="该迭代暂无关联事件" :image-size="70" />
      <div v-else class="iteration-events">
        <div v-for="e in iterationEvents" :key="e.eventId" class="iteration-event">
          <el-tag size="small" :color="typeMeta[e.eventType]?.color ?? '#94a3b8'" effect="dark">
            {{ typeMeta[e.eventType]?.label ?? e.eventType }}
          </el-tag>
          <span class="iteration-event-time">{{ e.occurredAt }}</span>
          <div v-if="e.summary" class="iteration-event-summary">{{ e.summary }}</div>
        </div>
      </div>
    </el-drawer>

    <!-- 文件历史抽屉 -->
    <el-drawer v-model="fileDrawer" :title="'文件历史：' + filePath" size="640px" v-loading="fileLoading">
      <el-empty v-if="!fileLoading && fileHistory.length === 0" description="暂无该文件的历史记录" :image-size="70" />
      <el-table v-else :data="fileHistory" stripe>
        <el-table-column prop="occurredAt" label="时间" width="180" />
        <el-table-column prop="changeKind" label="变更" width="110" />
        <el-table-column label="增减" width="120">
          <template #default="{ row }">
            <span class="add">+{{ row.addLines }}</span> / <span class="del">-{{ row.delLines }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="commitSha" label="提交" width="110" />
        <el-table-column prop="summary" label="AI 摘要" min-width="200" show-overflow-tooltip />
      </el-table>
    </el-drawer>
  </div>
</template>

<style scoped>
.timeline-wrap { padding: 20px }

.date-group { margin-bottom: 24px }
.date-group:last-child { margin-bottom: 0 }

.date-header {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 0 12px; color: var(--et-text-secondary); font-size: 13px; font-weight: 600
}
.date-count { color: var(--et-text-muted); font-weight: 400; font-size: 12px }

.events { position: relative; padding-left: 32px }

.event-item { position: relative; padding-bottom: 16px }
.event-item:last-child { padding-bottom: 0 }
.event-item:last-child .event-line { display: none }

.event-dot {
  position: absolute; left: -32px; top: 8px;
  width: 26px; height: 26px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; z-index: 1; border: 2px solid var(--et-card-bg);
}

.event-line {
  position: absolute; left: -19px; top: 38px;
  width: 2px; height: calc(100% - 22px);
  background: var(--et-border);
}

.event-card {
  background: var(--et-card-bg); border: 1px solid var(--et-border);
  border-radius: 10px; padding: 14px 16px; margin-left: 4px;
  transition: box-shadow 0.15s;
}
.event-card:hover { box-shadow: var(--et-shadow) }

.event-card-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap }
.event-type-tag { border: none !important; font-size: 11px }
.event-app { font-weight: 600; font-size: 13px; color: var(--et-text) }
.event-sha { background: var(--et-page-bg); padding: 2px 6px; border-radius: 4px; font-size: 11px; color: var(--et-text-secondary); font-family: monospace }
.event-time { color: var(--et-text-muted); font-size: 12px; margin-left: auto }
.event-author { color: var(--et-text-muted); font-size: 12px }

.event-summary { margin-top: 8px; font-size: 13px; color: var(--et-text-secondary); line-height: 1.6 }
.event-summary.pending { color: var(--et-text-muted); font-style: italic }
.ai-badge {
  display: inline-block; background: linear-gradient(135deg, #f59e0b, #e94d0b);
  color: #fff; font-size: 10px; font-weight: 700; padding: 1px 6px; border-radius: 4px; margin-right: 4px
}

.event-iteration { margin-top: 6px }

.event-files { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 4px; align-items: center }
.file-chip { cursor: pointer; font-family: monospace; font-size: 11px }
.file-chip:hover { border-color: var(--el-color-primary); color: var(--el-color-primary) }
.file-delta { margin-left: 4px; font-size: 10px; color: var(--et-text-muted) }
.file-delta.add { color: #10b981 }
.file-delta.del { color: #ef4444 }
.file-more { font-size: 11px; color: var(--et-text-muted) }

.iteration-events { display: flex; flex-direction: column; gap: 12px }
.iteration-event { border: 1px solid var(--et-border); border-radius: 8px; padding: 10px 12px }
.iteration-event-time { margin-left: 8px; font-size: 12px; color: var(--et-text-muted) }
.iteration-event-summary { margin-top: 6px; font-size: 13px; color: var(--et-text-secondary); line-height: 1.6 }

.add { color: #10b981 }
.del { color: #ef4444 }
</style>
