import type { Tour } from '../registry.js';

/**
 * Chapter 1 — Getting oriented. Highlights the four always-present regions so a
 * first-time user has a mental map before doing anything.
 */
export const orientationTour: Tour = {
  id: 'orientation',
  title: 'Getting oriented',
  summary: 'The four main areas of the editor.',
  version: 1,
  steps: () => [
    {
      target: 'epistola-toolbar',
      title: 'The toolbar',
      body: 'Undo and redo, toggle the live preview, hide block chrome, and save — all from up here.',
      side: 'bottom',
    },
    {
      target: 'epistola-sidebar',
      title: 'The sidebar',
      body: 'Three tabs: add blocks, browse the document structure, and edit the selected block’s properties.',
      side: 'right',
    },
    {
      target: 'epistola-canvas',
      title: 'The canvas',
      body: 'This is your document. Click a block to select it, then edit it over in the sidebar.',
      side: 'left',
    },
    {
      target: '[data-tour="preview-toggle"]',
      title: 'Live preview',
      body: 'Open a live PDF preview to see exactly what will be generated as you edit.',
      side: 'bottom',
    },
  ],
};
