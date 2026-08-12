<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import {
  DataAnalysis, Timer, Switch, ChatDotRound, Connection, TrendCharts, Bell,
  Monitor, Fold, Expand, SwitchButton, Sunny, Moon, Checked, Setting,
  Search, ArrowRight, User, Files, Tickets, PieChart, Aim, Odometer, Link, DataLine,
  Warning, MagicStick, Lightning, Box, FolderOpened
} from '@element-plus/icons-vue'
import { projectApi, memberApi, type Project } from '../api'
import { useProjectStore } from '../stores/project'

const route = useRoute()
const router = useRouter()
const collapsed = ref(false)
const username = localStorage.getItem('evotrace_user') ?? 'admin'

/* ---------- 分组折叠（手风琴式，默认展开） ---------- */
const collapsedGroups = ref<Set<string>>(new Set())
function toggleGroup(title: string) {
  const next = new Set(collapsedGroups.value)
  if (next.has(title)) next.delete(title)
  else next.add(title)
  collapsedGroups.value = next
}
function isGroupCollapsed(title: string) {
  return collapsed.value || collapsedGroups.value.has(title)
}

/* ---------- 主题（默认深色） ---------- */
const isDark = ref(localStorage.getItem('evotrace_theme') !== 'light')
function toggleTheme() {
  isDark.value = !isDark.value
  document.documentElement.setAttribute('data-theme', isDark.value ? 'dark' : 'light')
  localStorage.setItem('evotrace_theme', isDark.value ? 'dark' : 'light')
  window.dispatchEvent(new Event('evotrace-theme-change'))
}
document.documentElement.setAttribute('data-theme', isDark.value ? 'dark' : 'light')

/* ---------- 全局项目选择 ---------- */
const projectStore = useProjectStore()
const { current: currentProject } = storeToRefs(projectStore)
const projects = ref<Project[]>([])
const myRole = ref('ADMIN')
onMounted(async () => {
  try { projects.value = await projectApi.active() } catch { projects.value = [] }
  // 无有效当前项目时（未选择，或已停用/下线），自动选中第一个在线项目
  if (!projects.value.some((p) => p.projectKey === currentProject.value)) {
    if (projects.value.length) projectStore.setCurrent(projects.value[0].projectKey)
    else projectStore.setCurrent('')
  }
  // 拉取当前用户在当前项目中的角色，用于菜单权限过滤
  const key = currentProject.value
  if (key) {
    try { const r = await memberApi.me(key, username); myRole.value = r.role } catch {}
  }
})

// 按角色过滤菜单（无 roles 约束的项对所有角色可见）
const visibleNavGroups = computed(() => {
  const allow = (item: NavItem) => !item.roles || item.roles.includes(myRole.value)
  return navGroups
    .map((g) => ({ ...g, items: g.items.filter(allow) }))
    .filter((g) => g.items.length > 0)
})

