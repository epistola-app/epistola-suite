// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Stable editor-owned DOM anchors for optional UI extensions.
 *
 * Core components stamp these values in the space-separated `data-editor-anchor`
 * attribute. Extensions may target them without depending on component-internal
 * classes, ids, or test-only selectors.
 */
export const EDITOR_UI_ANCHORS = {
  /** The live-PDF preview toggle in the toolbar. */
  previewToggle: 'preview-toggle',
  /** The toolbar's right-hand tool cluster. */
  toolbarTools: 'toolbar-tools',
  /** Sidebar tab: the block palette. */
  tabBlocks: 'tab-blocks',
  /** Sidebar tab: the document structure tree. */
  tabStructure: 'tab-structure',
  /** Sidebar tab: the inspector. */
  tabInspector: 'tab-inspector',
  /** Document inspector: the Page Settings section. */
  pageSettings: 'page-settings',
  /** Document inspector: the Document Styles section. */
  documentStyles: 'document-styles',
  /** Node inspector: the Style Preset section. */
  stylePreset: 'style-preset',
  /** Node inspector: the per-block Styles section. */
  blockStyles: 'block-styles',
  /** Node inspector: the Delete Block section. */
  blockDelete: 'block-delete',
  /** Any block on the canvas. */
  canvasBlock: 'canvas-block',
  /** The currently selected canvas block. */
  selectedBlock: 'selected-block',
  /** The Text item in the block palette. */
  paletteItemText: 'palette-item-text',
} as const;

export type EditorUiAnchor = (typeof EDITOR_UI_ANCHORS)[keyof typeof EDITOR_UI_ANCHORS];

/** Selector grammar for a stable editor UI anchor. */
export type EditorUiAnchorSelector = `[data-editor-anchor~="${EditorUiAnchor}"]`;
