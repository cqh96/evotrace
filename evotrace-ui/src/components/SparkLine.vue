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

const fillId = `spark-fill-${Math.random().toString(36).slice(2, 7)}`
</script>

<template>
  <svg :width="w" :height="h" class="sparkline" viewBox="0 0 {{ w }} {{ h }}">
    <defs>
      <linearGradient :id="fillId" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" :stop-color="stroke" stop-opacity="0.3" />
        <stop offset="100%" :stop-color="stroke" stop-opacity="0.02" />
      </linearGradient>
    </defs>
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
      :fill="`url(#${fillId})`"
    />
  </svg>
</template>

<style scoped>
.sparkline { display: block; flex-shrink: 0 }
</style>
