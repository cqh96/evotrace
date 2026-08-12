<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Refresh, Setting, Plus, Check, Close, Delete, Edit, Link, Connection, Warning,
  CircleCheck, CircleClose, MagicStick, Tickets
} from '@element-plus/icons-vue'
import {
  traceApi, type GovernanceSummary, type UnlinkedChange, type PendingLink,
  type DanglingKey, type BrokenChainReq, type LinkRule, type TraceSettings
} from '../api'
import { useProjectStore } from '../stores/project'

const projectStore = useProjectStore()
const { current: project } = storeToRefs(projectStore)

const loading = ref(false)
const summary = ref<GovernanceSummary>({
  unlinkedChanges: 0, pendingLinks: 0, danglingKeys: 0,
  brokenChains: { reqWithoutCode: 0, reqWithoutCase: 0, reqWithBlockingBugs: 0, releaseWithoutGate: 0 }
})
const activeTab = ref('unlinked')

// ===== 未关联提交 =====
const unlinked = ref<UnlinkedChange[]>([])
const unlinkedTotal = ref(0)
const unlinkedPage = ref(1)
const unlinkedSize = 20

// ===== 待确认边 =====
const pending = ref<PendingLink[]>([])
const pendingSelection = ref<number[]>([])

// ===== 悬空键 =====
const dangling = ref<DanglingKey[]>([])

// ===== 断链 =====
const brokenType = ref('reqWithoutCode')
const broken = ref<BrokenChainReq[]>([])

const brokenTabs: { value: keyof GovernanceSummary['brokenChains']; label: string; icon: typeof Connection }[] = [
  { value: 'reqWithoutCode', label: '无代码关联', icon: Connection },
  { value: 'reqWithoutCase', label: '无测试用例', icon: Tickets },
  { value: 'reqWithBlockingBugs', label: '存在阻塞缺陷', icon: Warning },
  { value: 'releaseWithoutGate', label: '未过质量门禁', icon: CircleClose }
]

const actor = computed(() => localStorage.getItem('evotrace_user') ?? 'admin')

function shortId(s: string | undefined) {
  return s ? (s.length > 12 ? s.slice(0, 12) + '…' : s) : ''
}
function fmtTime(s: string | undefined) {
  return s ? s.replace('T', ' ').slice(0, 19) : '—'
}
function confCls(c: number) {
  return c >= 90 ? 'high' : c >= 60 ? 'mid' : 'low'
}

async function loadSummary() {
  if (!project.value) return
  try { summary.value = await traceApi.governanceSummary(project.value) } catch {}
}

async function loadUnlinked() {
  if (!project.value) return
  try {
    const res = await traceApi.unlinkedChanges(project.value, { page: unlinkedPage.value, size: unlinkedSize })
    unlinked.value = res.items
    unlinkedTotal.value = res.total
  } catch { ElMessage.error('加载未关联提交失败') }
}

async function loadPending() {
  if (!project.value) return
  try { pending.value = await traceApi.pendingLinks(project.value) } catch { ElMessage.error('加载待确认边失败') }
}

async function loadDangling() {
  if (!project.value) return
  try { const res = await traceApi.danglingKeys(project.value); dangling.value = res.items } catch { ElMessage.error('加载悬空键失败') }
}

async function loadBroken() {
  if (!project.value) return
  try { broken.value = await traceApi.brokenChains(project.value, brokenType.value) } catch { ElMessage.error('加载断链失败') }
}

async function load() {
  if (!project.value) return
  loading.value = true
  await loadSummary()
  switch (activeTab.value) {
    case 'unlinked': await loadUnlinked(); break
    case 'pending': await loadPending(); break
    case 'dangling': await loadDangling(); break
    case 'broken': await loadBroken(); break
  }
  loading.value = false
}

function onTab(tab: string) {
  activeTab.value = tab
  load()
}

async function onPage(p: number) {
  unlinkedPage.value = p
  await loadUnlinked()
}

function onPendingSelection(rows: PendingLink[]) {
  pendingSelection.value = rows.map(r => r.id)
}

