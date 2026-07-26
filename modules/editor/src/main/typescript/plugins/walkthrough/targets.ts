// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import type { EditorUiAnchorSelector } from '../../ui/editor-ui-anchors.js';

/** Walkthrough-owned anchors for elements rendered by the plugin itself. */
export const WALKTHROUGH_ANCHORS = {
  guideTrigger: 'guide-trigger',
} as const;

type WalkthroughAnchor = (typeof WALKTHROUGH_ANCHORS)[keyof typeof WALKTHROUGH_ANCHORS];
type WalkthroughAnchorSelector = `[data-walkthrough-anchor~="${WalkthroughAnchor}"]`;
type EpistolaElementTag = keyof HTMLElementTagNameMap & `epistola-${string}`;

/**
 * Closed grammar for walkthrough targets: editor extension anchors,
 * walkthrough-owned anchors, or registered Epistola custom elements.
 */
export type TourTarget = EditorUiAnchorSelector | WalkthroughAnchorSelector | EpistolaElementTag;
