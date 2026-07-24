import type { Tour, TourStep } from '../registry.js';
import { clickSidebarTab, openPreview } from './helpers.js';
import { advanceOnBlockAdded, advanceOnEvent, hasAnyBlock } from '../signals.js';

/**
 * Chapter 3 — Editing a block. Interactive (D1). The core is always the same three
 * "do it" beats on the selected block — edit its content, restyle it, remove it.
 *
 * A conditional preamble gets you *to* a selected block, only when needed:
 *  - empty document → add a block (which auto-selects it);
 *  - blocks exist but nothing selected → click one to select it;
 *  - a block already selected (e.g. chained straight from Building) → nothing:
 *    we go straight to editing, because "click to select" would be redundant.
 *
 * The core steps follow `.canvas-block.selected`, so they always match whatever is
 * selected. The "type" step targets a text editor and is skipped
 * (skipMissingElement) when a non-text block is selected.
 */
export const editingTour: Tour = {
  id: 'editing',
  title: 'Editing a block',
  summary: 'Edit a block’s content and style.',
  version: 1,
  steps: (host) => {
    const preamble: TourStep[] = [];
    if (!hasAnyBlock(host)) {
      preamble.push({
        before: (h) => clickSidebarTab(h, 'blocks'),
        target: '[data-testid="palette-item-text"]',
        title: 'Add a block to edit',
        body: 'Your document is empty — click <strong>Text</strong> to add a block first, then we’ll shape it.',
        side: 'right',
        advance: advanceOnBlockAdded(),
      });
    } else if (host.querySelector('.canvas-block.selected') === null) {
      preamble.push({
        target: '[data-testid="canvas-block"]',
        title: 'Select a block',
        body: 'Click this block to select it — the sidebar switches to its properties.',
        side: 'left',
        advance: advanceOnEvent('selection:change', (_host, data) => data.nodeId !== null),
      });
    }
    // else: a block is already selected — go straight to editing.

    const steps: TourStep[] = [
      ...preamble,
      {
        target: '.canvas-block.selected epistola-text-editor',
        title: 'Edit its content',
        body: 'Click into the block and type — your text updates live as you write.',
        // Right, not left: the bubble menu pops up on the left/top of the editor,
        // so keep the popover clear of it. (driver auto-flips if there's no room.)
        side: 'right',
        advance: advanceOnEvent('doc:change'),
      },
      {
        target: '.inspector-style-group',
        title: 'Style it',
        body: 'Now change how it looks — try a <strong>color</strong> or the <strong>font size</strong> in the Inspector, and watch the canvas update.',
        side: 'left',
        // A style applies (and the canvas updates) when the control commits — a
        // unit/number input on blur/Enter, a picker on close. doc:change fires exactly
        // then, for every control type, which is the moment we want to advance on.
        advance: advanceOnEvent('doc:change'),
      },
      {
        target: '.inspector-delete-section',
        title: 'Remove a block',
        body: 'Done with a block? Remove it here — or select it on the canvas and press Delete.',
        side: 'left',
      },
    ];

    // Open the live preview up front so edits are visible as they render.
    const firstBefore = steps[0].before;
    steps[0].before = (h) => {
      openPreview(h);
      firstBefore?.(h);
    };
    return steps;
  },
};
