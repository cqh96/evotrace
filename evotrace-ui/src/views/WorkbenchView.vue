<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import * as echarts from 'echarts'
import { useRouter } from 'vue-router'
import {
  User, Tickets, Warning, MagicStick, ChatDotRound, ArrowRight
} from '@element-plus/icons-vue'
import { pmApi, bugApi, feedbackApi, automationRuleApi } from '../api'
import { useProjectStore } from '../stores/project'

const projectStore = useProjectStore()
const { current: project } = storeToRefs(projectStore)
const router = useRouter()

const loading = ref(false)
const reqs = ref<any[]>([])
const bugs = ref<any[]>([])
const feedbacks = ref<any[]>([])
const rules = ref<any[]>([])

async function load() {
  if (!project.value) return
  loading.value = true
  try {
    const [r, b, f, ru] = await Promise.all([
      pmApi.list(project.value).catch(() => []),
      bugApi.list(project.value).catch(() => []),
      feedbackApi.list(project.value).catch(() => []),
      automationRuleApi.list(project.value).catch(() => [])
    ])
    reqs.value = r as any[]
    bugs.value = b as any[]
    feedbacks.value = f as any[]
    rules.value = ru as any[]
  } catch {}
  loading.value = false
}

const reqStats = computed(() => {
  const by: Record<string, number> = {}
  for (const req of reqs.value) by[req.status] = (by[req.status] || 0) + 1
  return by
})
const openBugs = computed(() => bugs.value.filter(b => !['CLOSED', 'VERIFIED'].includes(b.status)).length)
const newFeedback = computed(() => feedbacks.value.filter(f => f.status === 'NEW').length)
const enabledRules = computed(() => rules.value.filter(r => r.enabled).length)

const statusLabels: Record<string, string> = {
  DRAFT: '草稿', REVIEW: '评审中', DEVELOPING: '开发中', TESTING: '测试中', DONE: '已完成'
}
const statusColors: Record<string, string> = {
  DRAFT: '#64748b', REVIEW: '#fbbf24', DEVELOPING: '#38e1ff', TESTING: '#6d7cff', DONE: '#34d399'
}

const cards = [
  { label: '我的需求', value: reqs.value.length, foot: '需求工作台', icon: User, color: '#6d7cff', path: '/pm' },
  { label: '打开缺陷', value: openBugs.value, foot: '缺陷管理', icon: Warning, color: '#fb7185', path: '/bugs' },
  { label: '待处理反馈', value: newFeedback.value, foot: 'AI 反馈分析', icon: ChatDotRound, color: '#38e1ff', path: '/feedback' },
  { label: '启用规则', value: enabledRules.value, foot: '自动化规则', icon: MagicStick, color: '#34d399', path: '/automation' }
]

function go(path: string) { router.push(path) }

const chartObservers = new Map<string, ResizeObserver>()
function readThemeVars() {
  const cs = getComputedStyle(document.documentElement)
  return {
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
function setupCharts() {
  const el = document.getElementById('req-ring')
  if (!el) return
  const existing = echarts.getInstanceByDom(el); if (existing) existing.dispose()
  const v = readThemeVars()
  const keys = Object.keys(reqStats.value)
  echarts.init(el).setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, textStyle: { color: v.axis } },
    series: [{
      type: 'pie', radius: ['52%', '78%'], center: ['50%', '44%'],
      label: { show: false },
      data: keys.map(k => ({
        name: statusLabels[k] || k,
        value: reqStats.value[k],
        itemStyle: { color: statusColors[k] || '#6d7cff' }
      }))
    }]
  })
  observeChart(el)
}

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

onMounted(async () => {
  await load()
  setupCharts()
})
</script>

