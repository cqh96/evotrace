<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { DataAnalysis, Timer, Switch, ChatDotRound, Connection, TrendCharts, Bell, User, Monitor } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const username = localStorage.getItem('evotrace_user') ?? 'admin'

function logout() {
  localStorage.removeItem('evotrace_token')
  localStorage.removeItem('evotrace_user')
  router.push('/login')
}
</script>

<template>
  <el-container style="height: 100vh">
    <el-aside width="220px" style="background: #001529">
      <div class="logo">EvoTrace</div>
      <el-menu :default-active="route.path" router dark background-color="#001529" text-color="#a6adb4" active-text-color="#fff">
        <el-menu-item index="/dashboard"><el-icon><DataAnalysis /></el-icon>项目总览</el-menu-item>
        <el-menu-item index="/timeline"><el-icon><Timer /></el-icon>演化时间线</el-menu-item>
        <el-menu-item index="/compare"><el-icon><Switch /></el-icon>版本对比</el-menu-item>
        <el-menu-item index="/qa"><el-icon><ChatDotRound /></el-icon>AI 演化问答</el-menu-item>
        <el-menu-item index="/integration"><el-icon><Connection /></el-icon>接入管理</el-menu-item>
        <el-menu-item index="/analysis"><el-icon><TrendCharts /></el-icon>智能分析</el-menu-item>
        <el-menu-item index="/subscriptions"><el-icon><Bell /></el-icon>变更订阅</el-menu-item>
        <el-menu-item-group title="PM / QA">
          <el-menu-item index="/pm"><el-icon><User /></el-icon>PM 需求看板</el-menu-item>
          <el-menu-item index="/qa"><el-icon><Monitor /></el-icon>QA 测试面板</el-menu-item>
        </el-menu-item-group>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="page-title">{{ route.meta.title }}</span>
        <div style="margin-left: auto; display: flex; align-items: center; gap: 12px">
          <el-tag size="small" type="info">工作空间: default</el-tag>
          <el-dropdown>
            <span style="cursor: pointer">{{ username }}</span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main style="background: #f5f7fa"><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.logo { color: #fff; font-size: 20px; font-weight: 700; padding: 18px 20px; letter-spacing: 1px }
.header { display: flex; align-items: center; gap: 12px; border-bottom: 1px solid #e8e8e8; background: #fff }
.page-title { font-size: 16px; font-weight: 600 }
:deep(.el-menu) { border-right: none }
</style>
