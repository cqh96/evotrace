<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { analysisApi, releaseApi, type BreakingChangeAlert, type Hotspots, type RiskScore, type ImpactResult } from '../api'
import * as echarts from 'echarts'

const project = ref('mall')
const activeTab = ref('hotspots')
const loading = ref(false)

// Hotspots
const hotspots = ref<Hotspots | null>(null)
const hotspotsDays = ref(30)

// Breaking changes
const breakingChanges = ref<BreakingChangeAlert[]>([])

// Risk score
const fromVersion = ref('')
const toVersion = ref('')
const versions = ref<string[]>([])
const riskScore = ref<RiskScore | null>(null)
const impactResult = ref<ImpactResult | null>(null)

const severityColor = (s: string) => ({ CRITICAL: '#f56c6c', WARNING: '#e6a23c', INFO: '#409eff' })[s] ?? '#909399'

async function loadVersions() {
  try {
    const releases = await releaseApi.list(project.value)
    if (releases?.length) {
      versions.value = releases.map(r => r.version)
      fromVersion.value = versions.value[Math.min(1, versions.value.length - 1)] ?? ''
      toVersion.value = versions.value[0] ?? ''
    }
  } catch { /* ignore */ }
}

async function loadHotspots() {
  loading.value = true
  try {
    hotspots.value = await analysisApi.hotspots(project.value, hotspotsDays.value)
  } catch { /* ignore */ } finally { loading.value = false }
}

async function loadBreakingChanges() {
  try { breakingChanges.value = await analysisApi.breakingChanges(project.value) } catch { breakingChanges.value = [] }
}

async function loadRiskScore() {
  if (!fromVersion.value || !toVersion.value) return
  loading.value = true
  try {
    riskScore.value = await analysisApi.riskScore(project.value, fromVersion.value, toVersion.value)
  } catch { riskScore.value = null }
  try {
    impactResult.value = await analysisApi.impact(project.value, fromVersion.value, toVersion.value)
  } catch { impactResult.value = null }
  loading.value = false
}

async function acknowledge(id: number) {
  try {
    await analysisApi.acknowledge(project.value, id)
    await loadBreakingChanges()
  } catch { /* ignore */ }
}

function renderRiskGauge(score: number) {
  const el = document.getElementById('risk-gauge')
  if (!el || score === undefined) return
  echarts.init(el).setOption({
    series: [{
      type: 'gauge', startAngle: 200, endAngle: -20, center: ['50%', '55%'], radius: '90%',
      min: 0, max: 100,
      axisLine: { lineStyle: { width: 20, color: [[0.4, '#67c23a'], [0.7, '#e6a23c'], [1, '#f56c6c']] } },
      pointer: { length: '60%', width: 6, itemStyle: { color: 'auto' } },
      detail: { fontSize: 24, formatter: '{value}', offsetCenter: [0, '70%'] },
      data: [{ value: score, name: '风险分' }]
    }]
  })
}

onMounted(async () => {
  await Promise.all([loadVersions(), loadHotspots(), loadBreakingChanges()])
})

// Watch tab changes
import { watch } from 'vue'
watch(activeTab, (tab) => {
  if (tab === 'risk') setTimeout(() => { if (riskScore.value) renderRiskGauge(riskScore.value.totalScore) }, 100)
})
</script>

