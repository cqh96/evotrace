<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import {
  TrendCharts, CircleCheck, Lightning, Warning, Aim, Calendar, Refresh, Camera
} from '@element-plus/icons-vue'
import StatCard from '../components/StatCard.vue'
import { devMetricsApi, type DevMetrics } from '../api'
import { useProjectStore } from '../stores/project'

const projectStore = useProjectStore()
const { current: project } = storeToRefs(projectStore)

const loading = ref(false)
const overview = ref<DevMetrics | null>(null)
const trendDays = ref(30)
const trendData = ref<{ day: string; changes: number; requirements: number; bugs: number; executions: number }[]>([])
const bugDist = ref<{ severity: string; status: string; count: number }[]>([])
const reqFlow = ref<{ status: string; entries: number; avgDays: number }[]>([])
const history = ref<{ period: string; payload: Record<string, any>; createdAt: string }[]>([])

const chartObservers = new Map<string, ResizeObserver>()
function readThemeVars() {
  const cs = getComputedStyle(document.documentElement)
  return {
    text: cs.getPropertyValue('--et-text').trim() || '#e8edf9',
    axis: cs.getPropertyValue('--et-text-muted').trim() || '#8b93ab',
    grid: cs.getPropertyValue('--et-border').trim() || 'rgba(255,255,255,0.1)'
  }
}
function observeChart(el: HTMLElement) {
  chartObservers.get(el.id)?.disconnect()
  const ro = new ResizeObserver(() => echarts.getInstanceByDom(el)?.resize())
  ro.observe(el)
  chartObservers.set(el.id, ro)
}

const statusLabels: Record<string, string> = {
  OPEN: '打开', IN_PROGRESS: '处理中', FIXED: '已修复', REOPENED: '重新打开', VERIFIED: '已验收', CLOSED: '已关闭'
}
const sevColors: Record<string, string> = { P0: '#fb7185', P1: '#fbbf24', P2: '#38e1ff', P3: '#34d399' }

async function loadAll() {
  if (!project.value) return
  loading.value = true
  try {
    const [ov, tr, bd, rf, h] = await Promise.all([
      devMetricsApi.overview(project.value),
      devMetricsApi.trend(project.value, trendDays.value),
      devMetricsApi.bugDistribution(project.value),
      devMetricsApi.requirementFlow(project.value),
      devMetricsApi.history(project.value)
    ])
    overview.value = ov as unknown as DevMetrics
    trendData.value = tr
    bugDist.value = bd
    reqFlow.value = rf
    history.value = h
  } catch {
    ElMessage.error('加载研效指标失败')
  }
  loading.value = false
  await nextTick()
  renderTrend()
  renderBugDist()
  renderReqFlow()
}

async function snapshot() {
  if (!project.value) return
  try {
    await devMetricsApi.snapshot(project.value)
    ElMessage.success('已快照本月指标')
    history.value = await devMetricsApi.history(project.value)
  } catch {
    ElMessage.error('快照失败')
  }
}

function renderTrend() {
  const el = document.getElementById('trend-chart')
  if (!el) return
  const existing = echarts.getInstanceByDom(el); if (existing) existing.dispose()
  const v = readThemeVars()
  const d = trendData.value
  echarts.init(el).setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    legend: { textStyle: { color: v.axis }, top: 0, right: 0 },
    grid: { left: 40, right: 16, top: 40, bottom: 0 },
    xAxis: { type: 'category', data: d.map(p => p.day), axisLine: { lineStyle: { color: v.grid } }, axisLabel: { color: v.axis } },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: v.grid } }, axisLabel: { color: v.axis } },
    series: [
      { name: '提交', type: 'line', smooth: true, data: d.map(p => p.changes), itemStyle: { color: '#6d7cff' }, areaStyle: { opacity: 0.08 } },
      { name: '需求', type: 'line', smooth: true, data: d.map(p => p.requirements), itemStyle: { color: '#38e1ff' } },
      { name: '缺陷', type: 'line', smooth: true, data: d.map(p => p.bugs), itemStyle: { color: '#fb7185' } },
      { name: '用例', type: 'line', smooth: true, data: d.map(p => p.executions), itemStyle: { color: '#34d399' } }
    ]
  })
  observeChart(el)
}

