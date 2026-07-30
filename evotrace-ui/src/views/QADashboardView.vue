<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../api/client'

const project = ref('mall')
const activeTab = ref('tests')
const loading = ref(false)

// Test recommendation
const fromVersion = ref('v2.4.9')
const toVersion = ref('v2.5.0')
const recommendation = ref<any>(null)
const releaseReadiness = ref<any>(null)

// Bugs
const bugs = ref<any[]>([])
const bugFormVisible = ref(false)
const bugForm = ref({ title: '', severity: 'P2', foundBy: 'QA', foundVersion: '', assignedTo: '', requirementId: null as number | null, description: '' })

// Quality gate
const gateVersion = ref('')
const gateResult = ref<any>(null)
const gateHistory = ref<any[]>([])

// Test execution
const testCases = ref<any[]>([])
const testFormVisible = ref(false)
const testForm = ref({ title: '', testType: 'FUNCTIONAL', priority: 'P2', steps: '', relatedFiles: '', relatedApis: '', requirementId: null as number | null })

async function loadAll() {
  loading.value = true
  try {
    const [bugsData, gateHist] = await Promise.all([
      client.get(`/pm/bugs?projectKey=${project.value}`),
      client.get(`/pm/quality-gate/history?projectKey=${project.value}`)
    ])
    bugs.value = bugsData as any[]
    gateHistory.value = gateHist as any[]
  } catch { /* server not ready */ }
  loading.value = false
}

async function getRecommendation() {
  loading.value = true
  try {
    recommendation.value = await client.get(`/pm/test-recommendation?projectKey=${project.value}&fromVersion=${fromVersion.value}&toVersion=${toVersion.value}`)
  } catch { ElMessage.error('获取推荐失败') }
  loading.value = false
}

async function checkReadiness() {
  loading.value = true
  try {
    releaseReadiness.value = await client.get(`/pm/release-readiness?projectKey=${project.value}&targetVersion=${toVersion.value}`)
  } catch { ElMessage.error('检查失败') }
  loading.value = false
}

async function runQualityGate() {
  if (!gateVersion.value) return
  loading.value = true
  try {
    gateResult.value = await client.post(`/pm/quality-gate/check?projectKey=${project.value}&targetVersion=${gateVersion.value}`)
  } catch { ElMessage.error('检查失败') }
  loading.value = false
}

async function createBug() {
  try {
    await client.post(`/pm/bugs?projectKey=${project.value}`, bugForm.value)
    ElMessage.success('缺陷已创建')
    bugFormVisible.value = false
    loadAll()
  } catch { ElMessage.error('创建失败') }
}

async function createTestCase() {
  try {
    await client.post(`/pm/requirements?projectKey=${project.value}`, {
      title: testForm.value.title, testType: testForm.value.testType,
      priority: testForm.value.priority, steps: testForm.value.steps,
      relatedFiles: testForm.value.relatedFiles, relatedApis: testForm.value.relatedApis
    })
    ElMessage.success('测试用例已创建')
    testFormVisible.value = false
  } catch { ElMessage.error('创建失败') }
}

const severityColor = (s: string) => ({ P0: 'danger', P1: 'warning', P2: 'info', P3: '' } as any)[s]
const testTypeLabel = (t: string) => ({ FUNCTIONAL: '功能', REGRESSION: '回归', PERF: '性能', SECURITY: '安全', API: '接口' } as any)[t] || t

onMounted(loadAll)
</script>

