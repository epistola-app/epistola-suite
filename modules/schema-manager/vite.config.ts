import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path, { resolve } from 'path'

// Shared dependencies loaded via import map - excluded from bundle
const EXTERNALS = [
  'react',
  'react-dom',
  'react-dom/client',
  'react/jsx-runtime',
  'zustand',
  'zustand/middleware/immer',
  'immer',
  '@floating-ui/dom',
  'uuid',
  '@radix-ui/react-slot',
  '@radix-ui/react-accordion',
  '@radix-ui/react-dialog',
  '@radix-ui/react-label',
  '@radix-ui/react-popover',
  '@radix-ui/react-select',
  '@radix-ui/react-separator',
  '@radix-ui/react-tabs',
  '@radix-ui/react-tooltip',
  'class-variance-authority',
  'clsx',
  'lucide-react',
  'tailwind-merge',
  '@radix-ui/react-direction',
  'motion',
  'radix-ui',
  'zod',
  // Note: CodeMirror packages are intentionally NOT externalized.
  // They are bundled directly to avoid multiple @codemirror/state instances.
]

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src/main/typescript"),
    },
  },
  define: {
    // Define process.env for browser compatibility (some deps check NODE_ENV)
    'process.env.NODE_ENV': JSON.stringify('production'),
  },
  server: {
    port: 5174,
    cors: true,
    origin: 'http://localhost:5174',
  },
  build: {
    lib: {
      entry: resolve(__dirname, 'src/main/typescript/lib.tsx'),
      name: 'SchemaManager',
      fileName: 'schema-manager',
      formats: ['es'],
    },
    rollupOptions: {
      external: EXTERNALS,
      output: {
        assetFileNames: 'schema-manager.[ext]',
      },
    },
  },
})
