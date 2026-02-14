export { EditorEngine, type EngineListener } from './EditorEngine.js'
export {
  type Command,
  type InsertNode,
  type RemoveNode,
  type MoveNode,
  type UpdateNodeProps,
  type UpdateNodeStyles,
  type SetStylePreset,
  type UpdateDocumentStyles,
  type UpdatePageSettings,
  type CommandResult,
  type CommandOk,
  type CommandError,
} from './commands.js'
export {
  type DocumentIndexes,
  buildIndexes,
  getAncestorPath,
  isAncestor,
  getNodeDepth,
  findAncestorAtLevel,
} from './indexes.js'
export { UndoStack } from './undo.js'
export {
  ComponentRegistry,
  createDefaultRegistry,
  type ComponentDefinition,
  type ComponentCategory,
  type AllowedChildren,
  type StylePolicy,
  type SlotTemplate,
  type InspectorField,
} from './registry.js'
export { deepFreeze } from './freeze.js'
export { getNestedValue, setNestedValue } from './props.js'
