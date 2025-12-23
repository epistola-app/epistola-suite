// Re-export ReactDOM/client with named exports
import ReactDOMClientModule from 'react-dom/client';

export const {
  createRoot,
  hydrateRoot,
} = ReactDOMClientModule;

export default ReactDOMClientModule;
