/**
 * Placeholder component definition for the component registry.
 *
 * A placeholder has two slots:
 *
 *  - `default` — the stencil author's default content (set in stencil-edit
 *    mode, frozen once the stencil is published).
 *  - `fill`    — the embedding template's override (set in template-fill
 *    mode; empty in newly-inserted stencils).
 *
 * Renderer semantics: render `fill` if non-empty, otherwise `default`.
 * Clearing the override therefore reverts to the default.
 */

import type { ComponentDefinition } from '../../engine/registry.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import type { Node, NodeId } from '../../types/index.js';
import { html, nothing } from 'lit';
import { nanoid } from 'nanoid';
import { placeholderContext } from '../stencil/ancestry.js';
import { PLACEHOLDER_TYPE, PLACEHOLDER_SLOT_DEFAULT, PLACEHOLDER_SLOT_FILL } from './constants.js';
import { isPlaceholder, placeholderName } from './node-types.js';
import './PlaceholderInspector.js';

function placeholderModeLabel(node: Node, engineUnknown: unknown): string {
  const engine = engineUnknown as EditorEngine | undefined;
  if (!engine) return 'default';
  const context = placeholderContext(engine.doc, node.id, engine.indexes);
  if (context === 'stencil-author') return 'default';
  const slotsById = engine.doc.slots as Record<string, { name: string; children: NodeId[] }>;
  const fillSlotId = node.slots.find(
    (sid) => slotsById[sid as string]?.name === PLACEHOLDER_SLOT_FILL,
  );
  const fillChildren = fillSlotId ? (slotsById[fillSlotId as string]?.children ?? []) : [];
  return fillChildren.length > 0 ? 'override' : 'default (preview)';
}

