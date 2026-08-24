// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it } from 'vitest';
import { mountDataContractEditor, type DataContractEditorInstance } from './data-contract-lib.js';
import type { EpistolaDataContractEditor } from './data-contract/EpistolaDataContractEditor.js';

const instances: DataContractEditorInstance[] = [];

function mount(saveControlsContainer: HTMLElement | null, readonly = false) {
  const container = document.createElement('div');
  document.body.append(container);

  const instance = mountDataContractEditor({
    container,
    templateId: 'example-template',
    initialSchema: null,
    initialExamples: [{ id: 'example-1', name: 'Example 1', data: {} }],
    callbacks: {},
    readonly,
    saveControlsContainer,
  });
  instances.push(instance);

  return {
    container,
    editor: container.querySelector<EpistolaDataContractEditor>('epistola-data-contract-editor')!,
    instance,
  };
}

afterEach(() => {
  for (const instance of instances.splice(0)) instance.unmount();
  document.body.innerHTML = '';
});

describe('mountDataContractEditor save controls', () => {
  it('renders save controls in the host bar rather than inside the editor', async () => {
    const saveControlsContainer = document.createElement('div');
    document.body.append(saveControlsContainer);
    const { container, editor } = mount(saveControlsContainer);

    await editor.updateComplete;

    expect(saveControlsContainer.textContent).toContain('All changes saved');
    expect(saveControlsContainer.textContent).toContain('Save draft');
    expect(container.querySelector('.dc-contract-save-controls')).toBeNull();
  });

  it('keeps external save state in sync with editor changes', async () => {
    const saveControlsContainer = document.createElement('div');
    document.body.append(saveControlsContainer);
    const { editor } = mount(saveControlsContainer);
    await editor.updateComplete;

    editor.contractState?.updateDraftExample('example-1', { name: 'Updated example' });
    await editor.updateComplete;

    expect(saveControlsContainer.textContent).toContain('Unsaved draft changes');
    expect(saveControlsContainer.textContent).toContain('Examples');
  });

  it('clears externally rendered save controls when unmounted', async () => {
    const saveControlsContainer = document.createElement('div');
    document.body.append(saveControlsContainer);
    const { editor, instance } = mount(saveControlsContainer);
    await editor.updateComplete;

    expect(saveControlsContainer.textContent).toContain('Save draft');

    instance.unmount();
    expect(saveControlsContainer.querySelector('.dc-contract-save-controls')).toBeNull();
  });

  it('renders no save controls when read-only', async () => {
    const externalContainer = document.createElement('div');
    document.body.append(externalContainer);
    const readOnlyMount = mount(externalContainer, true);
    await readOnlyMount.editor.updateComplete;

    expect(externalContainer.querySelector('.dc-contract-save-controls')).toBeNull();
    expect(readOnlyMount.container.querySelector('.dc-contract-save-controls')).toBeNull();
  });
});
