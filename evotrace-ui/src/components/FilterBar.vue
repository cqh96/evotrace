<script setup lang="ts">
import { Search } from '@element-plus/icons-vue'
import PageCard from './PageCard.vue'

defineProps<{
  loading?: boolean
  showSearch?: boolean
}>()

defineEmits<{
  search: []
}>()
</script>

<template>
  <PageCard>
    <div class="filter-bar">
      <el-form inline class="filter-form" @submit.prevent="$emit('search')">
        <slot />
        <el-form-item v-if="$slots.actions">
          <slot name="actions" />
        </el-form-item>
        <el-form-item v-else-if="showSearch !== false">
          <el-button type="primary" :loading="loading" @click="$emit('search')">
            <el-icon style="margin-right: 4px"><Search /></el-icon>
            查询
          </el-button>
        </el-form-item>
      </el-form>
    </div>
  </PageCard>
</template>

<style scoped>
.filter-bar {
  padding: 4px 0;
}

.filter-form :deep(.el-form-item) {
  margin-bottom: 0;
  margin-right: 16px;
}

.filter-form :deep(.el-form-item__label) {
  color: var(--et-text-secondary);
  font-weight: 500;
}
</style>
