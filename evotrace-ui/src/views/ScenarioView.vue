<script setup lang="ts">
import { ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Plus, Delete, VideoPlay, FolderChecked, Top, Bottom
} from '@element-plus/icons-vue'
import { useProjectStore } from '../stores/project'
import {
  scenarioApi, apiDebugApi,
  type Scenario, type ScenarioStep, type ScenarioRunResult, type ApiEnvironment
} from '../api'

const projectStore = useProjectStore()
const { current: projectKey } = storeToRefs(projectStore)

const loading = ref(false)
const scenarios = ref<Scenario[]>([])
const selectedId = ref<number | null>(null)
const environments = ref<ApiEnvironment[]>([])

// 编辑草稿
const draftName = ref('')
const draftDesc = ref('')
const draftEnabled = ref(true)
const steps = ref<ScenarioStep[]>([])
const expandedStep = ref(-1)

// 运行
const runEnv = ref<number | null>(null)
const running = ref(false)
const runResult = ref<ScenarioRunResult | null>(null)

const assertionTypes = [
  { value: 'status', label: '状态码' },
  { value: 'jsonpath', label: 'JSON 取值' },
  { value: 'contains', label: '内容包含' },
  { value: 'equals', label: '完全相等' },
  { value: 'regex', label: '正则匹配' }
]

function assertionPlaceholder(t: string) {
  return ({ status: '如 200', jsonpath: '如 data.code == 0', contains: '如 成功', equals: '如 {"code":0}', regex: '如 ^\\d{4}$' } as Record<string, string>)[t] ?? ''
}

const methodColor = (m: string) => {
  const c: Record<string, string> = { GET: '#34d399', POST: '#a5b0ff', PUT: '#fbbf24', DELETE: '#fb7185', PATCH: '#f472b6' }
  return c[m.toUpperCase()] ?? '#93a0bd'
}
const stepTypeColor = (t: string) => ({ HTTP: '#38e1ff', SQL: '#f472b6', TCP: '#fb923c', EXTRACT: '#a78bfa', ASSERT: '#34d399', IF: '#fbbf24' }[t] ?? 'var(--et-text-muted)')
const stepTypeBg = (t: string) => ({ HTTP: 'rgba(56,225,255,0.12)', SQL: 'rgba(244,114,182,0.14)', TCP: 'rgba(251,146,60,0.14)', EXTRACT: 'rgba(167,139,250,0.14)', ASSERT: 'rgba(52,211,153,0.12)', IF: 'rgba(251,191,36,0.14)' }[t] ?? 'var(--et-bg-muted)')
const stepDefaultName = (t: string) => ({ HTTP: 'HTTP 请求', SQL: 'SQL 查询', TCP: 'TCP 通信', EXTRACT: '提取变量', ASSERT: '结果断言', IF: '条件分支' }[t] ?? t)

function defaultConfig(t: string): Record<string, any> {
  switch (t) {
    case 'HTTP': return { method: 'GET', url: '', headers: {}, body: '', assertions: [] }
    case 'SQL': return { jdbcUrl: '', user: '', password: '', sql: 'SELECT 1' }
    case 'TCP': return { host: '', port: 80, payload: '' }
    case 'EXTRACT': return { variable: '', path: '' }
    case 'ASSERT': return { type: 'status', expected: '' }
    case 'IF': return { condition: '' }
    default: return {}
  }
}

// 每个步骤在可编辑态维护 headers / assertions 的行式副本（不入库，保存时转回对象）
function normalizeStep(s: ScenarioStep): ScenarioStep {
  if (!s.config) s.config = {}
  const headers = (s.config.headers && typeof s.config.headers === 'object') ? s.config.headers : {}
  ;(s as any)._h = (s as any)._h?.length
    ? (s as any)._h
    : Object.entries(headers).map(([k, v]) => ({ key: k, value: String(v) }))
  const assertions = Array.isArray(s.config.assertions) ? s.config.assertions : []
  ;(s as any)._assertions = (s as any)._assertions?.length
    ? (s as any)._assertions
    : assertions.map((a: any) => ({ type: a.type ?? 'status', expected: a.expected ?? '' }))
  return s
}

