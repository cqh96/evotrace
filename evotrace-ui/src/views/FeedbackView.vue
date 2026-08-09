<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, MagicStick, Check, Close, ChatDotRound } from '@element-plus/icons-vue'
import { feedbackApi, type Feedback } from '../api'
import { useProjectStore } from '../stores/project'

const projectStore = useProjectStore()
const { current: project } = storeToRefs(projectStore)

const loading = ref(false)
const list = ref<Feedback[]>([])
const statusFilter = ref('')

async function load() {
  if (!project.value) return
  loading.value = true
  try { list.value = await feedbackApi.list(project.value, statusFilter.value || undefined) } catch { ElMessage.error('加载反馈失败') }
  loading.value = false
}

// ===== 新增反馈 =====
const createOpen = ref(false)
const content = ref('')
const sourceOptions = ['MANUAL', 'GITLAB', 'GITHUB', 'JIRA', 'FEISHU', 'APP']
const source = ref('MANUAL')
async function create() {
  if (!content.value.trim()) return ElMessage.warning('请输入反馈内容')
  try {
    await feedbackApi.create(project.value!, { content: content.value, source: source.value })
    ElMessage.success('反馈已提交')
    createOpen.value = false
    content.value = ''
    load()
  } catch { ElMessage.error('提交失败') }
}

// ===== AI 分析 =====
const analyzing = ref<number | null>(null)
const analysisResult = ref<Record<string, any> | null>(null)
const analysisOpen = ref(false)
async function analyze(fb: Feedback) {
  analyzing.value = fb.id
  try {
    const r = await feedbackApi.analyze(project.value!, fb.id)
    analysisResult.value = r
    analysisOpen.value = true
  } catch { ElMessage.error('AI 分析失败') }
  analyzing.value = null
}

// ===== 转需求/缺陷 =====
const convertOpen = ref(false)
const convertId = ref<number | null>(null)
const convertForm = ref({ type: 'REQUIREMENT', title: '', priority: 'P2', summary: '' })
async function openConvert(fb: Feedback) {
  const prev = analysisResult.value && analysisResult.value.feedbackId === fb.id ? analysisResult.value : null
  convertId.value = fb.id
  convertForm.value = {
    type: prev?.type === 'BUG' ? 'BUG' : 'REQUIREMENT',
    title: prev?.title || fb.content.substring(0, 40),
    priority: prev?.priority || 'P2',
    summary: prev?.summary || fb.content
  }
  convertOpen.value = true
}
async function doConvert() {
  if (convertId.value == null) return
  try {
    await feedbackApi.convert(project.value!, convertId.value, convertForm.value)
    ElMessage.success('已转换为需求/缺陷')
    convertOpen.value = false
    load()
  } catch { ElMessage.error('转换失败') }
}

async function ignore(id: number) {
  await ElMessageBox.confirm('确认忽略该反馈？', '提示', { type: 'warning' })
  try { await feedbackApi.ignore(project.value!, id); load() } catch { ElMessage.error('操作失败') }
}

