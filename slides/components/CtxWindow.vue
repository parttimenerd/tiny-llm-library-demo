<template>
  <div class="ctx-window" :style="windowStyle">
    <div
      v-for="(msg, i) in parsedMessages"
      :key="i"
      :class="['ctx-bar', msg.type, msg.pinned ? 'msg-pinned' : '', msg.faded ? 'ctx-faded' : '']"
      :style="msg.style"
    >{{ msg.text }}</div>
    <div v-if="threshold" class="ctx-threshold" />
    <div v-if="overflow" class="ctx-bar empty">⚠️ overflow</div>
    <div v-if="label" class="ctx-label">{{ label }}</div>
  </div>
</template>

<script setup>
/**
 * CtxWindow — declarative context-window diagram.
 *
 * Usage (shorthand string array):
 *   <CtxWindow :messages="['sys:SYS', 'usr:U1', 'ast:A1', 'tool:ls(src)', 'empty:headroom']" />
 *
 * Message format: "<type>:<text>" or "<type>:<text>:pinned" or "<type>:<text>:faded"
 * Types: sys | usr | ast | tool | empty | summary
 *
 * Or pass objects directly:
 *   <CtxWindow :messages="[{type:'sys', text:'SYS'}, {type:'tool', text:'ls', pinned:true}]" />
 *
 * Props:
 *   label     — rotated label on the right (e.g. "CONTEXT WINDOW")
 *   threshold — show the orange dashed threshold line before the last N messages
 *   overflow  — append a ⚠️ overflow bar at the bottom
 *   width     — CSS width (default: auto)
 *   minHeight — CSS min-height (default: 0)
 */
const props = defineProps({
  messages:  { type: Array,  default: () => [] },
  label:     { type: String, default: '' },
  threshold: { type: Boolean, default: false },
  overflow:  { type: Boolean, default: false },
  width:     { type: String, default: 'auto' },
  minHeight: { type: String, default: '0' },
});

const windowStyle = {
  width: props.width,
  minHeight: props.minHeight,
};

const parsedMessages = props.messages.map(m => {
  if (typeof m === 'object') return m;
  const parts = m.split(':');
  const type  = parts[0];
  const rest  = parts.slice(1).join(':');
  const flag  = rest.split(':').pop();
  const text  = (flag === 'pinned' || flag === 'faded')
      ? rest.slice(0, -(flag.length + 1))
      : rest;
  return {
    type,
    text,
    pinned: flag === 'pinned',
    faded:  flag === 'faded',
    style:  type === 'empty' ? 'flex-grow:1' : '',
  };
});
</script>

<style scoped>
.ctx-faded { opacity: 0.4; }
</style>
