import resolve from '@rollup/plugin-node-resolve';
import commonjs from '@rollup/plugin-commonjs';
import replace from '@rollup/plugin-replace';
import terser from '@rollup/plugin-terser';

const plugins = [
  replace({
    preventAssignment: true,
    'process.env.NODE_ENV': JSON.stringify('production'),
  }),
  resolve({ browser: true }),
  commonjs(),
  terser(),
];

export default [
  // React core - uses wrapper for proper named exports
  {
    input: 'entries/react.js',
    output: { file: 'dist/react.js', format: 'esm' },
    plugins,
  },

  // React JSX Runtime - uses wrapper
  {
    input: 'entries/react-jsx-runtime.js',
    output: { file: 'dist/react-jsx-runtime.js', format: 'esm' },
    external: ['react'],
    plugins,
  },

  // React DOM - uses wrapper
  {
    input: 'entries/react-dom.js',
    output: { file: 'dist/react-dom.js', format: 'esm' },
    external: ['react'],
    plugins,
  },

  // React DOM Client - uses wrapper
  {
    input: 'entries/react-dom-client.js',
    output: { file: 'dist/react-dom-client.js', format: 'esm' },
    external: ['react', 'react-dom'],
    plugins,
  },

  // Immer - already ESM, direct import works
  {
    input: 'node_modules/immer/dist/immer.mjs',
    output: { file: 'dist/immer.js', format: 'esm' },
    plugins,
  },

  // Zustand - already ESM
  {
    input: 'node_modules/zustand/esm/index.mjs',
    output: { file: 'dist/zustand.js', format: 'esm' },
    external: ['react', 'use-sync-external-store', 'use-sync-external-store/shim/with-selector'],
    plugins,
  },

  // Zustand Immer middleware - already ESM
  {
    input: 'node_modules/zustand/esm/middleware/immer.mjs',
    output: { file: 'dist/zustand-middleware-immer.js', format: 'esm' },
    external: ['immer', 'zustand'],
    plugins,
  },

  // TipTap packages - separate bundles to preserve default exports
  {
    input: 'entries/tiptap-core.js',
    output: { file: 'dist/tiptap-core.js', format: 'esm' },
    plugins,
  },
  {
    input: 'entries/tiptap-react.js',
    output: { file: 'dist/tiptap-react.js', format: 'esm' },
    external: ['react', 'react-dom', 'react/jsx-runtime', '@tiptap/core'],
    plugins,
  },
  {
    input: 'entries/tiptap-starter-kit.js',
    output: { file: 'dist/tiptap-starter-kit.js', format: 'esm' },
    external: ['@tiptap/core'],
    plugins,
  },
  {
    input: 'entries/tiptap-extension-underline.js',
    output: { file: 'dist/tiptap-extension-underline.js', format: 'esm' },
    external: ['@tiptap/core'],
    plugins,
  },

  // dnd-kit packages
  {
    input: 'node_modules/@dnd-kit/core/dist/core.esm.js',
    output: { file: 'dist/dnd-kit-core.js', format: 'esm' },
    external: ['react', 'react-dom'],
    plugins,
  },
  {
    input: 'node_modules/@dnd-kit/sortable/dist/sortable.esm.js',
    output: { file: 'dist/dnd-kit-sortable.js', format: 'esm' },
    external: ['react', '@dnd-kit/core', '@dnd-kit/utilities'],
    plugins,
  },
  {
    input: 'node_modules/@dnd-kit/utilities/dist/utilities.esm.js',
    output: { file: 'dist/dnd-kit-utilities.js', format: 'esm' },
    external: ['react'],
    plugins,
  },

  // Floating UI
  {
    input: 'node_modules/@floating-ui/dom/dist/floating-ui.dom.mjs',
    output: { file: 'dist/floating-ui-dom.js', format: 'esm' },
    plugins,
  },

  // UUID - use the default dist entry
  {
    input: 'entries/uuid.js',
    output: { file: 'dist/uuid.js', format: 'esm' },
    plugins,
  },
];
