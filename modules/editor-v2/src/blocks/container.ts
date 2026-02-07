/**
 * Container block definition.
 *
 * A simple container that holds child blocks.
 * Used for grouping and applying shared styles.
 */

import type { ContainerBlock, Block } from "../types/template.ts";
import type { BlockDefinition } from "./types.ts";
import { registerBlock } from "./registry.ts";

/**
 * Container block definition.
 */
export const containerBlockDef: BlockDefinition<ContainerBlock> = {
  type: "container",
  label: "Container",
  category: "structure",
  description: "A container for grouping blocks together",
  icon: `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
  </svg>`,

  createDefault: (): ContainerBlock => ({
    id: crypto.randomUUID(),
    type: "container",
    children: [],
  }),

  getChildren: (block: ContainerBlock): Block[] => block.children,

  setChildren: (block: ContainerBlock, children: Block[]): ContainerBlock => ({
    ...block,
    children,
  }),

  canContain: () => true, // Container can contain any block type
};

/**
 * Register the container block.
 */
export function registerContainerBlock(): void {
  registerBlock(containerBlockDef);
}
