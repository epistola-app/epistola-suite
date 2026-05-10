/**
 * Typed contract for editor feature flags.
 *
 * The host page resolves these from the backend's tenant-aware feature
 * toggle service and forwards them to `mountEditor`. The engine exposes
 * them via `engine.isFeatureEnabled(flag)`; consumers consult the engine
 * directly rather than threading boolean props through every component.
 *
 * To add a new flag: add a field here, register the matching key in the
 * backend's `KnownFeatures`, and surface it on the host (Thymeleaf model
 * → window global → `mountEditor` options). The compile-time union on
 * `isFeatureEnabled` then catches typos at the call site.
 */
export interface EditorFeatureFlags {
  /**
   * When true, the stencil author can declare typed parameters and the
   * picker/inspector show binding affordances. When false, all parameter
   * authoring UI is hidden — existing parameter data still renders but
   * cannot be edited.
   */
  stencilParameters?: boolean;
}

export type EditorFeatureFlag = keyof EditorFeatureFlags;
