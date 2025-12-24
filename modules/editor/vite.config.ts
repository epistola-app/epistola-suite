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
  '@tiptap/core',
  '@tiptap/react',
  '@tiptap/starter-kit',
  '@tiptap/extension-underline',
  '@dnd-kit/core',
  '@dnd-kit/sortable',
  '@dnd-kit/utilities',
  '@floating-ui/dom',
  '@fontsource/inter',
  'uuid',
  '@radix-ui/react-slot',
  'class-variance-authority',
  'clsx',
  'lucide-react',
  'tailwind-merge'
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
    port: 5173,
    cors: true,
    origin: 'http://localhost:5173',
  },
  build: {
    lib: {
      entry: resolve(__dirname, 'src/main/typescript/lib.tsx'),
      name: 'TemplateEditor',
      fileName: 'template-editor',
      formats: ['es'],
    },
    rollupOptions: {
      external: EXTERNALS,
      output: {
        assetFileNames: 'template-editor.[ext]',
      },
    },
  },
})
