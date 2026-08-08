<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch, nextTick } from 'vue'
import * as echarts from 'echarts'
import { storeToRefs } from 'pinia'
import { TrendCharts, WarningFilled, DataAnalysis } from '@element-plus/icons-vue'
import FilterBar from '../components/FilterBar.vue'
import FileHistoryDrawer from '../components/FileHistoryDrawer.vue'
import PageCard from '../components/PageCard.vue'
import StatCard from '../components/StatCard.vue'
import { analysisApi, releaseApi, type BreakingChangeAlert, type Hotspots, type ImpactResult, type RiskScore } from '../api'
import { useProjectStore } from '../stores/project'

const projectStore = useProjectStore()
const { current: project } = storeToRefs(projectStore)
const activeTab = ref('hotspots')
const loading = ref(false)

const fileDrawer = ref(false)
const filePath = ref('')

function openFileHistory(path: string) {
  filePath.value = path
  fileDrawer.value = true
}

const hotspots = ref<Hotspots | null>(null)
const hotspotsDays = ref(30)
const breakingChanges = ref<BreakingChangeAlert[]>([])

const fromVersion = ref(''); const toVersion = ref('')
const versions = ref<string[]>([])
const riskScore = ref<RiskScore | null>(null)

// 影响面分析
const impactFrom = ref(''); const impactTo = ref('')
const impactResult = ref<ImpactResult | null>(null)
const impactLoading = ref(false)

// 风险历史
const riskHistory = ref<{ version: string; totalScore: number; explanation: string; createdAt: string }[]>([])
const riskHistoryLoading = ref(false)

// 高影响端点
const topEndpoints = ref<{ endpoint: string; callerCount: number }[]>([])
const endpointsLoading = ref(false)

const severityColor = (s: string) => ({ CRITICAL: '#fb7185', WARNING: '#fbbf24', INFO: '#a78bfa' })[s] ?? '#5c6a8a'

// ========== 图表主题（读取设计令牌，跟随主题切换自动取色） ==========
function readThemeVars() {
  const cs = getComputedStyle(document.documentElement)
  return {
    axis: cs.getPropertyValue('--et-axis').trim() || '#5c6a8a',
    grid: cs.getPropertyValue('--et-gridline').trim() || 'rgba(255,255,255,0.05)',
    text: cs.getPropertyValue('--et-text').trim() || '#e8edf9',
    card: cs.getPropertyValue('--et-card-solid').trim() || '#141a2e',
    border: cs.getPropertyValue('--et-hover-border').trim() || 'rgba(255,255,255,0.16)'
  }
}
const chartObservers = new Map<string, ResizeObserver>()
function observeChart(el: HTMLElement) {
  chartObservers.get(el.id)?.disconnect()
  const ro = new ResizeObserver(() => echarts.getInstanceByDom(el)?.resize())
  ro.observe(el)
  chartObservers.set(el.id, ro)
}

async function loadVersions() {
  try {
    const r = await releaseApi.list(project.value)
    if (r?.length) {
      versions.value = r.map(v => v.version)
      fromVersion.value = versions.value[Math.min(1, versions.value.length - 1)] ?? ''
      toVersion.value = versions.value[0] ?? ''
      impactFrom.value = fromVersion.value
      impactTo.value = toVersion.value
    }
  } catch {}
}

async function loadHotspots() {
  loading.value = true
  try { hotspots.value = await analysisApi.hotspots(project.value, hotspotsDays.value) } catch {}
  loading.value = false
}

async function loadBreakingChanges() {
  try { breakingChanges.value = await analysisApi.breakingChanges(project.value) } catch { breakingChanges.value = [] }
}

async function loadRiskScore() {
  if (!fromVersion.value || !toVersion.value) return
  loading.value = true
  try { riskScore.value = await analysisApi.riskScore(project.value, fromVersion.value, toVersion.value) } catch { riskScore.value = null }
  loading.value = false
  await nextTick()
  if (riskScore.value) renderGauge(riskScore.value.totalScore)
}

