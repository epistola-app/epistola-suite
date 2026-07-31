// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it, vi } from 'vitest';
import { mountEditor, type EditorInstance } from '../../lib.js';
import type { EpistolaEditor } from '../../ui/EpistolaEditor.js';
import type { EpExpressionDialog } from '../../ui/EpExpressionDialog.js';
import type { NodeId, SlotId, TemplateDocument } from '../../types/index.js';
import type { StencilVersionInfo } from './types.js';
import { createMockCallbacks } from './stencil-test-helpers.js';

const EMPTY_EXPRESSION_CONTENT = {
  type: 'doc',
  content: [
    {
      type: 'paragraph',
      content: [{ type: 'expression', attrs: { expression: '' } }],
    },
  ],
};

const PARAMETER_SCHEMA = {
  type: 'object' as const,
  properties: {
    param1: { type: 'string' as const, default: 'Hydrated value' },
  },
};

function embeddedDraftDocument(): TemplateDocument {
  return {
    modelVersion: 1,
    root: 'root' as NodeId,
    nodes: {
      root: { id: 'root' as NodeId, type: 'root', slots: ['root-slot' as SlotId] },
      'stencil-instance': {
        id: 'stencil-instance' as NodeId,
        type: 'stencil',
        slots: ['stencil-slot' as SlotId],
        props: {
          stencilId: 'repro',
          catalogKey: 'default',
          draftVersion: 2,
          parameterSchemaSnapshot: PARAMETER_SCHEMA,
        },
      },
      'embedded-text': {
        id: 'embedded-text' as NodeId,
        type: 'text',
        slots: [],
        props: { content: EMPTY_EXPRESSION_CONTENT },
      },
    },
    slots: {
      'root-slot': {
        id: 'root-slot' as SlotId,
        nodeId: 'root' as NodeId,
        name: 'children',
        children: ['stencil-instance' as NodeId],
      },
      'stencil-slot': {
        id: 'stencil-slot' as SlotId,
        nodeId: 'stencil-instance' as NodeId,
        name: 'children',
        children: ['embedded-text' as NodeId],
      },
    },
    themeRef: { type: 'inherit' },
  };
}

function fetchedDraft(): StencilVersionInfo {
  return {
    ref: { stencilId: 'repro', catalogKey: 'default' },
    stencilName: 'Reproduction',
    version: 2,
    status: 'draft',
    parameterSchema: PARAMETER_SCHEMA,
    content: {
      modelVersion: 1,
      root: 'draft-root' as NodeId,
      nodes: {
        'draft-root': {
          id: 'draft-root' as NodeId,
          type: 'root',
          slots: ['draft-root-slot' as SlotId],
        },
        'draft-text': {
          id: 'draft-text' as NodeId,
          type: 'text',
          slots: [],
          props: { content: EMPTY_EXPRESSION_CONTENT },
        },
      },
      slots: {
        'draft-root-slot': {
          id: 'draft-root-slot' as SlotId,
          nodeId: 'draft-root' as NodeId,
          name: 'children',
          children: ['draft-text' as NodeId],
        },
      },
      themeRef: { type: 'inherit' },
    },
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
}

function mountWithVersionLoader(getStencilVersion: () => Promise<StencilVersionInfo | null>): {
  container: HTMLElement;
  editor: EpistolaEditor;
  instance: EditorInstance;
} {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const instance = mountEditor({
    container,
    template: embeddedDraftDocument(),
    stencilOptions: createMockCallbacks({ getStencilVersion }),
  });
  const editor = container.querySelector<EpistolaEditor>('epistola-editor')!;
  return { container, editor, instance };
}

function hydratedTextId(editor: EpistolaEditor): NodeId {
  const stencil = editor.engine!.doc.nodes['stencil-instance'];
  return editor.engine!.doc.slots[stencil.slots[0]].children[0];
}

async function openHydratedExpressionDialog(
  container: HTMLElement,
  textNodeId: NodeId,
): Promise<EpExpressionDialog> {
  await vi.waitFor(() => {
    expect(
      container.querySelector(`.canvas-block[data-node-id="${textNodeId}"] .expression-chip`),
    ).not.toBeNull();
  });
  container
    .querySelector<HTMLElement>(`.canvas-block[data-node-id="${textNodeId}"] .expression-chip`)!
    .click();
  await vi.waitFor(() => {
    expect(document.querySelector('ep-expression-dialog')).not.toBeNull();
  });
  return document.querySelector<EpExpressionDialog>('ep-expression-dialog')!;
}

const mountedInstances: EditorInstance[] = [];

afterEach(() => {
  for (const instance of mountedInstances.splice(0)) instance.unmount();
  document.body.replaceChildren();
});

describe('mounted editor stencil draft hydration', () => {
  it('exposes hydrated parameters when the version request resolves immediately', async () => {
    const { container, editor, instance } = mountWithVersionLoader(() =>
      Promise.resolve(fetchedDraft()),
    );
    mountedInstances.push(instance);

    await vi.waitFor(() => {
      expect(editor.engine!.doc.nodes['embedded-text']).toBeUndefined();
    });
    const dialog = await openHydratedExpressionDialog(container, hydratedTextId(editor));

    expect(dialog.fieldPaths).toEqual(
      expect.arrayContaining([expect.objectContaining({ path: 'params.param1' })]),
    );
    dialog.close(null);
  });

  it('replaces the mounted text editor and resolves manually entered parameters after delayed hydration', async () => {
    const versionGate = deferred<StencilVersionInfo | null>();
    const { container, editor, instance } = mountWithVersionLoader(() => versionGate.promise);
    mountedInstances.push(instance);

    await vi.waitFor(() => {
      expect(
        container.querySelector('.canvas-block[data-node-id="embedded-text"] epistola-text-editor'),
      ).not.toBeNull();
    });
    const embeddedEditor = container.querySelector(
      '.canvas-block[data-node-id="embedded-text"] epistola-text-editor',
    );

    versionGate.resolve(fetchedDraft());
    await vi.waitFor(() => {
      expect(editor.engine!.doc.nodes['embedded-text']).toBeUndefined();
    });
    const hydratedId = hydratedTextId(editor);
    await vi.waitFor(() => {
      expect(
        container.querySelector(`.canvas-block[data-node-id="${hydratedId}"] epistola-text-editor`),
      ).not.toBeNull();
    });
    const hydratedEditor = container.querySelector(
      `.canvas-block[data-node-id="${hydratedId}"] epistola-text-editor`,
    );
    expect(hydratedEditor).not.toBe(embeddedEditor);
    expect((embeddedEditor as HTMLElement).isConnected).toBe(false);

    const dialog = await openHydratedExpressionDialog(container, hydratedId);
    expect(dialog.fieldPaths).toEqual(
      expect.arrayContaining([expect.objectContaining({ path: 'params.param1' })]),
    );

    dialog.querySelector<HTMLButtonElement>('.mode-btn[data-mode="code"]')!.click();
    await dialog.updateComplete;
    const input = dialog.querySelector<HTMLTextAreaElement>('.expression-dialog-input')!;
    input.value = 'params.param1';
    input.dispatchEvent(new InputEvent('input', { bubbles: true }));
    dialog.querySelector<HTMLButtonElement>('.expression-dialog-btn.save')!.click();

    await vi.waitFor(() => {
      const value = container.querySelector<HTMLElement>(
        `.canvas-block[data-node-id="${hydratedId}"] .expression-chip-content`,
      )?.textContent;
      expect(value).toBe('Hydrated value');
    });
  });
});
