<template>
  <div class="code-runner">
    <div class="code-wrapper">
      <div ref="editorContainer" class="code-display"></div>

      <Transition name="fade">
        <div v-if="showFinished && hasFinished" class="finished-overlay">
          <pre v-if="highlightedFinished"><code v-html="highlightedFinished"></code></pre>
          <div class="finished-label">Expected Solution</div>
        </div>
      </Transition>

      <div class="button-group">
        <button
          v-if="hasFinished"
          class="check-button"
          :title="`${showFinished ? 'Hide' : 'Show'} expected solution (Ctrl+U)`"
          @click="toggleShowFinished"
        >
          <svg viewBox="0 0 24 24" width="20" height="20">
            <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z" fill="currentColor" />
          </svg>
        </button>
        <button
          v-if="isTerminalAvailable"
          class="play-button"
          :disabled="!mainCode || mainCode.trim().length === 0"
          :title="`Execute code (Ctrl+Enter)`"
          @click="handleRun"
        >
          <svg viewBox="0 0 24 24" width="20" height="20">
            <path d="M8 5v14l11-7z" fill="currentColor" />
          </svg>
        </button>
      </div>
    </div>

    <RunTerminalComponent />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, useSlots } from 'vue'
import { EditorView, keymap } from '@codemirror/view'
import { indentWithTab, indentMore } from '@codemirror/commands'
import { EditorState } from '@codemirror/state'
import { indentOnInput } from '@codemirror/language'
import { basicSetup } from 'codemirror'
import { java } from '@codemirror/lang-java'
import { getShikiTheme, getCurrentTheme, ACTIVE_THEME, ALL_THEMES } from '../setup/theme-config'

/**
 * CodeRunner Component
 * 
 * Live code execution component for Slidev presentations.
 * Displays code blocks with play button to execute and optional check button for solutions.
 * 
 * FEATURES:
 * - Syntax highlighting for Java, Bash, Python
 * - Play button: executes code (Java → temp file, Bash → direct)
 * - Check button: shows expected solution overlay (if #finished slot provided)
 * - Keyboard shortcuts: Ctrl+U (toggle solution), Enter (run)
 * - Health checks to disable play button when server unavailable
 * - Auto-parses code from markdown code fence: ```lang\ncode\n```
 * - Support for additional files via #files slot
 * 
 * USAGE:
 *   <CodeRunner enable-preview slide-id="1">
 *     ```java
 *     class Hello {
 *       public static void main(String[] args) {
 *         System.out.println("Hello");
 *       }
 *     }
 *     ```
 *     <template #before>
 *       ```java
 *       import java.util.*;
 *       ```
 *     </template>
 *     <template #after>
 *       ```java
 *       // Post-execution code
 *       ```
 *     </template>
 *     <template #finished>
 *       ```java
 *       class Hello {
 *         public static void main(String[] args) {
 *           System.out.println("Hello World");
 *         }
 *       }
 *       ```
 *     </template>
 *     <template #files>
 *       ```helper.java
 *       public class Helper {
 *         public static String format(String text) {
 *           return text.toUpperCase();
 *         }
 *       }
 *       ```
 *     </template>
 *   </CodeRunner>
 */

interface Props {
  enablePreview?: boolean
  slideId?: string | number
  cwd?: string
  indent?: number | string
  addModules?: string | string[]
}

const props = withDefaults(defineProps<Props>(), {
  enablePreview: false,
  slideId: '',
  cwd: '',
  indent: 0,
  addModules: ''
})

const emit = defineEmits<{
  run: [{ fullCode: string; language: string; isDirect: boolean; additionalFiles: Record<string, string>; slideId: string }]
}>()

const showFinished = ref(false)
const isTerminalAvailable = ref(false)
const slots = useSlots()
const hasFinished = computed(() => !!slots.finished)

// Editor refs
const editorContainer = ref<HTMLDivElement | null>(null)
const editorView = ref<EditorView | null>(null)
const editableCode = ref('')

// Global singleton for terminal health check - runs only once on app initialization
let globalHealthCheckInitialized = false

