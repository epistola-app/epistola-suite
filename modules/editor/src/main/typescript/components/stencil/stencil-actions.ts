// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Stencil action layer.
 *
 * Pure async functions behind the stencil inspector: start editing a stencil
 * in place, save its working copy, mark it ready, stop editing (back to the
 * saved working copy), refresh an instance, detach. Each takes a
 * {@link StencilActionContext}; UI state (busy spinner, messages) is the
 * inspector's concern.
 *
 * A template uses a stencil by reference (ADR 0006): the instance's content
 * is the stencil's working copy, inlined. So there is nothing to upgrade —
 * saving a stencil refreshes every instance of it in the document — and the
 * template's own part of an instance is its parameter bindings and its fills,
 * which survive every refresh.
 *
 * The engine's lock check rejects mutations of a locked stencil's content.
 * Replacing a whole instance passes `bypassLock: true` to engine.dispatch —
 * the documented escape hatch for exactly this case.
 */

import { nanoid } from 'nanoid';
import type { Node, NodeId, Slot, SlotId, TemplateDocument } from '../../types/index.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import type { StencilCallbacks, StencilRef, StencilWorkingCopy } from './types.js';
import type { StencilEditingState } from './editing-state.js';
import {
  PLACEHOLDER_PROP_FILL_PARAMETERS_AS,
  PLACEHOLDER_SLOT_FILL,
} from '../placeholder/constants.js';
import { isStencil, stencilIdentity, stencilRefOf } from './node-types.js';
import {
  collapse,
  expandInstance,
  fetchStencilTree,
  lexicalScopeAt,
  resolverOver,
  scopeRefs,
  topLevelPlaceholders,
  type FetchedStencils,
} from './instances.js';
import { missingRequiredParameters } from './parameter-requirements.js';

export interface StencilActionContext {
  engine: EditorEngine;
  callbacks: StencilCallbacks;
  editing: StencilEditingState;
  /** The stencil node we are operating on. Must be of `type === 'stencil'`. */
  stencilNodeId: NodeId;
}

// ---------------------------------------------------------------------------
// Public API — one function per high-level user action
// ---------------------------------------------------------------------------

/**
 * Start editing the stencil of this instance, in place. Shows the latest
 * working copy first (keeping the template's fills), then unlocks the
 * instance's content. Refuses a stencil of a read-only catalog.
 */
export async function startEditing(ctx: StencilActionContext): Promise<StencilWorkingCopy> {
  const ref = requireRef(ctx);
  const fetched = await fetchStencilTree(ref, ctx.callbacks.getStencil);
  const copy = fetched.get(stencilIdentity(ref));
  if (!copy) throw new Error(`Stencil '${ref.stencilKey}' no longer exists`);
  if (!copy.editable) {
    throw new Error(
      `Stencil '${ref.stencilKey}' belongs to the read-only catalog '${ref.catalogKey}'`,
    );
  }
  refreshInstance(ctx.engine, ctx.stencilNodeId, fetched, ctx.callbacks.catalogKey);
  ctx.editing.start(ctx.stencilNodeId, ctx.engine);
  return copy;
}

/**
 * Save the instance's content as the stencil's working copy, with the
 * parameters defined on the instance. The template's fills are not part of
 * the stencil and are left out; stencils nested in the content are saved by
 * reference. Every other instance of the stencil in the document is refreshed.
 */
export async function saveStencil(ctx: StencilActionContext): Promise<void> {
  const ref = requireRef(ctx);
  if (!ctx.callbacks.saveStencil) throw new Error('saveStencil callback is not configured');
  const node = stencilNode(ctx);
  const schema = isStencil(node) ? node.props.parameterSchemaSnapshot : undefined;
  await ctx.callbacks.saveStencil(ref, {
    content: extractStencilContent(ctx.engine.doc, ctx.stencilNodeId),
    parameterSchema: schema && Object.keys(schema.properties ?? {}).length > 0 ? schema : null,
  });
  await refreshOtherInstances(ctx, ref);
}

/**
 * Save the working copy, mark it ready and stop editing. Refuses while a
 * required parameter of this instance is unbound, since the stencil's own
 * check would then fail on the template that just wrote it.
 */
