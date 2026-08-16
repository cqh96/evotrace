<script setup lang="ts">
import { onMounted, ref, computed, watch } from 'vue'
import { Clock, Refresh } from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import FilterBar from '../components/FilterBar.vue'
import FileHistoryDrawer from '../components/FileHistoryDrawer.vue'
import PageCard from '../components/PageCard.vue'
import { timelineApi, type TimelineEvent } from '../api'
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

const typeMeta: Record<string, { label: string; color: string; icon: string }> = {
  RELEASE_TAG:    { label: '版本发布', color: '#b45309', icon: '🚀' },
  MR_MERGED:      { label: 'MR 合并',  color: '#047857', icon: '🔀' },
  CODE_COMMIT:     { label: '代码提交', color: '#4f5ad1', icon: '💻' },
  CONFIG_CHANGE:  { label: '配置变更', color: '#64748b', icon: '⚙️' },
  DDL_CHANGE:     { label: 'DDL 变更', color: '#dc2626', icon: '🗄️' },
  DEPLOY_RECORD:  { label: '部署记录', color: '#6d4fd6', icon: '📦' },
  INVENTORY_REPORT:{ label: '清单上报', color: '#0891b2', icon: '📋' },
  ITERATION_SYNC: { label: '迭代同步', color: '#d6336c', icon: '📌' }
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

function openFileHistory(path: string) {
  filePath.value = path
  fileDrawer.value = true
}

const eventFiles = (e: TimelineEvent): EventFile[] => e.files ?? []

// 全局项目切换时自动刷新
watch(project, (v) => { filters.value.project = v; load() })

// 筛选条件（应用/类型）变化时自动搜索，避免依赖手动点击查询按钮
let debounceTimer: ReturnType<typeof setTimeout> | undefined
watch([() => filters.value.app, () => filters.value.type], () => {
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(load, 400)
})

onMounted(load)

/* ===================== 展示层（不改动业务逻辑） ===================== */

// 迷你标签类别映射（对应 global.css 的 .et-tag-*）
const tagClassMap: Record<string, string> = {
  RELEASE_TAG:     'et-tag-rel',
  DEPLOY_RECORD:   'et-tag-rel',
  MR_MERGED:       'et-tag-review',
  CODE_COMMIT:     'et-tag-info',
  CONFIG_CHANGE:   'et-tag-api',
  DDL_CHANGE:      'et-tag-ddl',
  INVENTORY_REPORT:'et-tag-info',
  ITERATION_SYNC:  'et-tag-req'
}
const tagClass = (t: string) => tagClassMap[t] ?? 'et-tag-info'

// 时间线圆点颜色（随事件类型）
const dotColorMap: Record<string, string> = {
  RELEASE_TAG: '#0891b2', MR_MERGED: '#059669', CODE_COMMIT: '#5f6bd8',
  CONFIG_CHANGE: '#b45309', DDL_CHANGE: '#dc2626', DEPLOY_RECORD: '#0891b2',
  INVENTORY_REPORT: '#0284c7', ITERATION_SYNC: '#6d4fd6'
}
const dotGlowMap: Record<string, string> = {
  RELEASE_TAG: 'rgba(8,145,178,.5)', MR_MERGED: 'rgba(5,150,105,.5)', CODE_COMMIT: 'rgba(95,107,216,.5)',
  CONFIG_CHANGE: 'rgba(180,83,9,.45)', DDL_CHANGE: 'rgba(220,38,38,.45)', DEPLOY_RECORD: 'rgba(8,145,178,.5)',
  INVENTORY_REPORT: 'rgba(2,132,199,.45)', ITERATION_SYNC: 'rgba(109,79,214,.5)'
}
const dotColor = (t: string) => dotColorMap[t] ?? '#4f5ad1'
const dotGlow = (t: string) => dotGlowMap[t] ?? 'rgba(79,90,209,.5)'

// 事件标题（类型 + 应用上下文）
const titleOf = (e: TimelineEvent) => {
  const label = typeMeta[e.eventType]?.label ?? e.eventType
  return e.appKey ? `${label} · ${e.appKey}` : label
}

// 相对时间
function relativeTime(iso?: string): string {
  if (!iso) return ''
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return iso
  const diff = Date.now() - t
  const min = 60e3, hour = 3600e3, day = 86400e3
  if (diff < min) return '刚刚'
  if (diff < hour) return Math.floor(diff / min) + ' 分钟前'
  if (diff < day) return Math.floor(diff / hour) + ' 小时前'
  if (diff < 2 * day) return '昨天'
  if (diff < 30 * day) return Math.floor(diff / day) + ' 天前'
  return iso.substring(0, 10)
}

// Hero 概览统计
const totalEvents = computed(() => events.value.length)
const todayCount = computed(() => {
  const now = new Date()
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  return events.value.filter((e) => (e.occurredAt ?? '').startsWith(today)).length
})
const releaseCount = computed(() => events.value.filter((e) => e.eventType === 'RELEASE_TAG').length)
const iterationCount = computed(() => new Set(events.value.filter((e) => e.iterationId).map((e) => e.iterationId)).size)
</script>

<template>
  <div class="page">
    <!-- ======== 概览 Hero ======== -->
    <section class="et-hero rise" style="--d:.02s">
      <div class="hero-left">
        <h2>演化时间线 <span>Timeline</span></h2>
        <p class="et-hero-sub">全链路变更历史 · 发布、合并、提交、配置与部署事件自动归集</p>
      </div>

      <div v-if="events.length" class="hero-stats">
        <div class="h-chip">
          <span class="h-lbl">今日变更</span>
          <b class="h-num h-grad">{{ todayCount }}</b>
        </div>
        <div class="h-chip">
          <span class="h-lbl">总事件</span>
          <b class="h-num">{{ totalEvents }}</b>
        </div>
        <div class="h-chip">
          <span class="h-lbl">版本发布</span>
          <b class="h-num">{{ releaseCount }}</b>
        </div>
        <div class="h-chip">
          <span class="h-lbl">关联迭代</span>
          <b class="h-num">{{ iterationCount }}</b>
        </div>
      </div>
      <div v-else class="hero-stats static">
        <el-icon :size="14"><Clock /></el-icon>
        <span>选择项目后查看全链路演化动态</span>
      </div>

      <div class="hero-actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </section>

    <!-- ======== 筛选栏 ======== -->
    <FilterBar :loading="loading" @search="load" class="rise" style="--d:.08s">
      <el-form-item label="项目"><el-input v-model="filters.project" style="width: 150px" /></el-form-item>
      <el-form-item label="应用"><el-input v-model="filters.app" placeholder="全部" style="width: 150px" /></el-form-item>
      <el-form-item label="类型">
        <el-select v-model="filters.type" placeholder="全部" clearable style="width: 150px">
          <el-option v-for="(m, k) in typeMeta" :key="k" :label="m.label" :value="k" />
        </el-select>
      </el-form-item>
    </FilterBar>

    <!-- ======== 时间线 ======== -->
    <PageCard no-padding class="tl-card rise" style="--d:.16s" v-loading="loading">
      <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon style="margin: 16px" />

      <el-empty v-if="!loading && events.length === 0" description="暂无演化事件" :image-size="80" />

      <div v-else class="timeline-wrap">
        <div v-for="group in groupedEvents" :key="group.date" class="date-group">
          <div class="date-header">
            <span class="dh-dot"></span>
            <span>{{ group.date }}</span>
            <span class="date-count">{{ group.items.length }} 个事件</span>
          </div>

          <div class="tl">
            <div v-for="e in group.items" :key="e.eventId" class="tl-item"
                 :style="{ '--dot-color': dotColor(e.eventType), '--dot-glow': dotGlow(e.eventType) }">
              <div class="tl-main">
                <span class="et-mini-tag" :class="tagClass(e.eventType)">
                  {{ typeMeta[e.eventType]?.label ?? e.eventType }}
                </span>
                <span class="tl-title">{{ titleOf(e) }}</span>
                <span class="tl-time">{{ relativeTime(e.occurredAt) }}</span>
              </div>

              <div class="tl-meta">
                <el-icon :size="12" class="tl-clock-ic"><Clock /></el-icon>
                <span class="tl-clock">{{ e.occurredAt?.substring(11, 16) }}</span>
                <template v-if="e.commitSha">
                  <span class="tl-sep"></span>
                  <code class="tl-sha">{{ e.commitSha.substring(0, 7) }}</code>
                </template>
                <span v-if="e.author" class="tl-sep"></span>
                <span v-if="e.author" class="tl-author">{{ e.author }}</span>
              </div>

              <div v-if="e.summaryStatus === 'DONE'" class="tl-sum">
                <span class="ai-badge">AI</span>{{ e.summary }}
              </div>
              <div v-else-if="e.summaryStatus === 'PENDING'" class="tl-sum pending">AI 摘要生成中…</div>

              <div v-if="e.iterationTitle" class="tl-iter">
                <el-link type="primary" :underline="false" @click="openIteration(e)">
                  <el-icon :size="12" style="margin-right: 4px"><Clock /></el-icon>
                  {{ e.iterationTitle }}
                </el-link>
              </div>

              <div v-if="eventFiles(e).length" class="tl-files">
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

    <FileHistoryDrawer v-model="fileDrawer" :path="filePath" :project-key="filters.project" />
  </div>
</template>

<style scoped>
.page {
  position: relative;
}

/* ======== Hero 概览 ======== */
.hero-left { position: relative; z-index: 1; }
.hero-stats {
  position: relative; z-index: 1;
  display: flex; align-items: center; gap: 10px;
  margin-left: auto; flex-wrap: wrap;
}
.h-chip {
  min-width: 88px;
  padding: 10px 16px;
  border-radius: 14px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  display: flex; flex-direction: column; gap: 3px;
  transition: border-color 0.2s, transform 0.2s, box-shadow 0.2s;
}
.h-chip:hover {
  border-color: var(--et-hover-border);
  transform: translateY(-2px);
  box-shadow: var(--et-shadow-sm);
}
.h-lbl { font-size: 10.5px; color: var(--et-text-muted); font-weight: 500; }
.h-num {
  font-size: 21px; font-weight: 800; line-height: 1;
  font-variant-numeric: tabular-nums;
  color: var(--et-text);
}
.h-chip .h-grad {
  color: var(--et-primary);
}
.hero-stats.static {
  padding: 9px 16px;
  border-radius: 12px;
  background: var(--et-bg-muted);
  border: 1px dashed var(--et-border);
  font-size: 12.5px; color: var(--et-text-muted);
  display: inline-flex; align-items: center; gap: 7px;
}
.hero-actions { margin-top: 14px; }
@media (max-width: 980px) {
  .hero-stats { margin-left: 0; margin-top: 16px; }
}

/* ======== 时间线 ======== */
.timeline-wrap { padding: 6px 22px 22px; }

.date-group { margin-bottom: 6px; }
.date-group:last-child { margin-bottom: 0; }

.date-header {
  display: flex; align-items: center; gap: 8px;
  padding: 12px 0 8px;
  color: var(--et-text-secondary); font-size: 12.5px; font-weight: 700;
}
.dh-dot {
  width: 7px; height: 7px; border-radius: 50%;
  background: var(--et-primary);
  flex-shrink: 0;
}
.date-count {
  margin-left: auto;
  color: var(--et-text-muted); font-weight: 400; font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

/* 纯色竖轨 + 圆点（参考 mock-dashboard .tl） */
.tl { position: relative; padding-left: 24px; }
.tl::before {
  content: '';
  position: absolute; left: 8px; top: 10px; bottom: 10px; width: 2px;
  border-radius: 2px;
  background: var(--et-primary);
  opacity: 0.45;
}
.tl-item { position: relative; padding: 11px 0 14px; }
.tl-item:last-child { padding-bottom: 4px; }
.tl-item::before {
  content: '';
  position: absolute; left: -23px; top: 17px;
  width: 11px; height: 11px; border-radius: 50%;
  background: var(--et-card-solid);
  border: 2.5px solid var(--dot-color, var(--et-primary));
  box-shadow: var(--et-shadow-sm);
}

.tl-main { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }
.tl-title { font-size: 13.5px; font-weight: 600; color: var(--et-text); }
.tl-time {
  margin-left: auto;
  font-size: 11.5px; color: var(--et-text-muted);
  flex-shrink: 0; font-variant-numeric: tabular-nums;
}

.tl-meta {
  display: flex; align-items: center; gap: 7px;
  margin-top: 5px;
  font-size: 11.5px; color: var(--et-text-muted);
  flex-wrap: wrap;
}
.tl-clock-ic { color: var(--et-text-muted); }
.tl-clock { font-variant-numeric: tabular-nums; }
.tl-sep { width: 3px; height: 3px; border-radius: 50%; background: var(--et-text-muted); opacity: 0.5; }
.tl-sha {
  font-family: monospace; font-size: 10.5px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  padding: 1.5px 6px; border-radius: 6px;
  color: var(--et-text-secondary);
}
.tl-author { color: var(--et-text-secondary); }

/* DDL 变更专属迷你标签 */
.tl-item .et-tag-ddl {
  color: var(--et-danger);
  background: rgba(220, 38, 38, 0.13);
}

/* AI 摘要 */
.tl-sum {
  margin-top: 9px;
  padding: 9px 12px;
  border-radius: 10px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  border-left: 2px solid #6d4fd6;
  font-size: 12.5px; color: var(--et-text-secondary); line-height: 1.65;
}
.tl-sum.pending {
  color: var(--et-text-muted);
  font-style: italic;
  border-left-color: var(--et-warn);
}
.ai-badge {
  display: inline-flex; align-items: center;
  margin-right: 6px;
  background: var(--et-primary);
  color: #fff; font-size: 10px; font-weight: 700;
  padding: 1.5px 7px; border-radius: 5px;
  vertical-align: 1px;
}

/* 迭代链接 */
.tl-iter { margin-top: 8px; }
.tl-iter :deep(.el-link) { font-size: 12px; }

/* 文件 chips */
.tl-files { margin-top: 9px; display: flex; flex-wrap: wrap; gap: 5px; align-items: center; }
.file-chip { cursor: pointer; font-family: monospace; font-size: 11px; }
.file-chip:hover { border-color: var(--el-color-primary); color: var(--el-color-primary); }
.file-delta { margin-left: 4px; font-size: 10px; color: var(--et-text-muted); font-variant-numeric: tabular-nums; }
.file-delta.add { color: var(--et-ok); }
.file-delta.del { color: var(--et-danger); }
.file-more { font-size: 11px; color: var(--et-text-muted); }

/* 迭代抽屉 */
.iteration-events { display: flex; flex-direction: column; gap: 12px; }
.iteration-event {
  border: 1px solid var(--et-border);
  border-radius: 12px;
  padding: 11px 13px;
  background: var(--et-bg-muted);
}
.iteration-event-time { margin-left: 8px; font-size: 12px; color: var(--et-text-muted); }
.iteration-event-summary { margin-top: 6px; font-size: 13px; color: var(--et-text-secondary); line-height: 1.6; }

.add { color: var(--et-ok); }
.del { color: var(--et-danger); }
</style>
