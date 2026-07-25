/** Shared helpers for tour step side-effects (benign setup only — chapters are passive). */

import type { EditorEngine } from '../../../engine/EditorEngine.js';
import { PAGE_FOOTER_TYPE } from '../../../engine/registry.js';

/** The editor host exposes its engine as a property; read it without importing the element. */
function editorEngine(host: HTMLElement): EditorEngine | undefined {
  return (host as HTMLElement & { engine?: EditorEngine }).engine;
}

/** Switch the sidebar to a tab by id (`blocks` | `structure` | `inspector`). No-op if absent. */
export function clickSidebarTab(host: HTMLElement, tabId: string): void {
  const el = host.querySelector(`[data-tour="tab-${tabId}"]`);
  if (el instanceof HTMLElement) el.click();
}

/** Whether the document has at least one block on the canvas. */
export function hasAnyBlock(host: HTMLElement): boolean {
  return host.querySelector('.canvas-block[data-node-id]') !== null;
}

/** The id of the first top-level block, read from the engine model (not the DOM). */
function firstBlockId(engine: EditorEngine) {
  const doc = engine.doc;
  const root = doc.nodes[doc.root];
  for (const slotId of root?.slots ?? []) {
    const first = doc.slots[slotId]?.children[0];
    if (first) return first;
  }
  return undefined;
}

/**
 * Select the first block (non-destructive — just changes selection), so a chapter that
 * spotlights the selected block's inspector has something to point at. Reads the first
 * block from the engine *model* rather than the DOM, so it works even when a block was
 * just added and the canvas hasn't repainted yet. Selecting also auto-switches the
 * sidebar to the Inspector. No-op if there's no block or no engine.
 */
export function selectFirstBlock(host: HTMLElement): void {
  const engine = editorEngine(host);
  if (!engine) return;
  const id = firstBlockId(engine);
  if (id) engine.selectNode(id);
}

/**
 * Add a starter Text block to an empty canvas, so the block-centric chapters (editing,
 * styling) have something to work with. Idempotent: a no-op when the canvas already has
 * a block, so replaying the building chapter never stacks duplicates. Mirrors the palette
 * insert (append, but before a page footer if one exists) and selects the new block. The
 * insert is a normal undoable edit — the reader can Ctrl+Z it like any other.
 */
export function addStarterBlock(host: HTMLElement): void {
  if (hasAnyBlock(host)) return;
  const engine = editorEngine(host);
  if (!engine) return;

  const doc = engine.doc;
  const root = doc.nodes[doc.root];
  const targetSlotId = root?.slots[0];
  if (!targetSlotId) return;
  const targetSlot = doc.slots[targetSlotId];
  if (!targetSlot) return;

  const footerIndex = targetSlot.children.findIndex(
    (id) => doc.nodes[id]?.type === PAGE_FOOTER_TYPE,
  );
  const index = footerIndex >= 0 ? footerIndex : targetSlot.children.length;

  const { node, slots, extraNodes } = engine.registry.createNode('text');
  const result = engine.dispatch({
    type: 'InsertNode',
    node,
    slots,
    targetSlotId,
    index,
    _restoreNodes: extraNodes,
  });
  if (result.ok) engine.selectNode(node.id);
}

/**
 * Show the document-level Inspector — Page Settings + Document Styles — which only
 * renders when nothing is selected. Deselect first, then open the Inspector tab.
 * Deselecting to `null` does not trigger the sidebar's select-driven auto-switch
 * (that only fires on selecting a node), so the tab click here is not clobbered.
 * Both parts no-op safely if the engine/tab is absent.
 */
export function showDocumentInspector(host: HTMLElement): void {
  editorEngine(host)?.selectNode(null);
  clickSidebarTab(host, 'inspector');
}

/**
 * Open the live PDF preview if it isn't already, so edits are visible as they
 * render. No-op when there's no preview (the toggle is absent) or it's already open.
 */
export function openPreview(host: HTMLElement): void {
  const toggle = host.querySelector('[data-tour="preview-toggle"]');
  if (toggle instanceof HTMLElement && !toggle.classList.contains('active')) {
    toggle.click();
  }
}