export async function saveAndMarkReady(ctx: StencilActionContext): Promise<void> {
  const ref = requireRef(ctx);
  if (!ctx.callbacks.markStencilReady) {
    throw new Error('markStencilReady callback is not configured');
  }
  const node = stencilNode(ctx);
  if (isStencil(node) && node.props.parameterSchemaSnapshot) {
    const missing = missingRequiredParameters(
      node.props.parameterSchemaSnapshot,
      node.props.parameterBindings ?? {},
    );
    if (missing.length > 0) {
      throw new Error(
        `Configure required ${missing.length === 1 ? 'parameter' : 'parameters'} first: ${missing.join(', ')}`,
      );
    }
  }
  await saveStencil(ctx);
  await ctx.callbacks.markStencilReady(ref);
  ctx.editing.stop(ctx.stencilNodeId, ctx.engine);
}

/**
 * Stop editing: show the stencil's saved working copy again, discarding what
 * was not saved, and lock the content. The template's fills are kept.
 */
export async function stopEditing(ctx: StencilActionContext): Promise<void> {
  const ref = requireRef(ctx);
  const fetched = await fetchStencilTree(ref, ctx.callbacks.getStencil);
  refreshInstance(ctx.engine, ctx.stencilNodeId, fetched, ctx.callbacks.catalogKey);
  ctx.editing.stop(ctx.stencilNodeId, ctx.engine);
}

/**
 * Convert this stencil into a regular `container`, severing the link to
 * the stencil. Children are preserved.
 */
export function detach(
  ctx: Pick<StencilActionContext, 'engine' | 'stencilNodeId'> & {
    editing?: StencilEditingState;
  },
): void {
  ctx.engine.dispatch({
    type: 'ReplaceNode',
    nodeId: ctx.stencilNodeId,
    newType: 'container',
    newProps: {},
  });
  ctx.editing?.stop(ctx.stencilNodeId, ctx.engine);
}

// ---------------------------------------------------------------------------
// Instances
// ---------------------------------------------------------------------------

/**
 * Re-expand instance [instanceId] from [fetched] (which must hold its stencil
 * and every stencil that one uses), keeping its bindings and fills.
 */
export function refreshInstance(
  engine: EditorEngine,
  instanceId: NodeId,
  fetched: FetchedStencils,
  documentCatalogKey: string,
): void {
  const doc = engine.doc;
  const node = doc.nodes[instanceId];
  if (!isStencil(node)) throw new Error(`Stencil node ${instanceId} not found`);
  const parentSlotId = engine.indexes.parentSlotByNodeId.get(instanceId);
  if (!parentSlotId) throw new Error(`Stencil node ${instanceId} has no parent`);
  const parentSlot = doc.slots[parentSlotId];
  const index = parentSlot.children.indexOf(instanceId);
  const scope = lexicalScopeAt(doc, parentSlotId, engine.indexes, documentCatalogKey);

  const expanded = expandInstance(
    node,
    { nodes: doc.nodes as Record<string, Node>, slots: doc.slots as Record<string, Slot> },
    scope.catalogKey,
    resolverOver(fetched),
    scopeRefs(scope),
  );

  const wasSelected = engine.selectedNodeId === instanceId;
  engine.dispatch({ type: 'RemoveNode', nodeId: instanceId }, { bypassLock: true });
  engine.dispatch(
    {
      type: 'InsertNode',
      node: expanded.node,
      slots: [expanded.childrenSlot, ...expanded.descendantSlots],
      targetSlotId: parentSlotId,
      index,
      _restoreNodes: expanded.descendants.length > 0 ? expanded.descendants : undefined,
    },
    { bypassLock: true },
  );
  if (wasSelected) engine.selectNode(instanceId);
}

/** Refresh every instance of [ref] in the document but the one being acted on. */
async function refreshOtherInstances(ctx: StencilActionContext, ref: StencilRef): Promise<void> {
  const others = Object.values(ctx.engine.doc.nodes).filter((n) => {
    if (n.id === ctx.stencilNodeId) return false;
    const r = stencilRefOf(n, ctx.callbacks.catalogKey);
    return r !== null && stencilIdentity(r) === stencilIdentity(ref);
  });
  if (others.length === 0) return;
  const fetched = await fetchStencilTree(ref, ctx.callbacks.getStencil);
  for (const other of others) {
    // An earlier refresh may have replaced an instance nested in this one.
    if (!ctx.engine.doc.nodes[other.id]) continue;
    refreshInstance(ctx.engine, other.id, fetched, ctx.callbacks.catalogKey);
  }
}

/**
 * The content of instance [instanceId] as its stencil's working copy, in
 * stored form: the instance's children under a new root, without the fills of
 * the stencil's own placeholders (those are the using document's), with
 * stencils nested in it stored by reference.
 */
