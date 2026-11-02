// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Stencil instances by reference (contract ADR 0006), on the editor's side.
 *
 * A template stores an instance as a reference: the stencil node names the
 * stencil and holds, in its `children` slot, one placeholder per fill. The
 * editor edits the **expanded** form, with the stencil's content inlined and
 * each fill in its placeholder. The server expands a template when it hands
 * it to the editor and collapses it again when it stores it; the editor
 * expands what it inserts itself (a picked stencil, a refreshed one) and
 * collapses the content it saves to a stencil.
 *
 * This is a port of `StencilInstances.kt` (epistola-core) and must produce
 * the same IDs: expanded nodes `{instanceId}:{nodeId}`, expanded slots
 * `{instanceId}:s:{slotId}`, a stored fill `{instanceId}:fill:{name}` with
 * slot `{instanceId}:fill:{name}:fill`, the children slot
 * `{instanceId}:children`. Fill content keeps its own IDs.
 *
 * Scope is lexical: a placeholder's fill is written outside the stencil that
 * owns the placeholder. {@link lexicalScopeAt} and {@link checkStencilNesting}
 * follow the contract's `TemplateValidator` for recursion and depth.
 */

import { MAX_STENCIL_NESTING_DEPTH } from '@epistola.app/epistola-catalog';
import type { Node, NodeId, Slot, SlotId, TemplateDocument } from '../../types/index.js';
import type { DocumentIndexes } from '../../engine/indexes.js';
import type { JsonSchema } from '../../data-contract/types.js';
import {
  PLACEHOLDER_PROP_FILL_PARAMETERS_AS,
  PLACEHOLDER_PROP_NAME,
  PLACEHOLDER_PROP_PARAMETERS_AS,
  PLACEHOLDER_SLOT_DEFAULT,
  PLACEHOLDER_SLOT_FILL,
  PLACEHOLDER_TYPE,
} from '../placeholder/constants.js';
import {
  STENCIL_PROP_PARAMETER_SCHEMA_SNAPSHOT,
  STENCIL_PROP_READS_TEMPLATE_DATA,
  STENCIL_PROP_STENCIL_KEY,
  STENCIL_SLOT_CHILDREN,
  STENCIL_TRANSIENT_PROPS,
  STENCIL_TYPE,
} from './constants.js';
import { stencilIdentity, stencilRefOf } from './node-types.js';
import type { GetStencilFn, StencilRef, StencilWorkingCopy } from './types.js';

type Nodes = Record<string, Node>;
type Slots = Record<string, Slot>;

/** A stencil's content, as an instance resolves it. */
export interface ResolvedStencil {
  document: TemplateDocument;
  parameterSchema?: JsonSchema | null;
  readsTemplateData?: boolean;
}

/** Resolves a stencil synchronously (from stencils fetched beforehand), or null when there is none. */
export type StencilResolver = (ref: StencilRef) => ResolvedStencil | null | undefined;

export type InstanceProblem =
  | 'STENCIL_NOT_FOUND'
  | 'RECURSION'
  | 'DEPTH_EXCEEDED'
  | 'UNKNOWN_PLACEHOLDER';

/** Where a document no longer fits a stencil it uses. */
export interface InstanceFinding {
  instanceNodeId: string;
  ref: StencilRef;
  problem: InstanceProblem;
  /** The placeholder, for UNKNOWN_PLACEHOLDER. */
  name?: string;
}

export interface Expanded {
  document: TemplateDocument;
  findings: InstanceFinding[];
}

// ---------------------------------------------------------------------------
// Collapse
// ---------------------------------------------------------------------------

/**
 * The stored form of [document]: every stencil instance reachable from the
 * root keeps only its reference, its bindings and its non-empty fills;
 * instances inside a fill are collapsed too. Idempotent.
 */
