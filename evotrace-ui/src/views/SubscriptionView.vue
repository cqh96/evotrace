<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Bell, Plus, RefreshRight, Delete, Promotion } from '@element-plus/icons-vue'
import { subscriptionApi, type SubscriptionRule } from '../api'
import { useProjectStore } from '../stores/project'

interface NotifLog { id: number; channel: string; title: string; status: string; errorMsg?: string; createdAt: string }

const subscriptions = ref<SubscriptionRule[]>([])
const logs = ref<NotifLog[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const projectStore = useProjectStore()
const form = ref({ name: '', workspaceId: 1, userId: 2, filter: { projectKey: projectStore.current, eventTypes: ['DDL_CHANGE'], filePattern: '' }, channel: 'FEISHU', webhookUrl: '' })

const eventOptions = ['CODE_COMMIT','MR_MERGED','RELEASE_TAG','DDL_CHANGE','CONFIG_CHANGE','DEPLOY_RECORD']
const channelMap: Record<string, string> = { FEISHU: '飞书', DINGTALK: '钉钉', WECHAT: '企业微信', WEBHOOK: 'Webhook', EMAIL: '邮件' }

/* ---------- 展示辅助（不参与业务逻辑） ---------- */
const sessionChips = ref<Record<number, { projectKey: string; eventTypes: string[] }>>({})
const logoGrads = ['g-indigo', 'g-emerald', 'g-amber', 'g-violet', 'g-cyan']
const eventLabels: Record<string, string> = {
  CODE_COMMIT: '代码提交', MR_MERGED: '合并请求', RELEASE_TAG: '发布标签',
  DDL_CHANGE: 'DDL 变更', CONFIG_CHANGE: '配置变更', DEPLOY_RECORD: '部署记录'
}
const enabledCount = computed(() => subscriptions.value.filter((s) => s.enabled).length)

function logoCls(i: number) { return logoGrads[i % logoGrads.length] }
function channelTagCls(c: string) {
  const map: Record<string, string> = {
    FEISHU: 'et-tag-info', DINGTALK: 'et-tag-api', WECHAT: 'et-tag-test',
    WEBHOOK: 'et-tag-review', EMAIL: 'et-tag-rel'
  }
  return map[c] ?? 'et-tag-info'
}
function channelAccent(c: string) {
  const map: Record<string, string> = {
    FEISHU: '#0891b2', DINGTALK: '#b45309', WECHAT: '#059669',
    WEBHOOK: '#6d4fd6', EMAIL: '#d6336c'
  }
  return map[c] ?? '#4f5ad1'
}
function trackCreated() {
  const f = form.value.filter
  sessionChips.value[Date.now()] = {
    projectKey: typeof f.projectKey === 'string' ? f.projectKey : '',
    eventTypes: Array.isArray(f.eventTypes) ? (f.eventTypes as string[]) : []
  }
}
function fmtTime(t?: string) { return t ? t.replace('T', ' ').substring(0, 16) : '' }

async function load() {
  loading.value = true
  try {
    const [s, l] = await Promise.all([subscriptionApi.list(), subscriptionApi.logs()])
    subscriptions.value = s; logs.value = l
    // 已有订阅的筛选信息：从后端返回的 filter 填充（key 用订阅 id，保证唯一且加载即显示）
    const chips: Record<number, { projectKey: string; eventTypes: string[] }> = {}
    for (const sub of s) {
      const f = sub.filter
      chips[sub.id] = {
        projectKey: typeof f?.projectKey === 'string' ? f.projectKey : '',
        eventTypes: Array.isArray(f?.eventTypes) ? (f.eventTypes as string[]) : []
      }
    }
    sessionChips.value = chips
  } catch {}
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
  <div class="page">
    <!-- Hero -->
    <section class="et-hero rise" style="--d: 0.02s">
      <div class="hero-left">
        <h2>变更订阅</h2>
        <div class="et-hero-sub">订阅感兴趣的变更事件，实时推送到飞书、钉钉或 Webhook</div>
        <div class="hero-chips">
          <span class="chip-mini">订阅规则 <b>{{ subscriptions.length }}</b></span>
          <span class="chip-mini">生效中 <b>{{ enabledCount }}</b></span>
          <span class="chip-mini live"><span class="et-pulse"></span>推送实时</span>
        </div>
      </div>
      <div class="hero-right">
        <el-button type="primary" :icon="Plus" @click="dialogVisible = true">新建订阅</el-button>
      </div>
    </section>

    <!-- 订阅规则（分组卡片） -->
    <section class="et-card rise loading-sec" style="--d: 0.08s" v-loading="loading">
      <div class="et-card-head">
        <span class="et-tic"><el-icon :size="15"><Bell /></el-icon></span>
        <div>
          <div class="et-card-title">订阅规则</div>
          <div class="et-card-sub">匹配条件 · 推送渠道 · 实时生效</div>
        </div>
        <div class="right">
          <button class="et-link-more" @click="load"><el-icon :size="13"><RefreshRight /></el-icon>刷新</button>
          <el-button type="primary" size="small" :icon="Plus" @click="dialogVisible = true">新建订阅</el-button>
        </div>
      </div>
      <div class="et-card-body">
        <div v-if="!loading && subscriptions.length === 0" class="et-empty-hint">
          <div class="et-empty-ic"><el-icon :size="24"><Bell /></el-icon></div>
          暂无订阅规则，点击「新建订阅」关注感兴趣的项目变更
        </div>
        <div v-else class="sub-list">
          <div
            v-for="(s, i) in subscriptions" :key="s.id"
            class="sub-card" :class="{ on: s.enabled }"
            :style="{ '--acc': channelAccent(s.channel) }"
          >
            <span class="sub-accent"></span>
            <span class="et-g-ic" :class="logoCls(i)">{{ (channelMap[s.channel] || s.channel).charAt(0) }}</span>
            <div class="sub-main">
              <div class="sub-top">
                <span class="sub-name">{{ s.name }}</span>
                <span class="sub-status" :class="s.enabled ? 'on' : 'off'">{{ s.enabled ? '生效中' : '已暂停' }}</span>
              </div>
              <div class="sub-chips">
                <span class="et-mini-tag" :class="channelTagCls(s.channel)">{{ channelMap[s.channel] || s.channel }}</span>
                <template v-if="sessionChips[s.id]">
                  <span v-if="sessionChips[s.id].projectKey" class="et-mini-tag et-tag-info">{{ sessionChips[s.id].projectKey }}</span>
                  <span v-for="t in sessionChips[s.id].eventTypes" :key="t" class="et-mini-tag et-tag-req">{{ eventLabels[t] ?? t }}</span>
                </template>
                <span class="sub-time">创建于 {{ s.createdAt?.substring(0, 10) }}</span>
              </div>
            </div>
            <div class="sub-actions">
              <el-switch class="sub-switch" :model-value="s.enabled" @change="(v: boolean) => toggleRule(s.id, v)" />
              <button class="sub-del" title="删除订阅" @click="deleteRule(s.id)">
                <el-icon :size="14"><Delete /></el-icon>
              </button>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 通知记录 -->
    <section class="et-card rise loading-sec" style="--d: 0.14s" v-loading="loading">
      <div class="et-card-head">
        <span class="et-tic"><el-icon :size="15"><Promotion /></el-icon></span>
        <div>
          <div class="et-card-title">通知记录</div>
          <div class="et-card-sub">最近推送的变更通知</div>
        </div>
      </div>
      <div class="et-card-body">
        <div v-if="!loading && logs.length === 0" class="et-empty-hint">
          <div class="et-empty-ic"><el-icon :size="24"><Promotion /></el-icon></div>
          暂无通知记录
        </div>
        <div v-else class="log-list">
          <div v-for="log in logs" :key="log.id" class="log-row">
            <span class="et-mini-tag" :class="channelTagCls(log.channel)">{{ channelMap[log.channel] || log.channel }}</span>
            <span class="log-title">{{ log.title }}</span>
            <span class="et-mini-tag" :class="log.status === 'SENT' ? 'et-tag-review' : 'et-tag-api'">{{ log.status === 'SENT' ? '已送达' : '失败' }}</span>
            <span class="log-time">{{ fmtTime(log.createdAt) }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 新建订阅 -->
    <el-dialog v-model="dialogVisible" title="新建变更订阅" width="520px">
      <el-form label-width="80px">
        <el-form-item label="名称"><el-input v-model="form.name" placeholder="如：支付模块DDL变更通知" /></el-form-item>
        <el-form-item label="渠道">
          <el-select v-model="form.channel"><el-option v-for="(v,k) in channelMap" :key="k" :label="v" :value="k" /></el-select>
        </el-form-item>
        <el-form-item v-if="form.channel==='WEBHOOK'" label="URL"><el-input v-model="form.webhookUrl" placeholder="https://..." /></el-form-item>
        <el-form-item label="项目"><el-input v-model="form.filter.projectKey" style="width:180px" /></el-form-item>
        <el-form-item label="事件类型"><el-select v-model="form.filter.eventTypes" multiple collapse-tags collapse-tags-tooltip :max-collapse-tags="2"><el-option v-for="t in eventOptions" :key="t" :label="t" :value="t" /></el-select></el-form-item>
        <el-form-item label="文件模式"><el-input v-model="form.filter.filePattern" placeholder="**/payment/** (glob)" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" @click="create(); trackCreated()">创建</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page .et-hero { display: flex; align-items: center; justify-content: space-between; gap: 24px; margin-bottom: 18px; flex-wrap: wrap; }
.hero-left { min-width: 0; }
.hero-chips { display: flex; align-items: center; gap: 10px; margin-top: 16px; flex-wrap: wrap; }
.hero-right { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }
.chip-mini {
  display: inline-flex; align-items: center; gap: 7px;
  font-size: 12px; color: var(--et-text-secondary);
  padding: 6px 12px; border-radius: 10px;
  background: var(--et-bg-muted); border: 1px solid var(--et-border);
}
.chip-mini b { color: var(--et-text); font-variant-numeric: tabular-nums; }
.chip-mini.live { color: var(--et-ok); }
.chip-mini .et-pulse { width: 6px; height: 6px; }

.loading-sec { position: relative; }

/* ---------- 订阅分组卡片 ---------- */
.sub-list { display: flex; flex-direction: column; gap: 12px; }
.sub-card {
  position: relative; display: flex; align-items: center; gap: 14px;
  padding: 14px 16px; border-radius: 14px;
  background: var(--et-card-bg); border: 1px solid var(--et-border);
  overflow: hidden; transition: border-color 0.22s, box-shadow 0.22s, background 0.22s;
}
.sub-card:hover { border-color: var(--et-hover-border); box-shadow: var(--et-shadow-md); }
.sub-card.on {
  border-color: rgba(5, 150, 105, 0.3);
  background: var(--et-primary-bg);
}
.sub-accent {
  position: absolute; left: 0; top: 14px; bottom: 14px;
  width: 3px; border-radius: 3px;
  background: var(--acc, var(--et-primary)); opacity: 0.7;
}
.sub-main { flex: 1; min-width: 0; }
.sub-top { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.sub-name { font-size: 14px; font-weight: 700; }
.sub-status { font-size: 10.5px; font-weight: 700; padding: 2.5px 8px; border-radius: 20px; flex-shrink: 0; }
.sub-status.on { color: var(--et-ok); background: rgba(5, 150, 105, 0.1); }
.sub-status.off { color: var(--et-text-muted); background: var(--et-bg-muted); }
.sub-chips { display: flex; align-items: center; gap: 7px; margin-top: 8px; flex-wrap: wrap; }
.sub-time { font-size: 11px; color: var(--et-text-muted); }
.sub-actions { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }

/* 开关启用时配色 */
.sub-switch :deep(.el-switch.is-checked .el-switch__core) {
  background: var(--et-primary) !important;
  border-color: transparent !important;
}
.sub-del {
  width: 30px; height: 30px; border-radius: 9px;
  display: flex; align-items: center; justify-content: center;
  color: var(--et-text-muted); background: none;
  border: 1px solid transparent; cursor: pointer; transition: all 0.15s;
}
.sub-del:hover { color: var(--et-danger); background: rgba(220, 38, 38, 0.1); border-color: rgba(220, 38, 38, 0.3); }

/* ---------- 通知记录 ---------- */
.log-list { display: flex; flex-direction: column; }
.log-row {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 8px; border-radius: 10px;
  border-bottom: 1px solid var(--et-border); transition: background 0.15s;
}
.log-row:last-child { border-bottom: none; }
.log-row:hover { background: rgba(79, 90, 209, 0.05); }
.log-title {
  flex: 1; min-width: 0; font-size: 13px; font-weight: 500;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.log-time { font-size: 11px; color: var(--et-text-muted); flex-shrink: 0; font-variant-numeric: tabular-nums; }
</style>
