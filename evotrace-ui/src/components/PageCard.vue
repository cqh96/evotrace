<script setup lang="ts">
import type { Component } from 'vue'

defineProps<{
  title?: string
  sub?: string
  icon?: Component
  noPadding?: boolean
}>()
</script>

<template>
  <div class="et-card page-card">
    <div v-if="title || sub || $slots.header" class="et-card-head">
      <slot name="header">
        <div class="head-left">
          <div class="et-card-title">
            <span v-if="icon" class="et-tic"><el-icon :size="15"><component :is="icon" /></el-icon></span>
            <span>{{ title }}</span>
          </div>
          <div v-if="sub" class="et-card-sub">{{ sub }}</div>
        </div>
      </slot>
      <div v-if="$slots.extra" class="right">
        <slot name="extra" />
      </div>
    </div>
    <div class="et-card-body" :class="{ 'no-padding': noPadding }">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.head-left {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
</style>
