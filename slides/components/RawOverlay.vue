<template>
  <div v-if="isOpen" class="raw-overlay" @click="close">
    <div class="raw-modal" @click.stop>
      <div class="raw-header">
        <h3>{{ title }}</h3>
        <button class="raw-close" type="button" @click="close">✕</button>
      </div>
      <div class="raw-content">
        <pre><code><slot /></code></pre>
      </div>
    </div>
  </div>

  <button v-if="!isOpen" class="raw-button" type="button" @click="open">
    {{ buttonText }}
  </button>
</template>

<script setup lang="ts">
import { ref } from 'vue'

withDefaults(
  defineProps<{
    title?: string
    buttonText?: string
  }>(),
  {
    title: 'Output',
    buttonText: 'Show Output'
  }
)

const isOpen = ref(false)

const open = () => {
  isOpen.value = true
}

const close = () => {
  isOpen.value = false
}
</script>

<style scoped>
.raw-button {
  background: linear-gradient(135deg, #ea580c 0%, #ff6b35 100%);
  color: white;
  border: none;
  padding: 4px 12px;
  border-radius: 16px;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  box-shadow: 0 1px 4px rgba(234, 88, 12, 0.2);
  white-space: nowrap;
}

.raw-button:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(234, 88, 12, 0.4);
}

.raw-button:active {
  transform: translateY(0);
}

.raw-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  backdrop-filter: blur(4px);
  animation: fadeIn 0.2s ease;
}

@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.raw-modal {
  background: linear-gradient(135deg, #0f172a 0%, #1e293b 100%);
  border: 1px solid rgba(226, 232, 240, 0.2);
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.6);
  max-width: 90%;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  animation: slideUp 0.3s ease;
}

@keyframes slideUp {
  from {
    transform: translateY(20px);
    opacity: 0;
  }
  to {
    transform: translateY(0);
    opacity: 1;
  }
}

.raw-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1.5rem;
  border-bottom: 1px solid rgba(226, 232, 240, 0.1);
}

.raw-header h3 {
  margin: 0;
  font-size: 1.25rem;
  color: #f8fafc;
  font-weight: 700;
}

.raw-close {
  background: none;
  border: none;
  color: #94a3b8;
  font-size: 1.5rem;
  cursor: pointer;
  padding: 0;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: color 0.2s ease;
}

.raw-close:hover {
  color: #f1f5f9;
}

.raw-content {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 1.5rem;
}

.raw-content pre {
  margin: 0;
  font-family: 'Monospace', 'Monaco', 'Courier New', monospace;
  font-size: 0.95rem !important;
  line-height: 1.6;
  color: #cbd5e1;
  white-space: pre-wrap !important;
  word-wrap: break-word !important;
  word-break: break-all !important;
  overflow-wrap: anywhere !important;
  overflow: hidden !important;
  border: none !important;
  box-shadow: none !important;
  padding: 0 !important;
  max-height: none !important;
}

.raw-content code {
  display: block;
  white-space: pre-wrap !important;
  word-break: break-all !important;
  overflow-wrap: anywhere !important;
}

/* If the slot renders its own code block (e.g. Markdown fenced block),
   force wrapping there too (Slidev/Shiki tends to set white-space: pre). */
.raw-content :deep(pre),
.raw-content :deep(code),
.raw-content :deep(.shiki) {
  white-space: pre-wrap !important;
  word-break: break-all !important;
  overflow-wrap: anywhere !important;
}

/* Scrollbar styling */
.raw-content::-webkit-scrollbar {
  width: 8px;
}

.raw-content::-webkit-scrollbar-track {
  background: rgba(226, 232, 240, 0.05);
}
</style>
