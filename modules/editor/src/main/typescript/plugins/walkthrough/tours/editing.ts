// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import type { Tour } from '../registry.js';
import { hasAnyBlock, openPreview, selectFirstBlock } from './helpers.js';

/**
 * Chapter 3 — Editing a block. Gated on there being a block (otherwise it's locked in
 * the launcher with a hint), so the steps are a plain linear list — no empty-document
 * branching. `setup` selects the first block up front so the spotlights have a target.
 */
export const editingTour: Tour = {
  id: 'editing',
  title: 'Editing a block',
  summary: 'Edit a block’s content and style.',
  version: 2,
  isAvailable: (ctx) => hasAnyBlock(ctx),
  unavailableHint: 'Add a block to the canvas first.',
  setup: (ctx) => {
    selectFirstBlock(ctx); // selecting also opens the Inspector
    openPreview(ctx);
  },
  steps: () => [
    {
      target: '[data-editor-anchor~="selected-block"]',
      title: 'Edit its content',
      body: 'A block is selected. <strong>Text</strong> blocks are editable right on the canvas — you click in and type, and it updates live.',
      side: 'bottom',
    },
    {
      target: '[data-editor-anchor~="block-styles"]',
      title: 'Style it',
      body: 'The Inspector is where a block’s look lives — its <strong>colour</strong>, <strong>font size</strong>, spacing, and more. Changes show on the canvas as you make them.',
      side: 'right',
    },
    {
      target: '[data-editor-anchor~="block-delete"]',
      title: 'Remove a block',
      body: 'Done with a block? Remove it here — or select it on the canvas and press Delete.',
      side: 'right',
    },
  ],
};
