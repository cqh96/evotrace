<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { Refresh, Odometer, RefreshLeft, Tickets, Warning, CircleCheck, Connection } from '@element-plus/icons-vue'
import { releaseApi, traceApi, type Release, type ReleaseOverview } from '../api'
import { useProjectStore } from '../stores/project'

const projectStore = useProjectStore()
const { current: project } = storeToRefs(projectStore)

const loading = ref(false)
const releases = ref<Release[]>([])
const selectedId = ref<number | null>(null)
const overview = ref<ReleaseOverview | null>(null)

const emptyStatus = '未评估'

async function loadReleases() {
  if (!project.value) return
  try {
    releases.value = await releaseApi.list(project.value)
    if (!selectedId.value && releases.value.length) {
      selectedId.value = releases.value[0].id ?? null
    }
  } catch { ElMessage.error('加载版本列表失败') }
}

async function loadOverview() {
  if (!project.value || selectedId.value == null) return
  loading.value = true
  try { overview.value = await traceApi.releaseOverview(project.value, selectedId.value) } catch { ElMessage.error('加载版本全景失败') }
  loading.value = false
}

async function selectRelease(id: number) {
  selectedId.value = id
  await loadOverview()
}

async function rebuild() {
  if (!project.value || selectedId.value == null) return
  try {
    const res = await traceApi.rebuildChangeset(project.value, selectedId.value)
    ElMessage.success(`已重建变更集，共 ${res.rebuiltChanges} 条`)
    loadOverview()
  } catch { ElMessage.error('重建失败') }
}

function fmtTime(s: string | undefined) {
  return s ? s.replace('T', ' ').slice(0, 16) : '—'
}
function statusCls(status: string | undefined) {
  return (status || '') === 'PASSED' ? 'pass' : (status || '') === 'FAILED' ? 'fail' : 'warn'
}
function gateLabel(status: string | undefined) {
  if (!status) return emptyStatus
  return status === 'PASSED' ? '通过' : status === 'FAILED' ? '未通过' : status
}