<template>
  <div>
    <el-card shadow="never">
      <el-form inline>
        <el-form-item label="项目"><el-input v-model="project" style="width: 140px" /></el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadHotshots(); loadBreakingChanges(); loadVersions()">刷新</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" style="margin-top: 16px">
      <el-tabs v-model="activeTab">
        <!-- Tab: 代码热点 -->
        <el-tab-pane label="代码热点" name="hotspots">
          <el-form inline style="margin-bottom: 12px">
            <el-form-item label="天数"><el-input-number v-model="hotspotsDays" :min="7" :max="365" @change="loadHotspots" /></el-form-item>
            <el-form-item><el-button @click="loadHotspots" :loading="loading">查询</el-button></el-form-item>
          </el-form>
          <el-row :gutter="16">
            <el-col :span="12">
              <el-card shadow="never" v-loading="loading">
                <template #header>📁 变更最频繁文件</template>
                <div v-if="hotspots?.topChangedFiles?.length">
                  <div v-for="f in hotspots.topChangedFiles" :key="f.filePath" class="hotspot-row">
                    <span class="hotspot-path">{{ f.filePath }}</span>
                    <el-tag size="small">{{ f.changeCount }} 次</el-tag>
                  </div>
                </div>
                <el-empty v-else description="暂无数据" :image-size="60" />
              </el-card>
            </el-col>
            <el-col :span="12">
              <el-card shadow="never" v-loading="loading">
                <template #header>🐛 缺陷倾向文件</template>
                <div v-if="hotspots?.bugProneFiles?.length">
                  <div v-for="f in hotspots.bugProneFiles" :key="f.filePath" class="hotspot-row">
                    <span class="hotspot-path">{{ f.filePath }}</span>
                    <el-tag size="small" type="danger">{{ f.changeCount }} 次</el-tag>
                  </div>
                </div>
                <el-empty v-else description="暂无数据" :image-size="60" />
              </el-card>
            </el-col>
          </el-row>
          <el-row :gutter="16" style="margin-top: 16px">
            <el-col :span="24">
              <el-card shadow="never" v-loading="loading">
                <template #header>📦 模块变更热度</template>
                <el-table v-if="hotspots?.moduleHotspots?.length" :data="hotspots.moduleHotspots" stripe size="small">
                  <el-table-column prop="module" label="模块" min-width="200" />
                  <el-table-column prop="changes" label="变更数" width="100" sortable />
                  <el-table-column prop="authors" label="参与人数" width="100" sortable />
                  <el-table-column prop="avgDiffSize" label="平均变更行数" width="130" sortable />
                </el-table>
                <el-empty v-else description="暂无数据" :image-size="60" />
              </el-card>
            </el-col>
          </el-row>
        </el-tab-pane>

        <!-- Tab: 破坏性变更 -->
        <el-tab-pane label="破坏性变更">
          <template #label>
            破坏性变更 <el-badge v-if="breakingChanges.length" :value="breakingChanges.length" style="margin-left: 4px" />
          </template>
          <el-table :data="breakingChanges" stripe v-loading="loading">
            <el-table-column label="严重级别" width="120">
              <template #default="{ row }">
                <el-tag :color="severityColor(row.severity)" effect="dark" style="border: none">{{ row.severity }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="changeType" label="变更类型" width="180" />
            <el-table-column label="详情" min-width="300">
              <template #default="{ row }">{{ row.detail?.message ?? row.detail?.identityKey ?? '-' }}</template>
            </el-table-column>
            <el-table-column prop="createdAt" label="时间" width="180" />
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button v-if="!row.acknowledged" size="small" @click="acknowledge(row.id)">确认</el-button>
                <el-tag v-else size="small" type="success">已确认</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loading && breakingChanges.length === 0" description="暂无破坏性变更 🎉" :image-size="80" />
        </el-tab-pane>

        <!-- Tab: 发布风险评估 -->
        <el-tab-pane label="发布风险评估" name="risk">
          <el-form inline style="margin-bottom: 12px">
            <el-form-item label="从版本">
              <el-select v-model="fromVersion" style="width: 140px"><el-option v-for="v in versions" :key="v" :value="v" /></el-select>
            </el-form-item>
            <el-form-item label="到版本">
              <el-select v-model="toVersion" style="width: 140px"><el-option v-for="v in versions" :key="v" :value="v" /></el-select>
            </el-form-item>
            <el-form-item><el-button type="primary" @click="loadRiskScore" :loading="loading">评估风险</el-button></el-form-item>
          </el-form>
          <el-row v-if="riskScore" :gutter="16">
            <el-col :span="8">
              <div id="risk-gauge" style="height: 220px" />
              <div style="text-align: center; margin-top: -20px">
                <el-tag size="large" :type="riskScore.totalScore >= 70 ? 'danger' : riskScore.totalScore >= 40 ? 'warning' : 'success'">
                  {{ riskScore.riskLevel }}
                </el-tag>
              </div>
            </el-col>
            <el-col :span="16">
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="变更量">{{ riskScore.subScores.changeVolume }}/30</el-descriptions-item>
                <el-descriptions-item label="破坏性变更">{{ riskScore.subScores.breakingChange }}/25</el-descriptions-item>
                <el-descriptions-item label="历史缺陷">{{ riskScore.subScores.historicalBugs }}/20</el-descriptions-item>
                <el-descriptions-item label="影响半径">{{ riskScore.subScores.impactRadius }}/15</el-descriptions-item>
                <el-descriptions-item label="时间因子">{{ riskScore.subScores.timeFactor }}/10</el-descriptions-item>
                <el-descriptions-item label="总分"><b>{{ riskScore.totalScore }}/100</b></el-descriptions-item>
              </el-descriptions>
              <el-alert type="warning" :closable="false" style="margin-top: 12px" :title="riskScore.explanation" />
            </el-col>
          </el-row>
          <div v-if="impactResult" style="margin-top: 16px">
            <el-card shadow="never">
              <template #header>影响面分析</template>
              <p><b>影响节点数：</b>{{ impactResult.affectedNodeCount }}</p>
              <p><b>受影响服务：</b><el-tag v-for="s in impactResult.affectedServices" :key="s" size="small" style="margin: 2px">{{ s }}</el-tag></p>
              <p v-for="(r, i) in impactResult.suggestedRegression" :key="i">{{ r }}</p>
            </el-card>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.hotspot-row { display: flex; justify-content: space-between; align-items: center; padding: 6px 0; border-bottom: 1px dashed #ebeef5 }
.hotspot-path { font-family: monospace; font-size: 13px; word-break: break-all }
</style>