async function checkTerminalAvailability() {
  try {
    const wsUrl = import.meta.env.VITE_TERMINAL_WS_URL || 'ws://127.0.0.1:3031'
    const httpUrl = wsUrl.replace(/^ws/, 'http')
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 500)
    const response = await fetch(`${httpUrl}/health`, { signal: controller.signal, mode: 'no-cors' })
    window.clearTimeout(timeout)
    isTerminalAvailable.value = response.ok
  } catch {
    isTerminalAvailable.value = false
  }
}

function initializeGlobalHealthCheck() {
  if (globalHealthCheckInitialized) return
  globalHealthCheckInitialized = true
  
  // Check if terminal availability was pre-determined by launch script
  const terminalAvailable = import.meta.env.VITE_TERMINAL_AVAILABLE
  
  if (terminalAvailable === 'true') {
    // Terminal server was started by launch script, trust it's there
    isTerminalAvailable.value = true
  } else if (terminalAvailable === 'false') {
    // Terminal server was explicitly not started
    isTerminalAvailable.value = false
  } else {
    // Launch script not used or variable not set, do a health check
    checkTerminalAvailability()
  }
}

function applyIndentation(code: string, indent: number | string | undefined): string {
  if (!indent || indent === 0) return code
  
  // Convert indent to number of spaces
  let indentStr: string
  if (typeof indent === 'number') {
    indentStr = ' '.repeat(indent)
  } else {
    indentStr = indent
  }
  
  // Split code into lines and add indentation to each non-empty line
  const lines = code.split('\n')
  return lines
    .map(line => {
      // Don't indent empty lines
      if (line.trim() === '') return line
      return indentStr + line
    })
    .join('\n')
}

function extractCodeFromSlot(slotContent: any): { code: string; language: string } {
  if (!slotContent) {
    console.log('[CodeRunner] Empty slot content')
    return { code: '', language: 'java' }
  }
  
  let extracted = ''
  let debugInfo = `[CodeRunner] Slot: ${Array.isArray(slotContent) ? 'array' : typeof slotContent} length=${Array.isArray(slotContent) ? slotContent.length : 'n/a'}`
  
  // Handle array of VNodes (from slot render function)
  if (Array.isArray(slotContent)) {
    for (let i = 0; i < slotContent.length; i++) {
      const node = slotContent[i]
      debugInfo += ` | node[${i}]: ${typeof node}`
      
      if (typeof node === 'string') {
        // Direct text content
        extracted += node
      } else if (!node) {
        // Skip null/undefined
        continue
      } else if (typeof node === 'object') {
        // For VNode objects, try multiple extraction strategies
        
        // Strategy 1: Slidev CodeBlockWrapper - has children.default render function
        if (node.children && typeof node.children === 'object' && typeof node.children.default === 'function') {
          try {
            const rendered = node.children.default()
            if (rendered) {
              const renderedContent = extractTextContent(rendered)
              extracted += renderedContent
              debugInfo += `:children.default(${renderedContent.length}chars)`
            }
          } catch (e) {
            debugInfo += `:children.default(error)`
          }
        }
        // Strategy 2: Direct textContent from el (actual DOM element)
        else if (node.el && typeof node.el.textContent === 'string') {
          const text = node.el.textContent
          extracted += text
          debugInfo += `:el.textContent(${text.length}chars)`
        }
        // Strategy 3: .children property (could be string or array)
        else if (node.children) {
          if (typeof node.children === 'string') {
            extracted += node.children
            debugInfo += `:children(string)`
          } else if (Array.isArray(node.children)) {
            // Recursively extract from children array
            const childText = node.children
              .map((child: any) => {
                if (typeof child === 'string') return child
                if (child?.el?.textContent) return child.el.textContent
                if (child?.children) {
                  if (typeof child.children === 'string') return child.children
                  return extractTextContent(child)
                }
                return ''
              })
              .join('')
            extracted += childText
            debugInfo += `:children(array)`
          }
        }
        // Strategy 4: Direct textContent property
        else if (typeof node.textContent === 'string') {
          extracted += node.textContent
          debugInfo += `:textContent`
        }
      }
    }
  } else if (typeof slotContent === 'string') {
    // Direct string content
    extracted = slotContent
    debugInfo += ` | string content`
  } else if (slotContent?.el?.textContent) {
    // Single VNode with el property
    extracted = slotContent.el.textContent
    debugInfo += ` | single el.textContent`
  }
  
  // Try to extract markdown fence first
  const match = extracted.match(/```(\w+)?\n([\s\S]*?)\n```/)
  if (match) {
    const code = match[2].trim()
    const lang = (match[1] || 'java').toLowerCase()
    console.log(debugInfo, `| fence found, lang=${lang}, len=${code.length}`)
    return { code, language: lang }
  }
  
  // Fallback: use extracted content as-is (already rendered by Slidev)
  const code = extracted.trim()
  console.log(debugInfo, `| no fence, raw content len=${code.length}`)
  return { code, language: 'java' }
}

