/**
 * Page break block definition.
 *
 * Forces a page break in PDF output.
 */

import type { PageBreakBlock } from "../types/template.ts";
import type { BlockDefinition } from "./types.ts";
import { registerBlock } from "./registry.ts";

/**
 * Page break block definition.
 */
export const pageBreakBlockDef: BlockDefinition<PageBreakBlock> = {
  type: "pagebreak",
  label: "Page Break",
  category: "layout",
  description: "Force a page break in PDF output",
  icon: `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <polyline points="17 11 12 6 7 11"/>
    <polyline points="17 18 12 13 7 18"/>
  </svg>`,

  createDefault: (): PageBreakBlock => ({
    id: crypto.randomUUID(),
    type: "pagebreak",
  }),

  // Page break is a leaf node - no children
  getChildren: undefined,
  setChildren: undefined,
};

/**
 * Register the page break block.
 */
export function registerPageBreakBlock(): void {
  registerBlock(pageBreakBlockDef);
}
