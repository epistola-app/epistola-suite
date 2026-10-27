// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Collapsing block margins, as the renderer's current defaults (V8) apply them
 * (`BlockMarginCollapse` in the generation module): a container's bottom margin and its last
 * child's become one, the larger, kept on the container; the same for the top margin and the
 * first child's. Padding or a border on that side, or (for the bottom) a height or minimum
 * height, keeps them apart. Siblings keep their own margins.
 *
 * The canvas wraps each block in its own chrome, so the browser does not collapse margins by
 * itself; the editor applies the rule to the resolved styles instead, so a draft looks as it
 * renders.
 */

type Side = 'Top' | 'Bottom';

/** What the collapse needs to know about the document, kept apart from the engine for testing. */
export interface MarginTree {
  /** The node's resolved styles before any collapse. */
  ownStyles(nodeId: string): Record<string, unknown>;
  /** The node's type. */
  type(nodeId: string): string | undefined;
  /** The node's children in order, across its slots. */
  children(nodeId: string): readonly string[];
  /** The node's parent node, if any. */
  parent(nodeId: string): string | undefined;
  /** Points per `sp` token. */
  spacingUnit: number;
}

const COLLAPSING_TYPES = new Set(['container']);

/** The node's resolved styles with its vertical margins collapsed against its parent and children. */
export function collapseMargins(
  nodeId: string,
  tree: MarginTree,
  styles: Record<string, unknown>,
): Record<string, unknown> {
  let result = styles;
  for (const side of ['Top', 'Bottom'] as const) {
    const key = `margin${side}`;
    const value = collapsedAway(nodeId, side, tree) ? 0 : effectiveMargin(nodeId, side, tree);
    const own = toPoints(styles[key], tree.spacingUnit);
    if (value != null && own != null && value !== own) {
      result = { ...result, [key]: `${value}pt` };
    } else if (value === 0 && own == null && styles[key] != null) {
      result = { ...result, [key]: '0pt' };
    }
  }
  return result;
}

/** Whether this node's margin on [side] has moved up into its parent's. */
function collapsedAway(nodeId: string, side: Side, tree: MarginTree): boolean {
  const parent = tree.parent(nodeId);
  if (parent === undefined || !collapses(parent, side, tree)) return false;
  const siblings = tree.children(parent);
  const edge = side === 'Bottom' ? siblings[siblings.length - 1] : siblings[0];
  if (edge !== nodeId) return false;
  const margin = effectiveMargin(nodeId, side, tree);
  return margin != null && margin > 0;
}

/** The node's margin on [side] after taking its edge child's, in points; null when not comparable. */
function effectiveMargin(nodeId: string, side: Side, tree: MarginTree): number | null {
  const own = toPoints(tree.ownStyles(nodeId)[`margin${side}`], tree.spacingUnit);
  if (own == null) return null;
  if (!collapses(nodeId, side, tree)) return own;
  const children = tree.children(nodeId);
  const edge = side === 'Bottom' ? children[children.length - 1] : children[0];
  if (edge === undefined) return own;
  const child = effectiveMargin(edge, side, tree);
  if (child == null || child <= 0) return own;
  return Math.max(own, child);
}

function collapses(nodeId: string, side: Side, tree: MarginTree): boolean {
  const type = tree.type(nodeId);
  if (type === undefined || !COLLAPSING_TYPES.has(type)) return false;
  const styles = tree.ownStyles(nodeId);
  if ((toPoints(styles[`padding${side}`], tree.spacingUnit) ?? 0) > 0) return false;
  if (borderWidth(styles[`border${side}`], tree.spacingUnit) > 0) return false;
  if (side === 'Bottom' && (styles.minHeight != null || styles.height != null)) return false;
  return true;
}

function borderWidth(value: unknown, spacingUnit: number): number {
  if (value == null) return 0;
  if (typeof value === 'object') {
    return toPoints((value as Record<string, unknown>).width, spacingUnit) ?? 0;
  }
  const match = /(\d+(?:\.\d+)?)(pt|px|sp)?/.exec(String(value));
  if (!match) return 0;
  return toPoints(match[0], spacingUnit) ?? 0;
}

/** A length in points; 0 when unset, null when it is in a unit that cannot be compared. */
export function toPoints(value: unknown, spacingUnit: number): number | null {
  if (value == null || value === '') return 0;
  if (typeof value === 'number') return value;
  const match = /^\s*(-?\d+(?:\.\d+)?)\s*(pt|px|sp)?\s*$/.exec(String(value));
  if (!match) return null;
  const n = parseFloat(match[1]);
  switch (match[2]) {
    case 'sp':
      return n * spacingUnit;
    case 'px':
      return n * 0.75;
    default:
      return n;
  }
}
