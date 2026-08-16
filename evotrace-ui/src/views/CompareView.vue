<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { releaseApi, type CompareReport } from '../api'
import { useProjectStore } from '../stores/project'

const projectStore = useProjectStore()
const { current: project } = storeToRefs(projectStore)
const from = ref('v2.3.1')
const to = ref('v2.5.0')
const versions = ref<string[]>(['v2.3.1', 'v2.4.0', 'v2.4.9', 'v2.5.0'])
const activeTab = ref('apis')
const compared = ref(false)
const loading = ref(false)
// 接口不可用/无数据时页面展示内置演示报告，此标记用于提示用户
const usingDemo = ref(true)

const noteLoading = ref(false)
const noteVisible = ref(false)
const noteContent = ref('')
const noteModel = ref('')

const report = ref<CompareReport>({
  fromVersion: 'v2.3.1',
  toVersion: 'v2.5.0',
  apis: [
    { identityKey: 'POST /order/timeout/close', changeFlag: 'ADDED', after: 'OrderCloseController#closeByRule' },
    { identityKey: 'GET /order/{id}', changeFlag: 'MODIFIED', before: '返回 12 个字段', after: '返回 14 个字段（+channel, +timeoutRule）' },
    { identityKey: 'POST /order/manualClose', changeFlag: 'REMOVED', before: 'OrderCloseController#manualClose' }
  ],
  dependencies: [
    { identityKey: 'org.redisson:redisson', changeFlag: 'ADDED', after: '3.37.0' },
    { identityKey: 'mysql:mysql-connector-j', changeFlag: 'MODIFIED', before: '8.3.0', after: '9.1.0' }
  ],
  configs: [
    { identityKey: 'order.timeout.channel.*', changeFlag: 'ADDED', after: '渠道级超时配置组（3 keys，值已脱敏）' },
    { identityKey: 'order.timeout.fixed-minutes', changeFlag: 'REMOVED' }
  ],
  schemas: [
    { identityKey: 'order_timeout_rule.channel', changeFlag: 'ADDED', after: 'varchar(32) + uk_rule_channel' }
  ],
  stats: { filesChanged: 47, addLines: 1820, delLines: 640, commits: 23 },
  changes: []
})

const flagType = (f: string) => ({ ADDED: 'success', REMOVED: 'danger', MODIFIED: 'warning', UNCHANGED: 'info' })[f] as never
const flagLabel = (f: string) => ({ ADDED: '新增', REMOVED: '删除', MODIFIED: '修改', UNCHANGED: '不变' })[f]

const versionOptions = computed(() => versions.value)

async function loadVersions() {
  try {
    const releases = await releaseApi.list(project.value)
    if (releases?.length) {
      versions.value = releases.map(r => r.version)
      from.value = versions.value[Math.min(1, versions.value.length - 1)]
      to.value = versions.value[0]
      usingDemo.value = false
    }
  } catch {
    // 服务端未就绪时保留演示数据（带演示数据徽标）
  }
}

async function runCompare() {
  loading.value = true
  try {
    report.value = await releaseApi.compare(project.value, from.value, to.value)
    compared.value = true
    usingDemo.value = false
  } catch {
    // 接口失败时保留当前展示（演示数据仍带徽标）
  } finally {
    loading.value = false
  }
}

async function generateReleaseNotes() {
  noteLoading.value = true
  try {
    const res = await releaseApi.generateReleaseNotes(project.value, from.value, to.value)
    noteContent.value = res.content
    noteModel.value = res.model
    noteVisible.value = true
  } catch {
    // 错误提示由请求拦截器统一弹出
  } finally {
    noteLoading.value = false
  }
}

async function copyNote() {
  try {
    await navigator.clipboard.writeText(noteContent.value)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败，请手动选择复制')
  }
}

// 全局项目切换时自动刷新
watch(project, () => loadVersions())

onMounted(loadVersions)

/* ---------- 以下为展示层新增（不影响原有逻辑） ---------- */
// 交换基线 / 目标版本
function swapVersions() {
  const t = from.value
  from.value = to.value
  to.value = t
}

// 统计各变更标记数量（新增 / 修改 / 删除）
const diffCounts = computed(() => {
  const c: Record<string, number> = { ADDED: 0, MODIFIED: 0, REMOVED: 0 }
  for (const k of ['apis', 'dependencies', 'configs', 'schemas'] as const) {
    for (const it of report.value[k] as unknown[]) {
      const f = (it as { changeFlag?: string }).changeFlag
      if (f && f in c) c[f]++
    }
  }
  return c
})