const statusLabels: Record<string, string> = { NEW: '待处理', ANALYZED: '已分析', CONVERTED: '已转换', IGNORED: '已忽略' }
const statusColors: Record<string, string> = { NEW: '#fbbf24', ANALYZED: '#38e1ff', CONVERTED: '#34d399', IGNORED: '#64748b' }

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <div class="left">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="load">
          <el-option v-for="(label, key) in statusLabels" :key="key" :label="label" :value="key" />
        </el-select>
      </div>
      <div class="right">
        <button class="ops-btn primary" @click="createOpen = true"><el-icon><Plus /></el-icon> 提交反馈</button>
        <button class="ops-btn" @click="load"><el-icon><Refresh /></el-icon> 刷新</button>
      </div>
    </div>

    <div class="et-card">
      <div class="et-card-body no-padding">
        <el-table :data="list" v-loading="loading" size="default" style="width: 100%">
          <el-table-column prop="content" label="反馈内容" min-width="260" show-overflow-tooltip />
          <el-table-column label="来源" width="100">
            <template #default="{ row }"><span class="src">{{ row.source }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <span class="status" :style="{ color: statusColors[row.status], background: `color-mix(in srgb, ${statusColors[row.status]} 14%, transparent)` }">
                {{ statusLabels[row.status] || row.status }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="AI 分析" width="200">
            <template #default="{ row }">
              <span v-if="row.aiAnalysis" class="ai-hint">已分析 · {{ row.aiModel || 'heuristic' }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="转换结果" width="180">
            <template #default="{ row }">
              <span v-if="row.convertedRequirementId" class="conv">需求 #{{ row.convertedRequirementId }}</span>
              <span v-else-if="row.convertedBugId" class="conv bug">缺陷 #{{ row.convertedBugId }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="150">
            <template #default="{ row }">{{ row.createdAt }}</template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <div class="ops" v-if="row.status === 'NEW'">
                <button class="ops-btn primary" :loading="analyzing === row.id" @click="analyze(row)"><el-icon><MagicStick /></el-icon> AI 分析</button>
                <button class="ops-btn danger" @click="ignore(row)"><el-icon><Close /></el-icon></button>
              </div>
              <div class="ops" v-else-if="row.status === 'ANALYZED'">
                <button class="ops-btn success" @click="openConvert(row)"><el-icon><Check /></el-icon> 转换</button>
              </div>
              <span v-else class="muted">已完成</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- ===== 新增反馈 ===== -->
    <el-dialog v-model="createOpen" title="提交反馈" width="520px">
      <el-form label-width="80px">
        <el-form-item label="来源">
          <el-select v-model="source" style="width: 100%">
            <el-option v-for="s in sourceOptions" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="content" type="textarea" :rows="4" placeholder="描述你遇到的问题或期望的功能…" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" @click="create">提交</el-button>
      </template>
    </el-dialog>

    <!-- ===== AI 分析结果 ===== -->
    <el-dialog v-model="analysisOpen" title="AI 分析结果" width="480px">
      <div v-if="analysisResult" class="ana">
        <div class="ana-type" :class="analysisResult.type === 'BUG' ? 'bug' : 'req'">
          <el-icon><ChatDotRound /></el-icon>
          {{ analysisResult.type === 'BUG' ? '这是缺陷' : '这是需求' }}
        </div>
        <div class="ana-row"><span class="k">标题</span><span>{{ analysisResult.title }}</span></div>
        <div class="ana-row"><span class="k">优先级</span><span class="prio" :class="analysisResult.priority.toLowerCase()">{{ analysisResult.priority }}</span></div>
        <div class="ana-row"><span class="k">摘要</span><span>{{ analysisResult.summary }}</span></div>
        <div class="ana-foot" v-if="analysisResult.model">由 {{ analysisResult.model }} 生成</div>
      </div>
      <template #footer>
        <el-button @click="analysisOpen = false">关闭</el-button>
        <el-button type="primary" @click="list.find(f => f.id === analysisResult?.feedbackId) && openConvert(list.find(f => f.id === analysisResult!.feedbackId)!)">转为需求/缺陷</el-button>
      </template>
    </el-dialog>

    <!-- ===== 转换 ===== -->
    <el-dialog v-model="convertOpen" title="转换为需求 / 缺陷" width="520px">
      <el-form :model="convertForm" label-width="80px">
        <el-form-item label="类型">
          <el-radio-group v-model="convertForm.type">
            <el-radio-button value="REQUIREMENT">需求</el-radio-button>
            <el-radio-button value="BUG">缺陷</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="标题" required><el-input v-model="convertForm.title" /></el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="convertForm.priority" style="width: 100%">
            <el-option v-for="p in ['P0', 'P1', 'P2', 'P3']" :key="p" :label="p" :value="p" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述"><el-input v-model="convertForm.summary" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="convertOpen = false">取消</el-button>
        <el-button type="primary" @click="doConvert">转换</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.page-toolbar .left, .page-toolbar .right { display: flex; align-items: center; gap: 10px; }
.ops-btn {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 14px; border-radius: 20px; border: 1px solid transparent;
  font-family: inherit; font-size: 13px; font-weight: 600; cursor: pointer; transition: all 0.18s;
}
.ops-btn.primary { background: rgba(109, 124, 255, 0.14); color: #a8b4ff; }
.ops-btn.primary:hover { background: rgba(109, 124, 255, 0.28); box-shadow: 0 0 12px rgba(109, 124, 255, 0.3); }
.ops-btn.success { background: rgba(52, 211, 153, 0.14); color: #34d399; }
.ops-btn.success:hover { background: rgba(52, 211, 153, 0.28); box-shadow: 0 0 12px rgba(52, 211, 153, 0.3); }
.ops-btn.danger { background: rgba(251, 113, 133, 0.14); color: #fb7185; }
.ops-btn.danger:hover { background: rgba(251, 113, 133, 0.28); box-shadow: 0 0 12px rgba(251, 113, 133, 0.3); }
.ops { display: flex; gap: 6px; }
.src {
  font-family: ui-monospace, monospace; font-size: 11px; color: var(--et-text-secondary);
  padding: 3px 8px; border-radius: 6px; background: var(--et-bg-muted); border: 1px solid var(--et-border);
}
.status { font-size: 12px; font-weight: 600; padding: 3px 10px; border-radius: 20px; }
.ai-hint { font-size: 12px; color: var(--et-grad-c); }
.conv { font-size: 12px; color: #34d399; font-weight: 600; }
.conv.bug { color: #fb7185; }
.muted { color: var(--et-text-muted); font-size: 12.5px; }
.ana { display: flex; flex-direction: column; gap: 12px; }
.ana-type {
  display: inline-flex; align-items: center; gap: 8px; align-self: flex-start;
  font-size: 14px; font-weight: 700; padding: 8px 14px; border-radius: 12px;
}
.ana-type.bug { color: #fb7185; background: rgba(251, 113, 133, 0.12); }
.ana-type.req { color: #34d399; background: rgba(52, 211, 153, 0.12); }
.ana-row { display: flex; gap: 12px; font-size: 13px; line-height: 1.6; }
.ana-row .k { flex-shrink: 0; width: 48px; color: var(--et-text-muted); }
.ana-foot { font-size: 11.5px; color: var(--et-text-muted); }
.prio { font-weight: 700; }
.prio.p0 { color: #fb7185; } .prio.p1 { color: #f97316; } .prio.p2 { color: #eab308; } .prio.p3 { color: #64748b; }
</style>