<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Link, Document, Files, MagicStick, Delete } from '@element-plus/icons-vue'
import { pmApi, type ParsePreview, type ParsedRequirement } from '../../api'

const props = defineProps<{ projectKey: string }>()
const emit = defineEmits<{ imported: [] }>()

const visible = ref(false)
const step = ref<'input' | 'preview'>('input')
const activeTab = ref('link')
const parsing = ref(false)
const confirming = ref(false)

// 输入态
const linkUrl = ref('')
const prdText = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const pickedFile = ref<File | null>(null)

// 预览态
const preview = ref<ParsePreview | null>(null)
const editReqs = ref<ParsedRequirement[]>([])

const caseCount = computed(() =>
  editReqs.value.reduce((n, r) => n + (r.suggestedCases ?? []).filter(c => c.selected !== false).length, 0))

function open() {
  visible.value = true
  step.value = 'input'
  activeTab.value = 'link'
  linkUrl.value = ''
  prdText.value = ''
  pickedFile.value = null
  preview.value = null
  editReqs.value = []
}

function pickFile() {
  fileInput.value?.click()
}

function onFileChange(e: Event) {
  const files = (e.target as HTMLInputElement).files
  pickedFile.value = files && files.length > 0 ? files[0] : null
}

async function runParse() {
  parsing.value = true
  try {
    let result: ParsePreview
    if (activeTab.value === 'doc') {
      if (!pickedFile.value) {
        ElMessage.warning('请先选择文档')
        return
      }
      result = await pmApi.parseDoc(props.projectKey, pickedFile.value)
    } else {
      if (!linkUrl.value.trim()) {
        ElMessage.warning('请输入链接')
        return
      }
      result = await pmApi.parseLink(props.projectKey, linkUrl.value.trim(),
        activeTab.value === 'combo' ? prdText.value : undefined)
    }
    if (!result.parsed) {
      ElMessage.warning(result.message || '解析失败')
      return
    }
    preview.value = result
    editReqs.value = (result.requirements ?? []).map(r => ({
      ...r,
      priority: r.priority || 'P2',
      suggestedCases: (r.suggestedCases ?? []).map(c => ({ ...c, selected: true }))
    }))
    step.value = 'preview'
  } catch { /* interceptor 已提示 */ }
  finally { parsing.value = false }
}

function removeReq(idx: number) {
  editReqs.value.splice(idx, 1)
}

async function confirmImport() {
  if (!preview.value) return
  if (editReqs.value.length === 0) {
    ElMessage.warning('请至少保留一条需求')
    return
  }
  confirming.value = true
  try {
    const payload = editReqs.value.map(r => ({
      title: r.title,
      userStory: r.userStory,
      acceptanceCriteria: r.acceptanceCriteria,
      priority: r.priority,
      businessValue: r.businessValue,
      prototypeUrl: activeTab.value !== 'doc' ? linkUrl.value.trim() : undefined,
      cases: (r.suggestedCases ?? []).map(c => ({
        title: c.title, testType: c.testType, priority: c.priority,
        steps: c.steps, selected: c.selected !== false
      }))
    }))
    const res = await pmApi.importConfirm(props.projectKey, preview.value.parseId, payload)
    ElMessage.success(`已导入 ${res.requirementIds.length} 条需求、${res.caseIds.length} 条用例`)
    visible.value = false
    emit('imported')
  } catch { /* interceptor 已提示 */ }
  finally { confirming.value = false }
}

defineExpose({ open })
</script>

