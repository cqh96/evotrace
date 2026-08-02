<script setup lang="ts">
import { onMounted, ref, watch, nextTick } from 'vue'
import * as echarts from 'echarts'
import { storeToRefs } from 'pinia'
import { TrendCharts, WarningFilled, DataAnalysis } from '@element-plus/icons-vue'
import FilterBar from '../components/FilterBar.vue'
import PageCard from '../components/PageCard.vue'
import StatCard from '../components/StatCard.vue'
import { analysisApi, releaseApi, type BreakingChangeAlert, type Hotspots, type ImpactResult, type RiskScore } from '../api'
import { useProjectStore } from '../stores/project'

const projectStore = useProjectStore()
const { current: project } = storeToRefs(projectStore)
const activeTab = ref('hotspots')
const loading = ref(false)

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

const severityColor = (s: string) => ({ CRITICAL: '#f56c6c', WARNING: '#f59e0b', INFO: '#6366f1' })[s] ?? '#94a3b8'

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
  echarts.init(el).setOption({
    series: [{
      type: 'gauge', startAngle: 210, endAngle: -30, center: ['50%', '58%'], radius: '90%',
      min: 0, max: 100,
      axisLine: { lineStyle: { width: 18, color: [[0.4, '#10b981'], [0.7, '#f59e0b'], [1, '#ef4444']] } },
      pointer: { length: '55%', width: 5, itemStyle: { color: 'auto' } },
      axisTick: { distance: -18, length: 6, lineStyle: { width: 1, color: '#94a3b8' } },
      splitLine: { distance: -22, length: 12, lineStyle: { width: 2, color: '#94a3b8' } },
      axisLabel: { distance: 26, color: '#94a3b8', fontSize: 10 },
      detail: { fontSize: 26, fontWeight: 700, formatter: '{value}', offsetCenter: [0, '70%'], color: 'var(--et-text)' },
      data: [{ value: score, name: '风险分' }]
    }]
  })
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
  echarts.init(el).setOption({
    grid: { left: 40, right: 16, top: 16, bottom: 28 },
    xAxis: { type: 'category', data: reversed.map(r => r.version), axisLabel: { fontSize: 10 } },
    yAxis: { type: 'value', min: 0, max: 100, splitLine: { lineStyle: { color: 'rgba(148,163,184,.2)' } } },
    tooltip: { trigger: 'axis' },
    series: [{
      type: 'line', smooth: true, data: reversed.map(r => r.totalScore),
      lineStyle: { color: '#6366f1', width: 2 },
      itemStyle: { color: '#6366f1' },
      areaStyle: { opacity: .12, color: '#6366f1' },
      markLine: { data: [{ yAxis: 70 }, { yAxis: 40 }], silent: true,
        lineStyle: { type: 'dashed', width: 1, color: '#f59e0b' }, label: { show: false } }
    }]
  })
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

onMounted(async () => { await Promise.all([loadVersions(), loadHotspots(), loadBreakingChanges()]) })
</script>

