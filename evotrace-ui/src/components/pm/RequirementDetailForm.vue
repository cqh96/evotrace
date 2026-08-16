<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { pmApi, type RequirementDetail, type RequirementInput } from '../../api'

const props = defineProps<{
  projectKey: string
  requirement: RequirementDetail
  aiUsable: boolean
}>()

const emit = defineEmits<{ saved: [] }>()

const form = ref<RequirementDetail>({ ...props.requirement } as RequirementDetail)
const saving = ref(false)
const expanding = ref(false)

const canSave = computed(() => form.value.title.trim().length > 0)

function field(record: RequirementDetail): RequirementInput & { id?: number } {
  const { id, title, description, priority, status, techLead, estimateDays, businessValue, userStory, acceptanceCriteria, targetVersion, assignee, pm, prototypeUrl, designUrl } = record
  return { id, title, description, priority, status, techLead, estimateDays, businessValue, userStory, acceptanceCriteria, targetVersion, assignedTo: assignee, productManager: pm, prototypeUrl, designUrl }
}

async function save() {
  saving.value = true
  try {
    const data = { ...field(form.value) }
    if (data.id == null) delete data.id
    await pmApi.create(props.projectKey, data)
    ElMessage.success('已保存')
    emit('saved')
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function aiExpand() {
  expanding.value = true
  try {
    const result = await pmApi.aiExpand(props.projectKey, {
      title: form.value.title,
      description: form.value.description,
      priority: form.value.priority
    })
    if (result.generated) {
      form.value.businessValue = result.businessValue ?? form.value.businessValue
      form.value.userStory = result.userStory ?? form.value.userStory
      form.value.acceptanceCriteria = result.acceptanceCriteria ?? form.value.acceptanceCriteria
      form.value.estimateDays = Number(result.estimateDays) || form.value.estimateDays
      form.value.techLead = result.techLead ?? form.value.techLead
      ElMessage.success(`AI 扩写完成（${result.model}），可继续编辑`)
    } else {
      ElMessage.warning(result.message ?? 'AI 不可用')
    }
  } catch {
    ElMessage.error('AI 扩写失败')
  } finally {
    expanding.value = false
  }
}
</script>

<template>
  <div v-loading="expanding">
    <div class="form-head">
      <span class="form-hint">结构化需求建模：业务价值 / 用户故事 / 验收标准 / 工时评估 / 技术负责人</span>
      <el-button type="primary" plain :disabled="!aiUsable" :loading="expanding" @click="aiExpand">
        <el-tooltip :content="aiUsable ? 'AI 基于标题与描述生成结构化建模字段初稿' : '未配置可用 AI 模型（见 AI 模型配置）'" placement="top">
          <span>✨ AI 扩写</span>
        </el-tooltip>
      </el-button>
    </div>

    <el-form label-width="100px" label-position="left">
      <el-row :gutter="16">
        <el-col :span="14">
          <el-form-item label="标题" required>
            <el-input v-model="form.title" />
          </el-form-item>
        </el-col>
        <el-col :span="5">
          <el-form-item label="优先级">
            <el-select v-model="form.priority">
              <el-option v-for="p in ['P0', 'P1', 'P2', 'P3']" :key="p" :value="p" :label="p" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="5">
          <el-form-item label="状态">
            <el-select v-model="form.status">
              <el-option v-for="s in ['DRAFT', 'REVIEW', 'DEVELOPING', 'TESTING', 'DONE']" :key="s" :value="s" :label="s" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="目标版本">
        <el-input v-model="form.targetVersion" placeholder="v2.6.0" style="width: 220px" />
      </el-form-item>

      <el-form-item label="业务价值">
        <el-input v-model="form.businessValue" type="textarea" :rows="2" placeholder="一句话说明为什么做这件事（收益/痛点/机会）" />
      </el-form-item>
      <el-form-item label="用户故事">
        <el-input v-model="form.userStory" type="textarea" :rows="2" placeholder="作为〈角色〉，我希望〈能力〉，以便〈价值〉" />
      </el-form-item>
      <el-form-item label="验收标准">
        <el-input v-model="form.acceptanceCriteria" type="textarea" :rows="4" placeholder="- [ ] 可测试的验收项，每行一条" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="3" placeholder="补充背景、约束与范围" />
      </el-form-item>

      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="预估工时">
            <el-input-number v-model="form.estimateDays" :min="0" :step="0.5" :precision="1" />
            <span class="unit">人天</span>
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="技术负责人">
            <el-input v-model="form.techLead" placeholder="如：Charlie" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="产品经理">
            <el-input v-model="form.pm" placeholder="如：Alice" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="负责人">
            <el-input v-model="form.assignee" placeholder="开发负责人" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="原型链接">
            <el-input v-model="form.prototypeUrl" placeholder="Figma/蓝湖链接（可选）" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="设计稿链接">
            <el-input v-model="form.designUrl" placeholder="设计稿链接（可选）" />
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>

    <div class="form-footer">
      <el-button type="primary" :loading="saving" :disabled="!canSave" @click="save">保存需求</el-button>
    </div>
  </div>
</template>

<style scoped>
.form-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 16px;
  padding: 12px 16px;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  border-radius: 14px;
}
.form-hint { font-size: 12px; color: var(--et-text-muted) }
.unit { margin-left: 6px; color: var(--et-text-muted); font-size: 12px }
.form-footer {
  display: flex;
  justify-content: flex-end;
  border-top: 1px solid var(--et-border);
  padding-top: 14px;
  margin-top: 4px;
}
</style>
