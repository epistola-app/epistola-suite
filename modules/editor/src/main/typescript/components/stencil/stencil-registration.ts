/**
 * Stencil component definition for the component registry.
 *
 * A stencil is a container-like node that wraps a copy of a published stencil
 * version's content. The node's props carry the stencil reference metadata
 * (stencilId + version) for tracking origin and detecting available upgrades.
 *
 * On the canvas, the stencil renders its children normally but with a visual
 * badge indicating the stencil name and version. When stencilId is null, the
 * stencil is "unpublished" — content lives only in the template until the user
 * explicitly publishes it.
 */

import type { ComponentDefinition } from '../../engine/registry.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import type { Node, Slot, NodeId, SlotId, TemplateDocument } from '../../types/index.js';
import type { StencilCallbacks } from './types.js';
import type { JsonSchema } from '../../data-contract/types.js';
import { openStencilPickerDialog } from './stencil-picker-dialog.js';
import { reKeyContent } from './rekey-content.js';
import { computeAncestorScope } from './ancestry.js';
import { buildParameterScope } from '../../engine/parameter-scope.js';
import {
  STENCIL_TYPE,
  STENCIL_SLOT_CHILDREN,
  STENCIL_PROP_PARAMETER_SCHEMA_SNAPSHOT,
} from './constants.js';
import { isPublishedStencil, isStencil } from './node-types.js';
import './StencilInspector.js';
import { nanoid } from 'nanoid';
import { html } from 'lit';

export interface StencilOptions {
  /** Null when stencil operations aren't wired — component still renders but browse is disabled. */
  callbacks: StencilCallbacks | null;
}

