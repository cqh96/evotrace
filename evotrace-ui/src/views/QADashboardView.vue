<script setup lang="ts">
import { onMounted, ref, nextTick, computed, watch, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Setting, Document, Calendar, VideoPlay, TrendCharts, Warning, Monitor, FolderOpened } from '@element-plus/icons-vue'
import PageCard from '../components/PageCard.vue'
import StatCard from '../components/StatCard.vue'
import client from '../api/client'
import { bugApi, jiraApi, testPlanApi, releaseApi, analysisApi, diagnosticsApi, type TestCase, type TestPlan, type RunCaseResult, type RunPlanResult } from '../api'
import { useProjectStore } from '../stores/project'

const projectStore = useProjectStore()
const { current: project } = storeToRefs(projectStore)
const activeTab = ref('quality')

// ==================== 通用 ====================
const sevColor = (s: string) => ({ P0: 'danger', P1: 'error', P2: 'warning', P3: 'info' } as any)[s] || 'info'
const statusColor = (s: string) => ({ OPEN: 'danger', IN_PROGRESS: 'warning', FIXED: 'success', VERIFIED: 'success', CLOSED: 'info', REOPENED: 'danger' } as any)[s] || 'info'
const execStatusColor = (s: string) => ({ PENDING: 'info', PASSED: 'success', FAILED: 'danger', BLOCKED: 'warning', SKIPPED: 'info' } as any)[s] || 'info'
const planStatusColor = (s: string) => ({ DRAFT: 'info', RUNNING: 'warning', DONE: 'success' } as any)[s] || 'info'
const typeLabel = (t: string) => ({ FUNCTIONAL: '功能', REGRESSION: '回归', PERF: '性能', SECURITY: '安全', API: '接口', UI: 'UI' } as any)[t] || t
// 缺陷状态机合法流转
const bugTransitions: Record<string, string[]> = {
  OPEN: ['IN_PROGRESS'],
  IN_PROGRESS: ['FIXED', 'OPEN'],
  FIXED: ['VERIFIED', 'REOPENED', 'IN_PROGRESS'],
  VERIFIED: ['CLOSED', 'REOPENED'],
  REOPENED: ['IN_PROGRESS', 'FIXED'],
  CLOSED: ['REOPENED']
}
const planTransitions: Record<string, string[]> = {
  DRAFT: ['RUNNING'],
  RUNNING: ['DONE', 'DRAFT'],
  DONE: ['RUNNING']
}
// UI DSL + HTTP 步骤
const STEP_ACTIONS = ['open', 'click', 'input', 'select', 'assertText', 'assertUrl', 'screenshot', 'waitFor', 'http']
const stepActionLabel = (a: string) => ({ http: 'HTTP 请求', open: '打开页面', click: '点击', input: '输入', select: '选择', assertText: '文本断言', assertUrl: 'URL 断言', screenshot: '截图', waitFor: '等待' } as any)[a] || a
const ASSERTION_TYPES = ['statusCode', 'bodyContains', 'bodyNotContains', 'responseTimeMs']
const assertionTypeLabel = (t: string) => ({ statusCode: '状态码', bodyContains: '响应体包含', bodyNotContains: '响应体不包含', responseTimeMs: '耗时≤' } as any)[t] || t

// ==================== Tab1 测试用例 ====================
const caseTree = ref<TestCase[]>([])
const caseList = ref<TestCase[]>([])
const caseTotal = ref(0)
const casePage = ref(1)
const casePageSize = ref(20)
const caseFilter = ref({ keyword: '', testType: '', priority: '', parentId: undefined as number | undefined })
const caseLoading = ref(false)
const caseDialog = ref(false)
const caseForm = ref<{ id?: number; title: string; testType: string; priority: string; description: string; relatedFiles: string; relatedApis: string; tags: string; parentId?: number; steps: any[] }>({ id: undefined, title: '', testType: 'FUNCTIONAL', priority: 'P2', description: '', relatedFiles: '', relatedApis: '', tags: '', parentId: undefined, steps: [{ action: 'open', target: '', value: '', expected: '' }] })
const caseDetailVisible = ref(false)
const caseDetail = ref<TestCase | null>(null)

async function loadCaseTree() {
  try { caseTree.value = await testPlanApi.getCaseTree(project.value) } catch { caseTree.value = [] }
}
async function loadCases() {
  caseLoading.value = true
  try {
    const r = await testPlanApi.listCases(project.value, {
      page: casePage.value, pageSize: casePageSize.value,
      keyword: caseFilter.value.keyword, testType: caseFilter.value.testType,
      priority: caseFilter.value.priority, parentId: caseFilter.value.parentId
    })
    caseList.value = r.list; caseTotal.value = r.total
  } catch { caseList.value = []; caseTotal.value = 0 }
  caseLoading.value = false
}
function openCaseDialog(c?: TestCase) {
  caseForm.value = c ? {
    id: c.id, title: c.title, testType: c.testType, priority: c.priority,
    description: c.description ?? '', relatedFiles: c.relatedFiles ?? '', relatedApis: c.relatedApis ?? '',
    tags: c.tags ?? '', parentId: c.parentId, steps: parseSteps(c.steps)
  } : { id: undefined, title: '', testType: 'FUNCTIONAL', priority: 'P2', description: '', relatedFiles: '', relatedApis: '', tags: '', parentId: undefined, steps: [{ action: 'open', target: '', value: '', expected: '' }] }
  caseForm.value.steps.forEach(normalizeStep)
  caseDialog.value = true
}
function parseSteps(steps?: string): any[] {
  try { const parsed = JSON.parse(steps ?? '[]'); return Array.isArray(parsed) && parsed.length ? parsed : [{ action: 'open', target: '', value: '', expected: '' }] } catch { return [{ action: 'open', target: '', value: '', expected: '' }] }
}
/** 保证 http 步骤有 headers/assertions 默认结构（headers 用 {key,value} 数组便于编辑，保存时转对象）。 */
function normalizeStep(s: any) {
  if (s.action === 'http') {
    if (s.method === undefined) s.method = 'GET'
    if (!Array.isArray(s.headers)) {
      s.headers = Object.entries(s.headers ?? {}).map(([key, value]) => ({ key, value: String(value ?? '') }))
    }
    if (!Array.isArray(s.assertions)) s.assertions = [{ type: 'statusCode', expected: 200 }]
  }
}
function addStep() { caseForm.value.steps.push({ action: 'open', target: '', value: '', expected: '' }) }
function removeStep(i: number) { caseForm.value.steps.splice(i, 1) }
function addHttpHeader(s: any) { s.headers.push({ key: '', value: '' }) }
function removeHttpHeader(s: any, i: number | string) { s.headers.splice(Number(i), 1) }
function addAssertion(s: any) { s.assertions.push({ type: 'statusCode', expected: 200 }) }
function removeAssertion(s: any, i: number | string) { s.assertions.splice(Number(i), 1) }
async function saveCase() {
  try {
    // http 步骤的 headers 数组转对象；空步骤（无 url / 无 target）不参与保存
    const steps = caseForm.value.steps
      .filter((s: any) => s.action === 'http' ? !!s.url?.trim() : (s.target || s.value))
      .map((s: any) => {
        if (s.action !== 'http') return s
        const headers: Record<string, string> = {}
        ;(s.headers ?? []).forEach((h: any) => { if (h?.key?.trim()) headers[h.key.trim()] = String(h.value ?? '') })
        return { ...s, headers }
      })
    const payload: Record<string, any> = {
      title: caseForm.value.title, testType: caseForm.value.testType, priority: caseForm.value.priority,
      description: caseForm.value.description, relatedFiles: caseForm.value.relatedFiles,
      relatedApis: caseForm.value.relatedApis, tags: caseForm.value.tags,
      steps: JSON.stringify(steps)
    }
    if (caseForm.value.parentId) payload.parentId = caseForm.value.parentId
    if (caseForm.value.id) await testPlanApi.updateCase(project.value, caseForm.value.id, payload)
    else await testPlanApi.createCase(project.value, payload)
    ElMessage.success('用例已保存')
    caseDialog.value = false
    loadCases(); loadCaseTree()
  } catch { /* 拦截器已提示 */ }
}
async function deleteCase(row: TestCase) {
  try { await testPlanApi.deleteCase(project.value, row.id); ElMessage.success('已删除'); loadCases(); loadCaseTree() } catch { /* 拦截器已提示 */ }
}
async function openCaseDetail(row: TestCase) {
  try { caseDetail.value = await testPlanApi.getCase(project.value, row.id); caseDetailVisible.value = true } catch { /* 拦截器已提示 */ }
}
async function unlinkCaseBug(bugId: number) {
  if (!caseDetail.value) return
  try { await testPlanApi.unlinkCaseBug(project.value, caseDetail.value.id, bugId); ElMessage.success('已取消关联'); openCaseDetail(caseDetail.value) } catch { /* 拦截器已提示 */ }
}

// ==================== AI 用例生成 + 追溯矩阵（对标 MeterSphere） ====================
const aiGenVisible = ref(false)
const aiGenLoading = ref(false)
const aiGenEventOptions = ref<any[]>([])
const aiGenEventId = ref('')
const aiGenRequirementId = ref<number | null>(null)
const aiGenResult = ref<Record<string, any> | null>(null)
const aiGenSaving = ref<Record<string, any>>({})
const requirements = ref<any[]>([])

async function loadRequirements() {
  try { requirements.value = await client.get(`/pm/requirements`, { params: { projectKey: project.value } }) } catch { requirements.value = [] }
}
async function openAiGen() {
  aiGenVisible.value = true
  aiGenLoading.value = true
  aiGenEventId.value = ''; aiGenRequirementId.value = null; aiGenResult.value = null
  try {
    const data: any[] = await client.get(`/projects/${project.value}/timeline`, { params: { type: 'CODE_COMMIT', limit: 50 } })
    aiGenEventOptions.value = data ?? []
  } catch { aiGenEventOptions.value = [] }
  loadRequirements()
  aiGenLoading.value = false
}
async function runAiGen() {
  if (!aiGenEventId.value) { ElMessage.warning('请选择变更事件'); return }
  aiGenLoading.value = true
  aiGenResult.value = null
  try {
    aiGenResult.value = await testPlanApi.aiGenerateCase(project.value, {
      eventId: aiGenEventId.value,
      requirementId: aiGenRequirementId.value ?? undefined
    })
  } catch { /* 拦截器已提示 */ }
  aiGenLoading.value = false
}
function parseAiSteps(steps: any): any[] {
  try { const p = JSON.parse(steps ?? '[]'); return Array.isArray(p) ? p : [] } catch { return [] }
}
async function saveAiGeneratedCase(tc: any) {
  aiGenSaving.value[tc.title] = true
  try {
    await testPlanApi.createCase(project.value, {
      title: tc.title, testType: tc.testType ?? 'FUNCTIONAL', priority: tc.priority ?? 'P2',
      steps: tc.steps ?? '[]', requirementId: aiGenRequirementId.value ?? undefined
    })
    ElMessage.success('已保存为用例')
    loadCases(); loadCaseTree()
  } catch { /* 拦截器已提示 */ }
  aiGenSaving.value[tc.title] = false
}

// ---- 追溯矩阵 ----
const traceVisible = ref(false)
const traceLoading = ref(false)
const traceRequirementId = ref<number | null>(null)
const trace = ref<Record<string, any> | null>(null)
async function openTrace() {
  traceVisible.value = true
  traceRequirementId.value = null; trace.value = null
  loadRequirements()
}
async function runTrace() {
  if (!traceRequirementId.value) { ElMessage.warning('请选择需求'); return }
  traceLoading.value = true
  trace.value = null
  try { trace.value = await testPlanApi.traceMatrix(project.value, traceRequirementId.value) } catch { /* 拦截器已提示 */ }
  traceLoading.value = false
}

// ==================== 用例/计划执行（服务端执行器） ====================
const runDialog = ref(false)
const runResult = ref<RunCaseResult | null>(null)
const runLoadingCaseId = ref<number | null>(null)
const planRunDialog = ref(false)
const planRunResult = ref<RunPlanResult | null>(null)
const planRunLoadingId = ref<number | null>(null)

async function runCase(row: TestCase) {
  runLoadingCaseId.value = row.id
  try {
    runResult.value = await testPlanApi.runCase(project.value, row.id)
    runDialog.value = true
    loadCases(); loadExecutions()
  } catch { /* 拦截器已提示 */ }
  runLoadingCaseId.value = null
}
async function runPlan(plan: TestPlan) {
  planRunLoadingId.value = plan.id
  try {
    planRunResult.value = await testPlanApi.runPlan(project.value, plan.id)
    planRunDialog.value = true
    loadPlans()
  } catch { /* 拦截器已提示 */ }
  planRunLoadingId.value = null
}
const stepStatusColor = (s: string) => ({ PASSED: 'success', FAILED: 'danger', SKIPPED: 'info' } as any)[s] || 'info'
function stepRequestText(s: any) { return s.method ? `${s.method} ${s.url}` : `${s.type ?? ''} ${s.name ?? ''}` }
function stepFailInfo(s: any) {
  const msgs = (s.assertions ?? []).filter((a: any) => !a.passed).map((a: any) => a.message)
  if (msgs.length) return msgs.join('；')
  return s.error ?? ''
}
function copyEventId(id: string) { navigator.clipboard?.writeText(id).catch(() => {}) }

