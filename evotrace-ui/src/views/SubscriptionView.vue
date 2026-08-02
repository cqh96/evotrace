<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Bell, Plus } from '@element-plus/icons-vue'
import FilterBar from '../components/FilterBar.vue'
import PageCard from '../components/PageCard.vue'
import { subscriptionApi } from '../api'
import { useProjectStore } from '../stores/project'

interface SubRule { id: number; name: string; channel: string; enabled: boolean; createdAt: string }
interface NotifLog { id: number; channel: string; title: string; status: string; errorMsg?: string; createdAt: string }

const subscriptions = ref<SubRule[]>([])
const logs = ref<NotifLog[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const projectStore = useProjectStore()
const form = ref({ name: '', workspaceId: 1, userId: 2, filter: { projectKey: projectStore.current, eventTypes: ['DDL_CHANGE'], filePattern: '' }, channel: 'FEISHU', webhookUrl: '' })

const eventOptions = ['CODE_COMMIT','MR_MERGED','RELEASE_TAG','DDL_CHANGE','CONFIG_CHANGE','DEPLOY_RECORD']
const channelMap: Record<string, string> = { FEISHU: '飞书', DINGTALK: '钉钉', WECHAT: '企业微信', WEBHOOK: 'Webhook', EMAIL: '邮件' }

async function load() {
  loading.value = true
  try { const [s, l] = await Promise.all([subscriptionApi.list(), subscriptionApi.logs()]); subscriptions.value = s; logs.value = l } catch {}
  loading.value = false
}

async function create() {
  try { await subscriptionApi.create(form.value); ElMessage.success('已创建'); dialogVisible.value = false; load() } catch { ElMessage.error('创建失败') }
}

async function toggleRule(id: number, enabled: boolean) { try { await subscriptionApi.toggle(id, enabled); load() } catch {} }
async function deleteRule(id: number) { try { await subscriptionApi.delete(id); ElMessage.success('已删除'); load() } catch {} }

onMounted(load)
</script>

<template>
  <div>
    <FilterBar :loading="loading" @search="load">
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="dialogVisible = true">新建订阅</el-button>
      </template>
    </FilterBar>

    <PageCard title="订阅规则" style="margin-top: 16px" v-loading="loading">
      <el-empty v-if="!loading && subscriptions.length === 0" description="暂无订阅，点击上方按钮创建" :image-size="60" />
      <div v-else class="sub-list">
        <div v-for="s in subscriptions" :key="s.id" class="sub-item">
          <div class="sub-info">
            <span class="sub-name">{{ s.name }}</span>
            <el-tag size="small" effect="plain" round>{{ channelMap[s.channel] || s.channel }}</el-tag>
            <span class="sub-time">创建于 {{ s.createdAt?.substring(0, 10) }}</span>
          </div>
          <div class="sub-actions">
            <el-switch :model-value="s.enabled" @change="(v: boolean) => toggleRule(s.id, v)" size="small" />
            <el-button size="small" type="danger" text @click="deleteRule(s.id)">删除</el-button>
          </div>
        </div>
      </div>
    </PageCard>

    <PageCard title="通知记录" style="margin-top: 16px" v-loading="loading">
      <el-empty v-if="!loading && logs.length === 0" description="暂无通知记录" :image-size="60" />
      <el-table v-else :data="logs" size="small" stripe>
        <el-table-column label="渠道" width="80"><template #default="{row}"><el-tag size="small">{{ channelMap[row.channel]||row.channel }}</el-tag></template></el-table-column>
        <el-table-column prop="title" label="标题" min-width="240" />
        <el-table-column label="状态" width="80"><template #default="{row}"><el-tag size="small" :type="row.status==='SENT'?'success':'danger'">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="createdAt" label="时间" width="180" />
      </el-table>
    </PageCard>

    <el-dialog v-model="dialogVisible" title="新建变更订阅" width="520px">
      <el-form label-width="80px">
        <el-form-item label="名称"><el-input v-model="form.name" placeholder="如：支付模块DDL变更通知" /></el-form-item>
        <el-form-item label="渠道">
          <el-select v-model="form.channel"><el-option v-for="(v,k) in channelMap" :key="k" :label="v" :value="k" /></el-select>
        </el-form-item>
        <el-form-item v-if="form.channel==='WEBHOOK'" label="URL"><el-input v-model="form.webhookUrl" placeholder="https://..." /></el-form-item>
        <el-form-item label="项目"><el-input v-model="form.filter.projectKey" style="width:180px" /></el-form-item>
        <el-form-item label="事件类型"><el-select v-model="form.filter.eventTypes" multiple><el-option v-for="t in eventOptions" :key="t" :value="t" /></el-select></el-form-item>
        <el-form-item label="文件模式"><el-input v-model="form.filter.filePattern" placeholder="**/payment/** (glob)" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" @click="create">创建</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sub-list { display: flex; flex-direction: column }
.sub-item { display: flex; align-items: center; justify-content: space-between; padding: 14px 0; border-bottom: 1px solid var(--et-border) }
.sub-item:last-child { border: none }
.sub-info { display: flex; align-items: center; gap: 12px }
.sub-name { font-weight: 600; color: var(--et-text) }
.sub-time { color: var(--et-text-muted); font-size: 12px }
.sub-actions { display: flex; align-items: center; gap: 12px }
</style>
