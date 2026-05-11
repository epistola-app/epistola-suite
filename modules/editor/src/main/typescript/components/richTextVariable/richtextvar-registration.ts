import { html } from 'lit';
import type { ComponentDefinition } from '../../engine/registry.js';
import { richTextVariablePathDisabled } from '../../data-contract/binding-compatibility.js';
import './EpistolaRichTextVariablePreview.js';

/**
 * `richTextVariable` block component — renders the rich-text doc bound at the
 * given data path as block content (paragraphs, lists, marks). The inspector's
 * binding picker uses the registry-aware `richTextVariablePathDisabled`
 * predicate so only rich-text contract fields appear as compatible bindings.
 */
export function createRichTextVariableDefinition(): ComponentDefinition {
  return {
    type: 'richTextVariable',
    label: 'Rich text block',
    icon: 'type',
    category: 'content',
    slots: [],
    allowedChildren: { mode: 'none' },
    applicableStyles: 'all',
    inspector: [
      {
        key: 'binding',
        label: 'Binding',
        type: 'expression',
        defaultValue: '',
        pathDisabled: richTextVariablePathDisabled,
      },
    ],
    defaultStyles: { marginBottom: '1.5sp' },
    defaultProps: { binding: '' },
    renderCanvas: ({ node, engine: eng }) => {
      const binding = (node.props?.binding as string | undefined) ?? '';
      return html`
        <div class="rich-text-variable-canvas">
          <epistola-rich-text-variable-preview
            .engine=${eng}
            .nodeId=${node.id}
            .binding=${binding}
          ></epistola-rich-text-variable-preview>
        </div>
      `;
    },
    examples: [
      {
        name: 'minimal',
        description:
          'Renders the rich-text value bound at the given data path as block content (paragraphs, lists, marks).',
        fragment: {
          rootNodeId: 'n-richtextvar-minimal',
          nodes: {
            'n-richtextvar-minimal': {
              id: 'n-richtextvar-minimal',
              type: 'richTextVariable',
              slots: [],
              props: { binding: 'customer.bio' },
            },
          },
          slots: {},
        },
      },
    ],
  };
}
