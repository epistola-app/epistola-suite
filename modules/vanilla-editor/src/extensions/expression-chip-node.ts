/**
 * ExpressionChipNode — Custom TipTap Node extension for inline expression chips.
 *
 * Renders as a non-editable inline span displaying the expression text.
 * Created automatically when the user types `{{` via an input rule,
 * or loaded from existing TipTap JSON content with `type: "expression"`.
 *
 * Schema:
 * - `inline: true`, `group: 'inline'`, `atom: true`
 * - Attribute: `expression` (string) — the JSONata expression
 */

import { Node, mergeAttributes } from '@tiptap/core';
import { InputRule } from '@tiptap/core';

/**
 * Input rule pattern: `{{` followed by content and `}}` inserts an expression chip.
 * e.g. typing `{{customer.name}}` creates a chip with expression "customer.name".
 */
const EXPRESSION_INPUT_REGEX = /\{\{([^}]+)\}\}$/;
const OPEN_EXPRESSION_INPUT_REGEX = /\{\{$/;

export const ExpressionChipNode = Node.create({
  name: 'expression',

  group: 'inline',
  inline: true,
  atom: true,

  addAttributes() {
    return {
      expression: {
        default: '',
        parseHTML: (element: HTMLElement) => element.getAttribute('data-expression') ?? '',
        renderHTML: (attributes: { expression: string }) => ({
          'data-expression': attributes.expression,
        }),
      },
      isNew: {
        default: false,
        parseHTML: (element: HTMLElement) => element.getAttribute('data-is-new') === 'true',
        renderHTML: (attributes: { isNew?: boolean }) =>
          attributes.isNew ? { 'data-is-new': 'true' } : {},
      },
    };
  },

  parseHTML() {
    return [
      { tag: 'span[data-expression]' },
    ];
  },

  renderHTML({ HTMLAttributes }: { HTMLAttributes: Record<string, string> }) {
    const expression = HTMLAttributes['data-expression'] ?? '';

    return [
      'span',
      mergeAttributes(HTMLAttributes, {
        class: 'expression-chip',
        contenteditable: 'false',
      }),
      [
        'span',
        { class: 'expression-chip-expr' },
        expression || '...',
      ],
      [
        'span',
        { class: 'expression-chip-value' },
        '',
      ],
    ];
  },

  addInputRules() {
    return [
      new InputRule({
        find: EXPRESSION_INPUT_REGEX,
        handler: ({ state, range, match }) => {
          const expression = match[1]?.trim() ?? '';
          const { tr } = state;
          const node = this.type.create({ expression, isNew: false });
          tr.replaceWith(range.from, range.to, node);
        },
      }),
      new InputRule({
        find: OPEN_EXPRESSION_INPUT_REGEX,
        handler: ({ state, range }) => {
          const { tr } = state;
          const node = this.type.create({ expression: '', isNew: true });
          tr.replaceWith(range.from, range.to, node);
        },
      }),
    ];
  },
});
