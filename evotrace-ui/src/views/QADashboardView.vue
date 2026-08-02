<script setup lang="ts">
import { onMounted, ref, nextTick, computed, watch } from 'vue'
import * as echarts from 'echarts'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Setting, Document } from '@element-plus/icons-vue'
import FilterBar from '../components/FilterBar.vue'
import PageCard from '../components/PageCard.vue'
import StatCard from '../components/StatCard.vue'
import client from '../api/client'
import { bugApi, jiraApi, testPlanApi, releaseApi, analysisApi, type TestCase, type TestPlan } from '../api'
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

// ==================== Tab1 测试用例 ====================
const caseTree = ref<TestCase[]>([])
const caseList = ref<TestCase[]>([])
const caseTotal = ref(0)
const casePage = ref(1)
const casePageSize = ref(20)
const caseFilter = ref({ keyword: '', testType: '', priority: '', parentId: undefined as number | undefined })
const caseLoading = ref(false)
const caseDialog = ref(false)
const caseForm = ref({ id: undefined as number | undefined, title: '', testType: 'FUNCTIONAL', priority: 'P2', description: '', relatedFiles: '', relatedApis: '', tags: '', parentId: undefined as number | undefined, steps: [{ action: 'open', target: '', value: '', expected: '' }] })
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
  caseDialog.value = true
}
function parseSteps(steps?: string): any[] {
  try { const parsed = JSON.parse(steps ?? '[]'); return Array.isArray(parsed) && parsed.length ? parsed : [{ action: 'open', target: '', value: '', expected: '' }] } catch { return [{ action: 'open', target: '', value: '', expected: '' }] }
}
function addStep() { caseForm.value.steps.push({ action: 'open', target: '', value: '', expected: '' }) }
function removeStep(i: number) { caseForm.value.steps.splice(i, 1) }
async function saveCase() {
  try {
    const payload: Record<string, any> = {
      title: caseForm.value.title, testType: caseForm.value.testType, priority: caseForm.value.priority,
      description: caseForm.value.description, relatedFiles: caseForm.value.relatedFiles,
      relatedApis: caseForm.value.relatedApis, tags: caseForm.value.tags,
      steps: JSON.stringify(caseForm.value.steps.filter((s: any) => s.target || s.value))
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
async function loadTrends() {
  try {
    execTrend.value = await testPlanApi.executionTrend(project.value, 30)
    bugTrendData.value = await testPlanApi.bugTrend(project.value, 30)
    await nextTick(); renderTrendCharts()
  } catch { /* 拦截器已提示 */ }
}
function renderTrendCharts() {
  const el1 = document.getElementById('exec-trend')
  if (el1) {
    const e = echarts.getInstanceByDom(el1); if (e) e.dispose()
    echarts.init(el1).setOption({
      grid: { left: 40, right: 16, top: 30, bottom: 28 },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: execTrend.value.map(d => d.day.substring(5)), axisLabel: { fontSize: 10 } },
      yAxis: [{ type: 'value', minInterval: 1 }, { type: 'value', min: 0, max: 100 }],
      legend: { data: ['执行数', '通过率'], top: 0, textStyle: { fontSize: 11 } },
      series: [
        { name: '执行数', type: 'bar', data: execTrend.value.map(d => d.total), itemStyle: { color: '#6366f1', opacity: .75 }, barMaxWidth: 16 },
        { name: '通过率', type: 'line', yAxisIndex: 1, smooth: true, data: execTrend.value.map(d => d.total ? Math.round(d.passed * 100 / d.total) : null), lineStyle: { color: '#10b981', width: 2 }, itemStyle: { color: '#10b981' } }
      ]
    })
  }
  const el2 = document.getElementById('bug-trend')
  if (el2) {
    const e = echarts.getInstanceByDom(el2); if (e) e.dispose()
    echarts.init(el2).setOption({
      grid: { left: 40, right: 16, top: 30, bottom: 28 },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: bugTrendData.value.map(d => d.day.substring(5)), axisLabel: { fontSize: 10 } },
      yAxis: { type: 'value', minInterval: 1 },
      legend: { data: ['P0', 'P1', 'P2', 'P3'], top: 0, textStyle: { fontSize: 11 } },
      series: [
        { name: 'P0', type: 'line', smooth: true, data: bugTrendData.value.map(d => d.p0), lineStyle: { color: '#ef4444', width: 2 }, itemStyle: { color: '#ef4444' } },
        { name: 'P1', type: 'line', smooth: true, data: bugTrendData.value.map(d => d.p1), lineStyle: { color: '#f59e0b', width: 2 }, itemStyle: { color: '#f59e0b' } },
        { name: 'P2', type: 'line', smooth: true, data: bugTrendData.value.map(d => d.p2), lineStyle: { color: '#6366f1', width: 2 }, itemStyle: { color: '#6366f1' } },
        { name: 'P3', type: 'line', smooth: true, data: bugTrendData.value.map(d => d.p3), lineStyle: { color: '#94a3b8', width: 2 }, itemStyle: { color: '#94a3b8' } }
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

// 项目切换联动
watch(project, () => {
  activeTab.value = 'quality'
  loadAll()
})

async function loadAll() {
  loadVersions(); loadGateHistory(); loadTrends(); loadBugs()
  loadCases(); loadCaseTree(); loadPlans(); loadExecutions(); loadStandaloneCases(); loadJiraConfig()
}

onMounted(loadAll)
</script>

<template>
  <div>
    <FilterBar :loading="false" @search="loadAll">
      <template #actions>
        <el-button :icon="Setting" @click="jiraVisible = true; loadJiraConfig()">Jira 同步设置</el-button>
        <el-button type="primary" :icon="Plus" @click="openCaseDialog(); activeTab = 'cases'">新建用例</el-button>
      </template>
    </FilterBar>

    <PageCard no-padding style="margin-top: 16px">
      <el-tabs v-model="activeTab" class="page-tabs">
        <!-- ============ 测试用例 ============ -->
        <el-tab-pane label="测试用例" name="cases">
          <div class="tab-content cases-layout">
            <div class="case-tree">
              <div class="tree-head">用例模块</div>
              <el-tree :data="caseTree" :props="{ label: 'title', children: 'children' }" node-key="id"
                       default-expand-all highlight-current @node-click="(d: any) => { caseFilter.parentId = d.nodeType === 'MODULE' ? d.id : undefined; casePage = 1; loadCases() }">
                <template #default="{ data }">
                  <span class="tree-node">
                    <span>{{ data.nodeType === 'MODULE' ? '📁' : '🧪' }} {{ data.title }}</span>
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
              </div>
              <el-table :data="caseList" stripe size="small" v-loading="caseLoading">
                <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
                <el-table-column label="类型" width="80"><template #default="{ row }">{{ typeLabel(row.testType) }}</template></el-table-column>
                <el-table-column label="优先级" width="80"><template #default="{ row }"><el-tag size="small" :type="sevColor(row.priority)">{{ row.priority }}</el-tag></template></el-table-column>
                <el-table-column label="最近执行" width="90"><template #default="{ row }"><el-tag v-if="row.lastStatus" size="small" :type="execStatusColor(row.lastStatus)">{{ row.lastStatus }}</el-tag><span v-else style="color:var(--et-text-muted)">—</span></template></el-table-column>
                <el-table-column label="操作" width="190">
                  <template #default="{ row }">
                    <el-button size="small" text type="primary" @click="openCaseDetail(row)">详情</el-button>
                    <el-button size="small" text @click="openCaseDialog(row)">编辑</el-button>
                    <el-button size="small" text type="danger" @click="deleteCase(row)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-pagination v-model:current-page="casePage" :total="caseTotal" :page-size="casePageSize"
                             layout="total, prev, pager, next" style="margin-top: 10px; justify-content: flex-end" @current-change="loadCases" />
            </div>
          </div>
        </el-tab-pane>

        <!-- ============ 测试计划 ============ -->
        <el-tab-pane label="测试计划" name="plans">
          <div class="tab-content">
            <div class="toolbar-row">
              <el-button type="primary" size="small" :icon="Plus" @click="planDialog = true">新建计划</el-button>
              <el-button size="small" :icon="Refresh" @click="loadPlans">刷新</el-button>
            </div>
            <el-row :gutter="16">
              <el-col v-for="p in plans" :key="p.id" :xs="24" :sm="12" :md="8" style="margin-bottom: 12px">
                <PageCard>
                  <div class="plan-card" @click="openPlanDetail(p)">
                    <div class="plan-head">
                      <span class="plan-name">{{ p.name }}</span>
                      <el-tag size="small" :type="planStatusColor(p.status)" effect="dark">{{ p.status }}</el-tag>
                    </div>
                    <div class="plan-meta">{{ p.targetVersion ?? '—' }} · {{ p.total ?? 0 }} 个用例 · 通过 {{ p.passRate }}%</div>
                    <el-progress :percentage="p.progress ?? 0" :stroke-width="8" :color="(p.failed ?? 0) > 0 ? '#ef4444' : '#10b981'" style="margin-top: 8px" />
                    <div class="plan-actions">
                      <el-button v-for="t in planTransitions[p.status] ?? []" :key="t" size="small" text type="primary" @click.stop="changePlanStatus(p, t)">
                        {{ { RUNNING: '开始执行', DONE: '完成', DRAFT: '撤回', }[t] ?? t }}
                      </el-button>
                      <el-button size="small" text type="success" @click.stop="showPlanReport(p)">报告</el-button>
                      <el-button size="small" text type="danger" @click.stop="testPlanApi.deletePlan(project, p.id).then(() => { ElMessage.success('已删除'); loadPlans() })">删除</el-button>
                    </div>
                  </div>
                </PageCard>
              </el-col>
            </el-row>
            <el-empty v-if="!planLoading && plans.length === 0" description="暂无测试计划（可在质量看板一键生成）" :image-size="70" />
          </div>
        </el-tab-pane>

        <!-- ============ 执行记录 ============ -->
        <el-tab-pane label="执行记录" name="executions">
          <div class="tab-content">
            <div class="toolbar-row">
              <el-select v-model="execFilter.status" placeholder="状态" clearable size="small" style="width: 120px">
                <el-option v-for="s in ['PASSED','FAILED','BLOCKED','SKIPPED']" :key="s" :value="s" />
              </el-select>
              <el-button size="small" @click="execPage = 1; loadExecutions()">查询</el-button>
              <el-button type="primary" size="small" :icon="Plus" @click="loadStandaloneCases(); execDialog = true">记录执行</el-button>
            </div>
            <el-table :data="executions" stripe size="small">
              <el-table-column prop="executedAt" label="时间" width="170" />
              <el-table-column prop="title" label="用例" min-width="200" show-overflow-tooltip />
              <el-table-column label="来源" width="140">
                <template #default="{ row }">
                  <el-tag v-if="row.source === 'PLAN'" size="small" type="warning">计划: {{ row.planTitle }}</el-tag>
                  <el-tag v-else size="small" type="info">独立执行</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag size="small" :type="execStatusColor(row.status)">{{ row.status }}</el-tag></template></el-table-column>
              <el-table-column prop="executor" label="执行人" width="90" />
              <el-table-column prop="resultDetail" label="结果说明" min-width="180" show-overflow-tooltip />
            </el-table>
            <el-pagination v-model:current-page="execPage" :total="execTotal" :page-size="20"
                           layout="total, prev, pager, next" style="margin-top: 10px; justify-content: flex-end" @current-change="loadExecutions" />
          </div>
        </el-tab-pane>

        <!-- ============ 质量看板 ============ -->
        <el-tab-pane label="质量看板" name="quality">
          <div class="tab-content">
            <div class="toolbar-row">
              <el-select v-model="recFrom" size="small" style="width: 130px"><el-option v-for="v in versions" :key="v" :value="v" /></el-select>
              <span style="color:var(--et-text-muted)">→</span>
              <el-select v-model="recTo" size="small" style="width: 130px"><el-option v-for="v in versions" :key="v" :value="v" /></el-select>
              <el-button type="primary" size="small" @click="generateRecommendation" :loading="recLoading">生成推荐</el-button>
              <el-button size="small" type="warning" :disabled="!recommendation" @click="generatePlan">生成本轮计划</el-button>
            </div>

            <el-row v-if="recommendation" :gutter="16" style="margin-bottom: 12px">
              <el-col :xs="12" :sm="6"><StatCard label="推荐用例" :value="recommendation.totalCount ?? 0" color="#6366f1" /></el-col>
              <el-col :xs="12" :sm="6"><StatCard label="P0 用例" :value="recommendation.p0Count ?? 0" color="#ef4444" /></el-col>
              <el-col :xs="12" :sm="6"><StatCard label="回归用例" :value="recommendation.regressionCount ?? 0" color="#10b981" /></el-col>
              <el-col :xs="12" :sm="6"><StatCard label="风险等级" :value="recommendation.riskLevel ?? '—'" color="#f59e0b" /></el-col>
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
            </div>
            <el-alert v-if="readiness" style="margin-bottom: 12px" :type="readiness.ready ? 'success' : 'error'" :closable="false" :title="readiness.verdict ?? readiness.reason" />
            <el-alert v-if="gateResult" style="margin-bottom: 12px" :type="gateResult.passed ? 'success' : 'error'" :closable="false" :title="gateResult.verdict" />

            <el-row v-if="gateResult" :gutter="16">
              <el-col :xs="24" :md="12">
                <PageCard title="门禁明细" style="margin-bottom: 12px">
                  <div v-for="(c, key) in gateResult.checks" :key="key" class="gate-check">
                    <div class="gate-check-head">
                      <span :style="{ color: c.passed ? '#10b981' : '#ef4444', fontWeight: 700 }">{{ c.passed ? '✓' : '✕' }}</span>
                      <span class="gate-check-name">{{ ({ openBlockerBugs: '阻塞缺陷', failedTests: '失败用例', unacknowledgedBreaks: '破坏性变更', testCoverage: '测试覆盖', riskScore: '风险评分' } as any)[key] || key }}</span>
                      <span class="gate-check-value">{{ c.value }}{{ key === 'failedTests' && c.scope ? '（' + c.scope + '）' : '' }}</span>
                      <span class="gate-check-weight">{{ c.weight }}分</span>
                    </div>
                    <div class="gate-check-msg">{{ c.message }}</div>
                  </div>
                </PageCard>
              </el-col>
              <el-col :xs="24" :md="12">
                <PageCard title="门禁历史" style="margin-bottom: 12px">
                  <el-table :data="gateHistory" size="small">
                    <el-table-column prop="targetVersion" label="版本" width="100" />
                    <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag size="small" :type="row.status === 'PASSED' ? 'success' : 'danger'">{{ row.status }}</el-tag></template></el-table-column>
                    <el-table-column prop="checkedBy" label="执行人" width="80" />
                    <el-table-column prop="checkedAt" label="时间" width="170" />
                  </el-table>
                </PageCard>
              </el-col>
            </el-row>

            <el-divider content-position="left">质量趋势（近 30 天）</el-divider>
            <el-row :gutter="16">
              <el-col :xs="24" :md="12"><div id="exec-trend" style="height: 240px" /></el-col>
              <el-col :xs="24" :md="12"><div id="bug-trend" style="height: 240px" /></el-col>
            </el-row>
          </div>
        </el-tab-pane>

        <!-- ============ 缺陷追踪 ============ -->
        <el-tab-pane label="缺陷追踪" name="bugs">
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
            <el-table :data="bugs" stripe size="small">
              <el-table-column label="严重度" width="80"><template #default="{ row }"><el-tag size="small" :type="sevColor(row.severity)">{{ row.severity }}</el-tag></template></el-table-column>
              <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
              <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag size="small" :type="statusColor(row.status)">{{ row.status }}</el-tag></template></el-table-column>
              <el-table-column label="流转" width="180">
                <template #default="{ row }">
                  <el-dropdown v-if="(bugTransitions[row.status] ?? []).length" trigger="click" @command="(t: string) => transitionBug(row, t)">
                    <el-button size="small" text type="primary">流转 ▾</el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item v-for="t in bugTransitions[row.status]" :key="t" :command="t">{{ t }}</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </template>
              </el-table-column>
              <el-table-column prop="found_by" label="发现人" width="90" />
              <el-table-column label="操作" width="90"><template #default="{ row }"><el-button size="small" text type="primary" @click="openBugDetail(row)">详情</el-button></template></el-table-column>
            </el-table>
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
        <el-form-item label="步骤（UI DSL）">
          <div class="steps-editor">
            <div v-for="(s, i) in caseForm.steps" :key="i" class="step-row">
              <el-select v-model="s.action" size="small" style="width: 110px">
                <el-option v-for="a in ['open','click','input','select','assertText','assertUrl','screenshot','waitFor']" :key="a" :value="a" />
              </el-select>
              <el-input v-model="s.target" size="small" placeholder="target（选择器/URL）" style="flex:1" />
              <el-input v-model="s.value" size="small" placeholder="value（值）" style="flex:1" />
              <el-input v-model="s.expected" size="small" placeholder="expected（断言期望）" style="flex:1" />
              <el-button size="small" text type="danger" @click="removeStep(i)">✕</el-button>
            </div>
            <el-button size="small" text type="primary" @click="addStep">+ 添加步骤</el-button>
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
          <el-tag size="small">{{ typeLabel(caseDetail.testType) }}</el-tag>
          <el-tag size="small" :type="sevColor(caseDetail.priority)">{{ caseDetail.priority }}</el-tag>
          <span v-if="caseDetail.relatedFiles" class="meta-text">文件: {{ caseDetail.relatedFiles }}</span>
        </div>
        <div v-if="caseDetail.steps" class="step-preview">
          <div v-for="(s, i) in parseSteps(caseDetail.steps)" :key="i" class="step-preview-row">
            <el-tag size="small" type="info">{{ s.action }}</el-tag>
            <span>{{ s.target }}</span><span v-if="s.value" style="color:var(--et-text-muted)">= {{ s.value }}</span>
            <span v-if="s.expected" style="color:#10b981">→ {{ s.expected }}</span>
          </div>
        </div>
        <div class="bug-list">
          <h4>关联缺陷</h4>
          <div v-for="b in caseDetail.bugs ?? []" :key="b.id" class="bug-item">
            <el-tag size="small" :type="sevColor(b.severity)">{{ b.severity }}</el-tag>
            <span>{{ b.title }}</span>
            <el-button size="small" text type="danger" @click="unlinkCaseBug(b.id)">取消关联</el-button>
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
          <el-tag size="small" :type="planStatusColor(planDetail.status)" effect="dark">{{ planDetail.status }}</el-tag>
          <span class="meta-text">目标: {{ planDetail.targetVersion ?? '—' }} · 基线: {{ planDetail.fromVersion ?? '—' }}</span>
        </div>
        <div class="toolbar-row">
          <el-button v-for="t in planTransitions[planDetail.status] ?? []" :key="t" size="small" type="primary" plain @click="changePlanStatus(planDetail, t)">
            {{ { RUNNING: '开始执行', DONE: '完成', DRAFT: '撤回' }[t] ?? t }}
          </el-button>
          <el-button size="small" :icon="Document" @click="showPlanReport(planDetail)">计划报告</el-button>
          <el-button size="small" :icon="Plus" @click="addCaseVisible = true">追加用例</el-button>
        </div>
        <el-table :data="planDetail.items ?? []" stripe size="small">
          <el-table-column prop="title" label="用例" min-width="180" show-overflow-tooltip />
          <el-table-column label="优先级" width="70"><template #default="{ row }"><el-tag size="small" :type="sevColor(row.priority)">{{ row.priority }}</el-tag></template></el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-dropdown trigger="click" @command="(s: string) => executeItem(row, s)">
                <el-tag size="small" :type="execStatusColor(row.status)" style="cursor:pointer">{{ row.status }} ▾</el-tag>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-for="s in ['PASSED','FAILED','BLOCKED','SKIPPED']" :key="s" :command="s">{{ s }}</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-table-column>
          <el-table-column prop="executor" label="执行人" width="80" />
          <el-table-column label="操作" width="80"><template #default="{ row }"><el-button v-if="row.status === 'PENDING'" size="small" text type="danger" @click="removeItem(row)">移除</el-button></template></el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <!-- 追加用例弹窗 -->
    <el-dialog v-model="addCaseVisible" title="追加用例" width="520px">
      <div class="toolbar-row">
        <el-input v-model="caseFilter.keyword" placeholder="搜索用例" size="small" style="width: 180px" @keyup.enter="loadCases" />
      </div>
      <el-table :data="caseList" stripe size="small" @selection-change="(rows: any[]) => addCaseSelected = rows.map(r => r.id)">
        <el-table-column type="selection" width="40" />
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column label="优先级" width="70"><template #default="{ row }"><el-tag size="small" :type="sevColor(row.priority)">{{ row.priority }}</el-tag></template></el-table-column>
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
          <el-col :span="4"><StatCard label="总数" :value="planReport.total" color="#6366f1" /></el-col>
          <el-col :span="4"><StatCard label="通过" :value="planReport.passed" color="#10b981" /></el-col>
          <el-col :span="4"><StatCard label="失败" :value="planReport.failed" color="#ef4444" /></el-col>
          <el-col :span="4"><StatCard label="阻塞" :value="planReport.blocked" color="#f59e0b" /></el-col>
          <el-col :span="4"><StatCard label="跳过" :value="planReport.skipped" color="#94a3b8" /></el-col>
          <el-col :span="4"><StatCard label="通过率" :value="planReport.passRate + '%'" color="#10b981" /></el-col>
        </el-row>
        <h4 style="margin: 8px 0">失败用例明细</h4>
        <el-table :data="planReport.failCases ?? []" stripe size="small">
          <el-table-column prop="title" label="用例" min-width="200" show-overflow-tooltip />
          <el-table-column label="优先级" width="70"><template #default="{ row }"><el-tag size="small" :type="sevColor(row.priority)">{{ row.priority }}</el-tag></template></el-table-column>
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
          <el-tag size="small" :type="sevColor(bugDetail.severity)">{{ bugDetail.severity }}</el-tag>
          <el-tag size="small" :type="statusColor(bugDetail.status)">{{ bugDetail.status }}</el-tag>
          <span v-if="bugDetail.external_key" class="meta-text">Jira: {{ bugDetail.external_key }}</span>
        </div>
        <p v-if="bugDetail.description" style="font-size:13px;color:var(--et-text-secondary)">{{ bugDetail.description }}</p>
        <h4 style="margin: 10px 0 6px">关联用例</h4>
        <el-table :data="bugDetail.linkedCases ?? []" size="small">
          <el-table-column prop="title" label="用例" min-width="200" />
          <el-table-column label="优先级" width="70"><template #default="{ row }"><el-tag size="small" :type="sevColor(row.priority)">{{ row.priority }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="100"><template #default="{ row }"><el-button size="small" text type="danger" @click="unlinkBugCase(row.id)">取消关联</el-button></template></el-table-column>
        </el-table>
        <el-button size="small" text type="primary" style="margin-top: 6px" @click="linkBugCase">+ 关联用例</el-button>
        <h4 style="margin: 12px 0 6px">关联变更</h4>
        <div v-for="c in bugDetail.linkedChanges ?? []" :key="c.eventId" class="bug-item">
          <el-tag size="small" type="info">{{ c.linkType }}</el-tag>
          <code style="font-size: 11px">{{ c.commitSha?.substring(0, 8) ?? c.eventId?.substring(0, 8) }}</code>
          <span style="font-size: 12px; color: var(--et-text-secondary)">{{ c.summary ?? c.author }}</span>
        </div>
      </template>
    </el-dialog>

    <!-- Jira 设置弹窗 -->
    <el-dialog v-model="jiraVisible" title="Jira 缺陷双向同步" width="520px">
      <el-alert type="info" :closable="false" title="配置后：定时拉取 Jira 问题为缺陷；本地缺陷新建/流转自动推送 Jira。未配置时不影响本地流程。" style="margin-bottom: 12px" />
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
  </div>
</template>

<style scoped>
.page-tabs :deep(.el-tabs__header) { margin: 0; padding: 0 20px }
.tab-content { padding: 16px 20px 20px }
.toolbar-row { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; font-size: 13px; color: var(--et-text-secondary); flex-wrap: wrap }
.cases-layout { display: flex; gap: 16px }
.case-tree { width: 220px; flex-shrink: 0; border-right: 1px solid var(--et-border); padding-right: 12px }
.tree-head { font-size: 13px; font-weight: 600; margin-bottom: 10px; color: var(--et-text-secondary) }
.tree-node { display: flex; align-items: center; gap: 6px; font-size: 13px }
.tree-count { font-size: 11px; color: var(--et-text-muted); background: var(--et-page-bg); border-radius: 8px; padding: 0 6px }
.case-list { flex: 1; min-width: 0 }
.plan-card { cursor: pointer }
.plan-head { display: flex; align-items: center; justify-content: space-between; gap: 8px }
.plan-name { font-weight: 600; font-size: 14px }
.plan-meta { font-size: 12px; color: var(--et-text-muted); margin-top: 6px }
.plan-actions { margin-top: 10px; display: flex; flex-wrap: wrap; gap: 4px }
.gate-check { padding: 8px 10px; border: 1px solid var(--et-border); border-radius: 8px; margin-bottom: 8px; background: var(--et-page-bg) }
.gate-check-head { display: flex; align-items: center; gap: 8px }
.gate-check-name { font-weight: 600; font-size: 13px; flex: 1 }
.gate-check-value { font-size: 12px; color: var(--et-text-secondary) }
.gate-check-weight { font-size: 11px; color: var(--et-text-muted) }
.gate-check-msg { font-size: 12px; color: var(--et-text-muted); margin-top: 4px }
.meta-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px }
.meta-text { font-size: 12px; color: var(--et-text-muted) }
.steps-editor { width: 100%; display: flex; flex-direction: column; gap: 6px }
.step-row { display: flex; gap: 6px; align-items: center }
.step-preview { margin: 10px 0; display: flex; flex-direction: column; gap: 6px }
.step-preview-row { display: flex; gap: 8px; align-items: center; font-size: 12px; font-family: monospace }
.bug-list h4 { margin: 10px 0 6px; font-size: 13px }
.bug-item { display: flex; align-items: center; gap: 8px; padding: 6px 0; border-bottom: 1px dashed var(--et-border); font-size: 13px }
</style>
