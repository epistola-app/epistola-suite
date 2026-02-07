/**
 * Block registry and definitions.
 *
 * This module provides:
 * - Block type registration system
 * - Generic tree traversal operations
 * - Built-in block definitions
 */

// Types
export type {
  BlockDefinition,
  BlockCategory,
  RenderContext,
  ChildContainerRef,
  MultiContainerOps,
  MultiContainerBlockDefinition,
} from "./types.ts";

// Registry
export {
  registry,
  registerBlock,
  unregisterBlock,
  createBlock,
  getBlockLabel,
  getBlockIcon,
  canContain,
} from "./registry.ts";

// Tree operations
export {
  findBlock,
  findBlockLocation,
  getChildren,
  updateBlock,
  insertBlock,
  removeBlock,
  moveBlock,
  walkTree,
  countBlocks,
  findBlocksByType,
  getBlockPath,
} from "./tree.ts";
export type { BlockLocation } from "./tree.ts";

// Block definitions
export { containerBlockDef, registerContainerBlock } from "./container.ts";
export { textBlockDef, registerTextBlock } from "./text.ts";
export { conditionalBlockDef, registerConditionalBlock } from "./conditional.ts";
export { loopBlockDef, registerLoopBlock } from "./loop.ts";
export {
  columnsBlockDef,
  registerColumnsBlock,
  addColumn,
  removeColumn,
  setColumnSize,
} from "./columns.ts";
export {
  tableBlockDef,
  registerTableBlock,
  addTableRow,
  removeTableRow,
  addTableColumn,
  removeTableColumn,
  getCell,
} from "./table.ts";
export { pageBreakBlockDef, registerPageBreakBlock } from "./pagebreak.ts";
export {
  pageHeaderBlockDef,
  pageFooterBlockDef,
  registerPageHeaderBlock,
  registerPageFooterBlock,
} from "./header-footer.ts";

// Import for registration
import { registerContainerBlock } from "./container.ts";
import { registerTextBlock } from "./text.ts";
import { registerConditionalBlock } from "./conditional.ts";
import { registerLoopBlock } from "./loop.ts";
import { registerColumnsBlock } from "./columns.ts";
import { registerTableBlock } from "./table.ts";
import { registerPageBreakBlock } from "./pagebreak.ts";
import { registerPageHeaderBlock, registerPageFooterBlock } from "./header-footer.ts";

/**
 * Register all built-in block types.
 *
 * Call this once during application initialization.
 *
 * @example
 * ```typescript
 * import { registerAllBlocks } from './blocks';
 *
 * // At app startup
 * registerAllBlocks();
 * ```
 */
export function registerAllBlocks(): void {
  // Content blocks
  registerTextBlock();

  // Structure blocks
  registerContainerBlock();
  registerColumnsBlock();

  // Logic blocks
  registerConditionalBlock();
  registerLoopBlock();

  // Data blocks
  registerTableBlock();

  // Layout blocks
  registerPageBreakBlock();
  registerPageHeaderBlock();
  registerPageFooterBlock();
}