export function createStencilDefinition(options: StencilOptions): ComponentDefinition {
  return {
    type: STENCIL_TYPE,
    label: 'Stencil',
    getLabel: (node, _eng) => {
      if (!isStencil(node)) return 'Stencil';
      const { stencilId, version, isDraft } = node.props;

      if (!stencilId) return 'Stencil';

      if (isDraft) return stencilId;
      return `${stencilId} v${version}`;
    },
    icon: 'puzzle',
    category: 'layout',
    slots: [
      {
        name: STENCIL_SLOT_CHILDREN,
        // The stencil's children are frozen as soon as the stencil is
        // published (linked via `stencilId` and not in draft mode). Inner
        // placeholder fill slots opt out via their own `editable: true`.
        locked: (node) => isPublishedStencil(node),
      },
    ],
    allowedChildren: { mode: 'denylist', types: ['stencil'] },
    applicableStyles: 'all',
    inspector: [],
    defaultProps: {
      stencilId: null,
      catalogKey: null,
      version: null,
      isDraft: false,
    },

    examples: [
      {
        name: 'with-placeholder',
        description:
          'A published stencil instance (as it appears inside a template) containing one placeholder named "body". The placeholder owns two slots: `default` (set by the stencil author at publish time) and `fill` (the template override; empty here, so the renderer falls back to the default). Replacing or adding to `fill` overrides the default; clearing `fill` reverts to the default.',
        fragment: {
          rootNodeId: 'n-stencil-letter',
          nodes: {
            'n-stencil-letter': {
              id: 'n-stencil-letter',
              type: 'stencil',
              slots: ['s-stencil-letter-children'],
              props: {
                stencilId: 'letter-shell',
                catalogKey: 'epistola-demo',
                version: 1,
                isDraft: false,
              },
            },
            'n-stencil-letter-ph': {
              id: 'n-stencil-letter-ph',
              type: 'placeholder',
              slots: ['s-stencil-letter-ph-default', 's-stencil-letter-ph-fill'],
              props: { name: 'body', description: 'Body content', kind: 'block' },
            },
            'n-stencil-letter-default-text': {
              id: 'n-stencil-letter-default-text',
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
            's-stencil-letter-children': {
              id: 's-stencil-letter-children',
              nodeId: 'n-stencil-letter',
              name: 'children',
              children: ['n-stencil-letter-ph'],
            },
            's-stencil-letter-ph-default': {
              id: 's-stencil-letter-ph-default',
              nodeId: 'n-stencil-letter-ph',
              name: 'default',
              children: ['n-stencil-letter-default-text'],
            },
            's-stencil-letter-ph-fill': {
              id: 's-stencil-letter-ph-fill',
              nodeId: 'n-stencil-letter-ph',
              name: 'fill',
              children: [],
            },
          },
        },
      },
      {
        name: 'with-overridden-placeholder',
        description:
          'Same stencil as `with-placeholder`, but the template has overridden the placeholder by populating its `fill` slot. The renderer prefers `fill` over `default` when fill is non-empty, so this stencil instance renders "Custom body — overridden" instead of the default. Clearing `fill` would revert to the default.',
        fragment: {
          rootNodeId: 'n-stencil-letter-ov',
          nodes: {
            'n-stencil-letter-ov': {
              id: 'n-stencil-letter-ov',
              type: 'stencil',
              slots: ['s-stencil-letter-ov-children'],
              props: {
                stencilId: 'letter-shell',
                catalogKey: 'epistola-demo',
                version: 1,
                isDraft: false,
              },
            },
            'n-stencil-letter-ov-ph': {
              id: 'n-stencil-letter-ov-ph',
              type: 'placeholder',
              slots: ['s-stencil-letter-ov-ph-default', 's-stencil-letter-ov-ph-fill'],
              props: { name: 'body', description: 'Body content', kind: 'block' },
            },
            'n-stencil-letter-ov-default-text': {
              id: 'n-stencil-letter-ov-default-text',
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
            'n-stencil-letter-ov-fill-text': {
              id: 'n-stencil-letter-ov-fill-text',
              type: 'text',
              slots: [],
              props: {
                content: {
                  type: 'doc',
                  content: [
                    {
                      type: 'paragraph',
                      content: [{ type: 'text', text: 'Custom body — overridden' }],
                    },
                  ],
                },
              },
            },
          },
          slots: {
            's-stencil-letter-ov-children': {
              id: 's-stencil-letter-ov-children',
              nodeId: 'n-stencil-letter-ov',
              name: 'children',
              children: ['n-stencil-letter-ov-ph'],
            },
            's-stencil-letter-ov-ph-default': {
              id: 's-stencil-letter-ov-ph-default',
              nodeId: 'n-stencil-letter-ov-ph',
              name: 'default',
              children: ['n-stencil-letter-ov-default-text'],
            },
            's-stencil-letter-ov-ph-fill': {
              id: 's-stencil-letter-ov-ph-fill',
              nodeId: 'n-stencil-letter-ov-ph',
              name: 'fill',
              children: ['n-stencil-letter-ov-fill-text'],
            },
          },
        },
      },
    ],

    renderCanvas: ({ node, renderSlot }) => {
      const isLocked = isPublishedStencil(node);

      // IMPORTANT: Template structure must be identical across all states.
      // Lit's template diffing caches by template string shape. The
      // locked/editable difference is CSS-only. State info (name, version,
      // upgrade indicator) is in the block header via getLabel.
      return html`
        <div class=${isLocked ? 'canvas-stencil-locked' : 'canvas-stencil-content'}>
          ${node.slots.map((slotId) => renderSlot(slotId))}
        </div>
      `;
    },

    renderInspector: ({ node, engine: eng }) => {
      const engine = eng as EditorEngine;
      return html`<stencil-inspector
        .node=${node}
        .engine=${engine}
        .callbacks=${options.callbacks}
      ></stencil-inspector>`;
    },

    onBeforeInsert: async (engineUnknown, context) => {
      if (!options.callbacks) {
        // No callbacks — create empty stencil container
        return {};
      }

      // If we know the target slot, compute the ancestor stencil set so the
      // picker can disable recursive choices.
      let disabledStencilIds: Set<string> | undefined;
      const targetSlotId = context?.targetSlotId;
      if (targetSlotId) {
        const engine = engineUnknown as EditorEngine;
        const scope = computeAncestorScope(engine.doc, targetSlotId, engine.indexes);
        if (scope.stencilIds.size > 0) disabledStencilIds = scope.stencilIds;
      }

      const result = await openStencilPickerDialog(options.callbacks, { disabledStencilIds });
      if (!result) return null; // Cancelled

      if (result.action === 'create-new') {
        // Stencil entity created on backend — insert with stencilId in draft mode
        return {
          stencilId: result.ref.stencilId,
          catalogKey: result.ref.catalogKey,
          version: result.version,
          isDraft: true,
        };
      }

      // Use existing stencil — store content temporarily for createSubtree.
      // parameterSchemaSnapshot, paramsAlias, and parameterBindings (if any)
      // come straight from the picker; they're persisted on the stencil node
      // and read by ParameterScope at render time.
      const overrides: Record<string, unknown> = {
        stencilId: result.versionInfo.ref.stencilId,
        catalogKey: result.versionInfo.ref.catalogKey,
        version: result.versionInfo.version,
        _content: result.versionInfo.content,
      };
      if (result.versionInfo.parameterSchema) {
        overrides[STENCIL_PROP_PARAMETER_SCHEMA_SNAPSHOT] = result.versionInfo.parameterSchema;
      }
      if (result.bindings && Object.keys(result.bindings).length > 0) {
        overrides.parameterBindings = result.bindings;
      }
      if (result.paramsAlias && result.paramsAlias !== 'params') {
        overrides.paramsAlias = result.paramsAlias;
      }
      return overrides;
    },

    createSubtree: (nodeId: NodeId, props?: Record<string, unknown>) => {
      const content = props?._content as TemplateDocument | undefined;

      // Remove transient _content from persisted props
      if (props) {
        delete props._content;
      }

      if (content) {
        // Deep-copy the stencil version's content with fresh IDs
        const reKeyed = reKeyContent(content);

        const slotId = nanoid() as SlotId;
        const parentSlot: Slot = {
          id: slotId,
          nodeId,
          name: 'children',
          children: reKeyed.childNodeIds,
        };

        return {
          slots: [parentSlot],
          extraNodes: reKeyed.nodes,
          extraSlots: reKeyed.slots,
        };
      }

      // Empty stencil — just create an empty slot
      const slotId = nanoid() as SlotId;
      return {
        slots: [
          {
            id: slotId,
            nodeId,
            name: 'children',
            children: [] as NodeId[],
          },
        ],
        extraNodes: [] as Node[],
        extraSlots: [] as Slot[],
      };
    },

    // Stencils carry their parameter schema as a per-instance snapshot prop
    // (`parameterSchemaSnapshot`), populated by the picker / upgrade replacer.
    // The dynamic-function form lets static-parametrised components reuse the
    // same field in the future by returning a constant.
    parameters: (node: Node) => {
      if (!isStencil(node)) return null;
      const snapshot = node.props.parameterSchemaSnapshot as JsonSchema | undefined;
      return snapshot ?? null;
    },

    scopeProvider: buildParameterScope,
  };
}
