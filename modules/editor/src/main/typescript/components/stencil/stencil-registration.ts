// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Stencil component definition for the component registry.
 *
 * A stencil node is an instance of a stencil, used by reference (contract
 * ADR 0006): it names the stencil (`stencilKey`, and `catalogKey`) and holds
 * the template's parameter bindings and fills. The editor works on the
 * expanded instance, with the stencil's working copy inlined in the
 * `children` slot; that content is locked, except the `fill` slots of its
 * placeholders, and except while the author edits the stencil in place (see
 * `editing-state.ts`). The server stores the instance as a reference again.
 *
 * Stencils may contain stencils. Recursion is judged lexically: a stencil may
 * not contain itself, directly, through another stencil, or in a fill written
 * in its own content. The picker refuses such an insert before it happens.
 */

import type { ComponentDefinition } from '../../engine/registry.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import type { Node, NodeId, Slot, SlotId } from '../../types/index.js';
import type { StencilCallbacks, StencilRef } from './types.js';
import { openStencilPickerDialog, type PrepareStencilFn } from './stencil-picker-dialog.js';
import {
  expandInstance,
  fetchStencilTree,
  lexicalScopeAt,
  resolverOver,
  scopeRefs,
  type FetchedStencils,
  type InstanceFinding,
  type LexicalScope,
} from './instances.js';
import { buildParameterScope } from '../../engine/parameter-scope.js';
import { STENCIL_TYPE, STENCIL_SLOT_CHILDREN } from './constants.js';
import { isLinkedStencil, isStencil, stencilIdentity } from './node-types.js';
import { StencilEditingState } from './editing-state.js';
import './StencilInspector.js';
import { html } from 'lit';
import { MAX_STENCIL_NESTING_DEPTH } from '@epistola.app/epistola-catalog';

export interface StencilOptions {
  /** Null when stencil operations aren't wired — component still renders but browse is disabled. */
  callbacks: StencilCallbacks | null;
  /** Which instances are being edited; one per editor. Defaults to a fresh state. */
  editing?: StencilEditingState;
}

/** Transient insert props, read and removed by `createSubtree`. */
const INSERT_FETCHED = '_fetched';
const INSERT_SCOPE = '_scope';
const INSERT_EDITING = '_editing';

/** The sentence an author reads when a stencil cannot go where they put it. */
export function nestingRefusal(picked: StencilRef, finding: InstanceFinding): string {
  const name = `'${finding.ref.stencilKey}'`;
  switch (finding.problem) {
    case 'RECURSION':
      return finding.ref.stencilKey === picked.stencilKey &&
        finding.ref.catalogKey === picked.catalogKey
        ? `'${picked.stencilKey}' cannot go here: this is already the content of ${name}, and a stencil cannot contain itself.`
        : `'${picked.stencilKey}' cannot go here: it uses ${name}, which this is already the content of, and a stencil cannot contain itself.`;
    case 'DEPTH_EXCEEDED':
      return `'${picked.stencilKey}' cannot go here: stencils would be nested more than ${MAX_STENCIL_NESTING_DEPTH} deep.`;
    case 'STENCIL_NOT_FOUND':
      return finding.ref.stencilKey === picked.stencilKey
        ? `Stencil ${name} no longer exists.`
        : `'${picked.stencilKey}' uses stencil ${name}, which does not exist.`;
    default:
      return `'${picked.stencilKey}' cannot go here.`;
  }
}

/**
 * Fetch [ref] with everything it uses and check it may become content of
 * [scope]: no recursion, not too deep, every stencil found.
 */
