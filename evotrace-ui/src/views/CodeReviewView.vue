<script setup lang="ts">
import { onMounted, ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { storeToRefs } from 'pinia'
import { Checked, WarningFilled, CircleCloseFilled, InfoFilled, Refresh, DataAnalysis } from '@element-plus/icons-vue'
import FilterBar from '../components/FilterBar.vue'
import PageCard from '../components/PageCard.vue'
import StatCard from '../components/StatCard.vue'
import client from '../api/client'
import { useProjectStore } from '../stores/project'

const projectStore = useProjectStore()
const { current: project } = storeToRefs(projectStore)
const loading = ref(false)
const activeTab = ref('history')

// Overview
const overview = ref<any>({ totalReviews: 0, aiGenerated: 0, aiRatio: 0, passCount: 0, failCount: 0, avgScore: 0, criticalFindings: 0 })

// Review history
const reviews = ref<any[]>([])
const selectedReview = ref<any>(null)
const reviewLoading = ref(false)

// Stats
const stats = ref<any[]>([])
const eventIdInput = ref('')

// 事件选择器（远程加载 CODE_COMMIT 事件）
const eventOptions = ref<any[]>([])
const eventOptionsLoading = ref(false)
const showEventSelect = ref(false)

// 批量审查失败明细
const batchFailures = ref<{ eventId: string; error: string }[]>([])
const failuresVisible = ref(false)

const verdictColor = (v: string) => ({ PASS: 'success', WARNING: 'warning', FAIL: 'danger' } as any)[v] || 'info'
const verdictIcon = (v: string) => ({ PASS: Checked, WARNING: WarningFilled, FAIL: CircleCloseFilled } as any)[v] || InfoFilled
const sevColor = (s: string) => ({ CRITICAL: 'danger', WARNING: 'warning', INFO: 'info', SUGGESTION: '' } as any)[s]
const catLabel = (c: string) => ({ BUG: '缺陷', SECURITY: '安全', PERFORMANCE: '性能', LOGIC: '逻辑', STYLE: '风格', DEPENDENCY: '依赖' } as any)[c] || c

const scoreColor = computed(() => {
  const s = overview.value.avgScore
  if (s >= 70) return '#10b981'
  if (s >= 50) return '#f59e0b'
  return '#ef4444'
})

async function loadOverview() {
  try { overview.value = await client.get(`/projects/${project.value}/code-reviews/overview`) } catch {}
}

async function loadReviews() {
  loading.value = true
  try { reviews.value = await client.get(`/projects/${project.value}/code-reviews?limit=30`) } catch {}
  loading.value = false
}

async function loadStats() {
  try { stats.value = await client.get(`/projects/${project.value}/code-reviews/stats?days=30`) } catch {}
}

async function viewReport(eventId: string) {
  reviewLoading.value = true
  try { selectedReview.value = await client.get(`/projects/${project.value}/code-review/${eventId}`) } catch { ElMessage.error('获取报告失败') }
  reviewLoading.value = false
}

async function loadEventOptions() {
  if (!showEventSelect.value) return
  eventOptionsLoading.value = true
  try {
    const data: any[] = await client.get(`/projects/${project.value}/timeline`, { params: { type: 'CODE_COMMIT', limit: 50 } })
    eventOptions.value = data || []
  } catch { eventOptions.value = [] }
  eventOptionsLoading.value = false
}

async function triggerReview() {
  if (!eventIdInput.value.trim()) return
  loading.value = true
  try {
    await client.post(`/projects/${project.value}/code-review/${eventIdInput.value.trim()}`)
    ElMessage.success('审查完成')
    loadReviews(); loadOverview()
  } catch { ElMessage.error('审查失败') }
  loading.value = false
}

async function batchReview() {
  loading.value = true
  try {
    const r = await client.post(`/projects/${project.value}/code-review/batch?limit=10`)
    const reviewed = (r as any).reviewed ?? 0
    const total = (r as any).total ?? 0
    const failures = (r as any).failures ?? []
    if (failures.length) {
      batchFailures.value = failures
      ElMessage.warning(`审查完成 ${reviewed}/${total}，失败 ${failures.length} 个`)
    } else {
      ElMessage.success(`已审查 ${reviewed} / ${total} 个变更`)
    }
    loadReviews(); loadOverview()
  } catch {}
  loading.value = false
}

function showFailures() { failuresVisible.value = true }

async function acknowledgeFinding(id: number) {
  try { await client.post(`/code-review/findings/${id}/acknowledge`); ElMessage.success('已确认'); viewReport(selectedReview.value.change_event_id) } catch {}
}

const aiSources = computed(() => {
  const sources: Record<string, number> = {}
  for (const r of reviews.value) {
    if (r.aiGenerated && r.aiSource) {
      sources[r.aiSource] = (sources[r.aiSource] || 0) + 1
    }
  }
  return Object.entries(sources).sort((a, b) => b[1] - a[1])
})

// 全局项目切换时自动刷新
watch(project, () => { selectedReview.value = null; eventOptions.value = []; loadOverview(); loadReviews(); loadStats() })

onMounted(async () => { await Promise.all([loadOverview(), loadReviews(), loadStats()]) })
</script>

<template>
  <div>
    <FilterBar :loading="loading" @search="loadReviews(); loadOverview()">
      <el-form-item label="项目"><el-input v-model="project" style="width:140px" /></el-form-item>
      <template #actions>
        <el-button :icon="Refresh" @click="batchReview" :loading="loading">批量审查</el-button>
        <el-button v-if="batchFailures.length" size="small" text type="warning" @click="showFailures">查看 {{ batchFailures.length }} 个失败明细</el-button>
      </template>
    </FilterBar>

    <!-- 总览卡片 -->
    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :xs="12" :sm="6"><StatCard label="总审查" :value="overview.totalReviews" :icon="DataAnalysis" color="#6366f1" /></el-col>
      <el-col :xs="12" :sm="6"><StatCard label="AI 生成代码" :value="overview.aiGenerated" :icon="Refresh" color="#8b5cf6" :suffix="`${overview.aiRatio}%`" /></el-col>
      <el-col :xs="12" :sm="6"><StatCard label="审查通过率" :value="overview.passCount" :icon="Checked" color="#10b981" /></el-col>
      <el-col :xs="12" :sm="6"><StatCard label="严重风险" :value="overview.criticalFindings" :icon="WarningFilled" color="#ef4444" /></el-col>
    </el-row>

    <!-- AI 来源分布 -->
    <PageCard v-if="aiSources.length" title="AI 代码来源" style="margin-top: 16px">
      <div class="source-bar">
        <div v-for="[src, count] in aiSources" :key="src" class="source-item">
          <span class="source-name">{{ src }}</span>
          <el-progress :percentage="Math.round(count/reviews.length*100)" :stroke-width="12" :color="{ claude: '#6366f1', copilot: '#10b981', cursor: '#f59e0b', chatgpt: '#10b981', gemini: '#4285f4' }[src]||'#94a3b8'" style="flex:1;margin:0 12px" />
          <span class="source-count">{{ count }}</span>
        </div>
      </div>
    </PageCard>

    <!-- AI 提交统计 -->
    <PageCard v-if="stats.length" title="AI 提交统计（近 30 天）" style="margin-top: 16px">
      <el-table :data="stats" stripe size="small">
        <el-table-column prop="date" label="日期" width="130" />
        <el-table-column prop="source" label="AI 来源" width="120" />
        <el-table-column prop="commits" label="提交数" width="90" />
        <el-table-column prop="lines" label="变更行数" width="110" />
        <el-table-column label="审查通过率" width="140">
          <template #default="{ row }">{{ row.passRate ?? '—' }}%</template>
        </el-table-column>
        <el-table-column label="平均评分" min-width="100">
          <template #default="{ row }">{{ row.avgScore ?? '—' }}</template>
        </el-table-column>
      </el-table>
    </PageCard>

    <el-row :gutter="16" style="margin-top: 16px">
      <!-- 审查历史 -->
      <el-col :xs="24" :lg="selectedReview ? 12 : 24">
        <PageCard title="审查历史" v-loading="loading">
          <div class="review-trigger">
            <el-select v-if="showEventSelect" v-model="eventIdInput" placeholder="选择变更事件" filterable
                       size="small" style="width:280px" :loading="eventOptionsLoading" @focus="loadEventOptions">
              <el-option v-for="opt in eventOptions" :key="opt.eventId" :value="opt.eventId"
                         :label="opt.eventId.substring(0, 10) + '… ' + (opt.summary?.substring(0, 24) ?? opt.eventType)" />
            </el-select>
            <el-input v-else v-model="eventIdInput" placeholder="输入 eventId 触发审查" size="small" style="width:260px" />
            <el-button type="primary" size="small" :loading="loading" @click="triggerReview">审查</el-button>
            <el-button size="small" text @click="showEventSelect = !showEventSelect">
              {{ showEventSelect ? '手动输入' : '选择事件' }}
            </el-button>
          </div>
          <el-empty v-if="!loading && reviews.length === 0" description="暂无审查记录" :image-size="60" />
          <div v-else class="review-list">
            <div v-for="r in reviews" :key="r.id" class="review-item" :class="{ selected: selectedReview?.id === r.id }" @click="viewReport(r.eventId)">
              <div class="ri-left">
                <el-icon :size="16"><component :is="verdictIcon(r.verdict)" :style="{ color: ({ PASS: '#10b981', WARNING: '#f59e0b', FAIL: '#ef4444' } as any)[r.verdict] }" /></el-icon>
                <span class="ri-event">{{ r.eventId?.substring(0, 10) }}…</span>
              </div>
              <div class="ri-right">
                <el-tag v-if="r.aiGenerated" size="small" type="warning" effect="dark" round>{{ r.aiSource }}</el-tag>
                <span class="ri-score" :class="{ pass: r.score >= 70, warn: r.score >= 50 && r.score < 70, fail: r.score < 50 }">{{ r.score }}</span>
              </div>
            </div>
          </div>
        </PageCard>
      </el-col>

      <!-- 审查报告详情 -->
      <el-col v-if="selectedReview" :xs="24" :lg="12">
        <PageCard title="审查报告" v-loading="reviewLoading">
          <template #extra>
            <el-button size="small" text @click="selectedReview = null">✕</el-button>
          </template>

          <div class="report-header">
            <el-tag :type="verdictColor(selectedReview.overall_verdict)" size="large" effect="dark">{{ selectedReview.overall_verdict }}</el-tag>
            <span class="report-score" :class="{ pass: selectedReview.overall_score >= 70, warn: selectedReview.overall_score >= 50 && selectedReview.overall_score < 70, fail: selectedReview.overall_score < 50 }">{{ selectedReview.overall_score }}/100</span>
            <el-tag v-if="selectedReview.ai_generated" size="small" type="warning" effect="dark" round>{{ selectedReview.ai_source || 'AI' }}</el-tag>
          </div>

          <div v-if="selectedReview.diff_summary" class="report-section">
            <h4>变更摘要</h4>
            <p>{{ selectedReview.diff_summary }}</p>
          </div>

          <div v-if="selectedReview.logic_analysis" class="report-section">
            <h4>逻辑分析</h4>
            <p>{{ selectedReview.logic_analysis }}</p>
          </div>

          <div class="report-section">
            <h4>发现问题 ({{ selectedReview.criticalCount }}严重 / {{ selectedReview.warningCount }}警告 / {{ selectedReview.infoCount }}提示)</h4>
            <div v-if="!selectedReview.findings?.length" style="color:var(--et-text-muted);font-size:13px">未发现问题 ✅</div>
            <div v-for="f in selectedReview.findings" :key="f.id" class="finding-item" :class="f.severity.toLowerCase()">
              <div class="finding-head">
                <el-tag :type="sevColor(f.severity)" size="small" effect="dark">{{ f.severity }}</el-tag>
                <el-tag size="small" effect="plain" type="info">{{ catLabel(f.category) }}</el-tag>
                <span v-if="f.file_path" class="finding-file">{{ f.file_path }}<span v-if="f.line_range">:{{ f.line_range }}</span></span>
              </div>
              <div class="finding-title">{{ f.title }}</div>
              <div v-if="f.description" class="finding-desc">{{ f.description }}</div>
              <div v-if="f.code_snippet" class="finding-code"><code>{{ f.code_snippet }}</code></div>
              <div v-if="f.suggestion" class="finding-sugg">💡 {{ f.suggestion }}</div>
              <el-button v-if="!f.acknowledged" size="small" type="primary" text style="margin-top:4px" @click="acknowledgeFinding(f.id)">确认</el-button>
              <el-tag v-else size="small" type="success">已确认</el-tag>
            </div>
          </div>

          <div v-if="selectedReview.suggestion" class="report-section">
            <h4>总体建议</h4>
            <el-alert type="info" :closable="false" :title="selectedReview.suggestion" />
          </div>
        </PageCard>
      </el-col>
    </el-row>

    <!-- 批量审查失败明细 -->
    <el-dialog v-model="failuresVisible" title="批量审查失败明细" width="560px">
      <el-table :data="batchFailures" stripe size="small">
        <el-table-column prop="eventId" label="eventId" width="220">
          <template #default="{ row }"><code style="font-size:11px">{{ row.eventId.substring(0, 16) }}…</code></template>
        </el-table-column>
        <el-table-column prop="error" label="原因" min-width="240" show-overflow-tooltip />
      </el-table>
      <template #footer><el-button type="primary" @click="failuresVisible = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.review-trigger { display: flex; gap: 8px; margin-bottom: 12px }

.review-list { display: flex; flex-direction: column }
.review-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 12px; border-bottom: 1px solid var(--et-border); cursor: pointer;
  border-radius: 6px; transition: background 0.15s;
}
.review-item:hover { background: var(--et-page-bg) }
.review-item.selected { background: var(--et-primary-bg); border-color: rgba(99,102,241,0.3) }
.ri-left { display: flex; align-items: center; gap: 8px }
.ri-event { font-family: monospace; font-size: 12px; color: var(--et-text-secondary) }
.ri-right { display: flex; align-items: center; gap: 8px }
.ri-score { font-weight: 700; font-size: 14px }
.ri-score.pass { color: #10b981 } .ri-score.warn { color: #f59e0b } .ri-score.fail { color: #ef4444 }

.report-header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px }
.report-score { font-size: 28px; font-weight: 800 }
.report-score.pass { color: #10b981 } .report-score.warn { color: #f59e0b } .report-score.fail { color: #ef4444 }

.report-section { margin-bottom: 16px }
.report-section h4 { font-size: 13px; font-weight: 600; color: var(--et-text-secondary); margin: 0 0 8px; text-transform: uppercase; letter-spacing: 0.5px }
.report-section p { font-size: 14px; color: var(--et-text); line-height: 1.7; margin: 0 }

.finding-item { padding: 12px; border-radius: 8px; margin-bottom: 8px; border-left: 3px solid var(--et-border); background: var(--et-page-bg) }
.finding-item.critical { border-left-color: #ef4444 }
.finding-item.warning { border-left-color: #f59e0b }
.finding-item.info, .finding-item.suggestion { border-left-color: #6366f1 }

.finding-head { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; flex-wrap: wrap }
.finding-file { font-size: 12px; color: var(--et-text-muted); font-family: monospace }
.finding-title { font-weight: 600; font-size: 14px; color: var(--et-text); margin-bottom: 4px }
.finding-desc { font-size: 13px; color: var(--et-text-secondary); line-height: 1.6; margin-bottom: 6px }
.finding-code { background: #0f172a; border-radius: 6px; padding: 10px; margin-bottom: 6px; overflow-x: auto }
.finding-code code { font-size: 12px; color: #e2e8f0; font-family: monospace; white-space: pre }
.finding-sugg { font-size: 13px; color: #6366f1; line-height: 1.5 }

.source-bar { display: flex; flex-direction: column; gap: 10px }
.source-item { display: flex; align-items: center }
.source-name { width: 70px; font-size: 13px; font-weight: 600; color: var(--et-text) }
.source-count { width: 30px; font-size: 13px; color: var(--et-text-secondary); text-align: right }
</style>
