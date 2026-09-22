// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest';
import { render } from 'lit';
import {
  arrayTargetLabel,
  toDateTimeLocal,
  dateTimeOffset,
  combineDateTime,
  renderExampleForm,
  setNestedValue,
} from './ExampleForm.js';
import {
  validationPathToFormPath,
  buildFieldErrorMap,
  hasChildErrors,
} from '../validation-display.js';
import { validateDataAgainstSchema } from '../schema/validation.js';
import type { JsonObject, JsonSchema, JsonSchemaProperty, ValidationError } from '../types.js';

describe('example form placeholders', () => {
  it('renders field-name hints without assigning field values', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        emailadres: { type: 'string' },
        aantal: { type: 'integer' },
      },
    };
    const container = document.createElement('div');

    render(
      renderExampleForm(schema, {}, () => {}),
      container,
    );

    const email = container.querySelector<HTMLInputElement>('#dc-field-emailadres')!;
    const count = container.querySelector<HTMLInputElement>('#dc-field-aantal')!;
    expect(email.value).toBe('');
    expect(email.placeholder).toBe('emailadres');
    expect(count.value).toBe('');
    expect(count.placeholder).toBe('aantal');
  });

  it('renders placeholders at nested object and array depths', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        arr: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              arr: {
                type: 'array',
                items: {
                  type: 'object',
                  properties: { name: { type: 'string' } },
                },
              },
            },
          },
        },
      },
    };
    const container = document.createElement('div');

    render(
      renderExampleForm(schema, { arr: [{ arr: [{}] }] }, () => {}),
      container,
    );

    const nestedName = container.querySelector<HTMLInputElement>('#dc-field-arr-0-arr-0-name')!;
    expect(nestedName.value).toBe('');
    expect(nestedName.placeholder).toBe('name');
    expect(nestedName.classList.contains('dc-tree-input')).toBe(true);
  });
});

