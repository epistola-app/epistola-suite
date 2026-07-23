import type { Tour } from '../registry.js';
import { clickSidebarTab } from './helpers.js';
import { advanceOnBlockAdded } from '../signals.js';

/**
 * Chapter 2 — Building your document. Interactive (D1): the user actually adds a
 * block, and the chapter auto-advances once they do (validated on the engine's node
 * count, so a click or a drag both count). Each step switches the relevant sidebar
 * tab in `before` so the highlight never races the panel re-render.
 */
export const buildingTour: Tour = {
  id: 'building',
  title: 'Building your document',
  summary: 'Add a block and shape the layout.',
  // v2: rebuilt from a passive walkthrough into an interactive one.
  version: 2,
  steps: () => [
    {
      before: (host) => clickSidebarTab(host, 'blocks'),
      target: '[data-tour="tab-blocks"]',
      title: 'The block palette',
      body: 'Every piece of content is a block. The palette groups them into **Content**, **Layout**, **Logic**, and **Page**.',
      side: 'right',
    },
    {
      before: (host) => clickSidebarTab(host, 'blocks'),
      target: '[data-testid="palette-item-text"]',
      title: 'Add your first block',
      body: 'Click **Text** to drop a text block onto the page — you can drag it onto the canvas, too. (Undo anything with Ctrl+Z.)',
      side: 'right',
      advance: advanceOnBlockAdded(),
    },
    {
      before: (host) => clickSidebarTab(host, 'structure'),
      target: '[data-tour="tab-structure"]',
      title: 'The structure',
      body: 'Everything you add shows up here as a tree — the fastest way to reorder blocks or select nested ones.',
      side: 'right',
    },
  ],
};
