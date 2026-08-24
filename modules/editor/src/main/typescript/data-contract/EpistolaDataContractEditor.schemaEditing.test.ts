// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it } from 'vitest';
import { EpistolaDataContractEditor } from './EpistolaDataContractEditor.js';
import type { JsonSchema } from './types.js';

const schema: JsonSchema = {
  type: 'object',
  properties: {
    customer: {
      type: 'object',
      properties: {},
    },
  },
};

async function mountEditor(): Promise<EpistolaDataContractEditor> {
  const editor = new EpistolaDataContractEditor();
  editor.init(schema, [{ id: 'example-1', name: 'Example 1', data: { customer: {} } }], {});
  document.body.append(editor);
  await editor.updateComplete;
  return editor;
}

afterEach(() => document.body.replaceChildren());

describe('schema field creation usability', () => {
  it('adds a child, keeps its parent expanded, and selects its generated name', async () => {
    const editor = await mountEditor();
    editor.querySelector<HTMLButtonElement>('button[aria-label="Add field to customer"]')!.click();
    await editor.updateComplete;

    const input = editor.querySelector<HTMLInputElement>('[data-schema-field-name]')!;
    const customerRow = editor.querySelector<HTMLElement>('[data-field-id="field:customer"]')!;

    expect(editor.contractState?.schema?.properties?.customer.properties).toHaveProperty('field1');
    expect(customerRow.nextElementSibling?.textContent).toContain('field1');
    expect(document.activeElement).toBe(input);
    expect(input.value).toBe('field1');
    expect(input.selectionStart).toBe(0);
    expect(input.selectionEnd).toBe(input.value.length);
  });

  it('adds a sibling from the selected scalar without navigating to its parent', async () => {
    const editor = await mountEditor();
    editor.querySelector<HTMLButtonElement>('button[aria-label="Add field to customer"]')!.click();
    await editor.updateComplete;

    editor.querySelector<HTMLButtonElement>('button[aria-label="Add field to customer"]')!.click();
    await editor.updateComplete;

    expect(editor.contractState?.schema?.properties?.customer.properties).toEqual({
      field1: { type: 'string' },
      field2: { type: 'string' },
    });
    expect(editor.querySelector<HTMLInputElement>('[data-schema-field-name]')?.value).toBe(
      'field2',
    );
  });

  it('does not collapse a parent that was already expanded', async () => {
    const editor = await mountEditor();
    const expand = editor.querySelector<HTMLButtonElement>('.dc-field-expand-btn')!;
    expand.click();
    await editor.updateComplete;
    expect(expand.getAttribute('aria-expanded')).toBe('true');

    editor.querySelector<HTMLButtonElement>('button[aria-label="Add field to customer"]')!.click();
    await editor.updateComplete;

    expect(
      editor
        .querySelector<HTMLButtonElement>('.dc-field-expand-btn')
        ?.getAttribute('aria-expanded'),
    ).toBe('true');
  });

  it('commits a generated-name replacement with Enter and retains focus', async () => {
    const editor = await mountEditor();
    editor.querySelector<HTMLButtonElement>('button[aria-label="Add field to customer"]')!.click();
    await editor.updateComplete;

    const input = editor.querySelector<HTMLInputElement>('[data-schema-field-name]')!;
    input.value = 'emailAddress';
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await editor.updateComplete;

    const renamedInput = editor.querySelector<HTMLInputElement>('[data-schema-field-name]')!;
    expect(editor.contractState?.schema?.properties?.customer.properties).toHaveProperty(
      'emailAddress',
    );
    expect(document.activeElement).toBe(renamedInput);
    expect(renamedInput.selectionStart).toBe(renamedInput.value.length);
  });

  it('selects the parent when undo removes its only child', async () => {
    const editor = await mountEditor();
    editor.querySelector<HTMLButtonElement>('button[aria-label="Add field to customer"]')!.click();
    await editor.updateComplete;

    editor.querySelector<HTMLButtonElement>('.dc-undo-btn')!.click();
    await editor.updateComplete;

    expect(
      editor.querySelector('.dc-field-list-item-selected')?.getAttribute('data-field-id'),
    ).toBe('field:customer');
    expect(editor.querySelector<HTMLInputElement>('[data-schema-field-name]')?.value).toBe(
      'customer',
    );
  });
});