export function collapse(document: TemplateDocument): TemplateDocument {
  if (!Object.values(document.nodes).some((n) => n.type === STENCIL_TYPE)) return document;
  const nodes: Nodes = { ...(document.nodes as Nodes) };
  const slots: Slots = { ...(document.slots as Slots) };

  const walk = (nodeId: string): void => {
    const node = nodes[nodeId];
    if (!node) return;
    if (node.type !== STENCIL_TYPE) {
      for (const slot of slotsOf(node, slots)) slot.children.forEach(walk);
      return;
    }
    // Template content inside a fill may hold instances of its own.
    for (const fill of collapseInstance(node, nodes, slots)) fill.children.forEach(walk);
  };
  walk(document.root);
  return {
    ...document,
    nodes: nodes as TemplateDocument['nodes'],
    slots: slots as TemplateDocument['slots'],
  };
}

function collapseInstance(instance: Node, nodes: Nodes, slots: Slots): Fill[] {
  const childrenSlot = slotsOf(instance, slots).find((s) => s.name === STENCIL_SLOT_CHILDREN);
  const fills = childrenSlot
    ? fillsByName(childrenSlot.children, nodes, slots)
    : new Map<string, Fill>();
  const names = [...fills.keys()].toSorted();

  // Drop the inlined content, keeping each fill's subtree as it is.
  const keep = new Set([...fills.values()].flatMap((f) => f.children));
  childrenSlot?.children.forEach((id) => removeSubtree(id, nodes, slots, keep));
  for (const slotId of instance.slots) {
    if (slotId === childrenSlot?.id) continue;
    const removed = slots[slotId];
    delete slots[slotId];
    removed?.children.forEach((id) => removeSubtree(id, nodes, slots, keep));
  }

  const placeholderIds = names.map((name) => {
    const fill = fills.get(name)!;
    const placeholderId = `${instance.id}:fill:${name}`;
    const fillSlotId = `${placeholderId}:${PLACEHOLDER_SLOT_FILL}`;
    const props: Record<string, unknown> = { [PLACEHOLDER_PROP_NAME]: name };
    if (fill.parametersAs) props[PLACEHOLDER_PROP_PARAMETERS_AS] = fill.parametersAs;
    nodes[placeholderId] = {
      id: placeholderId as NodeId,
      type: PLACEHOLDER_TYPE,
      slots: [fillSlotId as SlotId],
      props,
    };
    slots[fillSlotId] = {
      id: fillSlotId as SlotId,
      nodeId: placeholderId as NodeId,
      name: PLACEHOLDER_SLOT_FILL,
      children: fill.children as NodeId[],
    };
    return placeholderId as NodeId;
  });
  const childrenSlotId = `${instance.id}:${STENCIL_SLOT_CHILDREN}`;
  if (childrenSlot) delete slots[childrenSlot.id];
  slots[childrenSlotId] = {
    id: childrenSlotId as SlotId,
    nodeId: instance.id,
    name: STENCIL_SLOT_CHILDREN,
    children: placeholderIds,
  };
  const props = Object.fromEntries(
    Object.entries(instance.props ?? {}).filter(([k]) => !STENCIL_TRANSIENT_PROPS.includes(k)),
  );
  nodes[instance.id] = {
    ...instance,
    slots: [childrenSlotId as SlotId],
    props: Object.keys(props).length > 0 ? props : undefined,
  };
  return names.map((n) => fills.get(n)!);
}

// ---------------------------------------------------------------------------
// Expand
// ---------------------------------------------------------------------------

/**
 * [document], written in catalog [catalogKey], with every stencil instance's
 * content inlined from [resolver], and every way it no longer fits the
 * stencils it uses. An instance whose stencil cannot be inlined keeps its
 * fills and shows nothing of the stencil.
 *
 * [outerPath] is the stencils the document itself is lexically inside (when
 * it is a fragment about to be placed into a stencil's content): an instance
 * reaching one of them is a RECURSION, and they count towards the depth.
 */
