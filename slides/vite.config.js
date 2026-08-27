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
  },
  build: {
    rollupOptions: {
      external: (id) => {
        if (id.startsWith('node:') || id === 'module') return true
        const nodeOnlyPkgs = ['fsevents', 'fdir', 'tinyglobby', 'tinyexec',
          'fast-glob', 'chokidar', 'glob', 'readdirp', '@iconify/utils', 'colorette', 'totalist']
        return nodeOnlyPkgs.some(p => id === p || id.startsWith(p + '/'))
      }
    }
  }
})