/* ---------- 导航 ---------- */
interface NavItem { path: string; icon: typeof DataAnalysis; label: string; badge?: string; desc?: string; roles?: string[] }
const navGroups: { title: string; items: NavItem[] }[] = [
  { title: '概览', items: [
    { path: '/dashboard', icon: DataAnalysis, label: '项目总览', desc: '工作台首页' },
    { path: '/workbench', icon: User, label: '个人工作台', desc: '我的待办聚合' }
  ] },
  {
    title: '演化追踪',
    items: [
      { path: '/timeline', icon: Timer, label: '演化时间线', badge: 'AI', desc: '全链路变更历史' },
      { path: '/compare', icon: Switch, label: '版本对比', desc: '任意两版本差异' },
      { path: '/analysis', icon: TrendCharts, label: '智能分析', desc: 'AI 自动分析' },
      { path: '/code-review', icon: Checked, label: '代码审查', desc: 'AI 提交审查' }
    ]
  },
  {
    title: '测试中心',
    items: [
      { path: '/qa-dashboard', icon: Tickets, label: '测试用例', desc: '用例与覆盖率' },
      { path: '/api-debug', icon: Files, label: '接口调试', desc: '接口文档/Mock' },
      { path: '/scenario', icon: Connection, label: '场景编排', desc: '多接口自动化' },
      { path: '/ui-test', icon: Aim, label: 'UI 测试', desc: 'Selenium 自动化' },
      { path: '/perf-test', icon: Odometer, label: '性能测试', desc: '并发压测与指标' }
    ]
  },
  {
    title: '链路追踪',
    items: [
      { path: '/trace-governance', icon: Connection, label: '链路治理中心', desc: '未关联/待确认/悬空键' },
      { path: '/release-cockpit', icon: Odometer, label: '版本全景', desc: '版本就绪度与门禁' }
    ]
  },
  {
    title: '质量与集成',
    items: [
      { path: '/test-report', icon: PieChart, label: '测试报告', desc: '可视化质量报告' },
      { path: '/ci-integration', icon: Link, label: 'CI 集成', desc: 'Jenkins 流水线触发' }
    ]
  },
  {
    title: '需求与协作',
    items: [
      { path: '/pm', icon: User, label: '需求看板', desc: '需求与验收' },
      { path: '/qa', icon: ChatDotRound, label: 'AI 问答', desc: '用自然语言提问' },
      { path: '/subscriptions', icon: Bell, label: '变更订阅', badge: '3', desc: '订阅感兴趣的变更' }
    ]
  },
  {
    title: '系统管理',
    items: [
      { path: '/integration', icon: DataLine, label: '接入管理', desc: '管理项目与 SDK' },
      { path: '/model-config', icon: Setting, label: '模型配置', desc: '模型与密钥管理' }
    ]
  },
  {
    title: '平台扩展',
    items: [
      { path: '/plugin-market', icon: Box, label: '插件市场', desc: '解析器插件安装', roles: ['ADMIN', 'PM'] },
      { path: '/gitlab-repos', icon: FolderOpened, label: 'GitLab 仓库', desc: '仓库导入与同步', roles: ['ADMIN', 'PM', 'OPS'] }
    ]
  },
  {
    title: '管理运营',
    items: [
      { path: '/metrics', icon: TrendCharts, label: '研效度量', desc: '交付/逃逸/吞吐指标', roles: ['ADMIN', 'PM', 'QA', 'OPS'] },
      { path: '/bugs', icon: Warning, label: '缺陷管理', desc: '状态流转与追溯', roles: ['ADMIN', 'PM', 'QA'] },
      { path: '/automation', icon: MagicStick, label: '自动化规则', desc: '事件触发引擎', roles: ['ADMIN', 'PM', 'OPS'] },
      { path: '/feedback', icon: ChatDotRound, label: '反馈管理', desc: 'AI 转需求缺陷', roles: ['ADMIN', 'PM'] },
      { path: '/members', icon: Lightning, label: '项目成员', desc: '角色权限管理', roles: ['ADMIN', 'PM'] }
    ]
  }
]

const pageTitle = computed(() => (route.meta.title as string) ?? 'EvoTrace')
const crumb = computed(() => {
  for (const g of visibleNavGroups.value) {
    const it = g.items.find((i) => i.path === route.path)
    if (it) return { group: g.title, page: it.label }
  }
  return { group: '工作台', page: pageTitle.value }
})
const userInitial = computed(() => username.charAt(0).toUpperCase())
const projectCount = computed(() => projects.value.length)

function isActive(path: string) {
  return route.path === path
}

function logout() {
  localStorage.removeItem('evotrace_token')
  localStorage.removeItem('evotrace_user')
  ElMessage.success('已退出登录')
  router.push('/login')
}

/* ---------- 命令面板 ---------- */
const paletteOpen = ref(false)
const paletteQuery = ref('')
const paletteSel = ref(0)
const paletteInput = ref<HTMLInputElement>()
const items = ref<{ icon: typeof DataAnalysis; label: string; desc: string; path?: string; action?: 'theme' | 'logout' }[]>([])
const visibleItems = computed(() => {
  const q = paletteQuery.value.trim().toLowerCase()
  return items.value.filter(
    (i) => !q || i.label.toLowerCase().includes(q) || i.desc.toLowerCase().includes(q)
  )
})

function buildItems() {
  const list: typeof items.value = []
  for (const g of visibleNavGroups.value) {
    for (const it of g.items) list.push({ icon: it.icon, label: it.label, desc: g.title, path: it.path })
  }
  list.push({ icon: Sunny, label: '切换主题', desc: '深色 / 浅色', action: 'theme' })
  list.push({ icon: SwitchButton, label: '退出登录', desc: '安全退出当前账号', action: 'logout' })
  items.value = list
}

