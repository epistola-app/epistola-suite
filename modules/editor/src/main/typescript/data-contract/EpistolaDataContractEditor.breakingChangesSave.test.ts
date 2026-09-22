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
    customer: { type: 'object', properties: {} },
    note: { type: 'string' },
  },
};

async function mountEditor(callbacks: SaveCallbacks): Promise<{
  editor: EpistolaDataContractEditor;
  saveControls: HTMLElement;
}> {
  const editor = new EpistolaDataContractEditor();
  editor.init(schema, [{ id: 'example-1', name: 'Example 1', data: { customer: {} } }], callbacks);
  document.body.append(editor);

  const saveControls = document.createElement('div');
  document.body.append(saveControls);
  editor.setSaveControlsContainer(saveControls);

  await editor.updateComplete;
  return { editor, saveControls };
}

async function settle(editor: EpistolaDataContractEditor): Promise<void> {
  await editor.updateComplete;
  // Saving is async; flush the microtask queue.
  await new Promise((r) => setTimeout(r, 0));
  await editor.updateComplete;
}

afterEach(() => document.body.replaceChildren());

describe('breaking changes after a confirmed save', () => {
  it('does not ask again on the next save once the breaking change is committed', async () => {
    const onSaveSchema = vi.fn().mockResolvedValue({ success: true });
    const onSaveDataExamples = vi.fn().mockResolvedValue({ success: true });
    const { editor, saveControls } = await mountEditor({ onSaveSchema, onSaveDataExamples });

    // Removing a field is a breaking change.
    editor.querySelector<HTMLElement>('[data-field-id="field:note"]')!.click();
    await editor.updateComplete;
    editor.querySelector<HTMLButtonElement>('.dc-detail-delete-btn')!.click();
    await editor.updateComplete;
    expect(editor.querySelector('.dc-breaking-changes-banner')).not.toBeNull();

    saveControls.querySelector<HTMLButtonElement>('.dc-save-btn')!.click();
    await editor.updateComplete;
    expect(editor.querySelector('dialog.dc-dialog')?.textContent).toContain('Breaking Changes');

    editor.querySelector<HTMLButtonElement>('.dc-dialog-actions .ep-btn-primary')!.click();
    await settle(editor);
    expect(onSaveSchema).toHaveBeenCalledOnce();
    expect(editor.querySelector('.dc-breaking-changes-banner')).toBeNull();

    // An examples-only save right after must go straight through.
    editor.querySelector<HTMLButtonElement>('.dc-example-chip-add')!.click();
    await editor.updateComplete;
    saveControls.querySelector<HTMLButtonElement>('.dc-save-btn')!.click();
    await settle(editor);

    expect(editor.querySelector('dialog.dc-dialog')).toBeNull();
    expect(onSaveDataExamples).toHaveBeenCalledOnce();
  });
});
