<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { FolderOpened, Refresh, Plus, Connection, VideoPlay, Tickets, Delete, Link, EditPen } from '@element-plus/icons-vue'
import { gitlabApi, type GitlabRepo, type GitlabSyncLog, type GitlabConnection } from '../api'
import { useProjectStore } from '../stores/project'

const projectStore = useProjectStore()
const { current: project } = storeToRefs(projectStore)

const loading = ref(false)
const repos = ref<GitlabRepo[]>([])
const connInfo = ref<GitlabConnection>({ configured: false })

const authTypeLabels: Record<string, string> = {
  PAT: 'Personal Access Token', GROUP_TOKEN: 'Group Token', PROJECT_TOKEN: 'Project Token'
}

const statusLabels: Record<string, string> = {
  PENDING: '待导入', CLONING: '克隆中', SYNCED: '已同步', FAILED: '失败'
}
const statusColors: Record<string, string> = {
  PENDING: '#94a3b8', CLONING: '#fbbf24', SYNCED: '#34d399', FAILED: '#fb7185'
}

async function load() {
  if (!project.value) return
  loading.value = true
  try { repos.value = await gitlabApi.repos(project.value) } catch { ElMessage.error('加载仓库失败') }
  loading.value = false
  loadConnect()
}

// ===== 连接配置 =====
const connectOpen = ref(false)
const conn = ref({ baseUrl: '', authType: 'PAT', token: '', namespace: '' })

async function loadConnect() {
  if (!project.value) return
  try { connInfo.value = await gitlabApi.getConnect(project.value) } catch {}
}

function openConnect() {
  conn.value = {
    baseUrl: connInfo.value.baseUrl || '',
    authType: connInfo.value.authType || 'PAT',
    token: '',
    namespace: connInfo.value.namespace || ''
  }
  connectOpen.value = true
}

async function saveConnect() {
  if (!conn.value.baseUrl || !conn.value.token) return ElMessage.warning('请填写 GitLab 地址与令牌')
  try {
    await gitlabApi.connect(project.value!, conn.value)
    ElMessage.success('连接已配置')
    connectOpen.value = false
    loadConnect()
  } catch {
    ElMessage.error('配置失败')
  }
}

async function clearConnect() {
  try {
    await gitlabApi.disconnect(project.value!)
    connInfo.value = { configured: false }
    ElMessage.success('连接已清除')
  } catch {
    ElMessage.error('清除失败')
  }
}

// ===== 导入仓库 =====
const importOpen = ref(false)
const imp = ref({ repoPath: '', defaultBranch: 'main' })
async function doImport() {
  if (!imp.value.repoPath) return ElMessage.warning('请填写仓库路径')
  try {
    const r = await gitlabApi.importRepo(project.value!, imp.value.repoPath, imp.value.defaultBranch)
    ElMessage.success(`导入完成，回填 ${r.commits} 个 commit`)
    importOpen.value = false
    load()
  } catch {
    ElMessage.error('导入失败')
  }
}

async function sync(repo: GitlabRepo) {
  try {
    const r = await gitlabApi.sync(project.value!, repo.id)
    ElMessage.success(`增量同步完成，新增 ${r.newCommits} 个 commit`)
    load()
  } catch {
    ElMessage.error('同步失败')
  }
}

