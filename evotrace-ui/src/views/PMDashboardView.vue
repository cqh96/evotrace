<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { Tickets, CircleCheckFilled, Odometer, Warning, Plus, Refresh, MagicStick } from '@element-plus/icons-vue'
import StatCard from '../components/StatCard.vue'
import LifecycleView from '../components/pm/LifecycleView.vue'
import RequirementDetailDrawer from '../components/pm/RequirementDetailDrawer.vue'
import RequirementImportDialog from '../components/pm/RequirementImportDialog.vue'
import { modelConfigApi, pmApi, type Requirement, type RequirementInput } from '../api'
import { useProjectStore } from '../stores/project'

const projectStore = useProjectStore()
const { current } = storeToRefs(projectStore)
const project = computed({
  get: () => current.value,
  set: (v: string) => projectStore.setCurrent(v)
})

const activeTab = ref('kanban')
const loading = ref(false)

// Requirement kanban
const requirements = ref<Requirement[]>([])
const statusColumns = ['DRAFT', 'REVIEW', 'DEVELOPING', 'TESTING', 'DONE']
const statusLabels: Record<string, string> = { DRAFT: '草稿', REVIEW: '评审中', DEVELOPING: '开发中', TESTING: '测试中', DONE: '已完成' }
const statusColors: Record<string, string> = { DRAFT: 'info', REVIEW: 'warning', DEVELOPING: 'primary', TESTING: 'danger', DONE: 'success' }

// Quality gate
const gateVersion = ref('')
const gateResult = ref<any>(null)

// Notifications
const notifications = ref<any[]>([])
const reqFormVisible = ref(false)
const reqForm = ref<RequirementInput>({
  title: '', priority: 'P2', status: 'DRAFT', productManager: '', assignedTo: '', targetVersion: '',
  description: '', prototypeUrl: '', designUrl: '', businessValue: '', userStory: '', acceptanceCriteria: '', estimateDays: 3, techLead: ''
})

// Bug & test stats
const bugStats = ref<any[]>([])
const reqStats = ref<any[]>([])

// Detail drawer
const drawerVisible = ref(false)
const selectedReq = ref<Requirement | null>(null)

// 智能导入
const importDialog = ref<InstanceType<typeof RequirementImportDialog> | null>(null)

// AI availability
const aiUsable = ref(false)

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
      pmApi.list(project.value),
      pmApi.notifications(project.value),
      pmApi.dashboard(project.value)
    ])
    requirements.value = reqs ?? []
    notifications.value = notifs ?? []
    bugStats.value = (dash as any).bugStats || []
    reqStats.value = (dash as any).requirementStats || []
  } catch { /* server not ready */ }
  loading.value = false
}

function kanbanByStatus(status: string) {
  return requirements.value.filter(r => r.status === status)
}

function openDetail(req: Requirement) {
  selectedReq.value = req
  drawerVisible.value = true
}

async function updateStatus(id: number, status: string) {
  try {
    await pmApi.updateStatus(id, status)
    ElMessage.success(`已流转为「${statusLabels[status]}」`)
    loadAll()
  } catch { ElMessage.error('更新失败') }
}

async function createRequirement() {
  try {
    await pmApi.create(project.value, reqForm.value)
    ElMessage.success('需求已创建')
    reqFormVisible.value = false
    reqForm.value = {
      title: '', priority: 'P2', status: 'DRAFT', productManager: '', assignedTo: '', targetVersion: '',
      description: '', prototypeUrl: '', designUrl: '', businessValue: '', userStory: '', acceptanceCriteria: '', estimateDays: 3, techLead: ''
    }
    loadAll()
  } catch { ElMessage.error('创建失败') }
}

async function runQualityGate() {
  if (!gateVersion.value) return
  loading.value = true
  try {
    gateResult.value = await pmApi.qualityGateCheck(project.value, gateVersion.value)
  } catch { ElMessage.error('检查失败') }
  loading.value = false
}

async function markRead(notifId: number) {
  await pmApi.notificationRead(notifId)
  loadAll()
}

function getReqStat(status: string): number {
  const s = reqStats.value.find((r: any) => r.status === status)
  return s ? s.count : 0
}

onMounted(async () => {
  loadAll()
  try {
    const status = await modelConfigApi.status()
    aiUsable.value = status.usable
  } catch { aiUsable.value = false }
})

