/**
 * Re-export all model types from the shared @epistola.app/epistola-model package.
 * This is the single import point for all type references within the editor.
 */
export type {
  NodeId,
  SlotId,
  TemplateDocument,
  Node,
  Slot,
  ThemeRef,
  ThemeRefInherit,
  ThemeRefOverride,
  PageFormat,
  Orientation,
  Margins,
  PageSettings,
  DocumentStyles,
  ExpressionLanguage,
  Expression,
} from '@epistola.app/epistola-model';
