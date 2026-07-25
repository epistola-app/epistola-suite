// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * The stable `data-tour` anchor vocabulary, shared between the editor components
 * (which stamp the attributes) and the walkthrough chapters (which spotlight them).
 *
 * A tour target is either a hook from this table or a registered custom element —
 * see {@link TourTarget}; a component's internal class names or ids do not
 * typecheck. Those rename freely, and `skipMissingElement` would turn a renamed
 * target into a silently dropped step with no warning and no failing test.
 *
 * `data-tour` holds a **space-separated word list** (like `class`), matched with
 * `[data-tour~="…"]` — so one element can carry several hooks (the canvas block
 * is always `canvas-block` and additionally `selected-block` while selected).
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
  /** Any block on the canvas (every block carries this hook). */
  canvasBlock: 'canvas-block',
  /** The currently selected canvas block (added alongside `canvas-block`). */
  selectedBlock: 'selected-block',
  /** The Text item in the block palette (`palette-item-<type>` family). */
  paletteItemText: 'palette-item-text',
} as const;

export type TourHook = (typeof TOUR_HOOKS)[keyof typeof TOUR_HOOKS];

/**
 * A *registered* epistola custom element, derived from the tag-name map — every
 * component's `declare global { interface HTMLElementTagNameMap … }` entry feeds
 * this automatically. A typo'd or unregistered tag is a compile error, and a new
 * component becomes targetable the moment it registers.
 */
export type EpistolaElementTag = keyof HTMLElementTagNameMap & `epistola-${string}`;

/**
 * The closed grammar of tour step targets. Everything a step may spotlight is
 * either a hook selector (written literally — `'[data-tour~="block-styles"]'`;
 * the template-literal union rejects unknown hook names and wrong syntax, with
 * autocompletion) or a registered epistola element. A component's internal
 * class names, ids, or ad-hoc attribute selectors simply do not typecheck,
 * which is the "never target internals" rule enforced by the compiler instead
 * of by review. Every arm is verified: hooks by the stamping test, elements by
 * the tag-name map. There is deliberately no selector-builder helper — the type
 * alone is the contract, and one mechanism beats two.
 */
export type TourTarget = `[data-tour~="${TourHook}"]` | EpistolaElementTag;