// ==================== Tab2 测试计划 ====================
const plans = ref<TestPlan[]>([])
const planLoading = ref(false)
const planDialog = ref(false)
const planForm = ref({ name: '', targetVersion: '', fromVersion: '' })
const planDetailVisible = ref(false)
const planDetail = ref<TestPlan | null>(null)
const planReportVisible = ref(false)
const planReport = ref<Record<string, any> | null>(null)
const addCaseVisible = ref(false)
const addCaseSelected = ref<number[]>([])
const versions = ref<string[]>([])

async function loadPlans() {
  planLoading.value = true
  try { plans.value = await testPlanApi.listPlans(project.value) } catch { plans.value = [] }
  planLoading.value = false
}
async function createPlan() {
  try {
    await testPlanApi.createPlan(project.value, planForm.value)
    ElMessage.success('计划已创建'); planDialog.value = false; loadPlans()
  } catch { /* 拦截器已提示 */ }
}
async function changePlanStatus(plan: TestPlan, to: string) {
  try { await testPlanApi.updatePlanStatus(project.value, plan.id, to); ElMessage.success(`计划已流转为 ${to}`); loadPlans() } catch { /* 拦截器已提示 */ }
}
async function openPlanDetail(plan: TestPlan) {
  try { planDetail.value = await testPlanApi.getPlan(project.value, plan.id); planDetailVisible.value = true } catch { /* 拦截器已提示 */ }
}
async function executeItem(item: any, status: string) {
  if (!planDetail.value) return
  try {
    await testPlanApi.executePlanItem(project.value, planDetail.value.id, item.id, { status, executor: 'QA' })
    ElMessage.success(`已标记 ${status}`)
    openPlanDetail(planDetail.value); loadPlans()
  } catch { /* 拦截器已提示 */ }
}
async function showPlanReport(plan: TestPlan) {
  try { planReport.value = await testPlanApi.getPlanReport(project.value, plan.id); planReportVisible.value = true } catch { /* 拦截器已提示 */ }
}
async function addCasesToPlan() {
  if (!planDetail.value) return
  try {
    await testPlanApi.addPlanItems(project.value, planDetail.value.id, addCaseSelected.value)
    ElMessage.success('用例已加入计划'); addCaseVisible.value = false; addCaseSelected.value = []
    openPlanDetail(planDetail.value); loadPlans()
  } catch { /* 拦截器已提示 */ }
}
async function removeItem(item: any) {
  if (!planDetail.value) return
  try { await testPlanApi.removePlanItem(project.value, planDetail.value.id, item.id); ElMessage.success('已移除'); openPlanDetail(planDetail.value); loadPlans() } catch { /* 拦截器已提示 */ }
}
async function reorderItem(item: any, dir: 'UP' | 'DOWN') {
  if (!planDetail.value) return
  try { await testPlanApi.reorderPlanItem(project.value, planDetail.value.id, item.id, dir); openPlanDetail(planDetail.value); loadPlans() } catch { /* 拦截器已提示 */ }
}

// ==================== Tab3 执行记录 ====================
const executions = ref<any[]>([])
const execTotal = ref(0)
const execPage = ref(1)
const execFilter = ref({ status: '' })
const execDialog = ref(false)
const execForm = ref({ testCaseId: undefined as number | undefined, status: 'PASSED', executor: 'QA', resultDetail: '' })
const standaloneCases = ref<TestCase[]>([])

async function loadExecutions() {
  try {
    const r = await testPlanApi.listExecutions(project.value, {
      page: execPage.value, pageSize: 20, status: execFilter.value.status
    })
    executions.value = r.list; execTotal.value = r.total
  } catch { executions.value = []; execTotal.value = 0 }
}
async function recordExecution() {
  try {
    await testPlanApi.recordExecution(project.value, execForm.value)
    ElMessage.success('执行已记录'); execDialog.value = false
    execForm.value = { testCaseId: undefined, status: 'PASSED', executor: 'QA', resultDetail: '' }
    loadExecutions()
  } catch { /* 拦截器已提示 */ }
}
async function loadStandaloneCases() {
  try {
    const r = await testPlanApi.listCases(project.value, { page: 1, pageSize: 100 })
    standaloneCases.value = r.list
  } catch { standaloneCases.value = [] }
}

// ==================== Tab4 质量看板 ====================
const recFrom = ref(''); const recTo = ref('')
const recommendation = ref<Record<string, any> | null>(null)
const recLoading = ref(false)
const readiness = ref<Record<string, any> | null>(null)
const gateVersion = ref('')
const gatePlanId = ref<number | undefined>(undefined)
const gateResult = ref<Record<string, any> | null>(null)
const gateHistory = ref<any[]>([])
const execTrend = ref<{ day: string; total: number; passed: number; failed: number }[]>([])
const bugTrendData = ref<{ day: string; p0: number; p1: number; p2: number; p3: number }[]>([])

async function loadVersions() {
  try {
    const r = await releaseApi.list(project.value)
    if (r?.length) {
      versions.value = r.map(v => v.version)
      recFrom.value = versions.value[Math.min(1, versions.value.length - 1)] ?? ''
      recTo.value = versions.value[0] ?? ''
      gateVersion.value = versions.value[0] ?? ''
    }
  } catch {}
}
async function generateRecommendation() {
  recLoading.value = true
  try {
    recommendation.value = await client.get(`/pm/test-recommendation`, {
      params: { projectKey: project.value, fromVersion: recFrom.value, toVersion: recTo.value }
    })
  } catch { recommendation.value = null }
  recLoading.value = false
}
async function generatePlan() {
  if (!recommendation.value) return
  try {
    const r = await testPlanApi.createPlanFromRecommendation(project.value, { fromVersion: recFrom.value, toVersion: recTo.value })
    ElMessage.success(`已生成测试计划 #${(r as any).planId}（${(r as any).itemCount} 个用例）`)
    activeTab.value = 'plans'; loadPlans()
  } catch { /* 拦截器已提示 */ }
}
async function checkReleaseReadiness() {
  try { readiness.value = await client.get(`/pm/release-readiness`, { params: { projectKey: project.value, targetVersion: gateVersion.value } }) } catch { readiness.value = null }
}
async function runGate() {
  try {
    gateResult.value = await client.post(`/pm/quality-gate/check`, null, { params: { projectKey: project.value, targetVersion: gateVersion.value, planId: gatePlanId.value } })
    loadGateHistory()
  } catch { gateResult.value = null }
}
async function loadGateHistory() {
  try { gateHistory.value = await client.get(`/pm/quality-gate/history`, { params: { projectKey: project.value } }) } catch { gateHistory.value = [] }
}
// ---- 门禁规则配置（对标 SonarQube Quality Gate，可配）----
const gateRulesVisible = ref(false)
const gateRules = ref<any[]>([])
const gateRuleDialogVisible = ref(false)
const gateRuleSaving = ref(false)
const gateRuleForm = ref({ ruleKey: '', name: '', description: '', enabled: true, weight: 10, threshold: '{}', existingKey: '' })
async function loadGateRules() {
  try { gateRules.value = await client.get('/quality-gate/rules', { params: { projectKey: project.value } }) } catch { gateRules.value = [] }
}
async function openGateRuleEdit(rule?: any) {
  if (rule) {
    gateRuleForm.value = {
      ruleKey: rule.ruleKey, name: rule.name, description: rule.description ?? '',
      enabled: !!rule.enabled, weight: rule.weight ?? 10,
      threshold: typeof rule.threshold === 'string' ? rule.threshold : JSON.stringify(rule.threshold ?? '{}'),
      existingKey: rule.ruleKey
    }
  } else {
    gateRuleForm.value = { ruleKey: '', name: '', description: '', enabled: true, weight: 10, threshold: '{}', existingKey: '' }
  }
  gateRuleDialogVisible.value = true
}
/** 行内启停（对全局规则自动生成项目级覆盖，避免污染全局）。 */
async function editEnabled(rule: any, v: boolean) {
  try {
    await client.post('/quality-gate/rules', {
      ruleKey: rule.ruleKey, name: rule.name, description: rule.description ?? '',
      enabled: v, weight: rule.weight ?? 10,
      threshold: typeof rule.threshold === 'string' ? rule.threshold : JSON.stringify(rule.threshold ?? '{}')
    }, { params: { projectKey: project.value } })
    ElMessage.success('已更新启用状态')
    loadGateRules(); loadGateHistory()
  } catch { loadGateRules() }
}
async function saveGateRule() {
  if (!gateRuleForm.value.ruleKey) { ElMessage.warning('请填写规则标识 ruleKey'); return }
  gateRuleSaving.value = true
  try {
    await client.post('/quality-gate/rules', gateRuleForm.value, { params: { projectKey: project.value } })
    ElMessage.success('门禁规则已保存')
    gateRulesVisible.value = false
    loadGateRules()
  } catch { /* 拦截器已提示 */ }
  gateRuleSaving.value = false
}
async function deleteGateRule(rule: any) {
  if (!rule.id || rule.scope !== 'PROJECT') { ElMessage.info('全局默认规则不可删除，可覆盖调节'); return }
  try { await client.delete(`/quality-gate/rules/${rule.id}`, { params: { projectKey: project.value } }); ElMessage.success('已恢复全局默认'); loadGateRules() } catch { /* 拦截器已提示 */ }
}
async function loadTrends() {
  try {
    execTrend.value = await testPlanApi.executionTrend(project.value, 30)
    bugTrendData.value = await testPlanApi.bugTrend(project.value, 30)
    await nextTick(); renderTrendCharts()
  } catch { /* 拦截器已提示 */ }
}
function renderTrendCharts() {
  // ---- 展示配置（仅颜色/字体/背景，随主题自适应） ----
  const isLight = document.documentElement.getAttribute('data-theme') === 'light'
  const AXIS = isLight ? '#909bb8' : '#5c6a8a'
  const SPLIT = isLight ? 'rgba(15,23,42,0.07)' : 'rgba(255,255,255,0.05)'
  const C = { primary: '#6d7cff', violet: '#a78bfa', cyan: '#38e1ff', ok: '#34d399', warn: '#fbbf24', danger: '#fb7185' }
  const tooltip = {
    trigger: 'axis',
    backgroundColor: '#141a2e',
    borderColor: 'rgba(255,255,255,.16)',
    textStyle: { color: '#e8edf9', fontSize: 12 },
    axisPointer: { lineStyle: { color: 'rgba(255,255,255,0.18)' } }
  }
  const el1 = document.getElementById('exec-trend')
  if (el1) {
    const e = echarts.getInstanceByDom(el1); if (e) e.dispose()
    echarts.init(el1).setOption({
      grid: { left: 40, right: 16, top: 32, bottom: 28 },
      tooltip,
      xAxis: { type: 'category', data: execTrend.value.map(d => d.day.substring(5)), axisLabel: { fontSize: 10, color: AXIS }, axisLine: { lineStyle: { color: SPLIT } }, axisTick: { show: false } },
      yAxis: [
        { type: 'value', minInterval: 1, axisLabel: { fontSize: 10, color: AXIS }, splitLine: { lineStyle: { color: SPLIT } } },
        { type: 'value', min: 0, max: 100, axisLabel: { fontSize: 10, color: AXIS }, splitLine: { show: false } }
      ],
      legend: { data: ['执行数', '通过率'], top: 0, textStyle: { fontSize: 11, color: AXIS }, icon: 'roundRect', itemWidth: 12, itemHeight: 4 },
      series: [
        {
          name: '执行数', type: 'bar', data: execTrend.value.map(d => d.total), barMaxWidth: 16,
          itemStyle: {
            borderRadius: [4, 4, 0, 0],
            color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(109,124,255,0.85)' }, { offset: 1, color: 'rgba(109,124,255,0.32)' }] }
          }
        },
        {
          name: '通过率', type: 'line', yAxisIndex: 1, smooth: true, data: execTrend.value.map(d => d.total ? Math.round(d.passed * 100 / d.total) : null),
          lineStyle: { color: C.ok, width: 2 }, itemStyle: { color: C.ok },
          areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(52,211,153,0.28)' }, { offset: 1, color: 'rgba(52,211,153,0)' }] } }
        }
      ]
    })
  }
  const el2 = document.getElementById('bug-trend')
  if (el2) {
    const e = echarts.getInstanceByDom(el2); if (e) e.dispose()
    echarts.init(el2).setOption({
      grid: { left: 40, right: 16, top: 32, bottom: 28 },
      tooltip,
      xAxis: { type: 'category', data: bugTrendData.value.map(d => d.day.substring(5)), axisLabel: { fontSize: 10, color: AXIS }, axisLine: { lineStyle: { color: SPLIT } }, axisTick: { show: false } },
      yAxis: { type: 'value', minInterval: 1, axisLabel: { fontSize: 10, color: AXIS }, splitLine: { lineStyle: { color: SPLIT } } },
      legend: { data: ['P0', 'P1', 'P2', 'P3'], top: 0, textStyle: { fontSize: 11, color: AXIS }, icon: 'circle', itemWidth: 8, itemHeight: 8 },
      series: [
        { name: 'P0', type: 'line', smooth: true, data: bugTrendData.value.map(d => d.p0), lineStyle: { color: C.danger, width: 2 }, itemStyle: { color: C.danger } },
        { name: 'P1', type: 'line', smooth: true, data: bugTrendData.value.map(d => d.p1), lineStyle: { color: C.warn, width: 2 }, itemStyle: { color: C.warn } },
        { name: 'P2', type: 'line', smooth: true, data: bugTrendData.value.map(d => d.p2), lineStyle: { color: C.primary, width: 2 }, itemStyle: { color: C.primary } },
        { name: 'P3', type: 'line', smooth: true, data: bugTrendData.value.map(d => d.p3), lineStyle: { color: C.violet, width: 2 }, itemStyle: { color: C.violet } }
      ]
    })
  }
}

