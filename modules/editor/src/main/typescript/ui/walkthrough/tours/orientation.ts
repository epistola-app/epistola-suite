import type { Tour } from '../registry.js';
import { openPreview } from './helpers.js';

/**
 * Chapter 1 — Getting oriented. An overview of the editor: the toolbar (and its
 * right-side tools), the sidebar, the canvas, and the live preview. Read-and-Next,
 * like every chapter — a mental map before the later chapters go deeper.
 */
export const orientationTour: Tour = {
  id: 'orientation',
  title: 'Getting oriented',
  summary: 'A quick tour of the four main areas.',
  version: 1,
  // Open the preview up front (before driving), so the final "preview pane" step's target
  // exists from the start. If it were opened in a step `before`, driver would compute the
  // prior step's button before the pane rendered, wrongly treat that step as last, and
  // mislabel its Next button as the "Next: <next chapter>" done button.
  setup: (ctx) => openPreview(ctx),
  steps: () => [
    {
      target: 'epistola-toolbar',
      title: 'The toolbar',
      body: 'Undo and redo, toggle the live PDF preview, hide block chrome for a clean view, and save. Everything autosaves as you go.',
      side: 'bottom',
    },
    {
      target: '.toolbar-right',
      title: 'Help and inspection',
      body: 'Three tools to understand what you’re building: this <strong>Guide</strong>, a <strong>JSON</strong> inspector for the raw document and its data, and a <strong>keyboard-shortcuts</strong> reference.',
      side: 'bottom',
    },
    {
      target: 'epistola-sidebar',
      title: 'The sidebar',
      body: 'Three tabs: <strong>Blocks</strong> to add content, <strong>Structure</strong> to see your document as a tree, and the <strong>Inspector</strong> to style whatever’s selected.',
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
      body: 'This button toggles a live PDF preview — exactly what will be generated. We’ve opened it for you.',
      side: 'bottom',
    },
    {
      target: 'epistola-preview',
      title: 'The preview, side by side',
      body: 'Here it is — the rendered PDF next to your document, refreshing as you edit. Drag the divider to resize it, or use the toggle to hide it again.',
      side: 'left',
    },
  ],
};
