// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it, vi } from 'vitest';
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
  saveControls: HTMLElement;
  validationAlert: HTMLElement;
}> {
  const editor = new EpistolaDataContractEditor();
  editor.init(schema, [{ id: 'example-1', name: 'Example 1', data: { customer: {} } }], callbacks);
  document.body.append(editor);

  const saveControls = document.createElement('div');
  document.body.append(saveControls);
  editor.setSaveControlsContainer(saveControls);

  const validationAlert = document.createElement('div');
  document.body.append(validationAlert);
  editor.setValidationAlertContainer(validationAlert);

  await editor.updateComplete;
  return { editor, saveControls, validationAlert };
}

afterEach(() => document.body.replaceChildren());

describe('save validation errors banner', () => {
  it('renders the errors chip banner when onSaveSchema rejects with a per-example errors map', async () => {
    const onSaveSchema = vi.fn().mockResolvedValue({
      success: false,
      error: 'Example data does not match the schema. Example 1 at items: is too long',
      errors: {
        'Example 1': [{ path: 'items', message: 'is too long' }],
      },
    });
    const { editor, saveControls, validationAlert } = await mountEditor({ onSaveSchema });

    // Dirty the schema so _executeSave takes the saveSchema path.
    editor.querySelector<HTMLButtonElement>('button[aria-label="Add field to customer"]')!.click();
    await editor.updateComplete;

    saveControls.querySelector<HTMLButtonElement>('.dc-save-btn')!.click();
    await editor.updateComplete;
    // saveSchema is async; flush the microtask queue.
    await new Promise((r) => setTimeout(r, 0));
    await editor.updateComplete;

    expect(onSaveSchema).toHaveBeenCalledOnce();
    const banner = validationAlert.querySelector('.dc-save-errors-banner');
    expect(banner).not.toBeNull();
    expect(banner?.textContent).toContain('is too long');
  });
});
