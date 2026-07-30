<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { subscriptionApi } from '../api'

interface SubRule {
  id: number; name: string; channel: string; enabled: boolean; createdAt: string
}
interface NotifLog {
  id: number; channel: string; title: string; status: string; errorMsg?: string; createdAt: string
}

const subscriptions = ref<SubRule[]>([])
const logs = ref<NotifLog[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const form = ref({
  name: '', workspaceId: 1, userId: 1,
  filter: { projectKey: 'mall', eventTypes: ['DDL_CHANGE'], filePattern: '' },
  channel: 'FEISHU', webhookUrl: ''
})

const eventTypeOptions = ['CODE_COMMIT', 'MR_MERGED', 'RELEASE_TAG', 'DDL_CHANGE', 'CONFIG_CHANGE', 'DEPLOY_RECORD']
const channelOptions = [
  { label: '飞书', value: 'FEISHU' },
  { label: '钉钉', value: 'DINGTALK' },
  { label: '企业微信', value: 'WECHAT' },
  { label: 'Webhook', value: 'WEBHOOK' }
]

async function load() {
  loading.value = true
  try {
    const [subs, l] = await Promise.all([subscriptionApi.list(), subscriptionApi.logs()])
    subscriptions.value = subs
    logs.value = l
  } catch { /* server not ready */ }
  loading.value = false
}

async function create() {
  try {
    await subscriptionApi.create({
      name: form.value.name,
      workspaceId: form.value.workspaceId,
      userId: form.value.userId,
      filter: form.value.filter,
      channel: form.value.channel,
      webhookUrl: form.value.webhookUrl
    })
    ElMessage.success('订阅规则已创建')
    dialogVisible.value = false
    load()
  } catch { ElMessage.error('创建失败') }
}

async function toggleRule(id: number, enabled: boolean) {
  try {
    await subscriptionApi.toggle(id, enabled)
    ElMessage.success(enabled ? '已启用' : '已停用')
    load()
  } catch { ElMessage.error('操作失败') }
}

async function deleteRule(id: number) {
  try {
    await subscriptionApi.delete(id)
    ElMessage.success('已删除')
    load()
  } catch { ElMessage.error('删除失败') }
}

onMounted(load)
</script>

<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span>变更订阅规则</span>
          <el-button type="primary" @click="dialogVisible = true">新建订阅</el-button>
        </div>
      </template>
      <el-table :data="subscriptions" stripe v-loading="loading">
        <el-table-column prop="name" label="规则名称" width="180" />
        <el-table-column label="通知渠道" width="120">
          <template #default="{ row }">
            <el-tag size="small">{{ { FEISHU: '飞书', DINGTALK: '钉钉', WECHAT: '企业微信', WEBHOOK: 'Webhook', EMAIL: '邮件' }[row.channel] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="(v: boolean) => toggleRule(row.id, v)" />
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button size="small" type="danger" plain @click="deleteRule(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && subscriptions.length === 0" description="暂无订阅规则" :image-size="60" />
    </el-card>

    <el-card shadow="never" style="margin-top: 16px">
      <template #header>通知日志</template>
      <el-table :data="logs" stripe v-loading="loading" size="small">
        <el-table-column prop="channel" label="渠道" width="100" />
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'SENT' ? 'success' : 'danger'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="180" />
      </el-table>
      <el-empty v-if="!loading && logs.length === 0" description="暂无通知记录" :image-size="60" />
    </el-card>

    <!-- Create subscription dialog -->
    <el-dialog v-model="dialogVisible" title="新建变更订阅" width="560px">
      <el-form label-width="90px">
        <el-form-item label="规则名称"><el-input v-model="form.name" placeholder="如：支付模块DDL变更通知" /></el-form-item>
        <el-form-item label="通知渠道">
          <el-select v-model="form.channel" style="width: 100%">
            <el-option v-for="c in channelOptions" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.channel === 'WEBHOOK'" label="Webhook URL">
          <el-input v-model="form.webhookUrl" placeholder="https://..." />
        </el-form-item>
        <el-form-item label="项目">
          <el-input v-model="form.filter.projectKey" placeholder="mall" style="width: 200px" />
        </el-form-item>
        <el-form-item label="事件类型">
          <el-select v-model="form.filter.eventTypes" multiple placeholder="选择事件类型" style="width: 100%">
            <el-option v-for="t in eventTypeOptions" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="文件匹配">
          <el-input v-model="form.filter.filePattern" placeholder="如: **/payment/** (glob格式，可选)" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="create">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>