// 全部清单项总数
const totalItems = computed(() => {
  let n = 0
  for (const k of ['apis', 'dependencies', 'configs', 'schemas'] as const) n += ((report.value[k] as unknown[])?.length ?? 0)
  return n
})

const tabCount = (k: string) => (report.value[k as 'apis'] as unknown[])?.length ?? 0

// 变更行高亮类名（按 changeFlag 染色）
const rowClass = ({ row }: { row: unknown }) =>
  'diff-' + String((row as { changeFlag?: string }).changeFlag ?? 'UNCHANGED').toLowerCase()

// Tab 图标（图标为全局注册，无需导入）
const tabIcons: Record<string, string> = { apis: 'Connection', dependencies: 'Box', configs: 'Setting', schemas: 'DataLine' }
</script>

<template>
  <div class="compare-page">
    <!-- ======== 版本选择区：玻璃卡片 ======== -->
    <section class="et-card picker-card rise">
      <div class="picker-row">
        <div class="pick-block">
          <span class="pick-label">项目</span>
          <el-input v-model="project" class="pick-input" placeholder="项目 Key" />
        </div>
        <div class="pick-block">
          <span class="pick-label">基线版本</span>
          <el-select v-model="from" class="pick-input" filterable>
            <el-option v-for="v in versionOptions" :key="v" :label="v" :value="v" />
          </el-select>
        </div>
        <button class="swap-btn" title="交换两个版本" :disabled="loading" @click="swapVersions">
          <el-icon :size="15"><Switch /></el-icon>
        </button>
        <div class="pick-block">
          <span class="pick-label">目标版本</span>
          <el-select v-model="to" class="pick-input" filterable>
            <el-option v-for="v in versionOptions" :key="v" :label="v" :value="v" />
          </el-select>
        </div>
        <div class="picker-actions">
          <el-button type="primary" class="run-btn" :loading="loading" :disabled="from === to" @click="runCompare">
            <el-icon v-if="!loading" :size="15"><DataAnalysis /></el-icon>生成对比报告
          </el-button>
          <el-button size="small" :loading="loading" @click="loadVersions">
            <el-icon v-if="!loading" :size="13"><Refresh /></el-icon>刷新
          </el-button>
        </div>
      </div>
    </section>

    <!-- ======== 统计条 ======== -->
    <section class="et-card stats-card rise" style="--d: .07s">
      <div class="stats-top">
        <div class="version-pair">
          <span class="v-chip v-from">{{ report.fromVersion }}</span>
          <span class="v-arrow">→</span>
          <span class="v-chip v-to">{{ report.toVersion }}</span>
          <span v-if="usingDemo" class="demo-badge" title="服务端未就绪时展示的内置演示数据">演示数据</span>
        </div>
        <el-button size="small" class="note-btn" :loading="noteLoading" @click="generateReleaseNotes">
          <el-icon v-if="!noteLoading" :size="13"><MagicStick /></el-icon>AI 生成发布说明
        </el-button>
      </div>
      <div class="stats-row">
        <span class="chip chip-glass"><el-icon :size="13"><Document /></el-icon>{{ report.stats.commits }} 个提交</span>
        <span class="chip chip-glass"><el-icon :size="13"><FolderOpened /></el-icon>{{ report.stats.filesChanged }} 个文件</span>
        <span class="chip chip-lines">+{{ report.stats.addLines }} 行</span>
        <span class="chip chip-lines del">-{{ report.stats.delLines }} 行</span>
        <span class="chip chip-add">新增 {{ diffCounts.ADDED }}</span>
        <span class="chip chip-mod">修改 {{ diffCounts.MODIFIED }}</span>
        <span class="chip chip-del">删除 {{ diffCounts.REMOVED }}</span>
      </div>
    </section>

    <!-- ======== 对比详情 ======== -->
    <section class="et-card diff-card rise" style="--d: .13s">
      <div class="et-card-head">
        <span class="et-tic"><el-icon :size="15"><Connection /></el-icon></span>
        <div>
          <div class="et-card-title">对比详情</div>
          <div class="et-card-sub">API / 依赖 / 配置 / DDL 变更清单</div>
        </div>
        <div class="right">
          <span class="total-badge"><i class="total-dot"></i>共 {{ totalItems }} 项变更</span>
        </div>
      </div>
      <div class="et-card-body no-padding">
        <el-tabs v-model="activeTab" class="compare-tabs">
          <el-tab-pane
            v-for="t in [{k:'apis',l:'接口'},{k:'dependencies',l:'依赖'},{k:'configs',l:'配置'},{k:'schemas',l:'DDL'}]"
            :key="t.k" :name="t.k"
          >
            <template #label>
              <span class="tab-label">
                <el-icon :size="13"><component :is="tabIcons[t.k]" /></el-icon>{{ t.l }}
                <span class="tab-count">{{ tabCount(t.k) }}</span>
              </span>
            </template>
            <div class="tab-content">
              <el-table :data="report[t.k as 'apis']" :row-class-name="rowClass">
                <el-table-column prop="identityKey" label="清单项" min-width="280" show-overflow-tooltip />
                <el-table-column label="变化" width="110">
                  <template #default="{ row }">
                    <span class="flag-mark" :class="'flag-' + String(row.changeFlag).toLowerCase()">
                      <i></i>{{ flagLabel(row.changeFlag) }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column prop="before" label="变更前" min-width="200" show-overflow-tooltip><template #default="{ row }">{{ row.before ?? '—' }}</template></el-table-column>
                <el-table-column prop="after" label="变更后" min-width="200" show-overflow-tooltip><template #default="{ row }">{{ row.after ?? '—' }}</template></el-table-column>
              </el-table>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </section>

    <!-- ======== 变更明细 ======== -->
    <section v-if="compared && report.changes?.length" class="et-card rise" style="--d: .19s">
      <div class="et-card-head">
        <span class="et-tic"><el-icon :size="15"><List /></el-icon></span>
        <div>
          <div class="et-card-title">变更明细（{{ report.changes.length }}）</div>
          <div class="et-card-sub">提交级变更记录与 AI 摘要</div>
        </div>
      </div>
      <div class="et-card-body no-padding">
        <el-table :data="report.changes">
          <el-table-column prop="occurredAt" label="时间" width="200" />
          <el-table-column label="类型" width="120">
            <template #default="{ row }"><span class="type-text">{{ row.type }}</span></template>
          </el-table-column>
          <el-table-column prop="sha" label="提交" width="110" />
          <el-table-column prop="author" label="作者" width="110" />
          <el-table-column prop="summary" label="AI 摘要" min-width="300" />
        </el-table>
      </div>
    </section>

    <!-- ======== AI 发布说明 ======== -->
    <el-dialog v-model="noteVisible" title="AI 发布说明" width="680px" top="8vh">
      <div class="note-meta" v-if="noteModel">生成模型：{{ noteModel === 'template' ? '模板降级（未配置 AI）' : noteModel }}</div>
      <pre class="note-content">{{ noteContent }}</pre>
      <template #footer>
        <el-button @click="noteVisible = false">关闭</el-button>
        <el-button type="primary" @click="copyNote">复制发布说明</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* ======== 版本选择卡片 ======== */
.picker-card {
  padding: 18px 22px;
}

.picker-row {
  display: flex;
  align-items: flex-end;
  gap: 14px;
  flex-wrap: wrap;
}

.pick-block {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.pick-label {
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.8px;
  text-transform: uppercase;
  color: var(--et-text-muted);
}

.pick-input {
  width: 150px;
}

.picker-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
}

.run-btn {
  padding: 12px 20px;
  border-radius: 12px;
}

.swap-btn {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  color: var(--et-text-secondary);
  cursor: pointer;
  font-family: inherit;
  transition: color 0.18s, border-color 0.18s, background 0.18s, box-shadow 0.18s, transform 0.35s;
}

.swap-btn:hover:not(:disabled) {
  color: #fff;
  border-color: var(--et-primary);
  background: rgba(79, 90, 209, 0.14);
  box-shadow: 0 0 0 3px rgba(79, 90, 209, 0.12);
  transform: rotate(180deg);
}

.swap-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* ======== 统计条 ======== */
.stats-card {
  padding: 18px 22px;
}

.stats-top {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.version-pair {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.v-chip {
  padding: 6px 15px;
  border-radius: 10px;
  font-size: 13.5px;
  font-weight: 800;
  letter-spacing: 0.3px;
  font-variant-numeric: tabular-nums;
}

.v-from {
  color: #fff;
  background: var(--et-primary);
  box-shadow: var(--et-shadow-sm);
}

.v-to {
  color: #fff;
  background: #0891b2;
  box-shadow: var(--et-shadow-sm);
}

.v-arrow {
  font-size: 17px;
  font-weight: 800;
}

.demo-badge {
  font-size: 10.5px;
  font-weight: 700;
  padding: 4px 11px;
  border-radius: 20px;
  color: var(--et-warn);
  background: rgba(180, 83, 9, 0.12);
  border: 1px solid rgba(180, 83, 9, 0.3);
}

.note-btn {
  margin-left: auto;
}

.stats-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 16px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  font-weight: 700;
  padding: 5px 13px;
  border-radius: 20px;
  font-variant-numeric: tabular-nums;
}

.chip-glass {
  color: var(--et-text-secondary);
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
}

.chip-glass .el-icon {
  color: #0e7490;
}

.chip-lines {
  color: var(--et-ok);
  background: rgba(5, 150, 105, 0.12);
}

.chip-lines.del {
  color: var(--et-danger);
  background: rgba(220, 38, 38, 0.12);
}

.chip-add {
  color: #fff;
  background: #059669;
  box-shadow: var(--et-shadow-sm);
}

.chip-mod {
  color: #fff;
  background: #d97706;
  box-shadow: var(--et-shadow-sm);
}

.chip-del {
  color: #fff;
  background: #dc2626;
  box-shadow: var(--et-shadow-sm);
}

/* ======== 对比详情 ======== */
.diff-card :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 22px;
}

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.tab-count {
  font-size: 10px;
  font-weight: 700;
  padding: 1px 7px;
  border-radius: 20px;
  color: var(--et-primary-light);
  background: rgba(79, 90, 209, 0.13);
}

.tab-content {
  padding: 0 22px 22px;
}

.total-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--et-text-secondary);
}

