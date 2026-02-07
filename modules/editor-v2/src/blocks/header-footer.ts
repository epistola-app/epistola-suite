/**
 * Page header and footer block definitions.
 *
 * Content that appears on every page of PDF output.
 */

import type {
  PageHeaderBlock,
  PageFooterBlock,
  Block,
} from "../types/template.ts";
import type { BlockDefinition } from "./types.ts";
import { registerBlock } from "./registry.ts";

/**
 * Page header block definition.
 */
export const pageHeaderBlockDef: BlockDefinition<PageHeaderBlock> = {
  type: "pageheader",
  label: "Page Header",
  category: "layout",
  description: "Content shown at top of every page",
  icon: `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
    <line x1="3" y1="9" x2="21" y2="9"/>
  </svg>`,

  createDefault: (): PageHeaderBlock => ({
    id: crypto.randomUUID(),
    type: "pageheader",
    children: [],
  }),

  getChildren: (block: PageHeaderBlock): Block[] => block.children,

  setChildren: (
    block: PageHeaderBlock,
    children: Block[],
  ): PageHeaderBlock => ({
    ...block,
    children,
  }),

  canContain: () => true,
};

/**
 * Page footer block definition.
 */
export const pageFooterBlockDef: BlockDefinition<PageFooterBlock> = {
  type: "pagefooter",
  label: "Page Footer",
  category: "layout",
  description: "Content shown at bottom of every page",
  icon: `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
    <line x1="3" y1="15" x2="21" y2="15"/>
  </svg>`,

  createDefault: (): PageFooterBlock => ({
    id: crypto.randomUUID(),
    type: "pagefooter",
    children: [],
  }),

  getChildren: (block: PageFooterBlock): Block[] => block.children,

  setChildren: (
    block: PageFooterBlock,
    children: Block[],
  ): PageFooterBlock => ({
    ...block,
    children,
  }),

  canContain: () => true,
};

/**
 * Register the page header block.
 */
export function registerPageHeaderBlock(): void {
  registerBlock(pageHeaderBlockDef);
}

/**
 * Register the page footer block.
 */
export function registerPageFooterBlock(): void {
  registerBlock(pageFooterBlockDef);
}
