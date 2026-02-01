import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path, {resolve} from 'path'

// Shared dependencies loaded via import map - excluded from bundle (React editor)
const REACT_EXTERNALS = [
  'react',
  'react-dom',
  'react-dom/client',
  'react/jsx-runtime',
  'zustand',
  'zustand/middleware/immer',
  'immer',
  '@dnd-kit/core',
  '@dnd-kit/sortable',
  '@dnd-kit/utilities',
  '@floating-ui/dom',
  'uuid',
  '@radix-ui/react-slot',
  '@radix-ui/react-accordion',
  '@radix-ui/react-dialog',
  '@radix-ui/react-label',
  '@radix-ui/react-popover',
  '@radix-ui/react-select',
  '@radix-ui/react-separator',
  '@radix-ui/react-slider',
  '@radix-ui/react-tabs',
  '@radix-ui/react-tooltip',
  'class-variance-authority',
  'clsx',
  'lucide-react',
  'tailwind-merge',
  'embla-carousel-react',
  'react-resizable-panels',
  '@radix-ui/react-direction',
  'motion',
  'radix-ui',
  '@uidotdev/usehooks',
  'zod',
  // Note: CodeMirror and TipTap packages are intentionally NOT externalized.
  // They are bundled directly to avoid multiple @codemirror/state and @tiptap/pm instances.
]

// Externals for vanilla editor (SortableJS loaded via CDN in vanilla-dev.html)
const VANILLA_EXTERNALS = [
  'sortablejs',
]

// Check which build mode we're in
const buildMode = process.env.BUILD_MODE || 'react'

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
  build: buildMode === 'vanilla' ? {
    // Vanilla editor build
    lib: {
      entry: resolve(__dirname, 'src/main/typescript/vanilla-lib.ts'),
      name: 'VanillaTemplateEditor',
      fileName: 'vanilla-template-editor',
      formats: ['es'],
    },
    rollupOptions: {
      external: VANILLA_EXTERNALS,
      output: {
        assetFileNames: 'vanilla-template-editor.[ext]',
      },
    },
  } : {
    // React editor build (default)
    lib: {
      entry: resolve(__dirname, 'src/main/typescript/lib.tsx'),
      name: 'TemplateEditor',
      fileName: 'template-editor',
      formats: ['es'],
    },
    rollupOptions: {
      external: REACT_EXTERNALS,
      output: {
        assetFileNames: 'template-editor.[ext]',
      },
    },
  },
})
