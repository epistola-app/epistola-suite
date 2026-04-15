/**
 * Callback types for stencil operations.
 *
 * The editor never calls endpoints directly — the hosting app provides
 * implementations via EditorOptions.stencilOptions, following the same
 * delegation pattern used for SaveFn, FetchPreviewFn, and AssetPickerCallbacks.
 */

/** Summary of a stencil for browse/search results. */
export interface StencilSummary {
  id: string;
  catalogKey?: string;
  name: string;
  description?: string;
  tags: string[];
  latestPublishedVersion: number | null;
  /** Highest version number (any status). Used as fallback when no published version exists. */
  latestVersion: number | null;
}

/** A specific stencil version with its content. */
export interface StencilVersionInfo {
  stencilId: string;
  stencilName: string;
  version: number;
  /** The template document fragment (nodes + slots) to embed. */
  content: import('../../types/index.js').TemplateDocument;
}

/** Version summary for the version picker. */
export interface StencilVersionSummary {
  version: number;
  status: 'draft' | 'published' | 'archived';
  createdAt: string;
  publishedAt?: string;
}

/** Search/browse available stencils. */
export type SearchStencilsFn = (query: string) => Promise<StencilSummary[]>;

/** List all versions for a stencil (for the version picker). */
export type ListStencilVersionsFn = (
  stencilId: string,
  catalogKey?: string,
) => Promise<StencilVersionSummary[]>;

/** Fetch a specific published stencil version's content. */
export type GetStencilVersionFn = (
  stencilId: string,
  version: number,
  catalogKey?: string,
) => Promise<StencilVersionInfo | null>;

/** Check which stencil instances in the document have newer versions available. */
export type CheckStencilUpgradesFn = (
  refs: Array<{ stencilId: string; version: number }>,
) => Promise<Array<{ stencilId: string; currentVersion: number; latestVersion: number }>>;

/** Create a new stencil entity with an empty draft. Returns the stencilId and draft version. */
export type CreateStencilFn = (
  slug: string,
  name: string,
) => Promise<{ stencilId: string; version: number }>;

/** Push updated content back to a stencil as a new draft version. */
export type UpdateStencilFn = (
  stencilId: string,
  content: import('../../types/index.js').TemplateDocument,
  catalogKey?: string,
) => Promise<{ version: number }>;

/** Ensure a draft exists for a stencil (creates one if needed). */
export type StartEditingFn = (
  stencilId: string,
  catalogKey?: string,
) => Promise<{ draftVersion: number }>;

/** Publish a specific version. */
export type PublishDraftFn = (
  stencilId: string,
  version: number,
  catalogKey?: string,
) => Promise<{ version: number }>;

/** All stencil-related callbacks provided by the hosting app. */
export interface StencilCallbacks {
  searchStencils: SearchStencilsFn;
  listVersions: ListStencilVersionsFn;
  getStencilVersion: GetStencilVersionFn;
  checkUpgrades?: CheckStencilUpgradesFn;
  createStencil?: CreateStencilFn;
  updateStencil?: UpdateStencilFn;
  startEditing?: StartEditingFn;
  publishDraft?: PublishDraftFn;
}
