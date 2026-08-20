// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { render } from 'lit';
import { describe, expect, it, vi } from 'vitest';
import { DataContractState } from '../DataContractState.js';
import type { DataExample } from '../types.js';
import { renderExamplesSection, type ExamplesSectionCallbacks } from './ExamplesSection.js';

const callbacks: ExamplesSectionCallbacks = {
  onSelectExample: vi.fn(),
  onAddExample: vi.fn(),
  onDeleteExample: vi.fn(),
  onUpdateExampleName: vi.fn(),
  onUpdateExampleData: vi.fn(),
  onUndo: vi.fn(),
  onRedo: vi.fn(),
};

function renderSection(examples: DataExample[], editingId: string | null = null): HTMLElement {
  const state = new DataContractState(null, examples, {});
  return renderState(state, editingId);
}

function renderState(state: DataContractState, editingId: string | null = null): HTMLElement {
  const container = document.createElement('div');
  render(
    renderExamplesSection(
      state,
      {
        editingId,
        fieldErrorMap: new Map(),
        validationErrorCount: 0,
        exampleErrorCounts: {},
        canUndo: false,
        canRedo: false,
        readOnly: false,
      },
      callbacks,
    ),
    container,
  );
  return container;
}

describe('ExamplesSection required example state', () => {
  it('keeps example undo and redo local without a section-specific save action', () => {
    const example = { id: 'one', name: 'Example 1', data: {} };
    const container = renderSection([example], example.id);
    const actions = container.querySelector('.dc-example-toolbar-actions');

    expect(actions?.textContent).toContain('Undo');
    expect(actions?.textContent).toContain('Redo');
    expect(actions?.textContent).toContain('Autofill');
    expect(actions?.textContent).not.toContain('Save');
    expect(container.querySelector('.dc-example-generate-btn')?.getAttribute('aria-label')).toBe(
      'Autofill missing example values from schema',
    );
  });

  it('explains the requirement and offers to add the first example', () => {
    const container = renderSection([]);

    expect(container.textContent).toContain('At least one test data example is required');
    expect(container.querySelector<HTMLButtonElement>('.dc-empty-state .ep-btn')?.disabled).toBe(
      false,
    );
  });

  it('hides deletion and explains why for the last example', () => {
    const example = { id: 'one', name: 'Example 1', data: {} };
    const container = renderSection([example], example.id);

    expect(container.querySelector('.dc-example-delete-btn')).toBeNull();
    const requirement = container
      .querySelector('.dc-example-delete-requirement')
      ?.textContent?.replace(/\s+/g, ' ')
      .trim();
    expect(requirement).toBe(
      'This example cannot be deleted because at least one test data example is required.',
    );
  });

  it('allows deleting when another example remains', () => {
    const examples = [
      { id: 'one', name: 'Example 1', data: {} },
      { id: 'two', name: 'Example 2', data: {} },
    ];
    const container = renderSection(examples, examples[0].id);

    expect(container.querySelector<HTMLButtonElement>('.dc-example-delete-btn')?.disabled).toBe(
      false,
    );
  });

  it('keeps a saved example protected while its alternative is unsaved', () => {
    const savedExample = { id: 'saved', name: 'Saved example', data: {} };
    const state = new DataContractState(null, [savedExample], {});
    state.addDraftExample({ id: 'new', name: 'New example', data: {} });

    const container = renderState(state, savedExample.id);

    expect(container.querySelector('.dc-example-delete-btn')).toBeNull();
    expect(container.querySelector('.dc-example-delete-requirement')?.textContent).toContain(
      'Save another example before deleting this one.',
    );
  });

  it('allows an unsaved example to be removed locally', () => {
    const savedExample = { id: 'saved', name: 'Saved example', data: {} };
    const newExample = { id: 'new', name: 'New example', data: {} };
    const state = new DataContractState(null, [savedExample], {});
    state.addDraftExample(newExample);

    const container = renderState(state, newExample.id);

    expect(container.querySelector<HTMLButtonElement>('.dc-example-delete-btn')?.disabled).toBe(
      false,
    );
  });
});
