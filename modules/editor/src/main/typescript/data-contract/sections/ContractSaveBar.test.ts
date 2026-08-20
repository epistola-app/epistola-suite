// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { render } from 'lit';
import { describe, expect, it, vi } from 'vitest';
import {
  renderContractSaveBar,
  type ContractSaveBarCallbacks,
  type ContractSaveBarState,
} from './ContractSaveBar.js';

const defaultState: ContractSaveBarState = {
  schemaDirty: false,
  examplesDirty: false,
  saving: false,
  saveSuccess: false,
  saveError: null,
  canForceSave: false,
  blockedReason: null,
};

function renderBar(
  state: Partial<ContractSaveBarState> = {},
  callbacks: ContractSaveBarCallbacks = { onSave: vi.fn(), onForceSave: vi.fn() },
): HTMLElement {
  const container = document.createElement('div');
  render(renderContractSaveBar({ ...defaultState, ...state }, callbacks), container);
  return container;
}

describe('ContractSaveBar', () => {
  it('shows every dirty contract part and saves through one action', () => {
    const onSave = vi.fn();
    const container = renderBar(
      { schemaDirty: true, examplesDirty: true },
      { onSave, onForceSave: vi.fn() },
    );

    expect(container.textContent).toContain('Unsaved changes');
    expect(container.textContent).toContain('Schema');
    expect(container.textContent).toContain('Examples');

    const saveButton = container.querySelector<HTMLButtonElement>('.dc-save-btn');
    expect(saveButton?.disabled).toBe(false);
    saveButton?.click();
    expect(onSave).toHaveBeenCalledOnce();
  });

  it('disables saving and explains a blocking validation requirement', () => {
    const container = renderBar({
      examplesDirty: true,
      blockedReason: 'Add at least one test data example before saving',
    });

    expect(container.textContent).toContain('Add at least one test data example before saving');
    expect(container.querySelector<HTMLButtonElement>('.dc-save-btn')?.disabled).toBe(true);
  });

  it('distinguishes a single dirty contract part and disables saving when clean', () => {
    const schemaOnly = renderBar({ schemaDirty: true });
    expect(schemaOnly.querySelector('.dc-contract-save-summary')?.textContent).toContain('Schema');
    expect(schemaOnly.querySelector('.dc-contract-save-summary')?.textContent).not.toContain(
      'Examples',
    );

    const clean = renderBar();
    expect(clean.textContent).toContain('All changes saved');
    expect(clean.querySelector<HTMLButtonElement>('.dc-save-btn')?.disabled).toBe(true);
  });

  it('shows the shared saving and saved states', () => {
    const saving = renderBar({ examplesDirty: true, saving: true });
    expect(saving.textContent).toContain('Saving contract…');
    expect(saving.querySelector<HTMLButtonElement>('.dc-save-btn')?.textContent).toContain(
      'Saving…',
    );

    const saved = renderBar({ saveSuccess: true });
    expect(saved.textContent).toContain('Contract saved');
  });

  it('shows errors and exposes the existing force-save action', () => {
    const onForceSave = vi.fn();
    const container = renderBar(
      { schemaDirty: true, saveError: 'Schema validation failed', canForceSave: true },
      { onSave: vi.fn(), onForceSave },
    );

    expect(container.textContent).toContain('Schema validation failed');
    const forceButton = container.querySelector<HTMLButtonElement>('.dc-force-save-btn');
    forceButton?.click();
    expect(onForceSave).toHaveBeenCalledOnce();
  });
});
