<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { DataAnalysis, Collection, EditPen, Promotion, ChatDotRound, Checked, Refresh, Timer } from '@element-plus/icons-vue'
import PageCard from '../components/PageCard.vue'
import StatCard from '../components/StatCard.vue'
import { dashboardApi, type DashboardStats, type RecentRelease, type TrendDay } from '../api'

const router = useRouter()
const stats = ref<DashboardStats>({ projectCount: 0, appCount: 0, todayChanges: 0, releaseCount: 0 })
const recent = ref<RecentRelease[]>([])
const trendData = ref<TrendDay[]>([])
const loading = ref(true)

const statCards = [
  { label: '接入项目', key: 'projectCount' as const, icon: DataAnalysis, color: '#4f5ad1',
    foot: '全链路演化追踪' },
  { label: '应用/服务', key: 'appCount' as const, icon: Collection, color: '#059669',
    foot: '覆盖业务服务模块' },
  { label: '今日变更', key: 'todayChanges' as const, icon: EditPen, color: '#b45309',
    foot: 'SDK 实时采集同步' },
  { label: '已发布版本', key: 'releaseCount' as const, icon: Promotion, color: '#6d4fd6',
    foot: '本月发布记录' }
]

const changesTrend = computed(() => trendData.value.map((d) => d.changes))
const releasesTrend = computed(() => trendData.value.map((d) => d.releases))
const weekChanges = computed(() => trendData.value.reduce((s, d) => s + d.changes, 0))

const username = localStorage.getItem('evotrace_user') ?? '管理员'
const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})
const today = new Date().toLocaleDateString('zh-CN', {
  year: 'numeric', month: 'long', day: 'numeric', weekday: 'long'
})

function readThemeVars() {
  const cs = getComputedStyle(document.documentElement)
  return {
    axis: cs.getPropertyValue('--et-axis').trim(),
    grid: cs.getPropertyValue('--et-gridline').trim(),
    text: cs.getPropertyValue('--et-text').trim(),
    card: cs.getPropertyValue('--et-card-solid').trim(),
    border: cs.getPropertyValue('--et-hover-border').trim()
  }
}

function renderTrend(data: TrendDay[]) {
  const el = document.getElementById('trend-chart')
  if (!el || !data.length) return
  const existing = echarts.getInstanceByDom(el)
  if (existing) existing.dispose()
  const v = readThemeVars()

  echarts.init(el).setOption({
    tooltip: {
      trigger: 'axis',
      backgroundColor: v.card,
      borderColor: v.border,
      textStyle: { color: v.text, fontSize: 12 },
      axisPointer: { lineStyle: { color: 'rgba(79,90,209,0.5)' } }
    },
    legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8,
      textStyle: { color: '#5c6a8a', fontSize: 12 } },
    grid: { left: 40, right: 16, top: 16, bottom: 40 },
    xAxis: {
      type: 'category', data: data.map((d) => d.day.substring(5)),
      axisLine: { lineStyle: { color: v.grid } },
      axisLabel: { color: v.axis, fontSize: 11 },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value', splitLine: { lineStyle: { color: v.grid } },
      axisLabel: { color: v.axis, fontSize: 11 }
    },
    series: [
      {
        name: '变更数', type: 'line', smooth: true, symbol: 'circle', symbolSize: 7,
        data: data.map((d) => d.changes),
        lineStyle: { color: '#4f5ad1', width: 2.5 },
        itemStyle: { color: '#4f5ad1', borderColor: v.card, borderWidth: 2 },
        areaStyle: {
          color: 'rgba(79,90,209,0.2)'
        }
      },
      {
        name: '发布数', type: 'bar', barWidth: 16,
        data: data.map((d) => d.releases),
        itemStyle: {
          color: 'rgba(8,145,178,0.85)',
          borderRadius: [4, 4, 0, 0]
        }
      }
    ]
  })

  const observer = new ResizeObserver(() => {
    echarts.getInstanceByDom(el)?.resize()
  })
  observer.observe(el)
}

async function load() {
  try {
    const [s, r, t] = await Promise.all([
      dashboardApi.stats(), dashboardApi.recentReleases(), dashboardApi.trend()
    ])
    stats.value = s
    recent.value = r
    trendData.value = t
    renderTrend(t)
  } catch { /* server not ready */ }
  loading.value = false
}

function onThemeChange() {
  if (trendData.value.length) renderTrend(trendData.value)
}

onMounted(() => {
  load()
  window.addEventListener('evotrace-theme-change', onThemeChange)
})
</script>

