/**
 * Text block definition.
 *
 * Rich text content using TipTap editor.
 */

import type { TextBlock } from "../types/template.ts";
import type { BlockDefinition } from "./types.ts";
import { registerBlock } from "./registry.ts";
import { createEmptyDocument } from "../types/richtext.ts";

/**
 * Text block definition.
 */
export const textBlockDef: BlockDefinition<TextBlock> = {
  type: "text",
  label: "Text",
  category: "content",
  description: "Rich text with formatting and expressions",
  icon: `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <polyline points="4 7 4 4 20 4 20 7"/>
    <line x1="9" y1="20" x2="15" y2="20"/>
    <line x1="12" y1="4" x2="12" y2="20"/>
  </svg>`,

  createDefault: (): TextBlock => ({
    id: crypto.randomUUID(),
    type: "text",
    content: createEmptyDocument(),
  }),

  // Text blocks are leaf nodes - no children
  getChildren: undefined,
  setChildren: undefined,
};

/**
 * Register the text block.
 */
export function registerTextBlock(): void {
  registerBlock(textBlockDef);
}
