<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Delete, Connection, VideoPlay, Monitor, MagicStick } from '@element-plus/icons-vue'
import { sqlConsoleApi, type SqlConsoleConnection, type SqlExecuteResult } from '../api'

const loading = ref(false)
const connections = ref<SqlConsoleConnection[]>([])
const currentId = ref<number | null>(null)
const sql = ref('')
const executing = ref(false)
const results = ref<SqlExecuteResult[]>([])
const history = ref<string[]>([])

const current = computed(() => connections.value.find((c) => c.id === currentId.value) ?? null)

async function load() {
  loading.value = true
  try {
    connections.value = await sqlConsoleApi.connections()
    if (!current.value && connections.value.length) {
      currentId.value = connections.value[0].id
    }
  } catch {
    ElMessage.error('加载连接失败')
  } finally {
    loading.value = false
  }
}

/* ==================== 连接管理 ==================== */
const dialogOpen = ref(false)
const editing = ref<SqlConsoleConnection | null>(null)
const form = ref<Record<string, unknown>>({
  name: '', sshHost: '', sshPort: 22, sshUser: '', sshPassword: '', sshKeyPath: '',
  dbType: 'postgres', dbHost: '', dbPort: 5432, dbName: '', dbUser: '', dbPassword: ''
})
const testing = ref(false)
const testResult = ref('')

function openCreate() {
  editing.value = null
  form.value = { name: '', sshHost: '', sshPort: 22, sshUser: '', sshPassword: '', sshKeyPath: '',
    dbType: 'postgres', dbHost: '', dbPort: 5432, dbName: '', dbUser: '', dbPassword: '' }
  testResult.value = ''
  dialogOpen.value = true
}

function openEdit(c: SqlConsoleConnection) {
  editing.value = c
  form.value = { name: c.name, sshHost: c.sshHost, sshPort: c.sshPort, sshUser: c.sshUser,
    sshPassword: '', sshKeyPath: '', dbType: c.dbType, dbHost: c.dbHost, dbPort: c.dbPort,
    dbName: c.dbName, dbUser: c.dbUser, dbPassword: '' }
  testResult.value = ''
  dialogOpen.value = true
}

async function saveConnection() {
  try {
    if (editing.value) {
      await sqlConsoleApi.update(editing.value.id, form.value)
      ElMessage.success('连接已更新')
    } else {
      const r = await sqlConsoleApi.create(form.value)
      currentId.value = r.id
      ElMessage.success('连接已创建')
    }
    dialogOpen.value = false
    load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  }
}

async function testConnection() {
  testing.value = true
  testResult.value = ''
  try {
    if (!editing.value) {
      // 先临时保存再测试
      const r = await sqlConsoleApi.create(form.value)
      currentId.value = r.id
      editing.value = null
      dialogOpen.value = false
      load()
      const res = await sqlConsoleApi.test(r.id)
      ElMessage[res.ok ? 'success' : 'error'](res.message)
      return
    }
    const res = await sqlConsoleApi.test(editing.value.id)
    testResult.value = `${res.ok ? '✅' : '❌'} ${res.message}(${res.elapsedMs}ms)`
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '测试失败')
  } finally {
    testing.value = false
  }
}

