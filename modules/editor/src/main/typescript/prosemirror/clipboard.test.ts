// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it } from 'vitest';
import { DOMParser } from 'prosemirror-model';
import { AllSelection, EditorState, NodeSelection } from 'prosemirror-state';
import { EditorView } from 'prosemirror-view';
import { ExpressionNodeView } from './ExpressionNodeView.js';
import { epistolaSchema } from './schema.js';

const mountedViews: EditorView[] = [];

afterEach(() => {
  for (const view of mountedViews) view.destroy();
  mountedViews.length = 0;
  document.body.replaceChildren();
});

function expressionDocument() {
  return epistolaSchema.node('doc', null, [
    epistolaSchema.node('paragraph', null, [
      epistolaSchema.text('Hello '),
      epistolaSchema.nodes.expression.create({
        expression: 'customer.name',
        isNew: false,
      }),
      epistolaSchema.text('!'),
    ]),
  ]);
}

describe('expression clipboard behavior', () => {
  it('serializes expression-rich text as a chip and readable plain text', () => {
    const doc = expressionDocument();
    const state = EditorState.create({
      doc,
      selection: new AllSelection(doc),
    });
    const mount = document.body.appendChild(document.createElement('div'));
    const view = new EditorView(mount, { state });
    mountedViews.push(view);

    const serialized = view.serializeForClipboard(state.selection.content());

    expect(serialized.text).toBe('Hello {{customer.name}}!');
    expect(serialized.dom.innerHTML).toContain('data-expression="customer.name"');
    expect(serialized.dom.textContent).toBe('Hello {{customer.name}}!');

    const pasted = DOMParser.fromSchema(epistolaSchema).parse(serialized.dom);
    const expression = pasted.firstChild?.child(1);
    expect(expression?.type.name).toBe('expression');
    expect(expression?.attrs.expression).toBe('customer.name');
  });

  it('copies an individually selected expression atom', () => {
    const doc = expressionDocument();
    const state = EditorState.create({
      doc,
      selection: NodeSelection.create(doc, 7),
    });
    const mount = document.body.appendChild(document.createElement('div'));
    const view = new EditorView(mount, { state });
    mountedViews.push(view);

    const serialized = view.serializeForClipboard(state.selection.content());

    expect(state.selection.node.type.name).toBe('expression');
    expect(serialized.text).toBe('{{customer.name}}');
    expect(serialized.dom.innerHTML).toContain('data-expression="customer.name"');
  });

  it('lets ProseMirror handle pointer selection and clipboard events from the chip', () => {
    const node = epistolaSchema.nodes.expression.create({
      expression: 'customer.name',
      isNew: false,
    });
    const nodeView = new ExpressionNodeView(node, {} as EditorView, () => 1, {
      getFieldPaths: () => [],
    });

    expect(nodeView.stopEvent(new Event('mousedown'))).toBe(false);
    expect(nodeView.stopEvent(new Event('mouseup'))).toBe(false);
    expect(nodeView.stopEvent(new Event('copy'))).toBe(false);
    expect(nodeView.stopEvent(new Event('cut'))).toBe(false);
    expect(nodeView.stopEvent(new Event('paste'))).toBe(false);
    expect(nodeView.stopEvent(new Event('click'))).toBe(true);

    nodeView.destroy();
  });
});
