<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'

const props = defineProps<{ to: number; duration?: number }>()

const display = ref(0)

function animate(start: number, end: number, ms: number) {
  const startTime = performance.now()
  function step(now: number) {
    const elapsed = now - startTime
    const progress = Math.min(elapsed / ms, 1)
    const eased = 1 - Math.pow(1 - progress, 3) // ease-out cubic
    display.value = Math.round(start + (end - start) * eased)
    if (progress < 1) requestAnimationFrame(step)
  }
  requestAnimationFrame(step)
}

watch(() => props.to, (val, old) => {
  animate(old ?? 0, val ?? 0, props.duration ?? 800)
})

onMounted(() => {
  animate(0, props.to ?? 0, props.duration ?? 800)
})
</script>

<template>
  <span class="animated-number">{{ display }}</span>
</template>

<style scoped>
.animated-number { font-variant-numeric: tabular-nums }
</style>