async function removeConnection(c: SqlConsoleConnection) {
  try {
    await ElMessageBox.confirm(`确认删除连接「${c.name}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await sqlConsoleApi.remove(c.id)
    if (currentId.value === c.id) currentId.value = null
    ElMessage.success('已删除')
    load()
  } catch {
    ElMessage.error('删除失败')
  }
}

/* ==================== 执行 ==================== */
async function run() {
  if (!current.value) return ElMessage.warning('请先选择连接')
  if (!sql.value.trim()) return ElMessage.warning('请输入 SQL')
  executing.value = true
  results.value = []
  try {
    results.value = await sqlConsoleApi.execute(current.value.id, sql.value)
    if (!history.value.includes(sql.value)) history.value.unshift(sql.value)
    if (history.value.length > 20) history.value.pop()
    const errs = results.value.filter((r) => r.error)
    if (!errs.length) ElMessage.success(`执行完成,共 ${results.value.length} 条语句`)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '执行失败')
  } finally {
    executing.value = false
  }
}

function onEditorKeydown(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
    e.preventDefault()
    run()
  }
}

function useHistory(s: string) {
  sql.value = s
  nextTick(() => {
    const ta = document.querySelector<HTMLTextAreaElement>('.sql-editor textarea')
    ta?.focus()
  })
}

onMounted(load)
</script>

<template>
  <div class="sql-console-page">
    <!-- Hero -->
    <div class="et-hero hero-row">
      <div class="hero-main">
        <div class="hero-icon et-g-ic g-cyan"><el-icon :size="18"><Monitor /></el-icon></div>
        <div>
          <h2>SQL 终端</h2>
          <div class="et-hero-sub">SSH 跳板连接内网数据库 · 界面执行 SQL · 结果一目了然</div>
        </div>
      </div>
      <div class="hero-actions">
        <button class="ops-btn primary" @click="openCreate"><el-icon><Plus /></el-icon> 新建连接</button>
        <button class="ops-btn" @click="load"><el-icon><Refresh /></el-icon> 刷新</button>
      </div>
    </div>

    <div class="layout-row">
      <!-- 左:连接列表 -->
      <div class="conn-panel">
        <div class="panel-title">连接列表({{ connections.length }})</div>
        <div v-if="!connections.length && !loading" class="empty">
          <el-icon :size="28"><Connection /></el-icon>
          <p>还没有连接<br />点击「新建连接」配置 SSH 跳板</p>
        </div>
        <div v-for="c in connections" :key="c.id" class="conn-item" :class="{ active: c.id === currentId }" @click="currentId = c.id">
          <div class="conn-main">
            <div class="conn-name">{{ c.name }}</div>
            <div class="conn-meta">{{ c.dbType }} · {{ c.dbHost }}:{{ c.dbPort }}/{{ c.dbName }}</div>
            <div class="conn-ssh">⇢ {{ c.sshUser }}@{{ c.sshHost }}:{{ c.sshPort }}</div>
          </div>
          <div class="conn-ops" @click.stop>
            <button class="mini-btn" title="测试连接" @click="sqlConsoleApi.test(c.id).then(r => ElMessage[r.ok ? 'success' : 'error'](r.message))"><el-icon :size="13"><VideoPlay /></el-icon></button>
            <button class="mini-btn" title="编辑" @click="openEdit(c)"><el-icon :size="13"><MagicStick /></el-icon></button>
            <button class="mini-btn danger" title="删除" @click="removeConnection(c)"><el-icon :size="13"><Delete /></el-icon></button>
          </div>
        </div>
      </div>

      <!-- 右:SQL 编辑 + 结果 -->
      <div class="work-panel">
        <div class="sql-card et-card">
          <div class="sql-head">
            <span class="current-name">{{ current ? `${current.name}(${current.dbType} / ${current.dbName})` : '未选择连接' }}</span>
            <div class="sql-actions">
              <button v-if="history.length" class="ops-btn" @click="useHistory(history[0])">
                <el-icon><Connection /></el-icon> 上次 SQL
              </button>
              <button class="ops-btn primary" :disabled="!current || executing" @click="run">
                <el-icon v-if="!executing"><VideoPlay /></el-icon>{{ executing ? '执行中…' : '执行(⌘/Ctrl + Enter)' }}
              </button>
            </div>
          </div>
          <div class="sql-editor">
            <textarea v-model="sql" spellcheck="false" placeholder="输入 SQL,支持多条语句(以分号结尾换行分隔)…" @keydown="onEditorKeydown" />
          </div>
          <div v-if="history.length" class="history-row">
            <span class="hist-label">历史:</span>
            <span v-for="(h, i) in history.slice(1, 6)" :key="i" class="hist-chip" @click="useHistory(h)">{{ h.slice(0, 60) }}{{ h.length > 60 ? '…' : '' }}</span>
          </div>
        </div>

        <!-- 结果 -->
        <div v-for="(r, i) in results" :key="i" class="result-card et-card">
          <div class="result-head">
            <code class="r-sql">{{ r.sql }}</code>
            <span class="r-meta">
              <span v-if="r.error" class="r-badge error">错误</span>
              <template v-else>
                <span v-if="r.columns" class="r-badge ok">查询</span>
                <span v-else class="r-badge ok">执行</span>
                <span class="r-time">{{ r.elapsedMs }}ms</span>
                <span v-if="r.columns">{{ r.truncated ? '≥' : '' }}{{ r.rowCount }} 行{{ r.truncated ? '(已截断,前 500 行)' : '' }}</span>
                <span v-else>影响 {{ r.affectedRows }} 行</span>
              </template>
            </span>
          </div>
          <div v-if="r.error" class="r-error">{{ r.error }}</div>
          <div v-else-if="r.columns" class="r-table-wrap">
            <table class="r-table">
              <thead>
                <tr><th v-for="c in r.columns" :key="c">{{ c }}</th></tr>
              </thead>
              <tbody>
                <tr v-for="(row, ri) in r.rows" :key="ri">
                  <td v-for="(cell, ci) in row" :key="ci" :class="{ null: cell === null }">{{ cell === null ? 'NULL' : String(cell) }}</td>
                </tr>
                <tr v-if="!r.rows?.length">
                  <td :colspan="r.columns.length" class="empty-cell">查询无数据</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="r-done">执行成功</div>
        </div>
      </div>
    </div>

    <!-- 连接弹窗 -->
    <el-dialog v-model="dialogOpen" :title="editing ? `编辑连接 · ${editing.name}` : '新建连接'" width="520px" append-to-body>
      <el-form label-width="96px">
        <div class="form-section">SSH 跳板</div>
        <el-form-item label="连接名称" required><el-input v-model="form.name" placeholder="如 内网订单库" /></el-form-item>
        <el-form-item label="SSH 主机" required><el-input v-model="form.sshHost" placeholder="如 10.0.0.5" /></el-form-item>
        <div class="form-row">
          <el-form-item label="SSH 端口"><el-input-number v-model="form.sshPort" :min="1" :max="65535" controls-position="right" style="width: 100%" /></el-form-item>
          <el-form-item label="SSH 用户" required><el-input v-model="form.sshUser" placeholder="如 ops" /></el-form-item>
        </div>
        <el-form-item label="SSH 密码"><el-input v-model="form.sshPassword" type="password" show-password :placeholder="editing?.hasSshPassword ? '留空保持不变' : 'SSH 密码(与密钥二选一)'" /></el-form-item>
        <el-form-item label="SSH 私钥"><el-input v-model="form.sshKeyPath" placeholder="私钥文件绝对路径(与密码二选一)" /></el-form-item>

        <div class="form-section">目标数据库(SSH 服务器可达的内网地址)</div>
        <div class="form-row">
          <el-form-item label="类型" required>
            <el-select v-model="form.dbType" style="width: 100%">
              <el-option label="PostgreSQL" value="postgres" />
              <el-option label="MySQL" value="mysql" />
            </el-select>
          </el-form-item>
          <el-form-item label="主机" required><el-input v-model="form.dbHost" placeholder="如 127.0.0.1" /></el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="端口" required><el-input-number v-model="form.dbPort" :min="1" :max="65535" controls-position="right" style="width: 100%" /></el-form-item>
          <el-form-item label="库名" required><el-input v-model="form.dbName" placeholder="数据库名" /></el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="账号" required><el-input v-model="form.dbUser" placeholder="数据库账号" /></el-form-item>
          <el-form-item label="密码" required><el-input v-model="form.dbPassword" type="password" show-password :placeholder="editing?.hasDbPassword ? '留空保持不变' : '数据库密码'" /></el-form-item>
        </div>
        <div v-if="testResult" class="test-result">{{ testResult }}</div>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button :loading="testing" @click="testConnection">测试连接</el-button>
        <el-button type="primary" @click="saveConnection">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sql-console-page { display: flex; flex-direction: column; gap: 16px; }
.hero-icon { display: flex; align-items: center; justify-content: center; }

.layout-row { display: flex; gap: 16px; align-items: flex-start; }

/* 左:连接列表 */
.conn-panel {
  width: 300px; flex-shrink: 0;
  background: var(--et-card-solid); border: 1px solid var(--et-border);
  border-radius: var(--et-radius-lg); padding: 14px;
  max-height: calc(100vh - 200px); overflow-y: auto; position: sticky; top: 0;
}
.panel-title { font-size: 13px; font-weight: 700; color: var(--et-text-secondary); margin-bottom: 10px; }
.empty { text-align: center; color: var(--et-text-muted); padding: 28px 0; font-size: 12.5px; }
.empty .el-icon { margin-bottom: 8px; }
.conn-item {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 10px 12px; border-radius: 12px; border: 1px solid var(--et-border);
  margin-bottom: 8px; cursor: pointer; transition: border-color 0.15s, background 0.15s;
}
.conn-item:hover { border-color: var(--et-hover-border); }
.conn-item.active { border-color: var(--et-primary); background: var(--et-primary-bg); }
.conn-name { font-size: 13.5px; font-weight: 700; color: var(--et-text); }
.conn-meta { font-size: 11.5px; color: var(--et-text-secondary); margin-top: 2px; font-family: ui-monospace, monospace; }
.conn-ssh { font-size: 11px; color: var(--et-text-muted); margin-top: 2px; font-family: ui-monospace, monospace; }
.conn-ops { display: flex; gap: 4px; flex-shrink: 0; }
.mini-btn {
  width: 26px; height: 26px; border-radius: 8px; border: 1px solid var(--et-border);
  background: var(--et-bg-muted); color: var(--et-text-secondary); cursor: pointer;
  display: flex; align-items: center; justify-content: center; transition: color 0.15s;
}
.mini-btn:hover { color: var(--et-primary); }
.mini-btn.danger:hover { color: #dc2626; }

/* 右:工作区 */
.work-panel { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 16px; }
.sql-card { padding: 16px 18px; }
.sql-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; flex-wrap: wrap; }
.current-name { font-size: 13px; font-weight: 700; color: var(--et-text-secondary); font-family: ui-monospace, monospace; }
.sql-actions { display: flex; gap: 8px; }
.sql-editor textarea {
  width: 100%; min-height: 160px; resize: vertical;
  border: 1px solid var(--et-border); border-radius: 10px;
  background: var(--et-bg-muted); color: var(--et-text);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; line-height: 1.6;
  padding: 12px 14px; outline: none; transition: border-color 0.15s, box-shadow 0.15s;
}
.sql-editor textarea:focus { border-color: var(--et-primary); box-shadow: 0 0 0 3px var(--et-primary-bg); }
.history-row { margin-top: 8px; display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.hist-label { font-size: 11.5px; color: var(--et-text-muted); }
.hist-chip {
  font-size: 11px; font-family: ui-monospace, monospace; color: var(--et-text-secondary);
  background: var(--et-bg-muted); border: 1px solid var(--et-border);
  padding: 2px 8px; border-radius: 6px; cursor: pointer; max-width: 260px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.hist-chip:hover { color: var(--et-primary); border-color: var(--et-primary); }

/* 结果 */
.result-card { padding: 14px 16px; }
.result-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.r-sql { font-size: 12.5px; color: var(--et-text); font-family: ui-monospace, monospace; word-break: break-all; }
.r-meta { display: flex; gap: 10px; align-items: center; font-size: 12px; color: var(--et-text-muted); flex-shrink: 0; }
.r-badge { padding: 2px 8px; border-radius: 20px; font-size: 11px; font-weight: 700; }
.r-badge.ok { color: #047857; background: rgba(4, 120, 87, 0.1); }
.r-badge.error { color: #dc2626; background: rgba(220, 38, 38, 0.1); }
.r-time { font-family: ui-monospace, monospace; }
.r-error {
  margin-top: 10px; padding: 10px 12px; border-radius: 10px;
  background: rgba(220, 38, 38, 0.06); border: 1px solid rgba(220, 38, 38, 0.2);
  color: #dc2626; font-size: 12.5px; font-family: ui-monospace, monospace; white-space: pre-wrap;
}
.r-done { margin-top: 10px; color: #047857; font-size: 12.5px; font-weight: 600; }
.r-table-wrap { margin-top: 10px; overflow-x: auto; border: 1px solid var(--et-border); border-radius: 10px; }
.r-table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.r-table th {
  background: var(--et-bg-muted); color: var(--et-text-secondary); font-weight: 700;
  padding: 8px 12px; text-align: left; white-space: nowrap; border-bottom: 1px solid var(--et-border);
  position: sticky; top: 0;
}
.r-table td {
  padding: 7px 12px; border-bottom: 1px solid var(--et-border);
  color: var(--et-text); font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  max-width: 420px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.r-table tbody tr:nth-child(even) td { background: var(--et-bg-muted); }
.r-table td.null { color: var(--et-text-muted); font-style: italic; }
.r-table .empty-cell { text-align: center; color: var(--et-text-muted); padding: 18px; }

/* 弹窗 */
.form-section {
  font-size: 12px; font-weight: 700; color: var(--et-primary-dark);
  border-left: 3px solid var(--et-primary); padding-left: 8px; margin: 4px 0 12px;
}
.form-row { display: flex; gap: 12px; }
.form-row .el-form-item { flex: 1; }
.test-result { font-size: 12.5px; margin-top: 4px; }

.ops-btn {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 7px 14px; border-radius: 20px; border: 1px solid transparent;
  font-family: inherit; font-size: 13px; font-weight: 600; cursor: pointer; transition: all 0.18s;
  background: var(--et-bg-muted); color: var(--et-text-secondary);
}
.ops-btn:hover { color: var(--et-text); border-color: var(--et-hover-border); }
.ops-btn.primary { background: var(--et-primary-bg); color: #5f6bd8; }
.ops-btn.primary:hover { background: rgba(79, 90, 209, 0.18); }
.ops-btn:disabled { opacity: 0.55; cursor: not-allowed; }
</style>