function openPalette() {
  buildItems()
  paletteQuery.value = ''
  paletteSel.value = 0
  paletteOpen.value = true
  nextTick(() => paletteInput.value?.focus())
}
function closePalette() {
  paletteOpen.value = false
}
function runItem(item: (typeof items.value)[number]) {
  closePalette()
  if (item.path) {
    if (route.path !== item.path) router.push(item.path)
  } else if (item.action === 'theme') {
    toggleTheme()
  } else if (item.action === 'logout') {
    logout()
  }
}
function onPaletteKey(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    paletteOpen.value ? closePalette() : openPalette()
    return
  }
  if (e.key === 'Escape' && paletteOpen.value) closePalette()
  if (!paletteOpen.value) return
  const n = visibleItems.value.length
  if (e.key === 'ArrowDown') { e.preventDefault(); paletteSel.value = (paletteSel.value + 1) % n }
  else if (e.key === 'ArrowUp') { e.preventDefault(); paletteSel.value = (paletteSel.value - 1 + n) % n }
  else if (e.key === 'Enter') { e.preventDefault(); const it = visibleItems.value[paletteSel.value]; if (it) runItem(it) }
}
window.addEventListener('keydown', onPaletteKey)
</script>

<template>
  <!-- 氛围背景 -->
  <div class="et-aurora" aria-hidden="true">
    <div class="et-orb et-orb-1"></div>
    <div class="et-orb et-orb-2"></div>
    <div class="et-orb et-orb-3"></div>
    <div class="et-grid-overlay"></div>
  </div>

  <div class="layout">
    <!-- ======== 侧边栏 ======== -->
    <aside class="sidebar" :class="{ collapsed }">
      <div class="brand">
        <div class="brand-logo">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"
               stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 12h4l2.5-7 5 14 2.5-7h4" />
          </svg>
        </div>
        <div class="brand-text" v-show="!collapsed">
          <span class="brand-name">EvoTrace</span>
          <span class="brand-sub">全链路演化追踪平台</span>
        </div>
      </div>

      <nav class="nav">
        <div v-for="group in visibleNavGroups" :key="group.title" class="nav-group">
          <button
            class="nav-group-title"
            :class="{ collapsed: isGroupCollapsed(group.title) }"
            :title="collapsed ? group.title : undefined"
            @click="toggleGroup(group.title)"
          >
            <span v-if="!collapsed" class="gt-text">{{ group.title }}</span>
            <span v-else class="gt-dot"></span>
            <el-icon v-if="!collapsed" :size="12" class="gt-chev"><ArrowRight /></el-icon>
          </button>
          <div v-show="!isGroupCollapsed(group.title)" class="nav-group-body">
            <button
              v-for="item in group.items" :key="item.path"
              class="nav-item" :class="{ active: isActive(item.path) }"
              :title="item.label"
              @click="router.push(item.path)"
            >
              <span class="nav-ic"><el-icon :size="18"><component :is="item.icon" /></el-icon></span>
              <span v-if="!collapsed" class="nav-label">{{ item.label }}</span>
              <span v-if="item.badge && !collapsed" class="nav-badge">{{ item.badge }}</span>
            </button>
          </div>
        </div>
      </nav>

      <div class="sidebar-foot">
        <div class="sys-card">
          <div class="sys-top">
            <span class="et-pulse"></span>
            <span>平台服务运行中</span>
          </div>
          <div class="sys-meta"><span>已接入项目</span><b>{{ projectCount }} 个</b></div>
        </div>
        <button class="collapse-btn" @click="collapsed = !collapsed">
          <el-icon :size="16"><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
          <span v-if="!collapsed" class="label">收起侧边栏</span>
        </button>
      </div>
    </aside>

    <!-- ======== 主区域 ======== -->
    <div class="main-area">
      <header class="topbar">
        <div class="topbar-left">
          <div class="crumb">
            <span>EvoTrace</span><span class="sep">/</span><span class="cur">{{ crumb.group }}</span>
          </div>
          <h1 class="page-title">{{ pageTitle }}</h1>
        </div>

        <div class="topbar-right">
          <button class="search-box" @click="openPalette" title="全局搜索（⌘K）">
            <el-icon :size="15"><Search /></el-icon>
            <span class="ph">搜索项目、版本、需求、接口…</span>
            <kbd>⌘K</kbd>
          </button>

          <button class="icon-btn" @click="toggleTheme" :title="isDark ? '切换浅色模式' : '切换深色模式'">
            <el-icon :size="17"><Sunny v-if="isDark" /><Moon v-else /></el-icon>
          </button>

          <button class="icon-btn" @click="router.push('/subscriptions')" title="变更订阅">
            <el-icon :size="17"><Bell /></el-icon>
            <span class="dot"></span>
          </button>

          <div class="divider"></div>

          <el-select v-model="currentProject" class="project-select" size="default"
                     placeholder="选择项目" filterable>
            <el-option v-for="p in projects" :key="p.projectKey" :value="p.projectKey">
              <span class="proj-option">
                <span class="proj-opt-name">{{ p.name }}</span>
                <span class="proj-opt-key">{{ p.projectKey }}</span>
              </span>
            </el-option>
          </el-select>

          <el-dropdown trigger="click">
            <div class="user-trigger">
              <div class="avatar">{{ userInitial }}</div>
              <span class="user-name">{{ username }}</span>
              <el-icon :size="12" class="chev"><ArrowRight /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="toggleTheme">
                  <el-icon><Sunny v-if="isDark" /><Moon v-else /></el-icon>
                  {{ isDark ? '切换浅色模式' : '切换深色模式' }}
                </el-dropdown-item>
                <el-dropdown-item divided @click="logout">
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="main-content">
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>

  <!-- ======== 命令面板 ======== -->
  <Teleport to="body">
    <div class="palette-mask" :class="{ open: paletteOpen }" @click="closePalette"></div>
    <div class="palette" :class="{ open: paletteOpen }" role="dialog" aria-label="全局搜索">
      <div class="p-input">
        <el-icon :size="18" class="p-search-ic"><Search /></el-icon>
        <input ref="paletteInput" v-model="paletteQuery"
               placeholder="搜索页面、命令…" autocomplete="off" spellcheck="false" />
        <kbd>ESC</kbd>
      </div>
      <div class="p-list">
        <template v-if="visibleItems.length">
          <button v-for="(item, i) in visibleItems" :key="item.label + item.path"
                  class="p-item" :class="{ sel: i === paletteSel }"
                  @mouseenter="paletteSel = i" @click="runItem(item)">
            <span class="p-ic"><el-icon :size="15"><component :is="item.icon" /></el-icon></span>
            <span class="label">{{ item.label }}</span>
            <span class="desc">{{ item.desc }}</span>
            <span class="kb">↩</span>
          </button>
        </template>
        <div v-else class="p-empty">未找到「{{ paletteQuery }}」相关内容</div>
      </div>
      <div class="p-foot">
        <span><kbd>↑↓</kbd> 选择</span>
        <span><kbd>Enter</kbd> 执行</span>
        <span><kbd>Esc</kbd> 关闭</span>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.layout {
  position: relative;
  z-index: 1;
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* ======== 侧边栏 ======== */
.sidebar {
  width: var(--et-sidebar-width);
  display: flex;
  flex-direction: column;
  padding: 18px 14px;
  background: var(--et-sidebar-bg);
  border-right: 1px solid var(--et-border);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  transition: width 0.3s cubic-bezier(0.4, 0, 0.2, 1), background 0.4s;
  overflow: hidden;
  flex-shrink: 0;
}
.sidebar.collapsed { width: var(--et-sidebar-collapsed); }

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 8px 18px;
  border-bottom: 1px solid var(--et-border);
}
.brand-logo {
  width: 38px;
  height: 38px;
  border-radius: 12px;
  flex-shrink: 0;
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-b) 55%, var(--et-grad-c));
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  box-shadow: 0 6px 18px var(--et-glow);
}
.brand-logo svg { width: 20px; height: 20px; }
.brand-text { display: flex; flex-direction: column; gap: 1px; white-space: nowrap; }
.brand-name { font-size: 17px; font-weight: 700; letter-spacing: 0.4px; }
.brand-sub { font-size: 10.5px; color: var(--et-text-muted); letter-spacing: 0.6px; }

