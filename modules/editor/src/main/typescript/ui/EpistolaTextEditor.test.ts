// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it, vi } from 'vitest';
import { AllSelection } from 'prosemirror-state';
import type { EditorView } from 'prosemirror-view';
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
    const view = (editor as unknown as { _pmView: EditorView })._pmView;
    view.dispatch(view.state.tr.setSelection(new AllSelection(view.state.doc)));
    expect(view.state.selection.empty).toBe(false);

    expressionChip.click();
    expect(view.state.selection.empty).toBe(true);

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

describe('EpistolaTextEditor expression chips and contract defaults', () => {
  it("shows a field's contract default when the example leaves it out", async () => {
    const content = {
      type: 'doc',
      content: [
        { type: 'paragraph', content: [{ type: 'expression', attrs: { expression: 'field2' } }] },
      ],
    };
    const doc = initialDocument();
    doc.nodes['initial-text' as NodeId] = {
      ...doc.nodes['initial-text' as NodeId],
      props: { content },
    };
    const engine = new EditorEngine(doc, createDefaultRegistry(), {
      dataModel: {
        type: 'object',
        properties: { field2: { type: 'string', default: 'testss' } },
      },
      dataExamples: [{ id: 'ex1', name: 'Example 1', data: {} }],
    });
    const editor = new EpistolaTextEditor();
    editor.engine = engine;
    editor.nodeId = 'initial-text' as NodeId;
    editor.content = content;
    document.body.appendChild(editor);
    await editor.updateComplete;

    const chip = editor.querySelector<HTMLElement>('.expression-chip')!;
    await vi.waitFor(() => expect(chip.classList.contains('is-raw')).toBe(false));
    expect(chip.textContent).toContain('testss');
  });
});
