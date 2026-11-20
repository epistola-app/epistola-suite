// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only
// @vitest-environment happy-dom

import { render } from 'lit';
import { describe, expect, it } from 'vitest';
import type { JsonSchema } from '../data-contract/types.js';
import {
  childPointer,
  fieldId,
  getAt,
  humanize,
  missingRequired,
  renderRespondentForm,
  resolve,
  setAt,
  singular,
  type RespondentFormState,
} from './respondent-form.js';

const invoice = {
  type: 'object',
  properties: {
    invoiceNumber: { type: 'string', description: 'Unique invoice identifier' },
    customer: {
      type: 'object',
      properties: { name: { type: 'string' }, email: { type: 'string', format: 'email' } },
      required: ['name'],
    },
    notes: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] },
    items: {
      type: 'array',
      items: {
        type: 'object',
        properties: { description: { type: 'string' }, amount: { type: 'number' } },
        required: ['description'],
      },
    },
  },
  required: ['invoiceNumber', 'customer', 'items'],
} as unknown as JsonSchema;

describe('humanize', () => {
  it('turns keys into words a person reads', () => {
    expect(humanize('invoiceNumber')).toBe('Invoice number');
    expect(humanize('zip_code')).toBe('Zip code');
    expect(humanize('due-date')).toBe('Due date');
    expect(humanize('name')).toBe('Name');
  });

  it('keeps acronyms', () => {
    expect(humanize('customerIBAN')).toBe('Customer IBAN');
    expect(humanize('VATNumber')).toBe('VAT number');
  });
});

describe('singular', () => {
  it('names one item of a list', () => {
    expect(singular('Delivery routes')).toBe('delivery route');
    expect(singular('Parties')).toBe('party');
    expect(singular('Address')).toBe('address');
  });
});

describe('pointers', () => {
  it('reads and writes by JSON Pointer, creating what is missing and leaving the input alone', () => {
    const data = { customer: { name: 'Jane' } };
    const next = setAt(data, '/items/0/description', 'Hours');
    expect(next).toEqual({ customer: { name: 'Jane' }, items: [{ description: 'Hours' }] });
    expect(data).toEqual({ customer: { name: 'Jane' } });
    expect(getAt(next, '/items/0/description')).toBe('Hours');
  });

  it('removes a key, or an item from a list, for undefined', () => {
    expect(setAt({ a: 1, b: 2 }, '/a', undefined)).toEqual({ b: 2 });
    expect(setAt({ l: ['x', 'y', 'z'] }, '/l/1', undefined)).toEqual({ l: ['x', 'z'] });
  });

  it('escapes keys as RFC 6901 says', () => {
    expect(childPointer('', 'a/b~c')).toBe('/a~1b~0c');
    expect(getAt({ 'a/b~c': 1 }, '/a~1b~0c')).toBe(1);
  });

  it('makes element ids from pointers', () => {
    expect(fieldId('/customer/name')).toBe('field-customer-name');
  });
});

describe('resolve', () => {
  it('follows a local $ref', () => {
    const root = {
      type: 'object',
      $defs: { address: { type: 'object', properties: { street: { type: 'string' } } } },
    } as unknown as JsonSchema;
    expect(resolve({ $ref: '#/$defs/address', title: 'Home' }, root)).toMatchObject({
      type: 'object',
      title: 'Home',
    });
  });
});

describe('missingRequired', () => {
  it('lists required fields still empty, in required groups and in every list item', () => {
    expect(missingRequired(invoice, {})).toEqual(['/invoiceNumber', '/customer/name', '/items']);
    expect(
      missingRequired(invoice, {
        invoiceNumber: 'F-1',
        customer: { name: 'Jane' },
        items: [{ amount: 3 }],
      }),
    ).toEqual(['/items/0/description']);
  });

  it('asks nothing of an optional group until it has data', () => {
    expect(
      missingRequired(invoice, {
        invoiceNumber: 'F-1',
        customer: { name: 'J' },
        items: [{ description: 'x' }],
      }),
    ).toEqual([]);
    expect(
      missingRequired(invoice, {
        invoiceNumber: 'F-1',
        customer: { name: 'J' },
        items: [{ description: 'x' }],
        notes: { other: 1 },
      }),
    ).toEqual(['/notes/text']);
  });
});

describe('renderRespondentForm', () => {
  const state = (overrides: Partial<RespondentFormState> = {}): RespondentFormState => ({
    schema: invoice,
    data: { customer: { name: 'Jane' } },
    problems: new Map([
      ['/invoiceNumber', { message: 'Fill this in.', missing: true }],
      ['/customer/email', { message: 'Not an email address', missing: false }],
    ]),
    touched: new Set(),
    showAll: false,
    ...overrides,
  });
  const draw = (s: RespondentFormState) => {
    const container = document.createElement('div');
    render(renderRespondentForm(s, { set: () => {}, touch: () => {} }), container);
    return container;
  };

  it('names fields in words, shows the description, and opens groups with what the caller sent', () => {
    const form = draw(state());
    expect(form.querySelector('label[for="field-invoiceNumber"]')?.textContent).toContain(
      'Invoice number',
    );
    expect(form.textContent).toContain('Unique invoice identifier');
    expect(form.querySelector('legend')?.textContent).toContain('Customer');
    expect((form.querySelector('#field-customer-name') as HTMLInputElement).value).toBe('Jane');
    expect(form.querySelector('#field-customer-email')?.getAttribute('type')).toBe('email');
    expect(form.textContent).not.toMatch(/object\b|\[\]/);
  });

  it('holds back an empty required field until visited, but shows a wrong value at once', () => {
    const fresh = draw(state());
    expect(fresh.querySelector('#field-invoiceNumber-error')).toBeNull();
    expect(fresh.querySelector('#field-customer-email-error')?.textContent).toBe(
      'Not an email address',
    );

    expect(
      draw(state({ touched: new Set(['/invoiceNumber']) })).querySelector(
        '#field-invoiceNumber-error',
      ),
    ).not.toBeNull();
    expect(draw(state({ showAll: true })).querySelector('#field-items-error')?.textContent).toBe(
      'Fill this in.',
    );
  });

  it('offers adding to a list, named after one of its items', () => {
    expect(draw(state()).querySelector('#field-items-add')?.textContent).toContain('+ Add item');
  });
});
