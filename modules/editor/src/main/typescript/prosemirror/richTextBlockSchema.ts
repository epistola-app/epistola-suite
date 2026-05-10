/**
 * ProseMirror schema for *block* rich-text parameter values.
 *
 * Subset of the editor's `epistolaSchema` (see ./schema.ts):
 *   - omits `heading` (headings belong in template structure, not parameter content)
 *   - omits `expression` (reserved for phase 2)
 *   - retains paragraphs, hard breaks, bullet/ordered lists, and the shared marks
 *
 * Wire format constrained by https://epistola.app/schemas/richtext-block-v1.json.
 * Companion: `richTextInlineSchema` for single-paragraph values.
 */

import { Schema, type NodeSpec } from 'prosemirror-model';
import { nodes as basicNodes } from 'prosemirror-schema-basic';
import { addListNodes } from 'prosemirror-schema-list';
import { richTextMarks } from './richTextMarks.js';

const baseNodes: Record<string, NodeSpec> = {
  doc: { content: 'block+' },
  paragraph: basicNodes.paragraph,
  text: basicNodes.text,
  hard_break: basicNodes.hard_break,
};

const tempSchema = new Schema({ nodes: baseNodes, marks: {} });
// eslint-disable-next-line @typescript-eslint/no-explicit-any
let withListNodes = addListNodes(tempSchema.spec.nodes as any, 'paragraph block*', 'block');

const olSpec = withListNodes.get('ordered_list')!;
withListNodes = withListNodes.update('ordered_list', {
  ...olSpec,
  attrs: {
    ...(olSpec.attrs as Record<string, unknown>),
    listType: { default: 'decimal' },
  },
  parseDOM: [
    {
      tag: 'ol',
      getAttrs(dom) {
        const el = dom as HTMLElement;
        return {
          order: el.hasAttribute('start') ? +el.getAttribute('start')! : 1,
          listType: el.getAttribute('data-list-type') || 'decimal',
        };
      },
    },
  ],
  toDOM(node) {
    const attrs: Record<string, string> = {};
    if (node.attrs.order !== 1) attrs.start = String(node.attrs.order);
    if (node.attrs.listType !== 'decimal') {
      attrs['data-list-type'] = node.attrs.listType;
      attrs.style = `list-style-type: ${node.attrs.listType}`;
    }
    return Object.keys(attrs).length ? ['ol', attrs, 0] : ['ol', 0];
  },
});

const ulSpec = withListNodes.get('bullet_list')!;
withListNodes = withListNodes.update('bullet_list', {
  ...ulSpec,
  attrs: { listStyle: { default: 'disc' } },
  parseDOM: [
    {
      tag: 'ul',
      getAttrs(dom) {
        const el = dom as HTMLElement;
        return { listStyle: el.getAttribute('data-list-style') || 'disc' };
      },
    },
  ],
  toDOM(node) {
    if (node.attrs.listStyle !== 'disc') {
      return [
        'ul',
        {
          'data-list-style': node.attrs.listStyle,
          style: `list-style-type: ${node.attrs.listStyle === 'dash' ? '"– "' : node.attrs.listStyle}`,
        },
        0,
      ];
    }
    return ['ul', 0];
  },
});

/** ProseMirror schema for *block* rich-text parameter values. */
export const richTextBlockSchema = new Schema({
  nodes: withListNodes,
  marks: richTextMarks,
});

/** Default value for a freshly-created `richTextBlock` field. */
export const EMPTY_RICH_TEXT_BLOCK_DOC = { type: 'doc', content: [] } as const;