describe('example form defaults', () => {
  function renderForm(
    schema: JsonSchema,
    data: JsonObject,
    onChange: (path: string, value: unknown) => void = () => {},
  ): HTMLElement {
    const container = document.createElement('div');
    render(renderExampleForm(schema, data, onChange), container);
    return container;
  }

  function clear(input: HTMLInputElement): void {
    input.value = '';
    input.dispatchEvent(new Event('change'));
  }

  it("shows a left-out field's default as its placeholder", () => {
    const container = renderForm(
      {
        type: 'object',
        properties: {
          country: { type: 'string', default: 'Netherlands' },
          taxRate: { type: 'number', default: 0.21 },
          name: { type: 'string' },
        },
      },
      {},
    );

    expect(container.querySelector<HTMLInputElement>('#dc-field-country')!.placeholder).toBe(
      'Netherlands (default)',
    );
    expect(container.querySelector<HTMLInputElement>('#dc-field-taxRate')!.placeholder).toBe(
      '0.21 (default)',
    );
    expect(container.querySelector<HTMLInputElement>('#dc-field-name')!.placeholder).toBe('name');
  });

  it('shows a hint for date, date-time and yes/no defaults, which have no placeholder', () => {
    const container = renderForm(
      {
        type: 'object',
        properties: {
          start: { type: 'string', format: 'date', default: '2026-01-01' },
          at: { type: 'string', format: 'date-time', default: '2026-01-01T09:00:00' },
          active: { type: 'boolean', default: true },
        },
      },
      {},
    );

    const hints = [...container.querySelectorAll('.dc-field-default-hint')].map((h) =>
      h.textContent?.trim(),
    );
    expect(hints).toEqual(['Default: 2026-01-01', 'Default: 2026-01-01T09:00:00', 'Default: true']);
  });

  it('shows no default once the field has a value', () => {
    const container = renderForm(
      {
        type: 'object',
        properties: { start: { type: 'string', format: 'date', default: '2026-01-01' } },
      },
      { start: '2026-05-01' },
    );

    expect(container.querySelector('.dc-field-default-hint')).toBeNull();
  });

  it('shows no default where generation would not apply it', () => {
    const container = renderForm(
      {
        type: 'object',
        properties: {
          // A missing parent object is not created to hold a nested default.
          vendor: {
            type: 'object',
            properties: { country: { type: 'string', default: 'Netherlands' } },
          },
          // Defaults inside array items are not applied.
          items: {
            type: 'array',
            items: { type: 'object', properties: { qty: { type: 'integer', default: 1 } } },
          },
        },
      },
      { items: [{}] },
    );

    expect(container.querySelector<HTMLInputElement>('#dc-field-vendor-country')!.placeholder).toBe(
      'country',
    );
    expect(container.querySelector<HTMLInputElement>('#dc-field-items-0-qty')!.placeholder).toBe(
      'qty',
    );
  });

  it('leaves a cleared field out when its default would then fill it', () => {
    const changes: [string, unknown][] = [];
    const container = renderForm(
      {
        type: 'object',
        properties: {
          country: { type: 'string', default: 'Netherlands' },
          taxRate: { type: 'number', default: 0.21 },
        },
      },
      { country: 'Belgium', taxRate: 0.06 },
      (path, value) => changes.push([path, value]),
    );

    clear(container.querySelector<HTMLInputElement>('#dc-field-country')!);
    clear(container.querySelector<HTMLInputElement>('#dc-field-taxRate')!);

    expect(changes).toEqual([
      ['country', undefined],
      ['taxRate', undefined],
    ]);
  });

  it('stores an empty value when clearing a field without a default', () => {
    const changes: [string, unknown][] = [];
    const container = renderForm(
      { type: 'object', properties: { name: { type: 'string' }, count: { type: 'integer' } } },
      { name: 'Ada', count: 3 },
      (path, value) => changes.push([path, value]),
    );

    clear(container.querySelector<HTMLInputElement>('#dc-field-name')!);
    clear(container.querySelector<HTMLInputElement>('#dc-field-count')!);

    expect(changes).toEqual([
      ['name', ''],
      ['count', null],
    ]);
  });

  it('setNestedValue leaves a property out when given undefined', () => {
    expect(setNestedValue({ a: { b: 1, c: 2 } }, 'a.b', undefined)).toEqual({ a: { c: 2 } });
    // Nothing to remove under a missing parent, and no parent is created for it.
    expect(setNestedValue({ a: 1 }, 'x.y', undefined)).toEqual({ a: 1 });
  });
});

describe('example property disclosure', () => {
  const schema: JsonSchema = {
    type: 'object',
    properties: {
      customer: {
        type: 'object',
        properties: {
          address: {
            type: 'object',
            properties: { city: { type: 'string' } },
          },
        },
      },
      contacts: {
        type: 'array',
        items: { type: 'object', properties: { name: { type: 'string' } } },
      },
    },
  };

  it('starts every object, array, and array item collapsed', () => {
    const container = document.createElement('div');
    render(
      renderExampleForm(
        schema,
        { customer: { address: {} }, contacts: [{ name: 'Ada' }] },
        () => {},
      ),
      container,
    );

    const groups = [...container.querySelectorAll<HTMLDetailsElement>('details')];
    expect(groups).toHaveLength(4);
    expect(groups.every((group) => !group.open)).toBe(true);
  });

  it('keeps a manually opened group open when example data rerenders', () => {
    const container = document.createElement('div');
    render(
      renderExampleForm(schema, { customer: { address: {} } }, () => {}),
      container,
    );
    const customer = container.querySelector<HTMLDetailsElement>(
      'details[aria-label="customer properties"]',
    )!;
    customer.open = true;

    render(
      renderExampleForm(schema, { customer: { address: { city: 'Utrecht' } } }, () => {}),
      container,
    );

    expect(customer.open).toBe(true);
  });
});