.nav { flex: 1; overflow-y: auto; overflow-x: hidden; margin-top: 14px; }
.nav-group { margin-bottom: 14px; }
.nav-group-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.5px;
  color: var(--et-text-secondary);
  padding: 6px 12px 8px;
  white-space: nowrap;
  background: none;
  border: none;
  cursor: pointer;
  font-family: inherit;
  transition: color 0.15s;
}
.nav-group-title:hover { color: var(--et-text); }
.nav-group-title .gt-chev {
  transition: transform 0.2s;
  color: var(--et-text-muted);
  opacity: 0.7;
}
.nav-group-title:not(.collapsed) .gt-chev { transform: rotate(90deg); }
.nav-group-title .gt-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin: 0 auto;
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-c));
  box-shadow: 0 0 6px var(--et-glow);
  flex-shrink: 0;
}
.nav-group-body { overflow: hidden; }
.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 10px 12px;
  margin: 2px 0;
  border-radius: 12px;
  background: none;
  border: none;
  color: var(--et-sidebar-text);
  font-size: 13.5px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.18s, color 0.18s;
  white-space: nowrap;
  text-align: left;
}
.nav-item::before {
  content: '';
  position: absolute;
  left: 0;
  top: 22%;
  bottom: 22%;
  width: 3px;
  border-radius: 3px;
  background: linear-gradient(180deg, var(--et-grad-a), var(--et-grad-c));
  opacity: 0;
  transition: opacity 0.2s;
}
.nav-item:hover { background: var(--et-sidebar-hover); color: var(--et-sidebar-text-active); }
.nav-item.active {
  background: linear-gradient(90deg, var(--et-sidebar-active), transparent);
  color: var(--et-sidebar-text-active);
  font-weight: 600;
}
.nav-item.active::before { opacity: 1; }
.nav-item.active .nav-ic { color: var(--et-grad-c); filter: drop-shadow(0 0 6px rgba(56, 225, 255, 0.5)); }
.nav-ic {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--et-text-muted);
  width: 22px;
  flex-shrink: 0;
  transition: color 0.18s;
}
.nav-item:hover .nav-ic { color: var(--et-grad-a); }
.nav-label { flex: 1; }
.nav-badge {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 20px;
  background: linear-gradient(135deg, var(--et-grad-b), var(--et-grad-c));
  color: #0b0f1e;
  flex-shrink: 0;
}