// ==================== Tab5 缺陷追踪 ====================
const bugs = ref<any[]>([])
const bugFilter = ref({ status: '', severity: '' })
const bugDialog = ref(false)
const bugForm = ref({ title: '', severity: 'P2', foundBy: '', foundVersion: '', description: '', requirementId: undefined })
const bugDetailVisible = ref(false)
const bugDetail = ref<Record<string, any> | null>(null)
const jiraVisible = ref(false)
const jiraForm = ref({ baseUrl: '', username: '', apiToken: '', jiraProjectKey: '', issueType: 'Bug', enabled: false, statusMap: {} as Record<string, string> })
const jiraSyncing = ref(false)

async function loadBugs() {
  try {
    bugs.value = await client.get(`/pm/bugs`, { params: { projectKey: project.value, status: bugFilter.value.status, severity: bugFilter.value.severity } })
  } catch { bugs.value = [] }
}
async function createBug() {
  try {
    await client.post(`/pm/bugs?projectKey=${project.value}`, bugForm.value)
    ElMessage.success('缺陷已创建'); bugDialog.value = false
    bugForm.value = { title: '', severity: 'P2', foundBy: '', foundVersion: '', description: '', requirementId: undefined }
    loadBugs()
  } catch { /* 拦截器已提示 */ }
}
async function transitionBug(row: any, to: string) {
  let fixedVersion: string | undefined
  if (to === 'FIXED') {
    fixedVersion = (await ElMessageBoxInput()) ?? undefined
  }
  try { await bugApi.transition(row.id, to, fixedVersion); ElMessage.success(`已流转为 ${to}`); loadBugs() } catch { /* 拦截器已提示 */ }
}
function ElMessageBoxInput(): Promise<string | null> {
  return new Promise((resolve) => {
    const input = window.prompt('填写修复版本（fixed version）:', '')
    resolve(input)
  })
}
async function openBugDetail(row: any) {
  try { bugDetail.value = await bugApi.detail(row.id); bugDetailVisible.value = true } catch { /* 拦截器已提示 */ }
}
async function linkBugCase() {
  if (!bugDetail.value) return
  const input = window.prompt('输入要关联的用例 ID:', '')
  if (!input) return
  try { await bugApi.linkTestCase(bugDetail.value.id, Number(input)); ElMessage.success('已关联'); openBugDetail(bugDetail.value) } catch { /* 拦截器已提示 */ }
}
async function unlinkBugCase(caseId: number) {
  if (!bugDetail.value) return
  try { await bugApi.unlinkTestCase(bugDetail.value.id, caseId); ElMessage.success('已取消关联'); openBugDetail(bugDetail.value) } catch { /* 拦截器已提示 */ }
}
async function loadJiraConfig() {
  try {
    const cfg = await jiraApi.getConfig(project.value)
    jiraForm.value = {
      baseUrl: cfg.baseUrl ?? '', username: cfg.username ?? '', apiToken: '', jiraProjectKey: cfg.jiraProjectKey ?? '',
      issueType: cfg.issueType ?? 'Bug', enabled: !!cfg.enabled, statusMap: cfg.statusMap ?? {}
    }
  } catch { /* 拦截器已提示 */ }
}
async function saveJiraConfig() {
  try {
    await jiraApi.saveConfig(project.value, jiraForm.value)
    ElMessage.success('Jira 配置已保存'); jiraVisible.value = false
  } catch { /* 拦截器已提示 */ }
}
async function syncJira() {
  jiraSyncing.value = true
  try {
    const r = await jiraApi.sync(project.value)
    ElMessage.success(`Jira 同步完成，导入 ${(r as any).imported} 条`)
    loadBugs(); loadJiraConfig()
  } catch { /* 拦截器已提示 */ }
  jiraSyncing.value = false
}

// ==================== Tab6 环境自检 ====================
const serverDiag = ref<Record<string, any> | null>(null)
const credDiag = ref<Record<string, any> | null>(null)
const sampleDiag = ref<Record<string, any> | null>(null)
const sampleSending = ref(false)
const diagLoading = ref(false)
const samplePollTimer = ref<number | null>(null)
const summaryPolled = ref(false)

function stopSamplePoll() {
  if (samplePollTimer.value !== null) { window.clearInterval(samplePollTimer.value); samplePollTimer.value = null }
}
async function diagServer() {
  try { serverDiag.value = await diagnosticsApi.serverCheck(project.value) } catch { serverDiag.value = null }
}
async function diagCredential() {
  try { credDiag.value = await diagnosticsApi.credentialCheck(project.value) } catch { credDiag.value = null }
}
async function diagSendSample() {
  stopSamplePoll()
  sampleSending.value = true
  sampleDiag.value = null
  summaryPolled.value = false
  try {
    sampleDiag.value = await diagnosticsApi.sendSample(project.value)
    // AI 摘要异步生成：PENDING 时轮询直至 DONE/FAILED 或超时
    if (sampleDiag.value?.ok && sampleDiag.value?.summaryStatus === 'PENDING') {
      const eventId = sampleDiag.value.eventId
      let tries = 0
      samplePollTimer.value = window.setInterval(async () => {
        tries++
        try {
          const s = await diagnosticsApi.sampleStatus(project.value, eventId)
          sampleDiag.value = { ...sampleDiag.value, ...s }
          if (s.summaryStatus !== 'PENDING' || tries >= 30) {
            stopSamplePoll(); summaryPolled.value = true
          }
        } catch { stopSamplePoll(); summaryPolled.value = true }
      }, 2000)
    } else {
      summaryPolled.value = true
    }
  } catch { sampleDiag.value = null }
  sampleSending.value = false
}
async function runAllDiag() {
  diagLoading.value = true
  await diagServer()
  await diagCredential()
  await diagSendSample()
  diagLoading.value = false
}
const diagOk = (d: any, key: string) => !!(d?.[key]?.ok)

// 项目切换联动
watch(project, () => {
  stopSamplePoll()
  serverDiag.value = credDiag.value = sampleDiag.value = null
  activeTab.value = 'quality'
  loadAll()
})

onUnmounted(stopSamplePoll)

// ==================== 展示辅助（仅样式/文案，不参与业务逻辑） ====================
const tagToPill = (t: string) => ({ success: 'pill-ok', danger: 'pill-danger', warning: 'pill-warn', info: 'pill-muted', primary: 'pill-cyan' } as any)[t] || 'pill-muted'
const sevClass = (s: string) => ({ danger: 'sev-0', error: 'sev-1', warning: 'sev-2', info: 'sev-3' } as any)[sevColor(s)] || 'sev-3'
const openBugs = computed(() => bugs.value.filter((b: any) => !['VERIFIED', 'CLOSED'].includes(b.status)).length)
const bugOpenRatio = computed(() => bugs.value.length ? Math.round(openBugs.value * 100 / bugs.value.length) : 0)
const planAvgPass = computed(() => plans.value.length ? Math.round(plans.value.reduce((s: number, p: any) => s + (p.passRate ?? 0), 0) / plans.value.length) : 0)
const execPassPct = computed(() => {
  const n = executions.value.length
  return n ? Math.round(executions.value.filter((e: any) => e.status === 'PASSED').length * 100 / n) : 0
})
const sparkData = { cases: [2, 3, 5, 4, 6, 8, 7], plans: [3, 3, 4, 6, 5, 7, 8], bugs: [5, 4, 6, 3, 5, 4, 3], exec: [4, 5, 4, 7, 6, 8, 9] }

async function loadAll() {
  loadVersions(); loadGateHistory(); loadTrends(); loadBugs()
  loadCases(); loadCaseTree(); loadPlans(); loadExecutions(); loadStandaloneCases(); loadJiraConfig()
}

onMounted(loadAll)
</script>

