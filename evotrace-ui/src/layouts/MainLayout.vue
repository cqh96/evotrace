<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import {
  DataAnalysis, Timer, Switch, ChatDotRound, Connection, TrendCharts, Bell,
  User, Monitor, Fold, Expand, SwitchButton, Sunny, Moon, Checked, Setting
} from '@element-plus/icons-vue'
import { projectApi, type Project } from '../api'
import { useProjectStore } from '../stores/project'

const route = useRoute()
const router = useRouter()
const collapsed = ref(false)
const username = localStorage.getItem('evotrace_user') ?? 'admin'
const isDark = ref(localStorage.getItem('evotrace_theme') === 'dark')

// 全局项目选择
const projectStore = useProjectStore()
const { current: currentProject } = storeToRefs(projectStore)
const projects = ref<Project[]>([])

async function loadProjects() {
  try { projects.value = await projectApi.list() } catch { projects.value = [] }
}
onMounted(loadProjects)

function toggleTheme() {
  isDark.value = !isDark.value
  const theme = isDark.value ? 'dark' : 'light'
  document.documentElement.setAttribute('data-theme', theme)
  localStorage.setItem('evotrace_theme', theme)
}

// Init theme on mount
if (isDark.value) {
  document.documentElement.setAttribute('data-theme', 'dark')
}

const navGroups = [
  {
    title: '概览',
    items: [
      { path: '/dashboard', icon: DataAnalysis, label: '项目总览' }
    ]
  },
  {
    title: '演化追踪',
    items: [
      { path: '/timeline', icon: Timer, label: '演化时间线' },
      { path: '/compare', icon: Switch, label: '版本对比' },
      { path: '/analysis', icon: TrendCharts, label: '智能分析' },
      { path: '/code-review', icon: Checked, label: 'AI 代码审查' }
    ]
  },
  {
    title: '协作',
    items: [
      { path: '/qa', icon: ChatDotRound, label: 'AI 演化问答' },
      { path: '/subscriptions', icon: Bell, label: '变更订阅' }
    ]
  },
  {
    title: '管理',
    items: [
      { path: '/integration', icon: Connection, label: '接入管理' },
      { path: '/model-config', icon: Setting, label: 'AI 模型配置' }
    ]
  },
  {
    title: 'PM / QA',
    items: [
      { path: '/pm', icon: User, label: 'PM 需求看板' },
      { path: '/qa-dashboard', icon: Monitor, label: 'QA 测试面板' }
    ]
  }
]

const pageTitle = computed(() => (route.meta.title as string) ?? 'EvoTrace')
const userInitial = computed(() => username.charAt(0).toUpperCase())

function isActive(path: string) {
  return route.path === path
}

