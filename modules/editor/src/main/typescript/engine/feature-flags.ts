/**
 * Typed contract for editor feature flags.
 *
 * The host page resolves these from the backend's tenant-aware feature
 * toggle service and forwards them to `mountEditor`. The engine exposes
 * them via `engine.isFeatureEnabled(flag)`; consumers consult the engine
 * directly rather than threading boolean props through every component.
 *
 * To add a flag: add a field below, register the matching key in the backend's
 * `KnownFeatures`, and surface it on the host (Thymeleaf model → window
 * global → `mountEditor` options). The compile-time union on
 * `isFeatureEnabled` then catches typos at the call site.
 */
export interface EditorFeatureFlags {
  /**
   * Guided, driver.js-driven walkthrough of the editor for first-time users.
   * Backed by the `editor-walkthrough` backend feature toggle (alpha, off by
   * default). When absent/false the walkthrough code is never even downloaded
   * (it lives behind a dynamic import gated on this flag).
   */
  editorWalkthrough?: boolean;
}

export type EditorFeatureFlag = keyof EditorFeatureFlags;