<template>
  <div>
    <!-- ======== 页面横幅 ======== -->
    <div class="qa-hero rise" style="--d: 0s">
      <div>
        <h2 class="qa-hero-title"><span class="et-tic"><el-icon :size="16"><Monitor /></el-icon></span>QA 测试面板</h2>
        <p class="qa-hero-sub">用例编排 · 计划执行 · 质量门禁 · 缺陷追踪 · 环境自检，一站式测试质量工作台</p>
      </div>
      <div class="qa-hero-right">
        <el-button :icon="Refresh" @click="loadAll">刷新数据</el-button>
        <el-button :icon="Setting" @click="jiraVisible = true; loadJiraConfig()">Jira 同步设置</el-button>
        <el-button type="primary" :icon="Plus" @click="openCaseDialog(); activeTab = 'cases'">新建用例</el-button>
      </div>
    </div>

    <!-- ======== 顶部统计 ======== -->
    <div class="qa-stats">
      <div class="qa-stat-item rise" style="--d: .08s">
        <StatCard label="测试用例" :value="caseTotal" suffix="条" :icon="Document" color="#6d7cff"
                  :trend="sparkData.cases" delta="模块目录" delta-dir="up" foot="按模块 / 类型 / 优先级筛选" />
      </div>
      <div class="qa-stat-item rise" style="--d: .14s">
        <StatCard label="测试计划" :value="plans.length" suffix="个" :icon="Calendar" color="#a78bfa"
                  :trend="sparkData.plans" :delta="planAvgPass + '%'" delta-dir="up" foot="平均通过率 · 可一键生成回归计划" />
      </div>
      <div class="qa-stat-item rise" style="--d: .20s">
        <StatCard label="缺陷" :value="openBugs" suffix="个" :icon="Warning" color="#fb7185"
                  :trend="sparkData.bugs" :delta="bugOpenRatio + '%'" :delta-dir="bugOpenRatio > 50 ? 'up' : 'down'" foot="未处理缺陷占比 · Jira 双向同步" />
      </div>
      <div class="qa-stat-item rise" style="--d: .26s">
        <StatCard label="执行记录" :value="execTotal" suffix="条" :icon="VideoPlay" color="#38e1ff"
                  :trend="sparkData.exec" :delta="execPassPct + '%'" delta-dir="up" foot="近 30 日执行留痕 · 失败可回溯" />
      </div>
    </div>

    <PageCard no-padding class="qa-tabs-card rise" style="margin-top: 18px; --d: .32s">
      <el-tabs v-model="activeTab" class="page-tabs">
        <!-- ============ 测试用例 ============ -->
        <el-tab-pane name="cases">
          <template #label><span class="tab-label"><el-icon :size="14"><Document /></el-icon>测试用例</span></template>
          <div class="tab-content cases-layout">
            <div class="case-tree">
              <div class="tree-head"><span class="tree-head-ic"><el-icon :size="13"><FolderOpened /></el-icon></span>用例模块</div>
              <el-tree :data="caseTree" :props="{ label: 'title', children: 'children' }" node-key="id"
                       default-expand-all highlight-current @node-click="(d: any) => { caseFilter.parentId = d.nodeType === 'MODULE' ? d.id : undefined; casePage = 1; loadCases() }">
                <template #default="{ data }">
                  <span class="tree-node">
                    <span class="node-dot" :class="data.nodeType === 'MODULE' ? 'node-mod' : 'node-case'"></span>
                    <span class="tree-title">{{ data.title }}</span>
                    <span v-if="data.nodeType === 'MODULE'" class="tree-count">{{ data.childCount }}</span>
                  </span>
                </template>
              </el-tree>
            </div>
            <div class="case-list">
              <div class="toolbar-row">
                <el-input v-model="caseFilter.keyword" placeholder="搜索标题/标签" size="small" style="width: 160px" @keyup.enter="loadCases" />
                <el-select v-model="caseFilter.testType" placeholder="类型" clearable size="small" style="width: 110px">
                  <el-option v-for="t in ['FUNCTIONAL','REGRESSION','PERF','SECURITY','API','UI']" :key="t" :label="typeLabel(t)" :value="t" />
                </el-select>
                <el-select v-model="caseFilter.priority" placeholder="优先级" clearable size="small" style="width: 100px">
                  <el-option v-for="p in ['P0','P1','P2','P3']" :key="p" :value="p" />
                </el-select>
                <el-button size="small" @click="casePage = 1; loadCases()">查询</el-button>
                <el-button size="small" type="primary" :icon="VideoPlay" @click="openAiGen">AI 生成用例</el-button>
                <el-button size="small" :icon="TrendCharts" @click="openTrace">需求追溯矩阵</el-button>
              </div>
              <el-table :data="caseList" stripe size="small" v-loading="caseLoading" class="neo-table">
                <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
                <el-table-column label="类型" width="80"><template #default="{ row }"><span class="type-tag">{{ typeLabel(row.testType) }}</span></template></el-table-column>
                <el-table-column label="优先级" width="80"><template #default="{ row }"><span class="sev" :class="sevClass(row.priority)">{{ row.priority }}</span></template></el-table-column>
                <el-table-column label="最近执行" width="104"><template #default="{ row }"><span v-if="row.lastStatus" class="pill" :class="tagToPill(execStatusColor(row.lastStatus))">{{ row.lastStatus }}</span><span v-else style="color:var(--et-text-muted)">—</span></template></el-table-column>
                <el-table-column label="操作" width="232">
                  <template #default="{ row }">
                    <el-button v-if="row.runnable" size="small" text type="success" class="ops-btn" :loading="runLoadingCaseId === row.id" @click="runCase(row)">执行</el-button>
                    <el-tooltip v-else content="含浏览器 UI 步骤，无法在服务端执行（请手动执行）" placement="top">
                      <el-button size="small" text disabled>执行</el-button>
                    </el-tooltip>
                    <el-button size="small" text type="primary" class="ops-btn" @click="openCaseDetail(row)">详情</el-button>
                    <el-button size="small" text class="ops-btn" @click="openCaseDialog(row)">编辑</el-button>
                    <el-button size="small" text type="danger" class="ops-btn" @click="deleteCase(row)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-pagination v-model:current-page="casePage" :total="caseTotal" :page-size="casePageSize"
                             layout="total, prev, pager, next" class="table-pager" @current-change="loadCases" />
            </div>
          </div>
        </el-tab-pane>

        <!-- ============ 测试计划 ============ -->
        <el-tab-pane name="plans">
          <template #label><span class="tab-label"><el-icon :size="14"><Calendar /></el-icon>测试计划</span></template>
          <div class="tab-content">
            <div class="toolbar-row">
              <el-button type="primary" size="small" :icon="Plus" @click="planDialog = true">新建计划</el-button>
              <el-button size="small" :icon="Refresh" @click="loadPlans">刷新</el-button>
              <span class="bar-hint">计划执行进度与通过率一览</span>
            </div>
            <el-row :gutter="16">
              <el-col v-for="(p, i) in plans" :key="p.id" :xs="24" :sm="12" :md="8" style="margin-bottom: 12px">
                <div class="plan-card-box rise" :style="{ '--d': (0.2 + Math.min(i, 4) * 0.05) + 's' }" @click="openPlanDetail(p)">
                  <div class="plan-top">
                    <span class="plan-name">{{ p.name }}</span>
                    <span class="pill" :class="tagToPill(planStatusColor(p.status))">{{ p.status }}</span>
                  </div>
                  <div class="plan-meta">{{ p.targetVersion ?? '—' }} · {{ p.total ?? 0 }} 个用例 · 通过 {{ p.passRate }}%</div>
                  <div class="plan-progress">
                    <div class="et-bar"><i :style="{ width: (p.progress ?? 0) + '%', background: (p.failed ?? 0) > 0 ? 'linear-gradient(90deg, #fb7185, #fbbf24)' : 'linear-gradient(90deg, #34d399, #38e1ff)' }"></i></div>
                    <span class="plan-progress-num">{{ p.progress ?? 0 }}%</span>
                  </div>
                  <div class="plan-actions">
                    <el-button size="small" text type="success" class="ops-btn" :loading="planRunLoadingId === p.id" @click.stop="runPlan(p)">一键执行</el-button>
                    <el-button v-for="t in planTransitions[p.status] ?? []" :key="t" size="small" text type="primary" class="ops-btn" @click.stop="changePlanStatus(p, t)">
                      {{ { RUNNING: '开始执行', DONE: '完成', DRAFT: '撤回', }[t] ?? t }}
                    </el-button>
                    <el-button size="small" text type="primary" class="ops-btn" @click.stop="showPlanReport(p)">报告</el-button>
                    <el-button size="small" text type="danger" class="ops-btn" @click.stop="testPlanApi.deletePlan(project, p.id).then(() => { ElMessage.success('已删除'); loadPlans() })">删除</el-button>
                  </div>
                </div>
              </el-col>
            </el-row>
            <el-empty v-if="!planLoading && plans.length === 0" description="暂无测试计划（可在质量看板一键生成）" :image-size="70" />
          </div>
        </el-tab-pane>

        <!-- ============ 执行记录 ============ -->
        <el-tab-pane name="executions">
          <template #label><span class="tab-label"><el-icon :size="14"><VideoPlay /></el-icon>执行记录</span></template>
          <div class="tab-content">
            <div class="toolbar-row">
              <el-select v-model="execFilter.status" placeholder="状态" clearable size="small" style="width: 120px">
                <el-option v-for="s in ['PASSED','FAILED','BLOCKED','SKIPPED']" :key="s" :value="s" />
              </el-select>
              <el-button size="small" @click="execPage = 1; loadExecutions()">查询</el-button>
              <el-button type="primary" size="small" :icon="Plus" @click="loadStandaloneCases(); execDialog = true">记录执行</el-button>
            </div>
            <el-table :data="executions" stripe size="small" class="neo-table">
              <el-table-column prop="executedAt" label="时间" width="170" />
              <el-table-column prop="title" label="用例" min-width="200" show-overflow-tooltip />
              <el-table-column label="来源" width="150">
                <template #default="{ row }">
                  <span v-if="row.source === 'PLAN'" class="pill pill-warn">计划: {{ row.planTitle }}</span>
                  <span v-else class="pill pill-muted">独立执行</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="104"><template #default="{ row }"><span class="pill" :class="tagToPill(execStatusColor(row.status))">{{ row.status }}</span></template></el-table-column>
              <el-table-column prop="executor" label="执行人" width="90" />
              <el-table-column prop="resultDetail" label="结果说明" min-width="180" show-overflow-tooltip />
            </el-table>
            <el-pagination v-model:current-page="execPage" :total="execTotal" :page-size="20"
                           layout="total, prev, pager, next" class="table-pager" @current-change="loadExecutions" />
          </div>
        </el-tab-pane>

        <!-- ============ 质量看板 ============ -->
        <el-tab-pane name="quality">
          <template #label><span class="tab-label"><el-icon :size="14"><TrendCharts /></el-icon>质量看板</span></template>
          <div class="tab-content">
            <div class="toolbar-row">
              <el-select v-model="recFrom" size="small" style="width: 130px"><el-option v-for="v in versions" :key="v" :value="v" /></el-select>
              <span class="arrow-sep">→</span>
              <el-select v-model="recTo" size="small" style="width: 130px"><el-option v-for="v in versions" :key="v" :value="v" /></el-select>
              <el-button type="primary" size="small" @click="generateRecommendation" :loading="recLoading">生成推荐</el-button>
              <el-button size="small" type="warning" :disabled="!recommendation" @click="generatePlan">生成本轮计划</el-button>
            </div>

            <el-row v-if="recommendation" :gutter="16" style="margin-bottom: 12px">
              <el-col :xs="12" :sm="6"><StatCard label="推荐用例" :value="recommendation.totalCount ?? 0" color="#6d7cff" foot="版本区间推荐范围" /></el-col>
              <el-col :xs="12" :sm="6"><StatCard label="P0 用例" :value="recommendation.p0Count ?? 0" color="#fb7185" foot="高危用例优先覆盖" /></el-col>
              <el-col :xs="12" :sm="6"><StatCard label="回归用例" :value="recommendation.regressionCount ?? 0" color="#34d399" foot="回归风险点集合" /></el-col>
              <el-col :xs="12" :sm="6"><StatCard label="风险等级" :value="recommendation.riskLevel ?? '—'" color="#fbbf24" foot="发布前风险评级" /></el-col>
            </el-row>
            <el-alert v-if="recommendation" type="info" :closable="false" :title="recommendation.regressionScope" style="margin-bottom: 12px" />

            <el-divider content-position="left">发布准入</el-divider>
            <div class="toolbar-row">
              <el-select v-model="gateVersion" size="small" style="width: 130px"><el-option v-for="v in versions" :key="v" :value="v" /></el-select>
              <el-select v-model="gatePlanId" placeholder="本轮计划（可选）" clearable size="small" style="width: 200px">
                <el-option v-for="p in plans" :key="p.id" :label="p.name" :value="p.id" />
              </el-select>
              <el-button type="primary" size="small" @click="runGate">执行质量门禁</el-button>
              <el-button size="small" @click="checkReleaseReadiness">发布准入</el-button>
              <el-button size="small" :icon="Setting" @click="loadGateRules(); gateRulesVisible = true">门禁规则配置</el-button>
            </div>
            <el-alert v-if="readiness" style="margin-bottom: 12px" :type="readiness.ready ? 'success' : 'error'" :closable="false" :title="readiness.verdict ?? readiness.reason" />
            <el-alert v-if="gateResult" style="margin-bottom: 12px" :type="gateResult.passed ? 'success' : 'error'" :closable="false" :title="gateResult.verdict" />

            <el-row v-if="gateResult" :gutter="16">
              <el-col :xs="24" :md="12">
                <PageCard title="门禁明细" :icon="Setting" style="margin-bottom: 12px">
                  <div v-for="(c, key) in gateResult.checks" :key="key" class="gate-check">
                    <div class="gate-check-head">
                      <span class="check-ic" :class="c.passed ? 'ic-ok' : 'ic-bad'">{{ c.passed ? '✓' : '✕' }}</span>
                      <span class="gate-check-name">{{ ({ openBlockerBugs: '阻塞缺陷', failedTests: '失败用例', unacknowledgedBreaks: '破坏性变更', testCoverage: '测试覆盖', riskScore: '风险评分' } as any)[key] || key }}</span>
                      <span class="gate-check-value">{{ c.value }}{{ key === 'failedTests' && c.scope ? '（' + c.scope + '）' : '' }}</span>
                      <span class="gate-check-weight">{{ c.weight }}分</span>
                    </div>
                    <div class="gate-check-msg">{{ c.message }}</div>
                  </div>
                </PageCard>
              </el-col>
              <el-col :xs="24" :md="12">
                <PageCard title="门禁历史" :icon="Warning" style="margin-bottom: 12px">
                  <el-table :data="gateHistory" size="small" class="neo-table">
                    <el-table-column prop="targetVersion" label="版本" width="100" />
                    <el-table-column label="状态" width="100"><template #default="{ row }"><span class="pill" :class="row.status === 'PASSED' ? 'pill-ok' : 'pill-danger'">{{ row.status }}</span></template></el-table-column>
                    <el-table-column prop="checkedBy" label="执行人" width="80" />
                    <el-table-column prop="checkedAt" label="时间" width="170" />
                  </el-table>
                </PageCard>
              </el-col>
            </el-row>

            <el-divider content-position="left">质量趋势（近 30 天）</el-divider>
            <el-row :gutter="16">
              <el-col :xs="24" :md="12">
                <div class="chart-box">
                  <div class="chart-box-head">
                    <span class="et-tic"><el-icon :size="14"><TrendCharts /></el-icon></span>
                    <div><div class="chart-box-title">执行趋势</div><div class="chart-box-sub">执行数 / 通过率走势</div></div>
                  </div>
                  <div id="exec-trend" style="height: 240px" />
                </div>
              </el-col>
              <el-col :xs="24" :md="12">
                <div class="chart-box">
                  <div class="chart-box-head">
                    <span class="et-tic"><el-icon :size="14"><Warning /></el-icon></span>
                    <div><div class="chart-box-title">缺陷趋势</div><div class="chart-box-sub">各严重度新增走势</div></div>
                  </div>
                  <div id="bug-trend" style="height: 240px" />
                </div>
              </el-col>
            </el-row>
          </div>
        </el-tab-pane>

        <!-- ============ 缺陷追踪 ============ -->
        <el-tab-pane name="bugs">
          <template #label><span class="tab-label"><el-icon :size="14"><Warning /></el-icon>缺陷追踪</span></template>
          <div class="tab-content">
            <div class="toolbar-row">
              <el-select v-model="bugFilter.status" placeholder="状态" clearable size="small" style="width: 130px">
                <el-option v-for="s in ['OPEN','IN_PROGRESS','FIXED','VERIFIED','CLOSED','REOPENED']" :key="s" :value="s" />
              </el-select>
              <el-select v-model="bugFilter.severity" placeholder="严重度" clearable size="small" style="width: 110px">
                <el-option v-for="s in ['P0','P1','P2','P3']" :key="s" :value="s" />
              </el-select>
              <el-button size="small" @click="loadBugs">查询</el-button>
              <el-button type="primary" size="small" :icon="Plus" @click="bugDialog = true">提 Bug</el-button>
            </div>
            <el-table :data="bugs" stripe size="small" class="neo-table">
              <el-table-column label="严重度" width="80"><template #default="{ row }"><span class="sev" :class="sevClass(row.severity)">{{ row.severity }}</span></template></el-table-column>
              <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
              <el-table-column label="状态" width="120"><template #default="{ row }"><span class="pill" :class="tagToPill(statusColor(row.status))">{{ row.status }}</span></template></el-table-column>
              <el-table-column label="流转" width="170">
                <template #default="{ row }">
                  <el-dropdown v-if="(bugTransitions[row.status] ?? []).length" trigger="click" @command="(t: string) => transitionBug(row, t)">
                    <el-button size="small" text type="primary" class="ops-btn">流转 ▾</el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item v-for="t in bugTransitions[row.status]" :key="t" :command="t">{{ t }}</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </template>
              </el-table-column>
              <el-table-column prop="found_by" label="发现人" width="90" />
              <el-table-column label="操作" width="90"><template #default="{ row }"><el-button size="small" text type="primary" class="ops-btn" @click="openBugDetail(row)">详情</el-button></template></el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <!-- ============ 环境自检 ============ -->
        <el-tab-pane name="selfcheck">
          <template #label><span class="tab-label"><el-icon :size="14"><Monitor /></el-icon>环境自检</span></template>
          <div class="tab-content">
            <div class="toolbar-row">
              <el-button type="primary" size="small" :loading="diagLoading" @click="runAllDiag">一键自检</el-button>
              <span class="bar-hint">验证 服务器 → OpenAPI 凭证 → 全链路样例事件（HMAC 签名 → Kafka → 入库 → 时间线）</span>
            </div>
            <el-row :gutter="16">
              <el-col :xs="24" :md="8">
                <PageCard title="服务端状态" :icon="Monitor" style="margin-bottom: 12px">
                  <div v-if="!serverDiag" class="diag-empty">未检测</div>
                  <template v-else>
                    <div class="gate-check">
                      <div class="gate-check-head">
                        <span class="check-ic" :class="diagOk(serverDiag, 'db') ? 'ic-ok' : 'ic-bad'">{{ diagOk(serverDiag, 'db') ? '✓' : '✕' }}</span>
                        <span class="gate-check-name">数据库</span>
                        <span class="gate-check-value">{{ serverDiag.db.latencyMs != null ? serverDiag.db.latencyMs + 'ms' : '' }}</span>
                      </div>
                      <div class="gate-check-msg">{{ serverDiag.db.message ?? '连接正常' }}</div>
                    </div>
                    <div class="gate-check">
                      <div class="gate-check-head">
                        <span class="check-ic" :class="diagOk(serverDiag, 'kafka') ? 'ic-ok' : 'ic-bad'">{{ diagOk(serverDiag, 'kafka') ? '✓' : '✕' }}</span>
                        <span class="gate-check-name">Kafka</span>
                        <span class="gate-check-value">{{ serverDiag.serverTime }}</span>
                      </div>
                      <div class="gate-check-msg">{{ serverDiag.kafka.message }}</div>
                    </div>
                    <el-button size="small" text type="primary" class="ops-btn" @click="diagServer">重新检测</el-button>
                  </template>
                </PageCard>
              </el-col>
              <el-col :xs="24" :md="8">
                <PageCard title="OpenAPI 凭证" :icon="Setting" style="margin-bottom: 12px">
                  <div v-if="!credDiag" class="diag-empty">未检测</div>
                  <template v-else>
                    <div class="gate-check" v-for="row in [
                      { label: '凭证存在', ok: credDiag.exists, msg: credDiag.apiKeyPrefix ?? '' },
                      { label: '状态', ok: credDiag.status === 'ACTIVE', msg: credDiag.status ?? '' },
                      { label: 'HMAC 配置', ok: credDiag.hmacConfigured, msg: credDiag.hmacConfigured ? '已配置 hmac_key' : '未配置，请轮换凭证' },
                      { label: '签名自校验', ok: credDiag.signatureSelfTest, msg: credDiag.signatureSelfTest ? '签/验回环通过' : '签名校验失败' }
                    ]" :key="row.label">
                      <div class="gate-check-head">
                        <span class="check-ic" :class="row.ok ? 'ic-ok' : 'ic-bad'">{{ row.ok ? '✓' : '✕' }}</span>
                        <span class="gate-check-name">{{ row.label }}</span>
                        <span class="gate-check-value">{{ row.msg }}</span>
                      </div>
                    </div>
                    <el-button size="small" text type="primary" class="ops-btn" @click="diagCredential">重新检测</el-button>
                  </template>
                </PageCard>
              </el-col>
              <el-col :xs="24" :md="8">
                <PageCard title="全链路自检" :icon="TrendCharts" style="margin-bottom: 12px">
                  <el-button type="primary" size="small" :loading="sampleSending" @click="diagSendSample">发送样例事件</el-button>
                  <span v-if="sampleSending" class="diag-sending">正在走完整 ingestion 链路…</span>
                  <div v-if="sampleDiag" class="gate-check" style="margin-top: 10px">
                    <div class="gate-check-head">
                      <span class="check-ic" :class="sampleDiag.ok ? 'ic-ok' : 'ic-bad'">{{ sampleDiag.ok ? '✓' : '✕' }}</span>
                      <span class="gate-check-name">样例事件</span>
                      <code class="diag-event-id" title="点击复制" @click="copyEventId(sampleDiag.eventId)">{{ sampleDiag.eventId }}</code>
                    </div>
                    <div class="gate-check-msg" v-for="row in [
                      { label: '签名受理', ok: sampleDiag.accepted, msg: sampleDiag.duplicated ? '重复（幂等）' : 'HTTP ' + sampleDiag.httpStatus },
                      { label: '落库', ok: sampleDiag.persisted, msg: sampleDiag.persistAfterMs != null ? sampleDiag.persistAfterMs + 'ms' : '' },
                      { label: '时间线可见', ok: sampleDiag.timelineVisible, msg: '演化时间线可查询' },
                      { label: 'AI 摘要', ok: sampleDiag.summaryStatus === 'DONE', msg: sampleDiag.summaryStatus + (summaryPolled ? '' : '（生成中…）') }
                    ]" :key="row.label">
                      <span class="check-ic sm" :class="row.ok ? 'ic-ok' : 'ic-bad'">{{ row.ok ? '✓' : '✕' }}</span>
                      <span class="gate-check-name">{{ row.label }}</span>
                      <span class="gate-check-value">{{ row.msg }}</span>
                    </div>
                    <div v-if="sampleDiag.error" class="gate-check-msg" style="color:#fb7185">{{ sampleDiag.error }}</div>
                    <el-alert v-if="sampleDiag.ok && !sampleDiag.aiModelUsable" type="warning" :closable="false"
                              title="未配置可用 AI 模型，摘要将标记 FAILED，可在「AI 模型配置」启用后重试" style="margin-top: 8px" />
                  </div>
                </PageCard>
              </el-col>
            </el-row>
          </div>
        </el-tab-pane>
      </el-tabs>
    </PageCard>

    <!-- 用例编辑弹窗 -->
    <el-dialog v-model="caseDialog" :title="caseForm.id ? '编辑用例' : '新建用例'" width="640px">
      <el-form label-width="90px">
        <el-form-item label="标题"><el-input v-model="caseForm.title" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="caseForm.testType" style="width: 140px">
            <el-option v-for="t in ['FUNCTIONAL','REGRESSION','PERF','SECURITY','API','UI']" :key="t" :label="typeLabel(t)" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="caseForm.priority" style="width: 100px">
            <el-option v-for="p in ['P0','P1','P2','P3']" :key="p" :value="p" />
          </el-select>
        </el-form-item>
        <el-form-item label="步骤（UI DSL / HTTP 请求）">
          <div class="steps-editor">
            <div v-for="(s, i) in caseForm.steps" :key="i" class="step-row">
              <el-select v-model="s.action" size="small" style="width: 110px" @change="normalizeStep(s)">
                <el-option v-for="a in STEP_ACTIONS" :key="a" :label="stepActionLabel(a)" :value="a" />
              </el-select>
              <template v-if="s.action === 'http'">
                <el-input v-model="s.name" size="small" placeholder="步骤名（可选）" style="width: 140px" />
                <el-select v-model="s.method" size="small" style="width: 92px">
                  <el-option v-for="m in ['GET','POST','PUT','PATCH','DELETE']" :key="m" :value="m" />
                </el-select>
                <el-input v-model="s.url" size="small" placeholder="URL，如 https://api.example.com/orders" style="flex:1" />
              </template>
              <template v-else>
                <el-input v-model="s.target" size="small" placeholder="target（选择器/URL）" style="flex:1" />
                <el-input v-model="s.value" size="small" placeholder="value（值）" style="flex:1" />
                <el-input v-model="s.expected" size="small" placeholder="expected（断言期望）" style="flex:1" />
              </template>
              <el-button size="small" text type="danger" @click="removeStep(i)">✕</el-button>
            </div>
            <div v-for="(s, i) in caseForm.steps.filter((x: any) => x.action === 'http')" :key="'h' + i" class="http-step-body">
              <div class="http-step-title"><span class="http-badge">HTTP</span> {{ s.method ?? 'GET' }} {{ s.url ?? '' }}</div>
              <div class="http-step-field">
                <span class="http-step-label">请求头</span>
                <div v-for="(h, hi) in s.headers" :key="hi" class="http-kv-row">
                  <el-input v-model="h.key" size="small" placeholder="键，如 Content-Type" style="width: 46%" />
                  <span class="kv-eq">=</span>
                  <el-input v-model="h.value" size="small" placeholder="值" style="width: 46%" />
                  <el-button size="small" text type="danger" @click="removeHttpHeader(s, hi)">✕</el-button>
                </div>
                <el-button size="small" text type="primary" class="ops-btn" @click="addHttpHeader(s)">+ 添加请求头</el-button>
              </div>
              <div class="http-step-field">
                <span class="http-step-label">请求体</span>
                <el-input v-model="s.body" type="textarea" :rows="2" placeholder="JSON / 原始文本（GET 忽略）" />
              </div>
              <div class="http-step-field">
                <span class="http-step-label">断言</span>
                <div v-for="(a, ai) in s.assertions" :key="ai" class="http-kv-row">
                  <el-select v-model="a.type" size="small" style="width: 150px">
                    <el-option v-for="t in ASSERTION_TYPES" :key="t" :label="assertionTypeLabel(t)" :value="t" />
                  </el-select>
                  <el-input v-model="a.expected" size="small" :placeholder="a.type === 'responseTimeMs' ? '毫秒' : '期望值'" style="flex:1" />
                  <el-button size="small" text type="danger" @click="removeAssertion(s, ai)">✕</el-button>
                </div>
                <el-button size="small" text type="primary" class="ops-btn" @click="addAssertion(s)">+ 添加断言</el-button>
              </div>
            </div>
            <el-button size="small" text type="primary" class="ops-btn" @click="addStep">+ 添加步骤</el-button>
          </div>
        </el-form-item>
        <el-form-item label="关联文件"><el-input v-model="caseForm.relatedFiles" placeholder="逗号分隔，推荐引擎匹配键" /></el-form-item>
        <el-form-item label="关联接口"><el-input v-model="caseForm.relatedApis" placeholder="逗号分隔，如 POST /order/create" /></el-form-item>
        <el-form-item label="标签"><el-input v-model="caseForm.tags" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="caseForm.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="caseDialog = false">取消</el-button>
        <el-button type="primary" @click="saveCase">保存</el-button>
      </template>
    </el-dialog>

    <!-- 用例详情弹窗 -->
    <el-dialog v-model="caseDetailVisible" :title="caseDetail?.title ?? '用例详情'" width="560px">
      <template v-if="caseDetail">
        <div class="meta-line">
          <span class="type-tag">{{ typeLabel(caseDetail.testType) }}</span>
          <span class="sev" :class="sevClass(caseDetail.priority)">{{ caseDetail.priority }}</span>
          <span v-if="caseDetail.relatedFiles" class="meta-text">文件: {{ caseDetail.relatedFiles }}</span>
        </div>
        <div v-if="caseDetail.steps" class="step-preview">
          <div v-for="(s, i) in parseSteps(caseDetail.steps)" :key="i" class="step-preview-row">
            <span class="type-tag">{{ stepActionLabel(s.action) }}</span>
            <template v-if="s.action === 'http'">
              <span>{{ s.method }} {{ s.url }}</span>
              <span v-if="(s.assertions ?? []).length" class="expect-text">→ {{ s.assertions.length }} 条断言</span>
            </template>
            <template v-else>
              <span>{{ s.target }}</span><span v-if="s.value" class="meta-text">= {{ s.value }}</span>
              <span v-if="s.expected" class="expect-text">→ {{ s.expected }}</span>
            </template>
          </div>
        </div>
        <div class="bug-list">
          <h4>关联缺陷</h4>
          <div v-for="b in caseDetail.bugs ?? []" :key="b.id" class="bug-item">
            <span class="sev" :class="sevClass(b.severity)">{{ b.severity }}</span>
            <span>{{ b.title }}</span>
            <el-button size="small" text type="danger" class="ops-btn" @click="unlinkCaseBug(b.id)">取消关联</el-button>
          </div>
          <el-empty v-if="!(caseDetail.bugs ?? []).length" description="暂无关联缺陷" :image-size="50" />
        </div>
      </template>
    </el-dialog>

    <!-- 新建计划弹窗 -->
    <el-dialog v-model="planDialog" title="新建测试计划" width="460px">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="planForm.name" placeholder="如：v2.5.0 回归计划" /></el-form-item>
        <el-form-item label="目标版本">
          <el-select v-model="planForm.targetVersion" style="width: 200px">
            <el-option v-for="v in versions" :key="v" :value="v" />
          </el-select>
        </el-form-item>
        <el-form-item label="基线版本">
          <el-select v-model="planForm.fromVersion" style="width: 200px">
            <el-option v-for="v in versions" :key="v" :value="v" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planDialog = false">取消</el-button>
        <el-button type="primary" @click="createPlan">创建</el-button>
      </template>
    </el-dialog>

    <!-- 计划详情抽屉 -->
    <el-drawer v-model="planDetailVisible" :title="planDetail?.name ?? '测试计划'" size="680px">
      <template v-if="planDetail">
        <div class="meta-line" style="margin-bottom: 12px">
          <span class="pill" :class="tagToPill(planStatusColor(planDetail.status))">{{ planDetail.status }}</span>
          <span class="meta-text">目标: {{ planDetail.targetVersion ?? '—' }} · 基线: {{ planDetail.fromVersion ?? '—' }}</span>
        </div>
        <div class="toolbar-row">
          <el-button size="small" type="success" :loading="planRunLoadingId === planDetail.id" @click="runPlan(planDetail)">一键执行</el-button>
          <el-button v-for="t in planTransitions[planDetail.status] ?? []" :key="t" size="small" type="primary" plain @click="changePlanStatus(planDetail, t)">
            {{ { RUNNING: '开始执行', DONE: '完成', DRAFT: '撤回' }[t] ?? t }}
          </el-button>
          <el-button size="small" :icon="Document" @click="showPlanReport(planDetail)">计划报告</el-button>
          <el-button size="small" :icon="Plus" @click="addCaseVisible = true">追加用例</el-button>
        </div>
        <el-table :data="planDetail.items ?? []" stripe size="small" class="neo-table">
          <el-table-column prop="title" label="用例" min-width="180" show-overflow-tooltip />
          <el-table-column label="优先级" width="70"><template #default="{ row }"><span class="sev" :class="sevClass(row.priority)">{{ row.priority }}</span></template></el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-dropdown trigger="click" @command="(s: string) => executeItem(row, s)">
                <span class="pill" :class="tagToPill(execStatusColor(row.status))" style="cursor: pointer">{{ row.status }} ▾</span>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-for="s in ['PASSED','FAILED','BLOCKED','SKIPPED']" :key="s" :command="s">{{ s }}</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-table-column>
          <el-table-column prop="executor" label="执行人" width="80" />
          <el-table-column label="操作" width="120">
            <template #default="{ row, $index }">
              <el-button size="small" text class="ops-btn" :disabled="(planDetail.items?.length ?? 0) <= 1 || $index === 0" @click="reorderItem(row, 'UP')">↑</el-button>
              <el-button size="small" text class="ops-btn" :disabled="(planDetail.items?.length ?? 0) <= 1 || $index === (planDetail.items?.length ?? 1) - 1" @click="reorderItem(row, 'DOWN')">↓</el-button>
              <el-button v-if="row.status === 'PENDING'" size="small" text type="danger" class="ops-btn" @click="removeItem(row)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <!-- 追加用例弹窗 -->
    <el-dialog v-model="addCaseVisible" title="追加用例" width="520px">
      <div class="toolbar-row">
        <el-input v-model="caseFilter.keyword" placeholder="搜索用例" size="small" style="width: 180px" @keyup.enter="loadCases" />
      </div>
      <el-table :data="caseList" stripe size="small" class="neo-table" @selection-change="(rows: any[]) => addCaseSelected = rows.map(r => r.id)">
        <el-table-column type="selection" width="40" />
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column label="优先级" width="70"><template #default="{ row }"><span class="sev" :class="sevClass(row.priority)">{{ row.priority }}</span></template></el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="addCaseVisible = false">取消</el-button>
        <el-button type="primary" @click="addCasesToPlan">加入计划</el-button>
      </template>
    </el-dialog>

    <!-- 计划报告弹窗 -->
    <el-dialog v-model="planReportVisible" :title="'计划报告 — ' + (planReport?.planName ?? '')" width="640px">
      <template v-if="planReport">
        <el-row :gutter="12" style="margin-bottom: 12px">
          <el-col :span="4"><StatCard label="总数" :value="planReport.total" color="#6d7cff" /></el-col>
          <el-col :span="4"><StatCard label="通过" :value="planReport.passed" color="#34d399" /></el-col>
          <el-col :span="4"><StatCard label="失败" :value="planReport.failed" color="#fb7185" /></el-col>
          <el-col :span="4"><StatCard label="阻塞" :value="planReport.blocked" color="#fbbf24" /></el-col>
          <el-col :span="4"><StatCard label="跳过" :value="planReport.skipped" color="#a78bfa" /></el-col>
          <el-col :span="4"><StatCard label="通过率" :value="planReport.passRate + '%'" color="#34d399" /></el-col>
        </el-row>
        <h4 class="block-title">失败用例明细</h4>
        <el-table :data="planReport.failCases ?? []" stripe size="small" class="neo-table">
          <el-table-column prop="title" label="用例" min-width="200" show-overflow-tooltip />
          <el-table-column label="优先级" width="70"><template #default="{ row }"><span class="sev" :class="sevClass(row.priority)">{{ row.priority }}</span></template></el-table-column>
          <el-table-column prop="executor" label="执行人" width="80" />
          <el-table-column prop="executedAt" label="时间" width="170" />
          <el-table-column prop="resultDetail" label="失败原因" min-width="160" show-overflow-tooltip />
        </el-table>
      </template>
    </el-dialog>

    <!-- 记录执行弹窗 -->
    <el-dialog v-model="execDialog" title="记录执行" width="480px">
      <el-form label-width="80px">
        <el-form-item label="用例">
          <el-select v-model="execForm.testCaseId" filterable style="width: 100%">
            <el-option v-for="c in standaloneCases" :key="c.id" :label="c.title" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="execForm.status" style="width: 140px">
            <el-option v-for="s in ['PASSED','FAILED','BLOCKED','SKIPPED']" :key="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="执行人"><el-input v-model="execForm.executor" /></el-form-item>
        <el-form-item label="结果说明"><el-input v-model="execForm.resultDetail" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="execDialog = false">取消</el-button>
        <el-button type="primary" @click="recordExecution">记录</el-button>
      </template>
    </el-dialog>

    <!-- 提 Bug 弹窗 -->
    <el-dialog v-model="bugDialog" title="提 Bug" width="480px">
      <el-form label-width="80px">
        <el-form-item label="标题"><el-input v-model="bugForm.title" /></el-form-item>
        <el-form-item label="严重度">
          <el-select v-model="bugForm.severity" style="width: 100px">
            <el-option v-for="s in ['P0','P1','P2','P3']" :key="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="发现人"><el-input v-model="bugForm.foundBy" /></el-form-item>
        <el-form-item label="发现版本"><el-input v-model="bugForm.foundVersion" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="bugForm.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="bugDialog = false">取消</el-button>
        <el-button type="primary" @click="createBug">提交</el-button>
      </template>
    </el-dialog>

    <!-- 缺陷详情弹窗 -->
    <el-dialog v-model="bugDetailVisible" :title="bugDetail?.title ?? '缺陷详情'" width="640px">
      <template v-if="bugDetail">
        <div class="meta-line">
          <span class="sev" :class="sevClass(bugDetail.severity)">{{ bugDetail.severity }}</span>
          <span class="pill" :class="tagToPill(statusColor(bugDetail.status))">{{ bugDetail.status }}</span>
          <span v-if="bugDetail.external_key" class="meta-text">Jira: {{ bugDetail.external_key }}</span>
        </div>
        <p v-if="bugDetail.description" class="bug-desc">{{ bugDetail.description }}</p>
        <h4 class="block-title">关联用例</h4>
        <el-table :data="bugDetail.linkedCases ?? []" size="small" class="neo-table">
          <el-table-column prop="title" label="用例" min-width="200" />
          <el-table-column label="优先级" width="70"><template #default="{ row }"><span class="sev" :class="sevClass(row.priority)">{{ row.priority }}</span></template></el-table-column>
          <el-table-column label="操作" width="100"><template #default="{ row }"><el-button size="small" text type="danger" class="ops-btn" @click="unlinkBugCase(row.id)">取消关联</el-button></template></el-table-column>
        </el-table>
        <el-button size="small" text type="primary" class="ops-btn" style="margin-top: 6px" @click="linkBugCase">+ 关联用例</el-button>
        <h4 class="block-title">关联变更</h4>
        <div v-for="c in bugDetail.linkedChanges ?? []" :key="c.eventId" class="bug-item">
          <span class="type-tag">{{ c.linkType }}</span>
          <code class="commit-sha">{{ c.commitSha?.substring(0, 8) ?? c.eventId?.substring(0, 8) }}</code>
          <span class="change-sum">{{ c.summary ?? c.author }}</span>
        </div>
      </template>
    </el-dialog>

    <!-- Jira 设置弹窗 -->
    <el-dialog v-model="jiraVisible" title="Jira 缺陷双向同步" width="520px">
      <el-alert type="info" :closable="false" class="qa-alert" title="配置后：定时拉取 Jira 问题为缺陷；本地缺陷新建/流转自动推送 Jira。未配置时不影响本地流程。" style="margin-bottom: 12px" />
      <el-form label-width="90px">
        <el-form-item label="启用"><el-switch v-model="jiraForm.enabled" /></el-form-item>
        <el-form-item label="Base URL"><el-input v-model="jiraForm.baseUrl" placeholder="https://yourcompany.atlassian.net" /></el-form-item>
        <el-form-item label="用户名"><el-input v-model="jiraForm.username" placeholder="邮箱或用户名" /></el-form-item>
        <el-form-item label="API Token"><el-input v-model="jiraForm.apiToken" placeholder="留空则保持原值" show-password /></el-form-item>
        <el-form-item label="项目 Key"><el-input v-model="jiraForm.jiraProjectKey" placeholder="如 EVO" /></el-form-item>
        <el-form-item label="Issue 类型"><el-input v-model="jiraForm.issueType" placeholder="Bug" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button :loading="jiraSyncing" @click="syncJira">手动同步（拉取）</el-button>
        <el-button @click="jiraVisible = false">取消</el-button>
        <el-button type="primary" @click="saveJiraConfig">保存配置</el-button>
      </template>
    </el-dialog>

    <!-- AI 生成用例弹窗 -->
    <el-dialog v-model="aiGenVisible" title="AI 生成测试用例" width="760px" top="6vh">
      <el-alert class="qa-alert" style="margin-bottom: 12px" type="info" :closable="false"
                title="基于变更事件（+ 可选需求上下文）由 AI 生成用例建议，不自动落库，可逐条保存为正式用例。未配置可用模型时采用启发式建议。" />
      <div class="toolbar-row">
        <el-select v-model="aiGenEventId" filterable placeholder="选择 CODE_COMMIT 变更事件" size="small" style="width: 320px" :loading="aiGenLoading">
          <el-option v-for="opt in aiGenEventOptions" :key="opt.eventId" :value="opt.eventId"
                     :label="opt.eventId?.substring(0, 10) + '… ' + (opt.summary?.substring(0, 24) ?? opt.eventType)" />
        </el-select>
        <el-select v-model="aiGenRequirementId" clearable placeholder="关联需求（可选）" filterable size="small" style="width: 200px">
          <el-option v-for="r in requirements" :key="r.id" :label="r.title" :value="r.id" />
        </el-select>
        <el-button type="primary" size="small" :loading="aiGenLoading" :disabled="!aiGenEventId" @click="runAiGen">生成</el-button>
      </div>
      <template v-if="aiGenResult">
        <el-alert class="qa-alert" style="margin-bottom: 12px" :type="aiGenResult.aiGenerated ? 'success' : 'info'" :closable="false"
                  :title="aiGenResult.summary + (aiGenResult.model ? ` · 模型 ${aiGenResult.model}` : '')" />
        <div v-for="(tc, i) in aiGenResult.testCases ?? []" :key="i" class="ai-case-card">
          <div class="ai-case-head">
            <span class="type-tag">{{ tc.testType }}</span>
            <span class="sev" :class="sevClass(tc.priority)">{{ tc.priority }}</span>
            <span class="ai-case-title">{{ tc.title }}</span>
            <el-button size="small" text type="primary" class="ops-btn" :loading="!!aiGenSaving[tc.title]" @click="saveAiGeneratedCase(tc)">保存为用例</el-button>
          </div>
          <div v-for="(s, si) in parseAiSteps(tc.steps)" :key="si" class="ai-case-step">
            <span class="ai-case-step-idx">{{ si + 1 }}</span>
            <span>{{ s.step }}</span>
            <span class="expect-text">→ {{ s.expected }}</span>
          </div>
        </div>
      </template>
    </el-dialog>

    <!-- 需求追溯矩阵弹窗 -->
    <el-dialog v-model="traceVisible" title="需求追溯矩阵" width="760px" top="6vh">
      <div class="toolbar-row">
        <el-select v-model="traceRequirementId" filterable placeholder="选择需求" size="small" style="width: 320px">
          <el-option v-for="r in requirements" :key="r.id" :label="`${r.id} · ${r.title}`" :value="r.id" />
        </el-select>
        <el-button type="primary" size="small" :loading="traceLoading" :disabled="!traceRequirementId" @click="runTrace">查询</el-button>
      </div>
      <template v-if="trace">
        <div class="trace-req">
          <span class="type-tag">{{ trace.requirement?.status }}</span>
          <span class="ai-case-title">{{ trace.requirement?.title }}</span>
        </div>
        <el-row :gutter="12" style="margin: 12px 0">
          <el-col :span="6"><StatCard label="关联用例" :value="trace.coverage?.total ?? 0" color="#6d7cff" /></el-col>
          <el-col :span="6"><StatCard label="通过" :value="trace.coverage?.passed ?? 0" color="#34d399" /></el-col>
          <el-col :span="6"><StatCard label="失败" :value="trace.coverage?.failed ?? 0" color="#fb7185" /></el-col>
          <el-col :span="6"><StatCard label="未关闭缺陷" :value="trace.coverage?.openBugs ?? 0" color="#fbbf24" /></el-col>
        </el-row>
        <el-divider content-position="left">关联用例</el-divider>
        <el-table :data="trace.testCases ?? []" stripe size="small" class="neo-table">
          <el-table-column prop="title" label="用例" min-width="200" show-overflow-tooltip />
          <el-table-column label="类型" width="80"><template #default="{ row }"><span class="type-tag">{{ typeLabel(row.testType) }}</span></template></el-table-column>
          <el-table-column label="优先级" width="70"><template #default="{ row }"><span class="sev" :class="sevClass(row.priority)">{{ row.priority }}</span></template></el-table-column>
          <el-table-column label="最近执行" width="110">
            <template #default="{ row }"><span v-if="row.lastStatus" class="pill" :class="tagToPill(execStatusColor(row.lastStatus))">{{ row.lastStatus }}</span><span v-else style="color:var(--et-text-muted)">—</span></template>
          </el-table-column>
          <el-table-column label="执行次数" width="80"><template #default="{ row }">{{ row.execCount ?? 0 }}</template></el-table-column>
        </el-table>
        <el-divider content-position="left">关联缺陷</el-divider>
        <el-table :data="trace.bugs ?? []" stripe size="small" class="neo-table">
          <el-table-column label="严重度" width="70"><template #default="{ row }"><span class="sev" :class="sevClass(row.severity)">{{ row.severity }}</span></template></el-table-column>
          <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
          <el-table-column label="状态" width="110"><template #default="{ row }"><span class="pill" :class="tagToPill(statusColor(row.status))">{{ row.status }}</span></template></el-table-column>
        </el-table>
      </template>
    </el-dialog>

    <!-- 门禁规则配置弹窗 -->
    <el-dialog v-model="gateRulesVisible" title="质量门禁规则配置" width="760px" top="6vh">
      <el-alert class="qa-alert" style="margin-bottom: 12px" type="info" :closable="false"
                title="生效规则 = 全局默认 + 项目覆盖。新增/编辑为项目级覆盖，删除后恢复全局默认。权重与阈值可调，启用状态决定是否参与门禁计算。" />
      <div class="toolbar-row">
        <el-button size="small" type="primary" :icon="Plus" @click="openGateRuleEdit()">新增规则</el-button>
        <el-button size="small" :icon="Refresh" @click="loadGateRules">刷新</el-button>
        <span class="bar-hint">ruleKey 须与 QualityGateChecker 校验器一致</span>
      </div>
      <el-table :data="gateRules" stripe size="small" class="neo-table">
        <el-table-column label="规则" min-width="180">
          <template #default="{ row }">
            <div class="gate-check-name">{{ row.name }}</div>
            <code class="commit-sha">{{ row.ruleKey }}</code>
          </template>
        </el-table-column>
        <el-table-column label="作用域" width="90">
          <template #default="{ row }"><span class="pill" :class="row.scope === 'PROJECT' ? 'pill-warn' : 'pill-muted'">{{ row.scope === 'PROJECT' ? '项目覆盖' : '全局默认' }}</span></template>
        </el-table-column>
        <el-table-column label="启用" width="70">
          <template #default="{ row }"><el-switch :model-value="!!row.enabled" @change="(v: any) => editEnabled(row, v)" /></template>
        </el-table-column>
        <el-table-column label="权重" width="70"><template #default="{ row }"><span class="gate-check-weight">{{ row.weight }}分</span></template></el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button size="small" text type="primary" class="ops-btn" @click="openGateRuleEdit(row)">调整</el-button>
            <el-button size="small" text type="danger" class="ops-btn" @click="deleteGateRule(row)">恢复默认</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 门禁规则编辑弹窗 -->
    <el-dialog v-model="gateRuleDialogVisible" title="编辑门禁规则" width="520px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="规则标识">
          <el-input v-model="gateRuleForm.ruleKey" :disabled="!!gateRuleForm.existingKey" placeholder="如 openBlockerBugs / failedTests" />
        </el-form-item>
        <el-form-item label="展示名称"><el-input v-model="gateRuleForm.name" placeholder="如：阻塞缺陷数" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="gateRuleForm.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="gateRuleForm.enabled" /></el-form-item>
        <el-form-item label="权重"><el-input-number v-model="gateRuleForm.weight" :min="0" :max="100" /></el-form-item>
        <el-form-item label="阈值(JSON)"><el-input v-model="gateRuleForm.threshold" type="textarea" :rows="2" placeholder='如 {"max": 3} 或 {"min": 80}' /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="gateRuleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="gateRuleSaving" @click="saveGateRule">保存</el-button>
      </template>
    </el-dialog>

    <!-- 用例执行结果弹窗 -->
    <el-dialog v-model="runDialog" title="执行结果" width="780px" top="6vh">
      <template v-if="runResult">
        <el-alert class="qa-alert" style="margin-bottom: 12px" :type="runResult.verdict === 'PASSED' ? 'success' : 'error'" :closable="false"
                  :title="`${runResult.verdict} · ${runResult.summary} · 总耗时 ${runResult.durationMs}ms`" />
        <el-table :data="runResult.steps" size="small" class="neo-table">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="run-step-detail">
                <div v-if="row.url" class="meta-line"><code>{{ row.method }} {{ row.url }}</code></div>
                <div v-if="row.statusCode != null" class="meta-text">HTTP {{ row.statusCode }} · {{ row.durationMs }}ms</div>
                <div v-for="a in row.assertions ?? []" :key="a.type + a.expected" class="meta-text" :style="{ color: a.passed ? '#34d399' : '#fb7185' }">
                  {{ a.passed ? '✓' : '✕' }} {{ assertionTypeLabel(a.type) }} → {{ a.expected }}：{{ a.message }}
                </div>
                <div v-if="row.responseSnippet" class="meta-text">响应: <code>{{ row.responseSnippet }}</code></div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="#" width="45"><template #default="{ row }">{{ row.index + 1 }}</template></el-table-column>
          <el-table-column label="步骤" min-width="150">
            <template #default="{ row }"><span class="step-name">{{ row.name || stepActionLabel(row.type) }}</span></template>
          </el-table-column>
          <el-table-column label="请求" min-width="200" show-overflow-tooltip><template #default="{ row }"><code>{{ stepRequestText(row) }}</code></template></el-table-column>
          <el-table-column label="状态" width="104"><template #default="{ row }"><span class="pill" :class="tagToPill(stepStatusColor(row.status))">{{ row.status }}</span></template></el-table-column>
          <el-table-column label="耗时" width="80"><template #default="{ row }">{{ row.durationMs }}ms</template></el-table-column>
          <el-table-column label="信息" min-width="180" show-overflow-tooltip><template #default="{ row }"><span :style="{ color: row.status === 'FAILED' ? '#fb7185' : 'inherit' }">{{ stepFailInfo(row) }}</span></template></el-table-column>
        </el-table>
      </template>
    </el-dialog>

    <!-- 计划执行结果弹窗 -->
    <el-dialog v-model="planRunDialog" :title="'计划执行 — ' + (planRunResult?.planName ?? '')" width="860px" top="5vh">
      <template v-if="planRunResult">
        <el-row :gutter="12" style="margin-bottom: 12px">
          <el-col :span="4"><StatCard label="总用例" :value="planRunResult.total" color="#6d7cff" /></el-col>
          <el-col :span="4"><StatCard label="通过" :value="planRunResult.passed" color="#34d399" /></el-col>
          <el-col :span="4"><StatCard label="失败" :value="planRunResult.failed" color="#fb7185" /></el-col>
          <el-col :span="4"><StatCard label="跳过" :value="planRunResult.skipped" color="#a78bfa" /></el-col>
          <el-col :span="8"><StatCard label="总耗时" :value="planRunResult.durationMs + 'ms'" color="#38e1ff" /></el-col>
        </el-row>
        <el-table :data="planRunResult.results" size="small" class="neo-table" v-if="planRunResult.results.length">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="run-step-detail">
                <div v-if="row.reason" class="meta-text" style="color:#fbbf24">{{ row.reason }}</div>
                <el-table :data="row.steps ?? []" size="small">
                  <el-table-column label="步骤" min-width="140">
                    <template #default="{ row: sr }"><span class="step-name">{{ sr.name || stepActionLabel(sr.type) }}</span></template>
                  </el-table-column>
                  <el-table-column label="请求" min-width="200" show-overflow-tooltip><template #default="{ row: sr }"><code>{{ stepRequestText(sr) }}</code></template></el-table-column>
                  <el-table-column label="状态" width="104"><template #default="{ row: sr }"><span class="pill" :class="tagToPill(stepStatusColor(sr.status))">{{ sr.status }}</span></template></el-table-column>
                  <el-table-column label="耗时" width="80"><template #default="{ row: sr }">{{ sr.durationMs }}ms</template></el-table-column>
                  <el-table-column label="信息" min-width="180" show-overflow-tooltip><template #default="{ row: sr }"><span :style="{ color: sr.status === 'FAILED' ? '#fb7185' : 'inherit' }">{{ stepFailInfo(sr) }}</span></template></el-table-column>
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="用例" min-width="220" show-overflow-tooltip />
          <el-table-column label="结果" width="104"><template #default="{ row }"><span class="pill" :class="tagToPill(stepStatusColor(row.verdict))">{{ row.verdict }}</span></template></el-table-column>
          <el-table-column label="耗时" width="90"><template #default="{ row }">{{ row.durationMs }}ms</template></el-table-column>
          <el-table-column label="说明" min-width="160" show-overflow-tooltip><template #default="{ row }"><span :style="{ color: row.reason ? '#fbbf24' : 'inherit' }">{{ row.reason ?? '' }}</span></template></el-table-column>
        </el-table>
        <el-empty v-else description="计划中没有用例" :image-size="60" />
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* ==================== 页面横幅 ==================== */
.qa-hero {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
  padding: 20px 24px;
  border-radius: 18px;
  margin-top: 18px;
  overflow: hidden;
  background: linear-gradient(120deg, rgba(109, 124, 255, 0.14), rgba(167, 139, 250, 0.08) 45%, rgba(56, 225, 255, 0.09));
  border: 1px solid rgba(109, 124, 255, 0.25);
}
.qa-hero::before {
  content: '';
  position: absolute;
  inset: 0;
  background: radial-gradient(500px 180px at 85% -30%, rgba(56, 225, 255, 0.18), transparent 60%);
  pointer-events: none;
}
.qa-hero-title {
  margin: 0;
  font-size: 20px;
  font-weight: 800;
  display: flex;
  align-items: center;
  gap: 10px;
}
.qa-hero-sub {
  margin: 7px 0 0;
  font-size: 13px;
  color: var(--et-text-secondary);
}
.qa-hero-right {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

/* ==================== 顶部统计 ==================== */
.qa-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 18px;
  margin-top: 18px;
}
.qa-stat-item {
  display: flex;
}
.qa-stat-item .et-card {
  flex: 1;
}