// ===== 待确认边操作 =====
async function confirmLink(id: number) {
  if (!project.value) return
  try { await traceApi.confirmLink(project.value, id, actor.value); ElMessage.success('已确认'); load() } catch { ElMessage.error('操作失败') }
}
async function rejectLink(id: number) {
  if (!project.value) return
  try {
    const { value } = await ElMessageBox.prompt('请输入驳回原因', '驳回关联', { inputValue: '误关联' })
    await traceApi.rejectLink(project.value, id, value, actor.value)
    ElMessage.success('已驳回'); load()
  } catch (e: any) { if (e !== 'cancel' && e !== 'close') ElMessage.error('操作失败') }
}
async function batchConfirm() {
  if (!project.value || pendingSelection.value.length === 0) return
  try {
    const res = await traceApi.batchConfirm(project.value, pendingSelection.value, actor.value)
    ElMessage.success(`确认 ${res.confirmed} 条关联`); load()
  } catch { ElMessage.error('操作失败') }
}

// ===== 从悬空键 / 未关联提交创建需求 =====
async function createFromDangling(item: DanglingKey) {
  if (!project.value) return
  try {
    const { value } = await ElMessageBox.prompt('请确认需求标题', '从悬空键创建需求', { inputValue: `需求 ${item.matchedKey}` })
    await traceApi.createRequirementFromDangling(project.value, { matchedKey: item.matchedKey, eventId: item.eventId, title: value, actor: actor.value })
    ElMessage.success('已创建需求并关联'); load()
  } catch (e: any) { if (e !== 'cancel' && e !== 'close') ElMessage.error('操作失败') }
}

// 未关联提交：先输入需求键与标题，再创建需求并回链该提交
async function createFromUnlinked(row: UnlinkedChange) {
  if (!project.value) return
  try {
    const { value } = await ElMessageBox.prompt('请输入需求键（如 REQ-5）', '手工关联提交', { inputValue: 'REQ-' })
    const key = value.trim().toUpperCase()
    if (!key) return ElMessage.warning('需求键不能为空')
    const t = await ElMessageBox.prompt('请输入需求标题', '创建需求', { inputValue: `需求 ${key}` })
    await traceApi.createRequirementFromDangling(project.value, { matchedKey: key, eventId: row.eventId, title: t.value, actor: actor.value })
    ElMessage.success('已创建需求并关联'); load()
  } catch (e: any) { if (e !== 'cancel' && e !== 'close') ElMessage.error('操作失败') }
}

async function ignoreOrphan(row: UnlinkedChange) {
  if (!project.value) return
  try {
    const { value } = await ElMessageBox.prompt('请输入忽略原因', '忽略该提交', { inputValue: '无需关联' })
    await traceApi.ignoreOrphan(project.value, row.eventId, value, actor.value)
    ElMessage.success('已忽略'); load()
  } catch (e: any) { if (e !== 'cancel' && e !== 'close') ElMessage.error('操作失败') }
}

// ===== 设置与规则 =====
const settingsOpen = ref(false)
const settings = ref<TraceSettings>({})
const rules = ref<LinkRule[]>([])
async function loadSettings() {
  if (!project.value) return
  try {
    settings.value = await traceApi.settings(project.value)
    rules.value = await traceApi.rules(project.value)
  } catch { ElMessage.error('加载设置失败') }
}
async function openSettings() {
  await loadSettings()
  settingsOpen.value = true
}
async function saveSettings() {
  if (!project.value) return
  try {
    await traceApi.updateSettings(project.value, {
      reqKeyPrefix: settings.value.reqKeyPrefix,
      autoLinkEnabled: settings.value.autoLinkEnabled,
      hashIssueEnabled: settings.value.hashIssueEnabled,
      aiSuggestEnabled: settings.value.aiSuggestEnabled
    })
    ElMessage.success('设置已保存')
  } catch { ElMessage.error('保存失败') }
}
async function seedDefaults() {
  if (!project.value) return
  try { await traceApi.seedDefaults(project.value); ElMessage.success('已恢复默认规则'); loadSettings() } catch { ElMessage.error('操作失败') }
}

