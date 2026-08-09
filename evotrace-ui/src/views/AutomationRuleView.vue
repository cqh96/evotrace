<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Delete, Edit, MagicStick, VideoPlay } from '@element-plus/icons-vue'
import { automationRuleApi, type AutomationRule } from '../api'
import { useProjectStore } from '../stores/project'

const projectStore = useProjectStore()
const { current: project } = storeToRefs(projectStore)

const loading = ref(false)
const rules = ref<AutomationRule[]>([])

const triggerOptions = [
  { value: 'CHANGE_AI_SUMMARY', label: '代码提交（AI 摘要）' },
  { value: 'CHANGE_CODE_REVIEW', label: '代码审查' },
  { value: 'BUG_CREATED', label: '缺陷创建' },
  { value: 'REQUIREMENT_DONE', label: '需求完成' },
  { value: 'RELEASE_RELEASED', label: '版本发布' }
]
const actionOptions = [
  { value: 'NOTIFY', label: '发送通知', desc: '通知相关角色' },
  { value: 'CREATE_BUG', label: '自动创建缺陷', desc: '按配置生成缺陷' },
  { value: 'AUTO_ASSIGN', label: '自动分配', desc: '指派给负责人' },
  { value: 'AI_ANALYZE', label: 'AI 分析', desc: '触发 AI 深度分析' }
]
const actionLabels: Record<string, string> = {
  NOTIFY: '发送通知', CREATE_BUG: '自动创建缺陷', AUTO_ASSIGN: '自动分配', AI_ANALYZE: 'AI 分析'
}

async function load() {
  if (!project.value) return
  loading.value = true
  try { rules.value = await automationRuleApi.list(project.value) } catch { ElMessage.error('加载规则失败') }
  loading.value = false
}

// ===== 新增/编辑 =====
const editOpen = ref(false)
const editing = ref<AutomationRule | null>(null)
const form = ref({
  name: '', triggerEvent: 'CHANGE_AI_SUMMARY', action: 'NOTIFY', enabled: true,
  conditionJson: '{}', configJson: '{"targetRole":"ALL","content":""}'
})
function openCreate() {
  editing.value = null
  form.value = { name: '', triggerEvent: 'CHANGE_AI_SUMMARY', action: 'NOTIFY', enabled: true, conditionJson: '{}', configJson: '{"targetRole":"ALL","content":""}' }
  editOpen.value = true
}
function openEdit(rule: AutomationRule) {
  editing.value = rule
  form.value = {
    name: rule.name,
    triggerEvent: rule.triggerEvent,
    action: rule.action,
    enabled: rule.enabled,
    conditionJson: JSON.stringify(rule.condition ?? {}, null, 2),
    configJson: JSON.stringify(rule.config ?? {}, null, 2)
  }
  editOpen.value = true
}
function parseJson(s: string): Record<string, any> {
  try { return JSON.parse(s) } catch { throw new Error('JSON 格式错误') }
}
async function save() {
  if (!form.value.name.trim()) return ElMessage.warning('请输入规则名称')
  let condition: Record<string, any>, config: Record<string, any>
  try {
    condition = parseJson(form.value.conditionJson)
    config = parseJson(form.value.configJson)
  } catch (e: any) {
    return ElMessage.error(e.message)
  }
  try {
    await automationRuleApi.upsert(project.value!, {
      id: editing.value?.id,
      name: form.value.name,
      triggerEvent: form.value.triggerEvent,
      action: form.value.action,
      enabled: form.value.enabled,
      condition,
      config
    })
    ElMessage.success(editing.value ? '规则已更新' : '规则已创建')
    editOpen.value = false
    load()
  } catch {
    ElMessage.error('保存失败')
  }
}

async function toggle(rule: AutomationRule) {
  try {
    await automationRuleApi.upsert(project.value!, {
      id: rule.id, name: rule.name, triggerEvent: rule.triggerEvent,
      action: rule.action, enabled: !rule.enabled, condition: rule.condition, config: rule.config
    })
    load()
  } catch { ElMessage.error('操作失败') }
}

async function remove(rule: AutomationRule) {
  await ElMessageBox.confirm(`确认删除规则「${rule.name}」？`, '删除确认', { type: 'warning' })
  try { await automationRuleApi.delete(project.value!, rule.id); load() } catch { ElMessage.error('删除失败') }
}

