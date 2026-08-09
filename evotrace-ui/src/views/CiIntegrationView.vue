<template>
  <div class="ci-page">
    <!-- Hero -->
    <section class="et-hero ci-hero rise">
      <div class="hero-left">
        <h2>CI/CD 集成</h2>
        <div class="et-hero-sub">通过 Jenkins / GitHub Actions 等 CI 工具触发测试计划执行，让质量门禁融入流水线</div>
        <div class="hero-chips">
          <span class="chip-mini">触发配置 <b>{{ triggers.length }}</b></span>
          <span class="chip-mini">启用中 <b>{{ enabledCount }}</b></span>
          <span class="chip-mini live"><span class="et-pulse"></span>Webhook 就绪</span>
        </div>
      </div>
      <div class="hero-right">
        <el-button type="primary" :icon="Plus" @click="openCreate">新建触发配置</el-button>
      </div>
    </section>

    <!-- CI Token 输入 -->
    <section class="et-card rise token-card" style="--d: 0.05s">
      <div class="et-card-head">
        <span class="et-tic"><el-icon :size="15"><Key /></el-icon></span>
        <div>
          <div class="et-card-title">CI Token</div>
          <div class="et-card-sub">用于 HTTP 触发执行的鉴权凭证，请与 evotrace.ci.token 保持一致</div>
        </div>
        <div class="right">
          <el-input
            v-model="ciToken"
            placeholder="evotrace.ci.token 配置值，留空使用默认"
            class="token-input"
            clearable
            size="default"
          />
        </div>
      </div>
    </section>

    <!-- 运行结果 -->
    <section v-if="result" class="et-card rise result-card" style="--d: 0.08s">
      <div class="et-card-head">
        <span class="et-tic"><el-icon :size="15"><CircleCheck /></el-icon></span>
        <div>
          <div class="et-card-title">执行结果</div>
          <div class="et-card-sub">{{ result.planName }} · 通过率 {{ passRate }}%</div>
        </div>
        <div class="right">
          <button class="et-link-more" @click="result = null"><el-icon :size="13"><Close /></el-icon>关闭</button>
        </div>
      </div>
      <div class="et-card-body">
        <div class="result-grid">
          <div class="stat total"><span class="stat-num">{{ result.total }}</span><span class="stat-label">用例总数</span></div>
          <div class="stat ok"><span class="stat-num">{{ result.passed }}</span><span class="stat-label">通过</span></div>
          <div class="stat bad"><span class="stat-num">{{ result.failed }}</span><span class="stat-label">失败</span></div>
          <div class="stat skip"><span class="stat-num">{{ result.skipped }}</span><span class="stat-label">跳过</span></div>
          <div class="stat run"><span class="stat-num">{{ result.runnable }}</span><span class="stat-label">可执行</span></div>
          <div class="stat time"><span class="stat-num">{{ fmtDuration(result.durationMs) }}</span><span class="stat-label">耗时</span></div>
        </div>
        <div class="rate-row">
          <span class="rate-label">通过率</span>
          <div class="et-bar rate-bar"><i :style="{ width: passRate + '%', background: rateColor }"></i></div>
          <span class="rate-num" :style="{ color: rateColor }">{{ passRate }}%</span>
        </div>
      </div>
    </section>

    <!-- 触发配置列表 -->
    <section class="et-card rise list-card" style="--d: 0.11s" v-loading="loading">
      <div class="et-card-head">
        <span class="et-tic"><el-icon :size="15"><Connection /></el-icon></span>
        <div>
          <div class="et-card-title">触发配置</div>
          <div class="et-card-sub">绑定测试计划 · 通过 CI 触发 · 实时执行</div>
        </div>
        <div class="right">
          <button class="et-link-more" @click="load"><el-icon :size="13"><RefreshRight /></el-icon>刷新</button>
          <el-button type="primary" size="small" :icon="Plus" @click="openCreate">新建触发配置</el-button>
        </div>
      </div>
      <div class="et-card-body no-padding">
        <el-table :data="triggers" style="width: 100%">
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column label="名称" min-width="180">
            <template #default="{ row }">
              <span class="tg-name">{{ row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column label="绑定计划" min-width="180">
            <template #default="{ row }">
              <span class="plan-name">{{ row.planName || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="触发类型" width="140">
            <template #default="{ row }">
              <span class="type-pill" :class="typeCls(row.triggerType)">{{ typeLabel(row.triggerType) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <span class="status-pill" :class="row.enabled ? 'ok' : 'off'">{{ row.enabled ? '启用' : '停用' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" align="right">
            <template #default="{ row }">
              <el-button size="small" class="ops-btn success" :icon="VideoPlay" :loading="runningId === row.id" @click="run(row)">运行</el-button>
              <el-button size="small" class="ops-btn danger" :icon="Delete" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="et-empty-hint">
              <div class="et-empty-ic"><el-icon :size="26"><Connection /></el-icon></div>
              <div>暂无触发配置</div>
              <div class="empty-sub">点击「新建触发配置」绑定测试计划到 CI/CD 流水线</div>
            </div>
          </template>
        </el-table>
      </div>
    </section>

    <!-- 接入示例 -->
    <section class="et-card rise code-card" style="--d: 0.14s">
      <div class="et-card-head">
        <span class="et-tic"><el-icon :size="15"><DocumentCode /></el-icon></span>
        <div>
          <div class="et-card-title">接入示例</div>
          <div class="et-card-sub">在 Jenkinsfile / GitHub Actions 中通过 HTTP 触发测试计划执行</div>
        </div>
        <div class="right">
          <el-button size="small" class="ops-btn primary" :icon="CopyDocument" :loading="copying" @click="copySnippet">复制代码</el-button>
        </div>
      </div>
      <div class="et-card-body">
        <p class="code-desc">
          在流水线中调用 <code>POST /api/v1/ci/run</code>，请求头携带 <code>X-CI-Token</code>，
          body 传入 projectKey / planId，即可触发对应测试计划执行。
        </p>
        <div class="code-block">
          <div class="code-bar">
            <span class="code-dot r"></span><span class="code-dot y"></span><span class="code-dot g"></span>
            <span class="code-fname">Jenkinsfile · curl</span>
          </div>
          <pre class="code-pre"><code>{{ snippet }}</code></pre>
        </div>
        <div class="code-desc">
          <b>GitHub Actions</b> 中只需把 <code>curl</code> 命令放入 <code>run</code> 步骤，
          并将 <code>evotrace.ci.token</code> 配置到仓库 Secrets 中引用即可。
        </div>
      </div>
    </section>

    <!-- 新建触发配置 -->
    <el-dialog v-model="dialogVisible" title="新建触发配置" width="500px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="绑定计划" required>
          <el-select v-model="form.planId" placeholder="请选择测试计划" style="width: 100%" :loading="plansLoading">
            <el-option v-for="p in plans" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如：发布前回归触发" />
        </el-form-item>
        <el-form-item label="触发类型">
          <el-select v-model="form.triggerType" style="width: 100%">
            <el-option label="Webhook（HTTP 触发）" value="WEBHOOK" />
            <el-option label="Cron（定时调度）" value="CRON" />
            <el-option label="MR（合并请求触发）" value="MR" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button class="ops-btn" @click="dialogVisible = false">取消</el-button>
        <el-button class="ops-btn primary" :loading="creating" :disabled="!form.planId || !form.name" @click="create">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Plus, RefreshRight, Delete, Connection, Key, CircleCheck, Close,
  VideoPlay, Document, CopyDocument
} from '@element-plus/icons-vue'
import { useProjectStore } from '../stores/project'
import { ciApi, testPlanApi, type CiTrigger, type TestPlan, type CiRunResult } from '../api'

const projectStore = useProjectStore()
const { current: projectKey } = storeToRefs(projectStore)

const loading = ref(false)
const triggers = ref<CiTrigger[]>([])

// CI Token（默认占位 evotrace.ci.token 配置值）
const DEFAULT_TOKEN = 'evotrace-devops-2026'
const ciToken = ref(DEFAULT_TOKEN)

// 运行结果
const result = ref<CiRunResult | null>(null)
const runningId = ref<number | null>(null)

// 新建
const dialogVisible = ref(false)
const creating = ref(false)
const plansLoading = ref(false)
const plans = ref<TestPlan[]>([])
const form = ref({ planId: undefined as number | undefined, name: '', triggerType: 'WEBHOOK', enabled: true })

const copying = ref(false)

const enabledCount = computed(() => triggers.value.filter((t) => t.enabled).length)

const typeMap: Record<string, { label: string; cls: string }> = {
  WEBHOOK: { label: 'WEBHOOK', cls: 'type-webhook' },
  CRON: { label: 'CRON', cls: 'type-cron' },
  MR: { label: 'MR', cls: 'type-mr' }
}
function typeLabel(t: string): string { return typeMap[t]?.label ?? t }
function typeCls(t: string): string { return typeMap[t]?.cls ?? 'type-webhook' }

const passRate = computed(() => {
  if (!result.value || !result.value.total) return 0
  return Math.round((result.value.passed / result.value.total) * 100)
})
const rateColor = computed(() =>
  passRate.value >= 80 ? 'var(--et-ok)' : passRate.value >= 60 ? 'var(--et-warn)' : 'var(--et-danger)'
)

const snippet = computed(() => `# 触发 ${projectKey.value || '{projectKey}'} 下测试计划执行
curl -X POST "${window.location.origin}/api/v1/ci/run" \\
  -H "Content-Type: application/json" \\
  -H "X-CI-Token: ${ciToken.value}" \\
  -d '{
    "projectKey": "${projectKey.value || "{projectKey}"}",
    "planId": ${form.value.planId ?? '{planId}'},
    "generateReport": true
  }'`)

function fmtDuration(ms: number): string {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

async function load() {
  if (!projectKey.value) return
  loading.value = true
  try {
    triggers.value = await ciApi.listTriggers(projectKey.value)
  } catch {
    ElMessage.error('加载触发配置失败')
  } finally {
    loading.value = false
  }
}

async function openCreate() {
  dialogVisible.value = true
  form.value = { planId: undefined, name: '', triggerType: 'WEBHOOK', enabled: true }
  if (!projectKey.value) return
  plansLoading.value = true
  try {
    plans.value = await testPlanApi.listPlans(projectKey.value)
  } catch {
    ElMessage.error('加载测试计划失败')
  } finally {
    plansLoading.value = false
  }
}

async function create() {
  if (!projectKey.value || !form.value.planId || !form.value.name) return
  creating.value = true
  try {
    await ciApi.createTrigger(projectKey.value, {
      planId: form.value.planId,
      name: form.value.name,
      triggerType: form.value.triggerType,
      enabled: form.value.enabled
    })
    ElMessage.success('触发配置已创建')
    dialogVisible.value = false
    await load()
  } catch {
    ElMessage.error('创建失败')
  } finally {
    creating.value = false
  }
}

async function run(row: CiTrigger) {
  if (!projectKey.value) return
  runningId.value = row.id
  try {
    const r = await ciApi.runByToken(ciToken.value || DEFAULT_TOKEN, {
      projectKey: projectKey.value,
      planId: row.planId,
      generateReport: true
    })
    result.value = r
    ElMessage.success(`「${r.planName}」执行完成，通过率 ${passRate.value}%`)
  } catch {
    ElMessage.error('触发执行失败，请检查 CI Token 是否正确')
  } finally {
    runningId.value = null
  }
}

async function remove(row: CiTrigger) {
  if (!projectKey.value) return
  try {
    await ElMessageBox.confirm(`确定删除触发配置「${row.name}」吗？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await ciApi.deleteTrigger(projectKey.value, row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    ElMessage.error('删除失败')
  }
}

async function copySnippet() {
  copying.value = true
  try {
    await navigator.clipboard.writeText(snippet.value)
    ElMessage.success('示例代码已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败')
  } finally {
    copying.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.ci-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.ci-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;
}
.hero-left { min-width: 0; }
.hero-chips { display: flex; align-items: center; gap: 10px; margin-top: 16px; flex-wrap: wrap; }
.hero-right { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }
.chip-mini {
  display: inline-flex; align-items: center; gap: 7px;
  font-size: 12px; color: var(--et-text-secondary);
  padding: 6px 12px; border-radius: 10px;
  background: var(--et-bg-muted); border: 1px solid var(--et-border);
}
.chip-mini b { color: var(--et-text); font-variant-numeric: tabular-nums; }
.chip-mini.live { color: var(--et-ok); }
.chip-mini .et-pulse { width: 6px; height: 6px; }

/* Token */
.token-card .right { min-width: 320px; }
.token-input :deep(.el-input__wrapper) {
  background: var(--et-bg-muted);
  box-shadow: 0 0 0 1px var(--et-border) inset;
  border-radius: 10px;
}

/* 表格 */
.list-card .et-card-head { padding-bottom: 14px; }
.tg-name { font-weight: 600; }
.plan-name { color: var(--et-text-secondary); font-size: 12.5px; }

/* 触发类型胶囊 */
.type-pill {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 3px 10px; border-radius: 20px;
  font-size: 11px; font-weight: 700; letter-spacing: 0.3px;
}
.type-pill::before { content: ''; width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.type-webhook { color: var(--et-primary-light); background: rgba(109, 124, 255, 0.13); }
.type-cron { color: var(--et-grad-b); background: rgba(167, 139, 250, 0.14); }
.type-mr { color: var(--et-warn); background: rgba(251, 191, 36, 0.13); }

/* 状态胶囊 */
.status-pill {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 3px 10px; border-radius: 20px; font-size: 11.5px; font-weight: 700;
}
.status-pill::before { content: ''; width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.status-pill.ok { color: var(--et-ok); background: rgba(52, 211, 153, 0.12); }
.status-pill.off { color: var(--et-text-muted); background: var(--et-bg-muted); }

/* 操作按钮 */
.ops-btn {
  font-weight: 600;
  border-radius: 8px;
  background: color-mix(in srgb, currentColor 12%, transparent);
}
.ops-btn:hover {
  background: color-mix(in srgb, currentColor 22%, transparent);
  box-shadow: 0 0 12px var(--et-glow);
}
.ops-btn.primary { color: #a8b4ff; }
.ops-btn.success { color: #34d399; }
.ops-btn.danger { color: #fb7185; }

.empty-sub { margin-top: 6px; font-size: 12px; color: var(--et-text-muted); }

/* 运行结果 */
.result-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 14px;
}
.stat {
  display: flex; flex-direction: column; gap: 5px;
  padding: 16px; border-radius: 14px;
  background: var(--et-bg-muted); border: 1px solid var(--et-border);
}
.stat-num { font-size: 26px; font-weight: 800; font-variant-numeric: tabular-nums; }
.stat-label { font-size: 12px; color: var(--et-text-muted); }
.stat.total .stat-num { color: var(--et-text); }
.stat.ok .stat-num { color: var(--et-ok); }
.stat.bad .stat-num { color: var(--et-danger); }
.stat.skip .stat-num { color: var(--et-warn); }
.stat.run .stat-num { color: var(--et-grad-c); }
.stat.time .stat-num { color: var(--et-grad-b); }

.rate-row { display: flex; align-items: center; gap: 14px; margin-top: 18px; }
.rate-label { font-size: 12.5px; color: var(--et-text-muted); flex-shrink: 0; }
.rate-bar { flex: 1; height: 8px; }
.rate-bar i { transition: width 0.8s cubic-bezier(0.22, 1, 0.36, 1); }
.rate-num { font-size: 15px; font-weight: 800; font-variant-numeric: tabular-nums; flex-shrink: 0; }

/* 接入示例 */
.code-desc {
  margin: 0 0 14px;
  font-size: 13px; line-height: 1.8; color: var(--et-text-secondary);
}
.code-desc code {
  padding: 1px 6px; border-radius: 6px; font-size: 12px;
  background: var(--et-bg-muted); border: 1px solid var(--et-border);
  color: var(--et-grad-c);
}
.code-block {
  border-radius: 12px; overflow: hidden;
  border: 1px solid var(--et-border);
  background: #0b1021;
}
.code-bar {
  display: flex; align-items: center; gap: 6px;
  padding: 9px 14px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(255, 255, 255, 0.03);
}
.code-dot { width: 10px; height: 10px; border-radius: 50%; }
.code-dot.r { background: #ff5f57; }
.code-dot.y { background: #febc2e; }
.code-dot.g { background: #28c840; }
.code-fname { margin-left: 8px; font-size: 11.5px; color: var(--et-text-muted); }
.code-pre {
  margin: 0; padding: 16px;
  overflow-x: auto;
  font-family: 'SF Mono', 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12.5px; line-height: 1.7;
  color: #cbd5f5;
}
</style>