// ===== 规则编辑 =====
const ruleEditOpen = ref(false)
const editingRule = ref<LinkRule | null>(null)
const ruleForm = ref({ name: '', enabled: true, priority: 100, pattern: '', extractGroup: 'reqKey', applyTo: 'COMMIT_MESSAGE', linkType: 'IMPLEMENTS', confidence: 90 })
const applyToOptions = [
  { value: 'COMMIT_MESSAGE', label: '提交消息' },
  { value: 'MR_TITLE', label: 'MR 标题' },
  { value: 'MR_BODY', label: 'MR 描述' },
  { value: 'BRANCH_NAME', label: '分支名' }
]
function openRuleCreate() {
  editingRule.value = null
  ruleForm.value = { name: '', enabled: true, priority: 100, pattern: '', extractGroup: 'reqKey', applyTo: 'COMMIT_MESSAGE', linkType: 'IMPLEMENTS', confidence: 90 }
  ruleEditOpen.value = true
}
function openRuleEdit(rule: LinkRule) {
  editingRule.value = rule
  ruleForm.value = {
    name: rule.name, enabled: rule.enabled, priority: rule.priority,
    pattern: rule.pattern, extractGroup: rule.extractGroup || 'reqKey',
    applyTo: rule.applyTo || 'COMMIT_MESSAGE', linkType: rule.linkType || 'IMPLEMENTS', confidence: rule.confidence
  }
  ruleEditOpen.value = true
}
async function saveRule() {
  if (!project.value) return
  if (!ruleForm.value.name.trim()) return ElMessage.warning('请输入规则名称')
  if (!ruleForm.value.pattern.includes('(?<')) return ElMessage.warning('正则需包含命名组 (?<reqKey>…)')
  try {
    const payload = { ...ruleForm.value }
    if (editingRule.value) await traceApi.updateRule(project.value, editingRule.value.id, payload)
    else await traceApi.createRule(project.value, payload)
    ElMessage.success(editingRule.value ? '规则已更新' : '规则已创建')
    ruleEditOpen.value = false
    loadSettings()
  } catch { ElMessage.error('保存失败') }
}
async function toggleRule(rule: LinkRule) {
  if (!project.value) return
  try { await traceApi.updateRule(project.value, rule.id, { enabled: !rule.enabled }); loadSettings() } catch { ElMessage.error('操作失败') }
}
async function deleteRule(rule: LinkRule) {
  if (!project.value) return
  await ElMessageBox.confirm(`确认删除规则「${rule.name}」？`, '删除确认', { type: 'warning' })
  try { await traceApi.deleteRule(project.value, rule.id); loadSettings() } catch { ElMessage.error('删除失败') }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <div class="left">
        <span class="et-tic"><el-icon><Setting /></el-icon></span>
        <span class="tip">链路治理中心 —— 维护「需求 ↔ 代码」等关联，处理未关联提交、待确认边与断链</span>
      </div>
      <div class="right">
        <button class="ops-btn" @click="openSettings"><el-icon><Setting /></el-icon> 设置与规则</button>
        <button class="ops-btn primary" @click="load"><el-icon><Refresh /></el-icon> 刷新</button>
      </div>
    </div>

    <!-- ===== 概览卡片 ===== -->
    <div class="sum-grid">
      <div class="sum-card" @click="onTab('unlinked')">
        <div class="sum-icon unlinked"><el-icon><Link /></el-icon></div>
        <div class="sum-body"><div class="sum-num">{{ summary.unlinkedChanges }}</div><div class="sum-label">未关联提交</div></div>
      </div>
      <div class="sum-card" @click="onTab('pending')">
        <div class="sum-icon pending"><el-icon><CircleCheck /></el-icon></div>
        <div class="sum-body"><div class="sum-num">{{ summary.pendingLinks }}</div><div class="sum-label">待确认边</div></div>
      </div>
      <div class="sum-card" @click="onTab('dangling')">
        <div class="sum-icon dangling"><el-icon><CircleClose /></el-icon></div>
        <div class="sum-body"><div class="sum-num">{{ summary.danglingKeys }}</div><div class="sum-label">悬空键</div></div>
      </div>
      <div class="sum-card" @click="onTab('broken')">
        <div class="sum-icon broken"><el-icon><Warning /></el-icon></div>
        <div class="sum-body"><div class="sum-num">{{ Object.values(summary.brokenChains).reduce((a, b) => a + b, 0) }}</div><div class="sum-label">断链</div></div>
      </div>
    </div>

    <!-- ===== 明细 Tab ===== -->
    <div class="et-card">
      <el-tabs v-model="activeTab" class="gov-tabs" @tab-change="onTab">
        <!-- 未关联提交 -->
        <el-tab-pane label="未关联提交" name="unlinked">
          <div class="pane-toolbar">
            <span class="pane-tip">未匹配到任何需求的提交，可手工关联或忽略</span>
          </div>
          <el-table :data="unlinked" v-loading="loading" size="default" style="width: 100%">
            <el-table-column label="提交信息" min-width="260">
              <template #default="{ row }">
                <div class="msg-cell">
                  <div class="msg">{{ row.message || row.eventId }}</div>
                  <div class="meta">
                    <span class="sha">{{ row.commitSha || shortId(row.eventId) }}</span>
                    <span v-if="row.author">· {{ row.author }}</span>
                    <span v-if="row.branch">· {{ row.branch }}</span>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="170">
              <template #default="{ row }">{{ fmtTime(row.occurredAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <div class="ops">
                  <button class="ops-btn primary" @click="createFromUnlinked(row)"><el-icon><Plus /></el-icon> 关联</button>
                  <button class="ops-btn danger" @click="ignoreOrphan(row)"><el-icon><Close /></el-icon> 忽略</button>
                </div>
              </template>
            </el-table-column>
          </el-table>
          <div class="pager" v-if="unlinkedTotal > unlinkedSize">
            <el-pagination layout="prev, pager, next" :total="unlinkedTotal" :page-size="unlinkedSize" :current-page="unlinkedPage" @current-change="onPage" />
          </div>
        </el-tab-pane>

        <!-- 待确认边 -->
        <el-tab-pane label="待确认边" name="pending">
          <div class="pane-toolbar">
            <span class="pane-tip">自动建边报告中置信度低于阈值的边，需人工确认或驳回</span>
            <div class="right">
              <button class="ops-btn success" :disabled="pendingSelection.length === 0" @click="batchConfirm"><el-icon><Check /></el-icon> 批量确认 ({{ pendingSelection.length }})</button>
            </div>
          </div>
          <el-table :data="pending" v-loading="loading" size="default" style="width: 100%" @selection-change="onPendingSelection">
            <el-table-column type="selection" width="44" />
            <el-table-column label="来源" min-width="150">
              <template #default="{ row }">
                <div class="msg-cell"><div class="msg">{{ row.fromType }} · {{ shortId(row.fromId) }}</div><div class="meta">{{ row.source }}</div></div>
              </template>
            </el-table-column>
            <el-table-column label="去向" min-width="150">
              <template #default="{ row }">
                <div class="msg-cell"><div class="msg">{{ row.toType }} · {{ shortId(row.toId) }}</div><div class="meta">{{ row.linkType }}</div></div>
              </template>
            </el-table-column>
            <el-table-column label="置信度" width="120">
              <template #default="{ row }">
                <span class="conf" :class="confCls(row.confidence)">{{ row.confidence }}%</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <div class="ops">
                  <button class="ops-btn success" @click="confirmLink(row.id)"><el-icon><Check /></el-icon> 确认</button>
                  <button class="ops-btn danger" @click="rejectLink(row.id)"><el-icon><Close /></el-icon> 驳回</button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 悬空键 -->
        <el-tab-pane label="悬空键" name="dangling">
          <div class="pane-toolbar">
            <span class="pane-tip">提交中引用但系统中不存在的需求键，可一键创建需求</span>
          </div>
          <el-table :data="dangling" v-loading="loading" size="default" style="width: 100%">
            <el-table-column label="需求键" width="160">
              <template #default="{ row }"><span class="ev">{{ row.matchedKey }}</span></template>
            </el-table-column>
            <el-table-column label="提交信息" min-width="280">
              <template #default="{ row }"><div class="msg truncate">{{ row.message }}</div></template>
            </el-table-column>
            <el-table-column label="时间" width="170">
              <template #default="{ row }">{{ fmtTime(row.occurredAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <div class="ops">
                  <button class="ops-btn primary" @click="createFromDangling(row)"><el-icon><Plus /></el-icon> 创建需求</button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 断链 -->
        <el-tab-pane label="断链" name="broken">
          <div class="pane-toolbar">
            <div class="break-tabs">
              <button v-for="t in brokenTabs" :key="t.value" class="break-tab" :class="{ active: brokenType === t.value }" @click="brokenType = t.value; loadBroken()">
                <el-icon><component :is="t.icon" /></el-icon> {{ t.label }} <span class="cnt">{{ summary.brokenChains[t.value] ?? 0 }}</span>
              </button>
            </div>
          </div>
          <el-table :data="broken" v-loading="loading" size="default" style="width: 100%">
            <el-table-column label="需求键" width="160">
              <template #default="{ row }"><span class="ev">{{ row.reqKey || row.version }}</span></template>
            </el-table-column>
            <el-table-column prop="title" label="标题" min-width="260" />
            <el-table-column prop="status" label="状态" width="120" />
            <el-table-column prop="targetVersion" label="目标版本" width="140" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- ===== 设置与规则抽屉 ===== -->
    <el-drawer v-model="settingsOpen" title="链路设置与关联规则" size="520px">
      <div class="drawer-section">
        <h3 class="ds-title">基础设置</h3>
        <el-form label-width="120px">
          <el-form-item label="需求键前缀">
            <el-input v-model="settings.reqKeyPrefix" placeholder="REQ" />
          </el-form-item>
          <el-form-item label="自动建边">
            <el-switch v-model="settings.autoLinkEnabled" active-text="开启" />
          </el-form-item>
          <el-form-item label="Hash 映射">
            <el-switch v-model="settings.hashIssueEnabled" active-text="开启" />
            <span class="ds-hint">支持 #123 形式引用</span>
          </el-form-item>
          <el-form-item label="AI 建议">
            <el-switch v-model="settings.aiSuggestEnabled" active-text="开启" />
          </el-form-item>
        </el-form>
        <button class="ops-btn primary" @click="saveSettings"><el-icon><Check /></el-icon> 保存设置</button>
      </div>

      <div class="drawer-section">
        <div class="ds-head">
          <h3 class="ds-title">关联规则</h3>
          <div class="ds-actions">
            <button class="ops-btn" @click="seedDefaults"><el-icon><MagicStick /></el-icon> 恢复默认</button>
            <button class="ops-btn primary" @click="openRuleCreate"><el-icon><Plus /></el-icon> 新建</button>
          </div>
        </div>
        <el-table :data="rules" size="small" style="width: 100%">
          <el-table-column prop="name" label="名称" min-width="120" />
          <el-table-column label="启用" width="60">
            <template #default="{ row }"><el-switch v-model="row.enabled" size="small" @change="toggleRule(row)" /></template>
          </el-table-column>
          <el-table-column label="置信度" width="70">
            <template #default="{ row }">{{ row.confidence }}%</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <div class="ops">
                <button class="ops-btn primary" @click="openRuleEdit(row)"><el-icon><Edit /></el-icon></button>
                <button class="ops-btn danger" @click="deleteRule(row)"><el-icon><Delete /></el-icon></button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>

    <!-- ===== 规则编辑 ===== -->
    <el-dialog v-model="ruleEditOpen" :title="editingRule ? '编辑规则' : '新建规则'" width="560px">
      <el-form :model="ruleForm" label-width="90px">
        <el-form-item label="规则名称" required><el-input v-model="ruleForm.name" placeholder="如：REQ key" /></el-form-item>
        <el-form-item label="正则 pattern" required>
          <el-input v-model="ruleForm.pattern" class="mono-input" placeholder='(?i)\b(?<reqKey>REQ[-_]?\d+)\b' />
          <span class="ds-hint">需包含命名组 (?&lt;reqKey&gt;…)</span>
        </el-form-item>
        <el-form-item label="应用位置">
          <el-select v-model="ruleForm.applyTo" style="width: 100%">
            <el-option v-for="o in applyToOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联类型">
          <el-input v-model="ruleForm.linkType" placeholder="IMPLEMENTS" />
        </el-form-item>
        <el-form-item label="置信度">
          <el-slider v-model="ruleForm.confidence" :min="0" :max="100" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="ruleForm.priority" :min="1" />
          <span class="ds-hint">越小越先匹配</span>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="ruleForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleEditOpen = false">取消</el-button>
        <el-button type="primary" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.page-toolbar .left { display: flex; align-items: center; gap: 10px; }
.page-toolbar .right { display: flex; align-items: center; gap: 10px; }
.tip { font-size: 13px; color: var(--et-text-secondary); }
.et-tic { color: var(--et-grad-c); display: flex; }
.ops-btn { display: inline-flex; align-items: center; gap: 6px; padding: 8px 14px; border-radius: 20px; border: 1px solid transparent; font-family: inherit; font-size: 13px; font-weight: 600; cursor: pointer; transition: all 0.18s; color: var(--et-text); }
.ops-btn[disabled] { opacity: 0.45; cursor: not-allowed; }
.ops-btn.primary { background: rgba(109, 124, 255, 0.14); color: #a8b4ff; }
.ops-btn.primary:hover:not([disabled]) { background: rgba(109, 124, 255, 0.28); box-shadow: 0 0 12px rgba(109, 124, 255, 0.3); }
.ops-btn.success { background: rgba(52, 211, 153, 0.14); color: #34d399; }
.ops-btn.success:hover:not([disabled]) { background: rgba(52, 211, 153, 0.28); box-shadow: 0 0 12px rgba(52, 211, 153, 0.3); }
.ops-btn.danger { background: rgba(251, 113, 133, 0.14); color: #fb7185; }
.ops-btn.danger:hover:not([disabled]) { background: rgba(251, 113, 133, 0.28); box-shadow: 0 0 12px rgba(251, 113, 133, 0.3); }
.ops { display: flex; gap: 6px; }

.sum-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 16px; }
.sum-card { display: flex; align-items: center; gap: 14px; padding: 18px; border-radius: 16px; background: var(--et-card-solid); border: 1px solid var(--et-border); cursor: pointer; transition: transform 0.18s, border-color 0.18s, box-shadow 0.18s; }
.sum-card:hover { transform: translateY(-2px); border-color: var(--et-hover-border); box-shadow: 0 8px 24px rgba(2, 6, 23, 0.25); }
.sum-icon { width: 44px; height: 44px; border-radius: 13px; display: flex; align-items: center; justify-content: center; font-size: 20px; flex-shrink: 0; }
.sum-icon.unlinked { color: #38e1ff; background: rgba(56, 225, 255, 0.12); }
.sum-icon.pending { color: #fbbf24; background: rgba(251, 191, 36, 0.12); }
.sum-icon.dangling { color: #a78bfa; background: rgba(167, 139, 250, 0.12); }
.sum-icon.broken { color: #fb7185; background: rgba(251, 113, 133, 0.12); }
.sum-num { font-size: 26px; font-weight: 800; line-height: 1.1; }
.sum-label { font-size: 12.5px; color: var(--et-text-muted); margin-top: 2px; }

.gov-tabs :deep(.el-tabs__header) { margin-bottom: 14px; }
.pane-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.pane-toolbar .right { display: flex; gap: 8px; }
.pane-tip { font-size: 12.5px; color: var(--et-text-muted); }
.msg-cell { line-height: 1.4; }
.msg { font-size: 13px; font-weight: 600; }
.msg.truncate { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 420px; }
.meta { font-size: 11.5px; color: var(--et-text-muted); }
.sha { font-family: ui-monospace, monospace; color: var(--et-grad-c); }
.ev { font-family: ui-monospace, monospace; font-size: 11.5px; color: var(--et-grad-c); padding: 3px 9px; border-radius: 6px; background: rgba(56, 225, 255, 0.1); }
.conf { font-family: ui-monospace, monospace; font-size: 12px; font-weight: 700; padding: 3px 10px; border-radius: 20px; }
.conf.high { color: #34d399; background: rgba(52, 211, 153, 0.12); }
.conf.mid { color: #fbbf24; background: rgba(251, 191, 36, 0.12); }
.conf.low { color: #fb7185; background: rgba(251, 113, 133, 0.12); }
.pager { display: flex; justify-content: flex-end; padding: 14px 6px 0; }

.break-tabs { display: flex; gap: 8px; flex-wrap: wrap; }
.break-tab { display: inline-flex; align-items: center; gap: 6px; padding: 7px 14px; border-radius: 20px; border: 1px solid var(--et-border); background: var(--et-bg-muted); color: var(--et-text-secondary); font-size: 12.5px; font-family: inherit; cursor: pointer; transition: all 0.15s; }
.break-tab .cnt { font-weight: 700; }
.break-tab.active { color: #fff; background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-b)); border-color: transparent; box-shadow: 0 4px 12px var(--et-glow); }
.break-tab.active .cnt { color: #0b0f1e; }

.drawer-section { margin-bottom: 26px; }
.drawer-section h3.ds-title { margin: 0 0 14px; font-size: 15px; font-weight: 700; }
.ds-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.ds-head h3.ds-title { margin: 0; }
.ds-actions { display: flex; gap: 8px; }
.ds-hint { font-size: 11px; color: var(--et-text-muted); margin-left: 10px; }
.mono-input :deep(input) { font-family: ui-monospace, monospace; font-size: 12px; }
</style>