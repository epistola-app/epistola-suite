/**
 * Rich text editing module.
 *
 * Provides TipTap-based rich text editing with expression support.
 */

export { ExpressionNode } from "./expression-node.ts";
export type { ExpressionEvaluator, ExpressionNodeOptions } from "./expression-node.ts";

export {
  createRichTextEditor,
  contentToHTML,
  contentToText,
  createEmptyContent,
  createTextContent,
} from "./editor.ts";
export type { RichTextEditorOptions, RichTextEditor } from "./editor.ts";
