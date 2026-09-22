// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it, vi } from 'vitest';
import type { JsonSchema, SaveCallbacks } from './types.js';
import { EpistolaDataContractEditor } from './EpistolaDataContractEditor.js';

function schemaWithCountry(country: JsonSchema['properties'][string]): JsonSchema {
  return { type: 'object', required: ['country'], properties: { country } };
}

async function mountEditor(
  schema: JsonSchema,
  callbacks: SaveCallbacks = {},
): Promise<EpistolaDataContractEditor> {
  const editor = new EpistolaDataContractEditor();
  editor.init(schema, [{ id: 'example-1', name: 'Example 1', data: {} }], callbacks);
  document.body.append(editor);
  await editor.updateComplete;
  return editor;
}

afterEach(() => document.body.replaceChildren());

describe('example validation with schema defaults', () => {
  it('accepts an example that omits a required field with a default, as generation does', async () => {
    const editor = await mountEditor(schemaWithCountry({ type: 'string', default: 'Netherlands' }));

    expect(editor.querySelector('.dc-validation-success')?.textContent).toContain('Valid');
  });

  it('still rejects an example that omits a required field without a default', async () => {
    const editor = await mountEditor(schemaWithCountry({ type: 'string' }));

    expect(editor.querySelector('.dc-validation-success')).toBeNull();
  });

  it('saves a schema change without asking to migrate a field its default fills', async () => {
    const onSaveSchema = vi.fn().mockResolvedValue({ success: true });
    const editor = await mountEditor(
      schemaWithCountry({ type: 'string', default: 'Netherlands' }),
      {
        onSaveSchema,
      },
    );
    const saveControls = document.createElement('div');
    document.body.append(saveControls);
    editor.setSaveControlsContainer(saveControls);

    // Any schema edit makes the save check examples for needed migrations.
    editor
      .querySelector<HTMLButtonElement>('button[aria-label="Add field to data contract"]')!
      .click();
    await editor.updateComplete;
    saveControls.querySelector<HTMLButtonElement>('.dc-save-btn')!.click();
    await editor.updateComplete;
    await new Promise((r) => setTimeout(r, 0));
    await editor.updateComplete;

    expect(editor.querySelector('.dc-migration-title')).toBeNull();
    expect(onSaveSchema).toHaveBeenCalledOnce();
  });
});