export function extractStencilContent(doc: TemplateDocument, instanceId: NodeId): TemplateDocument {
  const instance = doc.nodes[instanceId];
  if (!instance) throw new Error(`Node ${instanceId} not found`);
  const childrenSlot = instance.slots.map((s) => doc.slots[s]).find((s) => s !== undefined);
  const rootChildren = childrenSlot ? [...childrenSlot.children] : [];

  const nodes: Record<string, Node> = {};
  const slots: Record<string, Slot> = {};
  const collect = (nodeId: string): void => {
    const node = doc.nodes[nodeId as NodeId];
    if (!node || nodes[nodeId]) return;
    nodes[nodeId] = node;
    for (const s of node.slots) {
      const slot = doc.slots[s];
      if (!slot) continue;
      slots[s] = slot;
      slot.children.forEach(collect);
    }
  };
  rootChildren.forEach(collect);

  // The stencil's own placeholders: their fills belong to the using document.
  for (const placeholder of topLevelPlaceholders(rootChildren, nodes, slots).values()) {
    for (const s of placeholder.slots) {
      const slot = slots[s];
      if (!slot || slot.name !== PLACEHOLDER_SLOT_FILL) continue;
      slot.children.forEach((c) => removeSubtree(c, nodes, slots));
      slots[s] = { ...slot, children: [] };
    }
    const props: Record<string, unknown> = { ...placeholder.props };
    delete props[PLACEHOLDER_PROP_FILL_PARAMETERS_AS];
    nodes[placeholder.id] = { ...placeholder, props };
  }

  // Inlining prefixed the stencil's own IDs with the instance's; give them back,
  // so the stencil keeps its IDs however often it is edited in place.
  const nodePrefix = `${instanceId}:`;
  const slotPrefix = `${instanceId}:s:`;
  const nodeId = (id: string) => (id.startsWith(nodePrefix) ? id.slice(nodePrefix.length) : id);
  const slotId = (id: string) => (id.startsWith(slotPrefix) ? id.slice(slotPrefix.length) : id);
  const outNodes: Record<string, Node> = {};
  for (const node of Object.values(nodes)) {
    outNodes[nodeId(node.id)] = {
      ...node,
      id: nodeId(node.id) as NodeId,
      slots: node.slots.map((s) => slotId(s) as SlotId),
    };
  }
  const outSlots: Record<string, Slot> = {};
  for (const slot of Object.values(slots)) {
    outSlots[slotId(slot.id)] = {
      ...slot,
      id: slotId(slot.id) as SlotId,
      nodeId: nodeId(slot.nodeId) as NodeId,
      children: slot.children.map((c) => nodeId(c) as NodeId),
    };
  }

  const rootId = nanoid() as NodeId;
  const rootSlotId = nanoid() as SlotId;
  outNodes[rootId] = { id: rootId, type: 'root', slots: [rootSlotId] };
  outSlots[rootSlotId] = {
    id: rootSlotId,
    nodeId: rootId,
    name: 'children',
    children: rootChildren.map((c) => nodeId(c) as NodeId),
  };
  return collapse({
    modelVersion: 1,
    root: rootId,
    nodes: outNodes as TemplateDocument['nodes'],
    slots: outSlots as TemplateDocument['slots'],
    themeRef: { type: 'inherit' },
  });
}

function removeSubtree(id: string, nodes: Record<string, Node>, slots: Record<string, Slot>) {
  const node = nodes[id];
  if (!node) return;
  delete nodes[id];
  for (const s of node.slots) {
    const slot = slots[s];
    delete slots[s];
    slot?.children.forEach((c) => removeSubtree(c, nodes, slots));
  }
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

function stencilNode(ctx: StencilActionContext): Node {
  const node = ctx.engine.doc.nodes[ctx.stencilNodeId];
  if (!node) throw new Error(`Stencil node ${ctx.stencilNodeId} not found`);
  return node;
}

/** The stencil this instance names. Null when unlinked. */
export function stencilRef(
  ctx: Pick<StencilActionContext, 'engine' | 'callbacks' | 'stencilNodeId'>,
): StencilRef | null {
  const doc = ctx.engine.doc;
  const parentSlotId = ctx.engine.indexes.parentSlotByNodeId.get(ctx.stencilNodeId);
  const contextCatalog = parentSlotId
    ? lexicalScopeAt(doc, parentSlotId, ctx.engine.indexes, ctx.callbacks.catalogKey).catalogKey
    : ctx.callbacks.catalogKey;
  return stencilRefOf(doc.nodes[ctx.stencilNodeId], contextCatalog);
}

function requireRef(ctx: StencilActionContext): StencilRef {
  const ref = stencilRef(ctx);
  if (!ref) throw new Error('This stencil block is not linked to a stencil');
  return ref;
}
