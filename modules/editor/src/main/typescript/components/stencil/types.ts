// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Callback types for stencil operations.
 *
 * The editor never calls endpoints directly — the hosting app provides
 * implementations via EditorOptions.stencilOptions, following the same
 * delegation pattern used for SaveFn, FetchPreviewFn, and AssetPickerCallbacks.
 *
 * A stencil has one working copy and no versions (contract ADRs 0002 and
 * 0006): a template uses it by reference, the editor shows the working copy
 * inlined, and editing a stencil saves its working copy, which every template
 * using it then shows. Marking it ready lets the next catalog release capture it.
 */

import type { TemplateDocument } from '../../types/index.js';
import type { JsonSchema } from '../../data-contract/types.js';

/** Names a stencil within a tenant. */
export interface StencilRef {
  stencilKey: string;
  catalogKey: string;
}

/** Summary of a stencil for browse/search results. */
export interface StencilSummary {
  stencilKey: string;
  catalogKey: string;
  name: string;
  description?: string;
  tags: string[];
}

/** A stencil's working copy, as an instance inlines it. */
export interface StencilWorkingCopy {
  ref: StencilRef;
  name: string;
  /** False for a stencil of a read-only (subscribed or imported) catalog. */
  editable: boolean;
  /**
   * The stencil's content in stored form: instances of other stencils in it
   * name their stencil and carry their fills; the editor expands them.
   */
  content: TemplateDocument;
  /** The parameters an instance binds; absent when the stencil takes none. */
  parameterSchema?: JsonSchema;
  /** The stencil's content also sees the using template's data (ADR 0006 part 5). */
  readsTemplateData: boolean;
}

/** What saving a stencil's working copy writes. */
export interface StencilContentUpdate {
  /** The stencil's content in stored form (nested instances by reference). */
  content: TemplateDocument;
  /** The stencil's parameters; null when it takes none (clears a stored schema). */
  parameterSchema: JsonSchema | null;
}

/** Search/browse available stencils. */
export type SearchStencilsFn = (query: string) => Promise<StencilSummary[]>;

/** Read a stencil's working copy; null when there is no such stencil. */
export type GetStencilFn = (ref: StencilRef) => Promise<StencilWorkingCopy | null>;

/** Create a new stencil with an empty working copy, in the document's catalog. */
export type CreateStencilFn = (stencilKey: string, name: string) => Promise<StencilRef>;

/** Save a stencil's working copy. */
export type SaveStencilFn = (ref: StencilRef, update: StencilContentUpdate) => Promise<void>;

/**
 * Mark a stencil's working copy ready, so the next catalog release captures
 * it. Rejects with an Error whose message says why when the stencil cannot be
 * marked ready.
 */
export type MarkStencilReadyFn = (ref: StencilRef) => Promise<void>;

/** All stencil-related callbacks provided by the hosting app. */
export interface StencilCallbacks {
  /**
   * The catalog of the document being edited: what an instance without a
   * `catalogKey` refers to, and where a new stencil is created.
   */
  catalogKey: string;
  searchStencils: SearchStencilsFn;
  getStencil: GetStencilFn;
  createStencil?: CreateStencilFn;
  saveStencil?: SaveStencilFn;
  markStencilReady?: MarkStencilReadyFn;
}
