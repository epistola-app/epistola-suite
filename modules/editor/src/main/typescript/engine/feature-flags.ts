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
 * `KnownFeatures`, and surface it on the host (Thymeleaf model -> JSON config
 * -> `mountEditor` options). The compile-time union on `isFeatureEnabled` then
 * catches typos at the call site.
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
}

export type EditorFeatureFlag = keyof EditorFeatures;
