<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { projectApi, credentialApi, type Project } from '../api'

const projects = ref<Project[]>([])
const loading = ref(true)

const dialogVisible = ref(false)
const form = ref({ projectKey: '', name: '', repoUrl: '' })
const created = ref<{ apiKey: string; apiSecret: string } | null>(null)
const activeGuide = ref('java')

const credentialDialogVisible = ref(false)
const credentialProject = ref('')
const credentialList = ref<{ id: number; apiKey: string; scope: string; status: string; createdAt: string }[]>([])

async function load() {
  loading.value = true
  try {
    const data = await projectApi.list()
    if (data?.length) projects.value = data
  } catch {
    // 保留空列表
  } finally {
    loading.value = false
  }
}

async function create() {
  created.value = await projectApi.create(form.value)
  load()
}

async function showCredentials(projectKey: string) {
  credentialProject.value = projectKey
  try {
    credentialList.value = await credentialApi.list(projectKey)
  } catch {
    credentialList.value = []
  }
  credentialDialogVisible.value = true
}

async function rotateCredential() {
  try {
    const result = await credentialApi.rotate(credentialProject.value)
    ElMessage.success('凭证已轮换，请保存新的 API Secret')
    showCredentials(credentialProject.value)
  } catch {
    ElMessage.error('轮换失败')
  }
}

async function revokeCredential(id: number) {
  try {
    await credentialApi.revoke(credentialProject.value, id)
    ElMessage.success('凭证已吊销')
    showCredentials(credentialProject.value)
  } catch {
    ElMessage.error('吊销失败')
  }
}

// Import ElMessage at the top
import { ElMessage } from 'element-plus'

onMounted(load)
</script>

<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span>已接入项目</span>
          <el-button type="primary" @click="dialogVisible = true">新建项目接入</el-button>
        </div>
      </template>
      <el-table :data="projects" stripe v-loading="loading">
        <el-table-column prop="projectKey" label="项目标识" width="140" />
        <el-table-column prop="name" label="名称" width="140" />
        <el-table-column prop="techStack" label="技术栈" width="150">
          <template #default="{ row }">{{ row.techStack ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="repoUrl" label="仓库" min-width="240">
          <template #default="{ row }">{{ row.repoUrl ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="lastEventAt" label="最近上报" width="170">
          <template #default="{ row }">{{ row.lastEventAt ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button size="small" @click="showCredentials(row.projectKey)">凭证</el-button>
            <el-button size="small" type="danger" plain>停用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Create Project Dialog -->
    <el-dialog v-model="dialogVisible" title="新建项目接入" width="640px">
      <div v-if="!created">
        <el-form label-width="90px">
          <el-form-item label="项目标识"><el-input v-model="form.projectKey" placeholder="如 mall" /></el-form-item>
          <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
          <el-form-item label="仓库地址"><el-input v-model="form.repoUrl" /></el-form-item>
        </el-form>
      </div>
      <div v-else>
        <el-alert type="success" :closable="false" title="创建成功，请保存凭证（Secret 仅展示一次）" />
        <el-descriptions :column="1" border style="margin: 12px 0">
          <el-descriptions-item label="API Key"><code>{{ created.apiKey }}</code></el-descriptions-item>
          <el-descriptions-item label="API Secret"><code>{{ created.apiSecret }}</code></el-descriptions-item>
        </el-descriptions>
        <el-tabs v-model="activeGuide">
          <el-tab-pane label="Java Starter" name="java">
            <pre class="guide">&lt;dependency&gt;
  &lt;groupId&gt;io.evotrace&lt;/groupId&gt;
  &lt;artifactId&gt;evotrace-spring-boot-starter&lt;/artifactId&gt;
  &lt;version&gt;0.1.0-SNAPSHOT&lt;/version&gt;
&lt;/dependency&gt;

# application.yml
evotrace:
  server-url: http://evotrace.internal:8080
  project-key: {{ form.projectKey }}
  api-key: {{ created.apiKey }}
  api-secret: &lt;secret&gt;</pre>
          </el-tab-pane>
          <el-tab-pane label="CLI (Go/Py/Vue)" name="cli">
            <pre class="guide"># CI 中执行
evotrace scan --lang auto \
  --project-key {{ form.projectKey }} \
  --api-key {{ created.apiKey }} \
  --server http://evotrace.internal:8080</pre>
          </el-tab-pane>
          <el-tab-pane label="Git Webhook" name="webhook">
            <pre class="guide">GitLab → Settings → Webhooks
URL: http://evotrace.internal:8080/open-api/v1/webhooks/gitlab
Secret Token: &lt;webhook-secret&gt;
Trigger: Push events / Merge request events / Tag push events</pre>
          </el-tab-pane>
        </el-tabs>
      </div>
      <template #footer>
        <template v-if="!created">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" @click="create">创建并生成凭证</el-button>
        </template>
        <el-button v-else type="primary" @click="dialogVisible = false">完成</el-button>
      </template>
    </el-dialog>

    <!-- Credential Management Dialog -->
    <el-dialog v-model="credentialDialogVisible" :title="'凭证管理 — ' + credentialProject" width="600px">
      <el-table :data="credentialList" stripe>
        <el-table-column prop="apiKey" label="API Key" min-width="200">
          <template #default="{ row }"><code>{{ row.apiKey }}</code></template>
        </el-table-column>
        <el-table-column prop="scope" label="范围" width="100" />
        <el-table-column prop="createdAt" label="创建时间" width="170" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button size="small" type="danger" plain @click="revokeCredential(row.id)">吊销</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="rotateCredential" type="warning">轮换全部凭证</el-button>
        <el-button @click="credentialDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.guide { background: #f5f7fa; padding: 12px; border-radius: 6px; font-size: 12px; overflow-x: auto }
</style>
