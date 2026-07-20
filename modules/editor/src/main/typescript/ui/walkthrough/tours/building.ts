import type { Tour } from '../registry.js';
import { clickSidebarTab } from './helpers.js';

/**
 * Chapter 2 — Building your document. Walks the sidebar's authoring tabs. Each
 * step targets an always-present tab button and uses `before` to switch the
 * panel behind the spotlight, so the highlight never races the tab's re-render.
 */
export const buildingTour: Tour = {
  id: 'building',
  title: 'Building your document',
  summary: 'Add blocks and navigate the structure.',
  version: 1,
  steps: () => [
    {
      before: (host) => clickSidebarTab(host, 'blocks'),
      target: '[data-tour="tab-blocks"]',
      title: 'Blocks',
      body: 'The Blocks tab is your palette — every kind of content you can add to the document.',
      side: 'right',
    },
    {
      target: 'epistola-palette',
      title: 'The palette',
      body: 'Drag a block onto the canvas, or select a block first and click one here to insert it there.',
      side: 'right',
    },
    {
      before: (host) => clickSidebarTab(host, 'structure'),
      target: '[data-tour="tab-structure"]',
      title: 'Structure',
      body: 'The Structure tab is your document as a tree — the fastest way to reorder or select nested blocks.',
      side: 'right',
    },
  ],
};
