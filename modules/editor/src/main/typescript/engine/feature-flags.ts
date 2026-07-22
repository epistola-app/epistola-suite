/**
 * Typed contract for editor feature flags.
 *
 * The host page resolves these from the backend's tenant-aware feature
 * toggle service and forwards them to `mountEditor`. The engine exposes
 * them via `engine.isFeatureEnabled(flag)`; consumers consult the engine
 * directly rather than threading boolean props through every component.
 *
 * To add one: add a field below, register the matching key in the backend's
 * `KnownFeatures`, and surface it on the host (Thymeleaf model → JSON config
 * → `mountEditor` options). The compile-time union on `isFeatureEnabled` then
 * catches typos at the call site.
 */
export interface EditorFeatureFlags {
  quality?: boolean;
  aiChat?: boolean;
}

export type EditorFeatureFlag = keyof EditorFeatureFlags;
