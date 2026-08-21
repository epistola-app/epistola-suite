// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it, vi } from 'vitest';
import { EditorEngine } from '../engine/EditorEngine.js';
import { createDefaultRegistry } from '../engine/registry.js';
import { createStencilDefinition } from '../components/stencil/stencil-registration.js';
import type { NodeId, SlotId, TemplateDocument } from '../types/index.js';
import type { EpExpressionDialog } from './EpExpressionDialog.js';
import { EpistolaTextEditor } from './EpistolaTextEditor.js';

const EXPRESSION_CONTENT = {
  type: 'doc',
  content: [
    {
      type: 'paragraph',
      content: [{ type: 'expression', attrs: { expression: 'params.param1' } }],
    },
  ],
};

function initialDocument(): TemplateDocument {
  return {
    modelVersion: 1,
    root: 'root' as NodeId,
    nodes: {
      root: { id: 'root' as NodeId, type: 'root', slots: ['root-slot' as SlotId] },
      'initial-text': {
        id: 'initial-text' as NodeId,
        type: 'text',
        slots: [],
        props: { content: EXPRESSION_CONTENT },
      },
    },
    slots: {
      'root-slot': {
        id: 'root-slot' as SlotId,
        nodeId: 'root' as NodeId,
        name: 'children',
        children: ['initial-text' as NodeId],
      },
    },
    themeRef: { type: 'inherit' },
  };
}

function hydratedDocument(): TemplateDocument {
  return {
    modelVersion: 1,
    root: 'root' as NodeId,
    nodes: {
      root: { id: 'root' as NodeId, type: 'root', slots: ['root-slot' as SlotId] },
      stencil: {
        id: 'stencil' as NodeId,
        type: 'stencil',
        slots: ['stencil-slot' as SlotId],
        props: {
          stencilId: 'repro',
          catalogKey: 'default',
          draftVersion: 2,
          parameterSchemaSnapshot: {
            type: 'object',
            properties: { param1: { type: 'string', default: 'Hydrated value' } },
          },
        },
      },
      'hydrated-text': {
        id: 'hydrated-text' as NodeId,
        type: 'text',
        slots: [],
        props: { content: EXPRESSION_CONTENT },
      },
    },
    slots: {
      'root-slot': {
        id: 'root-slot' as SlotId,
        nodeId: 'root' as NodeId,
        name: 'children',
        children: ['stencil' as NodeId],
      },
      'stencil-slot': {
        id: 'stencil-slot' as SlotId,
        nodeId: 'stencil' as NodeId,
        name: 'children',
        children: ['hydrated-text' as NodeId],
      },
    },
    themeRef: { type: 'inherit' },
  };
}

afterEach(() => {
  document.body.replaceChildren();
});

describe('EpistolaTextEditor expression scope callbacks', () => {
  it('dereferences the current node after its initial ProseMirror mount', async () => {
    const registry = createDefaultRegistry();
    registry.register(createStencilDefinition({ callbacks: null }));
    const engine = new EditorEngine(initialDocument(), registry);
    const editor = new EpistolaTextEditor();
    editor.engine = engine;
    editor.nodeId = 'initial-text' as NodeId;
    editor.content = EXPRESSION_CONTENT;
    document.body.appendChild(editor);
    await editor.updateComplete;

    engine.replaceDocument(hydratedDocument(), 'HydrateStencilDrafts');
    editor.nodeId = 'hydrated-text' as NodeId;
    editor.content = EXPRESSION_CONTENT;
    await editor.updateComplete;

    const expressionChip = editor.querySelector<HTMLElement>('.expression-chip')!;
    const chipRange = document.createRange();
    chipRange.selectNodeContents(expressionChip);
    document.getSelection()!.removeAllRanges();
    document.getSelection()!.addRange(chipRange);
    expect(document.getSelection()!.toString()).not.toBe('');

    expressionChip.click();
    expect(document.getSelection()!.toString()).toBe('');

    await vi.waitFor(() => {
      expect(document.querySelector('ep-expression-dialog')).not.toBeNull();
    });
    const dialog = document.querySelector<EpExpressionDialog>('ep-expression-dialog')!;
    expect(dialog.fieldPaths).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          path: 'params.param1',
          scopeKind: 'stencil-parameter',
        }),
      ]),
    );
    expect(dialog.getExampleData?.()).toMatchObject({
      params: { param1: 'Hydrated value' },
    });
    dialog.close(null);
  });
});