<template>
  <div>
    <el-card shadow="never">
      <el-form inline>
        <el-form-item label="项目"><el-input v-model="project" style="width: 140px" /></el-form-item>
        <el-form-item><el-button type="primary" @click="loadAll">刷新</el-button></el-form-item>
        <el-form-item><el-button @click="bugFormVisible = true">提 Bug</el-button></el-form-item>
        <el-form-item><el-button @click="testFormVisible = true">写用例</el-button></el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" style="margin-top: 16px">
      <el-tabs v-model="activeTab">
        <!-- 测试推荐 -->
        <el-tab-pane label="🧪 测试推荐" name="tests">
          <el-form inline style="margin-bottom: 12px">
            <el-form-item label="从版本"><el-input v-model="fromVersion" style="width: 140px" /></el-form-item>
            <el-form-item label="到版本"><el-input v-model="toVersion" style="width: 140px" /></el-form-item>
            <el-form-item><el-button type="primary" :loading="loading" @click="getRecommendation">生成推荐</el-button></el-form-item>
            <el-form-item><el-button :loading="loading" @click="checkReadiness">发布准入检查</el-button></el-form-item>
          </el-form>

          <!-- Release readiness -->
          <el-alert v-if="releaseReadiness" :type="releaseReadiness.ready ? 'success' : 'error'"
            :title="releaseReadiness.verdict" :closable="false" style="margin-bottom: 12px" />

          <!-- Recommendation -->
          <div v-if="recommendation">
            <el-row :gutter="16">
              <el-col :span="8"><el-statistic title="推荐用例" :value="recommendation.totalCount" /></el-col>
              <el-col :span="8"><el-statistic title="P0 用例" :value="recommendation.p0Count" /></el-col>
              <el-col :span="8"><el-statistic title="回归用例" :value="recommendation.regressionCount" /></el-col>
            </el-row>
            <el-alert type="info" :closable="false" style="margin: 12px 0" :title="recommendation.regressionScope" />
            <el-table v-if="recommendation.recommendedTests?.length" :data="recommendation.recommendedTests" stripe size="small" max-height="400">
              <el-table-column prop="title" label="用例名称" min-width="200" />
              <el-table-column label="类型" width="80"><template #default="{ row }">{{ testTypeLabel(row.test_type) }}</template></el-table-column>
              <el-table-column prop="priority" label="优先级" width="80" />
            </el-table>
          </div>
        </el-tab-pane>

        <!-- Bug 管理 -->
        <el-tab-pane label="🐛 缺陷追踪" name="bugs">
          <el-table :data="bugs" stripe v-loading="loading" size="small">
            <el-table-column label="严重" width="80">
              <template #default="{ row }"><el-tag :type="severityColor(row.severity)" size="small">{{ row.severity }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="title" label="标题" min-width="240" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.status === 'OPEN' ? 'danger' : row.status === 'FIXED' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="found_by" label="发现人" width="80" />
            <el-table-column prop="found_version" label="发现版本" width="100" />
            <el-table-column prop="fixed_version" label="修复版本" width="100" />
            <el-table-column label="关联变更" width="80">
              <template #default="{ row }">{{ row.changeCount ?? 0 }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 质量门禁 -->
        <el-tab-pane label="🛡 质量门禁" name="gate">
          <el-form inline style="margin-bottom: 12px">
            <el-form-item label="目标版本"><el-input v-model="gateVersion" placeholder="v2.5.0" /></el-form-item>
            <el-form-item><el-button type="primary" :loading="loading" @click="runQualityGate">执行检查</el-button></el-form-item>
          </el-form>
          <el-alert v-if="gateResult" :type="gateResult.passed ? 'success' : 'error'" :title="gateResult.verdict" :closable="false" style="margin-bottom: 12px" />
          <el-table v-if="gateHistory.length" :data="gateHistory" size="small" stripe>
            <el-table-column prop="targetVersion" label="版本" width="120" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }"><el-tag :type="row.status === 'PASSED' ? 'success' : 'danger'">{{ row.status }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="checkedBy" label="检查人" width="100" />
            <el-table-column prop="checkedAt" label="时间" width="180" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- Bug Form Dialog -->
    <el-dialog v-model="bugFormVisible" title="提交缺陷" width="500px">
      <el-form label-width="90px">
        <el-form-item label="标题"><el-input v-model="bugForm.title" /></el-form-item>
        <el-form-item label="严重程度"><el-select v-model="bugForm.severity"><el-option v-for="s in ['P0','P1','P2','P3']" :key="s" :value="s" /></el-select></el-form-item>
        <el-form-item label="发现人"><el-input v-model="bugForm.foundBy" /></el-form-item>
        <el-form-item label="发现版本"><el-input v-model="bugForm.foundVersion" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="bugForm.description" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="bugFormVisible = false">取消</el-button>
        <el-button type="primary" @click="createBug">提交</el-button>
      </template>
    </el-dialog>

    <!-- Test Case Form Dialog -->
    <el-dialog v-model="testFormVisible" title="新增测试用例" width="500px">
      <el-form label-width="90px">
        <el-form-item label="标题"><el-input v-model="testForm.title" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="testForm.testType"><el-option v-for="t in ['FUNCTIONAL','REGRESSION','API','PERF','SECURITY']" :key="t" :label="testTypeLabel(t)" :value="t" /></el-select></el-form-item>
        <el-form-item label="优先级"><el-select v-model="testForm.priority"><el-option v-for="p in ['P0','P1','P2','P3']" :key="p" :value="p" /></el-select></el-form-item>
        <el-form-item label="关联文件"><el-input v-model="testForm.relatedFiles" placeholder="逗号分隔，如: src/OrderService.java" /></el-form-item>
        <el-form-item label="关联接口"><el-input v-model="testForm.relatedApis" placeholder="逗号分隔，如: POST /order/create" /></el-form-item>
        <el-form-item label="测试步骤"><el-input v-model="testForm.steps" type="textarea" :rows="3" placeholder="JSON: [{step, expected}]" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="testFormVisible = false">取消</el-button>
        <el-button type="primary" @click="createTestCase">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.el-statistic { text-align: center }
</style>