<template>
  <div class="page">
    <!-- 欢迎横幅 -->
    <div class="banner">
      <div>
        <h2>{{ greeting }}，开发者 👋</h2>
        <p>当前项目 <b>{{ project }}</b> — 以下是你的个人工作台聚合视图</p>
      </div>
      <div class="banner-right">
        <div class="big-num">{{ reqs.length }}</div>
        <div class="big-label">需求总数</div>
      </div>
    </div>

    <!-- 聚合卡片 -->
    <div class="card-grid">
      <div v-for="c in cards" :key="c.label" class="agg-card" :style="{ '--ac': c.color }" @click="go(c.path)">
        <div class="agg-ic"><el-icon :size="18"><component :is="c.icon" /></el-icon></div>
        <div>
          <div class="agg-num">{{ c.value }}</div>
          <div class="agg-label">{{ c.label }}</div>
        </div>
        <div class="agg-foot">{{ c.foot }} <el-icon><ArrowRight /></el-icon></div>
      </div>
    </div>

    <div class="grid-2">
      <!-- 需求状态分布 -->
      <div class="et-card">
        <div class="et-card-head">
          <div class="et-card-title"><span class="et-tic g-indigo"><el-icon><User /></el-icon></span> 需求状态分布</div>
        </div>
        <div class="et-card-body"><div id="req-ring" class="chart"></div></div>
      </div>

      <!-- 待办清单 -->
      <div class="et-card">
        <div class="et-card-head">
          <div class="et-card-title"><span class="et-tic g-rose"><el-icon><Tickets /></el-icon></span> 我的待办</div>
        </div>
        <div class="et-card-body">
          <div class="todo-section">
            <div class="todo-title">打开缺陷 <span class="cnt">{{ openBugs }}</span></div>
            <div class="todo-list">
              <div v-for="b in bugs.filter(x => !['CLOSED', 'VERIFIED'].includes(x.status)).slice(0, 5)" :key="b.id" class="todo-item" @click="go('/bugs')">
                <span class="sev" :class="b.severity.toLowerCase()">{{ b.severity }}</span>
                <span class="t-text">{{ b.title }}</span>
                <span class="t-status">{{ b.status }}</span>
              </div>
              <div v-if="!bugs.filter(x => !['CLOSED', 'VERIFIED'].includes(x.status)).length" class="empty">暂无未关闭缺陷 🎉</div>
            </div>
          </div>
          <div class="todo-section">
            <div class="todo-title">待处理反馈 <span class="cnt">{{ newFeedback }}</span></div>
            <div class="todo-list">
              <div v-for="f in feedbacks.filter(x => x.status === 'NEW').slice(0, 5)" :key="f.id" class="todo-item" @click="go('/feedback')">
                <span class="src">{{ f.source }}</span>
                <span class="t-text">{{ f.content }}</span>
              </div>
              <div v-if="!feedbacks.filter(x => x.status === 'NEW').length" class="empty">暂无待处理反馈</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.banner {
  display: flex; align-items: center; justify-content: space-between;
  padding: 22px 24px; margin-bottom: 18px;
  border-radius: 18px;
  background: linear-gradient(135deg, rgba(109, 124, 255, 0.16), rgba(56, 225, 255, 0.08));
  border: 1px solid var(--et-hover-border);
}
.banner h2 { margin: 0 0 6px; font-size: 22px; }
.banner p { margin: 0; font-size: 13px; color: var(--et-text-secondary); }
.banner b { color: var(--et-grad-c); }
.banner-right { display: flex; flex-direction: column; align-items: flex-end; }
.big-num { font-size: 40px; font-weight: 800; line-height: 1; background: linear-gradient(90deg, var(--et-grad-a), var(--et-grad-c)); -webkit-background-clip: text; background-clip: text; -webkit-text-fill-color: transparent; }
.big-label { font-size: 12px; color: var(--et-text-muted); margin-top: 4px; }

.card-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; margin-bottom: 18px; }
.agg-card {
  position: relative; padding: 18px; border-radius: 16px; cursor: pointer;
  background: var(--et-card-bg); border: 1px solid var(--et-border);
  transition: all 0.2s; overflow: hidden;
}
.agg-card:hover { border-color: var(--et-hover-border); box-shadow: var(--et-shadow-md); transform: translateY(-3px); }
.agg-ic {
  width: 40px; height: 40px; border-radius: 12px; margin-bottom: 12px;
  display: flex; align-items: center; justify-content: center; color: #fff;
  background: linear-gradient(135deg, var(--ac), color-mix(in srgb, var(--ac) 60%, #a78bfa));
}
.agg-num { font-size: 30px; font-weight: 800; line-height: 1; }
.agg-label { font-size: 13px; color: var(--et-text-secondary); margin-top: 4px; font-weight: 500; }
.agg-foot { margin-top: 14px; font-size: 12px; color: var(--et-text-muted); display: flex; align-items: center; gap: 4px; }

.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
.grid-2 .et-card + .et-card { margin-top: 0; }
.chart { width: 100%; height: 300px; }

.todo-section { margin-bottom: 18px; }
.todo-title { font-size: 13px; font-weight: 700; margin-bottom: 8px; color: var(--et-text-secondary); }
.todo-title .cnt {
  margin-left: 6px; font-size: 11px; font-weight: 700; color: var(--et-text-muted);
  padding: 1px 8px; border-radius: 20px; background: var(--et-bg-muted);
}
.todo-list { display: flex; flex-direction: column; gap: 8px; }
.todo-item {
  display: flex; align-items: center; gap: 10px; cursor: pointer;
  padding: 9px 12px; border-radius: 10px; background: var(--et-bg-muted); border: 1px solid var(--et-border);
  transition: all 0.15s;
}
.todo-item:hover { border-color: var(--et-hover-border); }
.t-text { flex: 1; font-size: 12.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.t-status { font-size: 11px; color: var(--et-text-muted); }
.sev { font-size: 10px; font-weight: 700; color: #fff; padding: 1px 6px; border-radius: 5px; }
.sev.p0 { background: #dc2626; } .sev.p1 { background: #f97316; } .sev.p2 { background: #eab308; } .sev.p3 { background: #64748b; }
.src { font-size: 10px; font-weight: 700; color: var(--et-text-secondary); padding: 1px 6px; border-radius: 5px; background: var(--et-bg-muted); border: 1px solid var(--et-border); }
.empty { color: var(--et-text-muted); font-size: 12.5px; padding: 8px; }

@media (max-width: 900px) {
  .grid-2 { grid-template-columns: 1fr; }
}
</style>