import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
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
      entry: resolve(__dirname, 'src/lib.tsx'),
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
})