/* ==================== 标签页 ==================== */
.page-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 20px;
}
.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.tab-label .el-icon {
  color: inherit;
}
.tab-content {
  padding: 18px 22px 22px;
}
.toolbar-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  font-size: 13px;
  color: var(--et-text-secondary);
  flex-wrap: wrap;
}
.arrow-sep {
  color: var(--et-text-muted);
  font-size: 13px;
}
.bar-hint {
  font-size: 12px;
  color: var(--et-text-muted);
}
.tab-content :deep(.el-divider--horizontal) {
  border-color: var(--et-border);
}
.tab-content :deep(.el-divider__text) {
  color: var(--et-text-secondary);
  font-size: 12.5px;
  font-weight: 700;
  background: var(--et-card-solid);
  padding: 0 14px;
  border-radius: 20px;
}
.tab-content :deep(.el-alert) {
  border-radius: 12px;
  border: 1px solid var(--et-border);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
}
.tab-content :deep(.el-alert--success) { background: rgba(52, 211, 153, 0.1); color: var(--et-text); }
.tab-content :deep(.el-alert--error) { background: rgba(251, 113, 133, 0.1); color: var(--et-text); }
.tab-content :deep(.el-alert--warning) { background: rgba(251, 191, 36, 0.1); color: var(--et-text); }
.tab-content :deep(.el-alert--info) { background: rgba(109, 124, 255, 0.1); color: var(--et-text); }
.tab-content :deep(.el-alert__title) { color: inherit; font-weight: 600; }
.tab-content :deep(.el-alert__description) { color: inherit; }