.sidebar-foot { margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--et-border); }
.sys-card {
  border-radius: 14px;
  padding: 12px;
  margin-bottom: 12px;
  background: linear-gradient(135deg, rgba(52, 211, 153, 0.12), rgba(56, 225, 255, 0.06));
  border: 1px solid rgba(52, 211, 153, 0.22);
  white-space: nowrap;
  overflow: hidden;
}
.sys-top { display: flex; align-items: center; gap: 8px; font-size: 12.5px; font-weight: 600; }
.sys-meta {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--et-text-muted);
  margin-top: 9px;
}
.sys-meta b { color: var(--et-text-secondary); }
.collapse-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 9px;
  border-radius: 12px;
  background: var(--et-bg-muted);
  border: 1px solid transparent;
  color: var(--et-text-muted);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition: color 0.18s, background 0.18s;
}
.collapse-btn:hover { color: var(--et-text); background: var(--et-sidebar-hover); }
.collapse-btn .label { white-space: nowrap; }

/* ======== 主区域 ======== */
.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.topbar {
  height: var(--et-header-height);
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 28px;
  flex-shrink: 0;
  background: var(--et-sidebar-bg);
  border-bottom: 1px solid var(--et-border);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
}
.crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--et-text-muted);
}
.crumb .sep { opacity: 0.5; }
.crumb .cur { color: var(--et-text-secondary); }
.page-title { margin: 0; font-size: 17px; font-weight: 700; margin-top: 2px; }

.topbar-right { margin-left: auto; display: flex; align-items: center; gap: 10px; }

.search-box {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 240px;
  padding: 8px 12px;
  border-radius: 12px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  color: var(--et-text-muted);
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s, width 0.25s;
}
.search-box:hover {
  border-color: var(--et-primary);
  box-shadow: 0 0 0 3px rgba(109, 124, 255, 0.15);
}
.search-box .ph { flex: 1; text-align: left; white-space: nowrap; overflow: hidden; }

kbd {
  font-family: inherit;
  font-size: 10.5px;
  padding: 2px 6px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.07);
  border: 1px solid var(--et-border);
  color: var(--et-text-secondary);
}
[data-theme="light"] kbd { background: rgba(15, 23, 42, 0.05); }