describe('array item actions', () => {
  const schema: JsonSchema = {
    type: 'object',
    properties: {
      tags: { type: 'array', items: { type: 'string' } },
      contacts: {
        type: 'array',
        items: { type: 'object', properties: { name: { type: 'string' } } },
      },
      matrix: {
        type: 'array',
        items: {
          type: 'array',
          items: { type: 'object', properties: { name: { type: 'string' } } },
        },
      },
    },
  };

  it('formats nested array paths with human-readable item numbers', () => {
    expect(arrayTargetLabel('groups.0.members.11')).toEqual({
      visible: 'groups › item 1 › members › item 12',
      accessible: 'groups, item 1, members, item 12',
    });
  });

  it('shows the destination for primitive, object, and nested-array actions', () => {
    const container = document.createElement('div');
    render(
      renderExampleForm(schema, { matrix: [[]] }, () => {}),
      container,
    );

    const labels = [...container.querySelectorAll<HTMLButtonElement>('.dc-array-add-btn')].map(
      (button) => button.getAttribute('aria-label'),
    );
    expect(labels).toEqual([
      'Add item to tags',
      'Add item to contacts',
      'Add item to matrix, item 1',
      'Add item to matrix',
    ]);

    const nestedButton = container.querySelector<HTMLButtonElement>(
      'button[aria-label="Add item to matrix, item 1"]',
    )!;
    expect(nestedButton.title).toBe('Add item to matrix, item 1');
    expect(nestedButton.querySelector('.dc-array-add-target')?.textContent).toBe('matrix › item 1');
  });

  it('retains the existing add behavior behind the contextual action', () => {
    let data: JsonObject = { contacts: [] };
    const container = document.createElement('div');
    render(
      renderExampleForm(schema, data, (path, value) => {
        data = setNestedValue(data, path, value);
      }),
      container,
    );

    container
      .querySelector<HTMLButtonElement>('button[aria-label="Add item to contacts"]')!
      .click();

    expect(data).toEqual({ contacts: [{ name: '' }] });
  });

  it('disables contextual array actions in read-only mode', () => {
    const container = document.createElement('div');
    render(
      renderExampleForm(schema, { matrix: [[]] }, () => {}, new Map(), true),
      container,
    );

    expect(
      [...container.querySelectorAll<HTMLButtonElement>('.dc-array-add-btn')].every(
        (button) => button.disabled,
      ),
    ).toBe(true);
  });

  it('shows an array-length error message below the array block', () => {
    const constrainedSchema: JsonSchema = {
      ...schema,
      properties: { ...schema.properties, tags: { ...schema.properties.tags, maxItems: 2 } },
    };
    const data = { tags: ['a', 'b', 'c'] };
    const { errors } = validateDataAgainstSchema(data, constrainedSchema);
    const container = document.createElement('div');
    render(
      renderExampleForm(constrainedSchema, data, () => {}, buildFieldErrorMap(errors)),
      container,
    );

    const group = container.querySelector('details[aria-label="tags array"]')!;
    const errorEl = group.parentElement!.querySelector('.dc-field-error');
    expect(errorEl?.textContent).toBe('must have at most 2 items');
  });

  it('shows an array-length error message below an array-of-objects block', () => {
    const constrainedSchema: JsonSchema = {
      ...schema,
      properties: {
        ...schema.properties,
        contacts: { ...schema.properties.contacts, minItems: 1 },
      },
    };
    const data = { contacts: [] };
    const { errors } = validateDataAgainstSchema(data, constrainedSchema);
    const container = document.createElement('div');
    render(
      renderExampleForm(constrainedSchema, data, () => {}, buildFieldErrorMap(errors)),
      container,
    );

    const group = container.querySelector('details[aria-label="contacts array of objects"]')!;
    const errorEl = group.parentElement!.querySelector('.dc-field-error');
    expect(errorEl?.textContent).toBe('must have at least 1 items');
  });

  it('shows an array-length error message below a nested-array block', () => {
    const constrainedSchema: JsonSchema = {
      ...schema,
      properties: { ...schema.properties, matrix: { ...schema.properties.matrix, maxItems: 1 } },
    };
    const data = { matrix: [[], []] };
    const { errors } = validateDataAgainstSchema(data, constrainedSchema);
    const container = document.createElement('div');
    render(
      renderExampleForm(constrainedSchema, data, () => {}, buildFieldErrorMap(errors)),
      container,
    );

    const group = container.querySelector('details[aria-label="matrix nested arrays"]')!;
    const errorEl = group.parentElement!.querySelector('.dc-field-error');
    expect(errorEl?.textContent).toBe('must have at most 1 items');
  });
});

