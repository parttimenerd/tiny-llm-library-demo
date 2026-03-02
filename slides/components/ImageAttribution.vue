<template>
  <div :class="['absolute text-xs text-gray-500 pointer-events-none', positionClass]">
    <slot />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  position?: 'bottom-left' | 'bottom-right'
}

const props = withDefaults(defineProps<Props>(), {
  position: 'bottom-right'
})

const positionClass = computed(() => {
  return props.position === 'bottom-left'
    ? 'bottom-2 left-2'
    : 'bottom-2 right-2'
})
</script>
