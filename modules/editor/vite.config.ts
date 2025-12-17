import { defineConfig } from 'vite'

export default defineConfig({
  build: {
    lib: {
      entry: 'src/main.ts',
      name: 'EpistolaEditor',
      fileName: 'editor',
      formats: ['es', 'umd']
    },
    outDir: 'dist',
    emptyDirBeforeWrite: true
  }
})