describe('advanced nested example schemas', () => {
  it('resolves local refs for objects nested in arrays and updates the exact path', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        contacts: {
          type: 'array',
          items: { $ref: '#/$defs/contact' },
        },
      },
      $defs: {
        contact: {
          type: 'object',
          properties: { email: { type: 'string', format: 'email' } },
          required: ['email'],
        },
      },
    };
    let data: JsonObject = { contacts: [{ email: 'first@example.com' }] };
    const container = document.createElement('div');

    render(
      renderExampleForm(schema, data, (path, value) => {
        data = setNestedValue(data, path, value);
      }),
      container,
    );

    const input = container.querySelector<HTMLInputElement>('#dc-field-contacts-0-email')!;
    expect(input.value).toBe('first@example.com');
    input.value = 'updated@example.com';
    input.dispatchEvent(new Event('change'));
    expect(data).toEqual({ contacts: [{ email: 'updated@example.com' }] });
  });

  it('renders and edits arrays nested directly inside arrays', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        matrix: {
          type: 'array',
          items: {
            type: 'array',
            items: {
              type: 'object',
              properties: { name: { type: 'string' } },
            },
          },
        },
      },
    };
    let data: JsonObject = { matrix: [[{ name: 'before' }]] };
    const container = document.createElement('div');

    render(
      renderExampleForm(schema, data, (path, value) => {
        data = setNestedValue(data, path, value);
      }),
      container,
    );

    const input = container.querySelector<HTMLInputElement>('#dc-field-matrix-0-0-name')!;
    expect(input.value).toBe('before');
    input.value = 'after';
    input.dispatchEvent(new Event('change'));
    expect(data).toEqual({ matrix: [[{ name: 'after' }]] });

    const innerGroup = container.querySelector<HTMLElement>(
      'details[aria-label="matrix[0] array of objects"]',
    )!;
    innerGroup.querySelector<HTMLButtonElement>('.dc-array-item-remove')!.click();
    expect(data).toEqual({ matrix: [[]] });

    render(
      renderExampleForm(schema, data, (path, value) => {
        data = setNestedValue(data, path, value);
      }),
      container,
    );
    container
      .querySelector<HTMLElement>('details[aria-label="matrix[0] array of objects"]')!
      .querySelector<HTMLButtonElement>('.dc-array-add-btn')!
      .click();
    expect(data).toEqual({ matrix: [[{ name: '' }]] });
  });

  it('renders the composition branch matching the current data', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        subject: {
          oneOf: [
            {
              type: 'object',
              properties: { personName: { type: 'string' } },
              required: ['personName'],
            },
            {
              type: 'object',
              properties: { companyName: { type: 'string' } },
              required: ['companyName'],
            },
          ],
        },
      },
    };
    const container = document.createElement('div');

    render(
      renderExampleForm(schema, { subject: { companyName: 'Epistola' } }, () => {}),
      container,
    );

    expect(container.querySelector('#dc-field-subject-companyName')).not.toBeNull();
    expect(container.querySelector('#dc-field-subject-personName')).toBeNull();
  });

  it('uses the non-null type from nullable imported schemas', () => {
    const nullableObject = {
      type: ['null', 'object'],
      properties: { city: { type: 'string' } },
    } as unknown as JsonSchemaProperty;
    const schema: JsonSchema = {
      type: 'object',
      properties: { address: nullableObject },
    };
    const container = document.createElement('div');

    render(
      renderExampleForm(schema, { address: { city: 'Amsterdam' } }, () => {}),
      container,
    );

    expect(container.querySelector<HTMLInputElement>('#dc-field-address-city')?.value).toBe(
      'Amsterdam',
    );
  });

  it('renders an editable branch when a nullable composition currently contains null', () => {
    const nullableMember = { type: 'null' } as unknown as JsonSchemaProperty;
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        address: {
          anyOf: [
            nullableMember,
            {
              type: 'object',
              properties: { city: { type: 'string' } },
            },
          ],
        },
      },
    };
    let data: JsonObject = { address: null };
    const container = document.createElement('div');

    render(
      renderExampleForm(schema, data, (path, value) => {
        data = setNestedValue(data, path, value);
      }),
      container,
    );

    const city = container.querySelector<HTMLInputElement>('#dc-field-address-city')!;
    city.value = 'Utrecht';
    city.dispatchEvent(new Event('change'));
    expect(data).toEqual({ address: { city: 'Utrecht' } });
  });

  it('resolves object union schemas separately for every array item', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        subjects: {
          type: 'array',
          items: {
            oneOf: [
              {
                type: 'object',
                properties: { personName: { type: 'string' } },
                required: ['personName'],
              },
              {
                type: 'object',
                properties: { companyName: { type: 'string' } },
                required: ['companyName'],
              },
            ],
          },
        },
      },
    };
    const container = document.createElement('div');

    render(
      renderExampleForm(
        schema,
        { subjects: [{ personName: 'Sander' }, { companyName: 'Epistola' }] },
        () => {},
      ),
      container,
    );

    expect(container.querySelector('#dc-field-subjects-0-personName')).not.toBeNull();
    expect(container.querySelector('#dc-field-subjects-0-companyName')).toBeNull();
    expect(container.querySelector('#dc-field-subjects-1-personName')).toBeNull();
    expect(container.querySelector('#dc-field-subjects-1-companyName')).not.toBeNull();
  });
});

