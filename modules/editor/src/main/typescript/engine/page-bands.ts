/**
 * Page headers and footers placed anywhere in the flow (#1020).
 *
 * Page breaks divide the document into sections. A header applies to what comes after it: at
 * the start of a section it applies from that page (several there in a row form a first-page
 * variant), after content from the next page. A footer applies from the page it lands on until
 * another lands; a later footer on a page that already has one is skipped, and the first footer
 * also covers the pages before it. The renderer (`PageBandSchedule` in modules/generation) is the
 * authority; this module mirrors it statically for the editor, which cannot evaluate conditionals
 * or loops, or know where pages end.
 */

import type { Node, NodeId, SlotId, TemplateDocument } from '../types/index.js';
import type { DocumentIndexes } from './indexes.js';

/** Mirrors `BlockHint` in registry.ts, which imports this module. */
export interface PageBandHint {
  text: string;
  tone: 'info' | 'warning';
}

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
  /** For a footer: whether a page break, or any content, came since the previous footer. */
  breakSincePreviousFooter: boolean;
  contentSincePreviousFooter: boolean;
}

function ordinal(n: number): string {
  return n === 1 ? 'first' : n === 2 ? 'second' : n === 3 ? 'third' : `${n}th`;
}

/**
 * Describes, for every header and footer in [doc], which pages it applies to. Sections come
 * from every page break outside a header or footer, whatever conditional wraps it, so the result
 * is the layout when all conditions hold; a band inside a conditional or loop says so. Where a
 * page ends is not known here, so a footer that may share its page with the one before it says so.
 */
export function describePageBands(doc: TemplateDocument): Map<NodeId, PageBandHint> {
  const bands: BandInfo[] = [];
  let section = 0;
  let contentSeen = false;
  let breakSinceFooter = false;
  let contentSinceFooter = false;

  const walk = (nodeId: NodeId, dataDependent: boolean) => {
    const node = doc.nodes[nodeId];
    if (!node) return;
    if (node.type === PAGE_BREAK_TYPE) {
      section += 1;
      contentSeen = false;
      breakSinceFooter = true;
      return;
    }
    if (isPageBand(node.type)) {
      bands.push({
        node,
        section,
        atSectionStart: !contentSeen,
        dataDependent,
        breakSincePreviousFooter: breakSinceFooter,
        contentSincePreviousFooter: contentSinceFooter,
      });
      if (node.type === PAGE_FOOTER_TYPE) {
        breakSinceFooter = false;
        contentSinceFooter = false;
      }
      return; // band content is page furniture, not flow
    }
    const inner = dataDependent || DATA_DEPENDENT_TYPES.has(node.type);
    for (const childId of childrenOf(doc, node)) walk(childId, inner);
    if (!NON_CONTENT_TYPES.has(node.type)) {
      contentSeen = true;
      contentSinceFooter = true;
    }
  };
  walk(doc.root, false);
  const sectionCount = section + 1;

  const headersAtStart = (s: number) =>
    bands.filter((b) => b.node.type === PAGE_HEADER_TYPE && b.section === s && b.atSectionStart);

  const hints = new Map<NodeId, PageBandHint>();
  const onlyWhenRendered = (b: BandInfo) =>
    b.dataDependent
      ? ' It is inside a conditional or loop, so this holds only when it renders.'
      : '';
  const set = (b: BandInfo, text: string, tone: PageBandHint['tone'] = 'info') =>
    hints.set(b.node.id, { text: text + onlyWhenRendered(b), tone });

  for (let s = 0; s < sectionCount; s += 1) {
    const starts = headersAtStart(s);
    starts.forEach((b, i) => {
      if (starts.length === 1) {
        set(b, 'Applies from this page on, until another header takes over.');
      } else if (i === starts.length - 1) {
        set(
          b,
          `Applies from the ${ordinal(i + 1)} page of its section on, until another header takes over.`,
        );
      } else {
        set(
          b,
          `Applies to the ${ordinal(i + 1)} page of its section only; the next header takes the page after it.`,
        );
      }
    });
  }

  const footers = bands.filter((b) => b.node.type === PAGE_FOOTER_TYPE);
  footers.forEach((b, i) => {
    if (i === 0) {
      set(
        b,
        'Applies from the page it lands on, and to the pages before it, until another footer lands.',
      );
    } else if (b.breakSincePreviousFooter) {
      set(b, 'Applies from the page it lands on, until another footer lands.');
    } else if (b.contentSincePreviousFooter) {
      set(
        b,
        'Applies from the page it lands on, but only if the footer before it did not land on that page too: a page keeps the first footer that lands on it.',
        'warning',
      );
    } else {
      set(
        b,
        'Skipped: it lands on the same page as the footer before it, and a page keeps the first footer that lands on it. Put a page break between them, or remove one.',
        'warning',
      );
    }
  });

  for (const b of bands) {
    if (b.node.type === PAGE_HEADER_TYPE && !b.atSectionStart) {
      set(
        b,
        'Takes over from the page after the one it lands on, because content comes before it in its section.',
      );
    }
  }
  return hints;
}

const hintCache = new WeakMap<TemplateDocument, Map<NodeId, PageBandHint>>();

/** [describePageBands], computed once per document snapshot. */
export function pageBandHint(doc: TemplateDocument, nodeId: NodeId): PageBandHint | undefined {
  let hints = hintCache.get(doc);
  if (!hints) {
    hints = describePageBands(doc);
    hintCache.set(doc, hints);
  }
  return hints.get(nodeId);
}