.total-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--et-primary);
}

/* 变化标记 */
.flag-mark {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-weight: 700;
  padding: 3px 11px;
  border-radius: 20px;
}

.flag-mark i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.flag-added { color: var(--et-ok); background: rgba(5, 150, 105, 0.12); }
.flag-added i { background: var(--et-ok); }
.flag-removed { color: var(--et-danger); background: rgba(220, 38, 38, 0.12); }
.flag-removed i { background: var(--et-danger); }
.flag-modified { color: var(--et-warn); background: rgba(180, 83, 9, 0.13); }
.flag-modified i { background: var(--et-warn); }
.flag-unchanged { color: var(--et-text-muted); background: var(--et-bg-muted); }
.flag-unchanged i { background: var(--et-text-muted); }

/* 行级高亮：新增 / 删除 / 修改 */
.diff-card :deep(.el-table__body tr.diff-added > td.el-table__cell) { background: rgba(5, 150, 105, 0.055); }
.diff-card :deep(.el-table__body tr.diff-added:hover > td.el-table__cell) { background: rgba(5, 150, 105, 0.09); }
.diff-card :deep(.el-table__body tr.diff-added > td.el-table__cell:first-child) { box-shadow: inset 3px 0 0 rgba(5, 150, 105, 0.65); }

.diff-card :deep(.el-table__body tr.diff-removed > td.el-table__cell) { background: rgba(220, 38, 38, 0.05); }
.diff-card :deep(.el-table__body tr.diff-removed:hover > td.el-table__cell) { background: rgba(220, 38, 38, 0.085); }
.diff-card :deep(.el-table__body tr.diff-removed > td.el-table__cell:first-child) { box-shadow: inset 3px 0 0 rgba(220, 38, 38, 0.6); }

.diff-card :deep(.el-table__body tr.diff-modified > td.el-table__cell) { background: rgba(180, 83, 9, 0.045); }
.diff-card :deep(.el-table__body tr.diff-modified:hover > td.el-table__cell) { background: rgba(180, 83, 9, 0.08); }
.diff-card :deep(.el-table__body tr.diff-modified > td.el-table__cell:first-child) { box-shadow: inset 3px 0 0 rgba(180, 83, 9, 0.55); }

/* 变更明细 */
.type-text {
  color: var(--et-text-secondary);
  font-weight: 600;
  font-size: 12.5px;
}

/* 发布说明弹窗 */
.note-meta {
  color: var(--et-text-secondary);
  font-size: 12px;
  margin-bottom: 10px;
}

.note-content {
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  border-radius: 12px;
  padding: 14px 16px;
  max-height: 55vh;
  overflow: auto;
  white-space: pre-wrap;
  font-size: 13px;
  line-height: 1.7;
  font-family: inherit;
  color: var(--et-text);
}
</style>
