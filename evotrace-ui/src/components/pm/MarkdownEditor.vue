<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pmApi, type RequirementDocument } from '../../api'
import { renderMarkdown } from '../../utils/markdown'

const props = defineProps<{
  projectKey: string
  requirementId: number
  aiUsable: boolean
}>()

const loading = ref(false)
const content = ref('')
const title = ref('PRD')
const versions = ref<RequirementDocument[]>([])
const currentVersion = ref(0)
const dirty = ref(false)
const preview = ref(false)
const saving = ref(false)
const drafting = ref(false)
const prompt = ref('')

const versionLabel = computed(() => (currentVersion.value === 0 ? '尚未保存' : `v${currentVersion.value}`))

async function load() {
  loading.value = true
  try {
    const [latest, list] = await Promise.all([
      pmApi.document(props.projectKey, props.requirementId),
      pmApi.documentVersions(props.projectKey, props.requirementId)
    ])
    content.value = latest.content ?? ''
    title.value = latest.title ?? 'PRD'
    currentVersion.value = latest.version ?? 0
    versions.value = list ?? []
    dirty.value = false
  } catch {
    ElMessage.error('加载文档失败')
  } finally {
    loading.value = false
  }
}

function openVersion(v: number) {
  if (dirty.value) {
    ElMessage.warning('当前有未保存的修改，请先保存')
    return
  }
  pmApi.documentVersion(props.projectKey, props.requirementId, v).then((doc) => {
    content.value = doc.content ?? ''
    title.value = doc.title ?? 'PRD'
    currentVersion.value = v
    ElMessage.info(`已加载 v${v}（只读视角，可回滚或另存为新版）`)
  })
}

