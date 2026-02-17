import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path, {resolve} from 'path'

// All dependencies are bundled — no import map needed
const EXTERNALS: string[] = []

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
