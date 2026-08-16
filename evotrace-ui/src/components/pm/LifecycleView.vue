<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { pmApi, type RoadmapVersion, type StatusFlow } from '../../api'

const props = defineProps<{ projectKey: string }>()

const roadmap = ref<RoadmapVersion[]>([])
const flow = ref<StatusFlow | null>(null)
const loading = ref(false)

const statusLabels: Record<string, string> = { DRAFT: '草稿', REVIEW: '评审中', DEVELOPING: '开发中', TESTING: '测试中', DONE: '已完成' }

async function load() {
  loading.value = true
  try {
    const [r, f] = await Promise.all([
      pmApi.roadmap(props.projectKey),
      pmApi.statusFlow(props.projectKey)
    ])
    roadmap.value = r ?? []
    flow.value = f ?? null
  } catch {
    roadmap.value = []
    flow.value = null
  } finally {
    loading.value = false
  }
}

function progressColor(rate: number): string {
  if (rate >= 100) return '#047857'
  if (rate >= 50) return '#4f5ad1'
  if (rate > 0) return '#b45309'
  return '#c0c4cc'
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 14px">
      版本路线图按已发布 release 与需求目标版本聚合；状态流转审计基于需求状态历史（DRAFT→REVIEW→DEVELOPING→TESTING→DONE）。
    </el-alert>

    <!-- 版本路线图 -->
    <div class="lc-section">
      <div class="lc-title">版本路线图</div>
      <el-empty v-if="roadmap.length === 0" description="暂无版本数据（无已发布版本，也无需求目标版本）" :image-size="70" />
      <div v-else class="roadmap-list">
        <div v-for="v in roadmap" :key="v.type + v.version" class="roadmap-row">
          <div class="rm-left">
            <span class="rm-version">{{ v.version }}</span>
            <el-tag size="small" :type="v.type === 'RELEASED' ? 'success' : 'warning'">{{ v.type === 'RELEASED' ? '已发布' : '规划中' }}</el-tag>
            <span v-if="v.type === 'RELEASED' && v.releasedAt" class="rm-date">{{ String(v.releasedAt).slice(0, 10) }}</span>
          </div>
          <div class="rm-progress">
            <el-progress
              :percentage="v.doneRate"
              :color="progressColor(v.doneRate)"
              :stroke-width="10"
            />
          </div>
          <div class="rm-stats">{{ v.done }}/{{ v.total }} 个需求完成</div>
        </div>
      </div>
    </div>

    <!-- 状态流转统计 -->
    <div v-if="flow" class="lc-section">
      <div class="lc-title">状态流转统计</div>
      <el-descriptions :column="3" border size="small" style="margin-bottom: 12px">
        <el-descriptions-item label="平均需求周期">{{ flow.avgCycleDays != null ? flow.avgCycleDays + ' 天' : '暂无完成需求' }}</el-descriptions-item>
        <el-descriptions-item label="近 30 天流转次数">{{ flow.transitions.reduce((s, t) => s + t.count, 0) }}</el-descriptions-item>
        <el-descriptions-item label="当前驻留中">{{ flow.byStatus.reduce((s, b) => s + b.openCount, 0) }}</el-descriptions-item>
      </el-descriptions>

      <div class="flow-grid">
        <div class="flow-col">
          <div class="flow-sub">各状态停留时长（平均/最长，天）</div>
          <el-table :data="flow.byStatus" size="small" border empty-text="暂无状态历史">
            <el-table-column label="状态" width="120">
              <template #default="{ row }">{{ statusLabels[row.status] ?? row.status }}</template>
            </el-table-column>
            <el-table-column label="进入次数" prop="entries" width="90" />
            <el-table-column label="平均停留" width="90">
              <template #default="{ row }">{{ row.avgDays.toFixed(1) }}</template>
            </el-table-column>
            <el-table-column label="最长停留" width="90">
              <template #default="{ row }">{{ row.maxDays.toFixed(1) }}</template>
            </el-table-column>
            <el-table-column label="当前驻留" width="90">
              <template #default="{ row }">{{ row.openCount }}</template>
            </el-table-column>
          </el-table>
        </div>

        <div class="flow-col">
          <div class="flow-sub">近 30 天流转矩阵</div>
          <el-table :data="flow.transitions" size="small" border empty-text="近 30 天无流转">
            <el-table-column label="从" width="110">
              <template #default="{ row }">{{ statusLabels[row.from] ?? row.from }}</template>
            </el-table-column>
            <el-table-column label="→ 到" min-width="110">
              <template #default="{ row }">{{ statusLabels[row.to] ?? row.to }}</template>
            </el-table-column>
            <el-table-column label="次数" prop="count" width="70" />
          </el-table>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.lc-section {
  background: var(--et-card-bg);
  border: 1px solid var(--et-border);
  border-radius: var(--et-radius-lg);
  padding: 18px;
  margin-bottom: 16px;
  transition: border-color 0.22s, box-shadow 0.22s;
}
.lc-section:hover { border-color: var(--et-hover-border); box-shadow: var(--et-shadow); }
.lc-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  margin-bottom: 14px;
}
.lc-title::before {
  content: '';
  width: 4px;
  height: 14px;
  border-radius: 2px;
  background: var(--et-primary);
}
.roadmap-list { display: flex; flex-direction: column; gap: 10px }
.roadmap-row {
  display: flex; align-items: center; gap: 16px;
  padding: 12px 16px;
  border: 1px solid var(--et-border);
  border-radius: 12px;
  background: var(--et-bg-muted);
  transition: border-color 0.18s, transform 0.18s, background 0.18s;
}
.roadmap-row:hover { border-color: var(--et-hover-border); background: var(--et-card-bg); transform: translateY(-1px) }
.rm-left { display: flex; align-items: center; gap: 8px; width: 180px; flex-shrink: 0 }
.rm-version {
  font-weight: 700;
  font-family: ui-monospace, monospace;
  color: var(--et-text);
}
.rm-date { font-size: 12px; color: var(--et-text-muted) }
.rm-progress { flex: 1 }
.rm-stats { font-size: 12px; color: var(--et-text-muted); width: 120px; text-align: right; font-variant-numeric: tabular-nums }
.flow-grid { display: flex; gap: 16px }
.flow-col { flex: 1; min-width: 0 }
.flow-sub { font-size: 13px; margin-bottom: 8px; color: var(--et-text-muted) }
@media (max-width: 1100px) {
  .flow-grid { flex-direction: column; }
}
</style>