async function save() {
  if (!content.value.trim()) return
  saving.value = true
  try {
    const result = await pmApi.documentSave(props.projectKey, props.requirementId, { title: title.value, content: content.value })
    currentVersion.value = result.version
    dirty.value = false
    ElMessage.success(`已保存为 v${result.version}`)
    await load()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function rollback(v: number) {
  try {
    await ElMessageBox.confirm(`将 v${v} 复制为新版本（历史版本不可变），继续？`, '回滚', { type: 'warning' })
  } catch {
    return
  }
  const result = await pmApi.documentRollback(props.projectKey, props.requirementId, v)
  ElMessage.success(`已回滚，生成 v${result.version}`)
  await load()
}

async function aiDraft() {
  drafting.value = true
  try {
    const result = await pmApi.documentAiDraft(props.projectKey, props.requirementId, prompt.value || undefined)
    if (result.generated) {
      content.value = result.content
      ElMessage.success('AI 初稿已生成（未保存），可编辑后保存')
    } else {
      ElMessage.warning(result.message ?? 'AI 不可用，已生成骨架稿')
      content.value = result.content
    }
  } catch {
    ElMessage.error('生成失败')
  } finally {
    drafting.value = false
  }
}
</script>

<template>
  <div v-loading="loading">
    <div class="doc-toolbar">
      <div class="doc-left">
        <el-input v-model="title" placeholder="文档标题" style="width: 200px" @input="dirty = true" />
        <el-tag size="small" :type="currentVersion > 0 ? 'success' : 'info'">{{ versionLabel }}</el-tag>
        <span v-if="dirty" class="dirty-mark">● 未保存</span>
      </div>
      <div class="doc-right">
        <el-button size="small" :loading="drafting" :disabled="!aiUsable" @click="aiDraft">
          <el-tooltip :content="aiUsable ? '基于需求字段生成 PRD 初稿（不落库）' : '未配置可用 AI 模型'" placement="top">
            <span>✨ AI 生成初稿</span>
          </el-tooltip>
        </el-button>
        <el-input v-model="prompt" size="small" placeholder="补充生成提示（可选）" style="width: 200px" clearable />
        <el-button size="small" @click="preview = !preview">{{ preview ? '编辑' : '预览' }}</el-button>
        <el-button size="small" type="primary" :loading="saving" :disabled="!content.trim()" @click="save">保存新版本</el-button>
      </div>
    </div>

    <div class="doc-body">
      <div class="doc-edit">
        <el-input v-if="!preview" v-model="content" type="textarea" class="doc-textarea" placeholder="用 Markdown 编写 PRD…" @input="dirty = true" />
        <div v-else v-html="renderMarkdown(content)" class="doc-preview markdown-body" />
      </div>

      <div class="doc-versions">
        <div class="versions-title">版本历史（{{ versions.length }}）</div>
        <div v-for="v in versions" :key="v.version" class="version-row" :class="{ active: v.version === currentVersion }">
          <div class="version-info" @click="openVersion(v.version)">
            <span class="version-no">v{{ v.version }}</span>
            <span class="version-meta">{{ v.createdBy || '—' }} · {{ v.createdAt?.slice(0, 10) }}</span>
          </div>
          <el-button size="small" text type="warning" :disabled="v.version === currentVersion" @click="rollback(v.version)">
            回滚
          </el-button>
        </div>
        <el-empty v-if="versions.length === 0" description="暂无版本" :image-size="50" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.doc-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
  padding: 12px 14px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  border-radius: 14px;
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  flex-wrap: wrap;
}
.doc-left, .doc-right { display: flex; align-items: center; gap: 8px; flex-wrap: wrap }
.dirty-mark {
  font-size: 12px;
  color: var(--et-warn);
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-weight: 600;
}
.doc-body { display: flex; gap: 14px; min-height: 480px }
.doc-edit { flex: 1; min-width: 0 }
.doc-textarea :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 13px;
  line-height: 1.7;
  min-height: 480px;
}
.doc-preview {
  background: var(--et-card-bg);
  border: 1px solid var(--et-border);
  border-radius: var(--et-radius-lg);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  padding: 20px 24px;
  min-height: 480px;
  overflow: auto;
  max-height: 640px;
  transition: border-color 0.22s, box-shadow 0.22s;
}
.doc-preview:hover { border-color: var(--et-hover-border); box-shadow: var(--et-shadow); }
.markdown-body :deep(h1) { font-size: 20px; border-bottom: 1px solid var(--et-border); padding-bottom: 8px }
.markdown-body :deep(h2) { font-size: 16px; margin-top: 16px }
.markdown-body :deep(p), .markdown-body :deep(li) { line-height: 1.8; font-size: 14px }
.markdown-body :deep(code) { background: var(--et-bg-muted); padding: 2px 6px; border-radius: 4px; font-size: 12px; color: var(--et-primary-light) }
.markdown-body :deep(pre) { background: var(--et-bg-muted); padding: 12px; border-radius: 8px; overflow: auto; border: 1px solid var(--et-border) }
.doc-versions { width: 220px; flex-shrink: 0; border-left: 1px solid var(--et-border); padding-left: 14px }
.versions-title {
  font-size: 13px;
  font-weight: 700;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  gap: 7px;
}
.versions-title::before {
  content: '';
  width: 4px;
  height: 13px;
  border-radius: 2px;
  background: linear-gradient(180deg, var(--et-grad-a), var(--et-grad-c));
}
.version-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
  padding: 7px 9px;
  border-radius: 10px;
  border: 1px solid transparent;
  cursor: pointer;
  margin-bottom: 4px;
  transition: background 0.15s, border-color 0.15s;
}
.version-row:hover { background: var(--et-bg-muted); border-color: var(--et-border) }
.version-row.active {
  background: var(--et-primary-bg);
  border-color: rgba(109, 124, 255, 0.28);
}
.version-info { display: flex; flex-direction: column; gap: 2px }
.version-no { font-weight: 700; font-size: 13px; font-variant-numeric: tabular-nums }
.version-row.active .version-no {
  background: linear-gradient(90deg, var(--et-grad-a), var(--et-grad-c));
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.version-meta { font-size: 11px; color: var(--et-text-muted) }
</style>
