<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { Promotion, Warning } from '@element-plus/icons-vue'
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

/* ---------- 展示层新增：新消息/加载态时自动滚动到底部 ---------- */
const messagesEl = ref<HTMLElement>()
watch([messages, loading], async () => {
  await nextTick()
  const el = messagesEl.value
  if (el) el.scrollTop = el.scrollHeight
})
</script>

<template>
  <div class="qa-wrap">
    <section class="et-card chat-card rise">
      <!-- 顶部标题栏 -->
      <div class="chat-head">
        <div class="chat-head-left">
          <span class="et-g-ic g-indigo head-ic"><el-icon :size="17"><ChatDotRound /></el-icon></span>
          <div>
            <div class="chat-title">AI 演化问答</div>
            <div class="chat-sub"><span class="et-pulse"></span>基于全链路演化数据的智能问答</div>
          </div>
        </div>
        <div class="chat-head-right">
          <div class="tool-group">
            <span class="tool-label">项目</span>
            <el-input v-model="project" class="proj-input" size="default" placeholder="项目 Key" />
          </div>
          <div class="tool-group">
            <span class="tool-label">模型</span>
            <el-select v-model="selectedModelId" class="model-select" placeholder="默认模型" clearable size="default">
              <el-option v-for="m in models" :key="m.id" :value="m.id" :label="m.modelName">
                <span>{{ m.modelName }}</span>
                <span class="opt-meta">{{ m.name }}{{ m.default ? ' · 默认' : '' }}</span>
              </el-option>
            </el-select>
          </div>
          <el-tooltip v-if="models.length === 0" content="暂无启用模型,请到「管理 → AI 模型配置」配置">
            <el-icon class="warn-ic"><Warning /></el-icon>
          </el-tooltip>
        </div>
      </div>

      <!-- 消息区 -->
      <div ref="messagesEl" class="messages">
        <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
          <span v-if="m.role === 'assistant'" class="et-g-ic g-violet avatar-ic">AI</span>
          <div class="bubble">
            <pre>{{ m.content }}</pre>
            <div v-if="m.model" class="model-tag">
              <span class="et-mini-tag et-tag-info">模型 {{ m.model }}</span>
            </div>
            <div v-if="m.refs?.length" class="refs">
              <span v-for="r in m.refs" :key="r.title" class="ref-chip">📎 {{ r.title }}</span>
            </div>
          </div>
          <span v-if="m.role === 'user'" class="user-avatar">我</span>
        </div>

        <!-- 加载中打字动画 -->
        <div v-if="loading" class="msg assistant">
          <span class="et-g-ic g-violet avatar-ic">AI</span>
          <div class="bubble typing">
            <span class="dot" /><span class="dot" /><span class="dot" />
          </div>
        </div>

        <!-- 建议问题 -->
        <div v-if="messages.length === 1 && !loading" class="suggestions">
          <span class="suggestions-label">试试这些问题：</span>
          <button v-for="s in suggestions" :key="s" class="sug-chip" @click="useSuggestion(s)">
            <el-icon :size="13"><ChatDotRound /></el-icon>{{ s }}
          </button>
        </div>
      </div>

      <!-- 底部输入玻璃条 -->
      <div class="input-bar">
        <el-input
          v-model="question"
          placeholder="输入关于系统演化的问题…"
          :disabled="loading"
          @keyup.enter="ask"
        >
          <template #prefix><el-icon><ChatDotRound /></el-icon></template>
        </el-input>
        <el-button type="primary" class="send-btn" :loading="loading" :icon="Promotion" @click="ask">
          发送
        </el-button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.chat-card {
  display: flex;
  flex-direction: column;
  height: calc(100vh - var(--et-header-height) - 44px);
  min-height: 480px;
  overflow: hidden;
}

/* ======== 顶部标题栏 ======== */
.chat-head {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  padding: 16px 22px;
  border-bottom: 1px solid var(--et-border);
  flex-shrink: 0;
}

.chat-head-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.head-ic {
  width: 40px;
  height: 40px;
  border-radius: 12px;
}

.chat-title {
  font-size: 16px;
  font-weight: 800;
}

.chat-sub {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--et-text-muted);
}

.chat-head-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.tool-group {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tool-label {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--et-text-muted);
}

