<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Key, CopyDocument } from '@element-plus/icons-vue'
import FilterBar from '../components/FilterBar.vue'
import PageCard from '../components/PageCard.vue'
import { applicationApi, projectApi, credentialApi, type Project, type AppInfo } from '../api'

const projects = ref<Project[]>([])
const loading = ref(true)
const dialogVisible = ref(false)
const form = ref({ projectKey: '', name: '', repoUrl: '' })
const created = ref<{ apiKey: string; apiSecret: string } | null>(null)
const activeGuide = ref('java')
const credDialogVisible = ref(false)
const credProject = ref('')
const credentialList = ref<any[]>([])

// 应用管理
const activeTab = ref('projects')
const apps = ref<AppInfo[]>([])
const appDialogVisible = ref(false)
const appProject = ref('')
const appForm = ref({ appKey: '', name: '', techStack: '', owner: '' })
const editingApp = ref<string | null>(null)

async function load() {
  loading.value = true
  try { const d = await projectApi.list(); if (d?.length) projects.value = d } catch {}
  loading.value = false
}

async function loadApps() {
  if (!appProject.value) return
  try { apps.value = await applicationApi.list(appProject.value) } catch { apps.value = [] }
}

function openAppDialog(projectKey: string, app?: AppInfo) {
  appProject.value = projectKey
  editingApp.value = app?.appKey ?? null
  appForm.value = app
    ? { appKey: app.appKey, name: app.name, techStack: app.techStack ?? '', owner: app.owner ?? '' }
    : { appKey: '', name: '', techStack: '', owner: '' }
  appDialogVisible.value = true
}

async function saveApp() {
  if (!appProject.value) return
  try {
    if (editingApp.value) {
      await applicationApi.update(appProject.value, editingApp.value, {
        name: appForm.value.name, techStack: appForm.value.techStack, owner: appForm.value.owner
      })
    } else {
      await applicationApi.create(appProject.value, appForm.value)
    }
    ElMessage.success(editingApp.value ? '应用已更新' : '应用已创建')
    appDialogVisible.value = false
    await loadApps()
  } catch { /* 错误提示由请求拦截器统一弹出 */ }
}

async function create() {
  created.value = await projectApi.create(form.value)
  load()
}

async function showCredentials(key: string) {
  credProject.value = key
  try { credentialList.value = await credentialApi.list(key) } catch { credentialList.value = [] }
  credDialogVisible.value = true
}

async function rotateCredential() {
  try { const r = await credentialApi.rotate(credProject.value); ElMessage.success('凭证已轮换'); showCredentials(credProject.value) } catch { ElMessage.error('轮换失败') }
}

async function revokeCredential(id: number) {
  try { await credentialApi.revoke(credProject.value, id); ElMessage.success('已吊销'); showCredentials(credProject.value) } catch { ElMessage.error('吊销失败') }
}

function copyToClipboard(text: string) {
  navigator.clipboard.writeText(text).then(() => ElMessage.success('已复制'))
}

onMounted(load)
</script>

