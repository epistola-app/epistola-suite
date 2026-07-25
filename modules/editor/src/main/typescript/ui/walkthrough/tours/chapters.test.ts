// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest';
import { orientationTour } from './orientation.js';
import { buildingTour } from './building.js';
import { editingTour } from './editing.js';

const host = document.createElement('div');

/** A host whose canvas has one block, nothing selected. */
function hostWithBlock(): HTMLElement {
  const h = document.createElement('div');
  const block = document.createElement('div');
  block.className = 'canvas-block';
  block.setAttribute('data-node-id', 'n1');
  h.appendChild(block);
  return h;
}

describe('orientation chapter', () => {
  it('is an overview of the regions, ending by opening the preview', () => {
    const targets = orientationTour.steps(host).map((s) => s.target);
    expect(targets).toEqual([
      'epistola-toolbar',
      '.toolbar-right',
      'epistola-sidebar',
      'epistola-canvas',
      '[data-tour="preview-toggle"]',
      'epistola-preview',
    ]);
  });

  it('opens the preview in setup so the final "preview pane" step has a target', () => {
    // In setup (not a step `before`): otherwise driver computes the prior step's button
    // before the pane renders and mislabels it as the done/"next chapter" button.
    expect(typeof orientationTour.setup).toBe('function');
  });
});

describe('building chapter', () => {
  it('walks palette → add → structure, switching the tab before each step', () => {
    const steps = buildingTour.steps(host);
    expect(steps.map((s) => s.target)).toEqual([
      '[data-tour="tab-blocks"]',
      '[data-testid="palette-item-text"]',
      '[data-tour="tab-structure"]',
    ]);
    for (const step of steps) expect(typeof step.before).toBe('function');
  });

  it('drops a starter block on finish, so the block-centric chapters can chain on', () => {
    expect(typeof buildingTour.onComplete).toBe('function');
  });
});

describe('editing chapter', () => {
  it('is locked until a block exists (no empty-document branching in steps)', () => {
    expect(editingTour.isAvailable?.(hostWithBlock())).toBe(true);
    expect(editingTour.isAvailable?.(document.createElement('div'))).toBe(false);
    expect(typeof editingTour.unavailableHint).toBe('string');
  });

  it('selects a block in setup and walks content → style → delete', () => {
    expect(typeof editingTour.setup).toBe('function');
    expect(editingTour.steps(host).map((s) => s.target)).toEqual([
      '.canvas-block.selected',
      '.inspector-style-group',
      '.inspector-delete-section',
    ]);
  });
});