/* ---------- 展示性统计（Neo-Glass 看板） ---------- */
const statTotal = computed(() => requirements.value.length)
const statDoneRate = computed(() => {
  const total = requirements.value.length
  if (!total) return 0
  return Math.round((kanbanByStatus('DONE').length / total) * 100)
})
const statInProgress = computed(() =>
  ['REVIEW', 'DEVELOPING', 'TESTING'].reduce((sum, s) => sum + kanbanByStatus(s).length, 0)
)
const statOpenBugs = computed(() =>
  bugStats.value.reduce((sum: number, b: any) => sum + (b.count ?? 0), 0)
)

const dotColors: Record<string, string> = {
  DRAFT: '#5c6a8a', REVIEW: '#fbbf24', DEVELOPING: '#6d7cff', TESTING: '#fb7185', DONE: '#34d399'
}
function dotColor(status: string): string {
  return dotColors[status] ?? '#6d7cff'
}
function priClass(priority?: string): string {
  return ({ P0: 'p0', P1: 'p1', P2: 'p2' } as Record<string, string>)[priority ?? ''] ?? 'p3'
}
function reqOwner(req: Requirement): string {
  return req.assignee || req.pm || '未分配'
}
function reqProgress(req: Requirement): number {
  if (req.taskCount && req.taskCount > 0) {
    return Math.min(100, Math.round(((req.taskDone ?? 0) / req.taskCount) * 100))
  }
  return ({ DRAFT: 10, REVIEW: 30, DEVELOPING: 55, TESTING: 80, DONE: 100 } as Record<string, number>)[req.status] ?? 0
}

// 需求完整度小环（绿 ≥80 / 黄 ≥50 / 红 <50；A 期无分数时按 0 处理）
function reqCompPct(req: Requirement): number {
  return Math.round((req as any).completenessScore ?? 0)
}
function compColor(req: Requirement): string {
  const pct = reqCompPct(req)
  return pct >= 80 ? '#34d399' : pct >= 50 ? '#fbbf24' : '#fb7185'
}
</script>

