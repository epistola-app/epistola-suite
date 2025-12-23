// Re-export ReactDOM with named exports
import ReactDOMModule from 'react-dom';

export const {
  createPortal,
  flushSync,
  unstable_batchedUpdates,
  version,
  preconnect,
  prefetchDNS,
  preinit,
  preinitModule,
  preload,
  preloadModule,
} = ReactDOMModule;

export default ReactDOMModule;
