import { defineConfig } from "vite";
import path, { resolve } from "path";

// https://vite.dev/config/
export default defineConfig({
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  define: {
    // Define process.env for browser compatibility (some deps check NODE_ENV)
    "process.env.NODE_ENV": JSON.stringify("production"),
  },
  server: {
    port: 5174,
    cors: true,
    origin: "http://localhost:5174",
  },
  build: {
    lib: {
      entry: resolve(__dirname, "src/index.ts"),
      name: "TemplateEditorV2",
      fileName: "template-editor-v2",
      formats: ["es"],
    },
    rollupOptions: {
      output: {
        assetFileNames: "template-editor-v2.[ext]",
      },
    },
  },
});