export function expand(
  document: TemplateDocument,
  catalogKey: string,
  resolver: StencilResolver,
  outerPath: readonly StencilRef[] = [],
): Expanded {
  const stored = collapse(document);
  if (!Object.values(stored.nodes).some((n) => n.type === STENCIL_TYPE)) {
    return { document: stored, findings: [] };
  }
  const nodes: Nodes = { ...(stored.nodes as Nodes) };
  const slots: Slots = { ...(stored.slots as Slots) };
  const findings: InstanceFinding[] = [];
  // A fill is expanded in its own context before it moves into the inlined
  // stencil; walking the stencil afterwards reaches it again and must leave it alone.
  const expanded = new Set<string>();

  const walk = (nodeId: string, contextCatalog: string, path: readonly StencilRef[]): void => {
    const node = nodes[nodeId];
    if (!node) return;
    if (node.type !== STENCIL_TYPE) {
      for (const slot of slotsOf(node, slots)) {
        slot.children.forEach((c) => walk(c, contextCatalog, path));
      }
      return;
    }
    if (expanded.has(node.id)) return;
    expanded.add(node.id);
    const props = node.props ?? {};
    const key = props[STENCIL_PROP_STENCIL_KEY];
    if (typeof key !== 'string' || !key) return;
    const ref = stencilRefOf(node, contextCatalog)!;
    const finding = (problem: InstanceProblem, name?: string): InstanceFinding => ({
      instanceNodeId: node.id,
      ref,
      problem,
      ...(name !== undefined ? { name } : {}),
    });

    const childrenSlotId = node.slots[0];
    if (!childrenSlotId || !slots[childrenSlotId]) return;
    const fills = fillsByName(slots[childrenSlotId].children, nodes, slots);
    // Fills are the document's content, in the context it is in: expand instances there first.
    for (const fill of fills.values()) fill.children.forEach((c) => walk(c, contextCatalog, path));

    let content: ResolvedStencil | null | undefined = null;
    if (path.some((p) => stencilIdentity(p) === stencilIdentity(ref))) {
      findings.push(finding('RECURSION'));
    } else if (path.length >= MAX_STENCIL_NESTING_DEPTH) {
      findings.push(finding('DEPTH_EXCEEDED'));
    } else {
      content = resolver(ref);
      if (!content) findings.push(finding('STENCIL_NOT_FOUND'));
    }
    if (!content) return;

    const inlined = inline(node.id, content.document);
    for (const placeholderId of slots[childrenSlotId].children) {
      const removed = nodes[placeholderId];
      delete nodes[placeholderId];
      removed?.slots.forEach((s) => delete slots[s]);
    }
    Object.assign(nodes, inlined.nodes);
    Object.assign(slots, inlined.slots);
    slots[childrenSlotId] = {
      ...slots[childrenSlotId],
      children: inlined.rootChildren as NodeId[],
    };

    // Each fill goes into the outermost placeholder of its name; inner stencils'
    // placeholders are the outer stencil's to fill, not the document's.
    const placeholders = topLevelPlaceholders(inlined.rootChildren, nodes, slots);
    for (const [name, placeholder] of placeholders) {
      const fill = fills.get(name);
      if (!fill) continue;
      const fillSlot = slotsOf(placeholder, slots).find((s) => s.name === PLACEHOLDER_SLOT_FILL);
      if (!fillSlot) continue;
      fillSlot.children.forEach((c) => removeSubtree(c, nodes, slots, new Set()));
      slots[fillSlot.id] = { ...fillSlot, children: fill.children as NodeId[] };
      if (fill.parametersAs) {
        nodes[placeholder.id] = {
          ...placeholder,
          props: {
            ...placeholder.props,
            [PLACEHOLDER_PROP_FILL_PARAMETERS_AS]: fill.parametersAs,
          },
        };
      }
    }
    for (const name of [...fills.keys()].filter((n) => !placeholders.has(n)).toSorted()) {
      findings.push(finding('UNKNOWN_PLACEHOLDER', name));
      // An unknown fill is not shown.
      fills.get(name)!.children.forEach((c) => removeSubtree(c, nodes, slots, new Set()));
    }
    const expandedProps: Record<string, unknown> = { ...props };
    if (content.parameterSchema)
      expandedProps[STENCIL_PROP_PARAMETER_SCHEMA_SNAPSHOT] = content.parameterSchema;
    if (content.readsTemplateData) expandedProps[STENCIL_PROP_READS_TEMPLATE_DATA] = true;
    nodes[node.id] = { ...node, props: expandedProps };

    // The stencil's own instances resolve in the stencil's catalog, and are its to fit.
    inlined.rootChildren.forEach((c) => walk(c, ref.catalogKey, [...path, ref]));
  };
  walk(stored.root, catalogKey, outerPath);
  return {
    document: {
      ...stored,
      nodes: nodes as TemplateDocument['nodes'],
      slots: slots as TemplateDocument['slots'],
    },
    findings,
  };
}

