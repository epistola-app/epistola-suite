// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it } from 'vitest';
import { openStencilPickerDialog } from './stencil-picker-dialog.js';
import type {
  StencilCallbacks,
  StencilSummary,
  StencilVersionInfo,
  StencilVersionSummary,
} from './types.js';
import type { JsonSchema } from '../../data-contract/types.js';
import { createTestDocument } from '../../engine/test-helpers.js';

const SUMMARY: StencilSummary = {
  id: 'greeting',
  catalogKey: 'default',
  name: 'Greeting',
  tags: [],
  latestPublishedVersion: 1,
  latestVersion: 1,
};

const VERSION: StencilVersionSummary = {
  version: 1,
  status: 'published',
  createdAt: '2026-01-01T00:00:00Z',
  publishedAt: '2026-01-01T00:00:00Z',
};

const PARAM_SCHEMA: JsonSchema = {
  type: 'object',
  properties: { recipient: { type: 'string' } },
  required: ['recipient'],
};

function versionInfo(parameterSchema?: JsonSchema): StencilVersionInfo {
  return {
    ref: { stencilId: 'greeting', catalogKey: 'default' },
    stencilName: 'Greeting',
    version: 1,
    content: createTestDocument(),
    parameterSchema,
  };
}

function callbacks(info: StencilVersionInfo): StencilCallbacks {
  return {
    searchStencils: () => Promise.resolve([SUMMARY]),
    listVersions: () => Promise.resolve([VERSION]),
    getStencilVersion: () => Promise.resolve(info),
  };
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

function pickerDialog(): HTMLDialogElement {
  const dialog = document.querySelector<HTMLDialogElement>('dialog.stencil-picker-dialog');
  if (!dialog) throw new Error('stencil picker dialog not opened');
  return dialog;
}

/** Drive the dialog through: stencil list → version list → click Insert. */
async function selectStencilAndInsert(): Promise<HTMLDialogElement> {
  await tick(); // searchStencils resolves → renderStencilList
  const dialog = pickerDialog();
  dialog.querySelector<HTMLElement>('#stencil-list .stencil-picker-card')!.click();
  await tick(); // listVersions resolves → renderVersionList
  dialog.querySelector<HTMLElement>('#stencil-version-list .stencil-picker-card')!.click();
  dialog.querySelector<HTMLButtonElement>('.insert')!.click();
  await tick(); // getStencilVersion resolves → doInsert decides
  return dialog;
}

describe('openStencilPickerDialog parameter binding step', () => {
  afterEach(() => {
    document.querySelectorAll('dialog').forEach((dialog) => dialog.remove());
  });

  // Parameters are intrinsic to a stencil (no feature toggle): inserting a version that
  // declares any always routes through the binding step so the consumer can bind them.
  it('advances to the binding step when the picked version declares parameters', async () => {
    openStencilPickerDialog(callbacks(versionInfo(PARAM_SCHEMA)));
    const dialog = await selectStencilAndInsert();

    const bindingStep = dialog.querySelector<HTMLElement>('#stencil-step-bindings')!;
    expect(bindingStep.style.display).not.toBe('none');
    expect(dialog.querySelector('#stencil-binding-title')!.textContent).toContain('Greeting');
    expect(dialog.querySelector('#stencil-binding-rows')!.children.length).toBeGreaterThan(0);
  });

  // The empty parameter set (the common case) skips binding and inserts straight away.
  it('inserts immediately with empty bindings when the version declares no parameters', async () => {
    const result = openStencilPickerDialog(callbacks(versionInfo(undefined)));
    await selectStencilAndInsert();

    await expect(result).resolves.toMatchObject({
      action: 'use-existing',
      bindings: {},
      paramsAlias: 'params',
    });
  });
});
