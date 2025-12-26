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

// Suppress "use client" directive warnings from React Server Components
function onwarn(warning, warn) {
  if (warning.code === 'MODULE_LEVEL_DIRECTIVE' && warning.message.includes('"use client"')) {
    return;
  }
  warn(warning);
}

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
  // radix-ui/react-slot
  {
    input: 'node_modules/@radix-ui/react-slot/dist/index.mjs',
    output: { file: 'dist/radix-ui-react-slot.js', format: 'esm' },
    external: ['react'],
    plugins,
  },
  // class-variance-authority
  {
    input: 'node_modules/class-variance-authority/dist/index.mjs',
    output: { file: 'dist/class-variance-authority.js', format: 'esm' },
    external: ['react'],
    plugins,
  },
  // clsx
  {
    input: 'node_modules/clsx/dist/clsx.mjs',
    output: { file: 'dist/clsx.js', format: 'esm' },
    external: ['react'],
    plugins,
  },
  // lucide-react
  {
    input: 'entries/lucide.js',
    output: { file: 'dist/lucide-react.js', format: 'esm' },
    external: ['react'],
    plugins,
  },
  // tailwind-merge
  {
    input: 'node_modules/tailwind-merge/dist/bundle-mjs.mjs',
    output: { file: 'dist/tailwind-merge.js', format: 'esm' },
    external: ['react'],
    plugins,
  },
  // radix-ui/react-accordion
  {
    input: 'node_modules/@radix-ui/react-accordion/dist/index.mjs',
    output: { file: 'dist/radix-ui-react-accordion.js', format: 'esm' },
    external: ['react', 'react-dom', '@radix-ui/react-slot'],
    plugins,
  },
  // radix-ui/react-dialog
  {
    input: 'node_modules/@radix-ui/react-dialog/dist/index.mjs',
    output: { file: 'dist/radix-ui-react-dialog.js', format: 'esm' },
    external: ['react', 'react-dom', '@radix-ui/react-slot'],
    plugins,
  },
  // radix-ui/react-label
  {
    input: 'node_modules/@radix-ui/react-label/dist/index.mjs',
    output: { file: 'dist/radix-ui-react-label.js', format: 'esm' },
    external: ['react', 'react-dom'],
    plugins,
  },
  // radix-ui/react-popover
  {
    input: 'node_modules/@radix-ui/react-popover/dist/index.mjs',
    output: { file: 'dist/radix-ui-react-popover.js', format: 'esm' },
    external: ['react', 'react-dom', '@radix-ui/react-slot'],
    plugins,
  },
  // radix-ui/react-select
  {
    input: 'node_modules/@radix-ui/react-select/dist/index.mjs',
    output: { file: 'dist/radix-ui-react-select.js', format: 'esm' },
    external: ['react', 'react-dom', '@radix-ui/react-slot'],
    plugins,
  },
  // radix-ui/react-separator
  {
    input: 'node_modules/@radix-ui/react-separator/dist/index.mjs',
    output: { file: 'dist/radix-ui-react-separator.js', format: 'esm' },
    external: ['react', 'react-dom'],
    plugins,
  },
  // radix-ui/react-slider
  {
    input: 'node_modules/@radix-ui/react-slider/dist/index.mjs',
    output: { file: 'dist/radix-ui-react-slider.js', format: 'esm' },
    external: ['react', 'react-dom'],
    plugins,
  },
  // radix-ui/react-tabs
  {
    input: 'node_modules/@radix-ui/react-tabs/dist/index.mjs',
    output: { file: 'dist/radix-ui-react-tabs.js', format: 'esm' },
    external: ['react', 'react-dom'],
    plugins,
  },
  // radix-ui/react-tooltip
  {
    input: 'node_modules/@radix-ui/react-tooltip/dist/index.mjs',
    output: { file: 'dist/radix-ui-react-tooltip.js', format: 'esm' },
    external: ['react', 'react-dom', '@radix-ui/react-slot'],
    plugins,
  },
  // embla-carousel-react
  {
    input: 'entries/embla-carousel-react.js',
    output: { file: 'dist/embla-carousel-react.js', format: 'esm' },
    external: ['react'],
    plugins,
  },
  // react-resizable-panels
  {
    input: 'entries/react-resizable-panels.js',
    output: { file: 'dist/react-resizable-panels.js', format: 'esm' },
    external: ['react', 'react-dom'],
    plugins,
  },
  // radix-ui/react-direction
  {
    input: 'node_modules/@radix-ui/react-direction/dist/index.mjs',
    output: { file: 'dist/radix-ui-react-direction.js', format: 'esm' },
    external: ['react'],
    plugins,
  },
  // motion (framer-motion successor)
  {
    input: 'entries/motion.js',
    output: { file: 'dist/motion.js', format: 'esm' },
    external: ['react', 'react-dom'],
    plugins,
  },
  // radix-ui (meta package)
  {
    input: 'entries/radix-ui.js',
    output: { file: 'dist/radix-ui.js', format: 'esm' },
    external: ['react', 'react-dom'],
    plugins,
  },
  // @uidotdev/usehooks
  {
    input: 'entries/uidotdev.js',
    output: { file: 'dist/uidotdev.js', format: 'esm' },
    external: ['react'],
    plugins,
  },
].map(config => ({ ...config, onwarn }));