/** A fill: the top-level nodes of a placeholder's `fill` slot, and the name the fill gives passed parameters. */
interface Fill {
  children: string[];
  parametersAs?: string;
}

/** Non-empty fills of the outermost placeholders under [roots], by name; the first of a name wins. */
function fillsByName(roots: readonly string[], nodes: Nodes, slots: Slots): Map<string, Fill> {
  const result = new Map<string, Fill>();
  for (const [name, node] of topLevelPlaceholders(roots, nodes, slots)) {
    const fillSlot = slotsOf(node, slots).find((s) => s.name === PLACEHOLDER_SLOT_FILL);
    if (!fillSlot || fillSlot.children.length === 0) continue;
    const parametersAs = usingSideName(node, slots);
    result.set(name, {
      children: [...fillSlot.children],
      ...(parametersAs ? { parametersAs } : {}),
    });
  }
  return result;
}

/**
 * The name the using document gave passed parameters: `fillParametersAs`
 * while expanded, `parametersAs` on a stored fill (which has no `default`
 * slot — on a stencil's own placeholder `parametersAs` is the author's default).
 */
function usingSideName(placeholder: Node, slots: Slots): string | undefined {
  const props = placeholder.props ?? {};
  const expandedName = props[PLACEHOLDER_PROP_FILL_PARAMETERS_AS];
  if (typeof expandedName === 'string') return expandedName;
  const isStoredFill = !slotsOf(placeholder, slots).some(
    (s) => s.name === PLACEHOLDER_SLOT_DEFAULT,
  );
  const stored = props[PLACEHOLDER_PROP_PARAMETERS_AS];
  return isStoredFill && typeof stored === 'string' ? stored : undefined;
}

/**
 * The placeholders under [roots] that belong to this level, by name (first
 * wins): not inside another placeholder. A nested stencil instance's own
 * placeholders are its stencil's, but the fills the instance carries are this
 * level's content, so they are searched.
 */
export function topLevelPlaceholders(
  roots: readonly string[],
  nodes: Nodes,
  slots: Slots,
): Map<string, Node> {
  const result = new Map<string, Node>();
  const visit = (nodeId: string): void => {
    const node = nodes[nodeId];
    if (!node) return;
    if (node.type === PLACEHOLDER_TYPE) {
      const name = node.props?.[PLACEHOLDER_PROP_NAME];
      if (typeof name === 'string' && !result.has(name)) result.set(name, node);
      return;
    }
    if (node.type === STENCIL_TYPE) {
      for (const carrierId of slotsOf(node, slots).flatMap((s) => s.children)) {
        const carrier = nodes[carrierId];
        if (!carrier) continue;
        slotsOf(carrier, slots)
          .filter((s) => s.name === PLACEHOLDER_SLOT_FILL)
          .flatMap((s) => s.children)
          .forEach(visit);
      }
      return;
    }
    for (const slot of slotsOf(node, slots)) slot.children.forEach(visit);
  };
  roots.forEach(visit);
  return result;
}

interface Inlined {
  rootChildren: string[];
  nodes: Nodes;
  slots: Slots;
}

