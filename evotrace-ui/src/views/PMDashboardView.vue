<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import FilterBar from '../components/FilterBar.vue'
import PageCard from '../components/PageCard.vue'
import StatCard from '../components/StatCard.vue'
import client from '../api/client'

const project = ref('mall')
const activeTab = ref('kanban')
const loading = ref(false)

// Requirement kanban
const requirements = ref<any[]>([])
const statusColumns = ['DRAFT', 'REVIEW', 'DEVELOPING', 'TESTING', 'DONE']
const statusLabels: Record<string, string> = { DRAFT: '草稿', REVIEW: '评审中', DEVELOPING: '开发中', TESTING: '测试中', DONE: '已完成' }
const statusColors: Record<string, string> = { DRAFT: 'info', REVIEW: 'warning', DEVELOPING: 'primary', TESTING: 'danger', DONE: 'success' }

// Quality gate
const gateVersion = ref('')
const gateResult = ref<any>(null)

// Notifications
const notifications = ref<any[]>([])
const reqFormVisible = ref(false)
const reqForm = ref({ title: '', priority: 'P2', status: 'DRAFT', productManager: '', assignedTo: '', targetVersion: '', description: '', prototypeUrl: '', designUrl: '' })

// Bug & test stats
const bugStats = ref<any[]>([])
const reqStats = ref<any[]>([])

function getNextStatus(current: string): string[] {
  const flow: Record<string, string[]> = {
    DRAFT: ['REVIEW'], REVIEW: ['DEVELOPING'], DEVELOPING: ['TESTING'], TESTING: ['DONE'], DONE: []
  }
  return flow[current] || []
}

async function loadAll() {
  loading.value = true
  try {
    const [reqs, notifs, dash] = await Promise.all([
      client.get(`/pm/requirements?projectKey=${project.value}`),
      client.get(`/pm/notifications?projectKey=${project.value}&role=PM`),
      client.get(`/pm/dashboard?projectKey=${project.value}`)
    ])
    requirements.value = reqs as unknown as any[]
    notifications.value = notifs as unknown as any[]
    bugStats.value = (dash as any).bugStats || []
    reqStats.value = (dash as any).requirementStats || []
  } catch { /* server not ready */ }
  loading.value = false
}

function kanbanByStatus(status: string) {
  return requirements.value.filter(r => r.status === status)
}

async function updateStatus(id: number, status: string) {
  try {
    await client.put(`/pm/requirements/${id}/status?status=${status}&actor=PM`)
    ElMessage.success('状态已更新')
    loadAll()
  } catch { ElMessage.error('更新失败') }
}

async function createRequirement() {
  try {
    await client.post(`/pm/requirements?projectKey=${project.value}`, reqForm.value)
    ElMessage.success('需求已创建')
    reqFormVisible.value = false
    loadAll()
  } catch { ElMessage.error('创建失败') }
}

async function runQualityGate() {
  if (!gateVersion.value) return
  loading.value = true
  try {
    gateResult.value = await client.post(`/pm/quality-gate/check?projectKey=${project.value}&targetVersion=${gateVersion.value}`)
  } catch { ElMessage.error('检查失败') }
  loading.value = false
}

async function markRead(notifId: number) {
  await client.put(`/pm/notifications/${notifId}/read`)
  loadAll()
}

function getReqStat(status: string): number {
  const s = reqStats.value.find((r: any) => r.status === status)
  return s ? s.count : 0
}

onMounted(loadAll)
</script>

