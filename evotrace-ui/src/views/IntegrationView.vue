<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Key, CopyDocument, Connection, RefreshRight, Monitor, Box } from '@element-plus/icons-vue'
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

/* ---------- 展示辅助（不参与业务逻辑） ---------- */
const activeSdk = ref<string | null>(null)
const logoGrads = ['g-indigo', 'g-emerald', 'g-amber', 'g-violet', 'g-cyan']
const onlineCount = computed(() => projects.value.filter((p) => p.status === 'ACTIVE').length)

function logoCls(i: number) { return logoGrads[i % logoGrads.length] }
function toggleSdk(key: string) { activeSdk.value = activeSdk.value === key ? null : key }
function statusOf(p: Project) {
  if (p.status === 'ACTIVE') return { label: '在线', cls: 'ok' }
  if (p.status === 'SUSPENDED' || p.status === 'PAUSED') return { label: '暂停', cls: 'warn' }
  return { label: '离线', cls: 'off' }
}
function activityOf(p: Project) {
  let v = p.status === 'ACTIVE' ? 72 : p.status === 'SUSPENDED' || p.status === 'PAUSED' ? 38 : 16
  if (p.lastEventAt) {
    const h = (Date.now() - new Date(p.lastEventAt).getTime()) / 36e5
    if (!Number.isNaN(h) && h >= 0 && h < 24) v = Math.min(96, v + 20)
  }
  return v
}
function activityLabel(p: Project) {
  const v = activityOf(p)
  return v >= 80 ? '高活跃' : v >= 45 ? '稳定' : '待接入'
}
function sdkSnippet(projectKey: string) {
  if (activeGuide.value === 'cli') {
    return `evotrace scan --lang auto \\
  --project-key ${projectKey} \\
  --api-key <YOUR_API_KEY> \\
  --server http://localhost:8080`
  }
  if (activeGuide.value === 'webhook') {
    return `URL: http://localhost:8080/open-api/v1/webhooks/gitlab
Header: X-EvoTrace-Api-Key: <YOUR_API_KEY>
Events: Push / MR / Tag`
  }
  return `evotrace:
  server-url: http://localhost:8080
  project-key: ${projectKey}
  api-key: <YOUR_API_KEY>`
}
function fmtTime(t?: string) { return t ? t.replace('T', ' ').substring(0, 16) : '—' }

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

/** 项目下线/停用或重新启用。停用后新事件将被拒绝。 */
async function setProjectStatus(p: Project, status: string) {
  const isActive = status === 'ACTIVE'
  const tip = isActive
    ? `重新启用项目「${p.name}」？启用后恢复接收上报事件。`
    : `停用项目「${p.name}」？停用后将拒绝新的事件上报，现有数据保留。`
  try {
    await ElMessageBox.confirm(tip, isActive ? '重新启用项目' : '停用项目', {
      type: isActive ? 'success' : 'warning', confirmButtonText: isActive ? '启用' : '停用'
    })
  } catch { return }
  try {
    await projectApi.setStatus(p.projectKey, status)
    ElMessage.success(isActive ? '项目已启用' : '项目已停用')
    load()
  } catch { /* 错误提示由请求拦截器统一弹出 */ }
}

function copyToClipboard(text: string) {
  navigator.clipboard.writeText(text).then(() => ElMessage.success('已复制'))
}

onMounted(load)
</script>

