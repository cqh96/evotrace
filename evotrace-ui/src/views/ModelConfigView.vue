<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Connection, Delete, MagicStick, Star } from '@element-plus/icons-vue'
import PageCard from '../components/PageCard.vue'
import { modelConfigApi, type ModelConfig, type ModelStatus } from '../api'

// ---------- 提供方快捷模板 ----------
const PROVIDERS: { value: string; label: string; baseUrl: string; modelPlaceholder: string }[] = [
  { value: 'OPENAI', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', modelPlaceholder: 'gpt-4o-mini' },
  { value: 'ARK', label: '火山方舟', baseUrl: 'https://ark.cn-beijing.volces.com/api/v3', modelPlaceholder: '推理接入点 ep-xxx 或模型名' },
  { value: 'DEEPSEEK', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com', modelPlaceholder: 'deepseek-chat' },
  { value: 'CUSTOM', label: '自定义(OpenAI 兼容)', baseUrl: '', modelPlaceholder: '模型名' }
]
const providerLabel = (v: string) => PROVIDERS.find(p => p.value === v)?.label ?? v

const models = ref<ModelConfig[]>([])
const status = ref<ModelStatus | null>(null)
const loading = ref(true)

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const testingId = ref<number | null>(null)
const form = ref({
  name: '', provider: 'OPENAI', baseUrl: 'https://api.openai.com/v1',
  apiKey: '', modelName: '', temperature: 0.2, enabled: true, default: false
})

const dialogTitle = computed(() => (editingId.value ? '编辑模型配置' : '新增模型配置'))

async function load() {
  loading.value = true
  try {
    const [m, s] = await Promise.all([modelConfigApi.list(), modelConfigApi.status()])
    models.value = m
    status.value = s
  } catch { /* 错误提示由拦截器统一处理 */ }
  loading.value = false
}

function openDialog(cfg?: ModelConfig) {
  editingId.value = cfg?.id ?? null
  if (cfg) {
    form.value = {
      name: cfg.name, provider: cfg.provider, baseUrl: cfg.baseUrl,
      apiKey: '', modelName: cfg.modelName,
      temperature: cfg.temperature ?? 0.2, enabled: cfg.enabled, default: cfg.default ?? false
    }
  } else {
    form.value = { name: '', provider: 'OPENAI', baseUrl: 'https://api.openai.com/v1', apiKey: '', modelName: '', temperature: 0.2, enabled: true, default: false }
  }
  dialogVisible.value = true
}

// 切换提供方时自动填充 base-url 模板(仅新增或 baseUrl 为空/为模板值时)
function onProviderChange() {
  const p = PROVIDERS.find(x => x.value === form.value.provider)
  if (!p) return
  const editing = editingId.value !== null
  const wasTemplate = PROVIDERS.some(x => x.baseUrl && x.baseUrl === form.value.baseUrl)
  if (!editing || wasTemplate || !form.value.baseUrl) {
    form.value.baseUrl = p.baseUrl
  }
}

async function save() {
  if (!form.value.name.trim()) { ElMessage.warning('请填写配置名'); return }
  if (!form.value.baseUrl.trim()) { ElMessage.warning('请填写 Base URL'); return }
  if (!form.value.modelName.trim()) { ElMessage.warning('请填写模型名称'); return }
  saving.value = true
  try {
    const payload = { ...form.value, apiKey: form.value.apiKey.trim() }
    if (editingId.value) {
      await modelConfigApi.update(editingId.value, payload)
      ElMessage.success('配置已更新')
    } else {
      await modelConfigApi.create(payload)
      ElMessage.success('配置已创建')
    }
    dialogVisible.value = false
    await load()
  } catch { /* 拦截器已提示 */ }
  saving.value = false
}

async function testConnection(id: number) {
  testingId.value = id
  try {
    const r = await modelConfigApi.test(id)
    if (r.ok) ElMessage.success(r.message)
    else ElMessage.error(r.message)
  } catch { /* 拦截器已提示 */ }
  testingId.value = null
}

async function setDefault(id: number) {
  try {
    await modelConfigApi.setDefault(id)
    ElMessage.success('已设为默认模型,立即生效')
    await load()
  } catch { /* 拦截器已提示 */ }
}

async function toggleEnabled(cfg: ModelConfig) {
  try {
    if (cfg.enabled) {
      await modelConfigApi.enable(cfg.id)
      ElMessage.success('已启用')
    } else {
      await modelConfigApi.disable(cfg.id)
      ElMessage.success('已停用')
    }
    await load()
  } catch {
    await load()  // 失败(如默认模型禁止停用)时回滚 switch 状态
  }
}

async function remove(cfg: ModelConfig) {
  try {
    await ElMessageBox.confirm(`确认删除模型配置「${cfg.name}」?`, '删除确认', { type: 'warning' })
    await modelConfigApi.remove(cfg.id)
    ElMessage.success('已删除')
    await load()
  } catch { /* 取消或拦截器提示 */ }
}

onMounted(load)
</script>

<template>
  <div>
    <!-- 当前生效模型提示 -->
    <el-alert v-if="status" :type="status.configured ? 'success' : 'info'" :closable="false" style="margin-bottom: 16px">
      <template #title>
        <template v-if="status.configured">
          ✅ 当前生效模型: <b>{{ status.model }}</b>
          <span v-if="status.provider"> ({{ providerLabel(status.provider) }})</span>
          <span v-if="!status.usable" style="margin-left: 8px" class="muted">— 未配置 API Key,AI 任务将使用模板回退</span>
        </template>
        <template v-else>
          ⚠️ 未配置默认模型,当前使用 yml 环境变量模型 <b>{{ status.model }}</b>
        </template>
      </template>
    </el-alert>

    <PageCard :title="'AI 模型配置 (' + models.length + ')'" no-padding style="margin-top: 16px">
      <template #extra>
        <el-button type="primary" :icon="Plus" @click="openDialog()">新增模型配置</el-button>
      </template>
      <el-table :data="models" v-loading="loading" stripe>
        <el-table-column prop="name" label="配置名" width="150" />
        <el-table-column label="提供方" width="120">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ providerLabel(row.provider) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="modelName" label="模型" width="180" show-overflow-tooltip />
        <el-table-column prop="baseUrl" label="Base URL" min-width="220" show-overflow-tooltip />
        <el-table-column label="API Key" width="140">
          <template #default="{ row }">
            <span v-if="row.apiKey" class="mono">{{ row.apiKey }}</span>
            <span v-else class="muted">未配置</span>
          </template>
        </el-table-column>
        <el-table-column label="默认" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.default" size="small" type="warning">默认</el-tag>
            <el-button v-else size="small" link type="primary" :icon="Star" @click="setDefault(row.id)">设默认</el-button>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80" align="center">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="toggleEnabled(row)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190" align="right">
          <template #default="{ row }">
            <el-button size="small" link :icon="MagicStick" :loading="testingId === row.id" @click="testConnection(row.id)">测试</el-button>
            <el-button size="small" link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button size="small" link type="danger" :icon="Delete" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && models.length === 0" description="暂无模型配置,点击右上角新增" style="padding: 40px 0" />
    </PageCard>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="540px">
      <el-alert type="info" :closable="false"
                title="协议统一为 OpenAI 兼容。Base URL 填写兼容端点;编辑时 API Key 留空表示保持原值。"
                style="margin-bottom: 14px" />
      <el-form label-width="90px">
        <el-form-item label="配置名" required>
          <el-input v-model="form.name" placeholder="如: 主模型-方舟" maxlength="64" />
        </el-form-item>
        <el-form-item label="提供方">
          <el-select v-model="form.provider" style="width: 100%" @change="onProviderChange">
            <el-option v-for="p in PROVIDERS" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL" required>
          <el-input v-model="form.baseUrl" placeholder="https://..." :icon="Connection" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password
                    :placeholder="editingId ? '留空则保持原值' : 'sk-...'" />
        </el-form-item>
        <el-form-item label="模型名称" required>
          <el-input v-model="form.modelName" :placeholder="PROVIDERS.find(p => p.value === form.provider)?.modelPlaceholder" maxlength="128" />
        </el-form-item>
        <el-form-item label="温度">
          <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="2" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="form.default" />
          <span class="muted" style="margin-left: 8px">默认模型承担全部 AI 任务(摘要/发布说明/代码审查)</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.mono { font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 12px; color: var(--el-text-color-regular); }
.muted { color: var(--el-text-color-secondary); font-size: 12px; }
</style>
