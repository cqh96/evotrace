<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Upload, Refresh, Setting, VideoPlay, Coin, DocumentAdd, Delete, Search, Files, Link
} from '@element-plus/icons-vue'
import { useProjectStore } from '../stores/project'
import { apiDebugApi, applicationApi, type ApiEndpoint, type ApiEnvironment, type ApiTestCase } from '../api'

const projectStore = useProjectStore()
const { current: projectKey } = storeToRefs(projectStore)

const loading = ref(false)
const endpoints = ref<ApiEndpoint[]>([])
const apps = ref<{ id: number; appKey: string; name: string; baseUrl?: string }[]>([])
const environments = ref<ApiEnvironment[]>([])
const testCases = ref<ApiTestCase[]>([])
const search = ref('')
const activeTab = ref('apis')

const selected = ref<ApiEndpoint | null>(null)
const method = ref('GET')
const baseUrl = ref('')
const seq = ref(0)

// request editor
const pathParams = ref<Record<string, string>>({})
const queryRows = ref<{ key: string; value: string }[]>([])
const headerRows = ref<{ key: string; value: string }[]>([])
const bodyText = ref('')

// response
const sending = ref(false)
const result = ref<{ status: number; durationMs: number; body: unknown; error?: string | null } | null>(null)

const groupByApp = computed<{ app: string; list: ApiEndpoint[] }[]>(() => {
  const map = new Map<string, ApiEndpoint[]>()
  for (const ep of endpoints.value) {
    if (search.value && !`${ep.method} ${ep.path} ${ep.name ?? ''}`.toLowerCase().includes(search.value.toLowerCase())) continue
    const key = ep.appKey ?? '未分组'
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(ep)
  }
  return Array.from(map, ([app, list]) => ({ app, list }))
})

const methodColor = (m: string) => {
  const c: Record<string, string> = { GET: '#34d399', POST: '#a5b0ff', PUT: '#fbbf24', DELETE: '#fb7185', PATCH: '#f472b6' }
  return c[m.toUpperCase()] ?? '#93a0bd'
}

async function load() {
  if (!projectKey.value) return
  loading.value = true
  try {
    const [eps, envs, cases, appList] = await Promise.all([
      apiDebugApi.list(projectKey.value),
      apiDebugApi.environments(projectKey.value),
      apiDebugApi.testCases(projectKey.value),
      applicationApi.list(projectKey.value)
    ])
    endpoints.value = eps
    environments.value = envs
    testCases.value = cases
    apps.value = appList
  } catch { /* 拦截器已提示 */ } finally { loading.value = false }
}

watch(projectKey, load, { immediate: true })

function selectEndpoint(ep: ApiEndpoint) {
  selected.value = ep
  method.value = ep.method
  seq.value++
  // populate editor from endpoint
  objectToRows(ep.params?.filter(p => p.in === 'query') ?? [], queryRows.value, 'query')
  objectToRows(ep.params?.filter(p => p.in === 'path') ?? [], headerRows.value, 'path')
  pathParams.value = {}
  for (const p of ep.params ?? []) {
    if (p.in === 'path') pathParams.value[p.name] = p.name
  }
  headerRows.value = []
  bodyText.value = ep.requestBody ? JSON.stringify(ep.requestBody, null, 2) : ''
  // base url from app
  const app = apps.value.find(a => a.id === ep.appId)
  baseUrl.value = app?.baseUrl ?? ''
  result.value = null
}

function objectToRows(list: { name: string; desc?: string }[], target: { key: string; value: string }[], mode: string) {
  target.length = 0
  for (const p of list) {
    if (mode === 'query') target.push({ key: p.name, value: '' })
  }
}

function addRow(rows: { key: string; value: string }[]) { rows.push({ key: '', value: '' }) }
function removeRow(rows: { key: string; value: string }[], i: number) { rows.splice(i, 1) }

interface SendBody { pathParams: Record<string, string>; query: Record<string, string>; headers: Record<string, string>; body: string }

function buildRequest(): SendBody {
  const query: Record<string, string> = {}
  for (const r of queryRows.value) if (r.key) query[r.key] = r.value
  const headers: Record<string, string> = {}
  for (const r of headerRows.value) if (r.key) headers[r.key] = r.value
  return { pathParams: pathParams.value, query, headers, body: bodyText.value }
}

