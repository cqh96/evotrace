<script setup lang="ts">
import type { Component } from 'vue'
import AnimatedNumber from './AnimatedNumber.vue'
import SparkLine from './SparkLine.vue'

defineProps<{
  label: string
  value: number | string
  icon?: Component
  color?: string
  loading?: boolean
  trend?: number[]
  suffix?: string
}>()
</script>

<template>
  <div class="stat-card" :style="{ '--accent': color ?? 'var(--et-primary)' }">
    <div class="stat-row">
      <div v-if="icon" class="stat-icon">
        <el-icon :size="20"><component :is="icon" /></el-icon>
      </div>
      <div class="stat-content">
        <div class="stat-label">{{ label }}</div>
        <div class="stat-value" v-loading="loading">
          <AnimatedNumber v-if="typeof value === 'number'" :to="value" :duration="1000" />
          <span v-else>{{ value }}</span>
          <span v-if="suffix" class="suffix">{{ suffix }}</span>
        </div>
      </div>
    </div>
    <SparkLine v-if="trend?.length" :data="trend" :color="color" :width="80" :height="32" />
  </div>
</template>

<style scoped>
.stat-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px;
  background: var(--et-card-bg);
  border: 1px solid var(--et-border);
  border-radius: var(--et-radius);
  box-shadow: var(--et-shadow-sm);
  transition: box-shadow 0.25s, transform 0.25s, border-color 0.25s;
  position: relative;
  overflow: hidden;
}

.stat-card::before {
  content: '';
  position: absolute;
  top: 0; left: 0;
  width: 3px;
  height: 100%;
  background: var(--accent);
  border-radius: 0 2px 2px 0;
  opacity: 0;
  transition: opacity 0.25s;
}

.stat-card:hover {
  box-shadow: var(--et-shadow-md);
  transform: translateY(-2px);
  border-color: var(--accent);
}

.stat-card:hover::before {
  opacity: 1;
}

.stat-row {
  display: flex;
  align-items: center;
  gap: 14px;
}

.stat-icon {
  width: 42px;
  height: 42px;
  border-radius: 11px;
  background: color-mix(in srgb, var(--accent) 12%, transparent);
  color: var(--accent);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.stat-value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.2;
  color: var(--et-text);
  display: flex;
  align-items: baseline;
  gap: 2px;
}

.suffix {
  font-size: 13px;
  font-weight: 500;
  color: var(--et-text-secondary);
}

.stat-label {
  color: var(--et-text-secondary);
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 2px;
}
</style>
