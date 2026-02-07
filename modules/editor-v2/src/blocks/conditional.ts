/**
 * Conditional block definition.
 *
 * Shows or hides content based on an expression evaluation.
 */

import type { ConditionalBlock, Block } from "../types/template.ts";
import type { BlockDefinition } from "./types.ts";
import { registerBlock } from "./registry.ts";

/**
 * Conditional block definition.
 */
export const conditionalBlockDef: BlockDefinition<ConditionalBlock> = {
  type: "conditional",
  label: "Conditional",
  category: "logic",
  description: "Show content based on a condition",
  icon: `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <path d="M16 3h5v5"/>
    <path d="M8 3H3v5"/>
    <path d="M12 22v-8.3a4 4 0 0 0-1.172-2.872L3 3"/>
    <path d="m15 9 6-6"/>
  </svg>`,

  createDefault: (): ConditionalBlock => ({
    id: crypto.randomUUID(),
    type: "conditional",
    condition: { raw: "" },
    inverse: false,
    children: [],
  }),

  getChildren: (block: ConditionalBlock): Block[] => block.children,

  setChildren: (
    block: ConditionalBlock,
    children: Block[],
  ): ConditionalBlock => ({
    ...block,
    children,
  }),

  canContain: () => true, // Can contain any block type
};

/**
 * Register the conditional block.
 */
export function registerConditionalBlock(): void {
  registerBlock(conditionalBlockDef);
}
