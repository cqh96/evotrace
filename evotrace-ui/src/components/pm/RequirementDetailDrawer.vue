<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { pmApi, traceApi, type Requirement, type RequirementDetail, type StatusHistoryEntry, type RequirementOverview } from '../../api'
import RequirementDetailForm from './RequirementDetailForm.vue'
import MarkdownEditor from './MarkdownEditor.vue'
import RequirementTaskPanel from './RequirementTaskPanel.vue'
import PrototypeEditor from './PrototypeEditor.vue'
import TraceGraphPanel from '../trace/TraceGraphPanel.vue'

const props = defineProps<{
  modelValue: boolean
  projectKey: string
  requirement: Requirement
  aiUsable: boolean
}>()

const emit = defineEmits<{ 'update:modelValue': [boolean]; refreshed: [] }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

const activeTab = ref('panorama')
const detail = ref<RequirementDetail | null>(null)
const loading = ref(false)
const protoEditorVisible = ref(false)
const statusHistory = ref<StatusHistoryEntry[]>([])
const historyLoading = ref(false)
const lifecycleVisible = ref(false)

// 全景（Trace Core / 需求全景）
const overview = ref<RequirementOverview | null>(null)
const overviewLoading = ref(false)

const statusLabels: Record<string, string> = { DRAFT: '草稿', REVIEW: '评审中', DEVELOPING: '开发中', TESTING: '测试中', DONE: '已完成' }
const statusColors: Record<string, string> = { DRAFT: 'info', REVIEW: 'warning', DEVELOPING: 'primary', TESTING: 'danger', DONE: 'success' }

async function load() {
  loading.value = true
  try {
    detail.value = (await pmApi.detail(props.projectKey, props.requirement.id)) ?? null
  } catch {
    detail.value = null
  } finally {
    loading.value = false
  }
}

async function loadOverview() {
  overviewLoading.value = true
  try {
    overview.value = (await traceApi.requirementOverview(props.projectKey, props.requirement.id)) ?? null
  } catch {
    overview.value = null
  } finally {
    overviewLoading.value = false
  }
}

async function loadHistory() {
  historyLoading.value = true
  try {
    statusHistory.value = (await pmApi.statusHistory(props.projectKey, props.requirement.id)) ?? []
  } catch {
    statusHistory.value = []
  } finally {
    historyLoading.value = false
  }
}

function onTabChange(name: string | number) {
  if (name === 'panorama' && !overview.value) loadOverview()
  if (name === 'lifecycle' && !lifecycleVisible.value) {
    lifecycleVisible.value = true
    loadHistory()
  }
}

function onSaved() {
  emit('refreshed')
  load()
}

