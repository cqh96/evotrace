<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Connection, Delete, MagicStick, Refresh, Star } from '@element-plus/icons-vue'
import StatCard from '../components/StatCard.vue'
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

// ---------- 展示辅助（不影响业务逻辑） ----------
const enabledCount = computed(() => models.value.filter(m => m.enabled).length)
const keyedCount = computed(() => models.value.filter(m => m.apiKey).length)
const defaultModel = computed(() => models.value.find(m => m.default) ?? null)
const providerClass = (v: string) => ({ OPENAI: 'g-emerald', ARK: 'g-cyan', DEEPSEEK: 'g-indigo', CUSTOM: 'g-violet' } as any)[v] || 'g-indigo'
const providerChar = (v: string) => providerLabel(v).charAt(0).toUpperCase()
const modelStatus = (m: ModelConfig) => {
  if (!m.enabled) return { cls: 'st-off', label: '已停用' }
  if (!m.apiKey) return { cls: 'st-warn', label: '未配置密钥' }
  return { cls: 'st-ok', label: '可用' }
}
const CAPABILITIES = [
  { key: 'C', name: '代码审查', desc: '生成审查报告与缺陷定位', cls: 'g-indigo' },
  { key: 'Q', name: '演化问答', desc: '自然语言检索变更历史', cls: 'g-violet' },
  { key: 'S', name: '变更摘要', desc: '提交摘要与发布说明', cls: 'g-cyan' },
  { key: 'R', name: '需求分析', desc: 'PRD 拆解与验收点生成', cls: 'g-amber' }
]

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
    <!-- 页面 Hero -->
    <section class="et-hero hero rise" style="--d:.02s">
      <div class="hero-left">
        <h2>AI 模型配置</h2>
        <div class="et-hero-sub">统一接入 OpenAI 兼容端点，管理密钥、默认模型与 AI 任务路由</div>
        <div class="hero-chips" v-if="status">
          <div class="chip-mini">
            <span class="status-dot" :class="status.usable ? 'st-ok' : 'st-warn'"></span>
            {{ status.configured ? '默认模型已配置' : '未配置默认模型' }}
          </div>
          <div class="chip-mini">当前生效 <b>{{ status.model }}</b></div>
        </div>
      </div>
      <div class="hero-right">
        <div class="chip-mini">模型总数 <b>{{ models.length }}</b></div>
        <div class="chip-mini">已启用 <b>{{ enabledCount }}</b> / 已配密钥 <b>{{ keyedCount }}</b></div>
        <div class="hero-actions">
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </section>

    <!-- 当前生效模型提示 -->
    <section v-if="status" class="status-banner et-card rise" :class="status.configured ? 'ok' : 'warn'" style="--d:.06s">
      <span class="status-dot" :class="status.configured ? 'st-ok' : 'st-warn'"></span>
      <div class="sb-body">
        <div class="sb-title">
          <template v-if="status.configured">
            当前生效模型：<b>{{ status.model }}</b>
            <span v-if="status.provider">（{{ providerLabel(status.provider) }}）</span>
          </template>
          <template v-else>
            未配置默认模型，当前使用 yml 环境变量模型 <b>{{ status.model }}</b>
          </template>
        </div>
        <div class="sb-sub" v-if="!status.usable">未配置 API Key，AI 任务将使用模板回退</div>
      </div>
    </section>

    <!-- 统计 -->
    <el-row :gutter="16" class="stats-row">
      <el-col :xs="12" :sm="6"><StatCard label="模型总数" :value="models.length" :icon="Connection" color="#4f5ad1" class="rise" style="--d:.10s" /></el-col>
      <el-col :xs="12" :sm="6"><StatCard label="已启用" :value="enabledCount" :icon="MagicStick" color="#059669" class="rise" style="--d:.14s" /></el-col>
      <el-col :xs="12" :sm="6"><StatCard label="已配密钥" :value="keyedCount" :icon="Star" color="#b45309" class="rise" style="--d:.18s" /></el-col>
      <el-col :xs="12" :sm="6"><StatCard label="默认模型" :value="defaultModel?.name ?? '—'" :icon="Plus" color="#6d4fd6" class="rise" style="--d:.22s" /></el-col>
    </el-row>

    <!-- 模型能力对比 -->
    <section class="cap-wrap rise" style="--d:.26s">
      <div v-for="c in CAPABILITIES" :key="c.key" class="cap-card et-card">
        <span class="et-g-ic" :class="c.cls">{{ c.key }}</span>
        <div class="cap-info">
          <div class="cap-name">{{ c.name }}</div>
          <div class="cap-desc">{{ c.desc }}</div>
        </div>
        <div class="cap-model">
          <span class="cap-lbl">承担模型</span>
          <span class="cap-val">{{ status?.model ?? '—' }}</span>
        </div>
      </div>
    </section>

    <!-- 模型列表 -->
    <section class="et-card model-panel rise" style="--d:.30s">
      <div class="et-card-head">
        <div>
          <div class="et-card-title">
            <span class="et-tic"><el-icon :size="15"><Connection /></el-icon></span>模型列表
            <span class="et-mini-tag et-tag-info">{{ models.length }}</span>
          </div>
          <div class="et-card-sub">配置 OpenAI 兼容模型端点，测试连接并设为默认模型</div>
        </div>
        <div class="right">
          <el-button type="primary" :icon="Plus" @click="openDialog()">新增模型配置</el-button>
        </div>
      </div>
      <div class="et-card-body" v-loading="loading">
        <div v-if="models.length" class="model-grid">
          <div v-for="m in models" :key="m.id" class="model-card et-card" :class="{ testing: testingId === m.id }">
            <div class="mc-head">
              <span class="et-g-ic" :class="providerClass(m.provider)">{{ providerChar(m.provider) }}</span>
              <div class="mc-id">
                <div class="mc-name">
                  {{ m.name }}
                  <span v-if="m.default" class="et-mini-tag et-tag-req">默认</span>
                </div>
                <div class="mc-sub">{{ providerLabel(m.provider) }} · <span class="mono">{{ m.modelName }}</span></div>
              </div>
              <span v-if="testingId === m.id" class="mc-status testing">
                <span class="et-pulse"></span>测试中
              </span>
              <span v-else class="mc-status">
                <span class="status-dot" :class="modelStatus(m).cls"></span>{{ modelStatus(m).label }}
              </span>
            </div>
            <div class="mc-url" :title="m.baseUrl">{{ m.baseUrl || '未填写 Base URL' }}</div>
            <div class="mc-row">
              <span>API Key</span>
              <span class="mono" :class="{ muted: !m.apiKey }">{{ m.apiKey || '未配置' }}</span>
            </div>
            <div class="mc-foot">
              <el-switch :model-value="m.enabled" size="small" @change="toggleEnabled(m)" />
              <span class="mc-enable-label">{{ m.enabled ? '已启用' : '已停用' }}</span>
              <div class="mc-actions">
                <el-button size="small" link :icon="MagicStick" :loading="testingId === m.id" @click="testConnection(m.id)">测试</el-button>
                <el-button v-if="!m.default" size="small" link type="primary" :icon="Star" @click="setDefault(m.id)">设默认</el-button>
                <el-button size="small" link type="primary" @click="openDialog(m)">编辑</el-button>
                <el-button size="small" link type="danger" :icon="Delete" @click="remove(m)">删除</el-button>
              </div>
            </div>
          </div>
        </div>
        <div v-else-if="!loading" class="et-empty-hint">
          <div class="et-empty-ic"><el-icon :size="24"><Connection /></el-icon></div>
          暂无模型配置，点击右上角「新增模型配置」创建
        </div>
      </div>
    </section>

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
/* ========== Hero ========== */
.hero { display: flex; align-items: center; justify-content: space-between; gap: 24px; flex-wrap: wrap; }
.hero-chips { display: flex; gap: 9px; margin-top: 14px; flex-wrap: wrap; }
.hero-right { display: flex; gap: 9px; flex-wrap: wrap; }
.chip-mini { display: inline-flex; align-items: center; gap: 8px; font-size: 12px; color: var(--et-text-secondary); padding: 6px 12px; border-radius: 10px; background: var(--et-bg-muted); border: 1px solid var(--et-border); }
.chip-mini b { color: var(--et-text); font-variant-numeric: tabular-nums; }

