<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { InfoFilled, User, Lock } from '@element-plus/icons-vue'
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
  } catch {} finally { loading.value = false }
}
</script>

<template>
  <div class="login-page">
    <!-- 登录卡片 -->
    <div class="login-stage">
      <div class="login-card rise">
        <!-- 品牌区 -->
        <div class="brand rise" style="--d: 0.08s">
          <div class="brand-logo">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"
                 stroke-linecap="round" stroke-linejoin="round">
              <path d="M3 12h4l2.5-7 5 14 2.5-7h4" />
            </svg>
          </div>
          <h1 class="brand-name">EvoTrace</h1>
          <p class="brand-sub">全链路演化追踪平台</p>
        </div>

        <!-- 表单 -->
        <el-form class="login-form rise" style="--d: 0.16s" @submit.prevent="login">
          <el-form-item>
            <el-input v-model="form.username" placeholder="用户名" size="large">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item>
            <el-input v-model="form.password" type="password" placeholder="密码" size="large"
                      show-password @keyup.enter="login">
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="login">登 录</el-button>
        </el-form>

        <!-- 环境提示 -->
        <div class="login-hint rise" style="--d: 0.24s">
          <el-icon><InfoFilled /></el-icon>
          <span>开发环境默认账号：admin / admin123</span>
        </div>

        <!-- 页脚 -->
        <div class="login-foot rise" style="--d: 0.32s">
          © 2026 EvoTrace · v2.0 · 全链路演化追踪与智能分析平台
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 20px;
  overflow: hidden;
}

.login-stage {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 404px;
}

/* ======== 卡片：纯白 + 细边框 ======== */
.login-card {
  position: relative;
  border-radius: 22px;
  padding: 44px 40px 26px;
  background: var(--et-card-bg);
  border: 1px solid var(--et-border);
  box-shadow: var(--et-shadow-md);
  transition: box-shadow 0.25s, border-color 0.25s;
}
.login-card:hover {
  border-color: var(--et-hover-border);
}

/* ======== 品牌区 ======== */
.brand { text-align: center; margin-bottom: 30px; }
.brand-logo {
  width: 58px;
  height: 58px;
  margin: 0 auto 18px;
  border-radius: 17px;
  background: var(--et-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}
.brand-logo svg { width: 30px; height: 30px; }
.brand-name { margin: 0; font-size: 27px; font-weight: 800; letter-spacing: 0.5px; }
.brand-sub { margin: 9px 0 0; font-size: 12px; color: var(--et-text-muted); letter-spacing: 2.5px; }

/* ======== 表单 ======== */
.login-form :deep(.el-input__wrapper) { padding: 4px 14px; }
.login-form .el-form-item { margin-bottom: 18px; }
.login-btn {
  width: 100%;
  height: 46px;
  margin-top: 4px;
  border-radius: 13px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 6px;
}

/* ======== 环境提示 ======== */
.login-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  margin-top: 22px;
  padding: 10px 14px;
  border-radius: 12px;
  background: var(--et-primary-bg);
  border: 1px solid rgba(109, 124, 255, 0.2);
  color: var(--et-text-secondary);
  font-size: 12px;
}
.login-hint .el-icon { color: var(--et-primary-light); flex-shrink: 0; }

/* ======== 页脚 ======== */
.login-foot {
  margin-top: 26px;
  padding-top: 18px;
  border-top: 1px solid var(--et-border);
  text-align: center;
  font-size: 11.5px;
  color: var(--et-text-muted);
  letter-spacing: 0.5px;
}

@media (max-width: 480px) {
  .login-card { padding: 36px 24px 22px; }
}
</style>
