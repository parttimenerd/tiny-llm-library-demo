<template>
  <div class="code-with-script">
    <div class="code-block">
      <slot></slot>
    </div>
    <div class="script-actions">
      <div class="script-pill">
        <button
          class="script-icon-button"
          type="button"
          :disabled="!canRun"
          :title="canRun ? 'Run script in terminal' : 'Terminal server unavailable'"
          @click="handleRunClick"
        >
          <span class="script-icon" aria-hidden="true">
            <svg viewBox="0 0 12 12" width="10" height="10" role="presentation">
              <path d="M3 2.2v7.6L9.2 6 3 2.2z" fill="currentColor" />
            </svg>
          </span>
        </button>
        <code>{{ scriptPath }}</code>
      </div>
      <slot name="actions"></slot>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

const props = defineProps<{
  scriptPath: string
  slideId?: string
}>()

const isTerminalAvailable = ref(false)
const canRun = computed(() => isTerminalAvailable.value)

async function checkTerminalAvailability() {
  const fromLaunchScript = import.meta.env.VITE_TERMINAL_AVAILABLE
  if (fromLaunchScript === 'true') {
    isTerminalAvailable.value = true
    return
  }

  const wsUrl = import.meta.env.VITE_TERMINAL_WS_URL || 'ws://127.0.0.1:3031'
  const httpUrl = wsUrl.replace(/^ws/, 'http')
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 500)

  try {
    const response = await fetch(`${httpUrl}/health`, {
      method: 'GET',
      signal: controller.signal
    })
    isTerminalAvailable.value = response.ok
  } catch {
    isTerminalAvailable.value = false
  } finally {
    window.clearTimeout(timeout)
  }
}

function handleRunClick() {
  if (!canRun.value) return

  window.dispatchEvent(
    new CustomEvent('terminal:code-run', {
      detail: {
        type: 'execute',
        script: props.scriptPath,
        slideId: props.slideId || 'default'
      }
    })
  )
}

onMounted(() => {
  checkTerminalAvailability()
})
</script>

<style scoped>
.code-with-script {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.code-block {
  width: 100%;
}

.script-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: linear-gradient(135deg, #f97316 40%, #ea580c 100%);
  color: white;
  padding: 4px 12px;
  border-radius: 16px;
  font-size: 11px;
  font-weight: 600;
  width: fit-content;
  box-shadow: 0 1px 4px rgba(249, 115, 22, 0.2);
}

.script-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.script-icon {
  display: inline-flex;
  align-items: center;
}

.script-icon svg {
  display: block;
}

.script-pill code {
  background: rgba(0, 0, 0, 0.15);
  padding: 1px 6px;
  border-radius: 3px;
  font-family: 'Courier New', monospace;
  font-size: 10px;
}

.script-icon-button {
  border: none;
  background: rgba(255, 255, 255, 0.18);
  color: white;
  width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  cursor: pointer;
  transition: background 0.2s ease, transform 0.2s ease;
  padding: 0;
}

.script-icon-button:hover {
  background: rgba(255, 255, 255, 0.32);
  transform: scale(1.05);
}

.script-icon-button:disabled {
  background: rgba(255, 255, 255, 0.12);
  color: rgba(255, 255, 255, 0.5);
  cursor: not-allowed;
  transform: none;
}
</style>