function renderGauge(score: number) {
  const el = document.getElementById('risk-gauge')
  if (!el) return
  const existing = echarts.getInstanceByDom(el); if (existing) existing.dispose()
  const v = readThemeVars()
  echarts.init(el).setOption({
    series: [{
      type: 'gauge', startAngle: 210, endAngle: -30, center: ['50%', '58%'], radius: '90%',
      min: 0, max: 100,
      axisLine: { lineStyle: { width: 18, color: [[0.4, '#34d399'], [0.7, '#fbbf24'], [1, '#fb7185']] } },
      pointer: { length: '55%', width: 5, itemStyle: { color: 'auto' } },
      axisTick: { distance: -18, length: 6, lineStyle: { width: 1, color: v.axis } },
      splitLine: { distance: -22, length: 12, lineStyle: { width: 2, color: v.axis } },
      axisLabel: { distance: 26, color: v.axis, fontSize: 10 },
      detail: { fontSize: 26, fontWeight: 700, formatter: '{value}', offsetCenter: [0, '70%'], color: v.text },
      data: [{ value: score, name: '风险分' }]
    }]
  })
  observeChart(el)
}

async function acknowledge(id: number) { try { await analysisApi.acknowledge(project.value, id); await loadBreakingChanges() } catch {} }

// ========== 影响面分析 ==========
async function loadImpact() {
  if (!impactFrom.value || !impactTo.value) return
  impactLoading.value = true
  try { impactResult.value = await analysisApi.impact(project.value, impactFrom.value, impactTo.value) } catch { impactResult.value = null }
  impactLoading.value = false
}

// ========== 风险历史 ==========
async function loadRiskHistory() {
  riskHistoryLoading.value = true
  try {
    riskHistory.value = await analysisApi.riskScoreHistory(project.value) || []
    await nextTick()
    renderRiskTrend()
  } catch { riskHistory.value = [] } finally { riskHistoryLoading.value = false }
}

function renderRiskTrend() {
  const el = document.getElementById('risk-trend')
  if (!el) return
  const existing = echarts.getInstanceByDom(el); if (existing) existing.dispose()
  const reversed = [...riskHistory.value].reverse()
  const v = readThemeVars()
  echarts.init(el).setOption({
    grid: { left: 40, right: 16, top: 16, bottom: 28 },
    tooltip: {
      trigger: 'axis', backgroundColor: v.card, borderColor: v.border,
      textStyle: { color: v.text, fontSize: 12 },
      axisPointer: { lineStyle: { color: 'rgba(109,124,255,0.5)' } }
    },
    xAxis: { type: 'category', data: reversed.map(r => r.version), axisLine: { lineStyle: { color: v.grid } }, axisLabel: { fontSize: 10, color: v.axis } },
    yAxis: { type: 'value', min: 0, max: 100, splitLine: { lineStyle: { color: v.grid } }, axisLabel: { color: v.axis, fontSize: 10 } },
    series: [{
      name: '风险分', type: 'line', smooth: true, data: reversed.map(r => r.totalScore),
      lineStyle: { color: '#6d7cff', width: 2.5 },
      itemStyle: { color: '#6d7cff', borderColor: v.card, borderWidth: 2 },
      areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
        colorStops: [{ offset: 0, color: 'rgba(109,124,255,0.3)' }, { offset: 1, color: 'rgba(109,124,255,0)' }] } },
      markLine: {
        data: [
          { yAxis: 70, lineStyle: { type: 'dashed', width: 1, color: '#fb7185' } },
          { yAxis: 40, lineStyle: { type: 'dashed', width: 1, color: '#fbbf24' } }
        ], silent: true, label: { show: false }
      }
    }]
  })
  observeChart(el)
}

// ========== 高影响端点 ==========
async function loadTopEndpoints() {
  endpointsLoading.value = true
  try { topEndpoints.value = await analysisApi.topImpactEndpoints(project.value) || [] } catch { topEndpoints.value = [] } finally { endpointsLoading.value = false }
}