function renderBugDist() {
  const el = document.getElementById('bug-dist-chart')
  if (!el) return
  const existing = echarts.getInstanceByDom(el); if (existing) existing.dispose()
  const v = readThemeVars()
  const sevs = ['P0', 'P1', 'P2', 'P3']
  const statuses = ['OPEN', 'IN_PROGRESS', 'FIXED', 'CLOSED']
  const series = statuses.map(s => ({
    name: statusLabels[s] ?? s,
    type: 'bar',
    stack: 'total',
    data: sevs.map(sev => bugDist.value.filter(b => b.severity === sev && b.status === s).reduce((a, b) => a + b.count, 0))
  }))
  echarts.init(el).setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { textStyle: { color: v.axis }, top: 0, right: 0 },
    grid: { left: 40, right: 16, top: 40, bottom: 0 },
    xAxis: { type: 'category', data: sevs, axisLine: { lineStyle: { color: v.grid } }, axisLabel: { color: v.axis } },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: v.grid } }, axisLabel: { color: v.axis } },
    series: series.map((s, i) => ({ ...s, itemStyle: { color: ['#6d7cff', '#38e1ff', '#fbbf24', '#fb7185'][i] } }))
  })
  observeChart(el)
}

function renderReqFlow() {
  const el = document.getElementById('req-flow-chart')
  if (!el) return
  const existing = echarts.getInstanceByDom(el); if (existing) existing.dispose()
  const v = readThemeVars()
  const d = reqFlow.value
  echarts.init(el).setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    legend: { textStyle: { color: v.axis }, top: 0, right: 0 },
    grid: { left: 40, right: 16, top: 40, bottom: 0 },
    xAxis: { type: 'category', data: d.map(p => p.status), axisLine: { lineStyle: { color: v.grid } }, axisLabel: { color: v.axis } },
    yAxis: [
      { type: 'value', name: '数量', minInterval: 1, splitLine: { lineStyle: { color: v.grid } }, axisLabel: { color: v.axis } },
      { type: 'value', name: '天数', splitLine: { show: false }, axisLabel: { color: v.axis } }
    ],
    series: [
      { name: '数量', type: 'bar', data: d.map(p => p.entries), itemStyle: { color: '#6d7cff', borderRadius: [6, 6, 0, 0] } },
      { name: '平均驻留(天)', type: 'line', yAxisIndex: 1, smooth: true, data: d.map(p => p.avgDays), itemStyle: { color: '#fbbf24' } }
    ]
  })
  observeChart(el)
}

const cycleVal = computed(() => {
  const c = overview.value?.avgReleaseCycleDays
  return c == null ? '—' : `${c} 天`
})

onMounted(loadAll)
</script>