async function send() {
  if (!selected.value) return
  sending.value = true
  try {
    const res = await apiDebugApi.debug(projectKey.value, selected.value.id, buildRequest() as unknown as Record<string, unknown>, baseUrl.value || undefined)
    result.value = { status: res.status, durationMs: res.durationMs, body: res.body, error: res.error }
  } catch (e: any) {
    result.value = { status: 0, durationMs: 0, body: null, error: e?.message }
  } finally { sending.value = false }
}

async function mock() {
  if (!selected.value) return
  try {
    const m = await apiDebugApi.mock(projectKey.value, selected.value.id)
    result.value = { status: 200, durationMs: 0, body: m, error: null }
  } catch { /* 拦截器已提示 */ }
}

async function saveAsCase() {
  if (!selected.value) return
  try {
    const { value } = await ElMessageBox.prompt('为用例命名（可留空自动生成）', '保存为用例', {
      inputValue: `用例 - ${selected.value.name || selected.value.path}`,
      confirmButtonText: '保存', cancelButtonText: '取消'
    })
    await apiDebugApi.saveTestCase(projectKey.value, {
      endpointId: selected.value.id,
      name: value || selected.value.path,
      request: buildRequest() as unknown as Record<string, unknown>,
      response: result.value?.body as Record<string, unknown> ?? null,
      expectedStatus: result.value?.status ?? null,
      lastStatus: result.value?.status ?? null,
      lastDurationMs: result.value?.durationMs ?? null
    })
    ElMessage.success('已保存用例')
    testCases.value = await apiDebugApi.testCases(projectKey.value)
  } catch { /* 取消 */ }
}

