// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { render } from 'lit';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { VisualSchema } from '../types.js';
import {
  renderSchemaSection,
  type SchemaSectionCallbacks,
  type SchemaUiState,
} from './SchemaSection.js';

const schema: VisualSchema = {
  fields: [
    { id: 'reference', name: 'reference', type: 'string', required: false },
    {
      id: 'customer',
      name: 'customer',
      type: 'object',
      required: false,
      nestedFields: [
        {
          id: 'address',
          name: 'addressWithAnIntentionallyLongNameForLayoutTesting',
          type: 'object',
          required: false,
          nestedFields: [],
        },
        {
          id: 'recipients',
          name: 'recipients',
          type: 'array',
          arrayItemType: 'object',
          required: false,
          nestedFields: [],
        },
      ],
    },
  ],
};

const callbacks: SchemaSectionCallbacks = {
  onCommand: vi.fn(),
  onToggleFieldExpand: vi.fn(),
  onSelectField: vi.fn(),
  onUndo: vi.fn(),
  onRedo: vi.fn(),
  onAddField: vi.fn(),
  onRequestFieldNameFocus: vi.fn(),
  onImport: vi.fn(),
  onToggleJson: vi.fn(),
};

function renderSection(
  selectedFieldId: string | null,
  readOnly = false,
  visualSchema: VisualSchema = schema,
): HTMLElement {
  const uiState: SchemaUiState = {
    warnings: [],
    canUndo: false,
    canRedo: false,
    selectedFieldId,
    readOnly,
    jsonPanelOpen: false,
  };
  const container = document.createElement('div');
  render(renderSchemaSection(visualSchema, uiState, callbacks, new Set()), container);
  document.body.append(container);
  return container;
}

beforeEach(() => {
  vi.clearAllMocks();
  document.body.replaceChildren();
});

describe('SchemaSection toolbar', () => {
  it('keeps schema actions and undo history local without a section-specific save action', () => {
    const container = renderSection(null, false, { fields: [] });
    const toolbar = container.querySelector('.dc-toolbar');

    expect(toolbar?.textContent).toContain('Add field to data contract');
    expect(toolbar?.textContent).toContain('Undo');
    expect(toolbar?.textContent).toContain('Redo');
    expect(toolbar?.querySelector('.dc-save-btn')).toBeNull();

    toolbar?.querySelector<HTMLButtonElement>('.dc-add-field-btn')?.click();
    expect(callbacks.onAddField).toHaveBeenCalledWith(null);
  });
});

describe('SchemaSection contextual field actions', () => {
  it('offers only the parent target for a scalar field', () => {
    const container = renderSection('reference');
    const actions = container.querySelectorAll<HTMLButtonElement>('.dc-context-add-field-btn');

    expect(actions).toHaveLength(1);
    expect(actions[0].getAttribute('aria-label')).toBe('Add field to data contract');
    expect(actions[0].classList.contains('ep-btn-primary')).toBe(true);
  });

  it('offers child first and sibling second for a nested object', () => {
    const container = renderSection('address');
    const actions = container.querySelectorAll<HTMLButtonElement>('.dc-context-add-field-btn');

    expect(actions).toHaveLength(2);
    expect(actions[0].getAttribute('aria-label')).toBe(
      'Add field to customer, addressWithAnIntentionallyLongNameForLayoutTesting',
    );
    expect(actions[0].getAttribute('title')).toBe(
      'Add field to customer, addressWithAnIntentionallyLongNameForLayoutTesting',
    );
    expect(actions[0].classList.contains('ep-btn-primary')).toBe(true);
    expect(actions[0].querySelector('.dc-context-target')?.textContent).toBe(
      'customer › addressWithAnIntentionallyLongNameForLayoutTesting',
    );
    expect(actions[1].getAttribute('aria-label')).toBe('Add field to customer');

    actions[0].click();
    actions[1].click();
    expect(callbacks.onAddField).toHaveBeenNthCalledWith(1, 'address');
    expect(callbacks.onAddField).toHaveBeenNthCalledWith(2, 'customer');
  });

  it('describes object-array children as items', () => {
    const container = renderSection('recipients');
    const action = container.querySelector<HTMLButtonElement>('.dc-context-add-field-btn');

    expect(action?.getAttribute('aria-label')).toBe('Add field to customer, recipients items');
    expect(action?.querySelector('.dc-context-target')?.textContent).toBe(
      'customer › recipients items',
    );
  });

  it('disables every contextual action in read-only mode', () => {
    const container = renderSection('address', true);
    const actions = container.querySelectorAll<HTMLButtonElement>('.dc-context-add-field-btn');

    expect([...actions].every((button) => button.disabled)).toBe(true);
  });
});

describe('SchemaSection name editing', () => {
  it('commits with Enter and requests stable focus', () => {
    const container = renderSection('reference');
    const input = container.querySelector<HTMLInputElement>('[data-schema-field-name]')!;
    input.value = 'caseReference';

    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));

    expect(callbacks.onCommand).toHaveBeenCalledWith({
      type: 'updateField',
      fieldId: 'reference',
      updates: { name: 'caseReference' },
    });
    expect(callbacks.onRequestFieldNameFocus).toHaveBeenCalledWith('reference', false);
  });

  it('restores the stored value with Escape without creating a command', () => {
    const container = renderSection('reference');
    const input = container.querySelector<HTMLInputElement>('[data-schema-field-name]')!;
    input.value = 'unfinished';

    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

    expect(input.value).toBe('reference');
    expect(callbacks.onCommand).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(input);
  });
});
