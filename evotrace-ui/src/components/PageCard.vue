<script setup lang="ts">
defineProps<{
  title?: string
  noPadding?: boolean
}>()
</script>

<template>
  <div class="page-card">
    <div v-if="title || $slots.header" class="page-card-header">
      <slot name="header">
        <span>{{ title }}</span>
      </slot>
      <div v-if="$slots.extra" class="page-card-extra">
        <slot name="extra" />
      </div>
    </div>
    <div class="page-card-body" :class="{ 'no-padding': noPadding }">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.page-card {
  background: var(--et-card-bg);
  border: 1px solid var(--et-border);
  border-radius: var(--et-radius);
  box-shadow: var(--et-shadow-sm);
}

.page-card + .page-card {
  margin-top: 16px;
}

.page-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--et-border);
  font-weight: 600;
  font-size: 15px;
  color: var(--et-text);
}

.page-card-body {
  padding: 20px;
}

.page-card-body.no-padding {
  padding: 0;
}
</style>
