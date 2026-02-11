import { describe, it, expect } from 'vitest';
import { Editor } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import { ExpressionChipNode } from '../extensions/expression-chip-node';

function createEditor(content?: object) {
  return new Editor({
    extensions: [StarterKit, ExpressionChipNode],
    content: content ?? undefined,
  });
}

describe('ExpressionChipNode', () => {
  it('should register as an inline atom node', () => {
    const editor = createEditor();
    const nodeType = editor.schema.nodes.expression;
    expect(nodeType).toBeDefined();
    expect(nodeType.isInline).toBe(true);
    expect(nodeType.isAtom).toBe(true);
    editor.destroy();
  });

  it('should have an expression attribute defaulting to empty string', () => {
    const editor = createEditor();
    const nodeType = editor.schema.nodes.expression;
    expect(nodeType.spec.attrs?.expression?.default).toBe('');
    expect(nodeType.spec.attrs?.isNew?.default).toBe(false);
    editor.destroy();
  });

  it('should load expression nodes from JSON content', () => {
    const editor = createEditor({
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [
            { type: 'text', text: 'Hello ' },
            { type: 'expression', attrs: { expression: 'customer.name' } },
          ],
        },
      ],
    });

    const json = editor.getJSON();
    const paragraph = json.content?.[0];
    const exprNode = paragraph?.content?.[1];
    expect(exprNode?.type).toBe('expression');
    expect(exprNode?.attrs?.expression).toBe('customer.name');
    editor.destroy();
  });

  it('should serialize expression nodes to JSON', () => {
    const editor = createEditor();
    editor.commands.insertContent({
      type: 'expression',
      attrs: { expression: 'order.total' },
    });

    const json = editor.getJSON();
    const paragraph = json.content?.[0];
    const nodes = paragraph?.content ?? [];
    const exprNode = nodes.find((n) => n.type === 'expression');
    expect(exprNode).toBeDefined();
    expect(exprNode?.attrs?.expression).toBe('order.total');
    editor.destroy();
  });

  it('should render expression chip HTML with data-expression attribute', () => {
    const editor = createEditor({
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [
            { type: 'expression', attrs: { expression: 'test.value' } },
          ],
        },
      ],
    });

    const html = editor.getHTML();
    expect(html).toContain('data-expression="test.value"');
    expect(html).toContain('expression-chip');
    editor.destroy();
  });

  it('should handle expression nodes without attrs gracefully', () => {
    const editor = createEditor({
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [
            { type: 'expression', attrs: { expression: '' } },
          ],
        },
      ],
    });

    const json = editor.getJSON();
    const paragraph = json.content?.[0];
    const exprNode = paragraph?.content?.[0];
    expect(exprNode?.attrs?.expression).toBe('');
    expect(exprNode?.attrs?.isNew).toBe(false);
    editor.destroy();
  });

  it('should serialize and render isNew attribute when true', () => {
    const editor = createEditor();
    editor.commands.insertContent({
      type: 'expression',
      attrs: { expression: '', isNew: true },
    });

    const json = editor.getJSON();
    const paragraph = json.content?.[0];
    const exprNode = paragraph?.content?.[0];
    expect(exprNode?.attrs?.isNew).toBe(true);

    const html = editor.getHTML();
    expect(html).toContain('data-is-new="true"');
    editor.destroy();
  });

  it('should parse isNew from HTML attribute', () => {
    const editor = createEditor({
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [
            {
              type: 'expression',
              attrs: { expression: '', isNew: true },
            },
          ],
        },
      ],
    });

    const html = editor.getHTML();
    expect(html).toContain('data-is-new="true"');
    editor.destroy();
  });
});