.proj-input {
  width: 120px;
}

.model-select {
  width: 190px;
}

.opt-meta {
  float: right;
  color: var(--et-text-muted);
  font-size: 12px;
  margin-left: 14px;
}

.warn-ic {
  color: var(--et-warn);
  cursor: pointer;
}

/* ======== 消息区 ======== */
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 22px 22px 6px;
  scroll-behavior: smooth;
}

.msg {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
  align-items: flex-start;
  animation: msg-in 0.35s cubic-bezier(0.22, 1, 0.36, 1);
}

@keyframes msg-in {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: none; }
}

.msg.user {
  flex-direction: row-reverse;
}

.avatar-ic {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  font-size: 11px;
}

.user-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-b) 60%, var(--et-grad-c));
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  box-shadow: 0 5px 14px var(--et-glow);
}

.bubble {
  position: relative;
  max-width: min(76%, 720px);
  padding: 12px 16px;
  border-radius: 14px;
  font-size: 13.5px;
  line-height: 1.7;
}

.bubble pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 13.5px;
  line-height: 1.7;
  color: inherit;
}

/* 用户：右侧渐变蓝紫气泡 */
.msg.user .bubble {
  color: #fff;
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-b));
  border-top-right-radius: 5px;
  box-shadow: 0 8px 24px rgba(109, 124, 255, 0.28);
}

.msg.user .bubble::after {
  content: '';
  position: absolute;
  right: -4px;
  bottom: 12px;
  width: 10px;
  height: 10px;
  transform: rotate(45deg);
  border-radius: 2px;
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-b));
}

/* AI：左侧玻璃气泡 */
.msg.ai .bubble {
  background: var(--et-card-bg);
  border: 1px solid var(--et-border);
  border-top-left-radius: 5px;
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  color: var(--et-text);
}

.msg.ai .bubble::after {
  content: '';
  position: absolute;
  left: -4px;
  bottom: 12px;
  width: 10px;
  height: 10px;
  transform: rotate(45deg);
  border-radius: 2px;
  background: var(--et-card-bg);
}

.model-tag {
  margin-top: 10px;
}

.refs {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.ref-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 600;
  padding: 3px 11px;
  border-radius: 20px;
  color: var(--et-text-secondary);
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--et-border);
}

[data-theme="light"] .ref-chip {
  background: rgba(15, 23, 42, 0.04);
}

/* 打字指示 */
.typing {
  display: flex;
  gap: 5px;
  padding: 14px 16px;
}

.typing .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-c));
  animation: bounce 1.2s infinite;
}

.typing .dot:nth-child(2) { animation-delay: 0.2s; }
.typing .dot:nth-child(3) { animation-delay: 0.4s; }

@keyframes bounce {
  0%, 60%, 100% { transform: translateY(0); }
  30% { transform: translateY(-5px); }
}

/* 建议问题 chips */
.suggestions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 9px;
  padding: 16px 0 4px;
}

.suggestions-label {
  font-size: 12.5px;
  color: var(--et-text-muted);
}

.sug-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 8px 14px;
  border-radius: 12px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  color: var(--et-text-secondary);
  font-size: 12.5px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.18s;
}

.sug-chip .el-icon {
  color: var(--et-grad-b);
}

.sug-chip:hover {
  color: var(--et-text);
  border-color: var(--et-primary);
  box-shadow: 0 0 0 3px rgba(109, 124, 255, 0.12);
  transform: translateY(-1px);
}

/* ======== 底部输入玻璃条 ======== */
.input-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 6px 18px 18px;
  padding: 9px 10px 9px 18px;
  border-radius: 16px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  box-shadow: var(--et-shadow-sm);
  flex-shrink: 0;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.input-bar:focus-within {
  border-color: var(--et-primary);
  box-shadow: 0 0 0 3px rgba(109, 124, 255, 0.15);
}

.input-bar .el-input {
  flex: 1;
}

.input-bar :deep(.el-input__wrapper) {
  background: transparent !important;
  box-shadow: none !important;
}

.input-bar :deep(.el-input__wrapper:hover),
.input-bar :deep(.el-input__wrapper.is-focus) {
  box-shadow: none !important;
}

.send-btn {
  padding: 12px 20px;
  border-radius: 12px;
  flex-shrink: 0;
}
</style>
