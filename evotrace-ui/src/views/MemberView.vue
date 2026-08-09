<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Delete, User } from '@element-plus/icons-vue'
import { memberApi, type ProjectMember } from '../api'
import { useProjectStore } from '../stores/project'

const projectStore = useProjectStore()
const { current: project } = storeToRefs(projectStore)

const loading = ref(false)
const members = ref<ProjectMember[]>([])

const roles: Record<string, { label: string; desc: string; color: string }> = {
  ADMIN: { label: '管理员', desc: '全部权限', color: '#6d7cff' },
  PM: { label: '产品经理', desc: '需求/发布/报表', color: '#38e1ff' },
  DEVELOPER: { label: '开发', desc: '演化/代码/审查', color: '#34d399' },
  QA: { label: '测试', desc: '用例/缺陷/质量', color: '#fbbf24' },
  OPS: { label: '运维', desc: '接入/CI/监控', color: '#a78bfa' }
}

async function load() {
  if (!project.value) return
  loading.value = true
  try { members.value = await memberApi.list(project.value) } catch { ElMessage.error('加载成员失败') }
  loading.value = false
}

// ===== 添加成员 =====
const addOpen = ref(false)
const form = ref({ username: '', role: 'DEVELOPER' })
async function add() {
  if (!form.value.username.trim()) return ElMessage.warning('请输入用户名')
  try {
    await memberApi.add(project.value!, form.value)
    ElMessage.success('成员已添加')
    addOpen.value = false
    form.value = { username: '', role: 'DEVELOPER' }
    load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '添加失败')
  }
}

async function remove(m: ProjectMember) {
  await ElMessageBox.confirm(`确认移除成员「${m.username}」？`, '确认', { type: 'warning' })
  try { await memberApi.remove(project.value!, m.id); load() } catch { ElMessage.error('移除失败') }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <div class="left">
        <span class="et-tic"><el-icon><User /></el-icon></span>
        <span class="tip">按角色划分数据与操作权限：ADMIN / PM / DEVELOPER / QA / OPS</span>
      </div>
      <div class="right">
        <button class="ops-btn primary" @click="addOpen = true"><el-icon><Plus /></el-icon> 添加成员</button>
        <button class="ops-btn" @click="load"><el-icon><Refresh /></el-icon> 刷新</button>
      </div>
    </div>

    <div class="role-grid">
      <div v-for="(r, key) in roles" :key="key" class="role-card" :style="{ '--rc': r.color }">
        <div class="role-name">{{ r.label }}</div>
        <div class="role-key">{{ key }}</div>
        <div class="role-desc">{{ r.desc }}</div>
      </div>
    </div>

    <div class="et-card">
      <div class="et-card-head">
        <div class="et-card-title">项目成员</div>
        <div class="right"><span class="et-card-sub">共 {{ members.length }} 人</span></div>
      </div>
      <div class="et-card-body no-padding">
        <el-table :data="members" v-loading="loading" size="default" style="width: 100%">
          <el-table-column label="用户" min-width="180">
            <template #default="{ row }">
              <div class="user">
                <div class="avatar" :style="{ background: roles[row.role]?.color || '#6d7cff' }">{{ row.username.charAt(0).toUpperCase() }}</div>
                <div>
                  <div class="uname">{{ row.username }}</div>
                  <div class="dname">{{ row.displayName || '未设置昵称' }}</div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="角色" width="160">
            <template #default="{ row }">
              <span class="role-pill" :style="{ color: roles[row.role]?.color || '#6d7cff', background: `color-mix(in srgb, ${roles[row.role]?.color || '#6d7cff'} 14%, transparent)` }">
                {{ roles[row.role]?.label || row.role }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="角色说明" min-width="200">
            <template #default="{ row }">{{ roles[row.role]?.desc || '—' }}</template>
          </el-table-column>
          <el-table-column label="加入时间" width="160">
            <template #default="{ row }">{{ row.createdAt }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <button class="ops-btn danger" @click="remove(row)"><el-icon><Delete /></el-icon></button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="addOpen" title="添加成员" width="440px">
      <el-form :model="form" label-width="70px">
        <el-form-item label="用户名" required><el-input v-model="form.username" placeholder="系统用户名" /></el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.role" style="width: 100%">
            <el-option v-for="(r, key) in roles" :key="key" :label="`${r.label} (${key})`" :value="key">
              <div class="opt"><div>{{ r.label }} · {{ key }}</div><div class="opt-desc">{{ r.desc }}</div></div>
            </el-option>
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addOpen = false">取消</el-button>
        <el-button type="primary" @click="add">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.page-toolbar .left { display: flex; align-items: center; gap: 10px; }
.page-toolbar .right { display: flex; align-items: center; gap: 10px; }
.tip { font-size: 13px; color: var(--et-text-secondary); }
.ops-btn {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 14px; border-radius: 20px; border: 1px solid transparent;
  font-family: inherit; font-size: 13px; font-weight: 600; cursor: pointer; transition: all 0.18s;
}
.ops-btn.primary { background: rgba(109, 124, 255, 0.14); color: #a8b4ff; }
.ops-btn.primary:hover { background: rgba(109, 124, 255, 0.28); box-shadow: 0 0 12px rgba(109, 124, 255, 0.3); }
.ops-btn.danger { background: rgba(251, 113, 133, 0.14); color: #fb7185; }
.ops-btn.danger:hover { background: rgba(251, 113, 133, 0.28); box-shadow: 0 0 12px rgba(251, 113, 133, 0.3); }

.role-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 14px; margin-bottom: 18px; }
.role-card {
  padding: 16px; border-radius: 14px;
  background: var(--et-card-bg); border: 1px solid var(--et-border);
  border-top: 3px solid var(--rc);
}
.role-name { font-size: 15px; font-weight: 700; }
.role-key { font-size: 11px; font-weight: 700; color: var(--rc); margin-top: 2px; letter-spacing: 0.5px; }
.role-desc { font-size: 12px; color: var(--et-text-muted); margin-top: 8px; }

.user { display: flex; align-items: center; gap: 10px; }
.avatar {
  width: 34px; height: 34px; border-radius: 50%; color: #fff;
  display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 14px;
}
.uname { font-size: 13.5px; font-weight: 600; }
.dname { font-size: 11.5px; color: var(--et-text-muted); }
.role-pill { font-size: 12px; font-weight: 600; padding: 3px 10px; border-radius: 20px; }
.opt { line-height: 1.3; }
.opt-desc { font-size: 11px; color: var(--et-text-muted); }
</style>