describe('toDateTimeLocal', () => {
  it('keeps a zoneless local date-time for the picker', () => {
    expect(toDateTimeLocal('2026-02-18T09:30:00')).toBe('2026-02-18T09:30:00');
    expect(toDateTimeLocal('2026-02-18T09:30')).toBe('2026-02-18T09:30');
  });

  it('strips a zone designator for display (re-applied on save)', () => {
    expect(toDateTimeLocal('2026-02-18T09:30:00Z')).toBe('2026-02-18T09:30:00');
    expect(toDateTimeLocal('2026-02-18T09:30:00+02:00')).toBe('2026-02-18T09:30:00');
  });

  it('widens a date-only value to midnight so it still renders', () => {
    expect(toDateTimeLocal('2026-02-18')).toBe('2026-02-18T00:00');
  });

  it('returns empty for unparseable / empty / non-string input', () => {
    expect(toDateTimeLocal('not a date')).toBe('');
    expect(toDateTimeLocal('')).toBe('');
    expect(toDateTimeLocal(undefined)).toBe('');
    expect(toDateTimeLocal(42)).toBe('');
  });
});

describe('dateTimeOffset', () => {
  it('extracts the zone designator for the offset dropdown', () => {
    expect(dateTimeOffset('2026-02-18T09:30:00Z')).toBe('Z');
    expect(dateTimeOffset('2026-02-18T09:30:00+02:00')).toBe('+02:00');
    expect(dateTimeOffset('2026-02-18T09:30:00-05:00')).toBe('-05:00');
  });

  it('returns empty for a naive value or non-string', () => {
    expect(dateTimeOffset('2026-02-18T09:30:00')).toBe('');
    expect(dateTimeOffset(undefined)).toBe('');
    expect(dateTimeOffset(42)).toBe('');
  });

  it('normalizes +00:00 to Z so UTC stays representable in the dropdown', () => {
    expect(dateTimeOffset('2026-02-18T09:30:00+00:00')).toBe('Z');
  });
});

