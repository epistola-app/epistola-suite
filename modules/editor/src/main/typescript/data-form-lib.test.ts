// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only
// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest';
import { mountDataForm } from './data-form-lib.js';

describe('mountDataForm', () => {
  const schema = {
    type: 'object' as const,
    properties: {
      items: { type: 'array', items: { type: 'object', properties: { text: { type: 'string' } } } },
    },
    required: ['items'],
  };

  it('drops the server problem of a list once an item is added to it', () => {
    const container = document.createElement('div');
    let latest = {};
    const form = mountDataForm({
      container,
      definition: { kind: 'generated', schema },
      data: {},
      problems: [{ path: '/items', message: 'Fill this in.', kind: 'missing' }],
      submitted: true,
      onChange: (data) => (latest = data),
    });
    expect(container.querySelector('#field-items-error')).not.toBeNull();

    (container.querySelector('#field-items-add') as HTMLButtonElement).click();

    expect(latest).toEqual({ items: [{}] });
    expect(form.missing()).toEqual([]);
    expect(container.querySelector('#field-items-error')).toBeNull();
  });
});
