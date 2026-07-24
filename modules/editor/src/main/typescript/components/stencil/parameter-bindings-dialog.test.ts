// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it } from 'vitest';
import { openParameterBindingsDialog } from './parameter-bindings-dialog.js';
import type { JsonSchema } from '../../data-contract/types.js';

const schema: JsonSchema = {
  type: 'object',
  properties: {
    param1: { type: 'string' },
  },
  required: ['param1'],
};

function openDialog(): HTMLDialogElement {
  openParameterBindingsDialog({ schema });
  const dialog = document.querySelector('dialog');
  if (!dialog) throw new Error('dialog not opened');
  return dialog;
}

function setBindingValue(dialog: HTMLDialogElement, value: string): void {
  const input = dialog.querySelector<HTMLInputElement>('.binding-row-input');
  if (!input) throw new Error('binding input not found');
  input.value = value;
  input.dispatchEvent(new InputEvent('input', { bubbles: true }));
}

function saveButton(dialog: HTMLDialogElement): HTMLButtonElement {
  const button = dialog.querySelector<HTMLButtonElement>('.save');
  if (!button) throw new Error('save button not found');
  return button;
}

describe('openParameterBindingsDialog', () => {
  afterEach(() => {
    document.querySelectorAll('dialog').forEach((dialog) => dialog.remove());
  });

  it('keeps Save disabled until required bindings are set', () => {
    const dialog = openDialog();

    expect(saveButton(dialog).disabled).toBe(true);
  });

  it('enables Save for a required valid JSONata string literal', () => {
    const dialog = openDialog();

    setBindingValue(dialog, "'hello there'");

    expect(saveButton(dialog).disabled).toBe(false);
  });

  it('keeps Save disabled for invalid JSONata syntax', () => {
    const dialog = openDialog();

    setBindingValue(dialog, 'hello there');

    expect(saveButton(dialog).disabled).toBe(true);
  });
});
