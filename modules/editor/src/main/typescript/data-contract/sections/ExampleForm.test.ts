// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest';
import { render } from 'lit';
import {
  validationPathToFormPath,
  buildFieldErrorMap,
  hasChildErrors,
  toDateTimeLocal,
  dateTimeOffset,
  combineDateTime,
  renderExampleForm,
  setNestedValue,
} from './ExampleForm.js';
import type { SchemaValidationError } from '../schema/validation.js';
import type { JsonObject, JsonSchema, JsonSchemaProperty } from '../types.js';

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
    const errors: SchemaValidationError[] = [
      { path: '$.name', message: 'is required' },
      { path: '$.age', message: 'must be integer' },
    ];
    const map = buildFieldErrorMap(errors);
    expect(map.get('name')).toBe('is required');
    expect(map.get('age')).toBe('must be integer');
  });

  it('handles array paths', () => {
    const errors: SchemaValidationError[] = [{ path: '$.items[0]', message: 'must be string' }];
    const map = buildFieldErrorMap(errors);
    expect(map.get('items.0')).toBe('must be string');
  });

  it('keeps first error per path (deduplicates)', () => {
    const errors: SchemaValidationError[] = [
      { path: '$.name', message: 'first error' },
      { path: '$.name', message: 'second error' },
    ];
    const map = buildFieldErrorMap(errors);
    expect(map.get('name')).toBe('first error');
  });

  it('handles nested object paths', () => {
    const errors: SchemaValidationError[] = [
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