function logout() {
  localStorage.removeItem('evotrace_token')
  localStorage.removeItem('evotrace_user')
  router.push('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? '64px' : '240px'" class="sidebar">
      <div class="sidebar-brand">
        <div class="brand-icon">E</div>
        <transition name="fade">
          <span v-if="!collapsed" class="brand-text">EvoTrace</span>
        </transition>
      </div>

      <nav class="sidebar-nav">
        <div v-for="group in navGroups" :key="group.title" class="nav-group">
          <div v-if="!collapsed" class="nav-group-title">{{ group.title }}</div>
          <router-link
            v-for="item in group.items"
            :key="item.path"
            :to="item.path"
            class="nav-item"
            :class="{ active: isActive(item.path) }"
            :title="collapsed ? item.label : undefined"
          >
            <el-icon :size="18"><component :is="item.icon" /></el-icon>
            <span v-if="!collapsed" class="nav-label">{{ item.label }}</span>
          </router-link>
        </div>
      </nav>

      <div class="sidebar-footer">
        <button class="collapse-btn" @click="collapsed = !collapsed">
          <el-icon :size="16"><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
        </button>
      </div>
    </el-aside>

    <el-container class="main-area">
      <el-header class="header">
        <div class="header-left">
          <h1 class="page-title">{{ pageTitle }}</h1>
        </div>
        <div class="header-right">
          <button class="theme-btn" @click="toggleTheme" :title="isDark ? '切换浅色模式' : '切换深色模式'">
            <el-icon :size="18"><Sunny v-if="isDark" /><Moon v-else /></el-icon>
          </button>
          <el-select v-model="currentProject" size="small" class="workspace-tag" style="width: 160px"
                     placeholder="选择项目">
            <el-option v-for="p in projects" :key="p.projectKey" :label="p.name + ' (' + p.projectKey + ')'" :value="p.projectKey" />
          </el-select>
          <el-dropdown trigger="click">
            <div class="user-trigger">
              <div class="user-avatar">{{ userInitial }}</div>
              <span v-if="!collapsed" class="user-name">{{ username }}</span>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="logout">
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main-content">
        <div class="page-container">
          <router-view v-slot="{ Component }">
            <transition name="page" mode="out-in">
              <component :is="Component" />
            </transition>
          </router-view>
        </div>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
  overflow: hidden;
}

.sidebar {
  background: var(--et-sidebar-bg);
  display: flex;
  flex-direction: column;
  transition: width 0.25s ease;
  overflow: hidden;
  border-right: 1px solid rgba(255, 255, 255, 0.06);
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.brand-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--et-primary) 0%, #8b5cf6 100%);
  color: #fff;
  font-weight: 800;
  font-size: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.brand-text {
  color: #f1f5f9;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.sidebar-nav {
  flex: 1;
  overflow-y: auto;
  padding: 12px 8px;
}

.nav-group {
  margin-bottom: 8px;
}

.nav-group-title {
  padding: 8px 12px 4px;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: #475569;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  margin: 2px 0;
  border-radius: 8px;
  color: var(--et-sidebar-text);
  text-decoration: none;
  font-size: 14px;
  transition: background 0.15s, color 0.15s;
  white-space: nowrap;
}

.nav-item:hover {
  background: var(--et-sidebar-hover);
  color: var(--et-sidebar-text-active);
}

.nav-item.active {
  background: var(--et-sidebar-active);
  color: var(--et-sidebar-text-active);
  font-weight: 500;
}

.nav-item.active .el-icon {
  color: var(--et-primary-light);
}

.sidebar-footer {
  padding: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.collapse-btn {
  width: 100%;
  padding: 8px;
  border: none;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);
  color: var(--et-sidebar-text);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s;
}

.collapse-btn:hover {
  background: rgba(255, 255, 255, 0.08);
  color: var(--et-sidebar-text-active);
}

.main-area {
  overflow: hidden;
}

.header {
  height: var(--et-header-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background: var(--et-card-bg);
  border-bottom: 1px solid var(--et-border);
  box-shadow: var(--et-shadow-sm);
}

.page-title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--et-text);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.theme-btn {
  width: 34px; height: 34px; border: none; border-radius: 8px;
  background: var(--et-page-bg); color: var(--et-text-secondary);
  cursor: pointer; display: flex; align-items: center; justify-content: center;
  transition: background 0.15s, color 0.15s;
}

.theme-btn:hover { background: var(--et-border); color: var(--et-text) }

.workspace-tag {
  border-color: var(--et-border);
  color: var(--et-text-secondary);
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px 4px 4px;
  border-radius: 8px;
  transition: background 0.15s;
}

.user-trigger:hover {
  background: var(--et-page-bg);
}

.user-avatar {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, var(--et-primary) 0%, #8b5cf6 100%);
  color: #fff;
  font-weight: 600;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.user-name {
  font-size: 14px;
  color: var(--et-text);
  font-weight: 500;
}

.main-content {
  background: var(--et-page-bg);
  padding: 20px 24px;
  overflow-y: auto;
}

.page-enter-active,
.page-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}

.page-enter-from {
  opacity: 0;
  transform: translateY(6px);
}

.page-leave-to {
  opacity: 0;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
