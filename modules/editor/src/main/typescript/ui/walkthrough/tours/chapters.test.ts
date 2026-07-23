// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest';
import { orientationTour } from './orientation.js';
import { buildingTour } from './building.js';

const host = document.createElement('div');

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
