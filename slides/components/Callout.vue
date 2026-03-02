<template>
  <div :class="['px-4 py-3 rounded-lg font-semibold', variantClass]">
    <slot />
  </div>
</template>

<script setup>
const props = defineProps({
  variant: { type: String, default: 'orange' },
});

const variantClass = {
  orange: 'bg-orange-500/15 text-orange-400',
  blue: 'bg-blue-500/15 text-blue-400',
  red: 'bg-red-500/15 text-red-400',
  green: 'bg-emerald-500/15 text-emerald-400',
}[props.variant] || 'bg-orange-500/15 text-orange-400';
</script>
