// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it, vi } from 'vitest';
import { openStencilPickerDialog, type PrepareStencilFn } from './stencil-picker-dialog.js';
import type { StencilCallbacks, StencilSummary } from './types.js';
import type { JsonSchema } from '../../data-contract/types.js';
import { workingCopy, CATALOG, text } from './stencil-test-helpers.js';

const SUMMARY: StencilSummary = {
  stencilKey: 'greeting',
  catalogKey: CATALOG,
  name: 'Greeting',
  tags: [],
};

const PARAM_SCHEMA: JsonSchema = {
  type: 'object',
  properties: { recipient: { type: 'string' } },
  required: ['recipient'],
};

function callbacks(summaries: StencilSummary[] = [SUMMARY]): StencilCallbacks {
  return {
    catalogKey: CATALOG,
    searchStencils: () => Promise.resolve(summaries),
    getStencil: () => Promise.resolve(null),
  };
}

function prepareWith(parameterSchema?: JsonSchema): PrepareStencilFn {
  const copy = workingCopy('greeting', [text('g')], { parameterSchema });
  return () => Promise.resolve({ ok: true, prepared: { copy, fetched: new Map() } });
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

function pickerDialog(): HTMLDialogElement {
  const dialog = document.querySelector<HTMLDialogElement>('dialog.stencil-picker-dialog');
  if (!dialog) throw new Error('stencil picker dialog not opened');
  return dialog;
}

/** Drive the dialog through: stencil list → select → click Insert. */
async function selectStencilAndInsert(): Promise<HTMLDialogElement> {
  await tick(); // searchStencils resolves → renderStencilList
  const dialog = pickerDialog();
  dialog.querySelector<HTMLElement>('#stencil-list .stencil-picker-card')!.click();
  dialog.querySelector<HTMLButtonElement>('.insert')!.click();
  await tick(); // prepare resolves → doInsert decides
  return dialog;
}

afterEach(() => {
  document.querySelectorAll('dialog').forEach((dialog) => dialog.remove());
});

describe('openStencilPickerDialog', () => {
  it('offers no version choice: picking a stencil and Insert is enough', async () => {
    const result = openStencilPickerDialog(callbacks(), { prepare: prepareWith(undefined) });
    await selectStencilAndInsert();

    await expect(result).resolves.toMatchObject({
      action: 'use-existing',
      prepared: { copy: { ref: { stencilKey: 'greeting' } } },
      bindings: {},
      paramsAlias: 'params',
    });
    expect(document.querySelector('#stencil-version-list')).toBeNull();
  });

  it('advances to the binding step when the stencil declares parameters', async () => {
    void openStencilPickerDialog(callbacks(), { prepare: prepareWith(PARAM_SCHEMA) });
    const dialog = await selectStencilAndInsert();

    const bindingStep = dialog.querySelector<HTMLElement>('#stencil-step-bindings')!;
    expect(bindingStep.style.display).not.toBe('none');
    expect(dialog.querySelector('#stencil-binding-title')!.textContent).toContain('greeting');
    expect(dialog.querySelector('#stencil-binding-rows')!.children.length).toBeGreaterThan(0);
    expect(dialog.querySelector<HTMLButtonElement>('button.insert')!.disabled).toBe(true);
  });

  it('allows a required parameter with a default to remain unbound', async () => {
    const schemaWithDefault = {
      type: 'object',
      properties: { recipient: { type: 'string', default: 'Customer' } },
      required: ['recipient'],
    } as JsonSchema;
    void openStencilPickerDialog(callbacks(), { prepare: prepareWith(schemaWithDefault) });
    const dialog = await selectStencilAndInsert();

    expect(dialog.querySelector<HTMLButtonElement>('button.insert')!.disabled).toBe(false);
  });

  it('shows why a stencil cannot be inserted and stays open', async () => {
    const prepare = vi.fn<PrepareStencilFn>().mockResolvedValue({
      ok: false,
      message: "'greeting' cannot go here: a stencil cannot contain itself.",
    });
    const result = openStencilPickerDialog(callbacks(), { prepare });
    const dialog = await selectStencilAndInsert();

    const error = dialog.querySelector<HTMLElement>('[data-testid="stencil-picker-error"]')!;
    expect(error.style.display).not.toBe('none');
    expect(error.textContent).toContain('cannot contain itself');
    expect(dialog.isConnected).toBe(true);
    dialog.querySelector<HTMLElement>('.cancel')!.click();
    await expect(result).resolves.toBeNull();
  });

  it('disables a stencil the insertion point is already the content of', async () => {
    void openStencilPickerDialog(callbacks(), {
      prepare: prepareWith(undefined),
      disabledStencils: new Set([`${CATALOG}/greeting`]),
    });
    await tick();

    const card = pickerDialog().querySelector<HTMLElement>('.stencil-picker-card')!;
    expect(card.classList.contains('stencil-picker-card--disabled')).toBe(true);
    card.click();
    expect(pickerDialog().querySelector<HTMLButtonElement>('button.insert')!.disabled).toBe(true);
  });

  it('offers "Create New" only when stencils can be created', async () => {
    void openStencilPickerDialog(callbacks(), { prepare: prepareWith(undefined) });
    await tick();
    expect(pickerDialog().querySelector('.create-new')).toBeNull();
    pickerDialog().remove();

    void openStencilPickerDialog(
      {
        ...callbacks(),
        createStencil: () => Promise.resolve({ stencilKey: 'x', catalogKey: CATALOG }),
      },
      { prepare: prepareWith(undefined) },
    );
    await tick();
    expect(pickerDialog().querySelector('.create-new')).not.toBeNull();
  });

  it('creates a new stencil and returns its reference', async () => {
    const createStencil = vi.fn().mockResolvedValue({ stencilKey: 'new-one', catalogKey: CATALOG });
    const result = openStencilPickerDialog(
      { ...callbacks(), createStencil },
      { prepare: prepareWith(undefined) },
    );
    await tick();
    const dialog = pickerDialog();
    dialog.querySelector<HTMLElement>('.create-new')!.click();
    const name = dialog.querySelector<HTMLInputElement>('#create-stencil-name')!;
    name.value = 'New One';
    name.dispatchEvent(new Event('input'));
    dialog.querySelector<HTMLButtonElement>('.create-confirm')!.click();

    await expect(result).resolves.toEqual({
      action: 'create-new',
      ref: { stencilKey: 'new-one', catalogKey: CATALOG },
    });
    expect(createStencil).toHaveBeenCalledWith('new-one', 'New One');
  });
});

describe('openStencilPickerDialog escapes author-controlled text', () => {
  const MALICIOUS = '"><img src=x onerror=window.__pwned=1>';

  afterEach(() => {
    (window as unknown as { __pwned?: unknown }).__pwned = undefined;
  });

  it('renders a crafted stencil name, description, and tag as inert text', async () => {
    const evil: StencilSummary = {
      stencilKey: 'evil',
      catalogKey: CATALOG,
      name: `Name ${MALICIOUS}`,
      description: `Desc ${MALICIOUS}`,
      tags: [`Tag ${MALICIOUS}`],
    };
    void openStencilPickerDialog(callbacks([evil]), { prepare: prepareWith(undefined) });
    await tick();

    const card = pickerDialog().querySelector<HTMLElement>('#stencil-list .stencil-picker-card')!;
    expect(card).not.toBeNull();
    expect(card.querySelector('img')).toBeNull();
    expect(card.querySelector('[onerror]')).toBeNull();
    expect(card.querySelector('.stencil-picker-card-name')!.textContent).toContain(
      `Name ${MALICIOUS}`,
    );
    expect(card.querySelector('.stencil-picker-card-desc')!.textContent).toBe(`Desc ${MALICIOUS}`);
    expect(card.querySelector('.stencil-picker-card-tags')!.textContent).toContain(
      `Tag ${MALICIOUS}`,
    );
    expect((window as unknown as { __pwned?: unknown }).__pwned).toBeUndefined();
  });
});
