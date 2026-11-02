// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Shared test helpers for the stencil + placeholder test suites: a compact
 * document builder, stencil working copies, mock callbacks and an engine.
 */

import { vi } from 'vitest';
import { EditorEngine } from '../../engine/EditorEngine.js';
import { createDefaultRegistry, type ComponentRegistry } from '../../engine/registry.js';
import { createStencilDefinition, prepareStencilInsert } from './stencil-registration.js';
import { StencilEditingState } from './editing-state.js';
import { lexicalScopeAt } from './instances.js';
import type { Node, NodeId, Slot, SlotId, TemplateDocument } from '../../types/index.js';
import type { JsonSchema } from '../../data-contract/types.js';
import type { StencilCallbacks, StencilRef, StencilWorkingCopy } from './types.js';
import { stencilIdentity } from './node-types.js';

export const CATALOG = 'cat';

export function richText(text: string) {
  return {
    type: 'doc',
    content: [{ type: 'paragraph', content: [{ type: 'text', text }] }],
  };
}

export function richTextValue(value: unknown): string | undefined {
  if (typeof value !== 'object' || value === null) return undefined;
  const content = (value as { content?: unknown }).content;
  if (!Array.isArray(content)) return undefined;
  const paragraph = content[0];
  if (typeof paragraph !== 'object' || paragraph === null) return undefined;
  const inline = (paragraph as { content?: unknown }).content;
  if (!Array.isArray(inline)) return undefined;
  const text = inline[0];
  return typeof text === 'object' && text !== null ? (text as { text?: string }).text : undefined;
}

// ---------------------------------------------------------------------------
// Compact document builder. Slot IDs are `{nodeId}:{slotName}`.
// ---------------------------------------------------------------------------

export interface Spec {
  id: string;
  type: string;
  props?: Record<string, unknown>;
  slots?: Record<string, Spec[]>;
}

export const text = (id: string, value = id): Spec => ({
  id,
  type: 'text',
  props: { content: richText(value) },
});

export const container = (id: string, children: Spec[] = []): Spec => ({
  id,
  type: 'container',
  slots: { children },
});

/** A stencil's own placeholder (both slots). */
export const placeholder = (
  id: string,
  name: string,
  slots: { default?: Spec[]; fill?: Spec[] } = {},
  props: Record<string, unknown> = {},
): Spec => ({
  id,
  type: 'placeholder',
  props: { name, ...props },
  slots: { default: slots.default ?? [], fill: slots.fill ?? [] },
});

/** A stored instance: a reference with one fill carrier per fill. */
export const storedInstance = (
  id: string,
  stencilKey: string,
  fills: Record<string, Spec[]> = {},
  props: Record<string, unknown> = {},
): Spec => ({
  id,
  type: 'stencil',
  props: { stencilKey, catalogKey: CATALOG, ...props },
  slots: {
    children: Object.entries(fills).map(([name, children]) => ({
      id: `${id}:fill:${name}`,
      type: 'placeholder',
      props: { name },
      slots: { fill: children },
    })),
  },
});

export function buildDocument(children: Spec[]): TemplateDocument {
  const nodes: Record<string, Node> = {};
  const slots: Record<string, Slot> = {};
  const add = (spec: Spec): NodeId => {
    const slotIds = Object.entries(spec.slots ?? {}).map(([name, kids]) => {
      const sid = `${spec.id}:${name}` as SlotId;
      slots[sid] = { id: sid, nodeId: spec.id as NodeId, name, children: kids.map(add) };
      return sid;
    });
    nodes[spec.id] = {
      id: spec.id as NodeId,
      type: spec.type,
      slots: slotIds,
      ...(spec.props ? { props: spec.props } : {}),
    };
    return spec.id as NodeId;
  };
  add({ id: 'root', type: 'root', slots: { children } });
  return {
    modelVersion: 1,
    root: 'root' as NodeId,
    nodes: nodes as TemplateDocument['nodes'],
    slots: slots as TemplateDocument['slots'],
    themeRef: { type: 'inherit' },
  };
}