// ===== 手动触发（冒烟） =====
const smokeOpen = ref(false)
const smoke = ref({ triggerEvent: 'CHANGE_AI_SUMMARY', payload: '{"eventId":"demo","branch":"main","commitSha":"abc123","author":"admin","severity":"P2"}' })
const smokeResult = ref<{ matched: number; executed: number; totalRules: number } | null>(null)
async function runSmoke() {
  let payload: Record<string, any>
  try { payload = JSON.parse(smoke.value.payload) } catch { return ElMessage.error('payload JSON 格式错误') }
  try {
    smokeResult.value = await automationRuleApi.trigger(project.value!, smoke.value.triggerEvent, payload)
  } catch { ElMessage.error('触发失败') }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <div class="left">
        <span class="et-tic"><el-icon><MagicStick /></el-icon></span>
        <span class="tip">基于事件自动执行动作 —— 变更触发 AI 摘要、风险告警、自动建缺陷</span>
      </div>
      <div class="right">
        <button class="ops-btn" @click="smokeOpen = true"><el-icon><VideoPlay /></el-icon> 手动触发测试</button>
        <button class="ops-btn primary" @click="openCreate"><el-icon><Plus /></el-icon> 新建规则</button>
        <button class="ops-btn" @click="load"><el-icon><Refresh /></el-icon> 刷新</button>
      </div>
    </div>

    <div class="et-card">
      <div class="et-card-body no-padding">
        <el-table :data="rules" v-loading="loading" size="default" style="width: 100%">
          <el-table-column prop="name" label="规则名称" min-width="180" />
          <el-table-column label="触发事件" width="180">
            <template #default="{ row }">
              <span class="ev">{{ row.triggerEvent }}</span>
            </template>
          </el-table-column>
          <el-table-column label="动作" width="140">
            <template #default="{ row }">
              <span class="action" :class="row.action.toLowerCase()">{{ actionLabels[row.action] || row.action }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-switch v-model="row.enabled" @change="toggle(row)" />
            </template>
          </el-table-column>
          <el-table-column label="已执行" width="90">
            <template #default="{ row }">{{ row.runCount ?? 0 }} 次</template>
          </el-table-column>
          <el-table-column label="最近执行" width="160">
            <template #default="{ row }">{{ row.lastRunAt || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <div class="ops">
                <button class="ops-btn primary" @click="openEdit(row)"><el-icon><Edit /></el-icon> 编辑</button>
                <button class="ops-btn danger" @click="remove(row)"><el-icon><Delete /></el-icon></button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- ===== 新建/编辑 ===== -->
    <el-dialog v-model="editOpen" :title="editing ? '编辑规则' : '新建规则'" width="560px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="规则名称" required><el-input v-model="form.name" placeholder="如：主干提交自动告警" /></el-form-item>
        <el-form-item label="触发事件">
          <el-select v-model="form.triggerEvent" style="width: 100%">
            <el-option v-for="o in triggerOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="执行动作">
          <el-select v-model="form.action" style="width: 100%">
            <el-option v-for="o in actionOptions" :key="o.value" :label="o.label" :value="o.value">
              <div class="opt"><div>{{ o.label }}</div><div class="opt-desc">{{ o.desc }}</div></div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
        <el-form-item label="条件 JSON">
          <el-input v-model="form.conditionJson" type="textarea" :rows="3" class="mono-input" placeholder='{"severity":"P0","branch":"main"}' />
        </el-form-item>
        <el-form-item label="动作配置 JSON">
          <el-input v-model="form.configJson" type="textarea" :rows="4" class="mono-input" placeholder='{"targetRole":"ALL","content":"..."} 或 {"title":"...","severity":"P2"} 或 {"assignee":"admin"}' />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editOpen = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- ===== 手动触发 ===== -->
    <el-dialog v-model="smokeOpen" title="手动触发测试（冒烟）" width="520px">
      <el-form label-width="90px">
        <el-form-item label="触发事件">
          <el-select v-model="smoke.triggerEvent" style="width: 100%">
            <el-option v-for="o in triggerOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="Payload JSON">
          <el-input v-model="smoke.payload" type="textarea" :rows="5" class="mono-input" />
        </el-form-item>
      </el-form>
      <div v-if="smokeResult" class="smoke-result">
        命中 {{ smokeResult.matched }} / {{ smokeResult.totalRules }} 条规则，成功执行 {{ smokeResult.executed }} 条
      </div>
      <template #footer>
        <el-button @click="smokeOpen = false">关闭</el-button>
        <el-button type="primary" @click="runSmoke">触发</el-button>
      </template>
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
.ev {
  font-family: ui-monospace, monospace; font-size: 11.5px; color: var(--et-grad-c);
  padding: 3px 9px; border-radius: 6px; background: rgba(56, 225, 255, 0.1);
}
.action { font-size: 12px; font-weight: 700; padding: 3px 10px; border-radius: 20px; }
.action.notify { color: #38e1ff; background: rgba(56, 225, 255, 0.12); }
.action.create_bug { color: #fb7185; background: rgba(251, 113, 133, 0.12); }
.action.auto_assign { color: #fbbf24; background: rgba(251, 191, 36, 0.12); }
.action.ai_analyze { color: #a78bfa; background: rgba(167, 139, 250, 0.12); }
.opt { line-height: 1.3; }
.opt-desc { font-size: 11px; color: var(--et-text-muted); }
.mono-input :deep(textarea) { font-family: ui-monospace, monospace; font-size: 12px; }
.smoke-result {
  margin-top: 8px; padding: 10px 14px; border-radius: 10px;
  background: rgba(52, 211, 153, 0.1); color: #34d399; font-size: 13px; font-weight: 600;
}
</style>