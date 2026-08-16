<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Grid, List, Warning, Delete } from '@element-plus/icons-vue'
import { bugApi, type BugItem } from '../api'
import { useProjectStore } from '../stores/project'

const projectStore = useProjectStore()
const { current: project } = storeToRefs(projectStore)

const loading = ref(false)
const viewMode = ref<'table' | 'kanban'>('table')
const bugs = ref<BugItem[]>([])
const statusFilter = ref('')
const severityFilter = ref('')

const statusLabels: Record<string, string> = {
  OPEN: '打开', IN_PROGRESS: '处理中', FIXED: '已修复', REOPENED: '重新打开', VERIFIED: '已验收', CLOSED: '已关闭'
}
const statusColors: Record<string, string> = {
  OPEN: '#dc2626', IN_PROGRESS: '#b45309', FIXED: '#0891b2', REOPENED: '#dc2626', VERIFIED: '#6d4fd6', CLOSED: '#059669'
}
const sevLabels: Record<string, string> = { P0: '致命', P1: '严重', P2: '一般', P3: '轻微' }
const sevColors: Record<string, string> = { P0: '#dc2626', P1: '#c2410c', P2: '#b45309', P3: '#64748b' }

const kanbanColumns = ['OPEN', 'IN_PROGRESS', 'FIXED', 'VERIFIED', 'CLOSED']

// 状态机允许的下一步
const nextStatus: Record<string, string[]> = {
  OPEN: ['IN_PROGRESS'],
  IN_PROGRESS: ['FIXED', 'OPEN'],
  FIXED: ['VERIFIED', 'REOPENED', 'IN_PROGRESS'],
  VERIFIED: ['CLOSED', 'REOPENED'],
  REOPENED: ['IN_PROGRESS', 'FIXED'],
  CLOSED: ['REOPENED']
}

async function load() {
  if (!project.value) return
  loading.value = true
  try {
    bugs.value = await bugApi.list(project.value, {
      status: statusFilter.value || undefined,
      severity: severityFilter.value || undefined
    }) as unknown as BugItem[]
  } catch {
    ElMessage.error('加载缺陷失败')
  }
  loading.value = false
}

function kanbanBy(status: string) {
  return bugs.value.filter(b => b.status === status)
}

// ===== 创建缺陷 =====
const createOpen = ref(false)
const form = ref({ title: '', description: '', severity: 'P2', foundBy: '', foundVersion: '', assignedTo: '' })
async function createBug() {
  if (!form.value.title.trim()) return ElMessage.warning('请输入缺陷标题')
  try {
    await bugApi.create(project.value!, { ...form.value })
    ElMessage.success('缺陷已创建')
    createOpen.value = false
    form.value = { title: '', description: '', severity: 'P2', foundBy: '', foundVersion: '', assignedTo: '' }
    load()
  } catch {
    ElMessage.error('创建失败')
  }
}

// ===== 状态流转 =====
async function transition(bug: BugItem, to: string) {
  try {
    await bugApi.transition(bug.id, to)
    ElMessage.success(`已流转为「${statusLabels[to]}」`)
    load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '流转失败')
  }
}

// ===== 详情抽屉（含追溯） =====
const detailOpen = ref(false)
const detail = ref<Record<string, any> | null>(null)
const detailLoading = ref(false)
async function openDetail(bug: BugItem) {
  detailLoading.value = true
  detailOpen.value = true
  detail.value = null
  try {
    detail.value = await bugApi.detail(bug.id)
  } finally {
    detailLoading.value = false
  }
}