// ===== 同步日志 =====
const logOpen = ref(false)
const logs = ref<GitlabSyncLog[]>([])
const logRepo = ref('')
async function showLogs(repo: GitlabRepo) {
  logRepo.value = repo.repoPath
  logs.value = []
  try { logs.value = await gitlabApi.logs(project.value!, repo.id) } catch {}
  logOpen.value = true
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <div class="left">
        <span class="et-tic"><el-icon><FolderOpened /></el-icon></span>
        <span class="tip">主动导入 GitLab 自建仓库，clone + 增量同步喂入演化时间线</span>
      </div>
      <div class="right">
        <button class="ops-btn" @click="openConnect">
          <el-icon><Connection /></el-icon> {{ connInfo.configured ? '编辑连接' : '配置连接' }}
        </button>
        <button class="ops-btn primary" @click="importOpen = true"><el-icon><Plus /></el-icon> 导入仓库</button>
        <button class="ops-btn" @click="load"><el-icon><Refresh /></el-icon> 刷新</button>
      </div>
    </div>

    <!-- 当前连接状态 -->
    <div class="et-card conn-card" v-if="connInfo.configured">
      <div class="conn-left">
        <span class="conn-icon"><el-icon><Link /></el-icon></span>
        <div class="conn-meta">
          <div class="conn-row">
            <span class="conn-label">GitLab 实例</span>
            <span class="conn-val">{{ connInfo.baseUrl }}</span>
          </div>
          <div class="conn-row">
            <span class="conn-label">认证</span>
            <span class="conn-val">{{ authTypeLabels[connInfo.authType || 'PAT'] || connInfo.authType }}</span>
            <span class="conn-token">{{ connInfo.tokenMasked }}</span>
            <span class="conn-ns" v-if="connInfo.namespace">命名空间：{{ connInfo.namespace }}</span>
          </div>
        </div>
      </div>
      <div class="conn-right">
        <button class="ops-btn" @click="openConnect"><el-icon><EditPen /></el-icon> 编辑</button>
        <button class="ops-btn danger" @click="clearConnect"><el-icon><Delete /></el-icon> 清除</button>
      </div>
    </div>

    <div class="et-card">
      <div class="et-card-head">
        <span>已导入仓库</span>
        <span class="count">{{ repos.length }} 个</span>
      </div>
      <div class="et-card-body no-padding">
        <el-table :data="repos" v-loading="loading" size="default" style="width: 100%">
          <el-table-column label="仓库" min-width="220">
            <template #default="{ row }">
              <div class="repo-name">
                <span class="r-avatar"><el-icon><FolderOpened /></el-icon></span>
                <span class="path">{{ row.repoPath }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="默认分支" width="120">
            <template #default="{ row }"><span class="branch">{{ row.defaultBranch }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <span class="status" :style="{ color: statusColors[row.cloneStatus], background: statusColors[row.cloneStatus] + '26' }">
                {{ statusLabels[row.cloneStatus] || row.cloneStatus }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="最近同步" width="170">
            <template #default="{ row }">
              <div class="sync-sha" v-if="row.lastSyncedSha">#{{ row.lastSyncedSha.slice(0, 8) }}</div>
              <div v-else class="muted">—</div>
            </template>
          </el-table-column>
          <el-table-column label="错误信息" min-width="160">
            <template #default="{ row }"><span class="err" v-if="row.lastError">{{ row.lastError }}</span><span v-else class="muted">—</span></template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <div class="ops">
                <button class="ops-btn" @click="showLogs(row)"><el-icon><Tickets /></el-icon> 日志</button>
                <button class="ops-btn primary" @click="sync(row)"><el-icon><VideoPlay /></el-icon> 同步</button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 连接配置 -->
    <el-dialog v-model="connectOpen" title="配置 GitLab 连接" width="520px">
      <el-form :model="conn" label-width="90px">
        <el-form-item label="实例地址" required>
          <el-input v-model="conn.baseUrl" placeholder="https://gitlab.example.com" />
        </el-form-item>
        <el-form-item label="认证方式">
          <el-select v-model="conn.authType" style="width: 100%">
            <el-option value="PAT" label="Personal Access Token" />
            <el-option value="GROUP_TOKEN" label="Group Token" />
            <el-option value="PROJECT_TOKEN" label="Project Token" />
          </el-select>
        </el-form-item>
        <el-form-item label="访问令牌" required>
          <el-input v-model="conn.token" type="password" show-password placeholder="glpat-..." />
        </el-form-item>
        <el-form-item label="默认命名空间">
          <el-input v-model="conn.namespace" placeholder="如：my-group（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="connectOpen = false">取消</el-button>
        <el-button type="primary" @click="saveConnect">保存</el-button>
      </template>
    </el-dialog>

    <!-- 导入仓库 -->
    <el-dialog v-model="importOpen" title="导入 GitLab 仓库" width="480px">
      <el-form :model="imp" label-width="90px">
        <el-form-item label="仓库路径" required>
          <el-input v-model="imp.repoPath" placeholder="my-group/my-repo" />
        </el-form-item>
        <el-form-item label="默认分支">
          <el-input v-model="imp.defaultBranch" placeholder="main" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importOpen = false">取消</el-button>
        <el-button type="primary" @click="doImport">导入并回填</el-button>
      </template>
    </el-dialog>

    <!-- 同步日志 -->
    <el-dialog v-model="logOpen" :title="`同步日志 · ${logRepo}`" width="640px">
      <el-table :data="logs" size="small" style="width: 100%" max-height="420">
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <span class="sync-type" :class="row.syncType.toLowerCase()">{{ row.syncType === 'FULL' ? '全量' : '增量' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span class="status" :style="{ color: statusColors[row.status], background: statusColors[row.status] + '26' }">
              {{ row.status === 'SUCCESS' ? '成功' : '失败' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="Commit 数" width="100">
          <template #default="{ row }">{{ row.commitsCount }}</template>
        </el-table-column>
        <el-table-column label="开始时间" width="170">
          <template #default="{ row }">{{ row.startedAt }}</template>
        </el-table-column>
        <el-table-column label="备注" min-width="140">
          <template #default="{ row }">{{ row.message || '—' }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px; margin-bottom: 16px; flex-wrap: wrap;
}
.page-toolbar .left { display: flex; align-items: center; gap: 10px; }
.page-toolbar .right { display: flex; align-items: center; gap: 10px; }
.tip { font-size: 13px; color: var(--et-text-secondary); }
.ops-btn {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 14px; border-radius: 20px; border: 1px solid transparent;
  font-family: inherit; font-size: 13px; font-weight: 600; cursor: pointer; transition: all 0.18s;
}
.ops-btn.primary { background: rgba(109, 124, 255, 0.14); color: #a8b4ff; }
.ops-btn.primary:hover { background: rgba(109, 124, 255, 0.28); box-shadow: 0 0 12px rgba(109, 124, 255, 0.3); }
.ops-btn.danger { background: rgba(251, 113, 133, 0.14); color: #fb7185; }
.ops-btn.danger:hover { background: rgba(251, 113, 133, 0.28); box-shadow: 0 0 12px rgba(251, 113, 133, 0.3); }
.ops { display: flex; gap: 6px; }

/* 连接状态卡片 */
.conn-card { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 20px; margin-bottom: 14px; }
.conn-left { display: flex; align-items: center; gap: 14px; min-width: 0; }
.conn-icon {
  width: 40px; height: 40px; border-radius: 12px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center; font-size: 18px;
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-b)); color: #fff;
}
.conn-meta { display: flex; flex-direction: column; gap: 5px; min-width: 0; }
.conn-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.conn-label { font-size: 11px; color: var(--et-text-muted); font-weight: 600; letter-spacing: 0.5px; }
.conn-val { font-family: ui-monospace, monospace; font-size: 13px; font-weight: 600; }
.conn-token { font-family: ui-monospace, monospace; font-size: 11.5px; color: var(--et-grad-c); }
.conn-ns { font-size: 12px; color: var(--et-text-secondary); }
.conn-right { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.et-card-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px 20px 12px; font-size: 13.5px; font-weight: 700;
}
.count { font-size: 11.5px; color: var(--et-text-muted); font-weight: 500; }
.repo-name { display: flex; align-items: center; gap: 10px; }
.r-avatar {
  width: 34px; height: 34px; border-radius: 10px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-b));
  color: #fff;
}
.path { font-family: ui-monospace, monospace; font-size: 13px; font-weight: 600; }
.branch { font-family: ui-monospace, monospace; font-size: 12px; color: var(--et-text-secondary); }
.status { font-size: 11.5px; font-weight: 700; padding: 3px 10px; border-radius: 20px; }
.sync-sha { font-family: ui-monospace, monospace; font-size: 12px; color: var(--et-grad-c); }
.err { font-size: 12px; color: var(--et-danger); }
.muted { color: var(--et-text-muted); }
.sync-type { font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 6px; }
.sync-type.full { color: #a8b4ff; background: rgba(109, 124, 255, 0.14); }
.sync-type.incremental { color: #38e1ff; background: rgba(56, 225, 255, 0.12); }
</style>