/* ==================== 测试用例 ==================== */
.cases-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}
.case-tree {
  width: 236px;
  flex-shrink: 0;
  border: 1px solid var(--et-border);
  border-radius: 14px;
  padding: 14px 12px;
  background: var(--et-bg-muted);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}
.tree-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 700;
  margin-bottom: 10px;
  color: var(--et-text-secondary);
}
.tree-head-ic {
  width: 24px;
  height: 24px;
  border-radius: 7px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--et-primary-light);
  background: rgba(109, 124, 255, 0.12);
  flex-shrink: 0;
}
.case-tree :deep(.el-tree) {
  background: transparent;
  --el-tree-node-hover-bg-color: rgba(109, 124, 255, 0.08);
  --el-tree-text-color: var(--et-text-secondary);
  --el-tree-node-expanded-bg-color: transparent;
  color: var(--et-text-secondary);
}
.case-tree :deep(.el-tree-node__content) {
  height: 30px;
  border-radius: 8px;
}
.case-tree :deep(.el-tree-node__content:hover) {
  background: rgba(109, 124, 255, 0.08);
}
.case-tree :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background: linear-gradient(90deg, rgba(109, 124, 255, 0.16), rgba(109, 124, 255, 0.05));
  color: var(--et-text);
}
.case-tree :deep(.el-tree-node__expand-icon) {
  color: var(--et-text-muted);
}
.tree-node {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  flex: 1;
  min-width: 0;
  overflow: hidden;
}
.tree-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.node-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}
.node-mod {
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-b));
  box-shadow: 0 0 8px var(--et-glow);
}
.node-case {
  background: var(--et-grad-c);
  box-shadow: 0 0 8px rgba(56, 225, 255, 0.5);
}
.tree-count {
  font-size: 10.5px;
  color: var(--et-text-muted);
  background: rgba(255, 255, 255, 0.07);
  border-radius: 20px;
  padding: 0 8px;
  margin-left: auto;
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}
[data-theme="light"] .tree-count {
  background: rgba(15, 23, 42, 0.06);
}
.case-list {
  flex: 1;
  min-width: 0;
}