<template>
  <div>
    <FilterBar :loading="loading" @search="loadHotspots(); loadBreakingChanges()">
      <el-form-item label="项目"><el-input v-model="project" style="width: 140px" /></el-form-item>
    </FilterBar>

    <PageCard no-padding style="margin-top: 16px">
      <el-tabs v-model="activeTab" class="page-tabs">
        <!-- Hotspots -->
        <el-tab-pane label="代码热点" name="hotspots">
          <div class="tab-content">
            <div class="toolbar-row">
              <span>统计天数</span>
              <el-input-number v-model="hotspotsDays" :min="7" :max="365" size="small" @change="loadHotspots" style="width: 120px" />
              <el-button size="small" @click="loadHotspots" :loading="loading">刷新</el-button>
            </div>
            <el-row :gutter="16">
              <el-col :xs="24" :lg="12">
                <PageCard title="📁 高频变更文件" style="margin-bottom: 12px">
                  <div v-if="hotspots?.topChangedFiles?.length">
                    <div v-for="(f, i) in hotspots.topChangedFiles" :key="f.filePath" class="hot-row">
                      <span class="hot-rank" :style="{ color: i < 3 ? '#ef4444' : '#94a3b8' }">#{{ i + 1 }}</span>
                      <span class="hot-path">{{ f.filePath }}</span>
                      <el-tag size="small" round>{{ f.changeCount }}次</el-tag>
                    </div>
                  </div>
                  <el-empty v-else description="暂无数据" :image-size="60" />
                </PageCard>
              </el-col>
              <el-col :xs="24" :lg="12">
                <PageCard title="🐛 缺陷倾向文件" style="margin-bottom: 12px">
                  <div v-if="hotspots?.bugProneFiles?.length">
                    <div v-for="(f, i) in hotspots.bugProneFiles" :key="f.filePath" class="hot-row">
                      <span class="hot-rank" :style="{ color: i < 3 ? '#ef4444' : '#94a3b8' }">#{{ i + 1 }}</span>
                      <span class="hot-path">{{ f.filePath }}</span>
                      <el-tag size="small" type="danger" round>{{ f.changeCount }}次</el-tag>
                    </div>
                  </div>
                  <el-empty v-else description="暂无数据" :image-size="60" />
                </PageCard>
              </el-col>
            </el-row>
          </div>
        </el-tab-pane>

        <!-- Breaking Changes -->
        <el-tab-pane name="breaks">
          <template #label>破坏性变更 <el-badge v-if="breakingChanges.length" :value="breakingChanges.length" class="tab-badge" /></template>
          <div class="tab-content">
            <el-table :data="breakingChanges" stripe v-loading="loading" size="small">
              <el-table-column label="级别" width="90">
                <template #default="{ row }"><el-tag :color="severityColor(row.severity)" effect="dark" size="small" style="border:none">{{ row.severity }}</el-tag></template>
              </el-table-column>
              <el-table-column prop="changeType" label="类型" width="170" />
              <el-table-column label="详情" min-width="280"><template #default="{ row }">{{ row.detail?.message ?? row.detail?.identityKey ?? '—' }}</template></el-table-column>
              <el-table-column prop="createdAt" label="时间" width="180" />
              <el-table-column label="操作" width="90"><template #default="{ row }"><el-button v-if="!row.acknowledged" size="small" @click="acknowledge(row.id)">确认</el-button><el-tag v-else size="small" type="success">已确认</el-tag></template></el-table-column>
            </el-table>
            <el-empty v-if="!loading && breakingChanges.length === 0" description="暂无破坏性变更 🎉" :image-size="80" />
          </div>
        </el-tab-pane>

        <!-- Risk Score -->
        <el-tab-pane label="风险评估" name="risk">
          <div class="tab-content">
            <div class="toolbar-row">
              <el-select v-model="fromVersion" style="width: 140px" size="small"><el-option v-for="v in versions" :key="v" :value="v" /></el-select>
              <span style="color:var(--et-text-muted)">→</span>
              <el-select v-model="toVersion" style="width: 140px" size="small"><el-option v-for="v in versions" :key="v" :value="v" /></el-select>
              <el-button type="primary" size="small" @click="loadRiskScore" :loading="loading">评估</el-button>
            </div>
            <el-row v-if="riskScore" :gutter="16">
              <el-col :xs="24" :md="8"><div id="risk-gauge" style="height:240px" /></el-col>
              <el-col :xs="24" :md="16">
                <PageCard title="评分明细">
                  <div v-for="(v, k) in riskScore.subScores" :key="k" class="score-row">
                    <span class="score-label">{{ ({ changeVolume: '变更量', breakingChange: '破坏性变更', historicalBugs: '历史缺陷', impactRadius: '影响半径', timeFactor: '时间因子' } as any)[k] || k }}</span>
                    <el-progress :percentage="(v as number)" :color="v > 20 ? '#ef4444' : v > 12 ? '#f59e0b' : '#10b981'" :stroke-width="8" style="flex:1;margin: 0 12px" />
                    <span class="score-val">{{ v }}/{{ ({ changeVolume: 30, breakingChange: 25, historicalBugs: 20, impactRadius: 15, timeFactor: 10 } as any)[k] || 0 }}</span>
                  </div>
                  <el-alert style="margin-top:12px" :type="riskScore.totalScore >= 70 ? 'error' : riskScore.totalScore >= 40 ? 'warning' : 'success'" :title="riskScore.explanation" :closable="false" />
                </PageCard>
              </el-col>
            </el-row>
          </div>
        </el-tab-pane>

        <!-- Impact Analysis -->
        <el-tab-pane label="影响面分析" name="impact">
          <div class="tab-content">
            <div class="toolbar-row">
              <el-select v-model="impactFrom" style="width: 140px" size="small"><el-option v-for="v in versions" :key="v" :value="v" /></el-select>
              <span style="color:var(--et-text-muted)">→</span>
              <el-select v-model="impactTo" style="width: 140px" size="small"><el-option v-for="v in versions" :key="v" :value="v" /></el-select>
              <el-button type="primary" size="small" @click="loadImpact" :loading="impactLoading">分析影响面</el-button>
            </div>
            <el-empty v-if="!impactResult" description="选择版本区间后点击分析" :image-size="70" />
            <template v-else>
              <el-alert style="margin-bottom: 12px" type="info" :closable="false" show-icon
                        :title="'影响节点：' + impactResult.affectedNodeCount + ' 个'" />
              <el-row :gutter="16">
                <el-col :xs="24" :md="8">
                  <PageCard title="直接调用方" style="margin-bottom: 12px">
                    <div v-if="impactResult.directCallers?.length">
                      <div v-for="c in impactResult.directCallers" :key="c" class="ep-row">{{ c }}</div>
                    </div>
                    <el-empty v-else description="无" :image-size="50" />
                  </PageCard>
                </el-col>
                <el-col :xs="24" :md="8">
                  <PageCard title="受影响服务" style="margin-bottom: 12px">
                    <div v-if="impactResult.affectedServices?.length">
                      <div v-for="c in impactResult.affectedServices" :key="c" class="ep-row">{{ c }}</div>
                    </div>
                    <el-empty v-else description="无" :image-size="50" />
                  </PageCard>
                </el-col>
                <el-col :xs="24" :md="8">
                  <PageCard title="建议回归测试">
                    <div v-if="impactResult.suggestedRegression?.length">
                      <div v-for="c in impactResult.suggestedRegression" :key="c" class="ep-row">{{ c }}</div>
                    </div>
                    <el-empty v-else description="无" :image-size="50" />
                  </PageCard>
                </el-col>
              </el-row>
            </template>
          </div>
        </el-tab-pane>

        <!-- Risk History -->
        <el-tab-pane label="风险历史" name="risk-history">
          <div class="tab-content">
            <div v-if="riskHistory.length" id="risk-trend" style="height: 220px; margin-bottom: 16px" />
            <el-table :data="riskHistory" stripe size="small" v-loading="riskHistoryLoading">
              <el-table-column prop="version" label="版本" width="120" />
              <el-table-column label="风险分" width="180">
                <template #default="{ row }">
                  <el-progress :percentage="row.totalScore" :color="row.totalScore >= 70 ? '#ef4444' : row.totalScore >= 40 ? '#f59e0b' : '#10b981'" :stroke-width="10" style="width: 120px" />
                </template>
              </el-table-column>
              <el-table-column prop="explanation" label="说明" min-width="260" show-overflow-tooltip />
              <el-table-column prop="createdAt" label="评估时间" width="180" />
            </el-table>
            <el-empty v-if="!riskHistoryLoading && riskHistory.length === 0" description="暂无风险历史" :image-size="70" />
          </div>
        </el-tab-pane>

        <!-- Top Impact Endpoints -->
        <el-tab-pane label="高影响端点" name="endpoints">
          <div class="tab-content">
            <el-table :data="topEndpoints" stripe size="small" v-loading="endpointsLoading">
              <el-table-column prop="endpoint" label="端点" min-width="320" />
              <el-table-column label="调用方数量" width="160">
                <template #default="{ row }"><el-tag size="small" :type="row.callerCount > 5 ? 'danger' : row.callerCount > 2 ? 'warning' : 'info'">{{ row.callerCount }}</el-tag></template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!endpointsLoading && topEndpoints.length === 0" description="暂无依赖图数据（需要上报 INVENTORY_REPORT）" :image-size="70" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </PageCard>
  </div>
</template>

<style scoped>
.page-tabs :deep(.el-tabs__header) { margin: 0; padding: 0 20px }
.tab-content { padding: 16px 20px 20px }
.toolbar-row { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; font-size: 13px; color: var(--et-text-secondary) }
.tab-badge { margin-left: 4px }
.hot-row { display: flex; align-items: center; gap: 10px; padding: 7px 0; border-bottom: 1px solid var(--et-border) }
.hot-row:last-child { border: none }
.hot-rank { font-weight: 700; width: 28px; font-size: 12px }
.hot-path { flex: 1; font-family: monospace; font-size: 12px; color: var(--et-text); word-break: break-all }
.score-row { display: flex; align-items: center; margin-bottom: 10px }
.score-label { width: 80px; font-size: 13px; color: var(--et-text-secondary) }
.score-val { font-size: 12px; color: var(--et-text-muted); width: 50px; text-align: right }
.ep-row { padding: 6px 0; border-bottom: 1px dashed var(--et-border); font-size: 12px; font-family: monospace; word-break: break-all }
.ep-row:last-child { border: none }
</style>
