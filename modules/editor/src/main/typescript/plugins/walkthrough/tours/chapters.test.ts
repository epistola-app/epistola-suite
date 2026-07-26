// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest';
import { EditorEngine } from '../../../engine/EditorEngine.js';
import {
  createTestDocument,
  createTestDocumentWithChildren,
  testRegistry,
} from '../../../engine/test-helpers.js';
import type { TourContext } from '../registry.js';
import { orientationTour } from './orientation.js';
import { buildingTour } from './building.js';
import { editingTour } from './editing.js';
import { stylingTour } from './styling.js';

/** A context over a real engine with an *empty* document (no blocks). */
function emptyContext(): TourContext {
  return {
    host: document.createElement('div'),
    engine: new EditorEngine(createTestDocument(), testRegistry()),
  };
}

/** A context over a real engine whose document already has blocks. */
function contextWithBlock(): TourContext {
  return {
    host: document.createElement('div'),
    engine: new EditorEngine(createTestDocumentWithChildren().doc, testRegistry()),
  };
}

const ctx = emptyContext();

describe('orientation chapter', () => {
  it('is an overview of the regions, ending by opening the preview', () => {
    const targets = orientationTour.steps(ctx).map((s) => s.target);
    expect(targets).toEqual([
      'epistola-toolbar',
      '[data-editor-anchor~="toolbar-tools"]',
      'epistola-sidebar',
      'epistola-canvas',
      '[data-editor-anchor~="preview-toggle"]',
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
    const steps = buildingTour.steps(ctx);
    expect(steps.map((s) => s.target)).toEqual([
      '[data-editor-anchor~="tab-blocks"]',
      '[data-editor-anchor~="palette-item-text"]',
      '[data-editor-anchor~="tab-structure"]',
    ]);
    for (const step of steps) expect(typeof step.before).toBe('function');
  });

  it('drops a starter block on finish, so the block-centric chapters can chain on', () => {
    expect(typeof buildingTour.onComplete).toBe('function');
  });

  it('onComplete unlocks the next chapters in the same tick (model, not DOM)', () => {
    // Chaining re-checks availability right after onComplete, before the canvas
    // repaints — this only works because availability reads the engine model.
    const c = emptyContext();
    expect(editingTour.isAvailable?.(c)).toBe(false);
    expect(stylingTour.isAvailable?.(c)).toBe(false);
    buildingTour.onComplete?.(c);
    expect(editingTour.isAvailable?.(c)).toBe(true);
    expect(stylingTour.isAvailable?.(c)).toBe(true);
  });

  it('onComplete is idempotent — replaying never stacks starter blocks', () => {
    const c = emptyContext();
    buildingTour.onComplete?.(c);
    buildingTour.onComplete?.(c);
    const doc = c.engine.doc;
    const rootSlot = doc.slots[doc.nodes[doc.root].slots[0]];
    expect(rootSlot.children).toHaveLength(1);
  });
});

describe('editing chapter', () => {
  it('is locked until a block exists (no empty-document branching in steps)', () => {
    expect(editingTour.isAvailable?.(contextWithBlock())).toBe(true);
    expect(editingTour.isAvailable?.(emptyContext())).toBe(false);
    expect(typeof editingTour.unavailableHint).toBe('string');
  });

  it('selects a block in setup and walks content → style → delete', () => {
    expect(typeof editingTour.setup).toBe('function');
    expect(editingTour.steps(ctx).map((s) => s.target)).toEqual([
      '[data-editor-anchor~="selected-block"]',
      '[data-editor-anchor~="block-styles"]',
      '[data-editor-anchor~="block-delete"]',
    ]);
  });
});

describe('styling chapter', () => {
  it('is locked until a block exists (no empty-document branching in steps)', () => {
    expect(stylingTour.isAvailable?.(contextWithBlock())).toBe(true);
    expect(stylingTour.isAvailable?.(emptyContext())).toBe(false);
    expect(typeof stylingTour.unavailableHint).toBe('string');
  });

  it('opens the document inspector in setup and walks the cascade', () => {
    expect(typeof stylingTour.setup).toBe('function');
    expect(stylingTour.steps(ctx).map((s) => s.target)).toEqual([
      '[data-editor-anchor~="page-settings"]',
      '[data-editor-anchor~="document-styles"]',
      '[data-editor-anchor~="canvas-block"]',
      '[data-editor-anchor~="style-preset"]',
      '[data-editor-anchor~="block-styles"]',
    ]);
    // The block step selects the block in `before`, setting up the preset/override targets.
    expect(typeof stylingTour.steps(ctx)[2].before).toBe('function');
  });
});