<template>
  <div class="page">
    <!-- Hero -->
    <section class="et-hero rise" style="--d: 0.02s">
      <div class="hero-left">
        <h2>接入管理</h2>
        <div class="et-hero-sub">统一管理项目接入与 SDK 集成，实时掌握连接状态</div>
        <div class="hero-chips">
          <span class="chip-mini">已接入项目 <b>{{ projects.length }}</b></span>
          <span class="chip-mini">在线 <b>{{ onlineCount }}</b></span>
          <span class="chip-mini live"><span class="et-pulse"></span>连接状态 实时</span>
        </div>
      </div>
      <div class="hero-right">
        <el-button type="primary" :icon="Plus" @click="dialogVisible = true">新建项目接入</el-button>
      </div>
    </section>

    <!-- 接入流程 -->
    <section class="et-card rise steps-card" style="--d: 0.08s">
      <div class="et-card-body">
        <el-steps :active="projects.length ? 3 : 0" finish-status="success" align-center class="guide-steps">
          <el-step title="创建项目" description="生成项目标识与访问凭证" />
          <el-step title="接入 SDK" description="复制配置命令，快速完成接入" />
          <el-step title="上报验证" description="API 清单与变更自动同步" />
        </el-steps>
      </div>
    </section>

    <!-- 项目 / 应用 -->
    <section class="et-card rise loading-sec" style="--d: 0.12s" v-loading="loading">
      <div class="et-card-head">
        <span class="et-tic"><el-icon :size="15"><Connection /></el-icon></span>
        <div>
          <div class="et-card-title">接入管理</div>
          <div class="et-card-sub">项目 SDK 接入配置与连接状态</div>
        </div>
        <div class="right">
          <button class="et-link-more" @click="load"><el-icon :size="13"><RefreshRight /></el-icon>刷新</button>
          <div class="et-seg">
            <button :class="{ on: activeTab === 'projects' }" @click="activeTab = 'projects'">接入项目</button>
            <button :class="{ on: activeTab === 'apps' }" @click="activeTab = 'apps'">应用管理</button>
          </div>
        </div>
      </div>
      <div class="et-card-body">
        <!-- ===== 接入项目（玻璃卡片列表） ===== -->
        <template v-if="activeTab === 'projects'">
          <div v-if="!loading && projects.length === 0" class="et-empty-hint">
            <div class="et-empty-ic"><el-icon :size="24"><Connection /></el-icon></div>
            暂无接入项目，点击「新建项目接入」开始接入
          </div>
          <div v-else class="proj-list">
            <div
              v-for="(p, i) in projects" :key="p.projectKey"
              class="proj-card et-card rise" :class="{ open: activeSdk === p.projectKey }"
              :style="{ '--d': (0.16 + i * 0.06) + 's' }"
            >
              <div class="proj-head">
                <span class="et-g-ic" :class="logoCls(i)">{{ p.name.charAt(0).toUpperCase() }}</span>
                <div class="proj-info">
                  <div class="proj-top">
                    <span class="proj-name">{{ p.name }}</span>
                    <span class="proj-key">{{ p.projectKey }}</span>
                    <span class="st-pill" :class="statusOf(p).cls">
                      <span v-if="statusOf(p).cls === 'ok'" class="et-pulse"></span>
                      <span v-else class="st-dot" :class="statusOf(p).cls"></span>
                      {{ statusOf(p).label }}
                    </span>
                  </div>
                  <div class="proj-meta">
                    <span>技术栈 {{ p.techStack ?? '—' }}</span>
                    <span class="meta-sep">·</span>
                    <span>最近上报 {{ fmtTime(p.lastEventAt) }}</span>
                  </div>
                  <div class="proj-bar-row">
                    <div class="et-bar"><i :style="{ width: activityOf(p) + '%' }"></i></div>
                    <span class="proj-act">{{ activityLabel(p) }}</span>
                  </div>
                </div>
                <div class="proj-actions">
                  <el-button size="small" :icon="Key" @click="showCredentials(p.projectKey)">凭证</el-button>
                  <el-button size="small" :icon="Connection"
                             :type="activeSdk === p.projectKey ? 'primary' : 'default'"
                             @click="toggleSdk(p.projectKey)">接入 SDK</el-button>
                  <el-button
                    v-if="p.status === 'ACTIVE'"
                    size="small" type="warning" plain :icon="Monitor"
                    @click="setProjectStatus(p, 'SUSPENDED')">停用</el-button>
                  <el-button
                    v-else
                    size="small" type="success" plain :icon="Connection"
                    @click="setProjectStatus(p, 'ACTIVE')">启用</el-button>
                </div>
              </div>

              <!-- SDK 接入配置区 -->
              <div v-if="activeSdk === p.projectKey" class="sdk-block">
                <div class="sdk-tabs">
                  <button class="sdk-tab" :class="{ on: activeGuide === 'java' }" @click="activeGuide = 'java'">Java Starter</button>
                  <button class="sdk-tab" :class="{ on: activeGuide === 'cli' }" @click="activeGuide = 'cli'">CLI</button>
                  <button class="sdk-tab" :class="{ on: activeGuide === 'webhook' }" @click="activeGuide = 'webhook'">GitLab Webhook</button>
                </div>
                <div class="code-block">
                  <pre><code>{{ sdkSnippet(p.projectKey) }}</code></pre>
                  <button class="code-copy" title="复制命令" @click="copyToClipboard(sdkSnippet(p.projectKey))">
                    <el-icon :size="14"><CopyDocument /></el-icon>
                  </button>
                </div>
                <div class="sdk-note">
                  <el-icon :size="12"><Monitor /></el-icon>
                  API Key 请通过「凭证」查看；<b>{{ p.projectKey }}</b> 接入完成后，API 清单将自动同步
                </div>
              </div>
            </div>
          </div>
        </template>

        <!-- ===== 应用管理 ===== -->
        <template v-else>
          <div class="apps-toolbar">
            <el-select v-model="appProject" placeholder="选择项目" style="width: 220px" @change="loadApps">
              <el-option v-for="p in projects" :key="p.projectKey" :label="p.name + ' (' + p.projectKey + ')'" :value="p.projectKey" />
            </el-select>
            <el-button v-if="appProject" type="primary" size="small" :icon="Plus" @click="openAppDialog(appProject)">新建应用</el-button>
          </div>
          <div v-if="appProject && !loading && apps.length === 0" class="et-empty-hint">
            <div class="et-empty-ic"><el-icon :size="24"><Box /></el-icon></div>
            该项目暂无应用
          </div>
          <div v-else class="app-list">
            <div v-for="(app, i) in apps" :key="app.appKey" class="app-row">
              <span class="et-g-ic app-logo" :class="logoCls(i)">{{ app.name.charAt(0).toUpperCase() }}</span>
              <div class="app-main">
                <div class="app-top">
                  <span class="app-name">{{ app.name }}</span>
                  <span class="app-key">{{ app.appKey }}</span>
                </div>
                <div class="app-meta">
                  <span>技术栈 {{ app.techStack ?? '—' }}</span>
                  <span class="meta-sep">·</span>
                  <span>负责人 {{ app.owner ?? '—' }}</span>
                </div>
              </div>
              <el-button size="small" text type="primary" @click="openAppDialog(appProject, app)">编辑</el-button>
            </div>
          </div>
        </template>
      </div>
    </section>

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
      <el-steps :active="created ? 1 : 0" finish-status="success" align-center class="dialog-steps">
        <el-step title="填写项目信息" description="标识、名称与仓库地址" />
        <el-step title="生成凭证并接入" description="复制凭证与 SDK 配置" />
      </el-steps>
      <div v-if="!created" class="dialog-body">
        <el-form label-width="80px">
          <el-form-item label="标识"><el-input v-model="form.projectKey" placeholder="如 my-project" /></el-form-item>
          <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
          <el-form-item label="仓库"><el-input v-model="form.repoUrl" /></el-form-item>
        </el-form>
      </div>
      <div v-else class="dialog-body">
        <el-alert type="success" :closable="false" title="创建成功（Secret 仅展示一次）" style="margin-bottom: 12px" />
        <div class="cred-box">
          <div class="cred-row"><span>API Key</span><code>{{ created.apiKey }}</code><el-button size="small" text :icon="CopyDocument" @click="copyToClipboard(created.apiKey)" /></div>
          <div class="cred-row"><span>API Secret</span><code>{{ created.apiSecret }}</code><el-button size="small" text :icon="CopyDocument" @click="copyToClipboard(created.apiSecret)" /></div>
        </div>
        <el-tabs v-model="activeGuide" style="margin-top: 12px">
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
        <el-table-column label="API Key" min-width="200">
          <template #default="{ row }">
            <code class="key-code">{{ row.apiKey }}</code>
            <el-button size="small" text :icon="CopyDocument" @click="copyToClipboard(row.apiKey)" />
          </template>
        </el-table-column>
        <el-table-column prop="scope" label="范围" width="80" />
        <el-table-column prop="createdAt" label="创建时间" width="170" />
        <el-table-column label="操作" width="80"><template #default="{ row }"><el-button size="small" type="danger" text @click="revokeCredential(row.id)">吊销</el-button></template></el-table-column>
      </el-table>
      <template #footer><el-button type="warning" @click="rotateCredential">轮换全部凭证</el-button><el-button @click="credDialogVisible=false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page .et-hero { display: flex; align-items: center; justify-content: space-between; gap: 24px; margin-bottom: 18px; flex-wrap: wrap; }