describe('combineDateTime', () => {
  it('fills in seconds the control omitted', () => {
    expect(combineDateTime('2026-02-18T09:30', '')).toBe('2026-02-18T09:30:00');
  });

  it('appends the chosen offset (UTC or numeric)', () => {
    expect(combineDateTime('2026-02-18T10:00', 'Z')).toBe('2026-02-18T10:00:00Z');
    expect(combineDateTime('2026-02-18T10:00', '+02:00')).toBe('2026-02-18T10:00:00+02:00');
  });

  it('stays naive when no offset is chosen ("time is time")', () => {
    expect(combineDateTime('2026-02-18T10:00', '')).toBe('2026-02-18T10:00:00');
    expect(combineDateTime('2026-02-18T10:00:30', '')).toBe('2026-02-18T10:00:30');
  });

  it('returns empty for an empty local part', () => {
    expect(combineDateTime('', 'Z')).toBe('');
  });
});

describe('validationPathToFormPath', () => {
  it('strips leading $. prefix', () => {
    expect(validationPathToFormPath('$.name')).toBe('name');
  });

  it('converts bracket notation to dot notation', () => {
    expect(validationPathToFormPath('$.items[0]')).toBe('items.0');
  });

  it('handles nested paths with arrays', () => {
    expect(validationPathToFormPath('$.users[0].email')).toBe('users.0.email');
  });

  it('handles deeply nested paths', () => {
    expect(validationPathToFormPath('$.a.b[1].c[2].d')).toBe('a.b.1.c.2.d');
  });

  it('handles simple root-level field', () => {
    expect(validationPathToFormPath('$.firstName')).toBe('firstName');
  });

  it('handles path without $. prefix gracefully', () => {
    expect(validationPathToFormPath('name')).toBe('name');
  });
});

describe('buildFieldErrorMap', () => {
  it('returns empty map for empty errors', () => {
    const map = buildFieldErrorMap([]);
    expect(map.size).toBe(0);
  });

  it('maps validation paths to form paths', () => {
    const errors: ValidationError[] = [
      { path: '$.name', message: 'is required' },
      { path: '$.age', message: 'must be integer' },
    ];
    const map = buildFieldErrorMap(errors);
    expect(map.get('name')).toBe('is required');
    expect(map.get('age')).toBe('must be integer');
  });

  it('handles array paths', () => {
    const errors: ValidationError[] = [{ path: '$.items[0]', message: 'must be string' }];
    const map = buildFieldErrorMap(errors);
    expect(map.get('items.0')).toBe('must be string');
  });

  it('keeps first error per path (deduplicates)', () => {
    const errors: ValidationError[] = [
      { path: '$.name', message: 'first error' },
      { path: '$.name', message: 'second error' },
    ];
    const map = buildFieldErrorMap(errors);
    expect(map.get('name')).toBe('first error');
  });

  it('handles nested object paths', () => {
    const errors: ValidationError[] = [
      { path: '$.address.street', message: 'is required' },
      { path: '$.users[0].email', message: 'must be string' },
    ];
    const map = buildFieldErrorMap(errors);
    expect(map.get('address.street')).toBe('is required');
    expect(map.get('users.0.email')).toBe('must be string');
  });
});

describe('hasChildErrors', () => {
  it('returns false for empty errors map', () => {
    expect(hasChildErrors('address', new Map())).toBe(false);
  });

  it('returns true when path itself has an error', () => {
    const errors = new Map([['address', 'is required']]);
    expect(hasChildErrors('address', errors)).toBe(true);
  });

  it('returns true when a child path has an error', () => {
    const errors = new Map([['address.street', 'is required']]);
    expect(hasChildErrors('address', errors)).toBe(true);
  });

  it('returns true for deeply nested child errors', () => {
    const errors = new Map([['users.0.address.zip', 'must be string']]);
    expect(hasChildErrors('users', errors)).toBe(true);
    expect(hasChildErrors('users.0', errors)).toBe(true);
    expect(hasChildErrors('users.0.address', errors)).toBe(true);
  });

  it('returns false when no matching paths', () => {
    const errors = new Map([['name', 'is required']]);
    expect(hasChildErrors('address', errors)).toBe(false);
  });

  it('does not match partial path prefixes', () => {
    const errors = new Map([['addressExtra.street', 'is required']]);
    expect(hasChildErrors('address', errors)).toBe(false);
  });
});
