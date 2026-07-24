// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Stencil action layer.
 *
 * Pure async functions that orchestrate the high-level stencil operations
 * (Start Editing, Save Draft, Publish, Discard, Upgrade, Detach,
 * Load Draft Version). Each takes a {@link StencilActionContext} and returns
 * a result; UI state (busy spinner, success/error message) is the inspector's
 * concern, not theirs.
 *
 * Why this lives outside `StencilInspector.ts`:
 *
 * - Testable without rendering Lit components.
 * - Reusable from non-UI callers (keyboard shortcuts, command palette,
 *   future MCP write tools, programmatic flows).
 * - No long-lived state that depends on a Lit instance surviving — every
 *   capture/restore pattern is local to a single function call.
 * - Domain helpers (`extractSubtree`, `reKeyContent`, `captureFillsByName`,
 *   …) stay out of the UI layer.
 *
 * The engine's lock check rejects mutations targeting a published stencil's
 * children. Whole-stencil swap operations (Discard, Upgrade) pass
 * `bypassLock: true` to engine.dispatch — that is the documented escape
 * hatch for exactly this case.
 */

import type { TemplateDocument, NodeId, SlotId, Node, Slot } from '../../types/index.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import type { StencilCallbacks, StencilRef, StencilVersionInfo } from './types.js';
import { extractSubtree } from './extract-subtree.js';
import { reKeyContent } from './rekey-content.js';
import { captureFillsByName, reKeyCapturedFill, type CapturedFill } from './preserve-fills.js';
import { PLACEHOLDER_SLOT_FILL } from '../placeholder/constants.js';
import { isPlaceholder, placeholderName } from '../placeholder/node-types.js';
import { isStencil } from './node-types.js';

export interface StencilActionContext {
  engine: EditorEngine;
  callbacks: StencilCallbacks;
  /** The stencil node we are operating on. Must be of `type === 'stencil'`. */
  stencilNodeId: NodeId;
}

// ---------------------------------------------------------------------------
// Public API — one function per high-level user action
// ---------------------------------------------------------------------------

/**
 * Start editing a stencil instance. Creates (or fetches) the backend draft
 * and flips the local stencil into draft mode.
 *
 * The local doc's content (including any template-fill overrides) is left
 * untouched — the canvas changes presentation via `placeholderContext`,
 * not via a content swap. Fills are restored automatically when the
 * stencil exits draft mode (Publish / Discard).
 */
export async function startEditing(ctx: StencilActionContext): Promise<{ draftVersion: number }> {
  const ref = requireRef(ctx);
  if (!ctx.callbacks.startEditing) {
    throw new Error('startEditing callback is not configured');
  }
  const result = await ctx.callbacks.startEditing(ref);
  ctx.engine.dispatch({
    type: 'UpdateNodeProps',
    nodeId: ctx.stencilNodeId,
    props: { ...stencilNode(ctx).props, isDraft: true },
  });
  return result;
}

/**
 * Persist the current local stencil content to the backend draft.
 * Fill slots are stripped on extract — template overrides never round-trip
 * into the stencil definition. The current `parameterSchemaSnapshot` prop
 * (edited inline by the author via `StencilParameterDefinitionsPanel`) is
 * forwarded so the schema persists alongside the content.
 */
export async function saveDraft(ctx: StencilActionContext): Promise<{ version: number }> {
  const ref = requireRef(ctx);
  if (!ctx.callbacks.updateStencil) {
    throw new Error('updateStencil callback is not configured');
  }
  const content = extractSubtree(ctx.engine.doc, ctx.stencilNodeId);
  const schema = stencilParameterSchema(ctx);
  return ctx.callbacks.updateStencil(ref, content, schema);
}

/**
 * Save the current draft and publish it. Flips the local stencil to the
 * new published version and out of draft mode. Does NOT swap local content
 * — the local fill content is untouched, so the canvas re-renders in
 * template-fill mode and shows the user's existing override.
 */
export async function publishDraft(
  ctx: StencilActionContext,
  draftVersion: number,
): Promise<{ version: number }> {
  const ref = requireRef(ctx);
  if (!ctx.callbacks.publishDraft) {
    throw new Error('publishDraft callback is not configured');
  }
  if (ctx.callbacks.updateStencil) {
    const content = extractSubtree(ctx.engine.doc, ctx.stencilNodeId);
    const schema = stencilParameterSchema(ctx);
    await ctx.callbacks.updateStencil(ref, content, schema);
  }
  const result = await ctx.callbacks.publishDraft(ref, draftVersion);
  ctx.engine.dispatch({
    type: 'UpdateNodeProps',
    nodeId: ctx.stencilNodeId,
    props: {
      ...stencilNode(ctx).props,
      version: result.version,
      isDraft: false,
    },
  });
  return result;
}

/**
 * Discard the local draft and revert to the published version. Captures
 * the user's template overrides (fill content) before the swap and
 * splices them back by placeholder name afterwards, so Discard reverts
 * the user's stencil-edits but keeps their template-side overrides.
 */