.hero-left { min-width: 0; }
.hero-chips { display: flex; align-items: center; gap: 10px; margin-top: 16px; flex-wrap: wrap; }
.hero-right { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }
.chip-mini {
  display: inline-flex; align-items: center; gap: 7px;
  font-size: 12px; color: var(--et-text-secondary);
  padding: 6px 12px; border-radius: 10px;
  background: var(--et-bg-muted); border: 1px solid var(--et-border);
}
.chip-mini b { color: var(--et-text); font-variant-numeric: tabular-nums; }
.chip-mini.live { color: var(--et-ok); }
.chip-mini .et-pulse { width: 6px; height: 6px; }

.loading-sec { position: relative; }

/* ---------- 接入流程 steps ---------- */
.steps-card .et-card-body { padding: 20px 22px; }
.guide-steps { max-width: 900px; margin: 0 auto; }
.guide-steps :deep(.el-step__icon) {
  width: 30px; height: 30px; font-size: 13px;
  border-color: var(--et-border); background: var(--et-bg-muted); color: var(--et-text-secondary);
}
.guide-steps :deep(.el-step__title) { font-size: 13px; font-weight: 600; color: var(--et-text-secondary); }
.guide-steps :deep(.el-step__description) { font-size: 11.5px; color: var(--et-text-muted); }
.guide-steps :deep(.el-step__line) { background: var(--et-border); }
.guide-steps :deep(.el-step__head.is-process .el-step__icon) {
  border-color: var(--et-grad-b); color: var(--et-grad-b);
  box-shadow: 0 0 0 4px rgba(167, 139, 250, 0.15);
}
.guide-steps :deep(.el-step__head.is-process .el-step__title) { color: var(--et-text); }
.guide-steps :deep(.el-step__head.is-success .el-step__icon) {
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-b));
  border-color: transparent; color: #fff; box-shadow: 0 4px 12px var(--et-glow);
}
.guide-steps :deep(.el-step__head.is-success .el-step__line) { background: linear-gradient(90deg, var(--et-grad-a), var(--et-grad-c)); }
.guide-steps :deep(.el-step__head.is-success .el-step__title) { color: var(--et-text); }

