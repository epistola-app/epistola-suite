/**
 * Loop block definition.
 *
 * Repeats content for each item in an array expression.
 */

import type { LoopBlock, Block } from "../types/template.ts";
import type { BlockDefinition } from "./types.ts";
import { registerBlock } from "./registry.ts";

/**
 * Loop block definition.
 */
export const loopBlockDef: BlockDefinition<LoopBlock> = {
  type: "loop",
  label: "Loop",
  category: "logic",
  description: "Repeat content for each item in a list",
  icon: `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <path d="M17 2.1l4 4-4 4"/>
    <path d="M3 12.2v-2a4 4 0 0 1 4-4h12.8"/>
    <path d="M7 21.9l-4-4 4-4"/>
    <path d="M21 11.8v2a4 4 0 0 1-4 4H4.2"/>
  </svg>`,

  createDefault: (): LoopBlock => ({
    id: crypto.randomUUID(),
    type: "loop",
    expression: { raw: "" },
    itemAlias: "item",
    indexAlias: "index",
    children: [],
  }),

  getChildren: (block: LoopBlock): Block[] => block.children,

  setChildren: (block: LoopBlock, children: Block[]): LoopBlock => ({
    ...block,
    children,
  }),

  canContain: () => true, // Can contain any block type
};

/**
 * Register the loop block.
 */
export function registerLoopBlock(): void {
  registerBlock(loopBlockDef);
}