<template>
  <div>
    <FilterBar :loading="loading" @search="load">
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="dialogVisible = true">新建项目接入</el-button>
      </template>
    </FilterBar>

    <PageCard no-padding style="margin-top: 16px">
      <el-tabs v-model="activeTab" class="page-tabs">
        <el-tab-pane label="接入项目" name="projects">
          <div class="tab-content" v-loading="loading">
            <el-table :data="projects" stripe>
              <el-table-column prop="projectKey" label="标识" width="120" />
              <el-table-column prop="name" label="名称" width="140" />
              <el-table-column label="技术栈" width="150"><template #default="{ row }">{{ row.techStack ?? '—' }}</template></el-table-column>
              <el-table-column label="仓库" min-width="220"><template #default="{ row }">{{ row.repoUrl ?? '—' }}</template></el-table-column>
              <el-table-column label="最近上报" width="170"><template #default="{ row }">{{ row.lastEventAt ?? '—' }}</template></el-table-column>
              <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag size="small" :type="row.status==='ACTIVE'?'success':'info'">{{ row.status }}</el-tag></template></el-table-column>
              <el-table-column label="操作" width="140">
                <template #default="{ row }">
                  <el-button size="small" :icon="Key" @click="showCredentials(row.projectKey)">凭证</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <el-tab-pane label="应用管理" name="apps">
          <div class="tab-content">
            <div class="toolbar-row">
              <el-select v-model="appProject" placeholder="选择项目" size="small" style="width: 200px" @change="loadApps">
                <el-option v-for="p in projects" :key="p.projectKey" :label="p.name + ' (' + p.projectKey + ')'" :value="p.projectKey" />
              </el-select>
              <el-button v-if="appProject" type="primary" size="small" :icon="Plus" @click="openAppDialog(appProject)">新建应用</el-button>
            </div>
            <el-table :data="apps" stripe v-loading="loading">
              <el-table-column prop="appKey" label="应用标识" width="160" />
              <el-table-column prop="name" label="名称" width="180" />
              <el-table-column prop="techStack" label="技术栈" width="160"><template #default="{ row }">{{ row.techStack ?? '—' }}</template></el-table-column>
              <el-table-column prop="owner" label="负责人"><template #default="{ row }">{{ row.owner ?? '—' }}</template></el-table-column>
              <el-table-column label="操作" width="90">
                <template #default="{ row }">
                  <el-button size="small" @click="openAppDialog(appProject, row)">编辑</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="appProject && !loading && apps.length === 0" description="该项目暂无应用" :image-size="60" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </PageCard>

    <!-- 应用编辑弹窗 -->
    <el-dialog v-model="appDialogVisible" :title="editingApp ? '编辑应用' : '新建应用'" width="480px">
      <el-form label-width="80px">
        <el-form-item label="应用标识"><el-input v-model="appForm.appKey" :disabled="!!editingApp" placeholder="order-service" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="appForm.name" /></el-form-item>
        <el-form-item label="技术栈"><el-input v-model="appForm.techStack" placeholder="Java/SpringBoot" /></el-form-item>
        <el-form-item label="负责人"><el-input v-model="appForm.owner" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="appDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveApp">保存</el-button>
      </template>
    </el-dialog>

    <!-- Create dialog -->
    <el-dialog v-model="dialogVisible" title="新建项目接入" width="640px">
      <div v-if="!created">
        <el-form label-width="80px">
          <el-form-item label="标识"><el-input v-model="form.projectKey" placeholder="mall" /></el-form-item>
          <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
          <el-form-item label="仓库"><el-input v-model="form.repoUrl" /></el-form-item>
        </el-form>
      </div>
      <div v-else>
        <el-alert type="success" :closable="false" title="创建成功（Secret 仅展示一次）" style="margin-bottom:12px" />
        <div class="cred-box">
          <div class="cred-row"><span>API Key</span><code>{{ created.apiKey }}</code><el-button size="small" text :icon="CopyDocument" @click="copyToClipboard(created.apiKey)" /></div>
          <div class="cred-row"><span>API Secret</span><code>{{ created.apiSecret }}</code><el-button size="small" text :icon="CopyDocument" @click="copyToClipboard(created.apiSecret)" /></div>
        </div>
        <el-tabs v-model="activeGuide" style="margin-top:12px">
          <el-tab-pane label="Java Starter" name="java"><pre class="guide"><code>&lt;dependency&gt;
  &lt;groupId&gt;io.evotrace&lt;/groupId&gt;
  &lt;artifactId&gt;evotrace-spring-boot-starter&lt;/artifactId&gt;
  &lt;version&gt;0.1.0-SNAPSHOT&lt;/version&gt;
&lt;/dependency&gt;

evotrace:
  server-url: http://localhost:8080
  project-key: {{ form.projectKey }}
  api-key: {{ created.apiKey }}</code></pre></el-tab-pane>
          <el-tab-pane label="CLI" name="cli"><pre class="guide"><code>evotrace scan --lang auto \
  --project-key {{ form.projectKey }} \
  --api-key {{ created.apiKey }} \
  --server http://localhost:8080</code></pre></el-tab-pane>
          <el-tab-pane label="GitLab Webhook" name="webhook"><pre class="guide"><code>URL: http://localhost:8080/open-api/v1/webhooks/gitlab
Header: X-EvoTrace-Api-Key: {{ created.apiKey }}
Events: Push / MR / Tag</code></pre></el-tab-pane>
        </el-tabs>
      </div>
      <template #footer>
        <el-button v-if="!created" @click="dialogVisible=false">取消</el-button>
        <el-button v-if="!created" type="primary" @click="create">创建并生成凭证</el-button>
        <el-button v-else type="primary" @click="dialogVisible=false">完成</el-button>
      </template>
    </el-dialog>

    <!-- Credential dialog -->
    <el-dialog v-model="credDialogVisible" :title="'凭证管理 — ' + credProject" width="560px">
      <el-table :data="credentialList" size="small">
        <el-table-column label="API Key" min-width="200"><template #default="{row}"><code>{{ row.apiKey }}</code></template></el-table-column>
        <el-table-column prop="scope" label="范围" width="80" />
        <el-table-column prop="createdAt" label="创建时间" width="170" />
        <el-table-column label="操作" width="80"><template #default="{row}"><el-button size="small" type="danger" text @click="revokeCredential(row.id)">吊销</el-button></template></el-table-column>
      </el-table>
      <template #footer><el-button type="warning" @click="rotateCredential">轮换全部凭证</el-button><el-button @click="credDialogVisible=false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-tabs :deep(.el-tabs__header) { margin: 0; padding: 0 20px }
.tab-content { padding: 16px 20px 20px }
.toolbar-row { display: flex; align-items: center; gap: 10px; margin-bottom: 16px }
.cred-box { background: var(--et-page-bg); border-radius: 8px; padding: 16px }
.cred-row { display: flex; align-items: center; gap: 12px; margin-bottom: 8px }
.cred-row:last-child { margin-bottom: 0 }
.cred-row span { width: 80px; font-size: 13px; color: var(--et-text-secondary) }
.cred-row code { font-family: monospace; font-size: 12px; background: var(--et-card-bg); padding: 4px 8px; border-radius: 4px; word-break: break-all }
.guide { background: var(--et-page-bg); padding: 14px; border-radius: 8px; overflow-x: auto }
.guide code { font-size: 12px; color: var(--et-text); white-space: pre }
</style>