// Helper function to extract text content from VNode tree
function extractTextContent(node: any, depth: number = 0): string {
  if (!node) return ''
  
  // Handle string directly
  if (typeof node === 'string') {
    console.log(`[extractTextContent] depth=${depth} | string: "${node.slice(0, 50)}"`)
    return node
  }
  
  // Handle array of nodes
  if (Array.isArray(node)) {
    console.log(`[extractTextContent] depth=${depth} | array length=${node.length}`)
    return node.map((child: any) => extractTextContent(child, depth + 1)).join('')
  }
  
  // Handle VNode objects
  if (typeof node === 'object') {
    console.log(`[extractTextContent] depth=${depth} | object keys=${Object.keys(node).slice(0, 5).join(',')}`)
    
    // Try DOM element text content first
    if (node.el?.textContent) {
      console.log(`[extractTextContent] depth=${depth} | found el.textContent`)
      return node.el.textContent
    }
    
    // Slidev code blocks might have props with the code
    if (node.props?.code) {
      console.log(`[extractTextContent] depth=${depth} | found props.code`)
      return node.props.code
    }
    
    // Check for content property (some Slidev components)
    if (node.content) {
      console.log(`[extractTextContent] depth=${depth} | found node.content`)
      return node.content
    }
    
    // Check for ssContent (server-sent content)
    if (node.ssContent) {
      console.log(`[extractTextContent] depth=${depth} | found node.ssContent`)
      return node.ssContent
    }
    
    // Regular string children property
    if (typeof node.children === 'string') {
      console.log(`[extractTextContent] depth=${depth} | found children string`)
      return node.children
    }
    
    // Array of children
    if (Array.isArray(node.children)) {
      console.log(`[extractTextContent] depth=${depth} | found children array, len=${node.children.length}`)
      return node.children.map((child: any) => extractTextContent(child, depth + 1)).join('')
    }
    
    // Check node's own textContent property
    if (node.textContent) {
      console.log(`[extractTextContent] depth=${depth} | found textContent property`)
      return node.textContent
    }
    
    // Last resort: try to stringify and look for meaningful content
    const str = String(node)
    if (str && str !== '[object Object]') {
      console.log(`[extractTextContent] depth=${depth} | fallback stringify: "${str.slice(0, 50)}"`)
      return str
    }
  }
  
  console.log(`[extractTextContent] depth=${depth} | no content found`)
  return ''
}

function extractFilesFromSlot(slotContent: any): FileContent[] {
  if (!slotContent) return []
  
  let html = ''
  if (Array.isArray(slotContent)) {
    html = slotContent.map((node: any) => typeof node.children === 'string' ? node.children : '').join('')
  } else if (typeof slotContent === 'string') {
    html = slotContent
  }
  
  // Support format: ```filename\ncode\n```
  const files: FileContent[] = []
  const regex = /```(\S+)\n([\s\S]*?)\n```/g
  let regexMatch
  
  while ((regexMatch = regex.exec(html)) !== null) {
    files.push({
      name: regexMatch[1],
      content: regexMatch[2].trim()
    })
  }
  
  return files
}