<template>
  <div class="pm-page">
    <!-- ======== 页头 ======== -->
    <div class="et-hero pm-hero rise" style="--d: 0s">
      <div class="hero-left">
        <span class="et-g-ic g-indigo hero-ic"><el-icon :size="19"><Tickets /></el-icon></span>
        <div>
          <h2>PM 需求看板</h2>
          <div class="et-hero-sub">草稿 → 评审 → 开发 → 测试 → 完成 · 全生命周期追踪 · AI 辅助建模与原型</div>
        </div>
      </div>
      <div class="hero-right">
        <el-input v-model="project" placeholder="项目 Key" class="proj-input" />
        <el-button @click="loadAll" :loading="loading">
          <el-icon :size="14" style="margin-right: 4px"><Refresh /></el-icon>刷新
        </el-button>
        <el-button type="primary" @click="reqFormVisible = true">
          <el-icon :size="14" style="margin-right: 4px"><Plus /></el-icon>新建需求
        </el-button>
      </div>
    </div>

    <!-- ======== 统计 ======== -->
    <div class="stats-grid">
      <StatCard class="rise" style="--d: .06s" label="需求总数" :value="statTotal" suffix="项"
                :icon="Tickets" color="#6d7cff" foot="全部看板需求" />
      <StatCard class="rise" style="--d: .12s" label="完成率" :value="statDoneRate" suffix="%"
                :icon="CircleCheckFilled" color="#34d399" foot="已完成 / 需求总数" />
      <StatCard class="rise" style="--d: .18s" label="进行中" :value="statInProgress" suffix="项"
                :icon="Odometer" color="#fbbf24" foot="评审 / 开发 / 测试中" />
      <StatCard class="rise" style="--d: .24s" label="遗留缺陷" :value="statOpenBugs" suffix="个"
                :icon="Warning" color="#fb7185" foot="未关闭 Bug · 按严重度统计" />
    </div>

    <!-- ======== 看板 / 生命周期 / 门禁 / 通知 ======== -->
    <div class="et-card board-card rise" style="--d: .3s">
      <el-tabs v-model="activeTab" class="pm-tabs">
        <!-- PM Kanban -->
        <el-tab-pane label="需求看板" name="kanban">
          <div class="kanban-board">
            <div v-for="(status, si) in statusColumns" :key="status" class="kanban-col rise"
                 :style="{ '--d': (si * 0.05 + 0.32) + 's' }">
              <div class="kanban-header">
                <div class="col-title">
                  <span class="col-dot" :style="{ background: dotColor(status), color: dotColor(status) }"></span>
                  <span class="col-name">{{ statusLabels[status] }}</span>
                </div>
                <span class="col-count">{{ kanbanByStatus(status).length }}</span>
              </div>

              <div class="col-body">
                <div v-for="(req, i) in kanbanByStatus(status)" :key="req.id" class="kanban-card"
                     :style="{ '--d': (i * 0.06) + 's' }" @click="openDetail(req)">
                  <div class="card-top">
                    <span class="et-mini-tag" :class="priClass(req.priority)">{{ req.priority || 'P3' }}</span>
                    <span v-if="req.reqKey" class="req-key-chip">{{ req.reqKey }}</span>
                    <span v-if="req.targetVersion" class="ver-chip">{{ req.targetVersion }}</span>
                    <span v-if="req.estimateDays != null" class="est-chip">{{ req.estimateDays }} 人天</span>
                    <span class="comp-dot" :style="{ background: compColor(req), color: compColor(req) }" :title="`完整度 ${reqCompPct(req)}%`"></span>
                  </div>

                  <div class="req-title">{{ req.title }}</div>

                  <div class="req-owner">
                    <span class="o-avatar">{{ reqOwner(req).charAt(0) }}</span>
                    <span class="o-name">{{ reqOwner(req) }}</span>
                  </div>

                  <div class="req-bar-row">
                    <div class="et-bar"><i :style="{ width: reqProgress(req) + '%' }"></i></div>
                    <span class="bar-pct">{{ reqProgress(req) }}%</span>
                  </div>

                  <div class="req-meta">
                    <span class="meta-chip"><i class="mc-dot" style="background: #6d7cff"></i>{{ req.linkedCommits ?? 0 }} 提交</span>
                    <span class="meta-chip"><i class="mc-dot" style="background: #a78bfa"></i>{{ req.testCases ?? 0 }} 用例</span>
                    <span class="meta-chip"><i class="mc-dot" style="background: #fb7185"></i>{{ req.openBugs ?? 0 }} 缺陷</span>
                    <span v-if="req.taskCount != null" class="meta-chip"><i class="mc-dot" style="background: #38e1ff"></i>{{ req.taskDone ?? 0 }}/{{ req.taskCount }} 任务</span>
                  </div>

                  <div class="req-actions" @click.stop>
                    <button v-for="next in getNextStatus(status)" :key="next" class="flow-btn"
                            @click="updateStatus(req.id, next)">
                      → {{ statusLabels[next] }}
                    </button>
                  </div>
                </div>

                <div v-if="kanbanByStatus(status).length === 0" class="col-empty">
                  <span class="col-empty-ic">◌</span>暂无需求
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <!-- 生命周期 -->
        <el-tab-pane label="生命周期" name="lifecycle">
          <LifecycleView :project-key="project" />
        </el-tab-pane>

        <!-- Quality Gate -->
        <el-tab-pane label="质量门禁" name="gate">
          <div class="gate-form">
            <el-form inline style="margin-bottom: 0">
              <el-form-item label="目标版本"><el-input v-model="gateVersion" placeholder="v2.5.0" style="width: 160px" /></el-form-item>
              <el-form-item><el-button type="primary" :loading="loading" @click="runQualityGate">执行门禁检查</el-button></el-form-item>
            </el-form>
          </div>
          <el-alert v-if="gateResult" :type="gateResult.passed ? 'success' : 'error'" :title="gateResult.verdict" :closable="false" style="margin-bottom: 12px" />
          <el-descriptions v-if="gateResult" :column="2" border size="small">
            <el-descriptions-item v-for="(check, key) in gateResult.checks" :key="key" :label="String(key)">
              <el-tag :type="check.passed ? 'success' : 'danger'" size="small">{{ check.message }}</el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>

        <!-- Notifications -->
        <el-tab-pane :label="'通知 (' + notifications.length + ')'" name="notifications">
          <div class="notif-list">
            <div v-for="n in notifications" :key="n.id" class="notif-item" @click="markRead(n.id)">
              <span class="notif-dot" :class="{ read: n.read }"></span>
              <el-tag size="small" :type="n.read ? 'info' : 'danger'">{{ n.trigger_event }}</el-tag>
              <strong>{{ n.title }}</strong>
              <span class="notif-time">{{ n.created_at }}</span>
            </div>
            <el-empty v-if="notifications.length === 0" description="暂无通知" :image-size="60" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- Create Requirement Dialog -->
    <el-dialog v-model="reqFormVisible" title="新建需求" width="640px">
      <el-form label-width="90px">
        <el-form-item label="标题" required><el-input v-model="reqForm.title" /></el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="优先级">
              <el-select v-model="reqForm.priority"><el-option v-for="p in ['P0','P1','P2','P3']" :key="p" :value="p" /></el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="预估工时">
              <el-input-number v-model="reqForm.estimateDays" :min="0.5" :step="0.5" :precision="1" />
              <span class="unit">人天</span>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="产品经理"><el-input v-model="reqForm.productManager" /></el-form-item>
        <el-form-item label="负责人"><el-input v-model="reqForm.assignedTo" /></el-form-item>
        <el-form-item label="技术负责人"><el-input v-model="reqForm.techLead" /></el-form-item>
        <el-form-item label="目标版本"><el-input v-model="reqForm.targetVersion" /></el-form-item>
        <el-form-item label="业务价值"><el-input v-model="reqForm.businessValue" type="textarea" :rows="2" placeholder="一句话说明为什么做这件事" /></el-form-item>
        <el-form-item label="用户故事"><el-input v-model="reqForm.userStory" type="textarea" :rows="2" placeholder="作为〈角色〉，我希望〈能力〉，以便〈价值〉" /></el-form-item>
        <el-form-item label="验收标准"><el-input v-model="reqForm.acceptanceCriteria" type="textarea" :rows="3" placeholder="- [ ] 可测试的验收项，每行一条" /></el-form-item>
        <el-form-item label="原型链接"><el-input v-model="reqForm.prototypeUrl" placeholder="Figma/蓝湖链接" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="reqForm.description" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reqFormVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!reqForm.title.trim()" @click="createRequirement">创建</el-button>
      </template>
    </el-dialog>

    <!-- Detail drawer -->
    <RequirementDetailDrawer
      v-if="selectedReq"
      v-model="drawerVisible"
      :project-key="project"
      :requirement="selectedReq"
      :ai-usable="aiUsable"
      @refreshed="loadAll"
    />

    <!-- 智能导入 -->
    <RequirementImportDialog ref="importDialog" :project-key="project" @imported="loadAll" />
  </div>
