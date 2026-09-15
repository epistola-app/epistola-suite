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
      id: 'tags',
      name: 'tags',
      type: 'array',
      arrayItemType: 'string',
      required: false,
      minItems: 1,
      maxItems: 5,
    },
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
  fieldErrors: Map<string, string> = new Map(),
): HTMLElement {
  const uiState: SchemaUiState = {
    fieldErrors,
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

describe('SchemaSection array constraints', () => {
  it('renders Min items and Max items with the field’s current values', () => {
    const container = renderSection('tags');
    const inputs = container.querySelectorAll<HTMLInputElement>(
      '.dc-detail-constraints input[type="number"]',
    );

    expect(inputs).toHaveLength(2);
    expect(inputs[0].value).toBe('1');
    expect(inputs[1].value).toBe('5');
  });

  it('emits an updateField command with maxItems on change', () => {
    const container = renderSection('tags');
    const inputs = container.querySelectorAll<HTMLInputElement>(
      '.dc-detail-constraints input[type="number"]',
    );
    inputs[1].value = '10';
    inputs[1].dispatchEvent(new Event('change', { bubbles: true }));

    expect(callbacks.onCommand).toHaveBeenCalledWith({
      type: 'updateField',
      fieldId: 'tags',
      updates: { maxItems: 10 },
    });
  });

  it('clears maxItems when the input is emptied', () => {
    const container = renderSection('tags');
    const inputs = container.querySelectorAll<HTMLInputElement>(
      '.dc-detail-constraints input[type="number"]',
    );
    inputs[1].value = '';
    inputs[1].dispatchEvent(new Event('change', { bubbles: true }));

    expect(callbacks.onCommand).toHaveBeenCalledWith({
      type: 'updateField',
      fieldId: 'tags',
      updates: { maxItems: undefined },
    });
  });

  it('shows the field error inline next to the constraint inputs', () => {
    const container = renderSection('tags', false, schema, new Map([['tags', 'bad range']]));

    const error = container.querySelector('.dc-field-error');
    expect(error?.textContent).toBe('bad range');
    const inputs = container.querySelectorAll<HTMLInputElement>(
      '.dc-detail-constraints input[type="number"]',
    );
    expect([...inputs].every((input) => input.classList.contains('dc-input-error'))).toBe(true);
  });

  it('renders no field error when the field has none', () => {
    const container = renderSection('tags');
    expect(container.querySelector('.dc-field-error')).toBeNull();
  });
});

describe('SchemaSection default value', () => {
  it('renders a text input with the current default for a string field', () => {
    const container = renderSection('reference', false, {
      fields: [
        { id: 'reference', name: 'reference', type: 'string', required: false, default: 'N/A' },
      ],
    });
    const input = container.querySelector<HTMLInputElement>(
      '[data-testid="dc-default-value-input"]',
    );
    expect(input?.getAttribute('type')).toBe('text');
    expect(input?.value).toBe('N/A');
  });

  it('emits an updateField command with the default value on change', () => {
    const container = renderSection('reference', false, {
      fields: [{ id: 'reference', name: 'reference', type: 'string', required: false }],
    });
    const input = container.querySelector<HTMLInputElement>(
      '[data-testid="dc-default-value-input"]',
    )!;
    input.value = 'unknown';
    input.dispatchEvent(new Event('change', { bubbles: true }));

    expect(callbacks.onCommand).toHaveBeenCalledWith({
      type: 'updateField',
      fieldId: 'reference',
      updates: { default: 'unknown' },
    });
  });

  it('clears the default when the input is emptied', () => {
    const container = renderSection('reference', false, {
      fields: [
        {
          id: 'reference',
          name: 'reference',
          type: 'string',
          required: false,
          default: 'unknown',
        },
      ],
    });
    const input = container.querySelector<HTMLInputElement>(
      '[data-testid="dc-default-value-input"]',
    )!;
    input.value = '';
    input.dispatchEvent(new Event('change', { bubbles: true }));

    expect(callbacks.onCommand).toHaveBeenCalledWith({
      type: 'updateField',
      fieldId: 'reference',
      updates: { default: undefined },
    });
  });

  it('renders a number input for number/integer fields', () => {
    const container = renderSection('score', false, {
      fields: [{ id: 'score', name: 'score', type: 'number', required: false, default: 42 }],
    });
    const input = container.querySelector<HTMLInputElement>(
      '[data-testid="dc-default-value-input"]',
    );
    expect(input?.getAttribute('type')).toBe('number');
    expect(input?.value).toBe('42');
  });

  it('emits a numeric default on change', () => {
    const container = renderSection('score', false, {
      fields: [{ id: 'score', name: 'score', type: 'integer', required: false }],
    });
    const input = container.querySelector<HTMLInputElement>(
      '[data-testid="dc-default-value-input"]',
    )!;
    input.value = '7';
    input.dispatchEvent(new Event('change', { bubbles: true }));

    expect(callbacks.onCommand).toHaveBeenCalledWith({
      type: 'updateField',
      fieldId: 'score',
      updates: { default: 7 },
    });
  });

  it('renders a None/True/False select for boolean fields, including a false default', () => {
    const container = renderSection('active', false, {
      fields: [{ id: 'active', name: 'active', type: 'boolean', required: false, default: false }],
    });
    const select = container.querySelector<HTMLSelectElement>(
      '[data-testid="dc-default-value-input"]',
    );
    expect(select?.tagName).toBe('SELECT');
    expect(select?.value).toBe('false');
  });

  it('emits a boolean default on change', () => {
    const container = renderSection('active', false, {
      fields: [{ id: 'active', name: 'active', type: 'boolean', required: false }],
    });
    const select = container.querySelector<HTMLSelectElement>(
      '[data-testid="dc-default-value-input"]',
    )!;
    select.value = 'true';
    select.dispatchEvent(new Event('change', { bubbles: true }));

    expect(callbacks.onCommand).toHaveBeenCalledWith({
      type: 'updateField',
      fieldId: 'active',
      updates: { default: true },
    });
  });

  it('renders a date input for date fields', () => {
    const container = renderSection('birthDate', false, {
      fields: [
        {
          id: 'birthDate',
          name: 'birthDate',
          type: 'date',
          required: false,
          default: '2024-01-01',
        },
      ],
    });
    const input = container.querySelector<HTMLInputElement>(
      '[data-testid="dc-default-value-input"]',
    );
    expect(input?.getAttribute('type')).toBe('date');
    expect(input?.value).toBe('2024-01-01');
  });

  it('renders a datetime-local input for datetime fields', () => {
    const container = renderSection('at', false, {
      fields: [
        { id: 'at', name: 'at', type: 'datetime', required: false, default: '2024-01-01T12:00' },
      ],
    });
    const input = container.querySelector<HTMLInputElement>(
      '[data-testid="dc-default-value-input"]',
    );
    expect(input?.getAttribute('type')).toBe('datetime-local');
    expect(input?.value).toBe('2024-01-01T12:00');
  });

  it('does not render a default value control for array fields', () => {
    const container = renderSection('tags');
    expect(container.querySelector('[data-testid="dc-default-value-input"]')).toBeNull();
  });

  it('does not render a default value control for object fields', () => {
    const container = renderSection('customer');
    expect(container.querySelector('[data-testid="dc-default-value-input"]')).toBeNull();
  });

  it('shows the field error inline and marks the input invalid', () => {
    const container = renderSection(
      'score',
      false,
      {
        fields: [
          {
            id: 'score',
            name: 'score',
            type: 'integer',
            required: false,
            default: 'nope' as unknown as number,
          },
        ],
      },
      new Map([['score', '"Default value" must be integer, got string']]),
    );
    const error = container.querySelector('.dc-field-error');
    expect(error?.textContent).toBe('"Default value" must be integer, got string');
    const input = container.querySelector<HTMLInputElement>(
      '[data-testid="dc-default-value-input"]',
    );
    expect(input?.classList.contains('dc-input-error')).toBe(true);
  });
});
