/**
 * @epistola/headless-editor
 *
 * Pure TypeScript, framework-agnostic editor core.
 * No DOM dependencies - pair with any rendering adapter.
 * Single dependency: nanostores (reactive state).
 */

// Main editor class
export { TemplateEditor } from './editor.js';

// Store utilities
export { createEditorStore, BlockTree } from './store.js';
export type { EditorStore } from './store.js';

// Undo manager
export { UndoManager } from './undo.js';

// Block definitions
export {
  defaultBlockDefinitions,
  textBlockDefinition,
  containerBlockDefinition,
  conditionalBlockDefinition,
  loopBlockDefinition,
  columnsBlockDefinition,
  tableBlockDefinition,
  pageBreakBlockDefinition,
  pageHeaderBlockDefinition,
  pageFooterBlockDefinition,
  blockHelpers,
} from './blocks/index.js';

// Types
export type {
  // CSS types
  CSSStyles,
  // Expression types
  ExpressionLanguage,
  Expression,
  // Block types
  BaseBlock,
  TextBlock,
  ContainerBlock,
  ConditionalBlock,
  LoopBlock,
  Column,
  ColumnsBlock,
  TableCell,
  TableRow,
  TableBlock,
  PageBreakBlock,
  PageHeaderBlock,
  PageFooterBlock,
  Block,
  BlockType,
  // Template types
  PageSettings,
  DocumentStyles,
  Template,
  // Constraint types
  BlockConstraints,
  ValidationResult,
  BlockDefinition,
  // Drag & drop types
  DropPosition,
  DropZone,
  DragDropPort,
  // JSON types
  JsonValue,
  JsonObject,
  JsonArray,
  // Data example types
  DataExample,
  // Editor state types
  EditorState,
  EditorCallbacks,
  EditorConfig,
  // JSON Schema types
  SchemaFieldType,
  JsonSchemaProperty,
  JsonSchema,
} from './types.js';

// Constants
export { DEFAULT_TEST_DATA } from './types.js';