const sevFilters = ['P0', 'P1', 'P2', 'P3']
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <div class="left">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="load">
          <el-option v-for="(label, key) in statusLabels" :key="key" :label="label" :value="key" />
        </el-select>
        <el-select v-model="severityFilter" placeholder="全部级别" clearable style="width: 120px" @change="load">
          <el-option v-for="s in sevFilters" :key="s" :label="`${s} ${sevLabels[s]}`" :value="s" />
        </el-select>
      </div>
      <div class="right">
        <div class="seg">
          <button :class="{ on: viewMode === 'table' }" @click="viewMode = 'table'"><el-icon><List /></el-icon> 列表</button>
          <button :class="{ on: viewMode === 'kanban' }" @click="viewMode = 'kanban'"><el-icon><Grid /></el-icon> 看板</button>
        </div>
        <button class="ops-btn primary" @click="createOpen = true"><el-icon><Plus /></el-icon> 新建缺陷</button>
        <button class="ops-btn" @click="load"><el-icon><Refresh /></el-icon> 刷新</button>
      </div>
    </div>

    <!-- ===== 表格视图 ===== -->
    <div v-if="viewMode === 'table'" class="et-card">
      <div class="et-card-body no-padding">
        <el-table :data="bugs" v-loading="loading" size="default" style="width: 100%">
          <el-table-column label="严重度" width="90">
            <template #default="{ row }">
              <span class="sev-dot" :style="{ background: sevColors[row.severity] || '#4f5ad1' }">{{ row.severity }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <span class="status-pill" :style="{ color: statusColors[row.status], background: `color-mix(in srgb, ${statusColors[row.status] || '#4f5ad1'} 14%, transparent)` }">
                {{ statusLabels[row.status] || row.status }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="分配人" width="110">
            <template #default="{ row }">{{ row.assigned_to || '—' }}</template>
          </el-table-column>
          <el-table-column label="发现版本" width="110">
            <template #default="{ row }">{{ row.found_version || '—' }}</template>
          </el-table-column>
          <el-table-column label="关联变更" width="90">
            <template #default="{ row }">{{ row.changeCount ?? 0 }} 次</template>
          </el-table-column>
          <el-table-column label="更新时间" width="150">
            <template #default="{ row }">{{ row.updated_at }}</template>
          </el-table-column>
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <div class="ops">
                <button class="ops-btn primary" @click="openDetail(row)">详情</button>
                <button v-for="to in nextStatus[row.status] || []" :key="to" class="ops-btn success" @click="transition(row, to)">
                  → {{ statusLabels[to] }}
                </button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- ===== 看板视图（P2 缺陷看板） ===== -->
    <div v-else class="kanban">
      <div v-for="col in kanbanColumns" :key="col" class="kanban-col">
        <div class="col-head">
          <span class="col-dot" :style="{ background: statusColors[col] }"></span>
          <span class="col-name">{{ statusLabels[col] }}</span>
          <span class="col-count">{{ kanbanBy(col).length }}</span>
        </div>
        <div class="col-body">
          <div v-for="bug in kanbanBy(col)" :key="bug.id" class="kb-card" @click="openDetail(bug)">
            <div class="kb-top">
              <span class="sev-dot" :style="{ background: sevColors[bug.severity] || '#4f5ad1' }">{{ bug.severity }}</span>
              <span class="kb-id">#{{ bug.id }}</span>
            </div>
            <div class="kb-title">{{ bug.title }}</div>
            <div class="kb-foot">
              <span>{{ bug.assigned_to || '未分配' }}</span>
              <button v-for="to in nextStatus[bug.status] || []" :key="to" class="ops-btn success mini"
                      @click.stop="transition(bug, to)">→ {{ statusLabels[to] }}</button>
            </div>
          </div>
          <div v-if="kanbanBy(col).length === 0" class="col-empty">暂无缺陷</div>
        </div>
      </div>
    </div>

    <!-- ===== 新建缺陷 ===== -->
    <el-dialog v-model="createOpen" title="新建缺陷" width="520px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="标题" required><el-input v-model="form.title" placeholder="缺陷标题" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="严重度">
          <el-select v-model="form.severity" style="width: 100%">
            <el-option v-for="s in sevFilters" :key="s" :label="`${s} ${sevLabels[s]}`" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="发现人"><el-input v-model="form.foundBy" /></el-form-item>
        <el-form-item label="发现版本"><el-input v-model="form.foundVersion" /></el-form-item>
        <el-form-item label="分配人"><el-input v-model="form.assignedTo" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" @click="createBug">创建</el-button>
      </template>
    </el-dialog>

    <!-- ===== 详情抽屉（含需求/代码/用例追溯） ===== -->
    <el-drawer v-model="detailOpen" title="缺陷详情与追溯" size="520px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="d-head">
            <h3>{{ detail.title }}</h3>
            <div class="d-meta">
              <span class="sev-dot" :style="{ background: sevColors[detail.severity] || '#4f5ad1' }">{{ detail.severity }}</span>
              <span class="status-pill" :style="{ color: statusColors[detail.status], background: `color-mix(in srgb, ${statusColors[detail.status] || '#4f5ad1'} 14%, transparent)` }">
                {{ statusLabels[detail.status] || detail.status }}
              </span>
            </div>
          </div>
          <p class="d-desc">{{ detail.description || '（无描述）' }}</p>

          <div class="d-sec-title"><Warning /> 关联代码变更（需求→代码→缺陷追溯）</div>
          <div v-if="(detail.linkedChanges || []).length" class="d-list">
            <div v-for="c in detail.linkedChanges" :key="c.eventId" class="d-item">
              <div class="d-item-line">
                <span class="mini-tag" :class="c.linkType === 'FIX' ? 'ok' : 'warn'">{{ c.linkType }}</span>
                <span class="mono">{{ c.commitSha?.substring(0, 8) }}</span>
                <span class="muted">{{ c.author }}</span>
              </div>
              <div class="d-item-sub">{{ c.summary || c.eventId }}</div>
            </div>
          </div>
          <div v-else class="d-empty">暂无关联变更，可在演化时间线中关联</div>

          <div class="d-sec-title"><Delete /> 关联测试用例</div>
          <div v-if="(detail.linkedCases || []).length" class="d-list">
            <div v-for="tc in detail.linkedCases" :key="tc.id" class="d-item">
              <div class="d-item-line">
                <span class="mono">#{{ tc.id }}</span><span>{{ tc.title }}</span>
              </div>
            </div>
          </div>
          <div v-else class="d-empty">暂无关联用例</div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.page-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.page-toolbar .left, .page-toolbar .right { display: flex; align-items: center; gap: 10px; }
.seg { display: inline-flex; border: 1px solid var(--et-border); border-radius: 12px; overflow: hidden; }
.seg button {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 8px 14px; background: none; border: none; cursor: pointer;
  color: var(--et-text-muted); font-family: inherit; font-size: 13px; font-weight: 600;
  transition: all 0.18s;
}
.seg button.on { background: rgba(79, 90, 209, 0.16); color: #5f6bd8; }

.ops-btn {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 14px; border-radius: 20px; border: 1px solid transparent;
  font-family: inherit; font-size: 13px; font-weight: 600; cursor: pointer; transition: all 0.18s;
}
.ops-btn.primary { background: rgba(79, 90, 209, 0.14); color: #5f6bd8; }
.ops-btn.primary:hover { background: rgba(79, 90, 209, 0.28); }
.ops-btn.success { background: rgba(5, 150, 105, 0.14); color: #059669; }
.ops-btn.success:hover { background: rgba(5, 150, 105, 0.28); }
.ops-btn.mini { padding: 4px 9px; font-size: 11.5px; }

.sev-dot {
  display: inline-flex; align-items: center; width: 34px; justify-content: center;
  padding: 2px 0; border-radius: 6px; color: #fff; font-size: 11px; font-weight: 700;
}
.status-pill {
  display: inline-flex; padding: 3px 10px; border-radius: 20px; font-size: 12px; font-weight: 600;
}
.ops { display: flex; gap: 6px; flex-wrap: wrap; }

/* 看板 */
.kanban { display: grid; grid-template-columns: repeat(5, 1fr); gap: 14px; }
.kanban-col {
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  border-radius: 14px;
  padding: 12px;
  min-height: 300px;
}
.col-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.col-dot { width: 9px; height: 9px; border-radius: 50%; }
.col-name { font-size: 13px; font-weight: 700; }
.col-count {
  margin-left: auto; font-size: 11px; font-weight: 700;
  padding: 2px 8px; border-radius: 20px; background: var(--et-sidebar-hover); color: var(--et-text-secondary);
}
.col-body { display: flex; flex-direction: column; gap: 10px; }
.kb-card {
  background: var(--et-card-bg);
  border: 1px solid var(--et-border);
  border-radius: 12px;
  padding: 12px;
  cursor: pointer;
  transition: all 0.18s;
}
.kb-card:hover { border-color: var(--et-hover-border); box-shadow: var(--et-shadow-md); transform: translateY(-2px); }
.kb-top { display: flex; align-items: center; gap: 8px; }
.kb-id { font-size: 11px; color: var(--et-text-muted); margin-left: auto; }
.kb-title { margin: 8px 0; font-size: 13.5px; font-weight: 600; line-height: 1.45; }
.kb-foot { display: flex; align-items: center; justify-content: space-between; gap: 6px; flex-wrap: wrap; font-size: 11.5px; color: var(--et-text-muted); }
.col-empty { text-align: center; color: var(--et-text-muted); font-size: 12px; padding: 24px 0; }

/* 详情 */
.d-head h3 { margin: 0 0 10px; font-size: 16px; }
.d-meta { display: flex; gap: 8px; }
.d-desc { color: var(--et-text-secondary); font-size: 13px; line-height: 1.6; margin: 14px 0; }
.d-sec-title {
  display: flex; align-items: center; gap: 7px;
  font-size: 13px; font-weight: 700; margin: 20px 0 10px; color: var(--et-text);
}
.d-list { display: flex; flex-direction: column; gap: 8px; }
.d-item { padding: 10px 12px; border-radius: 10px; background: var(--et-bg-muted); border: 1px solid var(--et-border); }
.d-item-line { display: flex; align-items: center; gap: 8px; font-size: 12.5px; }
.d-item-sub { font-size: 12px; color: var(--et-text-muted); margin-top: 4px; line-height: 1.5; }
.mono { font-family: ui-monospace, monospace; font-size: 12px; }
.muted { color: var(--et-text-muted); }
.mini-tag { font-size: 10px; font-weight: 700; padding: 1px 7px; border-radius: 6px; }
.mini-tag.ok { color: #059669; background: rgba(5, 150, 105, 0.14); }
.mini-tag.warn { color: #b45309; background: rgba(180, 83, 9, 0.14); }
.d-empty { color: var(--et-text-muted); font-size: 12.5px; padding: 8px 0; }

@media (max-width: 1100px) {
  .kanban { grid-template-columns: repeat(2, 1fr); }
}
</style>