// Extract initial code from slot
const { code: initialCode, language } = (() => {
  const slotContent = slots.default?.()
  const result = extractCodeFromSlot(slotContent)
  editableCode.value = result.code  // Initialize editable code
  if (process.env.NODE_ENV === 'development') {
    console.log('[CodeRunner] Slot content:', slotContent)
    console.log('[CodeRunner] Extracted code:', result.code)
  }
  return result
})()

// mainCode uses editable code if available, otherwise initial
const mainCode = computed(() => editableCode.value || initialCode)

const { code: finishedCode } = computed(() => extractCodeFromSlot(slots.finished?.())).value
const { code: beforeCode } = computed(() => extractCodeFromSlot(slots.before?.())).value
const { code: afterCode } = computed(() => extractCodeFromSlot(slots.after?.())).value
const additionalFiles = computed(() => extractFilesFromSlot(slots.files?.()))

const fullCode = computed(() => {
  const parts = []
  if (beforeCode) parts.push(beforeCode)
  
  // Apply indentation to main code if specified
  const indentedMainCode = applyIndentation(mainCode.value, props.indent)
  parts.push(indentedMainCode)
  
  if (afterCode) parts.push(afterCode)
  return parts.join('\n')
})

const additionalFilesMap = computed(() => {
  const map: Record<string, string> = {}
  for (const file of additionalFiles.value) {
    const parts = []
    if (beforeCode) parts.push(beforeCode)
    
    // Apply indentation to file content if specified
    const indentedContent = applyIndentation(file.content, props.indent)
    parts.push(indentedContent)
    
    if (afterCode) parts.push(afterCode)
    map[file.name] = parts.join('\n')
  }
  return map
})