async function deleteCase(t: ApiTestCase) {
  try {
    await ElMessageBox.confirm(`删除用例「${t.name}」？`, '删除用例', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    await apiDebugApi.deleteTestCase(projectKey.value, t.id)
    testCases.value = await apiDebugApi.testCases(projectKey.value)
    ElMessage.success('已删除')
  } catch { /* 取消 */ }
}

function loadCase(t: ApiTestCase) {
  const ep = endpoints.value.find(e => e.id === t.endpointId)
  if (ep) {
    selectEndpoint(ep)
    const req = t.request as unknown as SendBody | undefined
    if (req) {
      pathParams.value = req.pathParams ?? {}
      queryRows.value = Object.entries(req.query ?? {}).map(([k, v]) => ({ key: k, value: v }))
      headerRows.value = Object.entries(req.headers ?? {}).map(([k, v]) => ({ key: k, value: v }))
      bodyText.value = req.body ?? ''
    }
    result.value = t.response ? { status: t.lastStatus ?? 200, durationMs: t.lastDurationMs ?? 0, body: t.response, error: null } : null
  }
}

// ---------- import ----------
const importOpen = ref(false)
const importFormat = ref('openapi')
const importApp = ref<number | null>(null)
const importContent = ref('')
const importLoading = ref(false)
const formats = [
  { value: 'openapi', label: 'OpenAPI / Swagger' },
  { value: 'postman', label: 'Postman' },
  { value: 'curl', label: 'cURL' },
  { value: 'apifox', label: 'Apifox' },
  { value: 'java', label: 'Java 项目代码' }
]
async function doImport() {
  if (!importContent.value.trim()) { ElMessage.warning('请粘贴接口文档内容'); return }
  importLoading.value = true
  try {
    const fmt = importFormat.value === 'java' ? 'openapi' : importFormat.value
    const res = await apiDebugApi.import(projectKey.value, fmt, importApp.value, importContent.value)
    ElMessage.success(`导入成功，共 ${res.imported} 个接口`)
    importOpen.value = false
    importContent.value = ''
    load()
  } catch (e: any) { ElMessage.error(e?.message ?? '导入失败') } finally { importLoading.value = false }
}

async function sync() {
  try {
    const res = await apiDebugApi.sync(projectKey.value)
    ElMessage.success(`已从 SDK 清单同步 ${res.synced} 个接口`)
    load()
  } catch { /* 拦截器已提示 */ }
}

// ---------- environments ----------
const envOpen = ref(false)
const envForm = ref<ApiEnvironment>({ id: 0, projectId: 0, name: '', baseUrl: '', headers: {} })
function openEnvEditor(env?: ApiEnvironment) {
  envForm.value = env ? { ...env, headers: { ...(env.headers ?? {}) } } : { id: 0, projectId: 0, name: '', baseUrl: '', headers: {} }
  envOpen.value = true
}
async function saveEnv() {
  if (!envForm.value.name) { ElMessage.warning('环境名称必填'); return }
  await apiDebugApi.saveEnvironment(projectKey.value, envForm.value)
  ElMessage.success('环境已保存')
  envOpen.value = false
  environments.value = await apiDebugApi.environments(projectKey.value)
}
async function deleteEnv(id: number) {
  try {
    await ElMessageBox.confirm('删除该环境？', '删除环境', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    await apiDebugApi.deleteEnvironment(projectKey.value, id)
    environments.value = await apiDebugApi.environments(projectKey.value)
  } catch { /* 取消 */ }
}
function useEnv(env: ApiEnvironment) { baseUrl.value = env.baseUrl ?? '' }

const prettyBody = computed(() => {
  try { return JSON.stringify(result.value?.body, null, 2) } catch { return String(result.value?.body ?? '') }
})
</script>

<template>
  <div class="api-debug-page">
    <!-- 顶部 Hero -->
    <div class="et-hero hero-row" style="margin-bottom: 16px">
      <div class="hero-main">
        <div class="hero-icon et-g-ic g-cyan">API</div>
        <div>
          <h2>接口调试</h2>
          <div class="et-hero-sub">接口文档 · 在线调试 · Mock 服务 · 多格式导入</div>
        </div>
      </div>
      <div class="hero-actions">
        <el-button size="small" class="ops-btn" :icon="Upload" @click="importOpen = true">导入接口</el-button>
        <el-button size="small" class="ops-btn" :icon="Refresh" @click="sync">同步清单</el-button>
        <el-button size="small" class="ops-btn" :icon="Setting" @click="openEnvEditor()">环境</el-button>
      </div>
    </div>

    <!-- 顶部工具栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input v-model="search" size="small" placeholder="搜索接口…" clearable :prefix-icon="Search" class="search-input" />
      </div>
    </div>

    <div class="main">
      <!-- 左：接口/用例列表 -->
      <div class="side-panel">
        <el-tabs v-model="activeTab" class="side-tabs">
          <el-tab-pane label="接口" name="apis">
            <div v-loading="loading" class="side-scroll">
              <template v-for="g in groupByApp" :key="g.app">
                <div class="app-group">{{ g.app }} <span class="count">{{ g.list.length }}</span></div>
                <div
                  v-for="ep in g.list" :key="ep.id"
                  class="ep-item" :class="{ active: selected?.id === ep.id }"
                  @click="selectEndpoint(ep)"
                >
                  <span class="m-method" :style="{ color: methodColor(ep.method) }">{{ ep.method }}</span>
                  <span class="m-path" :title="ep.path">{{ ep.path }}</span>
                </div>
              </template>
              <el-empty v-if="!loading && endpoints.length === 0" description="暂无接口，点击「同步清单」或「导入接口」" :image-size="60" />
            </div>
          </el-tab-pane>
          <el-tab-pane label="用例" name="cases">
            <div class="side-scroll cases">
              <div v-for="t in testCases" :key="t.id" class="case-item" @click="loadCase(t)">
                <div class="case-name">
                  <Link :size="13" style="margin-right:6px;color:var(--et-primary-light)" />
                  <span class="ellipsis">{{ t.name }}</span>
                </div>
                <el-button size="small" text class="del-btn" :icon="Delete" @click.stop="deleteCase(t)" />
              </div>
              <el-empty v-if="testCases.length === 0" description="暂无用例" :image-size="60" />
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>

      <!-- 右：请求 + 响应 -->
      <div class="work-panel">
        <div class="req-bar">
          <el-select v-model="method" size="small" style="width: 110px" :disabled="!selected">
            <el-option v-for="m in ['GET','POST','PUT','DELETE','PATCH']" :key="m" :value="m" :label="m" />
          </el-select>
          <el-input v-model="baseUrl" size="small" placeholder="Base URL（默认取应用配置，可选环境覆盖）" clearable style="flex:1">
            <template #prepend>BASE</template>
          </el-input>
          <el-dropdown v-if="environments.length" trigger="click" @command="useEnv as any">
            <el-button size="small" class="ops-btn" :icon="Files">环境</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="env in environments" :key="env.id" :command="env">{{ env.name }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button size="small" type="primary" :loading="sending" :disabled="!selected" :icon="VideoPlay" @click="send">发送</el-button>
          <el-button size="small" class="ops-btn" :disabled="!selected" :icon="Coin" @click="mock">Mock</el-button>
          <el-button size="small" class="ops-btn" :disabled="!selected" :icon="DocumentAdd" @click="saveAsCase">存为用例</el-button>
        </div>

        <div v-if="selected" class="selected-info">
          <span class="m-method" :style="{ color: methodColor(selected.method) }">{{ selected.method }}</span>
          <span class="selected-path">{{ selected.path }}</span>
          <span v-if="selected.name" class="selected-name">{{ selected.name }}</span>
        </div>

        <!-- 请求编辑 tabs -->
        <div class="req-editor">
          <el-tabs class="req-tabs">
            <el-tab-pane label="Params">
              <div v-for="(p, i) in queryRows" :key="i" class="kv-row">
                <el-input v-model="p.key" size="small" placeholder="参数名" style="width:200px" />
                <el-input v-model="p.value" size="small" placeholder="值" style="flex:1" />
                <el-button size="small" text :icon="Delete" @click="removeRow(queryRows, i)" />
              </div>
              <el-button size="small" text type="primary" @click="addRow(queryRows)">+ 添加参数</el-button>
            </el-tab-pane>
            <el-tab-pane label="Headers">
              <div v-for="(h, i) in headerRows" :key="i" class="kv-row">
                <el-input v-model="h.key" size="small" placeholder="Header 名" style="width:200px" />
                <el-input v-model="h.value" size="small" placeholder="值" style="flex:1" />
                <el-button size="small" text :icon="Delete" @click="removeRow(headerRows, i)" />
              </div>
              <el-button size="small" text type="primary" @click="addRow(headerRows)">+ 添加 Header</el-button>
            </el-tab-pane>
            <el-tab-pane label="Body">
              <el-input v-model="bodyText" type="textarea" :rows="10" placeholder='请求体 JSON，如 {"key": "value"}' class="body-input" />
            </el-tab-pane>
          </el-tabs>
        </div>

        <!-- 响应 -->
        <div class="response-panel">
          <div class="resp-head">
            <span class="resp-label">响应</span>
            <span v-if="result" class="resp-meta">
              <span class="status" :class="result.status >= 200 && result.status < 400 ? 'ok' : 'err'">{{ result.status === 0 ? 'ERR' : result.status }}</span>
              <span v-if="result.durationMs" class="dur">{{ result.durationMs }}ms</span>
            </span>
          </div>
          <div v-if="result?.error" class="resp-error">{{ result.error }}</div>
          <pre v-else-if="result" class="resp-body">{{ prettyBody }}</pre>
          <div v-else class="resp-empty">选择左侧接口，编辑参数后点击「发送」调试；或点击「Mock」获取模拟数据。</div>
        </div>
      </div>
    </div>

    <!-- 导入弹窗 -->
    <el-dialog v-model="importOpen" title="导入接口文档" width="640px" :close-on-click-modal="false">
      <div class="import-form">
        <el-form label-width="90px">
          <el-form-item label="数据源格式">
            <el-radio-group v-model="importFormat">
              <el-radio-button v-for="f in formats" :key="f.value" :value="f.value">{{ f.label }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="所属应用">
            <el-select v-model="importApp" placeholder="选择应用（可选）" clearable style="width:100%">
              <el-option v-for="a in apps" :key="a.id" :value="a.id" :label="`${a.name} (${a.appKey})`" />
            </el-select>
          </el-form-item>
          <el-form-item label="内容">
            <el-input v-model="importContent" type="textarea" :rows="12" placeholder="粘贴 OpenAPI/Swagger(JSON 或 YAML)、Postman Collection、cURL 或 Apifox 导出内容" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="importOpen = false">取消</el-button>
        <el-button type="primary" :loading="importLoading" @click="doImport">导入</el-button>
      </template>
    </el-dialog>

    <!-- 环境弹窗 -->
    <el-dialog v-model="envOpen" :title="envForm.id ? '编辑环境' : '新建环境'" width="520px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="envForm.name" placeholder="如 生产 / 测试 / 本地" /></el-form-item>
        <el-form-item label="Base URL"><el-input v-model="envForm.baseUrl" placeholder="https://api.example.com" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="envOpen = false">取消</el-button>
        <el-button type="primary" @click="saveEnv">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.api-debug-page { display: flex; flex-direction: column; gap: 12px; height: calc(100vh - 150px); }
.toolbar { display: flex; justify-content: space-between; align-items: center; }
.toolbar-left, .toolbar-right { display: flex; gap: 8px; align-items: center; }
.search-input { width: 260px; }
.main { display: flex; gap: 12px; flex: 1; min-height: 0; }
.ops-btn { font-weight: 600; background: color-mix(in srgb, currentColor 12%, transparent); border-radius: 8px; }
.ops-btn:hover { background: color-mix(in srgb, currentColor 22%, transparent); box-shadow: 0 0 12px var(--et-glow); }

.side-panel { width: 320px; flex-shrink: 0; background: var(--et-card-bg); border: 1px solid var(--et-border); border-radius: 12px; display: flex; flex-direction: column; overflow: hidden; }
.side-tabs { flex: 1; display: flex; flex-direction: column; }
.side-tabs :deep(.el-tabs__header) { margin: 0; padding: 0 12px; }
.side-tabs :deep(.el-tabs__content) { flex: 1; overflow: hidden; }
.side-scroll { height: 100%; overflow-y: auto; padding: 6px 10px 12px; }
.app-group { font-size: 12px; color: var(--et-text-muted); margin: 10px 4px 6px; display: flex; align-items: center; gap: 6px; }
.app-group .count { background: var(--et-bg-muted); padding: 0 6px; border-radius: 8px; font-size: 11px; }
.ep-item { display: flex; align-items: center; gap: 8px; padding: 7px 10px; border-radius: 8px; cursor: pointer; }
.ep-item:hover { background: var(--et-bg-muted); }
.ep-item.active { background: var(--et-primary-bg); box-shadow: 0 0 0 1px var(--et-primary) inset; }
.m-method { font-size: 11px; font-weight: 700; width: 48px; flex-shrink: 0; }
.m-path { font-size: 12.5px; color: var(--et-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cases { padding: 10px; }
.case-item { display: flex; align-items: center; justify-content: space-between; padding: 8px 10px; border-radius: 8px; cursor: pointer; }
.case-item:hover { background: var(--et-bg-muted); }
.case-name { display: flex; align-items: center; min-width: 0; font-size: 13px; }
.ellipsis { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.del-btn { color: var(--et-text-muted); }
.del-btn:hover { color: #fb7185; }

.work-panel { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 10px; }
.req-bar { display: flex; gap: 8px; align-items: center; }
.selected-info { display: flex; align-items: center; gap: 10px; font-size: 13px; color: var(--et-text-secondary); }
.selected-path { color: var(--et-text); font-weight: 600; }
.selected-name { color: var(--et-text-muted); }
.req-editor { background: var(--et-card-bg); border: 1px solid var(--et-border); border-radius: 12px; padding: 4px 14px 12px; }
.kv-row { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
.body-input :deep(textarea) { font-family: 'SF Mono', Menlo, Consolas, monospace; background: rgba(4,8,18,0.4); }
.response-panel { flex: 1; min-height: 0; background: var(--et-card-bg); border: 1px solid var(--et-border); border-radius: 12px; display: flex; flex-direction: column; overflow: hidden; }
.resp-head { display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; border-bottom: 1px solid var(--et-border); }
.resp-label { font-weight: 600; font-size: 13px; }
.resp-meta { display: flex; gap: 10px; align-items: center; }
.status { font-weight: 700; font-size: 13px; }
.status.ok { color: #34d399; }
.status.err { color: #fb7185; }
.dur { color: var(--et-text-muted); font-size: 12px; }
.resp-body { flex: 1; overflow: auto; padding: 14px; margin: 0; font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 12.5px; color: var(--et-text); white-space: pre-wrap; word-break: break-word; }
.resp-error { flex: 1; padding: 14px; color: #fb7185; font-size: 13px; }
.resp-empty { flex: 1; display: flex; align-items: center; justify-content: center; color: var(--et-text-muted); font-size: 13px; }
</style>