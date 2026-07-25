/**
 * The stable `data-tour` anchor vocabulary, shared between the editor components
 * (which stamp the attributes) and the walkthrough chapters (which spotlight them).
 *
 * A tour target must be one of: a `data-tour` hook from this table, a custom-element
 * tag (`epistola-canvas`), or a `data-testid` (both are stable vocabularies in their
 * own right) — never a component's internal class names or ids. Those rename freely,
 * and `skipMissingElement` turns a renamed target into a silently dropped step with
 * no warning and no failing test.
 *
 * Components import this table and stamp `data-tour=${TOUR_HOOKS.x}` bindings, so
 * a hook rename is atomic and compile-checked across producers and tours. What the
 * compiler cannot prove is that an attribute is actually *stamped* somewhere —
 * `tour-hooks.test.ts` covers that: every hook here must appear in a component's
 * `data-tour` binding, so deleting or forgetting a stamp fails the build instead
 * of quietly degrading a chapter. (This module is dependency-free on purpose: it
 * is imported by always-loaded components and must never pull walkthrough code —
 * which is also why it lives in `ui/`, not `ui/walkthrough/`.)
 */
export const TOUR_HOOKS = {
  /** The Guide button (the walkthrough launcher itself, in the toolbar). */
  guideTrigger: 'guide-trigger',
  /** The toolbar's Save button. (Not yet targeted by a chapter.) */
  save: 'save',
  /** The live-PDF preview toggle in the toolbar. */
  previewToggle: 'preview-toggle',
  /** The toolbar's right-hand tool cluster (Guide, JSON inspector, shortcuts). */
  toolbarTools: 'toolbar-tools',
  /** Sidebar tab: the block palette. */
  tabBlocks: 'tab-blocks',
  /** Sidebar tab: the document structure tree. */
  tabStructure: 'tab-structure',
  /** Sidebar tab: the inspector. */
  tabInspector: 'tab-inspector',
  /** Document inspector: the Page Settings section (nothing selected). */
  pageSettings: 'page-settings',
  /** Document inspector: the Document Styles section (nothing selected). */
  documentStyles: 'document-styles',
  /** Node inspector: the Style Preset section (select or free-text fallback). */
  stylePreset: 'style-preset',
  /** Node inspector: the per-block Styles section. */
  blockStyles: 'block-styles',
  /** Node inspector: the Delete Block section. */
  blockDelete: 'block-delete',
  /** The currently selected canvas block (stamped only while selected). */
  selectedBlock: 'selected-block',
} as const;

export type TourHook = (typeof TOUR_HOOKS)[keyof typeof TOUR_HOOKS];

/** CSS selector for a tour hook. */
export function tourHook(hook: TourHook): string {
  return `[data-tour="${hook}"]`;
}