/** The stencil's content below its root, re-keyed under [instanceId]. */
function inline(instanceId: string, content: TemplateDocument): Inlined {
  const nodeId = (id: string) => `${instanceId}:${id}`;
  const slotId = (id: string) => `${instanceId}:s:${id}`;
  const rootSlots = new Set<string>(content.nodes[content.root]?.slots ?? []);
  const nodes: Nodes = {};
  for (const node of Object.values(content.nodes)) {
    if (node.id === content.root) continue;
    nodes[nodeId(node.id)] = {
      ...node,
      id: nodeId(node.id) as NodeId,
      slots: node.slots.map((s) => slotId(s) as SlotId),
    };
  }
  const slots: Slots = {};
  for (const slot of Object.values(content.slots)) {
    if (rootSlots.has(slot.id)) continue;
    slots[slotId(slot.id)] = {
      ...slot,
      id: slotId(slot.id) as SlotId,
      nodeId: nodeId(slot.nodeId) as NodeId,
      children: slot.children.map((c) => nodeId(c) as NodeId),
    };
  }
  const rootChildren = [...rootSlots].flatMap(
    (s) => (content.slots as Slots)[s]?.children.map((c) => nodeId(c)) ?? [],
  );
  return { rootChildren, nodes, slots };
}

function removeSubtree(id: string, nodes: Nodes, slots: Slots, keep: Set<string>): void {
  if (keep.has(id)) return;
  const node = nodes[id];
  if (!node) return;
  delete nodes[id];
  for (const slotId of node.slots) {
    const slot = slots[slotId];
    delete slots[slotId];
    slot?.children.forEach((c) => removeSubtree(c, nodes, slots, keep));
  }
}

function slotsOf(node: Node, slots: Slots): Slot[] {
  return node.slots.map((s) => slots[s]).filter((s): s is Slot => s !== undefined);
}

// ---------------------------------------------------------------------------
// Expanding one instance (insert, refresh)
// ---------------------------------------------------------------------------

/** One instance, expanded: its children slot, and the nodes and slots below it. */
export interface ExpandedInstance {
  node: Node;
  childrenSlot: Slot;
  /** Every node below the instance (inlined content and fills). */
  descendants: Node[];
  /** Every slot below the instance, without the instance's own children slot. */
  descendantSlots: Slot[];
  findings: InstanceFinding[];
}

/**
 * Expand a single instance [instance] whose subtree is in [source] (its stored
 * or expanded form), written in [contextCatalog], lexically inside [outerPath].
 */
export function expandInstance(
  instance: Node,
  source: { nodes: Nodes; slots: Slots },
  contextCatalog: string,
  resolver: StencilResolver,
  outerPath: readonly StencilRef[] = [],
): ExpandedInstance {
  const rootId = '__instance-root__';
  const rootSlotId = '__instance-root-children__';
  const nodes: Nodes = {
    [rootId]: { id: rootId as NodeId, type: 'root', slots: [rootSlotId as SlotId] },
  };
  const slots: Slots = {
    [rootSlotId]: {
      id: rootSlotId as SlotId,
      nodeId: rootId as NodeId,
      name: 'children',
      children: [instance.id],
    },
  };
  const collect = (nodeId: string): void => {
    const node = nodeId === instance.id ? instance : source.nodes[nodeId];
    if (!node || nodes[nodeId]) return;
    nodes[nodeId] = node;
    for (const s of node.slots) {
      const slot = source.slots[s];
      if (!slot) continue;
      slots[s] = slot;
      slot.children.forEach(collect);
    }
  };
  collect(instance.id);
  if (instance.slots.length === 0 || !slots[instance.slots[0]]) {
    const childrenSlotId = `${instance.id}:${STENCIL_SLOT_CHILDREN}`;
    nodes[instance.id] = { ...instance, slots: [childrenSlotId as SlotId] };
    slots[childrenSlotId] = {
      id: childrenSlotId as SlotId,
      nodeId: instance.id,
      name: STENCIL_SLOT_CHILDREN,
      children: [],
    };
  }
  const doc: TemplateDocument = {
    modelVersion: 1,
    root: rootId as NodeId,
    nodes: nodes as TemplateDocument['nodes'],
    slots: slots as TemplateDocument['slots'],
    themeRef: { type: 'inherit' },
  };
  const result = expand(doc, contextCatalog, resolver, outerPath);
  const outNodes = result.document.nodes as Nodes;
  const outSlots = result.document.slots as Slots;
  const node = outNodes[instance.id];
  const childrenSlot = outSlots[node.slots[0]];
  const descendants: Node[] = [];
  const descendantSlots: Slot[] = [];
  const gather = (nodeId: string): void => {
    const n = outNodes[nodeId];
    if (!n) return;
    descendants.push(n);
    for (const s of n.slots) {
      const slot = outSlots[s];
      if (!slot) continue;
      descendantSlots.push(slot);
      slot.children.forEach(gather);
    }
  };
  childrenSlot.children.forEach(gather);
  return { node, childrenSlot, descendants, descendantSlots, findings: result.findings };
}

