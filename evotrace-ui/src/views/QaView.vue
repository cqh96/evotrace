<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Promotion, Warning } from '@element-plus/icons-vue'
import PageCard from '../components/PageCard.vue'
import { qaApi, modelConfigApi, type ModelConfig } from '../api'

interface Message { role: 'user' | 'assistant'; content: string; refs?: { title: string; url?: string }[]; model?: string }

const project = ref('mall')
const question = ref('')
const loading = ref(false)
const models = ref<ModelConfig[]>([])
const selectedModelId = ref<number | null>(null)
const messages = ref<Message[]>([
  {
    role: 'assistant',
    content: '你好，我是 EvoTrace 演化问答助手。可以问我类似：\n\n• 「订单超时关单的逻辑在 v2.x 之后是怎么演化的？」\n• 「v2.5.0 有哪些破坏性变更？」\n• 「最近支付模块改了哪些接口？」',
    refs: []
  }
])

// 加载启用的模型配置,默认选中默认模型
onMounted(async () => {
  try {
    const all = await modelConfigApi.list()
    models.value = all.filter(m => m.enabled)
    selectedModelId.value = all.find(m => m.enabled && m.default)?.id ?? all.find(m => m.enabled)?.id ?? null
  } catch { /* 拦截器已提示 */ }
})

async function ask() {
  if (!question.value.trim() || loading.value) return
  const q = question.value
  messages.value.push({ role: 'user', content: q })
  question.value = ''
  loading.value = true
  try {
    const resp = await qaApi.ask(project.value, q, selectedModelId.value ?? undefined)
    messages.value.push({
      role: 'assistant',
      content: resp.answer,
      refs: resp.references.map(r => ({ title: r.title })),
      model: resp.model
    })
  } catch {
    messages.value.push({
      role: 'assistant',
      content: '问答服务暂不可用（服务端未启动或 AI 模型未配置）。',
      refs: []
    })
  } finally {
    loading.value = false
  }
}

const suggestions = [
  'v2.5.0 有哪些破坏性变更？',
  '订单超时关单逻辑是如何演化的？',
  '最近一周支付模块改了什么？'
]

function useSuggestion(text: string) {
  question.value = text
  ask()
}
</script>

<template>
  <PageCard class="qa-page">
    <div class="qa-toolbar">
      <span class="toolbar-label">项目</span>
      <el-input v-model="project" style="width: 160px" size="default" />
      <span class="toolbar-label" style="margin-left: 12px">模型</span>
      <el-select v-model="selectedModelId" placeholder="默认模型" clearable size="default" style="width: 200px">
        <el-option v-for="m in models" :key="m.id" :value="m.id" :label="m.modelName">
          <span>{{ m.modelName }}</span>
          <span style="float: right; color: var(--et-text-muted); font-size: 12px">
            {{ m.name }}{{ m.default ? ' · 默认' : '' }}
          </span>
        </el-option>
      </el-select>
      <el-tooltip v-if="models.length === 0" content="暂无启用模型,请到「管理 → AI 模型配置」配置">
        <el-icon style="color: var(--et-text-muted)"><Warning /></el-icon>
      </el-tooltip>
    </div>

    <div ref="messagesEl" class="messages">
      <div v-for="(m, i) in messages" :key="i" :class="['msg-row', m.role]">
        <div v-if="m.role === 'assistant'" class="avatar ai">AI</div>
        <div class="bubble">
          <pre>{{ m.content }}</pre>
          <div v-if="m.model" class="model-tag">
            <el-tag size="small" effect="plain" type="info">模型: {{ m.model }}</el-tag>
          </div>
          <div v-if="m.refs?.length" class="refs">
            <el-tag v-for="r in m.refs" :key="r.title" size="small" effect="plain" round>
              📎 {{ r.title }}
            </el-tag>
          </div>
        </div>
        <div v-if="m.role === 'user'" class="avatar user">
          <el-icon><User /></el-icon>
        </div>
      </div>

      <div v-if="loading" class="msg-row assistant">
        <div class="avatar ai">AI</div>
        <div class="bubble typing">
          <span class="dot" /><span class="dot" /><span class="dot" />
        </div>
      </div>

      <div v-if="messages.length === 1 && !loading" class="suggestions">
        <span class="suggestions-label">试试这些问题：</span>
        <el-button
          v-for="s in suggestions"
          :key="s"
          size="small"
          round
          @click="useSuggestion(s)"
        >
          {{ s }}
        </el-button>
      </div>
    </div>

    <div class="input-area">
      <el-input
        v-model="question"
        placeholder="输入关于系统演化的问题…"
        :disabled="loading"
        @keyup.enter="ask"
      >
        <template #prefix><el-icon><ChatDotRound /></el-icon></template>
      </el-input>
      <el-button type="primary" :loading="loading" :icon="Promotion" @click="ask">
        发送
      </el-button>
    </div>
  </PageCard>
</template>

<style scoped>
.qa-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - var(--et-header-height) - 40px);
}

.qa-page :deep(.page-card-body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 16px 20px;
  overflow: hidden;
}

.qa-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-shrink: 0;
}

.toolbar-label {
  color: var(--et-text-secondary);
  font-size: 13px;
  font-weight: 500;
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}

.msg-row {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
  align-items: flex-start;
}

.msg-row.user {
  flex-direction: row-reverse;
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 700;
}

.avatar.ai {
  background: linear-gradient(135deg, var(--et-primary), #8b5cf6);
  color: #fff;
}

.avatar.user {
  background: var(--et-page-bg);
  color: var(--et-text-secondary);
  border: 1px solid var(--et-border);
}

.bubble {
  max-width: 72%;
  padding: 12px 16px;
  border-radius: 12px;
  background: var(--et-page-bg);
  border: 1px solid var(--et-border);
}

.msg-row.user .bubble {
  background: var(--et-primary-bg);
  border-color: rgba(99, 102, 241, 0.2);
}

pre {
  white-space: pre-wrap;
  margin: 0;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.6;
  color: var(--et-text);
}

.model-tag {
  margin-top: 8px;
}

.refs {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.typing {
  display: flex;
  gap: 4px;
  padding: 16px;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--et-text-muted);
  animation: bounce 1.2s infinite;
}

.dot:nth-child(2) { animation-delay: 0.2s; }
.dot:nth-child(3) { animation-delay: 0.4s; }

@keyframes bounce {
  0%, 60%, 100% { transform: translateY(0); }
  30% { transform: translateY(-4px); }
}

.suggestions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 12px 0;
}

.suggestions-label {
  color: var(--et-text-muted);
  font-size: 13px;
}

.input-area {
  display: flex;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px solid var(--et-border);
  flex-shrink: 0;
}

.input-area .el-input {
  flex: 1;
}
</style>
