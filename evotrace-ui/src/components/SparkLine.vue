<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  data: number[]
  color?: string
  width?: number
  height?: number
}>()

const w = computed(() => props.width ?? 80)
const h = computed(() => props.height ?? 28)
const stroke = computed(() => props.color ?? 'var(--et-primary)')

const points = computed(() => {
  if (!props.data.length) return ''
  const max = Math.max(...props.data, 1)
  const min = Math.min(...props.data, 0)
  const range = max - min || 1
  const stepX = w.value / (props.data.length - 1 || 1)
  return props.data
    .map((v, i) => `${(i * stepX).toFixed(1)},${(h.value - ((v - min) / range) * h.value).toFixed(1)}`)
    .join(' ')
})
</script>

<template>
  <svg :width="w" :height="h" class="sparkline" :viewBox="`0 0 ${w} ${h}`">
    <polyline
      :points="points"
      fill="none"
      :stroke="stroke"
      stroke-width="1.8"
      stroke-linecap="round"
      stroke-linejoin="round"
    />
    <polygon
      v-if="points"
      :points="`0,${h} ${points} ${w},${h}`"
      :fill="stroke"
      fill-opacity="0.15"
    />
  </svg>
</template>

<style scoped>
.sparkline { display: block; flex-shrink: 0 }
</style>