/** A stencil's working copy whose content is [children]. */
export function workingCopy(
  stencilKey: string,
  children: Spec[],
  options: { parameterSchema?: JsonSchema; readsTemplateData?: boolean; editable?: boolean } = {},
): StencilWorkingCopy {
  return {
    ref: { stencilKey, catalogKey: CATALOG },
    name: stencilKey,
    editable: options.editable ?? true,
    content: buildDocument(children),
    parameterSchema: options.parameterSchema,
    readsTemplateData: options.readsTemplateData ?? false,
  };
}

/** Mock callbacks serving [copies] (mutable: tests may replace a stencil's copy). */
export function createMockCallbacks(
  copies: StencilWorkingCopy[] = [],
  overrides?: Partial<StencilCallbacks>,
): StencilCallbacks & { copies: Map<string, StencilWorkingCopy> } {
  const map = new Map(copies.map((c) => [stencilIdentity(c.ref), c]));
  return {
    copies: map,
    catalogKey: CATALOG,
    searchStencils: vi
      .fn()
      .mockResolvedValue(copies.map((c) => ({ ...c.ref, name: c.name, tags: [] }))),
    getStencil: vi.fn(async (ref: StencilRef) =>
      structuredClone(map.get(stencilIdentity(ref)) ?? null),
    ),
    createStencil: vi.fn(async (stencilKey: string) => ({ stencilKey, catalogKey: CATALOG })),
    saveStencil: vi.fn().mockResolvedValue(undefined),
    markStencilReady: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}

export function setupEngine(callbacks: StencilCallbacks | null, document?: TemplateDocument) {
  const registry = createDefaultRegistry();
  const editing = new StencilEditingState();
  registry.register(createStencilDefinition({ callbacks, editing }));
  const doc = document ?? buildDocument([]);
  const engine = new EditorEngine(doc, registry);
  const rootSlotId = engine.doc.nodes[engine.doc.root].slots[0];
  return { engine, registry, rootSlotId, editing };
}

/**
 * Insert an instance of [ref] into [targetSlotId] the way the picker does:
 * fetch the stencil tree, check it may go there, expand it. Throws the
 * refusal when it may not.
 */
export async function insertInstance(
  engine: EditorEngine,
  callbacks: StencilCallbacks,
  targetSlotId: SlotId,
  stencilKey: string,
  extraProps: Record<string, unknown> = {},
): Promise<NodeId> {
  const scope = lexicalScopeAt(engine.doc, targetSlotId, engine.indexes, callbacks.catalogKey);
  const outcome = await prepareStencilInsert(callbacks, { stencilKey, catalogKey: CATALOG }, scope);
  if (!outcome.ok) throw new Error(outcome.message);
  const { node, slots, extraNodes } = engine.registry.createNode('stencil', {
    stencilKey,
    catalogKey: CATALOG,
    _fetched: outcome.prepared.fetched,
    _scope: scope,
    ...extraProps,
  });
  const result = engine.dispatch({
    type: 'InsertNode',
    node,
    slots,
    targetSlotId,
    index: -1,
    _restoreNodes: extraNodes,
  });
  if (!result.ok) throw new Error(result.error);
  return node.id;
}

export function insertText(
  engine: EditorEngine,
  registry: ComponentRegistry,
  targetSlotId: SlotId,
  content?: string,
): NodeId {
  const { node, slots } = registry.createNode(
    'text',
    content ? { content: richText(content) } : undefined,
  );
  const result = engine.dispatch({ type: 'InsertNode', node, slots, targetSlotId, index: -1 });
  if (!result.ok) throw new Error(result.error);
  return node.id;
}

/** The children of [slotId] (by slot name of node [nodeId] when a name is given). */
export function childrenOf(engine: EditorEngine, nodeId: string, slotName: string): NodeId[] {
  const node = engine.doc.nodes[nodeId as NodeId];
  const slot = node?.slots.map((s) => engine.doc.slots[s]).find((s) => s?.name === slotName);
  return slot ? [...slot.children] : [];
}

/** The text of a text node. */
export function textOf(
  engine: EditorEngine | TemplateDocument,
  nodeId: string,
): string | undefined {
  const doc = 'doc' in engine ? engine.doc : engine;
  return richTextValue(doc.nodes[nodeId as NodeId]?.props?.content);
}
