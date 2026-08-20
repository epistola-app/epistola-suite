// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it } from 'vitest';
import type { JsonSchema } from './types.js';
import { EpistolaDataContractEditor } from './EpistolaDataContractEditor.js';

const schema: JsonSchema = {
  type: 'object',
  required: ['name', 'active', 'address'],
  properties: {
    name: { type: 'string' },
    active: { type: 'boolean' },
    address: {
      type: 'object',
      required: ['city'],
      properties: {
        city: { type: 'string' },
      },
    },
  },
};

async function mountEditor(readOnly = false): Promise<EpistolaDataContractEditor> {
  const editor = new EpistolaDataContractEditor();
  editor.init(
    schema,
    [{ id: 'example-1', name: 'Example 1', data: { name: 'Authored' } }],
    {},
    readOnly,
  );
  document.body.append(editor);
  await editor.updateComplete;
  return editor;
}

afterEach(() => document.body.replaceChildren());

describe('example generation', () => {
  it('fills missing values, preserves authored data, and can be undone in one step', async () => {
    const editor = await mountEditor();
    const generate = editor.querySelector<HTMLButtonElement>('.dc-example-generate-btn')!;

    generate.click();
    await editor.updateComplete;

    expect(editor.contractState!.dataExamples[0].data).toEqual({
      name: 'Authored',
      active: false,
      address: { city: 'Example value' },
    });
    expect(editor.querySelector('.dc-validation-success')?.textContent).toContain('Valid');

    const undo = editor.querySelector<HTMLButtonElement>(
      '.dc-example-toolbar button[aria-label="Undo"]',
    )!;
    expect(undo.disabled).toBe(false);
    undo.click();
    await editor.updateComplete;

    expect(editor.contractState!.dataExamples[0].data).toEqual({ name: 'Authored' });
  });

  it('disables generation in read-only mode', async () => {
    const editor = await mountEditor(true);

    expect(editor.querySelector<HTMLButtonElement>('.dc-example-generate-btn')!.disabled).toBe(
      true,
    );
  });
});
