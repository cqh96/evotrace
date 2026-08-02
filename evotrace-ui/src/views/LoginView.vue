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
    <div class="login-left">
      <canvas ref="canvasRef" class="particles" />
      <div class="hero">
        <div class="hero-logo">E</div>
        <h1>EvoTrace</h1>
        <p class="hero-tagline">系统演化追踪与智能分析平台</p>
        <ul class="hero-features">
          <li>需求 → 代码 → 测试 → 发布全链路打通</li>
          <li>AI 驱动的变更摘要与影响面分析</li>
          <li>版本对比 · 风险评分 · 质量门禁 · 热点分析</li>
        </ul>
      </div>
    </div>

    <div class="login-right">
      <div class="login-form-wrap">
        <h2>欢迎回来</h2>
        <p class="login-subtitle">登录以访问 EvoTrace 控制台</p>

        <el-form class="login-form" @submit.prevent="login">
          <el-form-item>
            <el-input v-model="form.username" placeholder="用户名" size="large">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item>
            <el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password @keyup.enter="login">
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="login">登 录</el-button>
        </el-form>

        <div class="login-hint">
          <el-icon><InfoFilled /></el-icon>
          开发环境默认账号：admin / admin123
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page { min-height: 100vh; display: flex }

.login-left {
  flex: 1; background: linear-gradient(145deg, #0f172a 0%, #1e1b4b 50%, #312e81 100%);
  display: flex; align-items: center; justify-content: center; padding: 48px;
  position: relative; overflow: hidden;
}

.particles { position: absolute; inset: 0; width: 100%; height: 100% }

.login-left::before {
  content: ''; position: absolute; inset: 0;
  background: radial-gradient(circle at 20% 80%, rgba(99,102,241,0.15) 0%, transparent 50%),
              radial-gradient(circle at 80% 20%, rgba(139,92,246,0.12) 0%, transparent 50%);
}

.hero { position: relative; max-width: 420px; color: #f1f5f9 }
.hero-logo {
  width: 56px; height: 56px; border-radius: 14px;
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
  color: #fff; font-size: 28px; font-weight: 800;
  display: flex; align-items: center; justify-content: center; margin-bottom: 24px;
  box-shadow: 0 8px 32px rgba(99,102,241,0.3);
}

.hero h1 { font-size: 36px; font-weight: 800; margin: 0 0 12px; letter-spacing: -0.5px }
.hero-tagline { font-size: 16px; color: #94a3b8; margin: 0 0 32px; line-height: 1.6 }

.hero-features { list-style: none; padding: 0; margin: 0 }
.hero-features li { padding: 10px 0 10px 24px; position: relative; color: #cbd5e1; font-size: 14px; line-height: 1.5 }
.hero-features li::before { content: '✓'; position: absolute; left: 0; color: #818cf8; font-weight: 700 }

.login-right { width: 480px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; background: var(--et-card-bg); padding: 48px; transition: background 0.3s }

.login-form-wrap { width: 100%; max-width: 360px }
.login-form-wrap h2 { margin: 0 0 8px; font-size: 24px; font-weight: 700; color: var(--et-text) }
.login-subtitle { margin: 0 0 32px; color: var(--et-text-secondary); font-size: 14px }

.login-form :deep(.el-input__wrapper) { border-radius: 10px; padding: 4px 12px }
.login-btn { width: 100%; height: 44px; border-radius: 10px; font-size: 15px; font-weight: 600; margin-top: 8px }

.login-hint { display: flex; align-items: center; gap: 6px; margin-top: 24px; padding: 12px 14px; background: var(--et-page-bg); border-radius: 8px; color: var(--et-text-muted); font-size: 12px }

@media (max-width: 900px) { .login-left { display: none } .login-right { width: 100% } }
</style>