<template>
  <el-dialog v-model="visible" :title="step === 'input' ? '智能导入需求' : '解析预览 · 确认导入'"
             width="760px" :close-on-click-modal="false">
    <!-- ======== 输入态 ======== -->
    <template v-if="step === 'input'">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="链接解析" name="link">
          <el-input v-model="linkUrl" placeholder="粘贴需求文档 / 原型页面链接，如 https://codesign.qq.com/app/s/xxx">
            <template #prefix><el-icon><Link /></el-icon></template>
          </el-input>
          <div class="tip">动态渲染的页面（如 codesign）可能抓不到正文，建议改用「文档上传」或「原型 + 轻 PRD」</div>
        </el-tab-pane>
        <el-tab-pane label="文档上传" name="doc">
          <div class="upload-box" @click="pickFile">
            <el-icon :size="28"><Document /></el-icon>
            <div v-if="pickedFile" class="file-name">{{ pickedFile.name }}</div>
            <div v-else class="tip">点击选择文件，支持 pdf / docx / md / txt / html / xlsx，≤20MB</div>
          </div>
          <input ref="fileInput" type="file" style="display: none"
                 accept=".pdf,.doc,.docx,.md,.txt,.html,.htm,.xlsx" @change="onFileChange" />
        </el-tab-pane>
        <el-tab-pane label="原型 + 轻 PRD" name="combo">
          <el-input v-model="linkUrl" placeholder="高保真原型链接（留档并回写需求的 prototypeUrl）" style="margin-bottom: 8px">
            <template #prefix><el-icon><Files /></el-icon></template>
          </el-input>
          <el-input v-model="prdText" type="textarea" :rows="8"
                    placeholder="粘贴轻量 PRD 文本（AI 以此为准解析需求与用例）" />
        </el-tab-pane>
      </el-tabs>
      <div class="dlg-footer">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="parsing" @click="runParse">
          <el-icon :size="14" style="margin-right: 4px"><MagicStick /></el-icon>开始解析
        </el-button>
      </div>
    </template>

    <!-- ======== 预览态 ======== -->
    <template v-else>
      <div class="preview-head">
        <span class="doc-title">{{ preview?.docTitle || '解析结果' }}</span>
        <span class="tip">{{ editReqs.length }} 条需求 · {{ caseCount }} 条用例<template v-if="preview?.model"> · {{ preview.model }}</template></span>
      </div>
      <div class="req-list">
        <div v-for="(req, idx) in editReqs" :key="idx" class="req-card">
          <div class="req-head">
            <el-input v-model="req.title" size="small" class="req-title" />
            <el-select v-model="req.priority" size="small" style="width: 84px">
              <el-option v-for="p in ['P0','P1','P2','P3']" :key="p" :value="p" :label="p" />
            </el-select>
            <el-button size="small" text type="danger" @click="removeReq(idx)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
          <el-collapse>
            <el-collapse-item title="用户故事 / 验收标准 / 用例" :name="idx">
              <div class="field-label">用户故事</div>
              <div class="field-text">{{ req.userStory || '—' }}</div>
              <div class="field-label">验收标准</div>
              <pre class="field-text pre">{{ req.acceptanceCriteria || '—' }}</pre>
              <div class="field-label">建议用例（{{ (req.suggestedCases ?? []).length }}）</div>
              <div v-for="(c, ci) in req.suggestedCases" :key="ci" class="case-row">
                <el-checkbox v-model="c.selected" />
                <el-tag size="small" effect="plain">{{ c.testType || 'FUNCTIONAL' }}</el-tag>
                <el-tag size="small" effect="plain" type="warning">{{ c.priority || 'P2' }}</el-tag>
                <span class="case-title">{{ c.title }}</span>
              </div>
            </el-collapse-item>
          </el-collapse>
        </div>
      </div>
      <div class="dlg-footer">
        <el-button @click="step = 'input'">返回重试</el-button>
        <el-button type="primary" :loading="confirming" :disabled="editReqs.length === 0" @click="confirmImport">
          确认导入（{{ editReqs.length }} 需求 / {{ caseCount }} 用例）
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.tip { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 6px; }
.upload-box {
  border: 1px dashed var(--el-border-color); border-radius: 8px; padding: 28px;
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  cursor: pointer; color: var(--el-text-color-secondary);
}
.upload-box:hover { border-color: var(--el-color-primary); color: var(--el-color-primary); }
.file-name { font-size: 13px; color: var(--el-text-color-primary); }
.dlg-footer { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
.preview-head { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 10px; }
.doc-title { font-weight: 600; }
.req-list { max-height: 46vh; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; }
.req-card { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 10px 12px; }
.req-head { display: flex; gap: 8px; align-items: center; }
.req-title { flex: 1; }
.field-label { font-size: 12px; color: var(--el-text-color-secondary); margin: 8px 0 2px; }
.field-text { font-size: 13px; }
.pre { white-space: pre-wrap; font-family: inherit; margin: 0; }
.case-row { display: flex; align-items: center; gap: 6px; padding: 3px 0; }
.case-title { font-size: 13px; }
</style>
