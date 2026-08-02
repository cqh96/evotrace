<script setup lang="ts">
import { onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { DataAnalysis, Collection, EditPen, Promotion } from '@element-plus/icons-vue'
import PageCard from '../components/PageCard.vue'
import StatCard from '../components/StatCard.vue'
import { dashboardApi, type DashboardStats, type RecentRelease, type TrendDay } from '../api'

const stats = ref<DashboardStats>({ projectCount: 0, appCount: 0, todayChanges: 0, releaseCount: 0 })
const recent = ref<RecentRelease[]>([])
const loading = ref(true)

const statCards = [
  { label: '接入项目', key: 'projectCount' as const, icon: DataAnalysis, color: '#6366f1' },
  { label: '应用/服务', key: 'appCount' as const, icon: Collection, color: '#10b981' },
  { label: '今日变更', key: 'todayChanges' as const, icon: EditPen, color: '#f59e0b' },
  { label: '已发布版本', key: 'releaseCount' as const, icon: Promotion, color: '#8b5cf6' }
]

async function load() {
  try {
    const [s, r, t] = await Promise.all([
      dashboardApi.stats(), dashboardApi.recentReleases(), dashboardApi.trend()
    ])
    stats.value = s
    recent.value = r
    renderTrend(t)
  } catch { /* server not ready */ }
  loading.value = false
}

function renderTrend(data: TrendDay[]) {
  const el = document.getElementById('trend-chart')
  if (!el || !data.length) return
  const existing = echarts.getInstanceByDom(el)
  if (existing) existing.dispose()

  echarts.init(el).setOption({
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0, textStyle: { color: '#94a3b8', fontSize: 12 } },
    grid: { left: 40, right: 16, top: 12, bottom: 36 },
    xAxis: { type: 'category', data: data.map(d => d.day.substring(5)),
      axisLine: { lineStyle: { color: '#e2e8f0' } }, axisLabel: { color: '#94a3b8', fontSize: 11 } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#f1f5f9' } },
      axisLabel: { color: '#94a3b8', fontSize: 11 } },
    series: [
      {
        name: '变更数', type: 'line', smooth: true, symbol: 'circle', symbolSize: 6,
        data: data.map(d => d.changes),
        lineStyle: { color: '#6366f1', width: 2.5 },
        itemStyle: { color: '#6366f1' },
        areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [{ offset: 0, color: 'rgba(99,102,241,0.25)' }, { offset: 1, color: 'rgba(99,102,241,0.02)' }] } }
      },
      {
        name: '发布数', type: 'bar', barWidth: 16,
        data: data.map(d => d.releases),
        itemStyle: { color: '#818cf8', borderRadius: [4, 4, 0, 0] }
      }
    ]
  })

  const observer = new ResizeObserver(() => {
    echarts.getInstanceByDom(el)?.resize()
  })
  observer.observe(el)
}

onMounted(load)
</script>

<template>
  <div>
    <el-row :gutter="16">
      <el-col v-for="s in statCards" :key="s.label" :xs="12" :sm="6">
        <StatCard
          :label="s.label" :value="stats[s.key]" :icon="s.icon" :color="s.color" :loading="loading"
        />
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :xs="24" :lg="15">
        <PageCard title="近 7 日演化趋势">
          <div id="trend-chart" style="height: 300px" />
        </PageCard>
      </el-col>
      <el-col :xs="24" :lg="9">
        <PageCard title="最近发布">
          <div v-if="recent.length === 0 && !loading" class="et-empty-hint">暂无发布记录</div>
          <div v-for="(r, i) in recent" :key="r.version + r.releasedAt" class="release-item" :class="{ first: i === 0 }">
            <div class="release-top">
              <el-tag size="small" effect="dark" round>{{ r.version }}</el-tag>
              <span class="release-project">{{ r.project || '—' }}</span>
              <span class="release-time">{{ r.releasedAt?.substring(0, 16) }}</span>
            </div>
            <div class="release-summary">{{ r.summary ?? '暂无摘要' }}</div>
          </div>
        </PageCard>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.release-item { padding: 12px 0; border-bottom: 1px solid var(--et-border) }
.release-item.first { padding-top: 0 }
.release-item:last-child { border-bottom: none; padding-bottom: 0 }
.release-top { display: flex; align-items: center; gap: 10px; margin-bottom: 6px }
.release-project { font-weight: 600; color: var(--et-text) }
.release-time { color: var(--et-text-muted); font-size: 12px; margin-left: auto }
.release-summary { color: var(--et-text-secondary); font-size: 13px; line-height: 1.5 }
</style>
