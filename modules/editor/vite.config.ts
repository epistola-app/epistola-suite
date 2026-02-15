import { defineConfig } from 'vitest/config'
import path, { resolve } from 'path'

// https://vite.dev/config/
export default defineConfig({
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src/main/typescript'),
      '@design': path.resolve(__dirname, '../design-system'),
    },
  },
  optimizeDeps: {
    include: ['jsonata'],
  },
  server: {
    port: 5174,
    cors: true,
    origin: 'http://localhost:5174',
  },
  build: {
    lib: {
      entry: resolve(__dirname, 'src/main/typescript/lib.ts'),
      name: 'TemplateEditor',
      fileName: 'template-editor',
      formats: ['es'],
    },
    rollupOptions: {
      output: {
        assetFileNames: 'template-editor.[ext]',
      },
    },
  },
  test: {
    globals: true,
    environment: 'node',
    include: ['src/main/typescript/**/*.test.ts'],
  },
})
