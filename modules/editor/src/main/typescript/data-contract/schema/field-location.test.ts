// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import type { VisualSchema } from '../types.js';
import {
  fieldTargetAncestorIds,
  fieldTargetLabel,
  findFieldLocation,
  findFieldPath,
  nextAvailableFieldName,
  reconcileFieldSelection,
} from './field-location.js';

function makeSchema(): VisualSchema {
  return {
    fields: [
      { id: 'name', name: 'name', type: 'string', required: false },
      {
        id: 'customer',
        name: 'customer',
        type: 'object',
        required: false,
        nestedFields: [
          { id: 'customer-reference', name: 'reference', type: 'string', required: false },
          {
            id: 'recipients',
            name: 'recipients',
            type: 'array',
            arrayItemType: 'object',
            required: false,
            nestedFields: [
              { id: 'recipient-name', name: 'name', type: 'string', required: false },
              { id: 'recipient-field1', name: 'field1', type: 'string', required: false },
            ],
          },
        ],
      },
    ],
  };
}

describe('field location', () => {
  it('locates a deeply nested field with its parent and ancestors', () => {
    const location = findFieldLocation(makeSchema().fields, 'recipient-name');

    expect(location?.parentFieldId).toBe('recipients');
    expect(location?.ancestors.map((field) => field.id)).toEqual(['customer', 'recipients']);
    expect(location?.index).toBe(0);
  });

  it('retains the parent-name path needed for example key migration', () => {
    const result = findFieldPath(makeSchema().fields, 'recipient-name');

    expect(result?.path).toEqual(['customer', 'recipients']);
    expect(result?.field.name).toBe('name');
  });

  it('formats root, object, and object-array destinations naturally', () => {
    const fields = makeSchema().fields;

    expect(fieldTargetLabel(fields, null)).toEqual({
      visible: 'data contract',
      accessible: 'data contract',
    });
    expect(fieldTargetLabel(fields, 'customer')).toEqual({
      visible: 'customer',
      accessible: 'customer',
    });
    expect(fieldTargetLabel(fields, 'recipients')).toEqual({
      visible: 'customer › recipients items',
      accessible: 'customer, recipients items',
    });
  });

  it('returns every object that must remain expanded for a nested target', () => {
    expect(fieldTargetAncestorIds(makeSchema().fields, 'recipients')).toEqual([
      'customer',
      'recipients',
    ]);
  });

  it('chooses a collision-free name within the receiving object', () => {
    const fields = makeSchema().fields;

    expect(nextAvailableFieldName(fields, null)).toBe('field1');
    expect(nextAvailableFieldName(fields, 'recipients')).toBe('field2');
  });
});

describe('field selection reconciliation', () => {
  it('keeps a selection that still exists', () => {
    const fields = makeSchema().fields;
    expect(reconcileFieldSelection(fields, fields, 'recipient-name')).toBe('recipient-name');
  });

  it('selects the next sibling after deleting a field', () => {
    const previous = makeSchema().fields;
    const next = structuredClone(previous);
    const customer = next[1];
    if (customer.type !== 'object') throw new Error('Expected customer object');
    const recipients = customer.nestedFields?.[1];
    if (recipients?.type !== 'array') throw new Error('Expected recipients array');
    recipients.nestedFields = recipients.nestedFields?.slice(1);

    expect(reconcileFieldSelection(previous, next, 'recipient-name')).toBe('recipient-field1');
  });

  it('falls back to the previous sibling and then the parent', () => {
    const previous = makeSchema().fields;
    const withoutLast = structuredClone(previous);
    const customer = withoutLast[1];
    if (customer.type !== 'object') throw new Error('Expected customer object');
    const recipients = customer.nestedFields?.[1];
    if (recipients?.type !== 'array') throw new Error('Expected recipients array');
    recipients.nestedFields = recipients.nestedFields?.slice(0, 1);

    expect(reconcileFieldSelection(previous, withoutLast, 'recipient-field1')).toBe(
      'recipient-name',
    );

    recipients.nestedFields = [];
    expect(reconcileFieldSelection(previous, withoutLast, 'recipient-name')).toBe('recipients');
  });

  it('falls back to the first root field when the former hierarchy is gone', () => {
    const previous = makeSchema().fields;
    const next = previous.slice(0, 1);

    expect(reconcileFieldSelection(previous, next, 'recipient-name')).toBe('name');
  });
});
