// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it } from 'vitest';
import { EpistolaDataContractEditor } from './EpistolaDataContractEditor.js';
import type { JsonSchema, SaveCallbacks } from './types.js';

const schema: JsonSchema = {
  type: 'object',
  properties: {
    customer: {
      type: 'object',
      properties: {},
    },
  },
};

async function mountEditor(callbacks: SaveCallbacks): Promise<{
  editor: EpistolaDataContractEditor;
  validationAlert: HTMLElement;
}> {
  const editor = new EpistolaDataContractEditor();
  // No examples: triggers the "Add at least one test data example" client message.
  editor.init(schema, [], callbacks);
  document.body.append(editor);

  const validationAlert = document.createElement('div');
  document.body.append(validationAlert);
  editor.setValidationAlertContainer(validationAlert);

  await editor.updateComplete;
  return { editor, validationAlert };
}

afterEach(() => document.body.replaceChildren());

describe('client-side validation banner position', () => {
  it('renders inline in the editor, alongside the breaking changes banner, not in the external container', async () => {
    const { editor, validationAlert } = await mountEditor({});

    expect(validationAlert.querySelector('.dc-validation-banner')).toBeNull();

    const banner = editor.querySelector('.dc-validation-banner');
    expect(banner).not.toBeNull();
    expect(banner?.textContent).toContain('Add at least one test data example before saving.');
  });
});