/* ---------- 项目卡片 ---------- */
.proj-list { display: flex; flex-direction: column; }
.proj-card { padding: 16px 18px; }
.proj-card.open { border-color: rgba(109, 124, 255, 0.45); }
.proj-head { display: flex; align-items: flex-start; gap: 14px; }
.proj-info { flex: 1; min-width: 0; }
.proj-top { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.proj-name { font-size: 15px; font-weight: 700; }
.proj-key {
  font-size: 12px; color: var(--et-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  background: var(--et-bg-muted); border: 1px solid var(--et-border);
  padding: 2px 8px; border-radius: 7px;
}
.proj-meta { display: flex; align-items: center; gap: 8px; margin-top: 6px; font-size: 12px; color: var(--et-text-muted); flex-wrap: wrap; }
.meta-sep { opacity: 0.5; }
.proj-bar-row { display: flex; align-items: center; gap: 10px; margin-top: 10px; max-width: 440px; }
.proj-bar-row .et-bar { flex: 1; }
.proj-act { font-size: 11px; color: var(--et-text-muted); font-variant-numeric: tabular-nums; flex-shrink: 0; }
.proj-actions { display: flex; gap: 8px; flex-shrink: 0; }

/* 状态 pill */
.st-pill {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 11px; font-weight: 700;
  padding: 3px 9px; border-radius: 20px; flex-shrink: 0;
}
.st-pill.ok { color: var(--et-ok); background: rgba(52, 211, 153, 0.12); }
.st-pill.warn { color: var(--et-warn); background: rgba(251, 191, 36, 0.12); }
.st-pill.off { color: var(--et-text-muted); background: var(--et-bg-muted); }
.st-pill .et-pulse { width: 7px; height: 7px; }
.st-dot { width: 7px; height: 7px; border-radius: 50%; }
.st-dot.warn { background: var(--et-warn); }
.st-dot.off { background: var(--et-text-muted); }

/* ---------- SDK 接入配置区 ---------- */
.sdk-block {
  margin-top: 14px; padding: 12px 14px;
  border: 1px dashed var(--et-hover-border); border-radius: 12px;
  background: var(--et-bg-muted);
}
.sdk-tabs { display: flex; gap: 6px; margin-bottom: 10px; flex-wrap: wrap; }
.sdk-tab {
  padding: 4px 12px; border-radius: 8px;
  font-size: 12px; font-weight: 600; color: var(--et-text-muted);
  background: none; border: 1px solid transparent; cursor: pointer;
  font-family: inherit; transition: all 0.15s;
}
.sdk-tab:hover { color: var(--et-text); }
.sdk-tab.on {
  color: var(--et-text); background: var(--et-card-solid);
  border-color: var(--et-hover-border); box-shadow: 0 2px 8px rgba(2, 6, 23, 0.25);
}
.code-block {
  position: relative; border-radius: 10px; overflow: hidden;
  background: var(--et-card-solid); border: 1px solid var(--et-border);
}
.code-block pre { margin: 0; padding: 12px 44px 12px 14px; overflow-x: auto; }
.code-block code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px; color: var(--et-text); line-height: 1.7; white-space: pre;
}
.code-copy {
  position: absolute; top: 8px; right: 8px;
  width: 28px; height: 28px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  color: var(--et-text-muted); background: var(--et-bg-muted);
  border: 1px solid var(--et-border); cursor: pointer; transition: all 0.15s;
}
.code-copy:hover { color: var(--et-grad-c); border-color: var(--et-hover-border); }
.sdk-note {
  display: flex; align-items: center; gap: 6px; margin-top: 10px;
  font-size: 11.5px; color: var(--et-text-muted);
}
.sdk-note b { color: var(--et-text-secondary); font-family: ui-monospace, Menlo, monospace; }

/* ---------- 应用管理 ---------- */
.apps-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.app-list { display: flex; flex-direction: column; }
.app-row {
  display: flex; align-items: center; gap: 13px;
  padding: 11px 10px; border-radius: 12px;
  border-bottom: 1px solid var(--et-border); transition: background 0.15s;
}
.app-row:last-child { border-bottom: none; }
.app-row:hover { background: rgba(109, 124, 255, 0.05); }
.app-logo { width: 34px; height: 34px; font-size: 13px; border-radius: 10px; }
.app-main { flex: 1; min-width: 0; }
.app-top { display: flex; align-items: center; gap: 9px; }
.app-name { font-size: 13.5px; font-weight: 700; }
.app-key { font-size: 11.5px; color: var(--et-text-muted); font-family: ui-monospace, Menlo, monospace; }
.app-meta { margin-top: 3px; font-size: 11.5px; color: var(--et-text-muted); display: flex; gap: 8px; }

/* ---------- 弹窗 ---------- */
.dialog-body { margin-top: 18px; }
.dialog-steps { margin-bottom: 6px; }
.dialog-steps :deep(.el-step__icon) {
  width: 26px; height: 26px; font-size: 12px;
  background: var(--et-bg-muted); border-color: var(--et-border); color: var(--et-text-secondary);
}
.dialog-steps :deep(.el-step__title) { font-size: 12.5px; font-weight: 600; color: var(--et-text-secondary); }
.dialog-steps :deep(.el-step__line) { background: var(--et-border); }
.dialog-steps :deep(.el-step__head.is-process .el-step__icon) { border-color: var(--et-grad-b); color: var(--et-grad-b); }
.dialog-steps :deep(.el-step__head.is-success .el-step__icon) {
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-b));
  border-color: transparent; color: #fff;
}
.dialog-steps :deep(.el-step__head.is-success .el-step__line) { background: linear-gradient(90deg, var(--et-grad-a), var(--et-grad-c)); }

.cred-box {
  background: var(--et-card-solid); border: 1px solid var(--et-border);
  border-radius: 12px; padding: 14px 16px;
}
.cred-row { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.cred-row:last-child { margin-bottom: 0; }
.cred-row span { width: 84px; font-size: 12.5px; font-weight: 600; color: var(--et-text-secondary); flex-shrink: 0; }
.cred-row code {
  flex: 1; font-family: ui-monospace, Menlo, Consolas, monospace;
  font-size: 12px; color: var(--et-grad-c);
  background: var(--et-bg-muted); border: 1px solid var(--et-border);
  padding: 6px 10px; border-radius: 8px; word-break: break-all;
}
.guide {
  background: var(--et-card-solid); border: 1px solid var(--et-border);
  padding: 14px; border-radius: 10px; overflow-x: auto;
}
.guide code { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; color: var(--et-text); white-space: pre; line-height: 1.7; }
.key-code { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; color: var(--et-grad-c); }
</style>