<template>
  <div>
    <FilterBar :show-search="false" @search="loadAll">
      <el-form-item label="项目"><el-input v-model="project" style="width: 140px" /></el-form-item>
      <template #actions>
        <el-button type="primary" @click="loadAll">刷新</el-button>
        <el-button @click="reqFormVisible = true">新建需求</el-button>
      </template>
    </FilterBar>

    <el-row :gutter="16">
      <el-col v-for="c in statusColumns" :key="c" :xs="12" :sm="8" :lg="4">
        <StatCard
          :label="statusLabels[c]"
          :value="getReqStat(c)"
          :color="({ DRAFT: '#64748b', REVIEW: '#f59e0b', DEVELOPING: '#6366f1', TESTING: '#ef4444', DONE: '#10b981' } as Record<string, string>)[c]"
        />
      </el-col>
    </el-row>

    <PageCard no-padding style="margin-top: 16px">
      <el-tabs v-model="activeTab" class="pm-tabs">
        <!-- PM Kanban -->
        <el-tab-pane label="需求看板" name="kanban">
          <div class="kanban-board">
            <div v-for="status in statusColumns" :key="status" class="kanban-col">
              <div class="kanban-header">
                <el-tag :type="statusColors[status] as any" size="small">{{ statusLabels[status] }}</el-tag>
                <span class="count">{{ kanbanByStatus(status).length }}</span>
              </div>
              <div v-for="req in kanbanByStatus(status)" :key="req.id" class="kanban-card">
                <div class="req-title">{{ req.title }}</div>
                <div class="req-meta">
                  <el-tag size="small" :type="req.priority === 'P0' ? 'danger' : req.priority === 'P1' ? 'warning' : 'info'">{{ req.priority }}</el-tag>
                  <span v-if="req.pm">{{ req.pm }}</span>
                </div>
                <div class="req-links">
                  <span>💻 {{ req.linkedCommits ?? 0 }}</span>
                  <span>🧪 {{ req.testCases ?? 0 }}</span>
                  <span>🐛 {{ req.openBugs ?? 0 }}</span>
                </div>
                <div class="req-actions">
                  <el-button v-for="next in getNextStatus(status)" :key="next" size="small" text @click="updateStatus(req.id, next)">
                    → {{ statusLabels[next] }}
                  </el-button>
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <!-- Quality Gate -->
        <el-tab-pane label="质量门禁" name="gate">
          <el-form inline style="margin-bottom: 12px">
            <el-form-item label="目标版本"><el-input v-model="gateVersion" placeholder="v2.5.0" style="width: 160px" /></el-form-item>
            <el-form-item><el-button type="primary" :loading="loading" @click="runQualityGate">执行门禁检查</el-button></el-form-item>
          </el-form>
          <el-alert v-if="gateResult" :type="gateResult.passed ? 'success' : 'error'" :title="gateResult.verdict" :closable="false" style="margin-bottom: 12px" />
          <el-descriptions v-if="gateResult" :column="2" border size="small">
            <el-descriptions-item v-for="(check, key) in gateResult.checks" :key="key" :label="String(key)">
              <el-tag :type="check.passed ? 'success' : 'danger'" size="small">{{ check.message }}</el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>

        <!-- Notifications -->
        <el-tab-pane :label="'通知 (' + notifications.length + ')'" name="notifications">
          <div v-for="n in notifications" :key="n.id" class="notif-item" @click="markRead(n.id)">
            <el-tag size="small" :type="n.read ? 'info' : 'danger'">{{ n.trigger_event }}</el-tag>
            <strong>{{ n.title }}</strong>
            <span class="notif-time">{{ n.created_at }}</span>
          </div>
          <el-empty v-if="notifications.length === 0" description="暂无通知" :image-size="60" />
        </el-tab-pane>
      </el-tabs>
    </PageCard>

    <!-- Create Requirement Dialog -->
    <el-dialog v-model="reqFormVisible" title="新建需求" width="560px">
      <el-form label-width="90px">
        <el-form-item label="标题"><el-input v-model="reqForm.title" /></el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="reqForm.priority"><el-option v-for="p in ['P0','P1','P2','P3']" :key="p" :value="p" /></el-select>
        </el-form-item>
        <el-form-item label="产品经理"><el-input v-model="reqForm.productManager" /></el-form-item>
        <el-form-item label="负责人"><el-input v-model="reqForm.assignedTo" /></el-form-item>
        <el-form-item label="目标版本"><el-input v-model="reqForm.targetVersion" /></el-form-item>
        <el-form-item label="原型链接"><el-input v-model="reqForm.prototypeUrl" placeholder="Figma/蓝湖链接" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="reqForm.description" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reqFormVisible = false">取消</el-button>
        <el-button type="primary" @click="createRequirement">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>


<style scoped>
.pm-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 20px;
}

.pm-tabs :deep(.el-tab-pane) {
  padding: 16px 20px 20px;
}

.kanban-board { display: flex; gap: 12px; overflow-x: auto; padding-bottom: 8px }
.kanban-col { flex: 1; min-width: 220px; background: var(--et-page-bg); border-radius: var(--et-radius); padding: 12px; border: 1px solid var(--et-border) }
.kanban-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; padding-bottom: 8px; border-bottom: 1px solid var(--et-border) }
.count { font-weight: 700; font-size: 18px; color: var(--et-text-muted) }
.kanban-card {
  background: var(--et-card-bg);
  padding: 12px;
  margin-bottom: 8px;
  border-radius: var(--et-radius);
  border: 1px solid var(--et-border);
  transition: box-shadow 0.2s, transform 0.2s;
  cursor: default;
}
.kanban-card:hover { box-shadow: var(--et-shadow-md); transform: translateY(-1px) }
.req-title { font-weight: 600; margin-bottom: 6px; color: var(--et-text); line-height: 1.4 }
.req-meta { display: flex; gap: 6px; align-items: center; margin-bottom: 6px; font-size: 12px }
.req-links { display: flex; gap: 12px; font-size: 12px; color: var(--et-text-muted); margin-bottom: 6px }
.req-actions { display: flex; gap: 4px; flex-wrap: wrap }
.notif-item {
  padding: 12px;
  border-radius: var(--et-radius);
  border: 1px solid var(--et-border);
  margin-bottom: 8px;
  cursor: pointer;
  display: flex;
  gap: 8px;
  align-items: center;
  transition: background 0.15s;
}
.notif-item:hover { background: var(--et-page-bg) }
.notif-time { color: var(--et-text-muted); font-size: 12px; margin-left: auto }
.el-col { margin-bottom: 16px }
</style>