function buildStepPayload(s: ScenarioStep): ScenarioStep {
  const cfg: Record<string, any> = { ...s.config }
  if (s.stepType === 'HTTP') {
    const headers: Record<string, string> = {}
    for (const r of (s as any)._h ?? []) if (r.key) headers[r.key] = r.value
    cfg.headers = headers
    cfg.assertions = ((s as any)._assertions ?? [])
      .filter((a: any) => a.type && a.expected !== undefined && a.expected !== '')
      .map((a: any) => ({ type: a.type, expected: a.expected }))
  }
  return { ...s, config: cfg }
}

function addHeader(step: ScenarioStep) { (step as any)._h.push({ key: '', value: '' }) }
function removeHeader(step: ScenarioStep, i: string | number) { (step as any)._h.splice(Number(i), 1) }
function addAssertion(step: ScenarioStep) { (step as any)._assertions.push({ type: 'status', expected: '' }) }
function removeAssertion(step: ScenarioStep, i: string | number) { (step as any)._assertions.splice(Number(i), 1) }

async function load() {
  if (!projectKey.value) return
  loading.value = true
  try {
    const [list, envs] = await Promise.all([
      scenarioApi.list(projectKey.value),
      apiDebugApi.environments(projectKey.value)
    ])
    scenarios.value = list
    environments.value = envs
    if (!selectedId.value && list.length) await selectScenario(list[0].id)
  } catch { /* 拦截器已提示 */ } finally { loading.value = false }
}

watch(projectKey, load, { immediate: true })

async function selectScenario(id: number) {
  selectedId.value = id
  expandedStep.value = -1
  try {
    const full = await scenarioApi.detail(projectKey.value, id)
    draftName.value = full.name
    draftDesc.value = full.description ?? ''
    draftEnabled.value = full.enabled
    steps.value = (full.steps ?? []).map(normalizeStep)
    runResult.value = null
  } catch { /* 拦截器已提示 */ }
}

function newScenario() {
  selectedId.value = null
  draftName.value = ''
  draftDesc.value = ''
  draftEnabled.value = true
  steps.value = []
  runResult.value = null
  expandedStep.value = -1
}

function addStep(type: string) {
  steps.value.push(normalizeStep({ stepType: type, sortOrder: steps.value.length + 1, name: stepDefaultName(type), config: defaultConfig(type) }))
  expandedStep.value = steps.value.length - 1
}

function toggleExpand(i: number) { expandedStep.value = expandedStep.value === i ? -1 : i }
function moveStep(i: number, dir: number) {
  const j = i + dir
  if (j < 0 || j >= steps.value.length) return
  const arr = steps.value
  ;[arr[i], arr[j]] = [arr[j], arr[i]]
  arr.forEach((s, idx) => { s.sortOrder = idx + 1 })
}
function removeStep(i: number) {
  steps.value.splice(i, 1)
  steps.value.forEach((s, idx) => { s.sortOrder = idx + 1 })
  expandedStep.value = -1
}

async function save() {
  if (!draftName.value.trim()) { ElMessage.warning('请填写场景名称'); return }
  if (!steps.value.length) { ElMessage.warning('请至少添加一个步骤'); return }
  const payload = {
    name: draftName.value.trim(),
    description: draftDesc.value,
    enabled: draftEnabled.value,
    steps: steps.value.map(buildStepPayload)
  }
  const id = selectedId.value
  if (id) {
    await scenarioApi.update(projectKey.value, id, payload)
  } else {
    const res = await scenarioApi.create(projectKey.value, payload)
    selectedId.value = res.id
  }
  ElMessage.success(id ? '场景已更新' : '场景已创建')
  await load()
  if (selectedId.value) await selectScenario(selectedId.value)
}