function fmtTime(v?: string | null): string {
  if (!v) return ''
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return v
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function compPct() {
  return Math.round((overview.value?.completeness.score ?? 0) * 100)
}
function compColor(pct: number) {
  return pct >= 80 ? '#34d399' : pct >= 50 ? '#fbbf24' : '#fb7185'
}
function onOpenNode(n: { type: string; id: string }) {
  ElMessage.info(`${n.type} · ${n.id}`)
}

watch(
  () => [props.modelValue, props.requirement?.id] as const,
  ([open]) => {
    if (open) {
      activeTab.value = 'panorama'
      load()
      loadOverview()
    }
  }
)
</script>

<template>
  <el-drawer v-model="visible" :title="`需求工作台：${requirement.title}`" size="860px" class="req-drawer">
    <div v-loading="loading" class="drawer-head">
      <template v-if="detail">
        <div class="head-tags">
          <el-tag size="small" :type="statusColors[detail.status] as any">{{ statusLabels[detail.status] }}</el-tag>
          <el-tag size="small" :type="detail.priority === 'P0' ? 'danger' : detail.priority === 'P1' ? 'warning' : 'info'">{{ detail.priority }}</el-tag>
          <el-tag v-if="detail.targetVersion" size="small">目标版本 {{ detail.targetVersion }}</el-tag>
          <el-tag v-if="detail.techLead" size="small">技术负责人 {{ detail.techLead }}</el-tag>
          <el-tag v-if="detail.iterationTitle" size="small" type="success">{{ detail.iterationTitle }}</el-tag>
        </div>
        <div class="head-meta">
          <span v-if="detail.pm">PM：{{ detail.pm }}</span>
          <span v-if="detail.assignee">负责人：{{ detail.assignee }}</span>
          <span v-if="detail.estimateDays != null">预估 {{ detail.estimateDays }} 人天</span>
          <span v-if="detail.docVersion">文档 v{{ detail.docVersion }}</span>
          <span v-if="detail.taskTotal != null">任务 {{ detail.taskDone ?? 0 }}/{{ detail.taskTotal }}</span>
          <span v-if="detail.prototypeUpdatedAt">原型更新 {{ String(detail.prototypeUpdatedAt).slice(0, 10) }}</span>
        </div>
      </template>
    </div>

    <el-tabs v-model="activeTab" class="drawer-tabs" @tab-change="onTabChange">
      <el-tab-pane label="全景" name="panorama">
        <div v-loading="overviewLoading" class="panorama-pane">
          <template v-if="overview">
            <!-- 完整度 -->
            <div class="pano-comp">
              <div class="ring" :style="{ '--c': compColor(compPct()), '--pct': compPct() }">
                <span class="ring-val">{{ compPct() }}<i>%</i></span>
                <span class="ring-label">完整度</span>
              </div>
              <div class="checks">
                <div v-for="c in overview.completeness.checks" :key="c.key" class="check" :class="{ pass: c.passed }">
                  <span class="check-ic">{{ c.passed ? '✓' : '✕' }}</span>
                  <span class="check-label">{{ c.detail || c.key }}</span>
                </div>
              </div>
            </div>

            <!-- 链路路径 -->
            <div class="pano-block">
              <h4>链路路径</h4>
              <TraceGraphPanel
                :project-key="projectKey"
                root-type="REQUIREMENT"
                :root-id="String(requirement.id)"
                :path="overview.tracePath as any"
                @open-node="onOpenNode"
              />
            </div>

            <!-- 关联 -->
            <div class="pano-block">
              <h4>关联</h4>
              <div class="link-sections">
                <div class="link-sec">
                  <div class="ls-head">代码 <el-tag size="small">{{ overview.links.changes.length }}</el-tag></div>
                  <div v-if="overview.links.changes.length" class="link-list">
                    <div v-for="ch in overview.links.changes" :key="ch.linkId" class="link-item">
                      <span class="sha">{{ ch.commitSha || ch.eventId }}</span>
                      <span class="msg">{{ ch.message || '' }}</span>
                      <span class="conf" :class="ch.confidence >= 90 ? 'high' : 'low'">{{ ch.confidence }}%</span>
                    </div>
                  </div>
                  <div v-else class="link-empty">无代码关联 — 提交消息请使用 {{ requirement.reqKey || 'REQ-{id}' }} 前缀</div>
                </div>

                <div class="link-sec">
                  <div class="ls-head">测试用例 <el-tag size="small">{{ overview.links.testCases.length }}</el-tag></div>
                  <div v-if="overview.links.testCases.length" class="link-list">
                    <div v-for="tc in overview.links.testCases" :key="tc.id" class="link-item">
                      <span class="msg">{{ tc.title }}</span>
                      <span class="conf high">{{ tc.priority }}</span>
                    </div>
                  </div>
                  <div v-else class="link-empty">无测试用例 — 去测试面板创建</div>
                </div>

                <div class="link-sec">
                  <div class="ls-head">缺陷 <el-tag size="small">{{ overview.links.bugs.length }}</el-tag></div>
                  <div v-if="overview.links.bugs.length" class="link-list">
                    <div v-for="b in overview.links.bugs" :key="b.id" class="link-item">
                      <span class="msg">{{ b.title }}</span>
                      <span class="conf" :class="b.severity === 'P0' || b.severity === 'P1' ? 'low' : 'high'">{{ b.severity }}</span>
                    </div>
                  </div>
                  <div v-else class="link-empty">无缺陷</div>
                </div>

                <div class="link-sec">
                  <div class="ls-head">发布版本 <el-tag size="small">{{ overview.links.releases.length }}</el-tag></div>
                  <div v-if="overview.links.releases.length" class="link-list">
                    <div v-for="r in overview.links.releases" :key="r.id" class="link-item">
                      <span class="msg">{{ r.version }}</span>
                      <span class="conf high">{{ r.linkType }}</span>
                    </div>
                  </div>
                  <div v-else class="link-empty">未纳入版本</div>
                </div>
              </div>
            </div>
          </template>
          <el-empty v-else description="暂无全景数据" :image-size="60" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="详情" name="detail">
        <RequirementDetailForm
          v-if="detail"
          :project-key="projectKey"
          :requirement="detail"
          :ai-usable="aiUsable"
          @saved="onSaved"
        />
        <el-empty v-else description="加载失败" :image-size="60" />
      </el-tab-pane>

      <el-tab-pane label="文档" name="document">
        <MarkdownEditor :project-key="projectKey" :requirement-id="requirement.id" :ai-usable="aiUsable" />
      </el-tab-pane>

      <el-tab-pane label="原型" name="prototype">
        <div class="proto-entry">
          <div class="proto-entry-desc">
            <h4>线框原型（内置编辑器）</h4>
            <p>拖拽组件搭建多页面原型，支持页面跳转、预览与导出独立 HTML；也可用 AI 一键生成初稿后微调。</p>
          </div>
          <div class="proto-entry-actions">
            <el-button type="primary" @click="protoEditorVisible = true">打开原型编辑器</el-button>
            <el-button v-if="requirement.prototypeUrl" tag="a" :href="requirement.prototypeUrl" target="_blank" plain>
              外部原型链接 ↗
            </el-button>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="任务" name="tasks">
        <RequirementTaskPanel :project-key="projectKey" :requirement-id="requirement.id" />
      </el-tab-pane>

      <el-tab-pane label="生命周期" name="lifecycle">
        <div v-loading="historyLoading" class="lifecycle-pane">
          <template v-if="statusHistory.length">
            <el-steps direction="vertical" :active="statusHistory.length" class="req-history-steps">
              <el-step
                v-for="(h, idx) in statusHistory"
                :key="idx"
                :status="idx === statusHistory.length - 1 ? (h.leftAt || idx === 0 ? 'process' : 'finish') : 'finish'"
                :title="statusLabels[h.status] || h.status"
              >
                <template #description>
                  <div class="hist-item">
                    <div class="hist-meta">
                      <el-tag size="small" :type="(statusColors[h.status] as any)">{{ statusLabels[h.status] || h.status }}</el-tag>
                      <span v-if="h.fromStatus" class="hist-from">← {{ statusLabels[h.fromStatus] || h.fromStatus }}</span>
                      <span v-if="h.actor" class="hist-actor">操作人：{{ h.actor }}</span>
                    </div>
                    <div class="hist-time">
                      <span>{{ fmtTime(h.enteredAt) }}</span>
                      <span v-if="h.leftAt" class="hist-range">→ {{ fmtTime(h.leftAt) }}</span>
                      <span v-if="h.durationDays != null" class="hist-duration">停留 {{ h.durationDays }} 天</span>
                      <span v-else class="hist-duration">当前驻留</span>
                    </div>
                  </div>
                </template>
              </el-step>
            </el-steps>
          </template>
          <el-empty v-else description="暂无状态流转记录" :image-size="60" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <PrototypeEditor
      v-model="protoEditorVisible"
      :project-key="projectKey"
      :requirement-id="requirement.id"
      :requirement-title="requirement.title"
      :ai-usable="aiUsable"
      @saved="onSaved"
    />
  </el-drawer>
</template>

<style scoped>
.drawer-head {
  margin-bottom: 6px;
  padding: 6px 22px 14px;
  border-bottom: 1px solid var(--et-border);
}
.head-tags { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 10px }
.head-meta {
  display: flex;
  align-items: center;
  gap: 16px;
  font-size: 12px;
  color: var(--et-text-muted);
  flex-wrap: wrap;
}
.head-meta span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.head-meta span::before {
  content: '';
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--et-primary);
  opacity: 0.7;
}
.drawer-tabs :deep(.el-tabs__header) { margin-bottom: 16px; padding: 0 22px; }
.drawer-tabs :deep(.el-tab-pane) { padding: 0 22px 22px; }
.proto-entry {
  display: flex; justify-content: space-between; align-items: center; gap: 16px;
  padding: 22px 24px;
  border: 1px dashed var(--et-hover-border);
  border-radius: var(--et-radius-lg);
  background: var(--et-card-bg);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  transition: border-color 0.2s, box-shadow 0.2s;
}
.proto-entry:hover { border-color: var(--et-primary); box-shadow: var(--et-shadow); }
.proto-entry-desc h4 {
  margin: 0 0 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.proto-entry-desc h4::before {
  content: '';
  width: 4px;
  height: 14px;
  border-radius: 2px;
  background: linear-gradient(180deg, var(--et-grad-a), var(--et-grad-c));
}
.proto-entry-desc p { margin: 0; font-size: 13px; color: var(--et-text-muted); line-height: 1.7 }
.proto-entry-actions { display: flex; gap: 8px; flex-shrink: 0 }
.lifecycle-pane { padding: 4px 0 8px; min-height: 200px }
.req-history-steps { padding: 8px 4px }
.hist-item { display: flex; flex-direction: column; gap: 4px; padding-bottom: 6px }
.hist-meta { display: flex; align-items: center; gap: 10px; flex-wrap: wrap }
.hist-from { color: var(--et-text-muted); font-size: 12px }
.hist-actor { color: var(--et-text-muted); font-size: 12px }
.hist-time { display: flex; align-items: center; gap: 10px; font-size: 12px; color: var(--et-text-muted) }
.hist-duration { color: var(--et-primary); font-size: 12px }
.panorama-pane { min-height: 200px; }
.pano-comp { display: flex; align-items: center; gap: 22px; padding: 16px 18px; border: 1px solid var(--et-border); border-radius: 14px; background: var(--et-bg-muted); margin-bottom: 14px; }
.ring { --c: #fbbf24; width: 74px; height: 74px; border-radius: 50%; background: conic-gradient(var(--c) calc(var(--pct, 0) * 1%), var(--et-bg-muted) 0); display: flex; flex-direction: column; align-items: center; justify-content: center; position: relative; flex-shrink: 0; }
.ring::before { content: ''; position: absolute; inset: 7px; border-radius: 50%; background: var(--et-card-solid); }
.ring-val { position: relative; font-size: 20px; font-weight: 800; line-height: 1; }
.ring-val i { font-style: normal; font-size: 11px; color: var(--et-text-muted); margin-left: 1px; }
.ring-label { position: relative; font-size: 10px; color: var(--et-text-muted); margin-top: 2px; }
.checks { flex: 1; display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.check { display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--et-text-muted); }
.check.pass { color: #34d399; }
.check-ic { width: 18px; height: 18px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 11px; flex-shrink: 0; background: rgba(251, 113, 133, 0.12); color: #fb7185; }
.check.pass .check-ic { background: rgba(52, 211, 153, 0.12); color: #34d399; }
.pano-block { margin-bottom: 16px; }
.pano-block h4 { margin: 0 0 10px; font-size: 14px; display: flex; align-items: center; gap: 8px; }
.pano-block h4::before { content: ''; width: 4px; height: 14px; border-radius: 2px; background: linear-gradient(180deg, var(--et-grad-a), var(--et-grad-c)); }
.link-sections { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
@media (max-width: 720px) { .link-sections { grid-template-columns: 1fr; } }
.link-sec { border: 1px solid var(--et-border); border-radius: 12px; padding: 10px 12px; background: var(--et-bg-muted); }
.ls-head { display: flex; align-items: center; gap: 8px; font-size: 12.5px; font-weight: 700; margin-bottom: 8px; }
.link-list { display: flex; flex-direction: column; gap: 6px; }
.link-item { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.sha { font-family: ui-monospace, monospace; color: var(--et-grad-c); flex-shrink: 0; }
.msg { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--et-text-secondary); }
.conf { font-size: 10.5px; font-weight: 700; padding: 2px 7px; border-radius: 20px; flex-shrink: 0; }
.conf.high { color: #34d399; background: rgba(52, 211, 153, 0.12); }
.conf.low { color: #fb7185; background: rgba(251, 113, 133, 0.12); }
.link-empty { font-size: 12px; color: var(--et-text-muted); padding: 4px 0; }
</style>