<template>
  <div>
    <!-- Hero 概览 -->
    <section class="et-hero rise" style="--d:.02s">
      <div class="hero-inner">
        <div>
          <h2>{{ greeting }}，<span>{{ username }}</span> 👋</h2>
          <p class="et-hero-sub">{{ today }} · 近 7 日共 {{ weekChanges }} 次变更</p>
        </div>
        <div class="hero-chips">
          <span class="chip-mini">
            <span class="et-pulse" style="width:6px;height:6px"></span>
            SDK 实时采集
          </span>
          <span class="chip-mini">
            <el-icon :size="13" color="var(--et-primary)"><DataAnalysis /></el-icon>
            接入项目 <b>{{ stats.projectCount }}</b> 个
          </span>
          <span class="chip-mini">
            <el-icon :size="13" color="#0e7490"><Promotion /></el-icon>
            已发布 <b>{{ stats.releaseCount }}</b> 个版本
          </span>
        </div>
        <div class="hero-actions">
          <button class="btn btn-primary" @click="router.push('/timeline')">
            <el-icon :size="15"><Timer /></el-icon>查看演化时间线
          </button>
          <button class="btn btn-ghost" @click="router.push('/code-review')">
            <el-icon :size="15"><Checked /></el-icon>发起 AI 评审
          </button>
          <button class="btn btn-ghost" @click="router.push('/qa')">
            <el-icon :size="15"><ChatDotRound /></el-icon>AI 问答
          </button>
          <button class="btn btn-ghost" @click="load">
            <el-icon :size="15"><Refresh /></el-icon>刷新
          </button>
        </div>
      </div>
    </section>

    <!-- 统计卡片 -->
    <el-row :gutter="18" style="margin-top: 22px">
      <el-col v-for="(s, i) in statCards" :key="s.label" :xs="12" :sm="6">
        <div class="rise" :style="{ '--d': 0.08 + i * 0.06 + 's' }">
          <StatCard
            :label="s.label" :value="stats[s.key]" :icon="s.icon" :color="s.color"
            :loading="loading" :foot="s.foot"
            :trend="s.key === 'todayChanges' ? changesTrend : s.key === 'releaseCount' ? releasesTrend : undefined"
            :suffix="s.key === 'todayChanges' ? '次' : '个'"
          />
        </div>
      </el-col>
    </el-row>

    <!-- 趋势图 + 最近发布 -->
    <el-row :gutter="18" style="margin-top: 18px">
      <el-col :xs="24" :lg="15">
        <div class="rise" style="--d:.32s">
          <PageCard title="演化趋势" sub="近 7 日变更与发布走势" :icon="Timer">
            <template #extra>
              <div class="legend">
                <span class="lg"><span class="lg-line lg-a"></span>变更</span>
                <span class="lg"><span class="lg-dot lg-b"></span>发布</span>
              </div>
              <div class="et-seg"><button class="on">近 7 日</button></div>
            </template>
            <div id="trend-chart" style="height: 300px" />
          </PageCard>
        </div>
      </el-col>
      <el-col :xs="24" :lg="9">
        <div class="rise" style="--d:.38s">
          <PageCard title="最近发布" sub="最新版本动态" :icon="Promotion">
            <template #extra>
              <button class="et-link-more" @click="router.push('/timeline')">全部动态</button>
            </template>
            <div v-if="recent.length === 0 && !loading" class="et-empty-hint">
              <div class="et-empty-ic"><el-icon :size="22"><Timer /></el-icon></div>
              暂无发布记录
            </div>
            <div v-for="(r, i) in recent" :key="r.version + r.releasedAt" class="release-item">
              <span class="ver-tag" :class="['v' + ((i % 3) + 1)]">{{ r.version }}</span>
              <div class="rel-main">
                <div class="rel-top">
                  <span class="rel-proj">{{ r.project || '—' }}</span>
                  <span class="rel-time">{{ r.releasedAt?.substring(0, 16) }}</span>
                </div>
                <div class="rel-sum">{{ r.summary ?? '暂无摘要' }}</div>
              </div>
            </div>
          </PageCard>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.hero-inner { position: relative; z-index: 1; }
.hero-chips { display: flex; gap: 10px; margin-top: 16px; flex-wrap: wrap; }
.chip-mini {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--et-text-secondary);
  padding: 6px 12px;
  border-radius: 10px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
}
.chip-mini b { color: var(--et-text); font-variant-numeric: tabular-nums; }
.hero-actions { display: flex; gap: 12px; margin-top: 20px; flex-wrap: wrap; }
.btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 18px;
  border-radius: 12px;
  font-size: 13.5px;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.18s, box-shadow 0.18s, border-color 0.18s, background 0.18s;
}
.btn:active { transform: scale(0.97); }
.btn-primary {
  color: #fff;
  background: var(--et-primary);
  box-shadow: var(--et-shadow-sm);
}
.btn-primary:hover { transform: translateY(-2px); box-shadow: var(--et-shadow); }
.btn-ghost {
  color: var(--et-text-secondary);
  border: 1px solid var(--et-border);
  background: var(--et-bg-muted);
}
.btn-ghost:hover { color: var(--et-text); border-color: var(--et-primary); transform: translateY(-2px); }

.legend { display: flex; gap: 14px; font-size: 12px; color: var(--et-text-secondary); }
.lg { display: inline-flex; align-items: center; gap: 6px; }
.lg-line { width: 14px; height: 3px; border-radius: 2px; }
.lg-dot { width: 9px; height: 9px; border-radius: 50%; }
.lg-a { background: #4f5ad1; }
.lg-b { background: #0891b2; }

.release-item { display: flex; align-items: flex-start; gap: 12px; padding: 13px 2px; border-bottom: 1px solid var(--et-border); }
.release-item:first-child { padding-top: 4px; }
.release-item:last-child { border-bottom: none; padding-bottom: 0; }
.ver-tag {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 800;
  padding: 5px 9px;
  border-radius: 8px;
  color: #fff;
  letter-spacing: 0.3px;
  font-variant-numeric: tabular-nums;
}
.v1 { background: #4f5ad1; }
.v2 { background: #0891b2; color: #fff; }
.v3 { background: #d6336c; }
.rel-main { flex: 1; min-width: 0; }
.rel-top { display: flex; align-items: center; gap: 8px; }
.rel-proj { font-size: 13px; font-weight: 700; }
.rel-time { margin-left: auto; font-size: 11px; color: var(--et-text-muted); flex-shrink: 0; }
.rel-sum {
  font-size: 12px;
  color: var(--et-text-secondary);
  margin-top: 3px;
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
