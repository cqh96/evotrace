<template>
  <div class="report-page">
    <!-- 顶部 Hero -->
    <div class="et-hero report-hero">
      <div class="hero-main">
        <div class="hero-icon et-g-ic g-indigo">报</div>
        <div>
          <h2>测试报告</h2>
          <div class="et-hero-sub">可视化执行结果 · 一键生成 · 免登录分享</div>
        </div>
      </div>
      <div class="hero-actions">
        <el-button size="small" class="ops-btn" :icon="Plus" :loading="genLoading" @click="openGen">从计划生成报告</el-button>
      </div>
    </div>

    <!-- 报告列表 -->
    <div class="et-card list-card" v-loading="loading">
      <div class="et-card-head">
        <div>
          <div class="et-card-title">报告列表</div>
          <div class="et-card-sub">共 {{ reports.length }} 份报告</div>
        </div>
      </div>

      <div class="et-card-body no-padding">
        <el-table :data="reports" style="width: 100%">
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column label="名称" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="rep-name">{{ row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <span class="status-pill" :class="statusClass(row.status)">{{ row.status }}</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }">
              <span class="muted">{{ formatTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="280" align="right">
            <template #default="{ row }">
              <el-button size="small" class="ops-btn" :class="'primary'" :icon="View" @click="view(row)">查看</el-button>
              <el-button size="small" class="ops-btn" :class="'info'" :icon="Refresh" @click="refreshShare(row)">刷新分享链接</el-button>
              <el-button size="small" class="ops-btn" :class="'danger'" :icon="Delete" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="et-empty-hint">
              <div class="et-empty-ic"><el-icon :size="26"><Document /></el-icon></div>
              <div>暂无测试报告</div>
              <div class="empty-sub">点击右上角「从计划生成报告」开始创建</div>
            </div>
          </template>
        </el-table>
      </div>
    </div>

    <!-- 从计划生成报告 -->
    <el-dialog v-model="genOpen" title="从计划生成报告" width="440px" append-to-body>
      <el-form label-position="top">
        <el-form-item label="选择测试计划">
          <el-select v-model="selectedPlanId" placeholder="请选择计划" style="width: 100%">
            <el-option v-for="p in plans" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button class="ops-btn" @click="genOpen = false">取消</el-button>
        <el-button class="ops-btn primary" :loading="genLoading" :disabled="!selectedPlanId" @click="generate">生成</el-button>
      </template>
    </el-dialog>

    <!-- 报告详情抽屉 -->
    <el-drawer v-model="detailOpen" size="640px" :with-header="true">
      <template #header>
        <div class="drawer-title">
          <span class="handler">报告详情</span>
          <span class="drawer-sub">{{ detail?.name ?? '' }}</span>
        </div>
      </template>
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="summary">
          <!-- 顶部结果卡片 -->
          <div class="result-card et-card">
            <div class="ring-wrap">
              <div class="ring" :style="ringStyle">
                <div class="ring-inner">
                  <span class="ring-num">{{ passRateDisplay }}</span>
                  <span class="ring-label">通过率</span>
                </div>
              </div>
            </div>
            <div class="stats">
              <div class="stat total">
                <span class="stat-num">{{ summary.total ?? 0 }}</span>
                <span class="stat-label">用例总数</span>
              </div>
              <div class="stat ok">
                <span class="stat-num">{{ summary.passed ?? 0 }}</span>
                <span class="stat-label">通过</span>
              </div>
              <div class="stat bad">
                <span class="stat-num">{{ summary.failed ?? 0 }}</span>
                <span class="stat-label">失败</span>
              </div>
            </div>
          </div>

          <!-- 计划信息 -->
          <div class="block">
            <div class="block-title">计划信息</div>
            <div class="info-grid">
              <div class="info-item">{{ summary.planName ?? '—' }}</div>
              <div class="info-item" v-if="summary.model">
                <span class="k">AI 模型</span>
                <span class="v">{{ summary.model }}</span>
              </div>
              <div class="info-item" v-if="summary.aiGenerated">
                <span class="k">生成方式</span>
                <span class="v"><span class="ai-badge">AI 生成</span></span>
              </div>
            </div>
          </div>

          <!-- 总结 -->
          <div class="block" v-if="summary.summary">
            <div class="block-title">报告总结</div>
            <p class="summary-text">{{ summary.summary }}</p>
          </div>

          <!-- 失败用例 -->
          <div class="block" v-if="failCases.length">
            <div class="block-title">失败用例（{{ failCases.length }}）</div>
            <ul class="fail-list">
              <li v-for="(fc, i) in failCases" :key="i" class="fail-item">
                <span class="fail-dot" />
                <span class="fail-name">{{ typeof fc === 'string' ? fc : (fc.title ?? fc.name ?? JSON.stringify(fc)) }}</span>
              </li>
            </ul>
          </div>

          <!-- 需求覆盖率 -->
          <div class="block" v-if="coverageEntries.length">
            <div class="block-title">需求覆盖率</div>
            <div class="cov-grid">
              <div v-for="([k, v], i) in coverageEntries" :key="i" class="cov-item">
                <span class="cov-k">{{ k }}</span>
                <span class="cov-v">{{ formatCov(v) }}</span>
              </div>
            </div>
          </div>

          <!-- 分享 -->
          <div class="block">
            <div class="block-title">分享</div>
            <div class="share-row">
              <el-button class="ops-btn info" :icon="Link" :loading="sharing" @click="share">复制分享链接</el-button>
              <span class="share-hint">免登录 · 适用于对外展示</span>
            </div>
          </div>
        </template>

        <el-empty v-else-if="!detailLoading" description="暂无详情数据" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, View, Refresh, Delete, Link, Document } from '@element-plus/icons-vue'
import { useProjectStore } from '../stores/project'
import { reportApi, testPlanApi, type TestReport, type TestPlan } from '../api'

const projectStore = useProjectStore()
const { current: projectKey } = storeToRefs(projectStore)

const loading = ref(false)
const reports = ref<TestReport[]>([])

// 生成报告
const genOpen = ref(false)
const genLoading = ref(false)
const plans = ref<TestPlan[]>([])
const selectedPlanId = ref<number>()

// 详情
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<TestReport | null>(null)
const sharing = ref(false)

const summary = computed<Record<string, any> | null>(() => (detail.value?.summary ?? null))
const failCases = computed<any[]>(() => summary.value?.failCases ?? [])
const coverageEntries = computed<[string, any][]>(() => {
  const c = summary.value?.coverage
  if (!c || typeof c !== 'object') return []
  return Object.entries(c)
})

const passRate = computed(() => {
  const s = summary.value
  if (!s) return 0
  const rate = Number(s.passRate ?? 0)
  return Number.isFinite(rate) ? rate : 0
})
const passRateDisplay = computed(() => `${Math.round(passRate.value)}%`)
const ringStyle = computed(() => {
  const pct = Math.min(100, Math.max(0, passRate.value))
  const color = pct >= 80 ? 'var(--et-ok)' : pct >= 60 ? 'var(--et-warn)' : 'var(--et-danger)'
  return {
    background: `conic-gradient(${color} ${pct * 3.6}deg, rgba(255,255,255,0.08) 0deg)`
  }
})

async function load() {
  if (!projectKey.value) return
  loading.value = true
  try {
    reports.value = await reportApi.list(projectKey.value)
  } catch {
    ElMessage.error('加载报告失败')
  } finally {
    loading.value = false
  }
}

async function openGen() {
  if (!projectKey.value) return
  genLoading.value = true
  genOpen.value = true
  selectedPlanId.value = undefined
  try {
    plans.value = await testPlanApi.listPlans(projectKey.value)
  } catch {
    ElMessage.error('加载计划失败')
  } finally {
    genLoading.value = false
  }
}

async function generate() {
  if (!projectKey.value || !selectedPlanId.value) return
  genLoading.value = true
  try {
    await reportApi.generate(projectKey.value, selectedPlanId.value)
    ElMessage.success('报告生成成功')
    genOpen.value = false
    await load()
  } catch {
    ElMessage.error('生成失败')
  } finally {
    genLoading.value = false
  }
}

async function view(row: TestReport) {
  if (!projectKey.value) return
  detailOpen.value = true
  detailLoading.value = true
  detail.value = row
  try {
    detail.value = await reportApi.detail(projectKey.value, row.id)
  } catch {
    ElMessage.error('加载报告详情失败')
  } finally {
    detailLoading.value = false
  }
}

async function refreshShare(row: TestReport) {
  if (!projectKey.value) return
  try {
    const { shareToken } = await reportApi.refreshShareToken(projectKey.value, row.id)
    row.shareToken = shareToken
    if (detail.value?.id === row.id) detail.value.shareToken = shareToken
    ElMessage.success('分享链接已刷新')
  } catch {
    ElMessage.error('刷新分享链接失败')
  }
}

async function share() {
  if (!projectKey.value || !detail.value) return
  sharing.value = true
  try {
    let token = detail.value.shareToken
    if (!token) {
      const r = await reportApi.refreshShareToken(projectKey.value, detail.value.id)
      token = r.shareToken
      detail.value.shareToken = token
    }
    const url = reportApi.shareUrl(token)
    await navigator.clipboard.writeText(url)
    ElMessage.success('分享链接已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败')
  } finally {
    sharing.value = false
  }
}

async function remove(row: TestReport) {
  if (!projectKey.value) return
  try {
    await ElMessageBox.confirm(`确定删除报告「${row.name}」吗？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await reportApi.remove(projectKey.value, row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    ElMessage.error('删除失败')
  }
}

function statusClass(status: string): string {
  const s = (status ?? '').toUpperCase()
  if (s.includes('PASS') || s.includes('DONE') || s.includes('SUCCESS')) return 'ok'
  if (s.includes('FAIL') || s.includes('ERROR')) return 'bad'
  if (s.includes('RUN') || s.includes('PEND') || s.includes('GEN')) return 'warn'
  return 'info'
}

function formatTime(v?: string): string {
  if (!v) return '—'
  return new Date(v).toLocaleString()
}

function formatCov(v: any): string {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'number') return `${v}%`
  if (typeof v === 'boolean') return v ? '是' : '否'
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

onMounted(load)
</script>

<style scoped>
.report-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.report-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.hero-main {
  display: flex;
  align-items: center;
  gap: 14px;
}
.hero-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.list-card .et-card-head {
  padding-bottom: 14px;
}
.rep-name {
  font-weight: 600;
}
.muted {
  color: var(--et-text-muted);
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
}
.empty-sub {
  margin-top: 6px;
  font-size: 12px;
  color: var(--et-text-muted);
}

/* 状态胶囊 */
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: 20px;
  font-size: 11.5px;
  font-weight: 700;
}
.status-pill::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.status-pill.ok { color: var(--et-ok); background: rgba(52, 211, 153, 0.12); }
.status-pill.bad { color: var(--et-danger); background: rgba(251, 113, 133, 0.12); }
.status-pill.warn { color: var(--et-warn); background: rgba(251, 191, 36, 0.13); }
.status-pill.info { color: var(--et-grad-c); background: rgba(56, 225, 255, 0.12); }

/* 操作按钮（半透明胶囊） */
.ops-btn {
  font-weight: 600;
  border-radius: 8px;
  background: color-mix(in srgb, currentColor 12%, transparent);
}
.ops-btn:hover {
  background: color-mix(in srgb, currentColor 22%, transparent);
  box-shadow: 0 0 12px var(--et-glow);
}
.ops-btn.primary { color: #a8b4ff; }
.ops-btn.success { color: #34d399; }
.ops-btn.danger { color: #fb7185; }
.ops-btn.info { color: #38e1ff; }

/* 抽屉 */
.drawer-title {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.drawer-title .handler {
  font-size: 16px;
  font-weight: 700;
}
.drawer-title .drawer-sub {
  font-size: 12px;
  color: var(--et-text-muted);
}
.detail-body {
  padding: 18px 22px 40px;
  min-height: 100%;
}

/* 结果卡片 */
.result-card {
  display: flex;
  align-items: center;
  gap: 28px;
  padding: 22px;
}
.ring-wrap { flex-shrink: 0; }
.ring {
  width: 132px;
  height: 132px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
}
.ring::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: 50%;
  box-shadow: 0 0 30px var(--et-glow);
  opacity: 0.5;
}
.ring-inner {
  width: 96px;
  height: 96px;
  border-radius: 50%;
  background: var(--et-card-solid);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  position: relative;
  z-index: 1;
}
.ring-num {
  font-size: 26px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  background: linear-gradient(90deg, var(--et-grad-a), var(--et-grad-c));
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.ring-label {
  font-size: 11px;
  color: var(--et-text-muted);
}
.stats {
  display: flex;
  gap: 22px;
  flex: 1;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.stat-num {
  font-size: 26px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
}
.stat-label {
  font-size: 12px;
  color: var(--et-text-muted);
}
.stat.total .stat-num { color: var(--et-text); }
.stat.ok .stat-num { color: var(--et-ok); }
.stat.bad .stat-num { color: var(--et-danger); }

/* 区块 */
.block {
  margin-top: 22px;
}
.block-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 700;
  margin-bottom: 12px;
}
.block-title::before {
  content: '';
  width: 4px;
  height: 14px;
  border-radius: 2px;
  background: linear-gradient(180deg, var(--et-grad-a), var(--et-grad-c));
}

.info-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.info-item {
  padding: 8px 14px;
  border-radius: 10px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.info-item .k {
  color: var(--et-text-muted);
  font-size: 12px;
}
.info-item .v { font-weight: 600; }
.ai-badge {
  padding: 2px 8px;
  border-radius: 20px;
  font-size: 11px;
  font-weight: 700;
  color: var(--et-grad-b);
  background: rgba(167, 139, 250, 0.14);
}

.summary-text {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--et-text-secondary);
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  border-left: 3px solid var(--et-primary);
  border-radius: 10px;
  padding: 14px 16px;
}

/* 失败用例 */
.fail-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.fail-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 10px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  font-size: 13px;
}
.fail-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--et-danger);
  box-shadow: 0 0 8px var(--et-danger);
  flex-shrink: 0;
}
.fail-name { color: var(--et-text-secondary); }

/* 覆盖率 */
.cov-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 10px;
}
.cov-item {
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.cov-k {
  font-size: 12px;
  color: var(--et-text-muted);
}
.cov-v {
  font-size: 16px;
  font-weight: 700;
  color: var(--et-grad-b);
}

/* 分享 */
.share-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.share-hint {
  font-size: 12px;
  color: var(--et-text-muted);
}
</style>