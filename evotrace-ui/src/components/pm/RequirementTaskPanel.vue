<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pmApi, type RequirementTask, type TaskStatus } from '../../api'

const props = defineProps<{
  projectKey: string
  requirementId: number
}>()

const tasks = ref<RequirementTask[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const editing = ref<RequirementTask | null>(null)
const form = ref({ title: '', assignee: '', priority: 'P2', estimateHours: 8 })
const draggingId = ref<number | null>(null)

const statusLabels: Record<TaskStatus, string> = { TODO: '待办', DOING: '进行中', DONE: '已完成' }
const statusColors: Record<TaskStatus, string> = { TODO: 'info', DOING: 'warning', DONE: 'success' }

async function load() {
  loading.value = true
  try {
    tasks.value = (await pmApi.tasks(props.projectKey, props.requirementId)) ?? []
  } catch {
    tasks.value = []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = { title: '', assignee: '', priority: 'P2', estimateHours: 8 }
  dialogVisible.value = true
}

function openEdit(task: RequirementTask) {
  editing.value = task
  form.value = { title: task.title, assignee: task.assignee ?? '', priority: task.priority ?? 'P2', estimateHours: task.estimateHours ?? 8 }
  dialogVisible.value = true
}

async function save() {
  if (!form.value.title.trim()) return
  if (editing.value) {
    await pmApi.taskUpdate(props.projectKey, props.requirementId, editing.value.id, form.value)
  } else {
    await pmApi.taskCreate(props.projectKey, props.requirementId, form.value)
  }
  ElMessage.success('已保存')
  dialogVisible.value = false
  await load()
}

async function changeStatus(task: RequirementTask, status: TaskStatus) {
  try {
    await pmApi.taskStatus(props.projectKey, props.requirementId, task.id, status)
    task.status = status
  } catch {
    ElMessage.error('状态更新失败')
  }
}

async function remove(task: RequirementTask) {
  try {
    await ElMessageBox.confirm(`删除任务「${task.title}」？`, '确认', { type: 'warning' })
  } catch {
    return
  }
  await pmApi.taskDelete(props.projectKey, props.requirementId, task.id)
  ElMessage.success('已删除')
  await load()
}

function onDragStart(task: RequirementTask) {
  draggingId.value = task.id
}

function onDrop(target: RequirementTask) {
  const dragIndex = tasks.value.findIndex((t) => t.id === draggingId.value)
  const dropIndex = tasks.value.findIndex((t) => t.id === target.id)
  if (dragIndex === -1 || dropIndex === -1 || dragIndex === dropIndex) return
  const [moved] = tasks.value.splice(dragIndex, 1)
  tasks.value.splice(dropIndex, 0, moved)
  persistOrder()
  draggingId.value = null
}

async function persistOrder() {
  const order = tasks.value.map((t, i) => ({ id: t.id, sortOrder: (i + 1) * 10 }))
  try {
    await pmApi.taskReorder(props.projectKey, props.requirementId, order)
  } catch {
    ElMessage.error('排序保存失败')
    await load()
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="task-toolbar">
      <span class="task-count">共 {{ tasks.length }} 项 · 拖拽行可调整顺序</span>
      <el-button type="primary" size="small" @click="openCreate">+ 新建任务</el-button>
    </div>

    <el-table :data="tasks" row-key="id" size="small" empty-text="暂无任务拆分">
      <el-table-column label="任务" min-width="220">
        <template #default="{ row }">
          <div class="task-title" draggable="true" @dragstart="onDragStart(row)" @dragover.prevent @drop="onDrop(row)">
            <span class="drag-handle">⠿</span>
            {{ row.title }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="负责人" width="110">
        <template #default="{ row }">{{ row.assignee || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-select :model-value="row.status" size="small" @change="(v: TaskStatus) => changeStatus(row, v)">
            <el-option v-for="s in (['TODO', 'DOING', 'DONE'] as TaskStatus[])" :key="s" :value="s" :label="statusLabels[s]" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="优先级" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.priority === 'P0' ? 'danger' : row.priority === 'P1' ? 'warning' : 'info'">{{ row.priority }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="工时(h)" width="80">
        <template #default="{ row }">{{ row.estimateHours ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" text type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑任务' : '新建任务'" width="480px">
      <el-form label-width="80px">
        <el-form-item label="任务标题" required><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="负责人"><el-input v-model="form.assignee" /></el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="form.priority"><el-option v-for="p in ['P0', 'P1', 'P2', 'P3']" :key="p" :value="p" :label="p" /></el-select>
        </el-form-item>
        <el-form-item label="预估工时">
          <el-input-number v-model="form.estimateHours" :min="0.5" :step="0.5" :precision="1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!form.title.trim()" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.task-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
  padding: 10px 14px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  border-radius: 14px;
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
}
.task-count {
  font-size: 12px;
  color: var(--et-text-muted);
  display: inline-flex;
  align-items: center;
  gap: 7px;
}
.task-count::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-c));
}
.task-title { cursor: grab; display: flex; align-items: center; gap: 6px; transition: color 0.15s }
.task-title:hover { color: var(--et-primary-light) }
.task-title:active { cursor: grabbing }
.drag-handle { color: var(--et-text-muted); font-size: 14px }
:deep(.el-table) { border-radius: 12px; }
</style>