export async function prepareStencilInsert(
  callbacks: StencilCallbacks,
  ref: StencilRef,
  scope: LexicalScope,
): ReturnType<PrepareStencilFn> {
  const fetched = await fetchStencilTree(ref, callbacks.getStencil);
  const copy = fetched.get(stencilIdentity(ref));
  if (!copy) return { ok: false, message: `Stencil '${ref.stencilKey}' no longer exists.` };
  const probe: Node = {
    id: '__probe__' as NodeId,
    type: STENCIL_TYPE,
    slots: [],
    props: { stencilKey: ref.stencilKey, catalogKey: ref.catalogKey },
  };
  const { findings } = expandInstance(
    probe,
    { nodes: {}, slots: {} },
    scope.catalogKey,
    resolverOver(fetched),
    scopeRefs(scope),
  );
  const blocking = findings.find((f) => f.problem !== 'UNKNOWN_PLACEHOLDER');
  if (blocking) return { ok: false, message: nestingRefusal(ref, blocking) };
  return { ok: true, prepared: { copy, fetched } };
}

export function createStencilDefinition(options: StencilOptions): ComponentDefinition {
  const editing = options.editing ?? new StencilEditingState();

  return {
    type: STENCIL_TYPE,
    label: 'Stencil',
    getLabel: (node) => {
      if (!isLinkedStencil(node)) return 'Stencil';
      const key = node.props.stencilKey!;
      return editing.isEditing(node.id) ? `${key} · editing` : key;
    },
    icon: 'puzzle',
    category: 'layout',
    slots: [
      {
        name: STENCIL_SLOT_CHILDREN,
        // An instance shows its stencil's working copy, so its content is
        // locked unless the author is editing the stencil. Inner placeholder
        // fill slots opt out via their own `editable: true`.
        locked: (node) => isLinkedStencil(node) && !editing.isEditing(node.id),
      },
    ],
    allowedChildren: { mode: 'denylist', types: ['root'] },
    applicableStyles: 'all',
    inspector: [],
    defaultProps: {
      stencilKey: null,
      catalogKey: null,
    },

    examples: [
      {
        name: 'with-placeholder',
        description:
          'A stencil instance (as it appears inside a template) containing one placeholder named "body". The placeholder owns two slots: `default` (set by the stencil author at publish time) and `fill` (the template override; empty here, so the renderer falls back to the default). Replacing or adding to `fill` overrides the default; clearing `fill` reverts to the default.',
        fragment: {
          rootNodeId: 'n-stencil-letter',
          nodes: {
            'n-stencil-letter': {
              id: 'n-stencil-letter',
              type: 'stencil',
              slots: ['s-stencil-letter-children'],
              props: {
                stencilKey: 'letter-shell',
                catalogKey: 'epistola-demo',
              },
            },
            'n-stencil-letter-ph': {
              id: 'n-stencil-letter-ph',
              type: 'placeholder',
              slots: ['s-stencil-letter-ph-default', 's-stencil-letter-ph-fill'],
              props: {
                name: 'body',
                description: 'Body content',
                kind: 'block',
              },
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
                      content: [
                        {
                          type: 'text',
                          text: 'Default body text',
                        },
                      ],
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
                stencilKey: 'letter-shell',
                catalogKey: 'epistola-demo',
              },
            },
            'n-stencil-letter-ov-ph': {
              id: 'n-stencil-letter-ov-ph',
              type: 'placeholder',
              slots: ['s-stencil-letter-ov-ph-default', 's-stencil-letter-ov-ph-fill'],
              props: {
                name: 'body',
                description: 'Body content',
                kind: 'block',
              },
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
                      content: [
                        {
                          type: 'text',
                          text: 'Default body text',
                        },
                      ],
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
                      content: [
                        {
                          type: 'text',
                          text: 'Custom body — overridden',
                        },
                      ],
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
      const isLocked = isLinkedStencil(node) && !editing.isEditing(node.id);

      // IMPORTANT: Template structure must be identical across all states.
      // Lit's template diffing caches by template string shape. The
      // locked/editable difference is CSS-only. State info (key, editing)
      // is in the block header via getLabel.
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
        .editing=${editing}
      ></stencil-inspector>`;
    },

    onBeforeInsert: async (engineUnknown, context) => {
      const callbacks = options.callbacks;
      // No callbacks — create an empty, unlinked stencil container.
      if (!callbacks) return {};

      const engine = engineUnknown as EditorEngine;
      // The palette inserts at the document root; elsewhere the target slot
      // says which stencils the new instance would be the content of.
      const targetSlotId = context?.targetSlotId;
      const scope: LexicalScope = targetSlotId
        ? lexicalScopeAt(engine.doc, targetSlotId, engine.indexes, callbacks.catalogKey)
        : { entries: [], catalogKey: callbacks.catalogKey, outer: null };
      if (scope.entries.length >= MAX_STENCIL_NESTING_DEPTH) return null;

      let fieldPaths: import('../../engine/schema-paths.js').FieldPath[] | undefined;
      let getExampleData: (() => Record<string, unknown> | undefined) | undefined;
      const parentNodeId = targetSlotId ? engine.doc.slots[targetSlotId]?.nodeId : undefined;
      if (parentNodeId) {
        fieldPaths = engine.getAvailableVariablesAt(parentNodeId);
        getExampleData = () => engine.getEvaluationContextAt(parentNodeId);
      }

      const result = await openStencilPickerDialog(callbacks, {
        prepare: (ref) => prepareStencilInsert(callbacks, ref, scope),
        disabledStencils: new Set(scope.entries.map((e) => stencilIdentity(e.ref))),
        fieldPaths,
        getExampleData,
        locale: engine.locale,
      });
      if (!result) return null; // Cancelled

      if (result.action === 'create-new') {
        // A new, empty stencil: insert it and start editing it in place.
        return {
          stencilKey: result.ref.stencilKey,
          catalogKey: result.ref.catalogKey,
          [INSERT_EDITING]: true,
        };
      }

      const overrides: Record<string, unknown> = {
        stencilKey: result.prepared.copy.ref.stencilKey,
        catalogKey: result.prepared.copy.ref.catalogKey,
        [INSERT_FETCHED]: result.prepared.fetched,
        [INSERT_SCOPE]: scope,
      };
      if (Object.keys(result.bindings).length > 0) overrides.parameterBindings = result.bindings;
      if (result.paramsAlias && result.paramsAlias !== 'params') {
        overrides.paramsAlias = result.paramsAlias;
      }
      return overrides;
    },

    createSubtree: (nodeId: NodeId, props?: Record<string, unknown>) => {
      const fetched = props?.[INSERT_FETCHED] as FetchedStencils | undefined;
      const scope = props?.[INSERT_SCOPE] as LexicalScope | undefined;
      const startEditing = props?.[INSERT_EDITING] === true;
      if (props) {
        delete props[INSERT_FETCHED];
        delete props[INSERT_SCOPE];
        delete props[INSERT_EDITING];
      }
      if (startEditing) editing.start(nodeId);

      const childrenSlotId = `${nodeId}:${STENCIL_SLOT_CHILDREN}` as SlotId;
      if (fetched && typeof props?.stencilKey === 'string' && props.stencilKey) {
        // Inline the stencil's working copy (and the stencils it uses) the way
        // the server expands a stored instance, so a save and reload give the same IDs.
        const expanded = expandInstance(
          { id: nodeId, type: STENCIL_TYPE, slots: [], props: { ...props } },
          { nodes: {}, slots: {} },
          scope?.catalogKey ?? options.callbacks?.catalogKey ?? '',
          resolverOver(fetched),
          scope ? scopeRefs(scope) : [],
        );
        Object.assign(props, expanded.node.props);
        return {
          slots: [expanded.childrenSlot],
          extraNodes: expanded.descendants,
          extraSlots: expanded.descendantSlots,
        };
      }

      return {
        slots: [
          {
            id: childrenSlotId,
            nodeId,
            name: STENCIL_SLOT_CHILDREN,
            children: [] as NodeId[],
          },
        ],
        extraNodes: [] as Node[],
        extraSlots: [] as Slot[],
      };
    },

    // Stencils carry their parameter schema on the expanded instance
    // (`parameterSchemaSnapshot`). The dynamic-function form lets
    // static-parametrised components reuse the same field by returning a constant.
    parameters: (node: Node) => {
      if (!isStencil(node)) return null;
      return node.props.parameterSchemaSnapshot ?? null;
    },

    scopeProvider: buildParameterScope,
  };
}
