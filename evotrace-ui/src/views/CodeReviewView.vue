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
  if (s >= 70) return '#059669'
  if (s >= 50) return '#b45309'
  return '#dc2626'
})

// ---------- 展示辅助（不影响业务逻辑） ----------
const verdictLabel = (v: string) => ({ PASS: '通过', WARNING: '警告', FAIL: '失败' } as any)[v] || v
const verdictMini = (v: string) => ({ PASS: 'verdict-pass', WARNING: 'verdict-warn', FAIL: 'verdict-fail' } as any)[v] || 'verdict-info'
const sevMini = (s: string) => ({ CRITICAL: 'tag-crit', WARNING: 'tag-warn', INFO: 'tag-info', SUGGESTION: 'tag-sug' } as any)[s] || 'tag-info'
const ringStyle = computed(() => {
  const s = Math.max(0, Math.min(100, overview.value.avgScore || 0))
  return { background: `conic-gradient(${scoreColor.value} ${s * 3.6}deg, rgba(107, 114, 128, 0.2) ${s * 3.6}deg 360deg)` }
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

// ---------- PR 描述生成 + 审查回写（对标 PR-Agent /describe + /push-back） ----------
const prDescVisible = ref(false)
const prDescLoading = ref(false)
const prDesc = ref<Record<string, any> | null>(null)
const pushBackVisible = ref(false)
const mergeRequestIid = ref<number | null>(null)
const pushBackLoading = ref(false)
const pushBackResult = ref<Record<string, any> | null>(null)

const selectedEventId = computed(() => selectedReview.value?.change_event_id ?? '')

async function generatePrDesc() {
  if (!selectedEventId.value) return
  prDescLoading.value = true
  prDesc.value = null
  try {
    prDesc.value = await client.post(`/projects/${project.value}/code-review/${selectedEventId.value}/pr-description`)
    prDescVisible.value = true
  } catch { prDescVisible.value = false }
  prDescLoading.value = false
}

function openPushBack() { mergeRequestIid.value = null; pushBackResult.value = null; pushBackVisible.value = true }

async function doPushBack() {
  pushBackLoading.value = true
  try {
    pushBackResult.value = await client.post(`/projects/${project.value}/code-review/${selectedEventId.value}/push-back`, { mergeRequestIid: mergeRequestIid.value })
    ElMessage.success('审查结论已回写 Git 平台')
  } catch { pushBackResult.value = null }
  pushBackLoading.value = false
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

    <!-- 页面 Hero -->
    <section class="et-hero hero rise" style="--d:.02s">
      <div class="hero-left">
        <h2>AI 代码审查</h2>
        <div class="et-hero-sub">对 CODE_COMMIT 事件自动生成审查报告，识别缺陷、安全与性能风险，并附修改建议</div>
        <div class="hero-chips">
          <div class="chip-mini"><span class="et-pulse"></span>审查服务运行中</div>
          <div class="chip-mini">累计审查 <b>{{ overview.totalReviews }}</b> 次</div>
        </div>
      </div>
      <div class="hero-right">
        <div class="score-ring" :style="ringStyle">
          <div class="score-ring-inner">
            <div class="score-num" :style="{ color: scoreColor }">{{ overview.avgScore }}</div>
            <div class="score-lbl">平均评分</div>
          </div>
        </div>
      </div>
    </section>

    <!-- 发起审查 -->
    <section class="et-card trigger-card rise" style="--d:.08s">
      <div class="et-card-head">
        <div>
          <div class="et-card-title"><span class="et-tic"><el-icon :size="15"><DataAnalysis /></el-icon></span>发起审查</div>
          <div class="et-card-sub">输入 eventId 或从项目时间线选择一次 CODE_COMMIT 变更事件</div>
        </div>
        <div class="right">
          <span class="et-mini-tag et-tag-info">近 30 次记录</span>
        </div>
      </div>
      <div class="et-card-body">
        <div class="trigger-row">
          <el-select v-if="showEventSelect" v-model="eventIdInput" placeholder="选择变更事件" filterable
                     size="small" class="trigger-select" :loading="eventOptionsLoading" @focus="loadEventOptions">
            <el-option v-for="opt in eventOptions" :key="opt.eventId" :value="opt.eventId"
                       :label="opt.eventId.substring(0, 10) + '… ' + (opt.summary?.substring(0, 24) ?? opt.eventType)" />
          </el-select>
          <el-input v-else v-model="eventIdInput" placeholder="输入 eventId 触发审查" size="small" class="trigger-input" />
          <el-button type="primary" size="small" :loading="loading" @click="triggerReview">开始审查</el-button>
          <el-button size="small" text @click="showEventSelect = !showEventSelect">
            {{ showEventSelect ? '手动输入' : '选择事件' }}
          </el-button>
        </div>
        <div v-if="loading" class="reviewing">
          <span class="et-pulse"></span>AI 审查进行中，报告生成后将自动刷新
        </div>
      </div>
    </section>

    <!-- 总览统计 -->
    <el-row :gutter="16" class="stats-row">
      <el-col :xs="12" :sm="6"><StatCard label="总审查" :value="overview.totalReviews" :icon="DataAnalysis" color="#4f5ad1" class="rise" style="--d:.12s" /></el-col>
      <el-col :xs="12" :sm="6"><StatCard label="AI 生成代码" :value="overview.aiGenerated" :icon="Refresh" color="#6d4fd6" :suffix="`${overview.aiRatio}%`" class="rise" style="--d:.16s" /></el-col>
      <el-col :xs="12" :sm="6"><StatCard label="审查通过率" :value="overview.passCount" :icon="Checked" color="#059669" class="rise" style="--d:.20s" /></el-col>
      <el-col :xs="12" :sm="6"><StatCard label="严重风险" :value="overview.criticalFindings" :icon="WarningFilled" color="#dc2626" class="rise" style="--d:.24s" /></el-col>
    </el-row>

    <!-- AI 来源分布 -->
    <PageCard v-if="aiSources.length" title="AI 代码来源" sub="审查记录中 AI 生成代码的来源分布" :icon="DataAnalysis" class="rise" style="--d:.28s">
      <div class="source-bar">
        <div v-for="[src, count] in aiSources" :key="src" class="source-item">
          <span class="source-name">{{ src }}</span>
          <el-progress :percentage="Math.round(count/reviews.length*100)" :stroke-width="12" :color="{ claude: '#4f5ad1', copilot: '#059669', cursor: '#b45309', chatgpt: '#059669', gemini: '#4285f4' }[src]||'#94a3b8'" style="flex:1;margin:0 12px" />
          <span class="source-count">{{ count }}</span>
        </div>
      </div>
    </PageCard>

    <!-- AI 提交统计 -->
    <PageCard v-if="stats.length" title="AI 提交统计（近 30 天）" :icon="Refresh" class="rise" style="--d:.32s">
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

    <el-row :gutter="16" class="stats-row">
      <!-- 审查历史 -->
      <el-col :xs="24" :lg="selectedReview ? 12 : 24">
        <PageCard title="审查历史" sub="点击记录查看 AI 审查报告" :icon="DataAnalysis" v-loading="loading" class="rise" style="--d:.34s">
          <div v-if="!loading && reviews.length === 0" class="et-empty-hint">
            <div class="et-empty-ic"><el-icon :size="24"><DataAnalysis /></el-icon></div>
            暂无审查记录，输入 eventId 或点击「批量审查」开始
          </div>
          <div v-else class="review-list">
            <div v-for="r in reviews" :key="r.id" class="review-item" :class="{ selected: selectedReview?.id === r.id }" @click="viewReport(r.eventId)">
              <div class="ri-left">
                <span class="et-mini-tag" :class="verdictMini(r.verdict)">
                  <el-icon :size="11"><component :is="verdictIcon(r.verdict)" /></el-icon>
                  {{ verdictLabel(r.verdict) }}
                </span>
                <span class="ri-event">{{ r.eventId?.substring(0, 10) }}…</span>
                <span v-if="r.aiGenerated" class="et-mini-tag et-tag-test">{{ r.aiSource }}</span>
              </div>
              <div class="ri-right">
                <span class="ri-score" :class="{ pass: r.score >= 70, warn: r.score >= 50 && r.score < 70, fail: r.score < 50 }">{{ r.score }}</span>
              </div>
            </div>
          </div>
        </PageCard>
      </el-col>

      <!-- 审查报告详情 -->
      <el-col v-if="selectedReview" :xs="24" :lg="12">
        <PageCard title="审查报告" sub="AI 对本次变更的完整审查结论" :icon="WarningFilled" v-loading="reviewLoading" class="rise" style="--d:.36s">
          <template #extra>
            <el-button size="small" text type="primary" :loading="prDescLoading" @click="generatePrDesc">生成 PR 描述</el-button>
            <el-button size="small" text type="warning" @click="openPushBack">回写 Git 平台</el-button>
            <el-button size="small" text @click="selectedReview = null">✕</el-button>
          </template>

          <div class="report-header">
            <span class="et-mini-tag report-verdict" :class="verdictMini(selectedReview.overall_verdict)">
              <el-icon :size="12"><component :is="verdictIcon(selectedReview.overall_verdict)" /></el-icon>
              {{ verdictLabel(selectedReview.overall_verdict) }}
            </span>
            <span class="report-score" :class="{ pass: selectedReview.overall_score >= 70, warn: selectedReview.overall_score >= 50 && selectedReview.overall_score < 70, fail: selectedReview.overall_score < 50 }">{{ selectedReview.overall_score }}/100</span>
            <span v-if="selectedReview.ai_generated" class="et-mini-tag et-tag-test">{{ selectedReview.ai_source || 'AI' }}</span>
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
            <h4>发现问题</h4>
            <div class="finding-counts">
              <span class="et-mini-tag tag-crit">{{ selectedReview.criticalCount }} 严重</span>
              <span class="et-mini-tag tag-warn">{{ selectedReview.warningCount }} 警告</span>
              <span class="et-mini-tag tag-info">{{ selectedReview.infoCount }} 提示</span>
            </div>
            <div v-if="!selectedReview.findings?.length" class="no-finding">
              <el-icon :size="15" color="#059669"><Checked /></el-icon> 未发现问题，代码质量良好
            </div>
            <div v-for="f in selectedReview.findings" :key="f.id" class="finding-item" :class="f.severity.toLowerCase()">
              <div class="finding-head">
                <span class="et-mini-tag" :class="sevMini(f.severity)">{{ f.severity }}</span>
                <span class="et-mini-tag et-tag-api">{{ catLabel(f.category) }}</span>
                <span v-if="f.file_path" class="finding-file">{{ f.file_path }}<span v-if="f.line_range">:{{ f.line_range }}</span></span>
              </div>
              <div class="finding-title">{{ f.title }}</div>
              <div v-if="f.description" class="finding-desc">{{ f.description }}</div>
              <div v-if="f.code_snippet" class="code-block">
                <div class="code-head">
                  <span class="cb-dot"></span><span class="cb-dot"></span><span class="cb-dot"></span>
                  <span class="cb-name">code snippet</span>
                </div>
                <pre><code>{{ f.code_snippet }}</code></pre>
              </div>
              <div v-if="f.suggestion" class="finding-sugg">💡 {{ f.suggestion }}</div>
              <div class="finding-act">
                <el-button v-if="!f.acknowledged" size="small" type="primary" text @click="acknowledgeFinding(f.id)">确认问题</el-button>
                <span v-else class="ack-badge"><el-icon :size="12" color="#059669"><Checked /></el-icon> 已确认</span>
              </div>
            </div>
          </div>

          <div v-if="selectedReview.suggestion" class="report-section">
            <h4>总体建议</h4>
            <el-alert type="info" :closable="false" :title="selectedReview.suggestion" />
          </div>
        </PageCard>
      </el-col>
    </el-row>

    <!-- PR 描述生成弹窗 -->
    <el-dialog v-model="prDescVisible" title="MR / PR 描述" width="640px" top="8vh">
      <template v-if="prDesc">
        <el-alert class="prd-alert" type="info" :closable="false" :title="(prDesc as any).summary ?? 'PR 描述已生成（已落库 ai_semantic_unit）'" />
        <div class="prd-section">
          <h4>标题</h4>
          <pre class="prd-pre"><code>{{ (prDesc as any).title ?? '—' }}</code></pre>
        </div>
        <div class="prd-section">
          <h4>描述</h4>
          <pre class="prd-pre"><code>{{ (prDesc as any).description ?? '—' }}</code></pre>
        </div>
        <div v-if="(prDesc as any).model" class="prd-meta">生成模型：{{ (prDesc as any).model }}</div>
      </template>
      <template #footer><el-button type="primary" @click="prDescVisible = false">关闭</el-button></template>
    </el-dialog>

    <!-- 审查回写弹窗 -->
    <el-dialog v-model="pushBackVisible" title="审查结果回写 Git 平台" width="560px">
      <el-alert class="prd-alert" type="warning" :closable="false"
                title="将本次审查结论以评论形式回写到 GitLab/GitHub 的 MR / PR。需已配置平台凭据。" />
      <el-form label-width="110px" style="margin-top: 14px">
        <el-form-item label="Merge Request IID">
          <el-input-number v-model="mergeRequestIid" :min="1" style="width: 200px" placeholder="如 42" />
          <span class="prd-meta" style="margin-left: 10px">可选，留空则自动推断</span>
        </el-form-item>
      </el-form>
      <div v-if="pushBackResult" class="prd-result">
        <div v-for="(v, k) in pushBackResult" :key="k" class="prd-result-row">
          <span class="prd-result-key">{{ k }}</span><span>{{ v }}</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="pushBackVisible = false">取消</el-button>
        <el-button type="primary" :loading="pushBackLoading" @click="doPushBack">回写</el-button>
      </template>
    </el-dialog>

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
/* ========== Hero ========== */
.hero { display: flex; align-items: center; justify-content: space-between; gap: 24px; flex-wrap: wrap; }
.hero-chips { display: flex; gap: 9px; margin-top: 14px; flex-wrap: wrap; }
.chip-mini { display: inline-flex; align-items: center; gap: 8px; font-size: 12px; color: var(--et-text-secondary); padding: 6px 12px; border-radius: 10px; background: var(--et-bg-muted); border: 1px solid var(--et-border); }
.chip-mini b { color: var(--et-text); font-variant-numeric: tabular-nums; }

.score-ring {
  width: 96px; height: 96px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.score-ring-inner {
  width: 74px; height: 74px; border-radius: 50%;
  background: var(--et-card-solid);
  border: 1px solid var(--et-border);
  display: flex; flex-direction: column; align-items: center; justify-content: center;
}
.score-num { font-size: 26px; font-weight: 800; font-variant-numeric: tabular-nums; line-height: 1; }
.score-lbl { font-size: 10.5px; color: var(--et-text-muted); margin-top: 3px; }

/* ========== 发起审查 ========== */
.trigger-card { margin-top: 16px; }
.trigger-row { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
.trigger-select { width: 320px; }
.trigger-input { width: 300px; }
.reviewing { display: inline-flex; align-items: center; gap: 9px; margin-top: 12px; font-size: 12.5px; color: var(--et-text-secondary); font-weight: 500; }

.stats-row { margin-top: 16px; }

/* ========== AI 来源分布 ========== */
.source-bar { display: flex; flex-direction: column; gap: 10px; }
.source-item { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-radius: 10px; background: var(--et-bg-muted); border: 1px solid var(--et-border); }
.source-name { width: 76px; font-size: 12.5px; font-weight: 700; color: var(--et-text); }
.source-count { font-size: 12.5px; color: var(--et-text-secondary); font-weight: 700; font-variant-numeric: tabular-nums; }

/* ========== 审查历史 ========== */
.review-list { display: flex; flex-direction: column; }
.review-item {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 14px; border-radius: 12px;
  border: 1px solid transparent;
  cursor: pointer; transition: background 0.15s, border-color 0.15s;
  margin-bottom: 6px;
}
.review-item:last-child { margin-bottom: 0; }
.review-item:hover { background: var(--et-bg-muted); border-color: var(--et-border); }
.review-item.selected { background: var(--et-primary-bg); border-color: rgba(79, 90, 209, 0.35); }
.ri-left { display: flex; align-items: center; gap: 10px; min-width: 0; flex-wrap: wrap; }
.ri-event { font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 12px; color: var(--et-text-secondary); }
.ri-right { margin-left: auto; display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.ri-score { font-weight: 800; font-size: 15px; font-variant-numeric: tabular-nums; }
.ri-score.pass { color: var(--et-ok); } .ri-score.warn { color: var(--et-warn); } .ri-score.fail { color: var(--et-danger); }

/* 结论迷你标签 */
.verdict-pass { color: var(--et-ok); background: rgba(5, 150, 105, 0.13); }
.verdict-warn { color: var(--et-warn); background: rgba(180, 83, 9, 0.13); }
.verdict-fail { color: var(--et-danger); background: rgba(220, 38, 38, 0.14); }
.verdict-info { color: var(--et-primary-light); background: rgba(79, 90, 209, 0.13); }

/* 严重级别迷你标签 */
.tag-crit { color: #dc2626; background: rgba(220, 38, 38, 0.14); }
.tag-warn { color: #b45309; background: rgba(180, 83, 9, 0.13); }
.tag-info { color: #5f6bd8; background: rgba(79, 90, 209, 0.13); }
.tag-sug { color: #6d4fd6; background: rgba(109, 79, 214, 0.15); }

/* ========== 审查报告 ========== */
.report-header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.report-verdict { font-size: 12px; padding: 4px 12px; }
.report-score { font-size: 28px; font-weight: 800; font-variant-numeric: tabular-nums; }
.report-score.pass { color: var(--et-ok); } .report-score.warn { color: var(--et-warn); } .report-score.fail { color: var(--et-danger); }

.report-section { margin-bottom: 16px; }
.report-section h4 { font-size: 12px; font-weight: 700; color: var(--et-text-secondary); margin: 0 0 8px; text-transform: uppercase; letter-spacing: 0.8px; }
.report-section p { font-size: 13.5px; color: var(--et-text); line-height: 1.7; margin: 0; }

.finding-counts { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }

.no-finding {
  display: inline-flex; align-items: center; gap: 8px;
  color: var(--et-ok); font-size: 13px; font-weight: 600;
  padding: 10px 14px; border-radius: 10px;
  background: rgba(5, 150, 105, 0.1); border: 1px solid rgba(5, 150, 105, 0.25);
}

.finding-item {
  position: relative; padding: 14px 16px; border-radius: 12px; margin-bottom: 10px;
  border: 1px solid var(--et-border); background: var(--et-bg-muted);
}
.finding-item::before {
  content: ''; position: absolute; left: 0; top: 14px; bottom: 14px; width: 3px; border-radius: 3px;
}
.finding-item.critical::before { background: #dc2626; }
.finding-item.warning::before { background: #d97706; }
.finding-item.info::before, .finding-item.suggestion::before { background: #4f5ad1; }

.finding-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.finding-file { font-size: 12px; color: var(--et-text-muted); font-family: 'SF Mono', Menlo, Consolas, monospace; }
.finding-title { font-weight: 700; font-size: 13.5px; color: var(--et-text); margin-bottom: 5px; }
.finding-desc { font-size: 12.5px; color: var(--et-text-secondary); line-height: 1.65; margin-bottom: 8px; }

/* 深色代码块 */
.code-block { background: #0d1226; border: 1px solid rgba(255, 255, 255, 0.07); border-radius: 10px; overflow: hidden; margin-bottom: 8px; }
.code-head { display: flex; align-items: center; gap: 6px; padding: 7px 12px; background: rgba(255, 255, 255, 0.03); border-bottom: 1px solid rgba(255, 255, 255, 0.06); }
.cb-dot { width: 8px; height: 8px; border-radius: 50%; background: rgba(255, 255, 255, 0.18); }
.cb-dot:nth-child(1) { background: #dc2626; }
.cb-dot:nth-child(2) { background: #b45309; }
.cb-dot:nth-child(3) { background: #059669; }
.cb-name { margin-left: 6px; font-size: 10.5px; color: rgba(255, 255, 255, 0.4); font-family: 'SF Mono', Menlo, Consolas, monospace; letter-spacing: 0.4px; }
.code-block pre { margin: 0; padding: 12px 14px; overflow-x: auto; }
.code-block code { font-family: 'SF Mono', Menlo, Consolas, 'Courier New', monospace; font-size: 12px; line-height: 1.7; color: #dbe4ff; white-space: pre; }

.finding-sugg {
  font-size: 12.5px; color: #6d4fd6; line-height: 1.55;
  background: rgba(109, 79, 214, 0.08); border: 1px solid rgba(109, 79, 214, 0.15);
  border-radius: 8px; padding: 8px 10px;
}
.finding-act { margin-top: 8px; }
.ack-badge { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; font-weight: 600; color: var(--et-ok); }

/* ========== PR 描述 / 回写 ========== */
.prd-alert { border-radius: 12px; border: 1px solid var(--et-border); }
.prd-section h4 { font-size: 12px; font-weight: 700; color: var(--et-text-secondary); margin: 14px 0 6px; }
.prd-pre { margin: 0; padding: 12px 14px; background: #0d1226; border: 1px solid rgba(255,255,255,0.07); border-radius: 10px; overflow: auto; }
[data-theme="light"] .prd-pre { background: rgba(15,23,42,0.05); border-color: var(--et-border); }
.prd-pre code { font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 12.5px; line-height: 1.7; color: #dbe4ff; white-space: pre-wrap; }
[data-theme="light"] .prd-pre code { color: #334155; }
.prd-meta { font-size: 12px; color: var(--et-text-muted); margin-top: 8px; }
.prd-result { margin-top: 14px; border: 1px solid var(--et-border); border-radius: 10px; padding: 10px 12px; }
.prd-result-row { display: flex; gap: 10px; font-size: 12.5px; padding: 4px 0; }
.prd-result-key { font-weight: 700; color: var(--et-text-secondary); min-width: 90px; }

@media (max-width: 860px) {
  .hero { flex-direction: column; align-items: flex-start; }
  .trigger-select, .trigger-input { width: 100%; }
}
</style>
