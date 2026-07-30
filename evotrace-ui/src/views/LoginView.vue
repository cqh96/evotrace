<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { authApi } from '../api'

const router = useRouter()
const form = ref({ username: 'admin', password: '' })
const loading = ref(false)

async function login() {
  loading.value = true
  try {
    const resp = await authApi.login(form.value.username, form.value.password)
    localStorage.setItem('evotrace_token', resp.token)
    localStorage.setItem('evotrace_user', resp.displayName)
    router.push('/dashboard')
  } catch {
    // 错误提示已由拦截器处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="brand">EvoTrace</div>
      <div class="slogan">系统演化追踪与智能分析平台</div>
      <el-form style="margin-top: 24px" @submit.prevent="login">
        <el-form-item><el-input v-model="form.username" placeholder="用户名" size="large" /></el-form-item>
        <el-form-item><el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password @keyup.enter="login" /></el-form-item>
        <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="login">登 录</el-button>
      </el-form>
      <div class="hint">开发环境默认账号：admin / admin123</div>
    </el-card>
  </div>
</template>

<style scoped>
.login-page { height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #001529 0%, #003a70 100%) }
.login-card { width: 400px; padding: 12px 8px }
.brand { font-size: 28px; font-weight: 700; text-align: center; color: #303133 }
.slogan { text-align: center; color: #909399; margin-top: 6px }
.hint { text-align: center; color: #c0c4cc; font-size: 12px; margin-top: 16px }
</style>
