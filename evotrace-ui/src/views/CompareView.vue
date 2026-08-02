<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { storeToRefs } from 'pinia'
import FilterBar from '../components/FilterBar.vue'
import PageCard from '../components/PageCard.vue'
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
</script>

<template>
  <div>
    <FilterBar :loading="loading" :show-search="false" @search="runCompare">
      <el-form-item label="项目"><el-input v-model="project" style="width: 140px" /></el-form-item>
      <el-form-item label="基线版本">
        <el-select v-model="from" style="width: 140px"><el-option v-for="v in versionOptions" :key="v" :value="v" /></el-select>
      </el-form-item>
      <el-form-item label="目标版本">
        <el-select v-model="to" style="width: 140px"><el-option v-for="v in versionOptions" :key="v" :value="v" /></el-select>
      </el-form-item>
      <template #actions>
        <el-button type="primary" :loading="loading" @click="runCompare">生成对比报告</el-button>
      </template>
    </FilterBar>

    <PageCard>
      <el-alert type="info" :closable="false" show-icon class="compare-summary">
        <template #title>
          <span class="version-range">{{ report.fromVersion }} → {{ report.toVersion }}</span>
          <span v-if="usingDemo" class="demo-tag"><el-tag type="warning" size="small" effect="dark">演示数据</el-tag></span>
          <span class="stats-inline">
            {{ report.stats.commits }} 个提交 · {{ report.stats.filesChanged }} 个文件 ·
            <span class="add">+{{ report.stats.addLines }}</span> /
            <span class="del">-{{ report.stats.delLines }}</span> 行
          </span>
        </template>
        <el-button size="small" type="warning" plain style="margin-top: 8px"
                   :loading="noteLoading" @click="generateReleaseNotes">AI 生成发布说明</el-button>
      </el-alert>
    </PageCard>

    <el-dialog v-model="noteVisible" title="AI 发布说明" width="680px" top="8vh">
      <div class="note-meta" v-if="noteModel">生成模型：{{ noteModel === 'template' ? '模板降级（未配置 AI）' : noteModel }}</div>
      <pre class="note-content">{{ noteContent }}</pre>
      <template #footer>
        <el-button @click="noteVisible = false">关闭</el-button>
        <el-button type="primary" @click="copyNote">复制发布说明</el-button>
      </template>
    </el-dialog>

    <PageCard title="对比详情" no-padding style="margin-top: 16px">
      <el-tabs v-model="activeTab" class="compare-tabs">
        <el-tab-pane v-for="t in [{k:'apis',l:'接口'},{k:'dependencies',l:'依赖'},{k:'configs',l:'配置'},{k:'schemas',l:'DDL'}]" :key="t.k" :label="t.l" :name="t.k">
          <div class="tab-content">
            <el-table :data="report[t.k as 'apis']" stripe>
              <el-table-column prop="identityKey" label="清单项" min-width="280" />
              <el-table-column label="变化" width="100">
                <template #default="{ row }"><el-tag size="small" :type="flagType(row.changeFlag)">{{ flagLabel(row.changeFlag) }}</el-tag></template>
              </el-table-column>
              <el-table-column prop="before" label="变更前" min-width="200"><template #default="{ row }">{{ row.before ?? '—' }}</template></el-table-column>
              <el-table-column prop="after" label="变更后" min-width="200"><template #default="{ row }">{{ row.after ?? '—' }}</template></el-table-column>
            </el-table>
          </div>
        </el-tab-pane>
      </el-tabs>
    </PageCard>

    <PageCard v-if="compared && report.changes?.length" :title="'变更明细（' + report.changes.length + ')'" style="margin-top: 16px">
      <el-table :data="report.changes" stripe>
        <el-table-column prop="occurredAt" label="时间" width="200" />
        <el-table-column prop="type" label="类型" width="120" />
        <el-table-column prop="sha" label="提交" width="110" />
        <el-table-column prop="author" label="作者" width="110" />
        <el-table-column prop="summary" label="AI 摘要" min-width="300" />
      </el-table>
    </PageCard>
  </div>
</template>

<style scoped>
.compare-summary :deep(.el-alert__title) {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.demo-tag {
  margin: 0 8px;
}

.version-range {
  font-weight: 700;
  font-size: 15px;
}

.note-meta {
  color: var(--et-text-secondary);
  font-size: 12px;
  margin-bottom: 8px;
}

.note-content {
  background: var(--et-bg-muted, #f5f7fa);
  border: 1px solid var(--et-border, #e4e7ed);
  border-radius: 6px;
  padding: 12px;
  max-height: 55vh;
  overflow: auto;
  white-space: pre-wrap;
  font-size: 13px;
  line-height: 1.7;
}

.stats-inline {
  font-weight: 400;
  font-size: 13px;
  color: var(--et-text-secondary);
}

.add { color: #10b981; font-weight: 600; }
.del { color: #ef4444; font-weight: 600; }

.compare-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 20px;
}

.tab-content {
  padding: 0 20px 20px;
}
</style>