async function removeScenario() {
  if (!selectedId.value) return
  try {
    await ElMessageBox.confirm(`删除场景「${draftName.value}」？此操作不可恢复。`, '删除场景', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch { return }
  await scenarioApi.remove(projectKey.value, selectedId.value)
  ElMessage.success('场景已删除')
  newScenario()
  await load()
}

async function run() {
  if (!selectedId.value) return
  running.value = true
  runResult.value = null
  try {
    runResult.value = await scenarioApi.run(projectKey.value, selectedId.value, { environmentId: runEnv.value ?? undefined })
  } catch (e: any) {
    ElMessage.error(e?.message ?? '运行失败')
  } finally { running.value = false }
}

const verdictClass = (v: string) => (v === 'PASSED' ? 'ok' : v === 'FAILED' ? 'fail' : 'err')
const statusLabel = (s: string) => ({ PASSED: '通过', FAILED: '失败', ERROR: '错误', SKIPPED: '跳过', RUNNING: '运行中' }[s] ?? s)
</script>

<template>
  <div class="scenario-page">
    <!-- 顶部 Hero -->
    <div class="et-hero hero-row" style="margin-bottom: 16px">
      <div class="hero-main">
        <div class="hero-icon et-g-ic g-violet">场</div>
        <div>
          <h2>场景编排</h2>
          <div class="et-hero-sub">多接口串联 · 变量提取 · 条件分支，构建自动化测试场景</div>
        </div>
      </div>
      <div class="hero-actions">
        <el-button size="small" type="primary" :icon="Plus" @click="newScenario">新建场景</el-button>
      </div>
    </div>
    <div class="main">
      <!-- 左：场景列表 -->
      <div class="side-panel">
        <div class="side-head">
          <span class="side-title">场景列表 <span class="count">{{ scenarios.length }}</span></span>
          <el-button size="small" class="ops-btn" :icon="Plus" @click="newScenario">新建场景</el-button>
        </div>
        <div v-loading="loading" class="side-scroll">
          <div
            v-for="s in scenarios" :key="s.id"
            class="scenario-item" :class="{ active: selectedId === s.id }"
            @click="selectScenario(s.id)"
          >
            <div class="item-top">
              <span class="dot" :class="s.enabled ? 'on' : 'off'"></span>
              <span class="name ellipsis">{{ s.name }}</span>
              <span class="step-count">{{ s.steps?.length ?? 0 }} 步</span>
            </div>
            <div v-if="s.description" class="item-desc ellipsis">{{ s.description }}</div>
          </div>
          <el-empty v-if="!loading && scenarios.length === 0" description="暂无场景，点击「新建场景」开始编排" :image-size="60" />
        </div>
      </div>

      <!-- 右：编辑 + 运行 -->
      <div class="work-panel">
        <!-- 场景信息卡 -->
        <div class="edit-card">
          <div class="edit-head">
            <div class="edit-title">
              <el-input v-model="draftName" placeholder="场景名称" class="name-input" />
              <span v-if="selectedId" class="enable-wrap">
                <el-switch
                  v-model="draftEnabled"
                  inline-prompt
                  active-text="启用" inactive-text="停用"
                  :active-color="'#34d399'" :inactive-color="'#fb7185'"
                />
              </span>
            </div>
            <div class="edit-actions">
              <el-select v-model="runEnv" placeholder="运行环境" size="small" clearable class="env-select">
                <el-option
                  v-for="e in environments" :key="e.id"
                  :value="e.id" :label="e.baseUrl ? `${e.name} · ${e.baseUrl}` : e.name"
                />
              </el-select>
              <el-button size="small" class="ops-btn" :icon="VideoPlay" :loading="running" :disabled="!selectedId" @click="run">运行</el-button>
              <el-button size="small" class="ops-btn danger" :icon="Delete" :disabled="!selectedId" @click="removeScenario">删除</el-button>
              <el-button size="small" type="primary" :icon="FolderChecked" @click="save">{{ selectedId ? '保存' : '创建' }}</el-button>
            </div>
          </div>
          <div class="edit-desc">
            <el-input v-model="draftDesc" placeholder="场景描述（可选）" clearable />
          </div>
        </div>

        <!-- 步骤编排 -->
        <div class="steps-panel">
          <div class="steps-head">
            <span class="steps-title">步骤编排</span>
            <el-dropdown trigger="click" @command="addStep as any">
              <el-button size="small" class="ops-btn" :icon="Plus">添加步骤</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="HTTP"><span class="dd-item"><i class="dd-dot" style="background:#38e1ff"></i>HTTP 请求</span></el-dropdown-item>
                  <el-dropdown-item command="SQL"><span class="dd-item"><i class="dd-dot" style="background:#f472b6"></i>SQL 查询</span></el-dropdown-item>
                  <el-dropdown-item command="TCP"><span class="dd-item"><i class="dd-dot" style="background:#fb923c"></i>TCP 通信</span></el-dropdown-item>
                  <el-dropdown-item command="EXTRACT"><span class="dd-item"><i class="dd-dot" style="background:#a78bfa"></i>提取变量</span></el-dropdown-item>
                  <el-dropdown-item command="ASSERT"><span class="dd-item"><i class="dd-dot" style="background:#34d399"></i>结果断言</span></el-dropdown-item>
                  <el-dropdown-item command="IF"><span class="dd-item"><i class="dd-dot" style="background:#fbbf24"></i>条件分支</span></el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>

          <div v-if="steps.length === 0" class="steps-empty">
            <el-empty description="暂无步骤，点击「添加步骤」编排多接口流水线" :image-size="80" />
          </div>

          <div v-for="(step, i) in steps" :key="i" class="step-item" :class="{ expanded: expandedStep === i }">
            <div class="step-head" @click="toggleExpand(i)">
              <span class="step-index">{{ i + 1 }}</span>
              <span class="step-type" :style="{ color: stepTypeColor(step.stepType), background: stepTypeBg(step.stepType) }">{{ step.stepType }}</span>
              <span class="step-name ellipsis">{{ step.name }}</span>
              <span class="step-ops">
                <el-button text size="small" :icon="Top" :disabled="i === 0" title="上移" @click.stop="moveStep(i, -1)" />
                <el-button text size="small" :icon="Bottom" :disabled="i === steps.length - 1" title="下移" @click.stop="moveStep(i, 1)" />
                <el-button text size="small" class="del" :icon="Delete" title="删除" @click.stop="removeStep(i)" />
              </span>
            </div>

            <div v-if="expandedStep === i" class="step-body">
              <!-- HTTP -->
              <template v-if="step.stepType === 'HTTP'">
                <div class="field-row">
                  <el-select v-model="step.config.method" size="small" style="width: 120px">
                    <el-option v-for="m in ['GET','POST','PUT','PATCH','DELETE']" :key="m" :value="m" :label="m" />
                  </el-select>
                  <el-input v-model="step.config.url" size="small" placeholder="URL，支持 ${baseUrl} 与 ${变量} 占位，如 ${baseUrl}/api/items" />
                </div>
                <div class="field-label">Headers</div>
                <div v-for="(h, hi) in (step as any)._h" :key="hi" class="kv-row">
                  <el-input v-model="h.key" size="small" placeholder="Header 名" style="width: 200px" />
                  <el-input v-model="h.value" size="small" placeholder="值" />
                  <el-button text size="small" :icon="Delete" @click="removeHeader(step, hi)" />
                </div>
                <el-button text size="small" type="primary" @click="addHeader(step)">+ Header</el-button>

                <div class="field-label">Body</div>
                <el-input v-model="step.config.body" type="textarea" :rows="4" placeholder='请求体 JSON，可用 ${变量}，如 {"keyword":"${keyword}"}' class="mono" />

                <div class="field-label">断言</div>
                <div v-for="(a, ai) in (step as any)._assertions" :key="ai" class="kv-row">
                  <el-select v-model="a.type" size="small" style="width: 150px">
                    <el-option v-for="o in assertionTypes" :key="o.value" :value="o.value" :label="o.label" />
                  </el-select>
                  <el-input v-model="a.expected" size="small" :placeholder="assertionPlaceholder(a.type)" />
                  <el-button text size="small" :icon="Delete" @click="removeAssertion(step, ai)" />
                </div>
                <el-button text size="small" type="primary" @click="addAssertion(step)">+ 断言</el-button>
              </template>

              <!-- SQL -->
              <template v-else-if="step.stepType === 'SQL'">
                <div class="field-row">
                  <el-input v-model="step.config.jdbcUrl" size="small" placeholder="JDBC URL，支持 ${变量}，如 jdbc:mysql://localhost:3306/db" />
                </div>
                <div class="field-row">
                  <el-input v-model="step.config.user" size="small" placeholder="用户名" style="width: 200px" />
                  <el-input v-model="step.config.password" size="small" placeholder="密码" style="width: 200px" />
                </div>
                <div class="field-label">SQL</div>
                <el-input v-model="step.config.sql" type="textarea" :rows="3" placeholder="SQL 语句，支持 ${变量}，如 SELECT id FROM t_user WHERE name='${name}'" class="mono" />
              </template>

              <!-- TCP -->
              <template v-else-if="step.stepType === 'TCP'">
                <div class="field-row">
                  <el-input v-model="step.config.host" size="small" placeholder="主机，如 127.0.0.1" style="width: 220px" />
                  <el-input-number v-model="step.config.port" size="small" :min="1" :max="65535" style="width: 140px" />
                </div>
                <div class="field-label">Payload</div>
                <el-input v-model="step.config.payload" type="textarea" :rows="3" placeholder="发送的报文内容，响应会写入 __lastResponse 供后续步骤引用" class="mono" />
              </template>

              <!-- EXTRACT -->
              <template v-else-if="step.stepType === 'EXTRACT'">
                <div class="field-row">
                  <el-input v-model="step.config.variable" size="small" placeholder="变量名，如 token / itemId" style="width: 220px" />
                  <el-input v-model="step.config.path" size="small" placeholder="JSONPath 取值，如 data.items[0].id" />
                </div>
                <div class="hint">从上一 HTTP 步骤的响应中按 JSONPath 提取变量，供后续步骤通过 ${变量} 引用。</div>
              </template>

              <!-- ASSERT -->
              <template v-else-if="step.stepType === 'ASSERT'">
                <div class="field-row">
                  <el-select v-model="step.config.type" size="small" style="width: 150px">
                    <el-option v-for="o in assertionTypes" :key="o.value" :value="o.value" :label="o.label" />
                  </el-select>
                  <el-input v-model="step.config.expected" size="small" :placeholder="assertionPlaceholder(step.config.type)" />
                </div>
              </template>

              <!-- IF -->
              <template v-else>
                <el-input v-model="step.config.condition" size="small" placeholder="条件表达式，如 code == 0 或 status == 200" />
                <div class="hint">条件为真时继续执行后续步骤，否则中断本次运行。</div>
              </template>
            </div>
          </div>
        </div>

        <!-- 运行结果 -->
        <div v-if="runResult" class="result-panel">
          <div class="result-head">
            <span class="result-title">运行结果</span>
            <span class="verdict" :class="verdictClass(runResult.verdict)">{{ runResult.verdict }}</span>
            <span class="result-dur">{{ runResult.durationMs }}ms</span>
          </div>
          <div v-if="Object.keys(runResult.variables ?? {}).length" class="result-vars">
            <span class="vars-title">变量</span>
            <span v-for="(v, k) in runResult.variables" :key="k" class="var-chip">{{ k }} = <b>{{ v }}</b></span>
          </div>
          <div class="result-steps">
            <div v-for="rs in runResult.steps" :key="rs.index" class="rstep">
              <div class="rstep-head">
                <span class="rstep-index">{{ rs.index }}</span>
                <span class="rstep-type" :style="{ color: stepTypeColor(rs.type) }">{{ rs.type }}</span>
                <span class="rstep-name ellipsis">{{ rs.name || '' }}</span>
                <span v-if="rs.method && rs.url" class="rstep-url ellipsis">
                  <i :style="{ color: methodColor(rs.method) }">{{ rs.method }}</i> {{ rs.url }}
                </span>
                <span class="rstep-meta">
                  <span v-if="rs.statusCode" class="code" :class="rs.statusCode >= 400 ? 'err' : 'ok'">{{ rs.statusCode }}</span>
                  <span v-if="rs.durationMs != null" class="dur">{{ rs.durationMs }}ms</span>
                  <span class="status-badge" :class="rs.status">{{ statusLabel(rs.status) }}</span>
                </span>
              </div>
              <div v-if="rs.assertions && rs.assertions.length" class="rstep-assertions">
                <div v-for="(a, ai) in rs.assertions" :key="ai" class="assert-line" :class="a.passed ? 'ok' : 'fail'">
                  <span class="mark">{{ a.passed ? '✓' : '✗' }}</span>
                  <span class="a-type">{{ a.type }}</span>
                  <span class="a-msg">{{ a.message || a.expected }}</span>
                </div>
              </div>
              <div v-if="rs.error" class="rstep-error">{{ rs.error }}</div>
              <pre v-if="rs.responseSnippet" class="rstep-snippet">{{ rs.responseSnippet }}</pre>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.scenario-page { height: calc(100vh - 150px); display: flex; flex-direction: column; }
.main { display: flex; gap: 12px; flex: 1; min-height: 0; }
.ops-btn { font-weight: 600; background: color-mix(in srgb, currentColor 12%, transparent); border-radius: 8px; }
.ops-btn:hover { background: color-mix(in srgb, currentColor 22%, transparent); box-shadow: 0 0 12px var(--et-glow); }
.ops-btn.danger { color: var(--et-danger); }
.ellipsis { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ---------- 左列表 ---------- */
.side-panel { width: 300px; flex-shrink: 0; background: var(--et-card-bg); border: 1px solid var(--et-border); border-radius: 12px; display: flex; flex-direction: column; overflow: hidden; }
.side-head { display: flex; justify-content: space-between; align-items: center; padding: 12px 14px; border-bottom: 1px solid var(--et-border); }
.side-title { font-weight: 700; font-size: 13px; display: flex; align-items: center; gap: 6px; }
.side-title .count { background: var(--et-bg-muted); padding: 0 7px; border-radius: 8px; font-size: 11px; color: var(--et-text-muted); }
.side-scroll { flex: 1; overflow-y: auto; padding: 8px 10px 12px; }
.scenario-item { padding: 9px 11px; border-radius: 9px; cursor: pointer; margin-bottom: 6px; border: 1px solid transparent; }
.scenario-item:hover { background: var(--et-bg-muted); }
.scenario-item.active { background: var(--et-primary-bg); box-shadow: 0 0 0 1px var(--et-primary) inset; }
.item-top { display: flex; align-items: center; gap: 8px; }
.dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.dot.on { background: var(--et-ok); box-shadow: 0 0 8px var(--et-ok); }
.dot.off { background: var(--et-text-muted); }
.name { font-size: 13px; font-weight: 600; flex: 1; min-width: 0; }
.step-count { font-size: 11px; color: var(--et-text-muted); background: var(--et-bg-muted); padding: 1px 6px; border-radius: 8px; flex-shrink: 0; }
.item-desc { margin-top: 4px; padding-left: 16px; font-size: 11.5px; color: var(--et-text-muted); }

/* ---------- 右面板 ---------- */
.work-panel { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 12px; overflow: hidden; }
.edit-card { background: var(--et-card-bg); border: 1px solid var(--et-border); border-radius: 12px; padding: 12px 14px; display: flex; flex-direction: column; gap: 10px; }
.edit-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.edit-title { display: flex; align-items: center; gap: 12px; flex: 1; min-width: 260px; }
.name-input { max-width: 320px; }
.name-input :deep(.el-input__wrapper) { background: transparent; }
.enable-wrap { flex-shrink: 0; }
.edit-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.env-select { width: 180px; }
.edit-desc :deep(.el-input__wrapper) { background: transparent; }

/* ---------- 步骤 ---------- */
.steps-panel { flex: 1; min-height: 0; background: var(--et-card-bg); border: 1px solid var(--et-border); border-radius: 12px; display: flex; flex-direction: column; overflow: hidden; }
.steps-head { display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; border-bottom: 1px solid var(--et-border); }
.steps-title { font-weight: 700; font-size: 13px; }
.steps-body { flex: 1; overflow-y: auto; padding: 10px 12px; }
.steps-empty { flex: 1; display: flex; align-items: center; justify-content: center; }
.dd-item { display: inline-flex; align-items: center; gap: 8px; }
.dd-dot { width: 8px; height: 8px; border-radius: 50%; }

.step-item { border: 1px solid var(--et-border); border-radius: 10px; margin-bottom: 8px; overflow: hidden; background: var(--et-bg-muted); }
.step-item.expanded { border-color: var(--et-primary); box-shadow: 0 0 0 1px var(--et-primary) inset; }
.step-head { display: flex; align-items: center; gap: 10px; padding: 9px 12px; cursor: pointer; }
.step-head:hover { background: var(--et-bg-muted); }
.step-index { width: 22px; height: 22px; border-radius: 6px; background: var(--et-primary-bg); color: var(--et-primary); font-size: 12px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.step-type { font-size: 10.5px; font-weight: 700; padding: 2px 8px; border-radius: 6px; letter-spacing: 0.5px; flex-shrink: 0; }
.step-name { flex: 1; min-width: 0; font-size: 13px; font-weight: 600; }
.step-ops { display: flex; align-items: center; flex-shrink: 0; }
.step-ops .del { color: var(--et-text-muted); }
.step-ops .del:hover { color: var(--et-danger); }

.step-body { padding: 12px 14px; border-top: 1px solid var(--et-border); display: flex; flex-direction: column; gap: 8px; }
.field-row { display: flex; gap: 8px; align-items: center; }
.field-label { font-size: 11.5px; color: var(--et-text-muted); font-weight: 600; margin-top: 4px; }
.kv-row { display: flex; gap: 8px; align-items: center; }
.mono :deep(textarea) { font-family: 'SF Mono', Menlo, Consolas, monospace; background: rgba(4, 8, 18, 0.4); }
.hint { font-size: 11.5px; color: var(--et-text-muted); }

/* ---------- 运行结果 ---------- */
.result-panel { background: var(--et-card-bg); border: 1px solid var(--et-border); border-radius: 12px; display: flex; flex-direction: column; max-height: 46%; overflow: hidden; }
.result-head { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-bottom: 1px solid var(--et-border); }
.result-title { font-weight: 700; font-size: 13px; }
.verdict { font-weight: 800; font-size: 13px; padding: 1px 10px; border-radius: 8px; }
.verdict.ok { color: var(--et-ok); background: color-mix(in srgb, var(--et-ok) 14%, transparent); }
.verdict.fail { color: var(--et-warn); background: color-mix(in srgb, var(--et-warn) 14%, transparent); }
.verdict.err { color: var(--et-danger); background: color-mix(in srgb, var(--et-danger) 14%, transparent); }
.result-dur { color: var(--et-text-muted); font-size: 12px; }
.result-vars { padding: 8px 14px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; border-bottom: 1px solid var(--et-border); }
.vars-title { font-size: 11px; color: var(--et-text-muted); font-weight: 600; }
.var-chip { font-size: 11.5px; background: var(--et-bg-muted); padding: 2px 8px; border-radius: 8px; color: var(--et-text-secondary); }
.var-chip b { color: var(--et-text); font-family: 'SF Mono', Menlo, Consolas, monospace; }
.result-steps { flex: 1; min-height: 0; overflow-y: auto; padding: 8px 12px 12px; }
.rstep { border: 1px solid var(--et-border); border-radius: 9px; margin-bottom: 8px; padding: 8px 11px; background: var(--et-bg-muted); }
.rstep-head { display: flex; align-items: center; gap: 10px; }
.rstep-index { width: 20px; height: 20px; border-radius: 5px; background: var(--et-bg-muted); color: var(--et-text-muted); font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.rstep-type { font-size: 10.5px; font-weight: 700; flex-shrink: 0; }
.rstep-name { flex-shrink: 0; max-width: 28%; font-size: 12.5px; font-weight: 600; }
.rstep-url { flex: 1; min-width: 0; font-size: 12px; color: var(--et-text-secondary); }
.rstep-url i { font-style: normal; font-weight: 700; margin-right: 4px; }
.rstep-meta { display: flex; gap: 8px; align-items: center; flex-shrink: 0; }
.code { font-weight: 700; font-size: 12px; }
.code.ok { color: var(--et-ok); }
.code.err { color: var(--et-danger); }
.dur { color: var(--et-text-muted); font-size: 11.5px; }
.status-badge { font-size: 11px; font-weight: 700; padding: 1px 8px; border-radius: 8px; }
.status-badge.PASSED { color: var(--et-ok); background: color-mix(in srgb, var(--et-ok) 14%, transparent); }
.status-badge.FAILED { color: var(--et-danger); background: color-mix(in srgb, var(--et-danger) 14%, transparent); }
.status-badge.ERROR { color: var(--et-danger); background: color-mix(in srgb, var(--et-danger) 14%, transparent); }
.status-badge.SKIPPED { color: var(--et-text-muted); background: var(--et-bg-muted); }
.rstep-assertions { margin-top: 8px; display: flex; flex-direction: column; gap: 4px; }
.assert-line { display: flex; gap: 8px; align-items: center; font-size: 12px; }
.mark { font-weight: 800; }
.assert-line.ok { color: var(--et-ok); }
.assert-line.fail { color: var(--et-danger); }
.a-type { font-weight: 600; }
.a-msg { color: var(--et-text-secondary); }
.rstep-error { margin-top: 8px; color: var(--et-danger); font-size: 12px; }
.rstep-snippet { margin: 8px 0 0; padding: 8px; background: rgba(4, 8, 18, 0.4); border-radius: 8px; font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 11.5px; color: var(--et-text); white-space: pre-wrap; word-break: break-word; max-height: 160px; overflow: auto; }

@media (max-width: 900px) {
  .side-panel { width: 240px; }
}
</style>