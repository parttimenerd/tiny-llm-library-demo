/**
 * Theme configuration for CodeRunner's CodeMirror editor.
 *
 * This module is consumed by components/CodeRunner.vue.
 * It provides CodeMirror theme extensions that match the
 * presentation's overall look-and-feel.
 */

import { EditorView } from '@codemirror/view'

/* ------------------------------------------------------------------ */
/*  CodeMirror themes                                                 */
/* ------------------------------------------------------------------ */

/** Light theme – matches the default Slidev light mode. */
const lightCodemirror = EditorView.theme(
  {
    '&': {
      backgroundColor: '#ffffff',
      color: '#1e293b',
    },
    '.cm-content': {
      caretColor: '#1e293b',
    },
    '.cm-cursor, .cm-dropCursor': {
      borderLeftColor: '#1e293b',
    },
    '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection':
      {
        backgroundColor: '#c8daf8',
      },
    '.cm-gutters': {
      backgroundColor: '#f8fafc',
      color: '#94a3b8',
      borderRight: '1px solid #e2e8f0',
    },
    '.cm-activeLineGutter': {
      backgroundColor: '#f1f5f9',
    },
  },
  { dark: false },
)

/** Dark theme – available if the presentation ever switches to dark mode. */
const darkCodemirror = EditorView.theme(
  {
    '&': {
      backgroundColor: '#1e293b',
      color: '#e2e8f0',
    },
    '.cm-content': {
      caretColor: '#e2e8f0',
    },
    '.cm-cursor, .cm-dropCursor': {
      borderLeftColor: '#e2e8f0',
    },
    '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection':
      {
        backgroundColor: '#334155',
      },
    '.cm-gutters': {
      backgroundColor: '#0f172a',
      color: '#64748b',
      borderRight: '1px solid #334155',
    },
    '.cm-activeLineGutter': {
      backgroundColor: '#1e293b',
    },
  },
  { dark: true },
)

/* ------------------------------------------------------------------ */
/*  Theme descriptors                                                 */
/* ------------------------------------------------------------------ */

export interface ThemeDescriptor {
  name: string
  codemirror: ReturnType<typeof EditorView.theme>
  shiki: string
}

const LIGHT_THEME: ThemeDescriptor = {
  name: 'light',
  codemirror: lightCodemirror,
  shiki: 'github-light',
}

const DARK_THEME: ThemeDescriptor = {
  name: 'dark',
  codemirror: darkCodemirror,
  shiki: 'github-dark',
}

/* ------------------------------------------------------------------ */
/*  Public API (consumed by CodeRunner.vue)                           */
/* ------------------------------------------------------------------ */

/** All available themes. */
export const ALL_THEMES: ThemeDescriptor[] = [LIGHT_THEME, DARK_THEME]

/** The currently active theme (light by default). */
export const ACTIVE_THEME: ThemeDescriptor = LIGHT_THEME

/** Return the active theme descriptor. */
export function getCurrentTheme(): ThemeDescriptor {
  return ACTIVE_THEME
}

/** Return the Shiki theme name for the active theme. */
export function getShikiTheme(): string {
  return ACTIVE_THEME.shiki
}
