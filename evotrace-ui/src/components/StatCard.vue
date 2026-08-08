<script setup lang="ts">
import { computed } from 'vue'
import type { Component } from 'vue'
import { ArrowUp, ArrowDown } from '@element-plus/icons-vue'
import AnimatedNumber from './AnimatedNumber.vue'
import SparkLine from './SparkLine.vue'

const props = withDefaults(defineProps<{
  label: string
  value: number | string
  icon?: Component
  color?: string
  loading?: boolean
  trend?: number[]
  suffix?: string
  delta?: string
  deltaDir?: 'up' | 'down'
  foot?: string
}>(), {
  deltaDir: 'up'
})

const gradientStyle = computed(() => ({
  background: `linear-gradient(135deg, var(--accent), color-mix(in srgb, var(--accent) 55%, #a78bfa))`,
  boxShadow: `0 6px 16px color-mix(in srgb, var(--accent) 35%, transparent)`
}))
</script>

<template>
  <div class="et-card stat-card" :style="{ '--accent': props.color ?? 'var(--et-primary)' }">
    <div class="stat-head">
      <span class="et-g-ic" :style="gradientStyle">
        <el-icon :size="17" v-if="props.icon"><component :is="props.icon" /></el-icon>
        <span v-else class="stat-char">{{ props.label.charAt(0) }}</span>
      </span>
      <span class="stat-label">{{ props.label }}</span>
      <span v-if="props.delta" class="et-delta" :class="props.deltaDir">
        <el-icon :size="11"><ArrowUp v-if="props.deltaDir === 'up'" /><ArrowDown v-else /></el-icon>
        {{ props.delta }}
      </span>
    </div>

    <div class="stat-num" v-loading="props.loading">
      <AnimatedNumber v-if="typeof props.value === 'number'" :to="props.value" :duration="1000" />
      <span v-else>{{ props.value }}</span>
      <span v-if="props.suffix" class="stat-unit">{{ props.suffix }}</span>
    </div>

    <div class="stat-foot">
      <span class="foot-text">{{ props.foot }}</span>
      <SparkLine v-if="props.trend?.length" :data="props.trend" :color="props.color" :width="84" :height="28" />
    </div>
  </div>
</template>

<style scoped>
.stat-card {
  position: relative;
  padding: 18px 20px 16px;
  overflow: hidden;
}
.stat-card::after {
  content: '';
  position: absolute;
  top: 0;
  left: 18px;
  right: 18px;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.25), transparent);
  pointer-events: none;
}
[data-theme="light"] .stat-card::after {
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.9), transparent);
}
.stat-card:hover {
  transform: translateY(-4px);
}

.stat-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.stat-label {
  font-size: 13px;
  color: var(--et-text-secondary);
  font-weight: 500;
}
.stat-char { font-size: 14px; }

.stat-num {
  margin-top: 12px;
  font-size: 32px;
  font-weight: 800;
  letter-spacing: -0.5px;
  font-variant-numeric: tabular-nums;
  display: flex;
  align-items: baseline;
  gap: 6px;
  color: var(--et-text);
  min-height: 38px;
}
.stat-unit {
  font-size: 13px;
  color: var(--et-text-muted);
  font-weight: 500;
}

.stat-foot {
  margin-top: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 11.5px;
  color: var(--et-text-muted);
}
.foot-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
