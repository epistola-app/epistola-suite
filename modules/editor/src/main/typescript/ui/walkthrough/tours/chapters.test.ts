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

/** A host whose canvas has one block, already selected. */
function hostWithSelectedBlock(): HTMLElement {
  const h = hostWithBlock();
  h.querySelector('.canvas-block')?.classList.add('selected');
  return h;
}

describe('orientation chapter', () => {
  it('is a passive overview of the four regions', () => {
    expect(orientationTour.passive).toBe(true);
    const targets = orientationTour.steps(host).map((s) => s.target);
    expect(targets).toEqual([
      'epistola-toolbar',
      'epistola-sidebar',
      'epistola-canvas',
      '[data-tour="preview-toggle"]',
    ]);
  });

  it('has no interactive steps (passive)', () => {
    expect(orientationTour.steps(host).some((s) => s.advance)).toBe(false);
  });
});

describe('building chapter', () => {
  it('is interactive, not passive', () => {
    expect(buildingTour.passive).toBeFalsy();
  });

  it('guides adding a text block and auto-advances on it', () => {
    const steps = buildingTour.steps(host);
    const addStep = steps.find((s) => s.target === '[data-testid="palette-item-text"]');
    expect(addStep).toBeDefined();
    // The "add a block" step is the interactive one.
    expect(typeof addStep?.advance).toBe('function');
  });

  it('switches the relevant sidebar tab before each step that needs it', () => {
    const steps = buildingTour.steps(host);
    // First (Blocks) and last (Structure) steps set up their panel via `before`.
    expect(typeof steps[0].before).toBe('function');
    expect(typeof steps[steps.length - 1].before).toBe('function');
  });
});

describe('editing chapter', () => {
  it('is interactive, not passive', () => {
    expect(editingTour.passive).toBeFalsy();
  });

  it('prepends a "select a block" step when blocks exist but none is selected', () => {
    const steps = editingTour.steps(hostWithBlock());
    expect(steps[0].target).toBe('[data-testid="canvas-block"]');
    expect(typeof steps[0].advance).toBe('function');
  });

  it('skips the select step and goes straight to editing when a block is already selected', () => {
    const steps = editingTour.steps(hostWithSelectedBlock());
    // No select/add preamble — first step edits the selected block.
    expect(steps[0].target).toBe('.canvas-block.selected epistola-text-editor');
  });

  it('falls back to adding a block first when the document is empty (D8)', () => {
    const steps = editingTour.steps(document.createElement('div'));
    expect(steps[0].target).toBe('[data-testid="palette-item-text"]');
    expect(typeof steps[0].advance).toBe('function');
  });

  it('has the user type and restyle, then ends on delete', () => {
    const steps = editingTour.steps(hostWithBlock());
    const byTarget = new Map(steps.map((s) => [s.target, s]));

    const typeStep = byTarget.get('.canvas-block.selected epistola-text-editor');
    const styleStep = byTarget.get('.inspector-style-group');
    expect(typeof typeStep?.advance).toBe('function'); // type → interactive
    expect(typeof styleStep?.advance).toBe('function'); // restyle → interactive
    // Delete stays narration (no advance) — forcing a destructive action is bad UX.
    const deleteStep = byTarget.get('.inspector-delete-section');
    expect(deleteStep).toBeDefined();
    expect(deleteStep?.advance).toBeUndefined();
  });
});
