<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { releaseApi, type CompareReport } from '../api'

const project = ref('mall')
const from = ref('v2.3.1')
const to = ref('v2.5.0')
const versions = ref<string[]>(['v2.3.1', 'v2.4.0', 'v2.4.9', 'v2.5.0'])
const activeTab = ref('apis')
const compared = ref(false)
const loading = ref(false)

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
    }
  } catch {
    // 服务端未就绪时保留演示数据
  }
}

async function runCompare() {
  loading.value = true
  try {
    report.value = await releaseApi.compare(project.value, from.value, to.value)
    compared.value = true
  } catch {
    // 保留当前展示
  } finally {
    loading.value = false
  }
}

onMounted(loadVersions)
</script>

<template>
  <div>
    <el-card shadow="never">
      <el-form inline>
        <el-form-item label="项目"><el-input v-model="project" style="width: 140px" /></el-form-item>
        <el-form-item label="基线版本">
          <el-select v-model="from" style="width: 140px"><el-option v-for="v in versionOptions" :key="v" :value="v" /></el-select>
        </el-form-item>
        <el-form-item label="目标版本">
          <el-select v-model="to" style="width: 140px"><el-option v-for="v in versionOptions" :key="v" :value="v" /></el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" :loading="loading" @click="runCompare">生成对比报告</el-button></el-form-item>
      </el-form>
      <el-alert type="info" :closable="false">
        {{ report.fromVersion }} → {{ report.toVersion }}：{{ report.stats.commits }} 个提交，
        {{ report.stats.filesChanged }} 个文件，+{{ report.stats.addLines }} / -{{ report.stats.delLines }} 行
        <el-button size="small" type="warning" plain style="margin-left: 12px">AI 生成发布说明</el-button>
      </el-alert>
    </el-card>
    <el-card shadow="never" style="margin-top: 16px">
      <el-tabs v-model="activeTab">
        <el-tab-pane v-for="t in [{k:'apis',l:'接口'},{k:'dependencies',l:'依赖'},{k:'configs',l:'配置'},{k:'schemas',l:'DDL'}]" :key="t.k" :label="t.l" :name="t.k">
          <el-table :data="report[t.k as 'apis']" stripe>
            <el-table-column prop="identityKey" label="清单项" min-width="280" />
            <el-table-column label="变化" width="100">
              <template #default="{ row }"><el-tag size="small" :type="flagType(row.changeFlag)">{{ flagLabel(row.changeFlag) }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="before" label="变更前" min-width="200"><template #default="{ row }">{{ row.before ?? '—' }}</template></el-table-column>
            <el-table-column prop="after" label="变更后" min-width="200"><template #default="{ row }">{{ row.after ?? '—' }}</template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
    <el-card v-if="compared && report.changes?.length" shadow="never" style="margin-top: 16px">
      <template #header>变更明细（{{ report.changes.length }}）</template>
      <el-table :data="report.changes" stripe>
        <el-table-column prop="occurredAt" label="时间" width="200" />
        <el-table-column prop="type" label="类型" width="120" />
        <el-table-column prop="sha" label="提交" width="110" />
        <el-table-column prop="author" label="作者" width="110" />
        <el-table-column prop="summary" label="AI 摘要" min-width="300" />
      </el-table>
    </el-card>
  </div>
</template>
