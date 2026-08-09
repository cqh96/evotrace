<template>
  <div class="perf-page">
    <!-- 顶部 Hero -->
    <div class="et-hero perf-hero">
      <div class="hero-main">
        <div class="hero-icon et-g-ic g-violet">压</div>
        <div>
          <h2>性能测试</h2>
          <div class="et-hero-sub">单机并发压测 · 命中率/TPS/耗时一览 · 对标 MeterSphere</div>
        </div>
      </div>
      <div class="hero-actions">
        <el-button size="small" class="ops-btn primary" :icon="Plus" @click="openCreate">新建压测任务</el-button>
      </div>
    </div>

    <!-- 压测任务列表 -->
    <div class="et-card list-card" v-loading="loading">
      <div class="et-card-head">
        <div>
          <div class="et-card-title">压测任务</div>
          <div class="et-card-sub">共 {{ tasks.length }} 个任务 · 点击「运行」发起并发压测</div>
        </div>
      </div>

      <div class="et-card-body no-padding">
        <el-table :data="tasks" style="width: 100%">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column label="任务名称" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="task-name">{{ row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column label="接口" min-width="200">
            <template #default="{ row }">
              <span class="method-tag" :style="{ color: methodColor(row.method) }">{{ row.method }}</span>
              <span class="path-text">{{ row.path }}</span>
            </template>
          </el-table-column>
          <el-table-column label="并发" width="90" align="center">
            <template #default="{ row }">
              <span class="num">{{ row.concurrency }}</span>
            </template>
          </el-table-column>
          <el-table-column label="时长" width="100" align="center">
            <template #default="{ row }">
              <span class="num">{{ row.durationSec }}s</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <span class="status-pill" :class="statusClass(row.status)">{{ statusText(row.status) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">
              <span class="muted">{{ formatTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180" align="right">
            <template #default="{ row }">
              <el-button size="small" class="ops-btn success" :icon="VideoPlay" :loading="runningId === row.id" @click="run(row)">
                {{ runningId === row.id ? '压测中' : '运行' }}
              </el-button>
              <el-button size="small" class="ops-btn danger" :icon="Delete" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="et-empty-hint">
              <div class="et-empty-ic"><el-icon :size="26"><Stopwatch /></el-icon></div>
              <div>暂无压测任务</div>
              <div class="empty-sub">点击右上角「新建压测任务」开始创建</div>
            </div>
          </template>
        </el-table>
      </div>
    </div>

    <!-- 新建压测任务 -->
    <el-dialog v-model="createOpen" title="新建压测任务" width="480px" append-to-body>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="压测接口" required>
          <el-select v-model="form.endpointId" placeholder="请选择接口" filterable style="width: 100%">
            <el-option
              v-for="ep in endpoints"
              :key="ep.id"
              :value="ep.id"
              :label="`${ep.method} ${ep.path}`"
            >
              <span class="method-tag" :style="{ color: methodColor(ep.method) }">{{ ep.method }}</span>
              <span class="path-text">{{ ep.path }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="任务名称" required>
          <el-input v-model="form.name" placeholder="例如：首页接口 100 并发压测" maxlength="60" show-word-limit />
        </el-form-item>
        <div class="form-row">
          <el-form-item label="并发数" required>
            <el-input-number v-model="form.concurrency" :min="1" :max="10000" :step="10" controls-position="right" style="width: 100%" />
          </el-form-item>
          <el-form-item label="压测时长（秒）" required>
            <el-input-number v-model="form.durationSec" :min="1" :max="3600" :step="5" controls-position="right" style="width: 100%" />
          </el-form-item>
        </div>
        <el-form-item label="目标 BaseUrl（可选，覆盖默认地址）">
          <el-input v-model="form.baseUrl" placeholder="http://host:port，留空使用默认" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button class="ops-btn" @click="createOpen = false">取消</el-button>
        <el-button class="ops-btn primary" :loading="creating" :disabled="!canCreate" @click="createTask">创建</el-button>
      </template>
    </el-dialog>

    <!-- 压测结果 -->
    <el-dialog v-model="resultOpen" width="720px" append-to-body>
      <template #header>
        <div class="result-drawer-title">
          <span class="handler">压测结果</span>
          <span class="drawer-sub">{{ resultTask?.name ?? '' }}</span>
        </div>
      </template>

      <div v-if="summary">
        <!-- 成功率进度 -->
        <div class="rate-block" v-loading="running">
          <div class="rate-head">
            <span class="rate-label">成功率</span>
            <span class="rate-pct" :style="{ color: successColor }">{{ successRate }}%</span>
          </div>
          <div class="et-bar">
            <i :style="{ width: successRate + '%', background: successGradient }" />
          </div>
        </div>

        <!-- 指标卡片 -->
        <div class="metric-grid">
          <div class="metric-card">
            <span class="metric-label">TPS</span>
            <span class="metric-num grad">{{ fmtNum(summary.tps) }}</span>
            <span class="metric-sub">每秒请求数</span>
          </div>
          <div class="metric-card">
            <span class="metric-label">平均响应</span>
            <span class="metric-num warn">{{ fmtNum(summary.avgRtMs) }}<em class="unit">ms</em></span>
            <span class="metric-sub">Avg Response Time</span>
          </div>
          <div class="metric-card">
            <span class="metric-label">P95</span>
            <span class="metric-num danger">{{ fmtNum(summary.p95RtMs) }}<em class="unit">ms</em></span>
            <span class="metric-sub">95% 响应耗时</span>
          </div>
          <div class="metric-card">
            <span class="metric-label">总请求</span>
            <span class="metric-num">{{ fmtNum(summary.totalRequests) }}</span>
            <span class="metric-sub">Total Requests</span>
          </div>
          <div class="metric-card">
            <span class="metric-label">成功</span>
            <span class="metric-num ok">{{ fmtNum(summary.success) }}</span>
            <span class="metric-sub">Success</span>
          </div>
          <div class="metric-card">
            <span class="metric-label">错误数 / 错误率</span>
            <span class="metric-num" :class="(summary.error ?? 0) > 0 ? 'danger' : ''">{{ fmtNum(summary.error ?? 0) }}</span>
            <span class="metric-sub">{{ (summary.errorRate ?? 0).toFixed(2) }}% Error Rate</span>
          </div>
        </div>

        <div class="meta-row">
          <span class="meta-item">压测时长：{{ summary.durationSec ?? form.durationSec }}s</span>
          <span class="meta-item">并发：{{ resultTask?.concurrency ?? 0 }}</span>
        </div>
      </div>

      <el-empty v-else-if="!running" description="暂无结果数据" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Delete, VideoPlay, Stopwatch } from '@element-plus/icons-vue'
import { useProjectStore } from '../stores/project'
import { perfApi, apiDebugApi, type PerfTest, type ApiEndpoint } from '../api'

interface PerfSummary {
  totalRequests?: number
  success?: number
  error?: number
  errorRate?: number
  tps?: number
  avgRtMs?: number
  p95RtMs?: number
  durationSec?: number
}

const projectStore = useProjectStore()
const { current: projectKey } = storeToRefs(projectStore)

const loading = ref(false)
const tasks = ref<PerfTest[]>([])

// 新建
const createOpen = ref(false)
const creating = ref(false)
const endpoints = ref<ApiEndpoint[]>([])
const form = ref({
  endpointId: undefined as number | undefined,
  name: '',
  concurrency: 10,
  durationSec: 30,
  baseUrl: ''
})

// 运行
const runningId = ref<number | null>(null)
const resultOpen = ref(false)
const resultTask = ref<PerfTest | null>(null)
const summary = ref<PerfSummary | null>(null)
const running = ref(false)

const canCreate = computed(() => !!form.value.endpointId && !!form.value.name.trim())

const successRate = computed(() => {
  const s = summary.value
  if (!s) return 0
  const total = Number(s.totalRequests ?? 0)
  if (!total) return 0
  return Math.min(100, Math.max(0, Math.round(((s.success ?? 0) / total) * 100)))
})
const successColor = computed(() => (successRate.value >= 90 ? 'var(--et-ok)' : successRate.value >= 70 ? 'var(--et-warn)' : 'var(--et-danger)'))
const successGradient = computed(() => {
  const c = successRate.value >= 90 ? 'var(--et-ok)' : successRate.value >= 70 ? 'var(--et-warn)' : 'var(--et-danger)'
  return `linear-gradient(90deg, ${c}, ${c})`
})

async function load() {
  if (!projectKey.value) return
  loading.value = true
  try {
    tasks.value = await perfApi.list(projectKey.value)
  } catch {
    ElMessage.error('加载压测任务失败')
  } finally {
    loading.value = false
  }
}

async function openCreate() {
  if (!projectKey.value) return
  createOpen.value = true
  form.value = { endpointId: undefined, name: '', concurrency: 10, durationSec: 30, baseUrl: '' }
  try {
    endpoints.value = await apiDebugApi.list(projectKey.value)
  } catch {
    ElMessage.error('加载接口列表失败')
  }
}

async function createTask() {
  if (!projectKey.value || !form.value.endpointId) return
  creating.value = true
  try {
    await perfApi.create(projectKey.value, {
      endpointId: form.value.endpointId,
      name: form.value.name.trim(),
      concurrency: form.value.concurrency,
      durationSec: form.value.durationSec
    })
    ElMessage.success('压测任务创建成功')
    createOpen.value = false
    await load()
  } catch {
    ElMessage.error('创建失败')
  } finally {
    creating.value = false
  }
}

async function run(row: PerfTest) {
  if (!projectKey.value) return
  runningId.value = row.id
  running.value = true
  resultOpen.value = true
  resultTask.value = row
  summary.value = null
  try {
    // 复用任务内保存的 baseUrl（若无则传空串）
    const baseUrl = (row as PerfTest & { baseUrl?: string }).baseUrl ?? ''
    const res = await perfApi.run(projectKey.value, row.id, baseUrl || undefined)
    summary.value = (res?.summary ?? res) as PerfSummary
    const err = Number(summary.value?.error ?? 0)
    if (err > 0) {
      ElMessage.warning(`压测完成，存在 ${err} 个错误请求`)
    } else {
      ElMessage.success('压测完成')
    }
    await load()
  } catch {
    summary.value = null
    ElMessage.error('压测执行失败')
  } finally {
    runningId.value = null
    running.value = false
  }
}

async function remove(row: PerfTest) {
  if (!projectKey.value) return
  try {
    await ElMessageBox.confirm(`确定删除压测任务「${row.name}」吗？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await perfApi.remove(projectKey.value, row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    ElMessage.error('删除失败')
  }
}

function statusText(status?: string): string {
  const s = (status ?? '').toUpperCase()
  if (s.includes('RUN')) return '运行中'
  if (s.includes('DONE') || s.includes('SUCCESS') || s.includes('PASS')) return '已完成'
  if (s.includes('FAIL') || s.includes('ERROR')) return '失败'
  if (s.includes('PEND') || s.includes('READY')) return '待运行'
  return s || '—'
}

function statusClass(status?: string): string {
  const t = statusText(status)
  if (t === '运行中') return 'warn'
  if (t === '已完成') return 'ok'
  if (t === '失败') return 'bad'
  return 'info'
}

function methodColor(m?: string): string {
  const c: Record<string, string> = { GET: '#34d399', POST: '#a5b0ff', PUT: '#fbbf24', DELETE: '#fb7185', PATCH: '#f472b6' }
  return c[(m ?? '').toUpperCase()] ?? '#93a0bd'
}

function fmtNum(v?: number): string {
  if (v === null || v === undefined) return '—'
  return Number(v).toLocaleString()
}

function formatTime(v?: string): string {
  if (!v) return '—'
  return new Date(v).toLocaleString()
}

watch(projectKey, () => { if (projectKey.value) load() })
onMounted(() => { if (projectKey.value) load() })
</script>

<style scoped>
.perf-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.perf-hero {
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
.task-name {
  font-weight: 600;
}
.muted {
  color: var(--et-text-muted);
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
}
.num {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
.path-text {
  color: var(--et-text-secondary);
  font-size: 12.5px;
  margin-left: 6px;
  font-family: 'SFMono-Regular', ui-monospace, Menlo, Consolas, monospace;
}
.empty-sub {
  margin-top: 6px;
  font-size: 12px;
  color: var(--et-text-muted);
}

/* 方法标签 */
.method-tag {
  font-weight: 800;
  font-size: 11.5px;
  letter-spacing: 0.5px;
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

/* 半透明胶囊按钮 */
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

/* 新建表单 */
.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

/* 结果弹窗 */
.result-drawer-title {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.result-drawer-title .handler {
  font-size: 16px;
  font-weight: 700;
}
.result-drawer-title .drawer-sub {
  font-size: 12px;
  color: var(--et-text-muted);
}

.rate-block {
  margin-bottom: 20px;
}
.rate-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 8px;
}
.rate-label {
  font-size: 13px;
  color: var(--et-text-muted);
}
.rate-pct {
  font-size: 22px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
.metric-card {
  padding: 16px 16px;
  border-radius: 14px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.metric-label {
  font-size: 12px;
  color: var(--et-text-muted);
}
.metric-num {
  font-size: 26px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
  color: var(--et-text);
}
.metric-num .unit {
  font-size: 13px;
  font-weight: 600;
  font-style: normal;
  color: var(--et-text-muted);
  margin-left: 2px;
}
.metric-num.grad {
  background: linear-gradient(90deg, var(--et-grad-a), var(--et-grad-c));
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.metric-num.ok { color: var(--et-ok); }
.metric-num.warn { color: var(--et-warn); }
.metric-num.danger { color: var(--et-danger); }
.metric-sub {
  font-size: 11px;
  color: var(--et-text-muted);
}

.meta-row {
  display: flex;
  gap: 16px;
  margin-top: 18px;
}
.meta-item {
  font-size: 12px;
  color: var(--et-text-muted);
  padding: 6px 12px;
  border-radius: 8px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
}
</style>