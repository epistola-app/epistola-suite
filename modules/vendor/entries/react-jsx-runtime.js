// Re-export React JSX runtime with named exports
import JSXRuntimeModule from 'react/jsx-runtime';

export const {
  jsx,
  jsxs,
  Fragment,
} = JSXRuntimeModule;

export default JSXRuntimeModule;
