// Core editor
export { TemplateEditor } from './core/editor.js';

// Types
export type {
  Block,
  BaseBlock,
  TextBlock,
  ContainerBlock,
  ColumnsBlock,
  ColumnBlock,
  Template,
  BlockDefinition,
  BlockConstraints,
  ValidationResult,
  EditorState,
  EditorConfig,
  EditorCallbacks,
  // Drag & Drop Port
  DropPosition,
  DropZone,
  DragDropPort,
} from './core/types.js';

// Default block definitions (users can extend or replace)
export {
  defaultBlockDefinitions,
  textBlockDefinition,
  containerBlockDefinition,
  columnsBlockDefinition,
  columnBlockDefinition,
} from './core/blocks.js';

// Store utilities (for advanced users)
export { BlockTree } from './core/store.js';
