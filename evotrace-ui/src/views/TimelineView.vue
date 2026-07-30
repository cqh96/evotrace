<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { timelineApi, type TimelineEvent } from '../api'

const filters = ref({ project: 'mall', app: '', type: '', range: [] as string[] })

const events = ref<TimelineEvent[]>([])
const loading = ref(false)
const error = ref('')

const typeMeta: Record<string, { label: string; color: string }> = {
  CODE_COMMIT: { label: '代码提交', color: '#409eff' },
  MR_MERGED: { label: 'MR 合并', color: '#67c23a' },
  RELEASE_TAG: { label: '版本发布', color: '#e6a23c' },
  CONFIG_CHANGE: { label: '配置变更', color: '#909399' },
  DDL_CHANGE: { label: 'DDL 变更', color: '#f56c6c' },
  DEPLOY_RECORD: { label: '部署记录', color: '#9b59b6' },
  INVENTORY_REPORT: { label: '清单上报', color: '#00bcd4' }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await timelineApi.query(filters.value.project, {
      app: filters.value.app,
      type: filters.value.type
    })
    events.value = data || []
  } catch {
    error.value = '加载时间线失败，请确认服务端已启动'
    events.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <el-card shadow="never">
      <el-form inline>
        <el-form-item label="项目"><el-input v-model="filters.project" style="width: 160px" /></el-form-item>
        <el-form-item label="应用"><el-input v-model="filters.app" placeholder="全部" style="width: 160px" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="filters.type" placeholder="全部" clearable style="width: 140px">
            <el-option v-for="(m, k) in typeMeta" :key="k" :label="m.label" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" @click="load">查询</el-button></el-form-item>
      </el-form>
    </el-card>
    <el-card shadow="never" style="margin-top: 16px" v-loading="loading">
      <el-alert v-if="error" type="warning" :closable="false" style="margin-bottom: 12px">{{ error }}</el-alert>
      <el-empty v-if="!loading && events.length === 0" description="暂无演化事件" />
      <el-timeline v-else style="padding-left: 8px">
        <el-timeline-item
          v-for="e in events"
          :key="e.eventId"
          :timestamp="e.occurredAt"
          placement="top"
          :color="typeMeta[e.eventType]?.color"
        >
          <el-card shadow="hover">
            <div class="event-head">
              <el-tag size="small" :color="typeMeta[e.eventType]?.color" effect="dark" style="border: none">
                {{ typeMeta[e.eventType]?.label ?? e.eventType }}
              </el-tag>
              <span class="app">{{ e.appKey }}</span>
              <code v-if="e.commitSha" class="sha">{{ e.commitSha }}</code>
              <span class="author">{{ e.author }}</span>
              <el-link v-if="e.iterationTitle" type="primary" style="margin-left: auto">{{ e.iterationTitle }}</el-link>
            </div>
            <div v-if="e.summaryStatus === 'DONE'" class="summary">
              <el-tag size="small" type="warning" effect="plain">AI</el-tag> {{ e.summary }}
            </div>
            <div v-else-if="e.summaryStatus === 'PENDING'" class="summary pending">AI 摘要生成中…</div>
          </el-card>
        </el-timeline-item>
      </el-timeline>
    </el-card>
  </div>
</template>

<style scoped>
.event-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap }
.app { font-weight: 600 }
.sha { background: #f5f7fa; padding: 1px 6px; border-radius: 4px; font-size: 12px }
.author { color: #909399 }
.summary { margin-top: 8px; color: #606266 }
.pending { color: #c0c4cc; font-style: italic }
</style>
