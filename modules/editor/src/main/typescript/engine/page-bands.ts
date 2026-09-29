/**
 * Page headers and footers placed anywhere in the flow (#1020).
 *
 * Page breaks divide the document into sections. A header applies to what comes after it: at
 * the start of a section it applies from that page, after content from the next page. A footer
 * applies to what comes before it: it covers its section and the footer-less sections above it.
 * Several of either at the start of / within a section form a first-page variant, in flow order.
 * The renderer (`PageBandSchedule` in modules/generation) is the authority; this module mirrors
 * it statically for the editor, which cannot evaluate conditionals or loops.
 */

import type { Node, NodeId, SlotId, TemplateDocument } from '../types/index.js';
import type { DocumentIndexes } from './indexes.js';

export const PAGE_HEADER_TYPE = 'pageheader';
export const PAGE_FOOTER_TYPE = 'pagefooter';
export const PAGE_BREAK_TYPE = 'pagebreak';

/** Whether a component type is a page band: a header or a footer. */
export function isPageBand(type: string): boolean {
  return type === PAGE_HEADER_TYPE || type === PAGE_FOOTER_TYPE;
}

export const NESTED_PAGE_BAND_ERROR =
  'A page header or footer cannot be placed inside another page header or footer';

/**
 * Node types that only arrange other nodes, or are page furniture, so they do not end the start
 * of a section. Mirrors `NON_CONTENT_TYPES` in the renderer's `NodeRendererRegistry`.
 */
const NON_CONTENT_TYPES = new Set([
  'root',
  'container',
  'stencil',
  'placeholder',
  'conditional',
  'loop',
  'columns',
  'table',
  PAGE_BREAK_TYPE,
  PAGE_HEADER_TYPE,
  PAGE_FOOTER_TYPE,
  'addressblock',
]);

/** Types whose content renders only for some data, so a band inside them may not apply. */
const DATA_DEPENDENT_TYPES = new Set(['conditional', 'loop', 'datalist', 'datatable']);

function childrenOf(doc: TemplateDocument, node: Node): NodeId[] {
  return node.slots.flatMap((slotId) => doc.slots[slotId]?.children ?? []);
}

/** The node owning [slotId] and its ancestors, nearest first. */
function slotOwnerChain(doc: TemplateDocument, indexes: DocumentIndexes, slotId: SlotId): Node[] {
  const chain: Node[] = [];
  let current: NodeId | undefined = doc.slots[slotId]?.nodeId;
  while (current !== undefined) {
    const node = doc.nodes[current];
    if (!node) break;
    chain.push(node);
    current = indexes.parentNodeByNodeId.get(current);
  }
  return chain;
}

/** Whether a slot sits inside a page header or footer (or belongs to one). */
export function isInsidePageBand(
  doc: TemplateDocument,
  indexes: DocumentIndexes,
  slotId: SlotId,
): boolean {
  return slotOwnerChain(doc, indexes, slotId).some((node) => isPageBand(node.type));
}

/**
 * Error for placing [subtree] (a node and everything inside it, with the slots they own) into
 * [targetSlotId], or null when it is allowed. A header or footer may not end up inside another,
 * whether the target is inside one or the subtree itself nests them.
 */
export function pageBandNestingError(
  doc: TemplateDocument,
  indexes: DocumentIndexes,
  targetSlotId: SlotId,
  subtree: {
    nodes: readonly Node[];
    slots: Readonly<Record<SlotId, { children: readonly NodeId[] }>>;
  },
): string | null {
  const bandsInSubtree = subtree.nodes.filter((node) => isPageBand(node.type));
  if (bandsInSubtree.length === 0) return null;
  if (isInsidePageBand(doc, indexes, targetSlotId)) return NESTED_PAGE_BAND_ERROR;

  // Within the subtree: a band with a band below it.
  const byId = new Map(subtree.nodes.map((node) => [node.id, node]));
  const hasBandBelow = (node: Node): boolean =>
    node.slots.some((slotId) =>
      (subtree.slots[slotId]?.children ?? []).some((childId) => {
        const child = byId.get(childId);
        return child !== undefined && (isPageBand(child.type) || hasBandBelow(child));
      }),
    );
  return bandsInSubtree.some(hasBandBelow) ? NESTED_PAGE_BAND_ERROR : null;
}

/** A node and all its descendants in [doc], with the slots they own. */
export function subtreeOf(
  doc: TemplateDocument,
  nodeId: NodeId,
): { root: Node; nodes: Node[]; slots: TemplateDocument['slots'] } | null {
  const root = doc.nodes[nodeId];
  if (!root) return null;
  const nodes: Node[] = [];
  const slots: TemplateDocument['slots'] = {};
  const visit = (node: Node) => {
    nodes.push(node);
    for (const slotId of node.slots) {
      const slot = doc.slots[slotId];
      if (!slot) continue;
      slots[slotId] = slot;
      for (const childId of slot.children) {
        const child = doc.nodes[childId];
        if (child) visit(child);
      }
    }
  };
  visit(root);
  return { root, nodes, slots };
}

/**
 * Where body content goes in the root slot: after the headers that open the document and before
 * the footers that close it, so appending never lands content in the wrong band role.
 */
