// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it } from 'vitest';
import { EpistolaDataContractEditor } from './EpistolaDataContractEditor.js';

async function mountEditorWithNoContract(): Promise<EpistolaDataContractEditor> {
  const editor = new EpistolaDataContractEditor();
  editor.init(null, [], {});
  document.body.append(editor);
  await editor.updateComplete;
  return editor;
}

afterEach(() => document.body.replaceChildren());

describe('breaking-change banner on a never-published contract', () => {
  it('does not appear when adding a required field with no default', async () => {
    const editor = await mountEditorWithNoContract();

    editor
      .querySelector<HTMLButtonElement>('button[aria-label="Add field to data contract"]')!
      .click();
    await editor.updateComplete;

    editor.querySelector<HTMLInputElement>('#dc-detail-required')!.click();
    await editor.updateComplete;

    expect(editor.querySelector('.dc-breaking-changes-banner')).toBeNull();
  });
});
