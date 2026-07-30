<script setup lang="ts">
import { ref } from 'vue'
import { qaApi } from '../api'

interface Message { role: 'user' | 'assistant'; content: string; refs?: { title: string; url?: string }[] }

const project = ref('mall')
const question = ref('')
const loading = ref(false)
const messages = ref<Message[]>([
  {
    role: 'assistant',
    content: '你好，我是 EvoTrace 演化问答助手。可以问我类似：「订单超时关单的逻辑在 v2.x 之后是怎么演化的？」「v2.5.0 有哪些破坏性变更？」',
    refs: []
  }
])

async function ask() {
  if (!question.value.trim()) return
  const q = question.value
  messages.value.push({ role: 'user', content: q })
  question.value = ''
  loading.value = true
  try {
    const resp = await qaApi.ask(project.value, q)
    messages.value.push({
      role: 'assistant',
      content: resp.answer,
      refs: resp.references.map(r => ({ title: r.title }))
    })
  } catch {
    messages.value.push({
      role: 'assistant',
      content: '问答服务暂不可用（服务端未启动或 M3 RAG 能力未接入）。',
      refs: []
    })
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-card shadow="never" class="qa-card">
    <div class="toolbar">
      <span>项目：</span><el-input v-model="project" style="width: 160px" />
    </div>
    <div class="messages">
      <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
        <div class="bubble">
          <pre>{{ m.content }}</pre>
          <div v-if="m.refs?.length" class="refs">
            <el-tag v-for="r in m.refs" :key="r.title" size="small" effect="plain" style="margin-right: 6px">📎 {{ r.title }}</el-tag>
          </div>
        </div>
      </div>
      <div v-if="loading" class="msg assistant"><div class="bubble">思考中…</div></div>
    </div>
    <div class="input-bar">
      <el-input v-model="question" placeholder="输入关于系统演化的问题…" @keyup.enter="ask" />
      <el-button type="primary" :loading="loading" @click="ask">发送</el-button>
    </div>
  </el-card>
</template>

<style scoped>
.qa-card { display: flex; flex-direction: column; height: calc(100vh - 140px) }
.toolbar { margin-bottom: 12px }
.messages { flex: 1; overflow-y: auto }
.msg { display: flex; margin-bottom: 12px }
.msg.user { justify-content: flex-end }
.bubble { max-width: 75%; padding: 10px 14px; border-radius: 8px; background: #f5f7fa }
.msg.user .bubble { background: #ecf5ff }
pre { white-space: pre-wrap; margin: 0; font-family: inherit }
.refs { margin-top: 8px }
.input-bar { display: flex; gap: 8px; margin-top: 12px }
</style>
