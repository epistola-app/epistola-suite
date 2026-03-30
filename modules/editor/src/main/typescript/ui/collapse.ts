/**
 * Collapse helpers for the editor canvas.
 *
 * Pure functions that determine collapsibility and child counts
 * for container blocks in the template document.
 */
import type { TemplateDocument, NodeId } from '../types/index.js';

/**
 * Returns true if a node can be collapsed (has child slots and is not the root).
 */
export function isCollapsible(doc: TemplateDocument, nodeId: NodeId): boolean {
  const node = doc.nodes[nodeId];
  if (!node) return false;
  return node.slots.length > 0 && nodeId !== doc.root;
}

/**
 * Counts the total number of direct children across all slots of a node.
 */
export function countChildren(doc: TemplateDocument, nodeId: NodeId): number {
  const node = doc.nodes[nodeId];
  if (!node) return 0;
  let count = 0;
  for (const slotId of node.slots) {
    const slot = doc.slots[slotId];
    if (slot) count += slot.children.length;
  }
  return count;
}
