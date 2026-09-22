// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it } from 'vitest';
import type { JsonSchema } from './types.js';
import { EpistolaDataContractEditor } from './EpistolaDataContractEditor.js';

function schemaWithCountry(country: JsonSchema['properties'][string]): JsonSchema {
  return { type: 'object', required: ['country'], properties: { country } };
}

async function mountEditor(schema: JsonSchema): Promise<EpistolaDataContractEditor> {
  const editor = new EpistolaDataContractEditor();
  editor.init(schema, [{ id: 'example-1', name: 'Example 1', data: {} }], {});
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
});