async function load() {
  await loadReleases()
  await loadOverview()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <div class="left">
        <span class="et-tic"><el-icon><Odometer /></el-icon></span>
        <span class="tip">版本全景 —— 关联需求、变更、缺陷与质量门禁，一站式评估版本就绪度</span>
      </div>
      <div class="right">
        <button class="ops-btn" @click="rebuild" :disabled="selectedId == null"><el-icon><RefreshLeft /></el-icon> 重建变更集</button>
        <button class="ops-btn primary" @click="load"><el-icon><Refresh /></el-icon> 刷新</button>
      </div>
    </div>

    <!-- ===== 版本选择 ===== -->
    <div class="ver-bar">
      <span class="ver-label">选择版本</span>
      <div class="ver-list">
        <button v-for="r in releases" :key="r.id" class="ver-chip" :class="{ active: selectedId === r.id }" @click="selectRelease(r.id!)">
          <span class="ver-name">{{ r.version }}</span>
          <span class="ver-meta">{{ r.env || '—' }}</span>
        </button>
      </div>
    </div>

    <template v-if="overview">
      <!-- ===== 概览 ===== -->
      <div class="head-grid">
        <div class="head-card main">
          <div class="hc-title">
            <span class="ver-big">{{ overview.release.version }}</span>
            <span class="gate" :class="statusCls(overview.qualityGate?.status)">
              <el-icon><CircleCheck v-if="overview.qualityGate?.status === 'PASSED'" /><Warning v-else /></el-icon>
              质量门禁：{{ gateLabel(overview.qualityGate?.status) }}
            </span>
          </div>
          <div class="hc-meta">
            <span>基础提交 <code>{{ overview.release.baseCommit || '—' }}</code></span>
            <span>发布时间 {{ fmtTime(overview.release.releasedAt) }}</span>
            <span>状态 {{ overview.release.status || '—' }}</span>
          </div>
        </div>
        <div class="head-card">
          <div class="hc-num">{{ overview.requirements.length }}</div>
          <div class="hc-label">关联需求</div>
        </div>
        <div class="head-card">
          <div class="hc-num">{{ overview.changes.total }}<span class="sub">/{{ overview.changes.linked }} 关联</span></div>
          <div class="hc-label">变更</div>
        </div>
        <div class="head-card">
          <div class="hc-num" :class="{ danger: overview.bugs.openP0P1 > 0 }">{{ overview.bugs.openP0P1 }}</div>
          <div class="hc-label">未关闭 P0/P1</div>
        </div>
        <div class="head-card">
          <div class="hc-num">≈{{ Math.round((overview.completeness.score ?? 0) * 100) }}%</div>
          <div class="hc-label">完整度评分</div>
        </div>
      </div>

      <div class="mid-grid">
        <!-- ===== 需求进度 ===== -->
        <div class="et-card">
          <div class="card-head"><h3>关联需求</h3><span class="count">{{ overview.requirements.length }}</span></div>
          <div class="et-card-body no-padding">
            <el-table :data="overview.requirements" size="small" style="width: 100%">
              <el-table-column label="需求键" width="110">
                <template #default="{ row }"><span class="ev">{{ row.reqKey || '—' }}</span></template>
              </el-table-column>
              <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
              <el-table-column prop="status" label="状态" width="100" />
              <el-table-column label="完整度" width="110">
                <template #default="{ row }">
                  <div class="mini-bar">
                    <span class="mini-fill" :style="{ width: Math.round((row.completenessScore ?? 0) * 100) + '%' }"></span>
                  </div>
                  <span class="mini-pct">{{ Math.round((row.completenessScore ?? 0) * 100) }}%</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <!-- ===== 测试摘要 ===== -->
        <div class="et-card">
          <div class="card-head"><h3>测试摘要</h3><span class="count"><el-icon><Tickets /></el-icon></span></div>
          <div class="test-grid">
            <div class="test-cell"><div class="t-num">{{ overview.testSummary.planCount }}</div><div class="t-label">执行用例数</div></div>
            <div class="test-cell"><div class="t-num pass">{{ Math.round((overview.testSummary.passRate ?? 0) * 100) }}%</div><div class="t-label">通过率</div></div>
            <div class="test-cell"><div class="t-num danger">{{ overview.testSummary.failed }}</div><div class="t-label">失败</div></div>
          </div>
        </div>
      </div>

      <!-- ===== 变更集 ===== -->
      <div class="et-card">
        <div class="card-head">
          <h3>变更集</h3>
          <div class="chips">
            <span class="chip ok">关联 {{ overview.changes.linked }}</span>
            <span class="chip warn">未关联 {{ overview.changes.unlinked }}</span>
            <button class="ops-btn" @click="rebuild"><el-icon><RefreshLeft /></el-icon> 重建</button>
          </div>
        </div>
        <div class="et-card-body no-padding">
          <el-table :data="overview.changes.items" size="small" style="width: 100%">
            <el-table-column label="提交信息" min-width="260">
              <template #default="{ row }">
                <div class="msg-cell">
                  <div class="msg">{{ row.message || row.eventId }}</div>
                  <div class="meta"><span class="sha">{{ row.eventId }}</span></div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="关联需求" min-width="160">
              <template #default="{ row }">
                <span v-if="row.reqKeys" class="ev">{{ row.reqKeys }}</span>
                <span v-else class="muted">未关联</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </template>

    <div v-else-if="!loading" class="empty-state">
      <el-icon :size="40"><Connection /></el-icon>
      <p>暂无版本，或当前项目未配置发布</p>
    </div>
  </div>
</template>

<style scoped>
.page-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.page-toolbar .left { display: flex; align-items: center; gap: 10px; }
.page-toolbar .right { display: flex; align-items: center; gap: 10px; }
.tip { font-size: 13px; color: var(--et-text-secondary); }
.et-tic { color: #0e7490; display: flex; }
.ops-btn { display: inline-flex; align-items: center; gap: 6px; padding: 8px 14px; border-radius: 20px; border: 1px solid transparent; font-family: inherit; font-size: 13px; font-weight: 600; cursor: pointer; transition: all 0.18s; color: var(--et-text); }
.ops-btn[disabled] { opacity: 0.45; cursor: not-allowed; }
.ops-btn.primary { background: var(--et-primary-bg); color: #5f6bd8; }
.ops-btn.primary:hover:not([disabled]) { background: rgba(79, 90, 209, 0.18); }
.ops-btn:hover:not([disabled]) { background: var(--et-sidebar-hover); }

.ver-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.ver-label { font-size: 13px; font-weight: 700; color: var(--et-text-secondary); }
.ver-list { display: flex; gap: 8px; flex-wrap: wrap; }
.ver-chip { display: flex; flex-direction: column; gap: 2px; padding: 8px 14px; border-radius: 12px; border: 1px solid var(--et-border); background: var(--et-bg-muted); cursor: pointer; transition: all 0.15s; }
.ver-chip .ver-name { font-size: 13px; font-weight: 700; }
.ver-chip .ver-meta { font-size: 10.5px; color: var(--et-text-muted); }
.ver-chip:hover { border-color: var(--et-hover-border); }
.ver-chip.active { color: #fff; background: var(--et-primary); border-color: transparent; }
.ver-chip.active .ver-meta { color: rgba(255, 255, 255, 0.75); }

.head-grid { display: grid; grid-template-columns: 2fr 1fr 1fr 1fr 1fr; gap: 14px; margin-bottom: 16px; }
.head-card { padding: 18px; border-radius: 16px; background: var(--et-card-solid); border: 1px solid var(--et-border); }
.head-card.main { grid-column: auto; display: flex; flex-direction: column; justify-content: center; gap: 10px; }
.hc-title { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.ver-big { font-size: 20px; font-weight: 800; }
.gate { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 700; padding: 4px 10px; border-radius: 20px; }
.gate.pass { color: #059669; background: rgba(5, 150, 105, 0.1); }
.gate.fail { color: #dc2626; background: rgba(220, 38, 38, 0.1); }
.gate.warn { color: #b45309; background: rgba(180, 83, 9, 0.1); }
.hc-meta { display: flex; gap: 14px; flex-wrap: wrap; font-size: 12px; color: var(--et-text-muted); }
.hc-meta code { font-family: ui-monospace, monospace; color: #0e7490; }
.hc-num { font-size: 26px; font-weight: 800; }
.hc-num .sub { font-size: 12px; font-weight: 600; color: var(--et-text-muted); }
.hc-num.danger { color: #dc2626; }
.hc-label { font-size: 12px; color: var(--et-text-muted); margin-top: 2px; }

.mid-grid { display: grid; grid-template-columns: 1.6fr 1fr; gap: 14px; margin-bottom: 16px; }
.card-head { display: flex; align-items: center; justify-content: space-between; padding: 14px 18px; border-bottom: 1px solid var(--et-border); }
.card-head h3 { margin: 0; font-size: 15px; font-weight: 700; }
.card-head .count { font-size: 12px; color: var(--et-text-muted); display: inline-flex; align-items: center; gap: 5px; }
.card-head .chips { display: flex; align-items: center; gap: 8px; }
.chip { font-size: 11.5px; font-weight: 700; padding: 3px 10px; border-radius: 20px; }
.chip.ok { color: #059669; background: rgba(5, 150, 105, 0.1); }
.chip.warn { color: #b45309; background: rgba(180, 83, 9, 0.1); }

.mini-bar { display: inline-block; width: 60px; height: 6px; border-radius: 4px; background: var(--et-bg-muted); overflow: hidden; vertical-align: middle; }
.mini-fill { display: block; height: 100%; background: var(--et-primary); }
.mini-pct { font-size: 11px; color: var(--et-text-muted); margin-left: 6px; }

.test-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 10px; padding: 16px 18px; }
.test-cell { text-align: center; }
.t-num { font-size: 22px; font-weight: 800; }
.t-num.pass { color: #059669; }
.t-num.danger { color: #dc2626; }
.t-label { font-size: 11.5px; color: var(--et-text-muted); margin-top: 2px; }

.ev { font-family: ui-monospace, monospace; font-size: 11.5px; color: #0e7490; padding: 2px 8px; border-radius: 6px; background: rgba(14, 116, 144, 0.1); }
.muted { color: var(--et-text-muted); font-size: 12px; }
.msg-cell { line-height: 1.4; }
.msg { font-size: 13px; font-weight: 600; }
.meta { font-size: 11px; color: var(--et-text-muted); }
.sha { font-family: ui-monospace, monospace; color: #0e7490; }
.empty-state { text-align: center; padding: 60px 0; color: var(--et-text-muted); }
.empty-state p { margin-top: 12px; }
</style>