function highlightCode(code: string): string {
  let h = code
  const placeholders: Record<string, string> = {}
  let placeholderIndex = 0
  
  // Step 1: Extract strings, comments, and numbers FIRST (don't highlight them further)
  // This prevents keywords inside strings/comments from being highlighted
  
  // Extract strings with placeholders
  h = h.replace(/"([^"\\]|\\.)*"/g, (m) => {
    const placeholder = `__STR_${placeholderIndex}__`
    placeholders[placeholder] = `<span class="str">${m}</span>`
    placeholderIndex++
    return placeholder
  })
  
  h = h.replace(/'([^'\\]|\\.)*'/g, (m) => {
    const placeholder = `__STR_${placeholderIndex}__`
    placeholders[placeholder] = `<span class="str">${m}</span>`
    placeholderIndex++
    return placeholder
  })
  
  // Extract comments with placeholders (without highlighting keywords inside them)
  h = h.replace(/\/\/.*$/gm, (m) => {
    const placeholder = `__CMT_${placeholderIndex}__`
    placeholders[placeholder] = `<span class="cmt">${m}</span>`
    placeholderIndex++
    return placeholder
  })
  
  h = h.replace(/#.*$/gm, (m) => {
    const placeholder = `__CMT_${placeholderIndex}__`
    placeholders[placeholder] = `<span class="cmt">${m}</span>`
    placeholderIndex++
    return placeholder
  })
  
  h = h.replace(/\/\*[\s\S]*?\*\//g, (m) => {
    const placeholder = `__CMT_${placeholderIndex}__`
    placeholders[placeholder] = `<span class="cmt">${m}</span>`
    placeholderIndex++
    return placeholder
  })
  
  // Step 2: NOW highlight keywords and numbers (only in remaining code, not in strings/comments)
  const keywords = ['public', 'private', 'protected', 'static', 'final', 'class', 'interface', 'extends', 'implements', 'new', 'return', 'if', 'else', 'for', 'while', 'void', 'String', 'int', 'boolean', 'var', 'try', 'catch', 'throw', 'import', 'package', 'synchronized', 'this', 'do', 'break', 'continue', 'case', 'default', 'switch', 'true', 'false', 'null']
  
  keywords.forEach(kw => {
    h = h.replace(new RegExp(`\\b${kw}\\b`, 'g'), () => {
      const placeholder = `__KW_${placeholderIndex}__`
      placeholders[placeholder] = `<span class="kw">${kw}</span>`
      placeholderIndex++
      return placeholder
    })
  })
  
  // Highlight numbers
  h = h.replace(/\b\d+\b/g, (m) => {
    const placeholder = `__NUM_${placeholderIndex}__`
    placeholders[placeholder] = `<span class="num">${m}</span>`
    placeholderIndex++
    return placeholder
  })
  
  // Step 3: HTML escape
  h = h
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
  
  // Step 4: Replace all placeholders with actual HTML spans
  Object.entries(placeholders).forEach(([placeholder, span]) => {
    h = h.replace(new RegExp(placeholder.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g'), span)
  })
  
  return h
}

const highlightedFinished = computed(() => finishedCode ? highlightCode(finishedCode) : '')

const canRun = computed(() => isTerminalAvailable.value && mainCode.value.trim().length > 0)

let wsConnection = null

async function ensureWebSocketConnection() {
  if (wsConnection && wsConnection.readyState === WebSocket.OPEN) {
    return wsConnection
  }

  return new Promise((resolve, reject) => {
    try {
      const wsUrl = `ws://127.0.0.1:3031`
      console.log('[CodeRunner] Opening WebSocket connection to', wsUrl)
      
      wsConnection = new WebSocket(wsUrl)
      
      wsConnection.onopen = () => {
        console.log('[CodeRunner] WebSocket connected')
        // Start the terminal session first
        wsConnection.send(JSON.stringify({
          type: 'start',
          cols: 80,
          rows: 24
        }))
        resolve(wsConnection)
      }
      
      wsConnection.onerror = (error) => {
        console.error('[CodeRunner] WebSocket error:', error)
        reject(error)
      }
      
      wsConnection.onmessage = (event) => {
        const message = JSON.parse(event.data)
        console.log('[CodeRunner] WebSocket message:', message.type)
      }
      
      wsConnection.onclose = () => {
        console.log('[CodeRunner] WebSocket closed')
        wsConnection = null
      }
    } catch (error) {
      console.error('[CodeRunner] Failed to create WebSocket:', error)
      reject(error)
    }
  })
}

function handleRun() {
  console.log('[CodeRunner] handleRun called. canRun:', canRun.value, 'isTerminalAvailable:', isTerminalAvailable.value, 'mainCode:', mainCode, 'mainCodeLength:', mainCode?.length || 0)
  if (!canRun.value) {
    console.warn('[CodeRunner] Cannot run: either terminal unavailable or code is empty')
    return
  }
  
  const slideId = String(props.slideId || 'default')
  console.log('[CodeRunner] Attempting to execute code for slideId:', slideId, 'language:', language)
  
  // Dispatch event for backward compatibility (in case RunTerminalComponent is loaded)
  if (language === 'java') {
    const timestamp = Date.now()
    const random = Math.random().toString(36).substring(7)
    console.log('[CodeRunner] Dispatching java-run event... with fullCode ' + fullCode.value)
    const eventData = {
      type: 'java-run',
      code: fullCode.value,
      tempFile: `/tmp/CodeDemo_${slideId}_${timestamp}_${random}.java`,
      enablePreview: props.enablePreview,
      addModules: props.addModules,
      language: 'java',
      additionalFiles: additionalFilesMap.value,
      slideId,
      cwd: '/tmp/slides'
    }
    window.dispatchEvent(new CustomEvent('terminal:code-run', { detail: eventData }))
    
    // Also try direct WebSocket connection as fallback
    console.log('[CodeRunner] Attempting direct WebSocket submission...')
    ensureWebSocketConnection()
      .then((ws) => {
        console.log('[CodeRunner] Sending java-run via WebSocket')
        ws.send(JSON.stringify(eventData))
      })
      .catch((error) => {
        console.warn('[CodeRunner] WebSocket submission failed, relying on event dispatch:', error)
      })
  } else {
    console.log('[CodeRunner] Dispatching execute-direct event for language:', language)
    const eventData = {
      type: 'execute-direct',
      command: fullCode.value,
      language,
      additionalFiles: additionalFilesMap.value,
      slideId,
      cwd: '/tmp/slides'
    }
    window.dispatchEvent(new CustomEvent('terminal:code-run', { detail: eventData }))
    
    // Also try direct WebSocket connection as fallback
    console.log('[CodeRunner] Attempting direct WebSocket submission...')
    ensureWebSocketConnection()
      .then((ws) => {
        console.log('[CodeRunner] Sending execute-direct via WebSocket')
        ws.send(JSON.stringify(eventData))
      })
      .catch((error) => {
        console.warn('[CodeRunner] WebSocket submission failed, relying on event dispatch:', error)
      })
  }
  
  console.log('[CodeRunner] Event dispatched successfully')
  emit('run', { fullCode: fullCode.value, language, isDirect: language !== 'java', additionalFiles: additionalFilesMap.value, slideId })
  console.log('[CodeRunner] Emit complete')
}

function toggleShowFinished() {
  showFinished.value = !showFinished.value
}

onMounted(() => {
  // Initialize global health check only once
  initializeGlobalHealthCheck()
  
  // Initialize CodeMirror editor
  if (editorContainer.value) {
    const syncPlugin = EditorView.updateListener.of((update) => {
      if (update.docChanged) {
        editableCode.value = update.state.doc.toString()
      }
    })
    
    // Add custom keybindings directly to CodeMirror
    const customKeymap = keymap.of([
      {
        key: 'Ctrl-u',
        mac: 'Cmd-u',
        run: () => {
          if (hasFinished.value) {
            toggleShowFinished()
          }
          return true
        }
      },
      {
        key: 'Ctrl-Enter',
        mac: 'Cmd-Enter',
        run: () => {
          handleRun()
          return true
        }
      },
      // Prevent arrow keys from triggering slide navigation
      {
        key: 'ArrowLeft',
        run: () => true
      },
      {
        key: 'ArrowRight',
        run: () => true
      },
      {
        key: 'ArrowUp',
        run: () => true
      },
      {
        key: 'ArrowDown',
        run: () => true
      },
      {
        key: 'Enter',
        run: indentMore
      },
      // allow Tab to indent
      indentWithTab
    ])
    
    // Add a small extension to disable active-line highlighting by default
    const noActiveLine = EditorView.theme({
      // default: no active-line highlight
      '.cm-activeLine, .cm-line.cm-activeLine, .cm-content .cm-activeLine': {
        backgroundColor: 'transparent',
        boxShadow: 'none',
        outline: 'none'
      },
      '.cm-gutters .cm-activeLineGutter': {
        backgroundColor: 'transparent',
        boxShadow: 'none'
      },
      '.cm-activeLine::before, .cm-activeLine::after': {
        background: 'transparent',
        boxShadow: 'none'
      },
      // when the editor receives focus, show a subtle active-line highlight
      '.cm-focused .cm-activeLine, .cm-focused .cm-line.cm-activeLine, .cm-focused .cm-content .cm-activeLine': {
        backgroundColor: 'rgba(100,150,255,0.06)'
      },
      '.cm-focused .cm-gutters .cm-activeLineGutter': {
        backgroundColor: 'transparent'
      }
    })
    const state = EditorState.create({
      doc: editableCode.value,
      extensions: [basicSetup, java(), indentOnInput(), syncPlugin, customKeymap, getCurrentTheme().codemirror, noActiveLine]
    })
    
    editorView.value = new EditorView({
      state,
      parent: editorContainer.value
    })
    
    // Prevent ALL keyboard events from bubbling to Slidev, except specific keys we allow
    // This prevents 'f' (fullscreen), arrow keys (navigation), and other Slidev shortcuts
    if (editorView.value.dom) {
      editorView.value.dom.addEventListener('keydown', (event: KeyboardEvent) => {
        // Allow only Escape to bubble (to exit fullscreen if needed)
        if (event.key !== 'Escape') {
          event.stopPropagation()
        }
      }, true)
      
      // Also capture keypress events to prevent them from bubbling
      editorView.value.dom.addEventListener('keypress', (event: KeyboardEvent) => {
        event.stopPropagation()
      }, true)
    }
  }
  
  onUnmounted(() => {
    if (editorView.value) {
      editorView.value.destroy()
    }
  })
})
</script>

<style scoped>
.code-runner {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  margin: 1.5rem 0;
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
}

.code-wrapper {
  position: relative;
  border: 1px solid rgba(0, 0, 0, 0.1);
  border-radius: 0.5rem;
  overflow: hidden;
  transition: border-color 0.2s;
}

.code-wrapper:hover {
  border-color: rgba(0, 0, 0, 0.2);
}

.code-display {
  position: relative;
  /* Keep an initial vertical size for the code area; configure via --code-initial-height */
  min-height: var(--code-initial-height, 220px);
  max-height: 60vh;
  overflow-y: auto; /* show scrollbar when content exceeds initial height */
  overflow-x: hidden;
  border-radius: 0.5rem;
}

/* CodeMirror styling
 * Theme: Configured via ThemeMirror extension from theme-config.ts
 * Active theme: dracula (dark) or solarized-light (light)
 * Theme extension handles all colors and styling automatically
 */
.code-display :deep(.cm-editor) {
  height: 100% !important;
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace !important;
  font-size: var(--code-font-size-px, 14px) !important;
  line-height: 21px !important;
  user-select: text;
}

.code-display :deep(.cm-content) {
  padding: 1.5rem !important;
  user-select: text;
}

.code-display :deep(.cm-scroller) {
  overflow: auto !important;
}
.code-display :deep(.cm-selectionBackground) {
  background: rgba(100, 150, 255, 0.4) !important;
}

.code-display :deep(.cm-selection-1) {
  background: rgba(100, 150, 255, 0.4) !important;
}

:deep(.kw) {
  color: #569cd6;
  font-weight: 500;
}

:deep(.str) {
  color: #ce9178;
}

:deep(.cmt) {
  color: #6a9955;
}

:deep(.num) {
  color: #b5cea8;
}

.finished-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.85);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 0.5rem;
  z-index: 20;
}