/* ==================== 表格 ==================== */
.neo-table {
  border: 1px solid var(--et-border);
  border-radius: 12px;
  overflow: hidden;
}
.ops-btn:hover {
  box-shadow: 0 0 12px var(--et-glow);
}
.table-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

/* ==================== 计划卡片 ==================== */
.plan-card-box {
  position: relative;
  cursor: pointer;
  height: 100%;
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  padding: 16px 18px;
  background: var(--et-card-bg);
  border: 1px solid var(--et-border);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  transition: transform 0.2s, border-color 0.2s, box-shadow 0.2s;
  overflow: hidden;
}
.plan-card-box::before {
  content: '';
  position: absolute;
  top: 0;
  left: 18px;
  right: 18px;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.22), transparent);
}
[data-theme="light"] .plan-card-box::before {
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.9), transparent);
}
.plan-card-box:hover {
  transform: translateY(-3px);
  border-color: var(--et-hover-border);
  box-shadow: var(--et-shadow-md);
}
.plan-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.plan-name {
  font-weight: 700;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.plan-meta {
  font-size: 12px;
  color: var(--et-text-muted);
  margin-top: 8px;
}
.plan-progress {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}
.plan-progress .et-bar {
  flex: 1;
}
.plan-progress-num {
  font-size: 11px;
  color: var(--et-text-secondary);
  font-variant-numeric: tabular-nums;
  min-width: 34px;
  text-align: right;
}
.plan-actions {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

/* ==================== 门禁 / 自检 ==================== */
.gate-check {
  padding: 10px 12px;
  border: 1px solid var(--et-border);
  border-radius: 10px;
  margin-bottom: 8px;
  background: var(--et-bg-muted);
  transition: border-color 0.15s;
}
.gate-check:hover {
  border-color: var(--et-hover-border);
}
.gate-check-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.gate-check-name {
  font-weight: 600;
  font-size: 13px;
  flex: 1;
}
.gate-check-value {
  font-size: 12px;
  color: var(--et-text-secondary);
}
.gate-check-weight {
  font-size: 11px;
  color: var(--et-text-muted);
  border: 1px solid var(--et-border);
  border-radius: 20px;
  padding: 1px 8px;
}
.gate-check-msg {
  font-size: 12px;
  color: var(--et-text-muted);
  margin-top: 4px;
}
.check-ic {
  width: 22px;
  height: 22px;
  border-radius: 7px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 800;
  flex-shrink: 0;
}
.check-ic.sm {
  width: 18px;
  height: 18px;
  border-radius: 6px;
  font-size: 11px;
}
.ic-ok {
  color: var(--et-ok);
  background: rgba(52, 211, 153, 0.13);
  box-shadow: 0 0 10px rgba(52, 211, 153, 0.25);
}
.ic-bad {
  color: var(--et-danger);
  background: rgba(251, 113, 133, 0.13);
  box-shadow: 0 0 10px rgba(251, 113, 133, 0.2);
}
.diag-empty {
  color: var(--et-text-muted);
  font-size: 12px;
}
.diag-sending {
  margin-left: 8px;
  font-size: 12px;
  color: var(--et-text-muted);
}
.diag-event-id {
  font-size: 11px;
  color: var(--et-text-secondary);
  cursor: pointer;
  user-select: all;
}

/* ==================== 图表卡片 ==================== */
.chart-box {
  border: 1px solid var(--et-border);
  border-radius: 14px;
  padding: 14px 16px 10px;
  background: var(--et-card-bg);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  height: 100%;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.chart-box:hover {
  border-color: var(--et-hover-border);
  box-shadow: var(--et-shadow);
}
.chart-box-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.chart-box-title {
  font-size: 13px;
  font-weight: 700;
}
.chart-box-sub {
  font-size: 11px;
  color: var(--et-text-muted);
  margin-top: 2px;
}

/* ==================== 状态徽章 ==================== */
.pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 10.5px;
  font-weight: 700;
  padding: 2.5px 9px;
  border-radius: 20px;
  line-height: 1.5;
  white-space: nowrap;
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}
.pill::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.pill-ok { color: var(--et-ok); background: rgba(52, 211, 153, 0.13); }
.pill-danger { color: var(--et-danger); background: rgba(251, 113, 133, 0.13); }
.pill-warn { color: var(--et-warn); background: rgba(251, 191, 36, 0.13); }
.pill-muted { color: var(--et-text-muted); background: var(--et-bg-muted); }
.pill-cyan { color: var(--et-grad-c); background: rgba(56, 225, 255, 0.12); }

/* 严重度徽章 */
.sev {
  display: inline-flex;
  align-items: center;
  font-size: 10.5px;
  font-weight: 800;
  padding: 2.5px 9px;
  border-radius: 8px;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.3px;
}
.sev-0 { color: var(--et-danger); background: rgba(251, 113, 133, 0.14); }
.sev-1 { color: var(--et-warn); background: rgba(251, 191, 36, 0.14); }
.sev-2 { color: var(--et-primary-light); background: rgba(109, 124, 255, 0.14); }
.sev-3 { color: var(--et-text-muted); background: var(--et-bg-muted); }

/* 类型标签 */
.type-tag {
  display: inline-flex;
  align-items: center;
  font-size: 11px;
  font-weight: 600;
  padding: 3px 9px;
  border-radius: 8px;
  color: var(--et-text-secondary);
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
}

/* ==================== 弹窗内通用 ==================== */
.meta-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
}
.meta-text {
  font-size: 12px;
  color: var(--et-text-muted);
}
.block-title {
  margin: 10px 0 6px;
  font-size: 13px;
  font-weight: 700;
  display: flex;
  align-items: center;
  gap: 8px;
}
.block-title::before {
  content: '';
  width: 3px;
  height: 12px;
  border-radius: 3px;
  background: linear-gradient(180deg, var(--et-grad-a), var(--et-grad-c));
}
.qa-alert {
  border-radius: 12px;
  border: 1px solid var(--et-border);
}
.bug-desc {
  font-size: 13px;
  color: var(--et-text-secondary);
  line-height: 1.6;
}
.bug-list h4 {
  margin: 10px 0 6px;
  font-size: 13px;
}
.bug-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px dashed var(--et-border);
  font-size: 13px;
}
.commit-sha {
  font-size: 11px;
  color: var(--et-grad-c);
  background: rgba(56, 225, 255, 0.1);
  border-radius: 6px;
  padding: 2px 6px;
}
.change-sum {
  font-size: 12px;
  color: var(--et-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 步骤编辑器 */
.steps-editor {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.step-row {
  display: flex;
  gap: 6px;
  align-items: center;
}
.http-step-body {
  border: 1px solid var(--et-border);
  border-radius: 10px;
  padding: 10px 12px;
  background: var(--et-bg-muted);
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.http-step-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--et-text-secondary);
  font-family: monospace;
  display: flex;
  align-items: center;
  gap: 8px;
}
.http-badge {
  font-size: 10px;
  font-weight: 800;
  color: var(--et-grad-c);
  background: rgba(56, 225, 255, 0.12);
  border-radius: 6px;
  padding: 2px 7px;
  font-family: inherit;
}
.http-step-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.http-step-label {
  font-size: 12px;
  color: var(--et-text-muted);
}
.http-kv-row {
  display: flex;
  gap: 6px;
  align-items: center;
}
.kv-eq {
  color: var(--et-text-muted);
  font-size: 12px;
}

/* 步骤预览 */
.step-preview {
  margin: 10px 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.step-preview-row {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 12px;
  font-family: monospace;
}
.expect-text {
  color: var(--et-ok);
  font-size: 12px;
}

/* 执行结果详情 */
.run-step-detail {
  padding: 8px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
}
.step-name {
  font-weight: 600;
}

/* AI 用例生成 / 追溯矩阵 */
.ai-case-card {
  border: 1px solid var(--et-border);
  border-radius: 12px;
  padding: 12px 14px;
  margin-bottom: 10px;
  background: var(--et-bg-muted);
}
.ai-case-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.ai-case-title {
  font-weight: 700;
  font-size: 13.5px;
  flex: 1;
  min-width: 0;
}
.ai-case-step {
  display: flex;
  gap: 8px;
  align-items: baseline;
  font-size: 12.5px;
  margin-top: 6px;
  color: var(--et-text-secondary);
}
.ai-case-step-idx {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: rgba(109, 124, 255, 0.14);
  color: var(--et-primary-light);
  font-size: 11px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.trace-req {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 700;
  padding: 10px 12px;
  border: 1px solid var(--et-border);
  border-radius: 10px;
  background: var(--et-bg-muted);
}

/* ==================== 响应式 ==================== */
@media (max-width: 1280px) {
  .qa-stats { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 900px) {
  .cases-layout { flex-direction: column; }
  .case-tree { width: 100%; }
}
@media (max-width: 700px) {
  .qa-stats { grid-template-columns: 1fr; }
}
</style>
