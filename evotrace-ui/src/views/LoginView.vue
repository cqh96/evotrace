<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { InfoFilled, User, Lock } from '@element-plus/icons-vue'
import { authApi } from '../api'

const router = useRouter()
const form = ref({ username: 'admin', password: '' })
const loading = ref(false)
const canvasRef = ref<HTMLCanvasElement>()

let animId = 0
function initParticles() {
  const c = canvasRef.value; if (!c) return
  const ctx = c.getContext('2d')!; c.width = c.offsetWidth; c.height = c.offsetHeight
  const particles: { x: number; y: number; vx: number; vy: number; r: number; alpha: number }[] = []
  for (let i = 0; i < 60; i++) {
    particles.push({ x: Math.random() * c.width, y: Math.random() * c.height,
      vx: (Math.random() - 0.5) * 0.3, vy: (Math.random() - 0.5) * 0.3, r: Math.random() * 1.5 + 0.5,
      alpha: Math.random() * 0.4 + 0.1 })
  }
  function draw() {
    ctx.clearRect(0, 0, c!.width, c!.height)
    for (const p of particles) {
      p.x += p.vx; p.y += p.vy
      if (p.x < 0 || p.x > c!.width) p.vx *= -1
      if (p.y < 0 || p.y > c!.height) p.vy *= -1
      ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2)
      ctx.fillStyle = `rgba(129,140,248,${p.alpha})`; ctx.fill()
    }
    // Draw connections
    for (let i = 0; i < particles.length; i++) {
      for (let j = i + 1; j < particles.length; j++) {
        const dx = particles[i].x - particles[j].x; const dy = particles[i].y - particles[j].y
        const dist = Math.sqrt(dx * dx + dy * dy)
        if (dist < 100) {
          ctx.beginPath(); ctx.moveTo(particles[i].x, particles[i].y); ctx.lineTo(particles[j].x, particles[j].y)
          ctx.strokeStyle = `rgba(129,140,248,${0.06 * (1 - dist / 100)})`; ctx.lineWidth = 0.5; ctx.stroke()
        }
      }
    }
    animId = requestAnimationFrame(draw)
  }
  draw()
}

async function login() {
  loading.value = true
  try {
    const resp = await authApi.login(form.value.username, form.value.password)
    localStorage.setItem('evotrace_token', resp.token)
    localStorage.setItem('evotrace_user', resp.displayName)
    router.push('/dashboard')
  } catch {} finally { loading.value = false }
}

onMounted(initParticles)
onUnmounted(() => cancelAnimationFrame(animId))
</script>

<template>
  <div class="login-page">
    <!-- 氛围背景 -->
    <div class="et-aurora" aria-hidden="true">
      <div class="et-orb et-orb-1"></div>
      <div class="et-orb et-orb-2"></div>
      <div class="et-orb et-orb-3"></div>
      <div class="et-grid-overlay"></div>
    </div>

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
          <h1 class="brand-name et-grad-text">EvoTrace</h1>
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

/* ======== 玻璃卡片：22px 大圆角 + 渐变描边 + 辉光 ======== */
.login-card {
  position: relative;
  border-radius: 22px;
  padding: 44px 40px 26px;
  background: var(--et-card-bg);
  border: 1px solid transparent;
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  box-shadow: 0 24px 70px rgba(2, 6, 23, 0.45), 0 0 80px rgba(109, 124, 255, 0.08);
  transition: box-shadow 0.25s, border-color 0.25s;
}
.login-card:hover {
  box-shadow: 0 30px 80px rgba(2, 6, 23, 0.5), 0 0 100px rgba(109, 124, 255, 0.14);
}
.login-card::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: 22px;
  padding: 1px;
  background: linear-gradient(160deg, rgba(109, 124, 255, 0.65), rgba(255, 255, 255, 0.1) 35%, rgba(56, 225, 255, 0.5));
  -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  -webkit-mask-composite: xor;
          mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
          mask-composite: exclude;
  pointer-events: none;
}
[data-theme="light"] .login-card {
  box-shadow: 0 24px 60px rgba(63, 78, 158, 0.18), 0 0 60px rgba(91, 107, 255, 0.1);
}
[data-theme="light"] .login-card::before {
  background: linear-gradient(160deg, rgba(91, 107, 255, 0.55), rgba(255, 255, 255, 0.75) 40%, rgba(56, 225, 255, 0.55));
}

/* ======== 品牌区 ======== */
.brand { text-align: center; margin-bottom: 30px; }
.brand-logo {
  width: 58px;
  height: 58px;
  margin: 0 auto 18px;
  border-radius: 17px;
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-b) 55%, var(--et-grad-c));
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  box-shadow: 0 12px 30px var(--et-glow), inset 0 1px 0 rgba(255, 255, 255, 0.25);
}
.brand-logo svg { width: 30px; height: 30px; filter: drop-shadow(0 1px 3px rgba(2, 6, 23, 0.3)); }
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
