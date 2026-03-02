<template>
  <div class="grid grid-cols-2 gap-3 text-sm">
    <div class="text-gray-400"><slot name="key" /></div>
    <div class="font-semibold"><slot name="value" /></div>
  </div>
</template>