export async function discard(ctx: StencilActionContext, publishedVersion: number): Promise<void> {
  const ref = requireRef(ctx);
  if (!ctx.callbacks.getStencilVersion) {
    throw new Error('getStencilVersion callback is not configured');
  }
  const versionInfo = await ctx.callbacks.getStencilVersion(ref, publishedVersion);
  if (!versionInfo) {
    throw new Error('Could not load published version');
  }
  const captured = captureFillsByName(
    extractSubtree(ctx.engine.doc, ctx.stencilNodeId, { keepFills: true }),
  );
  replaceContent(ctx, versionInfo.content);
  applyCapturedFills(ctx, captured);
  ctx.engine.dispatch({
    type: 'UpdateNodeProps',
    nodeId: ctx.stencilNodeId,
    props: { ...stencilNode(ctx).props, isDraft: false },
  });
}

/**
 * Upgrade a stencil instance to a newer published version. Captures the
 * user's overrides, swaps the inlined content with the new version, and
 * re-applies the overrides by placeholder name. Mirrors the server-side
 * `UpdateStencilInTemplate` behaviour.
 */
export async function upgrade(
  ctx: StencilActionContext,
  toVersion: number,
): Promise<{ version: number }> {
  const ref = requireRef(ctx);
  if (!ctx.callbacks.getStencilVersion) {
    throw new Error('getStencilVersion callback is not configured');
  }
  const versionInfo = await ctx.callbacks.getStencilVersion(ref, toVersion);
  if (!versionInfo) {
    throw new Error('Could not load the new version');
  }
  const captured = captureFillsByName(
    extractSubtree(ctx.engine.doc, ctx.stencilNodeId, { keepFills: true }),
  );
  replaceContent(ctx, versionInfo.content);
  applyCapturedFills(ctx, captured);
  ctx.engine.dispatch({
    type: 'UpdateNodeProps',
    nodeId: ctx.stencilNodeId,
    props: { ...stencilNode(ctx).props, version: toVersion },
  });
  return { version: versionInfo.version };
}

/**
 * Convert this stencil into a regular `container`, severing the link to
 * the stencil definition. Children are preserved.
 */
export function detach(ctx: StencilActionContext): void {
  ctx.engine.dispatch({
    type: 'ReplaceNode',
    nodeId: ctx.stencilNodeId,
    newType: 'container',
    newProps: {},
  });
}

/**
 * Resolve the current backend draft version by querying `listVersions`
 * and returning the entry with `status === 'draft'`. Returns null when no
 * draft exists or the callbacks aren't configured.
 *
 * Used when an inspector mounts on a stencil that's already in draft mode
 * but doesn't know which backend draft it corresponds to (e.g. user
 * reloaded mid-edit, or selected the stencil after a previous Start
 * Editing in another inspector instance).
 */
export async function loadDraftVersion(ctx: StencilActionContext): Promise<number | null> {
  const ref = stencilRef(ctx);
  if (!ref || !ctx.callbacks.listVersions) return null;
  try {
    const versions = await ctx.callbacks.listVersions(ref);
    return versions.find((v) => v.status === 'draft')?.version ?? null;
  } catch {
    return null;
  }
}

/**
 * Latest published version available for this stencil's `stencilId`,
 * or null when none / unable to resolve. Used to detect upgrades.
 */