<template>
  <div class="page">
    <div class="page-actions">
      <el-select v-model="trendDays" size="default" style="width: 130px" @change="loadAll">
        <el-option label="近 7 天" :value="7" />
        <el-option label="近 30 天" :value="30" />
        <el-option label="近 90 天" :value="90" />
      </el-select>
      <button class="ops-btn" @click="snapshot"><el-icon><Camera /></el-icon> 快照本月</button>
      <button class="ops-btn primary" @click="loadAll"><el-icon><Refresh /></el-icon> 刷新</button>
    </div>

    <!-- ===== 指标卡 ===== -->
    <div class="stat-grid">
      <StatCard label="需求交付率" :value="overview?.requirementDeliveryRate ?? 0" suffix="%"
                :icon="CircleCheck" color="#6d7cff" :loading="loading"
                :foot="`${overview?.requirementDone ?? 0}/${overview?.requirementTotal ?? 0} 需求已完成（近90天）`" />
      <StatCard label="变更吞吐" :value="overview?.changeThroughput ?? 0" suffix="次"
                :icon="Lightning" color="#38e1ff" :loading="loading"
                :foot="`日均 ${overview?.avgDailyChanges ?? 0} 次提交（近30天）`" />
      <StatCard label="缺陷逃逸率" :value="overview?.bugEscapeRate ?? 0" suffix="%"
                :icon="Warning" color="#fb7185" :loading="loading"
                :foot="`共 ${overview?.bugTotal ?? 0} 缺陷，未关闭 ${overview?.bugOpen ?? 0}`" />
      <StatCard label="用例通过率" :value="overview?.testPassRate ?? 0" suffix="%"
                :icon="Aim" color="#34d399" :loading="loading"
                :foot="`近90天执行 ${overview?.testExecutions ?? 0} 次`" />
      <StatCard label="发布周期" :value="cycleVal" :icon="Calendar" color="#a78bfa" :loading="loading"
                :foot="`共 ${overview?.releaseCount ?? 0} 次发布`" />
      <StatCard label="需求平均交付周期" :value="overview?.avgRequirementCycleDays == null ? '—' : `${overview.avgRequirementCycleDays} 天`"
                :icon="TrendCharts" color="#fbbf24" :loading="loading" :foot="'DONE 需求从创建到完成'" />
    </div>

    <!-- ===== 趋势 ===== -->
    <div class="et-card">
      <div class="et-card-head">
        <div class="et-card-title"><span class="et-tic"><el-icon><TrendCharts /></el-icon></span> 近 {{ trendDays }} 天演化趋势</div>
        <div class="right"><span class="et-card-sub">提交 / 需求 / 缺陷 / 用例</span></div>
      </div>
      <div class="et-card-body"><div id="trend-chart" class="chart"></div></div>
    </div>

    <div class="grid-2">
      <!-- ===== 缺陷分布 ===== -->
      <div class="et-card">
        <div class="et-card-head">
          <div class="et-card-title"><span class="et-tic g-rose"><el-icon><Warning /></el-icon></span> 缺陷分布（严重 × 状态）</div>
        </div>
        <div class="et-card-body"><div id="bug-dist-chart" class="chart"></div></div>
      </div>

      <!-- ===== 需求流 ===== -->
      <div class="et-card">
        <div class="et-card-head">
          <div class="et-card-title"><span class="et-tic g-emerald"><el-icon><TrendCharts /></el-icon></span> 需求状态流</div>
        </div>
        <div class="et-card-body"><div id="req-flow-chart" class="chart"></div></div>
      </div>
    </div>

    <!-- ===== 历史快照 ===== -->
    <div class="et-card">
      <div class="et-card-head">
        <div class="et-card-title"><span class="et-tic g-amber"><el-icon><Calendar /></el-icon></span> 月度指标快照</div>
        <div class="right"><span class="et-card-sub">快照后用于跨月对比</span></div>
      </div>
      <div class="et-card-body no-padding">
        <el-table :data="history" size="small" style="width: 100%">
          <el-table-column prop="period" label="周期" width="100" />
          <el-table-column label="交付率" width="110">
            <template #default="{ row }">{{ (row.payload as any).requirementDeliveryRate ?? 0 }}%</template>
          </el-table-column>
          <el-table-column label="变更吞吐" width="110">
            <template #default="{ row }">{{ (row.payload as any).changeThroughput ?? 0 }}</template>
          </el-table-column>
          <el-table-column label="缺陷逃逸率" width="110">
            <template #default="{ row }">{{ (row.payload as any).bugEscapeRate ?? 0 }}%</template>
          </el-table-column>
          <el-table-column label="用例通过率" width="110">
            <template #default="{ row }">{{ (row.payload as any).testPassRate ?? 0 }}%</template>
          </el-table-column>
          <el-table-column label="快照时间">
            <template #default="{ row }">{{ row.createdAt }}</template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  justify-content: flex-end;
  margin-bottom: 16px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
  margin-bottom: 18px;
}
.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}
.grid-2 .et-card + .et-card { margin-top: 0; }
.chart { width: 100%; height: 300px; }
.ops-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border-radius: 20px;
  border: 1px solid transparent;
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  color: var(--et-text);
  background: rgba(251, 191, 36, 0.14);
  color: #fbbf24;
  cursor: pointer;
  transition: all 0.18s;
}
.ops-btn:hover { background: rgba(251, 191, 36, 0.26); box-shadow: 0 0 14px rgba(251, 191, 36, 0.25); }
.ops-btn.primary { background: rgba(109, 124, 255, 0.14); color: #a8b4ff; }
.ops-btn.primary:hover { background: rgba(109, 124, 255, 0.28); box-shadow: 0 0 14px rgba(109, 124, 255, 0.3); }
.g-rose { background: linear-gradient(135deg, #fb7185, #f43f5e); box-shadow: 0 4px 12px rgba(251, 113, 133, 0.3); }

@media (max-width: 900px) {
  .grid-2 { grid-template-columns: 1fr; }
}
</style>