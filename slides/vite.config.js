import { defineConfig } from 'vite'

export default defineConfig({
  server: {
    host: '0.0.0.0',
    strictPort: false,
    hmr: {
      clientPort: 3032
    }
  },
  ssr: {
    noExternal: ['xterm']
  }
})