export async function findLatestPublishedVersion(
  ctx: StencilActionContext,
): Promise<number | null> {
  const ref = stencilRef(ctx);
  if (!ref || !ctx.callbacks.listVersions) return null;
  try {
    const versions = await ctx.callbacks.listVersions(ref);
    const published = versions
      .filter((v) => v.status === 'published')
      .toSorted((a, b) => b.version - a.version);
    return published[0]?.version ?? null;
  } catch {
    return null;
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

/** Build a StencilRef from the node's props. Returns null when unlinked. */
export function stencilRef(ctx: StencilActionContext): StencilRef | null {
  const node = ctx.engine.doc.nodes[ctx.stencilNodeId];
  if (!isStencil(node)) return null;
  const { stencilId: id, catalogKey } = node.props;
  return id && catalogKey ? { stencilId: id, catalogKey } : null;
}

/**
 * Read the parameter-schema snapshot from the stencil node's props. Returned
 * straight from the prop — no shape validation here; the backend's
 * ParameterSchemaValidator is the source of truth.
 */
function stencilParameterSchema(
  ctx: StencilActionContext,
): import('../../data-contract/types.js').JsonSchema | undefined {
  const node = ctx.engine.doc.nodes[ctx.stencilNodeId];
  if (!isStencil(node)) return undefined;
  return node.props.parameterSchemaSnapshot;
}

function requireRef(ctx: StencilActionContext): StencilRef {
  const ref = stencilRef(ctx);
  if (!ref) throw new Error('Stencil is not linked to a published definition');
  return ref;
}

/**
 * Replace the stencil's slot children with new content. Mutations bypass
 * the engine's slot-lock check because this is a deliberate whole-stencil
 * swap (the lock exists to prevent *user-driven* mutations).
 */
function replaceContent(ctx: StencilActionContext, content: TemplateDocument): void {
  const node = stencilNode(ctx);
  const slotId = node.slots[0];
  if (!slotId) return;

  // Remove existing children (re-read doc after each removal since it's immutable)
  while (true) {
    const currentSlot = ctx.engine.doc.slots[slotId];
    if (!currentSlot || currentSlot.children.length === 0) break;
    ctx.engine.dispatch(
      { type: 'RemoveNode', nodeId: currentSlot.children[0] },
      { bypassLock: true },
    );
  }

  // Re-key the new content and insert each top-level node
  const reKeyed = reKeyContent(content);
  const nodeById = new Map(reKeyed.nodes.map((n) => [n.id as string, n]));
  const slotById = new Map(reKeyed.slots.map((s) => [s.id as string, s]));

  for (const childId of reKeyed.childNodeIds) {
    const childNode = nodeById.get(childId as string);
    if (!childNode) continue;

    const descNodes: Node[] = [];
    const descSlots: Slot[] = [];

    function collectDescendants(nodeId: NodeId) {
      const n = nodeById.get(nodeId as string);
      if (!n) return;
      for (const sid of n.slots) {
        const slot = slotById.get(sid as string);
        if (slot) {
          descSlots.push(slot);
          for (const cid of slot.children) {
            const child = nodeById.get(cid as string);
            if (child) {
              descNodes.push(child);
              collectDescendants(cid);
            }
          }
        }
      }
    }

    collectDescendants(childId);

    const ownSlots = childNode.slots
      .map((sid) => slotById.get(sid as string))
      .filter(Boolean) as Slot[];

    ctx.engine.dispatch(
      {
        type: 'InsertNode',
        node: childNode,
        slots: [...ownSlots, ...descSlots],
        targetSlotId: slotId,
        index: -1,
        _restoreNodes: descNodes.length > 0 ? descNodes : undefined,
      },
      { bypassLock: true },
    );
  }
}

/**
 * Splice captured fills (template overrides) into the matching placeholders
 * by name. Each splice re-keys with fresh IDs and dispatches one InsertNode
 * per top-level fill child. The fill slot is `editable: true` so the
 * splices succeed without a bypass.
 */
function applyCapturedFills(ctx: StencilActionContext, captured: Map<string, CapturedFill>): void {
  if (captured.size === 0) return;
  const doc = ctx.engine.doc;
  const stencilSlotId = stencilNode(ctx).slots[0];
  if (!stencilSlotId) return;

  const stack: NodeId[] = [...(doc.slots[stencilSlotId]?.children ?? [])];
  const visited = new Set<NodeId>();
  while (stack.length > 0) {
    const nodeId = stack.shift()!;
    if (visited.has(nodeId)) continue;
    visited.add(nodeId);
    const node = doc.nodes[nodeId];
    if (!node) continue;

    if (isPlaceholder(node)) {
      const name = placeholderName(node);
      if (name && captured.has(name)) {
        const fillSlotId = node.slots.find((sid) => doc.slots[sid]?.name === PLACEHOLDER_SLOT_FILL);
        if (fillSlotId) spliceFillIntoSlot(ctx, fillSlotId, captured.get(name)!);
      }
      // Don't descend into placeholder slots — overrides are one-level only.
      continue;
    }

    for (const sid of node.slots) {
      const slot = doc.slots[sid];
      if (slot) for (const childId of slot.children) stack.push(childId);
    }
  }
}

function spliceFillIntoSlot(
  ctx: StencilActionContext,
  fillSlotId: SlotId,
  capture: CapturedFill,
): void {
  const reKeyed = reKeyCapturedFill(capture);
  const nodeById = new Map(reKeyed.nodes.map((n) => [n.id as string, n]));
  const slotById = new Map(reKeyed.slots.map((s) => [s.id as string, s]));

  for (const rootId of reKeyed.rootChildIds) {
    const topNode = nodeById.get(rootId as string);
    if (!topNode) continue;

    const ownSlots: Slot[] = [];
    const descNodes: Node[] = [];
    const descSlots: Slot[] = [];
    const seen = new Set<string>();
    const collect = (id: string, isRoot: boolean) => {
      if (seen.has(id)) return;
      seen.add(id);
      const n = nodeById.get(id);
      if (!n) return;
      if (!isRoot) descNodes.push(n);
      for (const sid of n.slots) {
        const slot = slotById.get(sid as string);
        if (!slot) continue;
        if (isRoot) ownSlots.push(slot);
        else descSlots.push(slot);
        for (const c of slot.children) collect(c as string, false);
      }
    };
    collect(rootId as string, true);

    ctx.engine.dispatch({
      type: 'InsertNode',
      node: topNode,
      slots: [...ownSlots, ...descSlots],
      targetSlotId: fillSlotId,
      index: -1,
      _restoreNodes: descNodes.length > 0 ? descNodes : undefined,
    });
  }
}

// Re-export so consumers can import the type from one place.
export type { StencilVersionInfo };
