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
