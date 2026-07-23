import type { Tour } from '../registry.js';

/**
 * Chapter 1 — Getting oriented. A passive overview (D4): the four always-present
 * regions, non-interactive, click-anywhere-to-advance. Just a mental map before the
 * later chapters have you doing things.
 */
export const orientationTour: Tour = {
  id: 'orientation',
  title: 'Getting oriented',
  summary: 'A quick tour of the four main areas.',
  version: 1,
  passive: true,
  steps: () => [
    {
      target: 'epistola-toolbar',
      title: 'The toolbar',
      body: 'Undo and redo, toggle the live PDF preview, hide block chrome for a clean view, and save. Everything autosaves as you go.',
      side: 'bottom',
    },
    {
      target: 'epistola-sidebar',
      title: 'The sidebar',
      body: 'Three tabs: **Blocks** to add content, **Structure** to see your document as a tree, and the **Inspector** to style whatever’s selected.',
      side: 'right',
    },
    {
      target: 'epistola-canvas',
      title: 'The canvas',
      body: 'This is your document. Click any block to select it, then shape it from the sidebar.',
      side: 'left',
    },
    {
      target: '[data-tour="preview-toggle"]',
      title: 'Live preview',
      body: 'Toggle a live PDF preview to see exactly what will be generated — it updates as you edit.',
      side: 'bottom',
    },
  ],
};