export function createPlaceholderDefinition(): ComponentDefinition {
  return {
    type: PLACEHOLDER_TYPE,
    label: 'Placeholder',
    getLabel: (node, engine) => {
      const name = placeholderName(node) ?? '';
      const mode = placeholderModeLabel(node, engine);
      return name ? `Placeholder · ${name} · ${mode}` : `Placeholder · ${mode}`;
    },
    icon: 'square-dashed',
    category: 'layout',
    slots: [
      // `default` inherits whatever lock the surrounding stencil has — the
      // stencil author edits it (in draft mode) and the template author
      // cannot (when published).
      { name: PLACEHOLDER_SLOT_DEFAULT },
      // `fill` is always user-editable, even inside a published (locked)
      // stencil. This is what makes placeholders work in templates.
      { name: PLACEHOLDER_SLOT_FILL, editable: true },
    ],
    allowedChildren: { mode: 'all' },
    applicableStyles: 'all',
    inspector: [],
    defaultProps: { name: '', description: '', kind: 'block' },

    onBeforeInsert: async (engineUnknown) => {
      // Auto-generate a unique kebab-case name so a freshly dropped placeholder
      // saves without manual editing. The user can rename via the inspector.
      const engine = engineUnknown as EditorEngine;
      const taken = new Set<string>();
      for (const n of Object.values(engine.doc.nodes)) {
        if (isPlaceholder(n)) {
          const existing = n.props.name;
          if (existing) taken.add(existing);
        }
      }
      let i = 1;
      while (taken.has(`placeholder-${i}`)) i++;
      return { name: `placeholder-${i}`, description: '', kind: 'block' };
    },

    createInitialSlots: (nodeId: NodeId) => [
      {
        id: nanoid(),
        nodeId,
        name: PLACEHOLDER_SLOT_DEFAULT,
        children: [],
      },
      {
        id: nanoid(),
        nodeId,
        name: PLACEHOLDER_SLOT_FILL,
        children: [],
      },
    ],

    examples: [
      {
        name: 'empty',
        description:
          'A placeholder with no default content. Both slots empty — the simplest authoring shape.',
        fragment: {
          rootNodeId: 'n-ph-empty',
          nodes: {
            'n-ph-empty': {
              id: 'n-ph-empty',
              type: 'placeholder',
              slots: ['s-ph-empty-default', 's-ph-empty-fill'],
              props: { name: 'body', description: '', kind: 'block' },
            },
          },
          slots: {
            's-ph-empty-default': {
              id: 's-ph-empty-default',
              nodeId: 'n-ph-empty',
              name: 'default',
              children: [],
            },
            's-ph-empty-fill': {
              id: 's-ph-empty-fill',
              nodeId: 'n-ph-empty',
              name: 'fill',
              children: [],
            },
          },
        },
      },
      {
        name: 'with-default',
        description:
          'A placeholder with a default text node in the `default` slot. Templates that do not fill the placeholder show this fallback. The `fill` slot starts empty.',
        fragment: {
          rootNodeId: 'n-ph-default',
          nodes: {
            'n-ph-default': {
              id: 'n-ph-default',
              type: 'placeholder',
              slots: ['s-ph-default-default', 's-ph-default-fill'],
              props: { name: 'body', description: 'Body content', kind: 'block' },
            },
            'n-ph-default-text': {
              id: 'n-ph-default-text',
              type: 'text',
              slots: [],
              props: {
                content: {
                  type: 'doc',
                  content: [
                    {
                      type: 'paragraph',
                      content: [{ type: 'text', text: 'Default body text' }],
                    },
                  ],
                },
              },
            },
          },
          slots: {
            's-ph-default-default': {
              id: 's-ph-default-default',
              nodeId: 'n-ph-default',
              name: 'default',
              children: ['n-ph-default-text'],
            },
            's-ph-default-fill': {
              id: 's-ph-default-fill',
              nodeId: 'n-ph-default',
              name: 'fill',
              children: [],
            },
          },
        },
      },
    ],

    renderCanvas: ({ node, doc, engine: eng, renderSlot }) => {
      const engine = eng as EditorEngine;
      const slotsById = doc.slots as Record<string, { name: string; children: NodeId[] }>;
      const defaultSlotId = node.slots.find((sid) => slotsById[sid as string]?.name === 'default');
      const fillSlotId = node.slots.find((sid) => slotsById[sid as string]?.name === 'fill');
      const defaultChildren = defaultSlotId
        ? (slotsById[defaultSlotId as string]?.children ?? [])
        : [];
      const fillChildren = fillSlotId ? (slotsById[fillSlotId as string]?.children ?? []) : [];

      const context = placeholderContext(doc, node.id, engine.indexes);

      // Stencil-author / draft mode: render the `default` slot for editing.
      // The fill slot is irrelevant in this context (filling is template-time).
      if (context === 'stencil-author') {
        return html`
          <div class="canvas-placeholder canvas-placeholder--default-edit">
            <div class="canvas-placeholder-default">
              ${defaultSlotId ? renderSlot(defaultSlotId) : nothing}
            </div>
          </div>
        `;
      }

      // Template-fill mode: render the `fill` slot when populated; when empty,
      // also render the `default` slot's children as a greyed-out, non-interactive
      // preview so the user can see what would render if they don't override.
      // If the default itself is empty, the preview wrapper is dropped — there's
      // nothing to preview and the drop zone alone tells the story.
      const showFill = fillChildren.length > 0;
      const hasDefaultPreview = !showFill && defaultChildren.length > 0;
      return html`
        <div
          class="canvas-placeholder ${showFill
            ? 'canvas-placeholder--filled'
            : 'canvas-placeholder--empty-fill'}"
        >
          ${showFill
            ? html`<div class="canvas-placeholder-fill">
                ${fillSlotId ? renderSlot(fillSlotId) : nothing}
              </div>`
            : html`
                ${hasDefaultPreview
                  ? html`<div
                      class="canvas-placeholder-default-preview"
                      aria-hidden="true"
                      title="Stencil default — read-only preview"
                    >
                      ${defaultSlotId ? renderSlot(defaultSlotId) : nothing}
                    </div>`
                  : nothing}
                <div class="canvas-placeholder-fill canvas-placeholder-fill--empty">
                  ${fillSlotId ? renderSlot(fillSlotId) : nothing}
                  <div class="canvas-placeholder-fill-hint">
                    Drop content here to override the default.
                  </div>
                </div>
              `}
        </div>
      `;
    },

    renderInspector: ({ node, engine: eng }) => {
      const engine = eng as EditorEngine;
      return html`<placeholder-inspector .node=${node} .engine=${engine}></placeholder-inspector>`;
    },
  };
}