/* 状态圆点 */
.status-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; display: inline-block; }
.st-ok { background: var(--et-ok); }
.st-warn { background: var(--et-warn); }
.st-off { background: var(--et-text-muted); }

/* ========== 生效模型横幅 ========== */
.status-banner { display: flex; align-items: center; gap: 14px; padding: 16px 22px; margin-top: 16px; }
.status-banner.ok {
  border-color: rgba(5, 150, 105, 0.3);
  background: rgba(5, 150, 105, 0.06);
}
.status-banner.warn {
  border-color: rgba(180, 83, 9, 0.3);
  background: rgba(180, 83, 9, 0.06);
}
.sb-body { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.sb-title { font-size: 14px; font-weight: 600; color: var(--et-text); }
.sb-title b { color: #0e7490; }
.sb-sub { font-size: 12px; color: var(--et-text-muted); }

.stats-row { margin-top: 16px; }

/* ========== 能力对比 ========== */
.cap-wrap { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-top: 16px; }
.cap-wrap .cap-card + .cap-card { margin-top: 0; }
.cap-card { display: flex; align-items: center; gap: 12px; padding: 14px 16px; }
.cap-info { flex: 1; min-width: 0; }
.cap-name { font-size: 13px; font-weight: 700; color: var(--et-text); }
.cap-desc { font-size: 11px; color: var(--et-text-muted); margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.cap-model { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; flex-shrink: 0; }
.cap-lbl { font-size: 10px; color: var(--et-text-muted); }
.cap-val { font-size: 11.5px; font-weight: 600; color: var(--et-text-secondary); max-width: 110px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ========== 模型列表 ========== */
.model-panel { margin-top: 18px; }
.model-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 14px; }
.model-grid .model-card + .model-card { margin-top: 0; }
.model-card { padding: 16px 18px; }
.model-card.testing {
  border-color: rgba(8, 145, 178, 0.35);
  box-shadow: 0 0 0 1px rgba(8, 145, 178, 0.15), var(--et-shadow-md);
}
.mc-head { display: flex; align-items: center; gap: 12px; }
.mc-id { flex: 1; min-width: 0; }
.mc-name { font-size: 14px; font-weight: 700; color: var(--et-text); display: flex; align-items: center; gap: 8px; }
.mc-sub { font-size: 11.5px; color: var(--et-text-muted); margin-top: 3px; }
.mc-status { display: inline-flex; align-items: center; gap: 6px; font-size: 11.5px; font-weight: 600; color: var(--et-text-secondary); flex-shrink: 0; }
.mc-status.testing { color: #0e7490; }
.mc-url {
  margin-top: 12px; font-size: 11.5px;
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  color: var(--et-text-muted); background: var(--et-bg-muted);
  border: 1px solid var(--et-border); border-radius: 8px; padding: 6px 10px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.mc-row { display: flex; align-items: center; justify-content: space-between; margin-top: 10px; font-size: 12px; color: var(--et-text-muted); }
.mc-foot {
  display: flex; align-items: center; gap: 10px; margin-top: 14px;
  padding-top: 12px; border-top: 1px dashed var(--et-border);
}
.mc-enable-label { font-size: 11.5px; color: var(--et-text-muted); }
.mc-actions { margin-left: auto; display: flex; align-items: center; gap: 2px; flex-wrap: wrap; }

.mono { font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 12px; color: var(--et-text); }
.mono.muted { color: var(--et-text-muted); }
.muted { color: var(--el-text-color-secondary); font-size: 12px; }

@media (max-width: 1280px) {
  .cap-wrap { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 860px) {
  .hero { flex-direction: column; align-items: flex-start; }
  .cap-wrap { grid-template-columns: 1fr; }
}
</style>
