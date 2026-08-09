<template>
  <div class="ui-page">
    <!-- 顶部 Hero -->
    <div class="et-hero hero-row" style="margin-bottom: 16px">
      <div class="hero-main">
        <div class="hero-icon et-g-ic g-amber">UI</div>
        <div>
          <h2>UI 测试</h2>
          <div class="et-hero-sub">基于 Selenium 的低代码浏览器自动化，录制式步骤编排</div>
        </div>
      </div>
      <div class="hero-actions">
        <el-button size="small" type="primary" :icon="Plus" @click="createTest">新建用例</el-button>
      </div>
    </div>
    <div class="ui-container">
      <!-- 左侧用例列表 -->
      <div class="ui-list">
        <div class="toolbar-row">
          <el-button size="small" :icon="Refresh" @click="loadTests">刷新</el-button>
        </div>
        <el-table
          v-loading="loading"
          :data="tests"
          highlight-current-row
          class="ui-table"
          @current-change="selectTest"
          :current-row-key="currentId"
        >
          <el-table-column prop="name" label="用例名称" min-width="180" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <span class="status-pill" :class="statusClass(row.status)">{{ statusLabel(row.status) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" width="150" />
        </el-table>
        <el-empty v-if="!loading && !tests.length" description="暂无 UI 用例" :image-size="60" />
      </div>

      <!-- 右侧编辑区 -->
      <div class="ui-edit" v-if="currentTest">
        <div class="edit-header">
          <el-input v-model="form.name" placeholder="用例名称" class="name-input" />
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
        </div>

        <el-input v-model="form.baseUrl" placeholder="被测页面基础地址，如 https://example.com" class="base-url" />

        <el-input v-model="form.description" type="textarea" :rows="2" placeholder="用例描述（可选）" class="desc-input" />

        <div class="steps-container">
          <div class="steps-header">
            <span class="steps-title">执行步骤</span>
            <el-dropdown @command="addStep">
              <el-button size="small" type="primary" :icon="Plus">添加步骤</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="OPEN">打开页面 (OPEN)</el-dropdown-item>
                  <el-dropdown-item command="CLICK">点击 (CLICK)</el-dropdown-item>
                  <el-dropdown-item command="INPUT">输入 (INPUT)</el-dropdown-item>
                  <el-dropdown-item command="ASSERT_TEXT">断言文本 (ASSERT_TEXT)</el-dropdown-item>
                  <el-dropdown-item command="ASSERT_URL">断言 URL (ASSERT_URL)</el-dropdown-item>
                  <el-dropdown-item command="WAIT">等待 (WAIT)</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>

          <div class="steps-list">
            <div v-for="(step, index) in form.steps" :key="index" class="step-item">
              <div class="step-row">
                <span class="step-index">{{ index + 1 }}</span>
                <el-tag size="small" class="step-type-tag">{{ step.type }}</el-tag>
                <template v-if="step.type === 'OPEN'">
                  <el-input v-model="step.url" placeholder="路径，如 /login（留空用基础地址）" size="small" />
                </template>
                <template v-else-if="step.type === 'WAIT'">
                  <el-input-number v-model="step.value" :min="0" :max="60000" :step="500" size="small" />
                  <span class="step-ms">ms</span>
                </template>
                <template v-else>
                  <el-input v-model="step.selector" placeholder="CSS 选择器，如 #username" size="small" class="sel-input" />
                  <el-input v-if="step.type === 'INPUT'" v-model="step.value" placeholder="输入值" size="small" class="val-input" />
                  <el-input v-if="step.type === 'ASSERT_TEXT' || step.type === 'ASSERT_URL'" v-model="step.value" placeholder="期望包含的文本/URL" size="small" class="val-input" />
                </template>
                <div class="step-ops">
                  <el-button size="small" :icon="ArrowUp" @click="moveStep(index, -1)" :disabled="index === 0" />
                  <el-button size="small" :icon="ArrowDown" @click="moveStep(index, 1)" :disabled="index === form.steps.length - 1" />
                  <el-button size="small" :icon="Delete" type="danger" @click="removeStep(index)" />
                </div>
              </div>
            </div>
            <el-empty v-if="!form.steps.length" description="暂无步骤" :image-size="50" />
          </div>
        </div>

        <div class="edit-actions">
          <el-button type="primary" :loading="saving" @click="saveTest">
            <el-icon :size="15"><Check /></el-icon> 保存
          </el-button>
          <el-button type="success" class="ops-btn" :loading="running" @click="runTest">
            <el-icon :size="15"><VideoPlay /></el-icon> 运行
          </el-button>
          <el-button type="danger" plain @click="deleteTest">
            <el-icon :size="15"><Delete /></el-icon> 删除
          </el-button>
        </div>

        <!-- 运行结果 -->
        <div v-if="runResult" class="run-result">
          <div class="result-header">
            <span class="result-title">运行结果</span>
            <span class="result-verdict" :class="verdictClass(runResult.verdict)">{{ runResult.verdict }}</span>
            <span class="result-time">耗时 {{ runResult.durationMs }}ms</span>
          </div>
          <div v-if="runResult.error" class="run-error">{{ runResult.error }}</div>
          <div v-for="rs in runResult.steps" :key="rs.index" class="result-step">
            <div class="step-summary">
              <span class="step-status" :class="stepStatusClass(rs.status)">{{ rs.status }}</span>
              <span class="step-info">{{ rs.type }}: {{ stepDesc(rs) }}</span>
            </div>
            <div v-if="rs.actual !== undefined && rs.type.includes('ASSERT')" class="step-detail">
              实际：{{ rs.actual }}
            </div>
            <div v-if="rs.error" class="step-error">{{ rs.error }}</div>
          </div>
        </div>
      </div>
      <div v-else class="empty-state">
        <el-empty description="请选择或新建 UI 测试用例" :image-size="80" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Plus, Refresh, Delete, ArrowUp, ArrowDown, Check, VideoPlay
} from '@element-plus/icons-vue'
import { useProjectStore } from '../stores/project'
import { uiTestApi, type UiStep, type UiTest, type UiRunResult } from '../api'

const projectStore = useProjectStore()
const { current: projectKey } = storeToRefs(projectStore)

const loading = ref(false)
const tests = ref<UiTest[]>([])
const currentId = ref<number | null>(null)
const currentTest = ref<UiTest | null>(null)
const saving = ref(false)
const running = ref(false)
const runResult = ref<UiRunResult | null>(null)

const form = reactive({
  name: '',
  description: '',
  baseUrl: '',
  enabled: true,
  steps: [] as (UiStep & { value?: any })[]
})

function emptyStep(type: string): UiStep & { value?: any } {
  const s: UiStep & { value?: any } = { type }
  if (type === 'OPEN') s.url = '/'
  if (type === 'CLICK' || type === 'INPUT' || type === 'ASSERT_TEXT') s.selector = ''
  if (type === 'WAIT') s.value = 1000
  return s
}

async function loadTests() {
  if (!projectKey.value) return
  loading.value = true
  try {
    tests.value = await uiTestApi.list(projectKey.value)
  } catch {
    ElMessage.error('加载 UI 用例失败')
  } finally {
    loading.value = false
  }
}

async function selectTest(row?: UiTest) {
  if (!row || !projectKey.value) return
  currentId.value = row.id
  runResult.value = null
  try {
    const detail = await uiTestApi.detail(projectKey.value, row.id)
    currentTest.value = detail
    form.name = detail.name
    form.description = detail.description ?? ''
    form.baseUrl = detail.baseUrl ?? ''
    form.enabled = detail.enabled
    form.steps = (detail.steps ?? []).map((s) => ({ ...s }))
  } catch {
    ElMessage.error('加载用例详情失败')
  }
}

function createTest() {
  currentTest.value = { id: 0, name: '新 UI 用例', steps: [], enabled: true, status: 'PENDING' } as UiTest
  currentId.value = null
  runResult.value = null
  form.name = '新 UI 用例'
  form.description = ''
  form.baseUrl = ''
  form.enabled = true
  form.steps = []
}

function addStep(type: string) {
  form.steps.push(emptyStep(type))
}

function moveStep(index: number, dir: number) {
  const target = index + dir
  if (target < 0 || target >= form.steps.length) return
  const tmp = form.steps[target]
  form.steps[target] = form.steps[index]
  form.steps[index] = tmp
}

function removeStep(index: number) {
  form.steps.splice(index, 1)
}

async function saveTest() {
  if (!projectKey.value || !form.name.trim()) {
    ElMessage.warning('请输入用例名称')
    return
  }
  saving.value = true
  try {
    if (currentId.value == null || currentId.value === 0) {
      const { id } = await uiTestApi.create(projectKey.value, {
        name: form.name, description: form.description, baseUrl: form.baseUrl,
        steps: form.steps, enabled: form.enabled
      })
      currentId.value = id
    } else {
      await uiTestApi.update(projectKey.value, currentId.value, {
        name: form.name, description: form.description, baseUrl: form.baseUrl,
        steps: form.steps, enabled: form.enabled
      })
    }
    ElMessage.success('已保存')
    await loadTests()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function runTest() {
  if (!projectKey.value || currentId.value == null) {
    ElMessage.warning('请先保存用例再运行')
    return
  }
  running.value = true
  runResult.value = null
  try {
    runResult.value = await uiTestApi.run(projectKey.value, currentId.value)
    ElMessage.success(`执行完成：${runResult.value.verdict}`)
    await loadTests()
  } catch {
    ElMessage.error('执行失败')
  } finally {
    running.value = false
  }
}

async function deleteTest() {
  if (!projectKey.value || currentId.value == null) return
  try {
    await ElMessageBox.confirm(`确定删除 UI 用例「${form.name}」吗？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await uiTestApi.remove(projectKey.value, currentId.value)
    ElMessage.success('已删除')
    currentTest.value = null
    currentId.value = null
    await loadTests()
  } catch {
    ElMessage.error('删除失败')
  }
}

function stepDesc(rs: any): string {
  if (rs.url) return rs.url
  if (rs.selector) return rs.selector
  if (rs.value !== undefined) return String(rs.value)
  return rs.type
}

const statusMap: Record<string, { label: string; cls: string }> = {
  PENDING: { label: '未执行', cls: 'st-pending' },
  RUNNING: { label: '执行中', cls: 'st-running' },
  PASSED: { label: '通过', cls: 'st-passed' },
  FAILED: { label: '失败', cls: 'st-failed' },
  SKIPPED: { label: '跳过', cls: 'st-skipped' }
}
function statusLabel(s: string): string { return statusMap[s]?.label ?? s }
function statusClass(s: string): string { return statusMap[s]?.cls ?? 'st-pending' }
function verdictClass(v: string): string {
  return v === 'PASSED' ? 'vd-passed' : v === 'SKIPPED' ? 'vd-skipped' : 'vd-failed'
}
function stepStatusClass(s: string): string {
  return s === 'PASSED' ? 'ss-passed' : s === 'SKIPPED' ? 'ss-skipped' : 'ss-failed'
}

onMounted(async () => {
  await loadTests()
  if (tests.value.length) selectTest(tests.value[0])
})
</script>

<style scoped>
.ui-container {
  display: flex;
  gap: 16px;
  height: calc(100vh - 260px);
  min-height: 480px;
}
.ui-list {
  width: 340px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.toolbar-row { display: flex; gap: 8px; }
.ui-table { flex: 1; }
.ui-edit {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  overflow-y: auto;
}
.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
.edit-header { display: flex; align-items: center; gap: 12px; }
.name-input { flex: 1; }
.base-url, .desc-input { width: 100%; }
.steps-container { display: flex; flex-direction: column; gap: 10px; }
.steps-header { display: flex; align-items: center; justify-content: space-between; }
.steps-title { font-weight: 600; font-size: 14px; }
.steps-list { display: flex; flex-direction: column; gap: 8px; }
.step-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  border-radius: 10px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
}
.step-row { display: flex; align-items: center; gap: 8px; }
.step-index {
  width: 22px; height: 22px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-c));
  color: #fff; font-size: 12px; font-weight: 700; flex-shrink: 0;
}
.step-type-tag { flex-shrink: 0; }
.sel-input { flex: 1; }
.val-input { flex: 1; }
.step-ms { font-size: 12px; color: var(--et-text-muted); flex-shrink: 0; }
.step-ops { display: flex; gap: 4px; flex-shrink: 0; }
.edit-actions { display: flex; gap: 10px; }
.ops-btn { font-weight: 600; border-radius: 8px; background: color-mix(in srgb, currentColor 12%, transparent); }
.ops-btn:hover { background: color-mix(in srgb, currentColor 22%, transparent); box-shadow: 0 0 12px var(--et-glow); }

/* 状态胶囊 */
.status-pill {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 3px 10px; border-radius: 20px; font-size: 11.5px; font-weight: 700;
}
.status-pill::before { content: ''; width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.st-pending { color: var(--et-text-muted); background: var(--et-bg-muted); }
.st-running { color: var(--et-grad-c); background: rgba(56, 225, 255, 0.12); }
.st-passed { color: var(--et-ok); background: rgba(52, 211, 153, 0.12); }
.st-failed { color: var(--et-danger); background: rgba(251, 113, 133, 0.12); }
.st-skipped { color: var(--et-warn); background: rgba(251, 191, 36, 0.12); }

/* 运行结果 */
.run-result {
  border-radius: 12px;
  border: 1px solid var(--et-border);
  background: var(--et-bg-muted);
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.result-header { display: flex; align-items: center; gap: 12px; }
.result-title { font-weight: 700; }
.result-time { font-size: 12px; color: var(--et-text-muted); }
.result-verdict { font-weight: 800; font-size: 13px; }
.vd-passed { color: var(--et-ok); }
.vd-failed { color: var(--et-danger); }
.vd-skipped { color: var(--et-warn); }
.run-error { color: var(--et-danger); font-size: 13px; }
.result-step { display: flex; flex-direction: column; gap: 4px; padding: 8px 10px; border-radius: 8px; background: var(--et-card-solid); }
.step-summary { display: flex; align-items: center; gap: 10px; }
.step-status { font-size: 11px; font-weight: 800; padding: 2px 8px; border-radius: 12px; }
.ss-passed { color: var(--et-ok); background: rgba(52, 211, 153, 0.12); }
.ss-failed { color: var(--et-danger); background: rgba(251, 113, 133, 0.12); }
.ss-skipped { color: var(--et-warn); background: rgba(251, 191, 36, 0.12); }
.step-info { font-size: 13px; }
.step-detail { font-size: 12px; color: var(--et-text-secondary); }
.step-error { font-size: 12.5px; color: var(--et-danger); }
</style>