watch(activeTab, (tab) => {
  if (tab === 'risk') setTimeout(() => { if (riskScore.value) renderGauge(riskScore.value.totalScore) }, 150)
  if (tab === 'impact') { if (!impactResult.value) loadImpact() }
  if (tab === 'risk-history') loadRiskHistory()
  if (tab === 'endpoints') loadTopEndpoints()
})

// 全局项目切换时自动刷新
watch(project, () => { impactResult.value = null; riskScore.value = null; riskHistory.value = []; topEndpoints.value = []; loadVersions(); loadHotspots(); loadBreakingChanges() })

// 主题切换时用新配色重绘已渲染的图表
const themeObserver = new MutationObserver(() => {
  if (riskScore.value) renderGauge(riskScore.value.totalScore)
  if (riskHistory.value.length) renderRiskTrend()
})
themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
onUnmounted(() => themeObserver.disconnect())

onMounted(async () => { await Promise.all([loadVersions(), loadHotspots(), loadBreakingChanges()]) })
</script>

<template>
  <div class="analysis-page">
    <div class="rise" style="--d:.02s">
      <FilterBar :loading="loading" @search="loadHotspots(); loadBreakingChanges()">
        <el-form-item label="项目"><el-input v-model="project" style="width: 140px" /></el-form-item>
      </FilterBar>
    </div>

    <div class="rise" style="--d:.08s">
      <PageCard no-padding class="tabs-card">
        <el-tabs v-model="activeTab" class="page-tabs">
          <!-- Hotspots -->
          <el-tab-pane label="代码热点" name="hotspots">
            <div class="tab-content">
              <div class="toolbar-row">
                <span class="toolbar-ic"><el-icon :size="14"><TrendCharts /></el-icon></span>
                <span>统计天数</span>
                <el-input-number v-model="hotspotsDays" :min="7" :max="365" size="small" @change="loadHotspots" style="width: 120px" />
                <el-button size="small" @click="loadHotspots" :loading="loading">刷新</el-button>
              </div>
              <el-row :gutter="16">
                <el-col :xs="24" :lg="12">
                  <div class="et-card sub-card rise" style="--d:.14s">
                    <div class="et-card-head">
                      <div class="et-card-title">
                        <span class="et-tic"><el-icon :size="15"><TrendCharts /></el-icon></span>高频变更文件
                        <span v-if="hotspots?.topChangedFiles?.length" class="et-mini-tag et-tag-info">{{ hotspots.topChangedFiles.length }}</span>
                      </div>
                    </div>
                    <div class="et-card-body">
                      <div v-if="hotspots?.topChangedFiles?.length">
                        <div
                          v-for="(f, i) in hotspots.topChangedFiles"
                          :key="f.filePath"
                          class="hot-row clickable"
                          title="点击查看该文件的提交历史与代码变更"
                          @click="openFileHistory(f.filePath)"
                        >
                          <span class="hot-rank" :class="{ 'rank-top': i < 3 }">#{{ i + 1 }}</span>
                          <span class="hot-path">{{ f.filePath }}</span>
                          <span class="cnt-pill">{{ f.changeCount }} 次</span>
                        </div>
                      </div>
                      <div v-else class="et-empty-hint">
                        <div class="et-empty-ic"><el-icon :size="22"><TrendCharts /></el-icon></div>暂无数据
                      </div>
                    </div>
                  </div>
                </el-col>
                <el-col :xs="24" :lg="12">
                  <div class="et-card sub-card rise" style="--d:.2s">
                    <div class="et-card-head">
                      <div class="et-card-title">
                        <span class="et-tic"><el-icon :size="15"><WarningFilled /></el-icon></span>缺陷倾向文件
                        <span v-if="hotspots?.bugProneFiles?.length" class="et-mini-tag et-tag-api">{{ hotspots.bugProneFiles.length }}</span>
                      </div>
                    </div>
                    <div class="et-card-body">
                      <div v-if="hotspots?.bugProneFiles?.length">
                        <div
                          v-for="(f, i) in hotspots.bugProneFiles"
                          :key="f.filePath"
                          class="hot-row clickable"
                          title="点击查看该文件的提交历史与代码变更"
                          @click="openFileHistory(f.filePath)"
                        >
                          <span class="hot-rank" :class="{ 'rank-top': i < 3 }">#{{ i + 1 }}</span>
                          <span class="hot-path">{{ f.filePath }}</span>
                          <span class="cnt-pill pill-warn">{{ f.changeCount }} 次</span>
                        </div>
                      </div>
                      <div v-else class="et-empty-hint">
                        <div class="et-empty-ic"><el-icon :size="22"><WarningFilled /></el-icon></div>暂无数据
                      </div>
                    </div>
                  </div>
                </el-col>
              </el-row>
            </div>
          </el-tab-pane>

          <!-- Breaking Changes -->
          <el-tab-pane name="breaks">
            <template #label>破坏性变更 <el-badge v-if="breakingChanges.length" :value="breakingChanges.length" class="tab-badge" /></template>
            <div class="tab-content">
              <div class="toolbar-row">
                <span class="toolbar-ic"><el-icon :size="14"><WarningFilled /></el-icon></span>
                <span>破坏性变更</span>
                <span v-if="breakingChanges.length" class="et-mini-tag et-tag-info">共 {{ breakingChanges.length }} 条</span>
                <span v-if="breakingChanges.filter(b => !b.acknowledged).length" class="et-mini-tag et-tag-api">待确认 {{ breakingChanges.filter(b => !b.acknowledged).length }} 条</span>
              </div>
              <el-table :data="breakingChanges" stripe v-loading="loading" size="small">
                <el-table-column label="级别" width="90">
                  <template #default="{ row }">
                    <span class="sev-pill" :style="{ color: severityColor(row.severity), background: severityColor(row.severity) + '1f' }">{{ row.severity }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="changeType" label="类型" width="170" />
                <el-table-column label="详情" min-width="280"><template #default="{ row }">{{ row.detail?.message ?? row.detail?.identityKey ?? '—' }}</template></el-table-column>
                <el-table-column prop="createdAt" label="时间" width="180" />
                <el-table-column label="操作" width="90">
                  <template #default="{ row }">
                    <el-button v-if="!row.acknowledged" size="small" @click="acknowledge(row.id)">确认</el-button>
                    <span v-else class="ok-pill">已确认</span>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-if="!loading && breakingChanges.length === 0" description="暂无破坏性变更 🎉" :image-size="80" />
            </div>
          </el-tab-pane>

          <!-- Risk Score -->
          <el-tab-pane label="风险评估" name="risk">
            <div class="tab-content">
              <div class="toolbar-row">
                <span class="toolbar-ic"><el-icon :size="14"><DataAnalysis /></el-icon></span>
                <el-select v-model="fromVersion" style="width: 140px" size="small"><el-option v-for="v in versions" :key="v" :value="v" /></el-select>
                <span class="arrow">→</span>
                <el-select v-model="toVersion" style="width: 140px" size="small"><el-option v-for="v in versions" :key="v" :value="v" /></el-select>
                <el-button type="primary" size="small" @click="loadRiskScore" :loading="loading">评估</el-button>
              </div>
              <el-row v-if="riskScore" :gutter="16">
                <el-col :xs="24" :md="8">
                  <div class="et-card sub-card rise" style="--d:.14s">
                    <div class="et-card-head">
                      <div class="et-card-title"><span class="et-tic"><el-icon :size="15"><DataAnalysis /></el-icon></span>风险总览</div>
                      <div class="right legend">
                        <span class="lg"><span class="lg-dot" style="background:#34d399"></span>低 0-40</span>
                        <span class="lg"><span class="lg-dot" style="background:#fbbf24"></span>中 40-70</span>
                        <span class="lg"><span class="lg-dot" style="background:#fb7185"></span>高 70+</span>
                      </div>
                    </div>
                    <div class="et-card-body">
                      <div id="risk-gauge" style="height:240px" />
                    </div>
                  </div>
                </el-col>
                <el-col :xs="24" :md="16">
                  <div class="et-card sub-card rise" style="--d:.2s">
                    <div class="et-card-head">
                      <div class="et-card-title"><span class="et-tic"><el-icon :size="15"><TrendCharts /></el-icon></span>评分明细</div>
                      <div class="right">
                        <span class="total-score">综合分 {{ riskScore.totalScore }}</span>
                      </div>
                    </div>
                    <div class="et-card-body">
                      <div v-for="(v, k) in riskScore.subScores" :key="k" class="score-row">
                        <span class="score-label">{{ ({ changeVolume: '变更量', breakingChange: '破坏性变更', historicalBugs: '历史缺陷', impactRadius: '影响半径', timeFactor: '时间因子' } as any)[k] || k }}</span>
                        <el-progress :percentage="(v as number)" :color="v > 20 ? '#fb7185' : v > 12 ? '#fbbf24' : '#34d399'" :stroke-width="8" style="flex:1;margin: 0 12px" />
                        <span class="score-val">{{ v }}/{{ ({ changeVolume: 30, breakingChange: 25, historicalBugs: 20, impactRadius: 15, timeFactor: 10 } as any)[k] || 0 }}</span>
                      </div>
                      <div class="conclusion" :class="riskScore.totalScore >= 70 ? 'c-high' : riskScore.totalScore >= 40 ? 'c-mid' : 'c-low'">
                        <div class="c-head">
                          <span class="et-tic"><el-icon :size="14"><DataAnalysis /></el-icon></span>
                          <span class="c-title">AI 分析结论</span>
                          <span class="c-tag" :class="riskScore.totalScore >= 70 ? 'high' : riskScore.totalScore >= 40 ? 'mid' : 'low'">
                            {{ riskScore.totalScore >= 70 ? '高风险' : riskScore.totalScore >= 40 ? '中风险' : '低风险' }}
                          </span>
                        </div>
                        <p class="c-text">{{ riskScore.explanation }}</p>
                      </div>
                    </div>
                  </div>
                </el-col>
              </el-row>
            </div>
          </el-tab-pane>

          <!-- Impact Analysis -->
          <el-tab-pane label="影响面分析" name="impact">
            <div class="tab-content">
              <div class="toolbar-row">
                <span class="toolbar-ic"><el-icon :size="14"><TrendCharts /></el-icon></span>
                <el-select v-model="impactFrom" style="width: 140px" size="small"><el-option v-for="v in versions" :key="v" :value="v" /></el-select>
                <span class="arrow">→</span>
                <el-select v-model="impactTo" style="width: 140px" size="small"><el-option v-for="v in versions" :key="v" :value="v" /></el-select>
                <el-button type="primary" size="small" @click="loadImpact" :loading="impactLoading">分析影响面</el-button>
              </div>
              <el-empty v-if="!impactResult" description="选择版本区间后点击分析" :image-size="70" />
              <template v-else>
                <div class="impact-banner rise" style="--d:.1s">
                  <span class="et-tic"><el-icon :size="15"><DataAnalysis /></el-icon></span>
                  <span class="ib-text">影响节点 <b>{{ impactResult.affectedNodeCount }}</b> 个</span>
                  <span class="et-mini-tag et-tag-info">影响面分析</span>
                </div>
                <el-row :gutter="16">
                  <el-col :xs="24" :md="8">
                    <div class="et-card sub-card rise" style="--d:.16s">
                      <div class="et-card-head">
                        <div class="et-card-title"><span class="et-tic"><el-icon :size="15"><TrendCharts /></el-icon></span>直接调用方</div>
                      </div>
                      <div class="et-card-body">
                        <div v-if="impactResult.directCallers?.length">
                          <div v-for="c in impactResult.directCallers" :key="c" class="ep-row">{{ c }}</div>
                        </div>
                        <div v-else class="et-empty-hint"><div class="et-empty-ic"><el-icon :size="20"><TrendCharts /></el-icon></div>无</div>
                      </div>
                    </div>
                  </el-col>
                  <el-col :xs="24" :md="8">
                    <div class="et-card sub-card rise" style="--d:.22s">
                      <div class="et-card-head">
                        <div class="et-card-title"><span class="et-tic"><el-icon :size="15"><DataAnalysis /></el-icon></span>受影响服务</div>
                      </div>
                      <div class="et-card-body">
                        <div v-if="impactResult.affectedServices?.length">
                          <div v-for="c in impactResult.affectedServices" :key="c" class="ep-row">{{ c }}</div>
                        </div>
                        <div v-else class="et-empty-hint"><div class="et-empty-ic"><el-icon :size="20"><DataAnalysis /></el-icon></div>无</div>
                      </div>
                    </div>
                  </el-col>
                  <el-col :xs="24" :md="8">
                    <div class="et-card sub-card rise" style="--d:.28s">
                      <div class="et-card-head">
                        <div class="et-card-title"><span class="et-tic"><el-icon :size="15"><WarningFilled /></el-icon></span>建议回归测试</div>
                      </div>
                      <div class="et-card-body">
                        <div v-if="impactResult.suggestedRegression?.length">
                          <div v-for="c in impactResult.suggestedRegression" :key="c" class="ep-row">{{ c }}</div>
                        </div>
                        <div v-else class="et-empty-hint"><div class="et-empty-ic"><el-icon :size="20"><WarningFilled /></el-icon></div>无</div>
                      </div>
                    </div>
                  </el-col>
                </el-row>
              </template>
            </div>
          </el-tab-pane>

          <!-- Risk History -->
          <el-tab-pane label="风险历史" name="risk-history">
            <div class="tab-content">
              <div class="et-card sub-card rise" style="--d:.1s">
                <div class="et-card-head">
                  <div class="et-card-title"><span class="et-tic"><el-icon :size="15"><TrendCharts /></el-icon></span>风险趋势</div>
                  <div class="right legend">
                    <span class="lg"><span class="lg-line" style="background:linear-gradient(90deg,#6d7cff,#a78bfa)"></span>风险分</span>
                    <span class="lg"><span class="lg-line dashed" style="--lc:#fb7185"></span>警戒 70</span>
                    <span class="lg"><span class="lg-line dashed" style="--lc:#fbbf24"></span>关注 40</span>
                  </div>
                </div>
                <div class="et-card-body">
                  <div v-if="riskHistory.length" id="risk-trend" style="height: 220px" />
                  <el-empty v-else-if="!riskHistoryLoading" description="暂无风险历史" :image-size="70" />
                </div>
              </div>
              <el-table class="history-table" :data="riskHistory" stripe size="small" v-loading="riskHistoryLoading">
                <el-table-column prop="version" label="版本" width="120" />
                <el-table-column label="风险分" width="180">
                  <template #default="{ row }">
                    <el-progress :percentage="row.totalScore" :color="row.totalScore >= 70 ? '#fb7185' : row.totalScore >= 40 ? '#fbbf24' : '#34d399'" :stroke-width="10" style="width: 120px" />
                  </template>
                </el-table-column>
                <el-table-column prop="explanation" label="说明" min-width="260" show-overflow-tooltip />
                <el-table-column prop="createdAt" label="评估时间" width="180" />
              </el-table>
            </div>
          </el-tab-pane>

          <!-- Top Impact Endpoints -->
          <el-tab-pane label="高影响端点" name="endpoints">
            <div class="tab-content">
              <div class="toolbar-row">
                <span class="toolbar-ic"><el-icon :size="14"><WarningFilled /></el-icon></span>
                <span>高影响端点</span>
                <span v-if="topEndpoints.length" class="et-mini-tag et-tag-api">{{ topEndpoints.length }} 个端点</span>
              </div>
              <el-table :data="topEndpoints" stripe size="small" v-loading="endpointsLoading">
                <el-table-column prop="endpoint" label="端点" min-width="320" />
                <el-table-column label="调用方数量" width="160">
                  <template #default="{ row }">
                    <span class="cnt-pill" :class="row.callerCount > 5 ? 'pill-danger' : row.callerCount > 2 ? 'pill-warn' : 'pill-info'">{{ row.callerCount }}</span>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-if="!endpointsLoading && topEndpoints.length === 0" description="暂无依赖图数据（需要上报 INVENTORY_REPORT）" :image-size="70" />
            </div>
          </el-tab-pane>
        </el-tabs>
      </PageCard>
    </div>

    <FileHistoryDrawer v-model="fileDrawer" :path="filePath" :project-key="project" />
  </div>
</template>

<style scoped>
.analysis-page { display: flex; flex-direction: column; gap: 18px; }

/* ========== 标签页容器 ========== */
.page-tabs :deep(.el-tabs__header) { margin: 0; padding: 0 20px; }
.page-tabs :deep(.el-tabs__item) { padding: 0 16px; height: 52px; }
.page-tabs :deep(.el-badge__content) {
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-b));
  border: none; font-weight: 700;
}
.tab-content { padding: 18px 22px 24px; }
.tab-badge { margin-left: 4px; }