// ---------------------------------------------------------------------------
// Fetching what an instance needs
// ---------------------------------------------------------------------------

/** Stencils fetched for expanding, by {@link stencilIdentity}; null when there is no such stencil. */
export type FetchedStencils = Map<string, StencilWorkingCopy | null>;

/**
 * Fetch [root] and every stencil its content uses, directly or further down,
 * up to the nesting limit. Each stencil is fetched once.
 */
export async function fetchStencilTree(
  root: StencilRef,
  getStencil: GetStencilFn,
  fetched: FetchedStencils = new Map(),
): Promise<FetchedStencils> {
  let level: StencilRef[] = [root];
  for (let depth = 0; depth <= MAX_STENCIL_NESTING_DEPTH && level.length > 0; depth++) {
    const pending = level.filter((ref) => !fetched.has(stencilIdentity(ref)));
    const unique = [...new Map(pending.map((r) => [stencilIdentity(r), r])).values()];
    const copies = await Promise.all(unique.map((ref) => getStencil(ref).catch(() => null)));
    unique.forEach((ref, i) => fetched.set(stencilIdentity(ref), copies[i] ?? null));
    level = unique.flatMap((ref) => {
      const copy = fetched.get(stencilIdentity(ref));
      return copy ? referencedStencils(copy.content, ref.catalogKey) : [];
    });
  }
  return fetched;
}

/** A resolver over stencils fetched with {@link fetchStencilTree}. */
export function resolverOver(fetched: FetchedStencils): StencilResolver {
  return (ref) => {
    const copy = fetched.get(stencilIdentity(ref));
    return copy
      ? {
          document: copy.content,
          parameterSchema: copy.parameterSchema ?? null,
          readsTemplateData: copy.readsTemplateData,
        }
      : null;
  };
}

/** Every stencil an instance in [document] names, a catalog-less reference resolved to [catalogKey]. */
export function referencedStencils(document: TemplateDocument, catalogKey: string): StencilRef[] {
  return Object.values(document.nodes)
    .map((n) => stencilRefOf(n, catalogKey))
    .filter((r): r is StencilRef => r !== null);
}

// ---------------------------------------------------------------------------
// Lexical scope: which stencils a place is inside
// ---------------------------------------------------------------------------

/** One stencil a place is lexically inside: the instance, and the stencil it names. */
export interface ScopeEntry {
  instanceNodeId: NodeId;
  ref: StencilRef;
}

/**
 * The stencils a place is lexically inside, outermost first (ADR 0006): the
 * stencils whose content it is. A placeholder's fill is written outside the
 * stencil that owns the placeholder, so it is in that stencil's outer scope.
 */
export interface LexicalScope {
  entries: readonly ScopeEntry[];
  /** The catalog a reference without one means here. */
  catalogKey: string;
  outer: LexicalScope | null;
}

