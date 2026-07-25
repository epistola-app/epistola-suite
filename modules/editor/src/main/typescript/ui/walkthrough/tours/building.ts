import type { Tour } from '../registry.js';
import { TOUR_HOOKS, tourHook } from '../hooks.js';
import { addStarterBlock, clickSidebarTab } from './helpers.js';

/**
 * Chapter 2 — Building your document. Narrates how content gets onto the page: the
 * block palette and its four categories, how to add a block, and the Structure tree.
 * Each step switches the relevant sidebar tab in `before` so the spotlight lands on it.
 *
 * On finish it drops a starter block onto an empty canvas (`onComplete`), so the
 * block-centric chapters that follow — Editing, Styling — have something to work with
 * and chain straight on. Idempotent, so replaying it never stacks duplicate blocks.
 */
export const buildingTour: Tour = {
  id: 'building',
  title: 'Building your document',
  summary: 'Add a block and shape the layout.',
  // v3: reverted from the interactive rebuild to passive narration.
  version: 3,
  onComplete: (ctx) => addStarterBlock(ctx),
  steps: () => [
    {
      before: (ctx) => clickSidebarTab(ctx, 'blocks'),
      target: tourHook(TOUR_HOOKS.tabBlocks),
      title: 'The block palette',
      body: 'Every piece of content is a block. The palette groups them into <strong>Content</strong>, <strong>Layout</strong>, <strong>Logic</strong>, and <strong>Page</strong>.',
      side: 'right',
    },
    {
      before: (ctx) => clickSidebarTab(ctx, 'blocks'),
      target: '[data-testid="palette-item-text"]',
      title: 'Adding a block',
      body: 'Click any block — like <strong>Text</strong> — to drop it onto the page, or drag it onto the canvas. Undo anything with Ctrl+Z.',
      side: 'right',
    },
    {
      before: (ctx) => clickSidebarTab(ctx, 'structure'),
      target: tourHook(TOUR_HOOKS.tabStructure),
      title: 'The structure',
      body: 'Everything you add shows up here as a tree — the fastest way to reorder blocks or select nested ones.',
      side: 'right',
    },
  ],
};