</template>

<style scoped>
/* ======== 页头 ======== */
.pm-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
  position: relative;
  z-index: 1;
}
.hero-left {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.hero-ic { width: 44px; height: 44px; border-radius: 13px; }
.hero-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.proj-input { width: 150px; }

/* ======== 统计 ======== */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-top: 18px;
}
@media (max-width: 1280px) {
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 720px) {
  .stats-grid { grid-template-columns: 1fr; }
}

/* ======== 看板卡片 ======== */
.board-card {
  margin-top: 18px;
  padding: 6px 0 0;
}
.pm-tabs :deep(.el-tabs__header) {
  margin: 0 22px;
}
.pm-tabs :deep(.el-tab-pane) {
  padding: 18px 22px 22px;
}

/* ======== 看板列 ======== */
.kanban-board {
  display: flex;
  gap: 14px;
  overflow-x: auto;
  align-items: flex-start;
  padding-bottom: 6px;
}
.kanban-col {
  flex: 1;
  min-width: 250px;
  background: var(--et-card-bg);
  border: 1px solid var(--et-border);
  border-radius: var(--et-radius-lg);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  padding: 14px 12px 12px;
  transition: border-color 0.22s, box-shadow 0.22s;
}
.kanban-col:hover {
  border-color: var(--et-hover-border);
  box-shadow: var(--et-shadow);
}

.kanban-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 2px 4px 10px;
  border-bottom: 1px solid var(--et-border);
}
.col-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.col-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  box-shadow: 0 0 8px currentColor;
}
.col-name {
  font-size: 13.5px;
  font-weight: 700;
  letter-spacing: 0.3px;
  color: var(--et-text);
}
.col-count {
  font-size: 12px;
  font-weight: 800;
  color: var(--et-text-secondary);
  padding: 2px 10px;
  border-radius: 20px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  font-variant-numeric: tabular-nums;
}

.col-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 12px;
}

