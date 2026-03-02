<template>
  <div v-if="isOpen" class="json-overlay" @click="close">
    <div class="json-modal" @click.stop>
      <div class="json-header">
        <h3>{{ title }}</h3>
        <button class="json-close" @click="close">✕</button>
      </div>
      <div class="json-content">
        <pre><code v-html="highlightedJson"></code></pre>
      </div>
    </div>
  </div>

  <button v-if="!isOpen" class="json-button" @click="open">
    {{ buttonText }}
  </button>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  json: {
    type: [Object, String],
    required: true
  },
  title: {
    type: String,
    default: 'Response'
  },
  buttonText: {
    type: String,
    default: 'Show Response'
  }
})

const isOpen = ref(false)

const open = () => {
  isOpen.value = true
}

const close = () => {
  isOpen.value = false
}

const highlightedJson = computed(() => {
  let json = props.json
  if (typeof json === 'string') {
    json = json
  } else {
    json = JSON.stringify(json, null, 2)
  }

  // Escape HTML first
  json = json
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

  // Apply syntax highlighting with better patterns
  return json.replace(/("(?:\\.|[^"\\])*"(?:\s*:)?|true|false|null|-?\d+(?:\.\d*)?(?:[eE][+\-]?\d+)?)/g, (match) => {
    // String key (ends with colon)
    if (/".*":/.test(match)) {
      return `<span class="key">${match.slice(0, -1)}</span><span class="colon">:</span>`
    }
    // String value
    if (/^"/.test(match)) {
      return `<span class="string">${match}</span>`
    }
    // Boolean
    if (/^(true|false)$/.test(match)) {
      return `<span class="boolean">${match}</span>`
    }
    // Null
    if (/^null$/.test(match)) {
      return `<span class="null">${match}</span>`
    }
    // Number
    if (/^-?\d/.test(match)) {
      return `<span class="number">${match}</span>`
    }
    return match
  })
})
</script>

<style scoped>
.json-button {
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

.json-button:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(234, 88, 12, 0.4);
}

.json-button:active {
  transform: translateY(0);
}

.json-overlay {
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

.json-modal {
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

.json-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1.5rem;
  border-bottom: 1px solid rgba(226, 232, 240, 0.1);
}

.json-header h3 {
  margin: 0;
  font-size: 1.25rem;
  color: #f8fafc;
  font-weight: 700;
}

.json-close {
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

.json-close:hover {
  color: #f1f5f9;
}

.json-content {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 1.5rem;
}

.json-content pre {
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

.json-content code {
  display: block;
  white-space: pre-wrap !important;
  word-break: break-all !important;
  overflow-wrap: anywhere !important;
}

.json-content :deep(.key) {
  color: #60a5fa;
  font-weight: 600;
}

.json-content :deep(.colon) {
  color: #e2e8f0;
  font-weight: 700;
}

.json-content :deep(.string) {
  color: #4ade80;
}

.json-content :deep(.number) {
  color: #fbbf24;
  font-weight: 500;
}

.json-content :deep(.boolean) {
  color: #f87171;
  font-weight: 600;
}

.json-content :deep(.null) {
  color: #a78bfa;
  font-weight: 500;
}

/* Scrollbar styling */
.json-content::-webkit-scrollbar {
  width: 8px;
}

.json-content::-webkit-scrollbar-track {
  background: rgba(226, 232, 240, 0.05);
}

.json-content::-webkit-scrollbar-thumb {
  background: rgba(226, 232, 240, 0.2);
  border-radius: 4px;
}

.json-content::-webkit-scrollbar-thumb:hover {
  background: rgba(226, 232, 240, 0.3);
}
</style>
