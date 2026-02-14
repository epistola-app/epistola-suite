import { defineConfig } from 'vitest/config'
import tailwindcss from '@tailwindcss/vite'
import path, { resolve } from 'path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src/main/typescript'),
    },
  },
  server: {
    port: 5174,
    cors: true,
    origin: 'http://localhost:5174',
  },
  build: {
    lib: {
      entry: resolve(__dirname, 'src/main/typescript/lib.ts'),
      name: 'TemplateEditorV2',
      fileName: 'template-editor-v2',
      formats: ['es'],
    },
    rollupOptions: {
      output: {
        assetFileNames: 'template-editor-v2.[ext]',
      },
    },
  },
  test: {
    globals: true,
    environment: 'node',
    include: ['src/main/typescript/**/*.test.ts'],
  },
})