function enter(scope: LexicalScope, node: Node, slotName: string): LexicalScope {
  if (node.type === PLACEHOLDER_TYPE && slotName === PLACEHOLDER_SLOT_FILL) {
    return scope.outer ?? scope;
  }
  const ref = stencilRefOf(node, scope.catalogKey);
  if (ref) {
    return {
      entries: [...scope.entries, { instanceNodeId: node.id, ref }],
      catalogKey: ref.catalogKey,
      outer: scope,
    };
  }
  return scope;
}

/**
 * The lexical scope of content placed into [slotId]: the stencils it would
 * be the content of. [documentCatalogKey] is the catalog of the document.
 */
export function lexicalScopeAt(
  doc: TemplateDocument,
  slotId: SlotId,
  indexes: DocumentIndexes,
  documentCatalogKey = '',
): LexicalScope {
  // The chain of (node, slot) from the root down to the slot.
  const chain: { node: Node; slotName: string }[] = [];
  let currentSlot: Slot | undefined = doc.slots[slotId];
  const visited = new Set<string>();
  while (currentSlot && !visited.has(currentSlot.id)) {
    visited.add(currentSlot.id);
    const node: Node | undefined = doc.nodes[currentSlot.nodeId];
    if (!node) break;
    chain.unshift({ node, slotName: currentSlot.name });
    const parentSlotId = indexes.parentSlotByNodeId.get(node.id);
    currentSlot = parentSlotId ? doc.slots[parentSlotId] : undefined;
  }
  let scope: LexicalScope = { entries: [], catalogKey: documentCatalogKey, outer: null };
  for (const { node, slotName } of chain) scope = enter(scope, node, slotName);
  return scope;
}

/** The stencil instance whose content [nodeId] lexically is, or undefined for template content. */
export function lexicalOwnerOf(
  doc: TemplateDocument,
  nodeId: NodeId,
  indexes: DocumentIndexes,
): NodeId | undefined {
  const parentSlotId = indexes.parentSlotByNodeId.get(nodeId);
  if (!parentSlotId) return undefined;
  const entries = lexicalScopeAt(doc, parentSlotId, indexes).entries;
  return entries[entries.length - 1]?.instanceNodeId;
}

/** Why a subtree cannot be placed where it would go. */
export interface NestingProblem {
  problem: 'RECURSION' | 'DEPTH_EXCEEDED';
  /** The instance that would recurse or nest too deep. */
  instanceNodeId: NodeId;
  ref: StencilRef;
}

/**
 * Check the subtree at [rootNodeId] as content of [scope], as the contract's
 * validator does: an instance of a stencil the place is already inside is
 * recursion, and more than {@link MAX_STENCIL_NESTING_DEPTH} stencils in one
 * lexical chain is too deep. Returns the first problem, or null.
 */
export function checkStencilNesting(
  nodes: Record<string, Node>,
  slots: Record<string, Slot>,
  rootNodeId: string,
  scope: LexicalScope,
): NestingProblem | null {
  const visit = (
    nodeId: string,
    current: LexicalScope,
    seen: Set<string>,
  ): NestingProblem | null => {
    if (seen.has(nodeId)) return null;
    seen.add(nodeId);
    const node = nodes[nodeId];
    if (!node) return null;
    const ref = stencilRefOf(node, current.catalogKey);
    if (ref) {
      if (current.entries.some((e) => stencilIdentity(e.ref) === stencilIdentity(ref))) {
        return { problem: 'RECURSION', instanceNodeId: node.id, ref };
      }
      if (current.entries.length + 1 > MAX_STENCIL_NESTING_DEPTH) {
        return { problem: 'DEPTH_EXCEEDED', instanceNodeId: node.id, ref };
      }
    }
    for (const slotId of node.slots) {
      const slot = slots[slotId];
      if (!slot) continue;
      const inner = enter(current, node, slot.name);
      for (const child of slot.children) {
        const problem = visit(child, inner, seen);
        if (problem) return problem;
      }
    }
    return null;
  };
  return visit(rootNodeId, scope, new Set());
}

/** The stencil refs of a scope, outermost first. */
export function scopeRefs(scope: LexicalScope): StencilRef[] {
  return scope.entries.map((e) => e.ref);
}