export function rootBodyBounds(
  doc: TemplateDocument,
): { slotId: SlotId; startIndex: number; endIndex: number } | null {
  const rootNode = doc.nodes[doc.root];
  const slotId = rootNode?.slots[0];
  const rootSlot = slotId ? doc.slots[slotId] : undefined;
  if (!slotId || !rootSlot) return null;

  const children = rootSlot.children;
  let startIndex = 0;
  while (
    startIndex < children.length &&
    doc.nodes[children[startIndex]]?.type === PAGE_HEADER_TYPE
  ) {
    startIndex += 1;
  }
  let endIndex = children.length;
  while (endIndex > startIndex && doc.nodes[children[endIndex - 1]]?.type === PAGE_FOOTER_TYPE) {
    endIndex -= 1;
  }
  return { slotId, startIndex, endIndex };
}

interface BandInfo {
  node: Node;
  section: number;
  atSectionStart: boolean;
  dataDependent: boolean;
  /** Index among the section's header runs or footers, and how many there are. */
  position: number;
  count: number;
}

function ordinal(n: number): string {
  return n === 1 ? 'first' : n === 2 ? 'second' : n === 3 ? 'third' : `${n}th`;
}

/**
 * Describes, for every header and footer in [doc], which pages it applies to. Sections come
 * from every page break outside a header or footer, whatever conditional wraps it, so the result
 * is the layout when all conditions hold; a band inside a conditional or loop says so.
 */
export function describePageBands(doc: TemplateDocument): Map<NodeId, string> {
  const bands: BandInfo[] = [];
  let section = 0;
  let contentSeen = false;

  const walk = (nodeId: NodeId, dataDependent: boolean) => {
    const node = doc.nodes[nodeId];
    if (!node) return;
    if (node.type === PAGE_BREAK_TYPE) {
      section += 1;
      contentSeen = false;
      return;
    }
    if (isPageBand(node.type)) {
      bands.push({
        node,
        section,
        atSectionStart: !contentSeen,
        dataDependent,
        position: 0,
        count: 0,
      });
      return; // band content is page furniture, not flow
    }
    const inner = dataDependent || DATA_DEPENDENT_TYPES.has(node.type);
    for (const childId of childrenOf(doc, node)) walk(childId, inner);
    if (!NON_CONTENT_TYPES.has(node.type)) contentSeen = true;
  };
  walk(doc.root, false);
  const sectionCount = section + 1;

  const headersAtStart = (s: number) =>
    bands.filter((b) => b.node.type === PAGE_HEADER_TYPE && b.section === s && b.atSectionStart);
  const footersIn = (s: number) =>
    bands.filter((b) => b.node.type === PAGE_FOOTER_TYPE && b.section === s);

  const labels = new Map<NodeId, string>();
  const suffix = (b: BandInfo) => (b.dataDependent ? ' · depends on data' : '');

  for (let s = 0; s < sectionCount; s += 1) {
    const starts = headersAtStart(s);
    starts.forEach((b, i) => {
      const where =
        starts.length === 1
          ? 'from this page'
          : i === starts.length - 1
            ? `from the section's ${ordinal(i + 1)} page`
            : `${ordinal(i + 1)} page of section`;
      labels.set(b.node.id, `${where}${suffix(b)}`);
    });

    const footers = footersIn(s);
    const coversAbove = s > 0 && footers.length > 0 && footersIn(s - 1).length === 0;
    const adjacent = footersAdjacent(
      doc,
      footers.map((f) => f.node.id),
    );
    footers.forEach((b, i) => {
      let where =
        footers.length === 1
          ? 'this section'
          : i === footers.length - 1
            ? `from the section's ${ordinal(i + 1)} page`
            : `${ordinal(i + 1)} page of section`;
      if (coversAbove && i === footers.length - 1) where += ' and the sections above';
      if (!adjacent) where += ' · not next to the section’s other footer';
      labels.set(b.node.id, `${where}${suffix(b)}`);
    });
  }

  for (const b of bands) {
    if (b.node.type === PAGE_HEADER_TYPE && !b.atSectionStart) {
      labels.set(b.node.id, `from the next page${suffix(b)}`);
    }
  }
  return labels;
}

/** Whether [footerIds] are adjacent children of one slot, as the validator's warning requires. */
function footersAdjacent(doc: TemplateDocument, footerIds: NodeId[]): boolean {
  if (footerIds.length < 2) return true;
  const slot = Object.values(doc.slots).find((candidate) =>
    candidate.children.includes(footerIds[0]),
  );
  if (!slot || !footerIds.every((id) => slot.children.includes(id))) return false;
  const indices = footerIds.map((id) => slot.children.indexOf(id)).sort((a, b) => a - b);
  return indices[indices.length - 1] - indices[0] === indices.length - 1;
}

const labelCache = new WeakMap<TemplateDocument, Map<NodeId, string>>();

/** [describePageBands], computed once per document snapshot. */
export function pageBandLabel(doc: TemplateDocument, nodeId: NodeId): string | undefined {
  let labels = labelCache.get(doc);
  if (!labels) {
    labels = describePageBands(doc);
    labelCache.set(doc, labels);
  }
  return labels.get(nodeId);
}
