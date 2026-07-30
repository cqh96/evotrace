<script setup lang="ts">
import { onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { dashboardApi, type DashboardStats, type RecentRelease, type TrendDay } from '../api'

const stats = ref<DashboardStats>({ projectCount: 0, appCount: 0, todayChanges: 0, releaseCount: 0 })
const recent = ref<RecentRelease[]>([])
const loading = ref(true)

const statCards = [
  { label: '接入项目', key: 'projectCount' as const },
  { label: '应用/服务', key: 'appCount' as const },
  { label: '今日变更', key: 'todayChanges' as const },
  { label: '已发布版本', key: 'releaseCount' as const }
]

async function load() {
  try {
    const [s, r, t] = await Promise.all([
      dashboardApi.stats(),
      dashboardApi.recentReleases(),
      dashboardApi.trend()
    ])
    stats.value = s
    recent.value = r
    renderTrend(t)
  } catch {
    // server not ready, keep defaults
  } finally {
    loading.value = false
  }
}

function renderTrend(data: TrendDay[]) {
  const el = document.getElementById('trend')
  if (!el || !data.length) return
  echarts.init(el).setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['变更数', '发布数'] },
    grid: { left: 40, right: 20, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: data.map(d => d.day.substring(5)) },
    yAxis: { type: 'value' },
    series: [
      { name: '变更数', type: 'line', smooth: true, data: data.map(d => d.changes) },
      { name: '发布数', type: 'bar', data: data.map(d => d.releases) }
    ]
  })
}

onMounted(load)
</script>

<template>
  <div>
    <el-row :gutter="16">
      <el-col v-for="s in statCards" :key="s.label" :span="6">
        <el-card shadow="hover" v-loading="loading">
          <div class="stat-value">{{ stats[s.key] }}</div>
          <div class="stat-label">{{ s.label }}</div>
        </el-card>
      </el-col>
    </el-row>
    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>近 7 日演化趋势</template>
          <div id="trend" style="height: 300px" />
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>最近发布</template>
          <div v-if="recent.length === 0 && !loading" class="empty-hint">暂无发布记录</div>
          <div v-for="r in recent" :key="r.version + r.releasedAt" class="release-item">
            <el-tag size="small" effect="dark">{{ r.version }}</el-tag>
            <span class="release-project">{{ r.project }}</span>
            <div class="release-summary">{{ r.summary ?? '—' }}</div>
            <div class="release-time">{{ r.releasedAt }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.stat-value { font-size: 28px; font-weight: 700 }
.stat-label { color: #909399; margin-top: 4px }
.release-item { padding: 8px 0; border-bottom: 1px dashed #ebeef5 }
.release-project { margin-left: 8px; font-weight: 600 }
.release-summary { color: #606266; margin-top: 4px }
.release-time { color: #909399; font-size: 12px; margin-top: 2px }
.empty-hint { color: #c0c4cc; font-style: italic; text-align: center; padding: 20px 0 }
</style>
