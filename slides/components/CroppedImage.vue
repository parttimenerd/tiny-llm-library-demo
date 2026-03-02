<template>
  <div class="w-full h-full overflow-hidden flex items-start justify-center">
    <img :src="src" :alt="alt" class="w-full" style="object-fit: cover; object-position: top; max-height: 100vh;" />
  </div>
</template>

<script setup lang="ts">
defineProps<{
  src: string
  alt?: string
}>()
</script>
