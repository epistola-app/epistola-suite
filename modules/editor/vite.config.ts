import { defineConfig } from 'vitest/config'
import path, { resolve } from 'path'

// https://vite.dev/config/
export default defineConfig(({ mode }) => ({
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src/main/typescript'),
      '@design': path.resolve(__dirname, '../design-system'),
    },
  },
  // Replace process.env.NODE_ENV for browser bundles (some dependencies reference it).
  // Excluded during tests — it causes Vitest v4 to set import.meta.env.PROD = true,
  // which disables deepFreeze and breaks the immutability test.
  define: mode !== 'test' ? {
    'process.env.NODE_ENV': JSON.stringify('production'),
  } : {},
  optimizeDeps: {
    include: ['jsonata'],
  },
  server: {
    port: 5174,
    cors: true,
    origin: 'http://localhost:5174',
  },
  build: {
    minify: 'esbuild',
    lib: {
      entry: {
        'template-editor': resolve(__dirname, 'src/main/typescript/lib.ts'),
        'theme-editor': resolve(__dirname, 'src/main/typescript/theme-editor-lib.ts'),
      },
      formats: ['es'],
    },
    rollupOptions: {
      output: {
        assetFileNames: '[name].[ext]',
      },
    },
    cssCodeSplit: true,
  },
  test: {
    globals: true,
    environment: 'node',
    include: ['src/main/typescript/**/*.test.ts'],
  },
}))