.finished-overlay pre {
  width: 100%;
  height: 100%;
  overflow: auto;
  margin: 0;
  padding: 1.5rem;
  font-size: 0.9rem;
  line-height: 1.5;
  color: #d4d4d4;
}

.finished-label {
  position: absolute;
  top: 0.75rem;
  left: 0.75rem;
  background: #0078d4;
  color: white;
  padding: 0.35rem 0.75rem;
  border-radius: 0.35rem;
  font-size: 0.75rem;
  font-weight: 600;
  font-family: system-ui, -apple-system, sans-serif;
  letter-spacing: 0.5px;
  z-index: 21;
}

.button-group {
  position: absolute;
  top: 0.75rem;
  right: 0.75rem;
  display: flex;
  gap: 0.5rem;
  z-index: 15;
}

.check-button,
.play-button {
  width: 2.5rem;
  height: 2.5rem;
  border: none;
  border-radius: 0.4rem;
  background: #0078d4;
  color: white;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0.8;
  transition: all 0.2s ease;
}

.check-button:hover {
  opacity: 1;
  background: #1084d8;
}

.play-button:hover {
  opacity: 1;
  background: #1084d8;
}

.play-button:disabled {
  background: #808080;
  cursor: not-allowed;
  opacity: 0.5;
}

.help-text {
  display: none;
  font-size: 0.75rem;
  color: #888;
  flex-wrap: wrap;
  align-items: center;
  font-family: system-ui, -apple-system, sans-serif;
}

kbd {
  background: #2d2d30;
  border: 1px solid #555;
  border-radius: 0.25rem;
  padding: 0.1rem 0.4rem;
  font-family: 'Menlo', 'Monaco', monospace;
  font-size: 0.7rem;
  color: #d4d4d4;
}

.status {
  color: #ff9800;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