/* ========== 工具栏 ========== */
.toolbar-row {
  display: flex; align-items: center; gap: 10px;
  margin-bottom: 18px; font-size: 13px; color: var(--et-text-secondary);
  flex-wrap: wrap;
}
.toolbar-ic {
  width: 26px; height: 26px; border-radius: 8px;
  background: rgba(109, 124, 255, 0.12); color: var(--et-primary-light);
  display: inline-flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.arrow { color: var(--et-text-muted); }

/* ========== 子卡片 ========== */
.sub-card { transition: border-color .22s, box-shadow .22s, transform .22s; }
.sub-card:hover { transform: translateY(-2px); }
.el-col .sub-card { margin-bottom: 18px; }

/* ========== 图例 ========== */
.legend { display: flex; gap: 14px; font-size: 12px; color: var(--et-text-secondary); flex-wrap: wrap; }
.legend .lg { display: inline-flex; align-items: center; gap: 6px; }
.lg-dot { width: 9px; height: 9px; border-radius: 50%; }
.lg-line { width: 14px; height: 3px; border-radius: 2px; }
.lg-line.dashed { background: none; border-top: 2px dashed var(--lc); height: 0; }
.total-score {
  font-size: 12px; font-weight: 700; color: var(--et-text-secondary);
  background: var(--et-bg-muted); border: 1px solid var(--et-border);
  padding: 4px 11px; border-radius: 20px;
  font-variant-numeric: tabular-nums;
}

/* ========== 热点文件行 ========== */
.hot-row {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 6px; margin: 0 -6px;
  border-bottom: 1px solid var(--et-border);
  border-radius: 10px;
  transition: background .15s, padding .15s;
}
.hot-row:last-child { border-bottom: none; }
.hot-row.clickable { cursor: pointer; }
.hot-row.clickable:hover { background: rgba(109, 124, 255, 0.06); padding-left: 10px; padding-right: 10px; }
.hot-row.clickable:hover .hot-path { color: var(--et-grad-c); }
.hot-rank { font-weight: 800; width: 26px; font-size: 12px; font-variant-numeric: tabular-nums; color: var(--et-text-muted); flex-shrink: 0; }
.hot-rank.rank-top { color: var(--et-danger); }
.hot-path {
  flex: 1; font-family: 'SF Mono', ui-monospace, 'Cascadia Code', monospace;
  font-size: 12px; color: var(--et-text); word-break: break-all;
  transition: color .15s;
}

/* ========== 计数胶囊 ========== */
.cnt-pill {
  font-size: 11px; font-weight: 700; padding: 3px 9px; border-radius: 20px;
  color: var(--et-text-secondary); background: var(--et-bg-muted);
  border: 1px solid var(--et-border); font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}
.pill-warn { color: var(--et-warn); background: rgba(251, 191, 36, 0.12); border-color: transparent; }
.pill-danger { color: var(--et-danger); background: rgba(251, 113, 133, 0.13); border-color: transparent; }
.pill-info { color: var(--et-primary-light); background: rgba(109, 124, 255, 0.13); border-color: transparent; }
.ok-pill {
  font-size: 10.5px; font-weight: 700; padding: 2.5px 9px; border-radius: 20px;
  color: var(--et-ok); background: rgba(52, 211, 153, 0.13);
}
.sev-pill {
  display: inline-flex; align-items: center;
  font-size: 11px; font-weight: 700; padding: 3px 10px; border-radius: 20px;
  letter-spacing: .3px;
}

/* ========== 评分明细 ========== */
.score-row { display: flex; align-items: center; margin-bottom: 12px; }
.score-row:last-of-type { margin-bottom: 6px; }
.score-label { width: 78px; font-size: 13px; color: var(--et-text-secondary); flex-shrink: 0; }
.score-val { font-size: 12px; color: var(--et-text-muted); width: 58px; text-align: right; font-variant-numeric: tabular-nums; flex-shrink: 0; }

/* ========== AI 结论卡片 ========== */
.conclusion {
  margin-top: 16px; padding: 14px 16px; border-radius: 14px;
  border: 1px solid rgba(109, 124, 255, 0.3);
  background: linear-gradient(120deg, rgba(109, 124, 255, 0.1), rgba(167, 139, 250, 0.05) 50%, rgba(56, 225, 255, 0.07));
}
.conclusion.c-high { border-color: rgba(251, 113, 133, 0.38); background: linear-gradient(120deg, rgba(251, 113, 133, 0.13), rgba(251, 113, 133, 0.04)); }
.conclusion.c-mid { border-color: rgba(251, 191, 36, 0.38); background: linear-gradient(120deg, rgba(251, 191, 36, 0.11), rgba(251, 191, 36, 0.04)); }
.conclusion.c-low { border-color: rgba(52, 211, 153, 0.38); background: linear-gradient(120deg, rgba(52, 211, 153, 0.11), rgba(52, 211, 153, 0.04)); }
.c-head { display: flex; align-items: center; gap: 10px; }
.c-title { font-weight: 700; font-size: 13.5px; color: var(--et-text); }
.c-tag {
  margin-left: auto; display: inline-flex; align-items: center;
  font-size: 10.5px; font-weight: 700; padding: 2.5px 10px; border-radius: 20px;
  flex-shrink: 0;
}
.c-tag.high { color: var(--et-danger); background: rgba(251, 113, 133, 0.13); }
.c-tag.mid { color: var(--et-warn); background: rgba(251, 191, 36, 0.13); }
.c-tag.low { color: var(--et-ok); background: rgba(52, 211, 153, 0.13); }
.c-text { margin: 10px 0 0; font-size: 13px; line-height: 1.7; color: var(--et-text-secondary); }

/* ========== 影响面 ========== */
.impact-banner {
  display: flex; align-items: center; gap: 12px;
  margin-bottom: 18px; padding: 13px 18px; border-radius: 14px;
  background: linear-gradient(120deg, rgba(109, 124, 255, 0.12), rgba(56, 225, 255, 0.06));
  border: 1px solid rgba(109, 124, 255, 0.26);
}
.impact-banner .et-mini-tag { margin-left: auto; }
.ib-text { font-size: 13.5px; font-weight: 600; }
.ib-text b { font-size: 17px; color: var(--et-grad-c); margin: 0 3px; font-variant-numeric: tabular-nums; }

.ep-row {
  padding: 7px 0; border-bottom: 1px dashed var(--et-border);
  font-size: 12px; font-family: 'SF Mono', ui-monospace, 'Cascadia Code', monospace;
  word-break: break-all; color: var(--et-text-secondary);
  transition: color .15s;
}
.ep-row:last-child { border: none; }

/* ========== 风险历史表格 ========== */
.history-table { margin-top: 18px; }
</style>