.icon-btn {
  position: relative;
  width: 38px;
  height: 38px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--et-text-secondary);
  background: var(--et-bg-muted);
  border: 1px solid transparent;
  cursor: pointer;
  transition: all 0.18s;
}
.icon-btn:hover {
  color: var(--et-text);
  border-color: var(--et-hover-border);
  box-shadow: 0 4px 14px rgba(2, 6, 23, 0.25);
  transform: translateY(-1px);
}
.icon-btn .dot {
  position: absolute;
  top: 9px;
  right: 9px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--et-danger);
  border: 2px solid var(--et-card-solid);
}

.divider { width: 1px; height: 24px; background: var(--et-border); margin: 0 4px; }

.project-select { width: 150px; }
.proj-option { display: flex; flex-direction: column; line-height: 1.35; }
.proj-opt-name { font-weight: 600; }
.proj-opt-key { font-size: 11px; color: var(--et-text-muted); }

.user-trigger {
  display: flex;
  align-items: center;
  gap: 9px;
  cursor: pointer;
  padding: 4px 8px 4px 4px;
  border-radius: 12px;
  transition: background 0.15s;
}
.user-trigger:hover { background: var(--et-sidebar-hover); }
.avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-b) 60%, var(--et-grad-c));
  color: #fff;
  font-size: 13.5px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.user-name { font-size: 13.5px; font-weight: 600; }
.chev { color: var(--et-text-muted); transform: rotate(90deg); }

.main-content {
  flex: 1;
  overflow-y: auto;
  padding: 24px 28px 20px;
  max-width: 1460px;
  width: 100%;
  margin: 0 auto;
}

/* ======== 页面切换 ======== */
.page-enter-active, .page-leave-active { transition: opacity 0.18s ease, transform 0.18s ease; }
.page-enter-from { opacity: 0; transform: translateY(8px); }
.page-leave-to { opacity: 0; }

/* ======== 命令面板 ======== */
.palette-mask {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: rgba(4, 8, 18, 0.55);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.2s;
}
.palette-mask.open { opacity: 1; pointer-events: auto; }
[data-theme="light"] .palette-mask { background: rgba(30, 41, 66, 0.3); }

.palette {
  position: fixed;
  top: 12%;
  left: 50%;
  transform: translate(-50%, -14px) scale(0.98);
  z-index: 2001;
  width: min(620px, calc(100vw - 40px));
  background: var(--et-card-solid);
  border: 1px solid var(--et-hover-border);
  border-radius: 18px;
  box-shadow: 0 30px 80px rgba(2, 6, 23, 0.6);
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.2s, transform 0.2s;
}
.palette.open { opacity: 1; transform: translate(-50%, 0); pointer-events: auto; }

.p-input {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 16px 18px;
  border-bottom: 1px solid var(--et-border);
}
.p-search-ic { color: var(--et-text-muted); }
.p-input input {
  flex: 1;
  border: none;
  outline: none;
  background: none;
  color: var(--et-text);
  font-size: 15px;
  font-family: inherit;
}
.p-input input::placeholder { color: var(--et-text-muted); }

.p-list { max-height: 380px; overflow-y: auto; padding: 8px; }
.p-item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 10px 12px;
  border-radius: 11px;
  background: none;
  border: none;
  font-size: 13.5px;
  font-family: inherit;
  color: var(--et-text-secondary);
  text-align: left;
  cursor: pointer;
  transition: all 0.13s;
}
.p-ic {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(109, 124, 255, 0.12);
  color: var(--et-primary-light);
  flex-shrink: 0;
}
.p-item .label { flex: 1; font-weight: 600; color: inherit; }
.p-item .desc { font-size: 11.5px; color: var(--et-text-muted); }
.p-item .kb { font-size: 10.5px; color: var(--et-text-muted); border: 1px solid var(--et-border); padding: 1px 6px; border-radius: 5px; }
.p-item.sel {
  background: linear-gradient(90deg, rgba(109, 124, 255, 0.18), rgba(109, 124, 255, 0.06));
  color: var(--et-text);
}
.p-item.sel .p-ic {
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-b));
  color: #fff;
  box-shadow: 0 4px 12px var(--et-glow);
}
.p-empty { padding: 26px; text-align: center; color: var(--et-text-muted); font-size: 13px; }

.p-foot {
  display: flex;
  gap: 14px;
  padding: 11px 18px;
  border-top: 1px solid var(--et-border);
  font-size: 11px;
  color: var(--et-text-muted);
}
.p-foot span { display: inline-flex; align-items: center; gap: 5px; }
</style>
