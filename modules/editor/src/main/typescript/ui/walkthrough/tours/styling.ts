import type { Tour } from '../registry.js';
import { hasAnyBlock, selectFirstBlock, showDocumentInspector } from './helpers.js';

/**
 * Chapter 4 — Styling. Narrates the style *cascade* on the real inspector surfaces,
 * top to bottom: the page itself → the document defaults every block inherits → a
 * per-block themed preset → per-block overrides that win over those defaults.
 *
 * Gated on there being a block, so the steps are a plain linear list. `setup` opens the
 * document inspector (deselect + Inspector tab) for the first two steps; the pivot step
 * selects a block in `before`, populating the block Inspector for the steps after it —
 * a step's `before` can only set up a *later* step's target, never its own.
 */
export const stylingTour: Tour = {
  id: 'styling',
  title: 'Styling',
  summary: 'Page setup, document defaults, and per-block style.',
  version: 1,
  isAvailable: (host) => hasAnyBlock(host),
  unavailableHint: 'Add a block to the canvas first.',
  setup: (host) => showDocumentInspector(host),
  steps: () => [
    {
      target: '[data-tour="page-settings"]',
      title: 'The page itself',
      body: 'With nothing selected, the Inspector shows the whole <strong>document</strong>. Set the page <strong>format</strong>, <strong>orientation</strong>, <strong>margins</strong>, and <strong>background</strong> here.',
      side: 'right',
    },
    {
      target: '[data-tour="document-styles"]',
      title: 'Document defaults',
      body: 'These are the <strong>defaults every block inherits</strong> — the top of the cascade. Set the base font or colour once and the whole document follows.',
      side: 'right',
    },
    {
      // Target the block itself (present without a selection); selecting it here
      // populates the block Inspector that the preset / override steps point at.
      before: (host) => selectFirstBlock(host),
      target: '[data-testid="canvas-block"]',
      title: 'Now a single block',
      body: 'Select any block and the Inspector switches to <strong>its</strong> style, layered on top of the document defaults.',
      side: 'left',
    },
    {
      // Absent when the theme has no presets (a free-text preset shows instead) — the
      // step then skips (skipMissingElement).
      target: '#style-preset-select',
      title: 'Apply a themed preset',
      body: 'Pick a <strong>preset</strong> to restyle the block with a coordinated look from your theme — fonts, colours, and spacing in one pick.',
      side: 'right',
    },
    {
      target: '.inspector-style-group',
      title: 'Override just this block',
      body: 'And these tune <strong>this block alone</strong> — a block’s own styles win over the document defaults. That’s the whole cascade: page → document → preset → block.',
      side: 'right',
    },
  ],
};