/* ======== 需求卡 ======== */
.kanban-card {
  position: relative;
  background: var(--et-card-solid);
  border: 1px solid var(--et-border);
  border-radius: 14px;
  padding: 14px 14px 12px;
  cursor: pointer;
  transition: transform 0.22s cubic-bezier(0.22, 1, 0.36, 1), border-color 0.22s, box-shadow 0.22s;
}
.kanban-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 14px;
  right: 14px;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.22), transparent);
  pointer-events: none;
}
[data-theme="light"] .kanban-card::before {
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.9), transparent);
}
.kanban-card:hover {
  transform: translateY(-4px);
  border-color: var(--et-hover-border);
  box-shadow: var(--et-shadow-md);
}

.card-top {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.et-mini-tag.p0 { color: var(--et-danger); background: rgba(251, 113, 133, 0.13); }
.et-mini-tag.p1 { color: var(--et-warn); background: rgba(251, 191, 36, 0.13); }
.et-mini-tag.p2 { color: var(--et-primary-light); background: rgba(109, 124, 255, 0.15); }
.et-mini-tag.p3 { color: var(--et-text-muted); background: var(--et-bg-muted); }
.ver-chip {
  font-size: 10.5px;
  font-weight: 700;
  color: var(--et-text-secondary);
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  padding: 2.5px 8px;
  border-radius: 20px;
  font-variant-numeric: tabular-nums;
}
.req-key-chip {
  font-size: 10.5px;
  font-weight: 700;
  color: var(--et-grad-c);
  background: rgba(56, 225, 255, 0.1);
  border: 1px solid rgba(56, 225, 255, 0.2);
  padding: 2.5px 8px;
  border-radius: 20px;
  font-family: ui-monospace, monospace;
}
.comp-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  margin-left: auto;
  box-shadow: 0 0 8px currentColor;
  flex-shrink: 0;
}
.est-chip {
  font-size: 10.5px;
  color: var(--et-text-muted);
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}

.req-title {
  font-size: 13.5px;
  font-weight: 700;
  line-height: 1.5;
  color: var(--et-text);
  margin-bottom: 10px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.req-owner {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 10px;
}
.o-avatar {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-b));
  color: #fff;
  font-size: 10.5px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.o-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--et-text-secondary);
}

.req-bar-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.req-bar-row .et-bar { flex: 1; }
.bar-pct {
  font-size: 10.5px;
  font-weight: 700;
  color: var(--et-text-muted);
  font-variant-numeric: tabular-nums;
  min-width: 30px;
  text-align: right;
}

.req-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}
.meta-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--et-text-muted);
  font-variant-numeric: tabular-nums;
}
.mc-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.req-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.flow-btn {
  border: 1px solid var(--et-border);
  background: var(--et-bg-muted);
  color: var(--et-text-secondary);
  font-size: 11.5px;
  font-weight: 600;
  font-family: inherit;
  padding: 4px 10px;
  border-radius: 9px;
  cursor: pointer;
  transition: all 0.15s;
}
.flow-btn:hover {
  color: var(--et-primary-light);
  border-color: var(--et-primary);
  transform: translateY(-1px);
}

.col-empty {
  text-align: center;
  padding: 26px 0 10px;
  color: var(--et-text-muted);
  font-size: 12px;
  border: 1px dashed var(--et-border);
  border-radius: 12px;
}
.col-empty-ic {
  display: block;
  font-size: 18px;
  margin-bottom: 6px;
  opacity: 0.6;
}

/* ======== 质量门禁 ======== */
.gate-form {
  padding: 14px 16px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  border-radius: 14px;
  margin-bottom: 14px;
}

/* ======== 通知 ======== */
.notif-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.notif-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 16px;
  border-radius: 14px;
  border: 1px solid var(--et-border);
  background: var(--et-bg-muted);
  cursor: pointer;
  transition: transform 0.18s, border-color 0.18s, background 0.18s;
}
.notif-item:hover {
  transform: translateY(-2px);
  border-color: var(--et-hover-border);
  background: var(--et-card-bg);
}
.notif-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--et-danger);
  box-shadow: 0 0 8px rgba(251, 113, 133, 0.55);
  flex-shrink: 0;
}
.notif-dot.read {
  background: var(--et-text-muted);
  box-shadow: none;
}
.notif-item strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--et-text);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.notif-time {
  font-size: 11.5px;
  color: var(--et-text-muted);
  margin-left: auto;
  flex-shrink: 0;
}

.unit {
  margin-left: 6px;
  color: var(--et-text-muted);
  font-size: 12px;
}
</style>
