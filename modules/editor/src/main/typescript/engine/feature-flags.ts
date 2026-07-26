// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Typed contract for editor feature state.
 *
 * The host page resolves these from the backend's tenant-aware feature
 * toggle service and forwards them to `mountEditor`. Each feature carries
 * resolved enablement plus optional UI metadata from the backend feature
 * registry. The engine exposes booleans via `engine.isFeatureEnabled(flag)`;
 * consumers consult the engine directly rather than threading boolean props
 * through every component.
 *
 * To add one: add a field below, register the matching key in the backend's
 * `KnownFeatures`, and add the pair to `EDITOR_FEATURES` in the backend's
 * `DocumentTemplateHandler` (Thymeleaf model -> JSON config -> `mountEditor`
 * options). The compile-time union on `isFeatureEnabled` catches typos at the
 * call site; across the language boundary nothing can, so the field name MUST
 * be the lowerCamelCase form of the backend feature key (`editor-walkthrough`
 * -> `editorWalkthrough`) — the Kotlin side pins that convention with
 * `EditorFeatureFlagsTest`.
 */
export interface EditorFeatureBadge {
  label: string;
  className: string;
}

export interface EditorFeatureConfig {
  enabled: boolean;
  badge?: EditorFeatureBadge | null;
}

export interface EditorFeatures {
  quality?: EditorFeatureConfig;
  aiChat?: EditorFeatureConfig;
  /**
   * Guided, driver.js-driven walkthrough of the editor for first-time users.
   * Backed by the `editor-walkthrough` backend feature toggle (alpha, off by
   * default). When absent/disabled the walkthrough code is never even
   * downloaded (it lives behind a dynamic import gated on this flag).
   */
  editorWalkthrough?: EditorFeatureConfig;
}

export type EditorFeatureFlag = keyof EditorFeatures;
